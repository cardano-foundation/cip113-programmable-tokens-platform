/**
 * The CIP-21 check must name the field a hardware wallet will disagree about — and must not cry
 * wolf on the bytes Evolution actually emits.
 *
 * ⛔ WHY BOTH HALVES MATTER. A conformance check that flags every transaction is worse than none:
 * it trains the operator to ignore it, and the one real violation arrives looking like the noise.
 * Evolution adds tag 258 to the inputs set (measured 2026-10-01 by round-tripping a hand-written
 * body through it), and CIP-21 permits that — "either there are no tags 258 in sets, or there are
 * such tags everywhere". So the Evolution shape must come out CLEAN, and only a MIXTURE is a
 * finding. The check's own first bug was exactly this: it counted the 2-element array inside each
 * input as a bare set, so every conformant transaction looked inconsistent.
 */
const assert = require("node:assert");
const { checkCip21 } = require("./.cip21-build/cip21.js");

let ran = 0;
const AB = "ab".repeat(32);
const CD = "cd".repeat(32);
const INPUT = `82${"5820"}${AB}00`;
const INPUT2 = `82${"5820"}${CD}01`;
const FEE = "021a0002bf20";
const OUTPUTS = `0181824100${"1a000f4240"}`;

const report = (hex) => checkCip21(hex);
const firstMatching = (r, re) => r.violations.find((v) => re.test(v));

// ---- 1. a canonical, untagged body is clean ----
const bodyA = `a300${"81"}${INPUT}${OUTPUTS}${FEE}`;
let r = report(bodyA);
assert.deepStrictEqual(r.violations, [], `canonical body flagged: ${r.violations.join(" | ")}`);
assert.strictEqual(r.tag258Count, 0);
console.log("  OK   a canonical untagged body reports no violations");
ran++;

// ---- 2. THE EVOLUTION SHAPE IS CLEAN — the anti-false-positive test ----
// inputs tagged, and no other set-valued field present. This is what the SDK hands the wallet.
const bodyEvo = `a300d90102${"81"}${INPUT}${OUTPUTS}${FEE}`;
r = report(bodyEvo);
assert.deepStrictEqual(r.violations, [],
  "the shape Evolution actually emits must NOT be flagged — tag 258 is permitted, only a " +
  `mixture is a violation. Got: ${r.violations.join(" | ")}`);
assert.strictEqual(r.tag258Count, 1);
assert.deepStrictEqual(r.taggedSetFields, ["body.inputs"]);
assert.deepStrictEqual(r.bareSetFields, []);
console.log("  OK   a consistently tagged body (the Evolution shape) is conformant");
ran++;

// ---- 3. and a full tx wrapper is walked, not just a bare body ----
r = report(`84${bodyEvo}a0f5f6`);
assert.deepStrictEqual(r.violations, [], `full tx flagged: ${r.violations.join(" | ")}`);
console.log("  OK   a full [body, witnessSet, isValid, auxData] transaction is walked");
ran++;

// ---- 4. MIXED tag 258 is a violation, and names both sides ----
// inputs tagged (field 0), reference_inputs bare (field 18 = 0x12).
const bodyMixed = `a300d90102${"81"}${INPUT}${FEE}12${"81"}${INPUT2}`;
r = report(bodyMixed);
const mix = firstMatching(r, /INCONSISTENT/);
assert.ok(mix, `expected a tag-258 inconsistency; got: ${r.violations.join(" | ")}`);
assert.ok(mix.includes("body.inputs") && mix.includes("body.reference_inputs"),
  `the message must name BOTH sides so the fix is obvious; got: ${mix}`);
console.log("  OK   mixed tag 258 is flagged and names the tagged and bare fields");
ran++;

// ---- 5. consistently tagged across TWO set fields is clean ----
r = report(`a300d90102${"81"}${INPUT}${FEE}12d90102${"81"}${INPUT2}`);
assert.deepStrictEqual(r.violations, [], `consistently tagged flagged: ${r.violations.join(" | ")}`);
assert.strictEqual(r.tag258Count, 2);
console.log("  OK   tags on every set field is the other conformant state");
ran++;

// ---- 6. WITHDRAWALS OUT OF CANONICAL ORDER — the rule CIP-113 is most exposed to ----
// Three withdrawals per programmable transfer (dispatcher, core transfer, module transfer), built
// in the order the builder adds them. CIP-21: "withdrawals also need to be sorted".
const rewardHi = `581df1${"ff".repeat(28)}`;
const rewardLo = `581df1${"00".repeat(28)}`;
r = report(`a2${FEE}05a2${rewardHi}00${rewardLo}00`);
const wd = firstMatching(r, /out of canonical order/);
assert.ok(wd, `expected an ordering violation; got: ${r.violations.join(" | ")}`);
assert.ok(wd.startsWith("body.withdrawals"),
  `the violation must be ATTRIBUTED to withdrawals, not reported as an anonymous map; got: ${wd}`);
console.log("  OK   an unsorted withdrawals map is flagged as body.withdrawals");
ran++;

// ---- 7. a duplicate map key is called a duplicate, not just misordered ----
r = report(`a2${FEE}05a2${rewardLo}00${rewardLo}00`);
assert.ok(firstMatching(r, /DUPLICATE key/),
  `expected a duplicate-key violation; got: ${r.violations.join(" | ")}`);
console.log("  OK   a duplicate withdrawal key is reported as a duplicate");
ran++;

// ---- 8. non-minimal integers ----
r = report(`a200${"81"}${INPUT}021900ff`);
assert.ok(firstMatching(r, /not minimal|shortest encoding/),
  `expected a minimality violation for a uint16 holding 255; got: ${r.violations.join(" | ")}`);
