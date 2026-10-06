/**
 * The registry walk, checked against the failures it exists to catch.
 *
 * A check that cannot fail is worse than no check, because it also reports success. So every
 * assertion here breaks the list on purpose and demands the walk notice — an "intact list is
 * intact" test on its own would pass against a function that returned `intact: true` always.
 *
 * ⛔ AND EVERY FIXTURE HERE USED TO FABRICATE A TAIL NODE THAT DOES NOT EXIST. They each appended
 * `node(MAX_NEXT, MAX_NEXT)`, because the walk assumed the end-of-list marker was a NODE. On chain
 * it is a VALUE in the last node's `next`, and no node is keyed by it. So the suite was fully green
 * against a list shape that never occurs, while the real page reported `dangling` on the last node
 * of every deployment and blamed the indexer for a block it had not missed.
 *
 * ⚑ Test data that shares the implementation's assumption cannot test that assumption. The
 * fixtures below now end the way the chain does, and `mainnetAsOfToday()` is the real list read
 * off the live indexer so the shape is pinned to something nobody can quietly redefine.
 */
const assert = require("node:assert");

const MAX_NEXT = "ff".repeat(30);
const A = "11".repeat(28);
const B = "55".repeat(28);
const C = "99".repeat(28);

const node = (key, next) => ({
  key, next,
  mintingLogicScript: "", transferLogicScript: "",
  thirdPartyTransferLogicScript: "", unfrackingLogicScript: "",
  globalStatePolicyId: "",
});

/**
 * A healthy three-token registry: sentinel -> A -> B -> C, whose `next` is the marker.
 *
 * Note what is NOT here: a node keyed MAX_NEXT. That is the whole point.
 */
const healthy = () => [node("", A), node(A, B), node(B, C), node(C, MAX_NEXT)];

/**
 * The live mainnet registry, read from
 * https://mainnet-indexer.programmabletokens.xyz/api/v1/registry/nodes/all?protocolParamsId=1
 * on 2026-10-06 (registry policy 484e733d…, tokenCount 2). Three nodes, no materialised tail.
 *
 * This is the list that produced "This view of the registry is incomplete" in production.
 */
const mainnetAsOfToday = () => [
  node("", "01c24df7941f8b5856762fcc8aa0bb61a8c24f0911ed6aef474034d0"),
  node("01c24df7941f8b5856762fcc8aa0bb61a8c24f0911ed6aef474034d0",
       "b025efe5b44b43ed154419c66b0efc9cf148fd98f464e261018c89e8"),
  node("b025efe5b44b43ed154419c66b0efc9cf148fd98f464e261018c89e8", MAX_NEXT),
];

