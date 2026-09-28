/**
 * The registry walk, checked against the failures it exists to catch.
 *
 * A check that cannot fail is worse than no check, because it also reports success. So every
 * assertion here breaks the list on purpose and demands the walk notice — an "intact list is
 * intact" test on its own would pass against a function that returned `intact: true` always.
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

/** A healthy three-token registry: sentinel -> A -> B -> C -> terminator. */
const healthy = () => [
  node("", A), node(A, B), node(B, C), node(C, MAX_NEXT), node(MAX_NEXT, MAX_NEXT),
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
    const shuffled = [node(C, MAX_NEXT), node("", A), node(MAX_NEXT, MAX_NEXT), node(B, C), node(A, B)];
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
    const orphaned = [node("", A), node(A, B), node(B, MAX_NEXT), node(C, MAX_NEXT), node(MAX_NEXT, MAX_NEXT)];
    const r = walkRegistry(orphaned);
    assert.ok(!r.intact);
    assert.ok(r.problems.some((p) => p.kind === "unreachable"),
      `expected unreachable, got ${r.problems.map((p) => p.kind).join(", ")}`);
  });

  check("a backwards pointer is reported", () => {
    const backwards = [node("", B), node(B, A), node(A, MAX_NEXT), node(MAX_NEXT, MAX_NEXT)];
    const r = walkRegistry(backwards);
    assert.ok(!r.intact);
    assert.ok(r.problems.some((p) => p.kind === "order"),
      `expected an order problem, got ${r.problems.map((p) => p.kind).join(", ")}`);
  });

  check("a cycle terminates the walk instead of hanging", () => {
    const cyclic = [node("", A), node(A, B), node(B, A), node(MAX_NEXT, MAX_NEXT)];
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
    const r = walkRegistry([node("", MAX_NEXT), node(MAX_NEXT, MAX_NEXT)]);
    assert.ok(r.intact, `a protocol with no tokens must not look broken: ${r.problems.map((p) => p.kind)}`);
    assert.deepStrictEqual(tokensOf(r), []);
  });

  if (failures > 0) throw new Error(`${failures} walk check(s) failed`);
  console.log("\n  the walk fails on every break it claims to catch");
}

main().catch((e) => { console.error(e); process.exit(1); });