console.log("  OK   a non-minimal integer encoding is flagged");
ran++;

// ---- 9. indefinite lengths ----
r = report(`a2009f${INPUT}ff${FEE}`);
assert.ok(firstMatching(r, /INDEFINITE/),
  `expected an indefinite-length violation; got: ${r.violations.join(" | ")}`);
console.log("  OK   an indefinite-length item is flagged");
ran++;

// ---- 10. the two body entries CIP-21 forbids outright ----
r = report(`a2${FEE}1480`);
assert.ok(firstMatching(r, /proposal_procedures/),
  `expected proposal_procedures to be refused; got: ${r.violations.join(" | ")}`);
r = report(`a20680${FEE}`);
assert.ok(firstMatching(r, /update/),
  `expected \`update\` to be refused; got: ${r.violations.join(" | ")}`);
console.log("  OK   `update` and `proposal_procedures` are refused by name");
ran++;

// ---- 11. a body the walker cannot parse must NOT be reported as conformant ----
// The failure mode that matters: a check that silently passes on bytes it did not understand.
r = report("a3ff");
assert.ok(r.violations.length > 0,
  "truncated/garbage CBOR was reported as conformant — a blind check must say it is blind");
console.log("  OK   unparseable CBOR is never reported as a pass");
ran++;

// ---- 11b. an empty MAP in the body, not just an empty list ----
// ⛔ ASYMMETRIC COVERAGE WAS A FINDING. Removing the empty-LIST half of this rule was killed by the
// test below; removing the empty-MAP half left every suite green. One CIP-21 rule, one of its two
// halves defended.
{
  // body = { 9: {} } — an empty `mint` map, which CIP-21 forbids outright in the body.
  const r = checkCip21("84a109a0a0f5f6");
  assert.ok(firstMatching(r, /empty map/),
    `an empty mint map in the body should be refused; got: ${r.violations.join(" | ")}`);
  assert.ok(r.scopedViolations.some((v) => v.scope === "body"),
    "and it must be scoped `body`, i.e. fatal — not advisory");
  console.log("  OK   an empty optional MAP in the body is flagged, and scoped fatal");
  ran++;
}

// ---- 11c. the legacy-output check does not stop firing at 24 outputs ----
// ⛔ MEASURED HOLE: the count came from `b[i] & 0x1f`, which at 24+ holds the additional-info code,
// not the count. Probed by the audit: 23 outputs -> 23 findings, 24 -> ZERO. The boundary is the
// test, because either side of it alone passes.
{
  // one token-free output in the forbidden [address, [coin, {}]] shape
  const legacyOut = "82" + "581c" + "11".repeat(28) + "82" + "01" + "a0";
  const build = (n) => {
    const header = n < 24 ? (0x80 + n).toString(16).padStart(2, "0") : "98" + n.toString(16).padStart(2, "0");
    return "84" + "a101" + header + legacyOut.repeat(n) + "a0f5f6";
  };
  for (const n of [1, 23, 24, 25]) {
    const r = checkCip21(build(n));
    const hits = r.violations.filter((v) => /token-free output/.test(v)).length;
    assert.strictEqual(hits, n,
      `${n} legacy outputs should produce ${n} findings, got ${hits} — the array-header count is ` +
      `being read as the raw additional-info value again`);
  }
  console.log("  OK   legacy outputs are counted correctly across the 24-element header boundary");
  ran++;
}

// ---- 12. optional empty lists and maps are forbidden IN THE BODY ----
// CIP-21: "optional empty lists and maps must not be included as part of the transaction body or
// its elements", and "HW wallets enforce this in many cases".
r = report(`a300${"81"}${INPUT}${OUTPUTS}${FEE}`.replace(/^a3/, "a4") + "0e80");
assert.ok(firstMatching(r, /empty list/),
  `an empty required_signers set should be refused; got: ${r.violations.join(" | ")}`);
console.log("  OK   an empty optional list in the body is flagged");
ran++;

// ---- 13. but an unsigned tx's EMPTY WITNESS SET is not a violation ----
// The rule is scoped to the body. Every unsigned transaction we build carries `a0` here, so
// applying it transaction-wide would flag all of them — the cry-wolf failure again.
r = report(`84${bodyA}a0f5f6`);
assert.deepStrictEqual(r.violations, [],
  `an unsigned tx's empty witness set must NOT be flagged; got: ${r.violations.join(" | ")}`);
console.log("  OK   an unsigned transaction's empty witness set is not a violation");
ran++;

// ---- 14. the legacy output shape CIP-21 forbids ----
// "[address, [coin, {}]]" instead of "[address, coin]" for a token-free output.
const badOut = `0181824100821a000f4240a0`;
r = report(`a300${"81"}${INPUT}${badOut}${FEE}`);
assert.ok(firstMatching(r, /token-free output/),
  `expected the legacy [coin, {}] shape to be refused; got: ${r.violations.join(" | ")}`);
console.log("  OK   a token-free output written as [address, [coin, {}]] is flagged");
ran++;

// ---- 15. AND THE REAL ENCODER'S OUTPUT STAYS CLEAN ----
// Two real Conway transactions from this protocol's live preview deployment, fetched from Koios.
// If a rule added here flags these, the rule is wrong — they are what the chain accepted.
const real = require("./test-fixtures/real-preview-txs.json");
for (const tx of real.transactions) {
  const rr = report(tx.cbor);
  assert.deepStrictEqual(rr.violations, [],
    `real preview tx ${tx.txHash} was flagged, which means a rule here is too strict: ` +
    rr.violations.join(" | "));
}
console.log(`  OK   ${real.transactions.length} real preview transactions remain conformant`);
ran++;

console.log(`\n${ran} checks passed`);