async function main() {
  const { walkRegistry, tokensOf } = await import("./.registry-build/walk.js");
  let failures = 0;
  const check = (label, fn) => {
    try { fn(); console.log(`  OK   ${label}`); }
    catch (e) { failures++; console.log(`  FAIL ${label}\n       ${e.message}`); }
  };

  check("an intact chain reports intact, in list order", () => {
    const r = walkRegistry(healthy());
    assert.ok(r.intact, `expected intact, got: ${r.problems.map((p) => p.kind).join(", ")}`);
    assert.deepStrictEqual(tokensOf(r).map((n) => n.key), [A, B, C]);
  });

  // ---- the shuffle test: order must come from `next`, never from a sort -----
  check("list order comes from the pointers, not from the input order", () => {
    const shuffled = [node(C, MAX_NEXT), node("", A), node(B, C), node(A, B)];
    const r = walkRegistry(shuffled);
    assert.ok(r.intact);
    assert.deepStrictEqual(tokensOf(r).map((n) => n.key), [A, B, C],
      "walk returned input order, so it is sorting rather than following next");
  });

  // ---- the failures it exists for -------------------------------------------
  check("a node the indexer missed is reported, and named", () => {
    const missing = healthy().filter((n) => n.key !== B);
    const r = walkRegistry(missing);
    assert.ok(!r.intact, "a missing node passed as intact");
    const d = r.problems.find((p) => p.kind === "dangling");
    assert.ok(d, `expected a dangling problem, got ${r.problems.map((p) => p.kind).join(", ")}`);
    assert.strictEqual(r.danglingFrom, A, "the break should be anchored at the node that points at the gap");
    assert.ok(d.message.includes(B.slice(0, 8)), "the message must name the missing key");
  });

  check("a node present but unreachable is reported", () => {
    // C is in the set, but B points past it to the terminator.
    const orphaned = [node("", A), node(A, B), node(B, MAX_NEXT), node(C, MAX_NEXT)];
    const r = walkRegistry(orphaned);
    assert.ok(!r.intact);
    assert.ok(r.problems.some((p) => p.kind === "unreachable"),
      `expected unreachable, got ${r.problems.map((p) => p.kind).join(", ")}`);
  });

  check("a backwards pointer is reported", () => {
    const backwards = [node("", B), node(B, A), node(A, MAX_NEXT)];
    const r = walkRegistry(backwards);
    assert.ok(!r.intact);
    assert.ok(r.problems.some((p) => p.kind === "order"),
      `expected an order problem, got ${r.problems.map((p) => p.kind).join(", ")}`);
  });

  check("a cycle terminates the walk instead of hanging", () => {
    const cyclic = [node("", A), node(A, B), node(B, A)];
    const r = walkRegistry(cyclic);
    assert.ok(!r.intact);
    assert.ok(r.problems.some((p) => p.kind === "cycle" || p.kind === "dangling"));
  });

  check("no sentinel is its own diagnosis, not an empty registry", () => {
    const headless = healthy().filter((n) => n.key !== "");
    const r = walkRegistry(headless);
    assert.ok(!r.intact);
    assert.strictEqual(r.problems[0].kind, "no-sentinel");
    assert.ok(/registry\/tokens/.test(r.problems[0].message),
      "the message should point at the likely cause — the endpoint that omits the sentinel");
  });

  check("an empty registry is intact, not broken", () => {
    const r = walkRegistry([node("", MAX_NEXT)]);
    assert.ok(r.intact, `a protocol with no tokens must not look broken: ${r.problems.map((p) => p.kind)}`);
    assert.deepStrictEqual(tokensOf(r), []);
  });

  // ---- the production bug, pinned to the real list -------------------------
  check("THE LIVE MAINNET LIST IS INTACT — the marker is a value, not a missing node", () => {
    const r = walkRegistry(mainnetAsOfToday());
    assert.ok(r.intact,
      `the real mainnet registry must not read as broken, got: ` +
      r.problems.map((p) => `${p.kind}: ${p.message}`).join(" | "));
    assert.strictEqual(r.danglingFrom, null, "the last node must not be reported as dangling");
    assert.deepStrictEqual(tokensOf(r).map((n) => n.key.slice(0, 8)), ["01c24df7", "b025efe5"]);
  });

  check("the last node of ANY list is not mistaken for a gap", () => {
    const r = walkRegistry(healthy());
    assert.ok(!r.problems.some((p) => p.kind === "dangling"),
      "a list ending at the marker reported a dangling pointer");
    // And the diagnosis the bug used to give must not appear for an intact list.
    assert.ok(!r.problems.some((p) => /missed a block/.test(p.message)),
      "an intact list must not accuse the indexer of missing a block");
  });

  check("a list with no terminator at all IS still reported", () => {
    // C's next names a key nobody has, and it is not the marker — a genuine gap.
    const truncated = [node("", A), node(A, B), node(B, C)];
    const r = walkRegistry(truncated);
    assert.ok(!r.intact, "a list whose last `next` names an absent key must not read as intact");
    assert.ok(r.problems.some((p) => p.kind === "dangling"),
      `expected dangling, got ${r.problems.map((p) => p.kind).join(", ")}`);
  });

  check("a materialised terminator node is tolerated, not called unreachable", () => {
    // Some view may yet hand us the marker as a real node; stepping into it must VISIT it.
    const withTail = [...healthy(), node(MAX_NEXT, MAX_NEXT)];
    const r = walkRegistry(withTail);
    assert.ok(r.intact,
      `a materialised tail must still read as intact, got ${r.problems.map((p) => p.kind).join(", ")}`);
    assert.ok(r.reached.has(MAX_NEXT), "the tail node must be reached, or it reports as unreachable");
    assert.deepStrictEqual(tokensOf(r).map((n) => n.key), [A, B, C], "the tail is not a token");
  });

  if (failures > 0) throw new Error(`${failures} walk check(s) failed`);
  console.log("\n  the walk fails on every break it claims to catch");
}

main().catch((e) => { console.error(e); process.exit(1); });
