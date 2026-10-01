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

console.log(`\n${ran} checks passed`);
