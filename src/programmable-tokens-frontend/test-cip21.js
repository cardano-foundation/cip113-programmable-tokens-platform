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
const { checkCip21, isFatalScope } = require("./.cip21-build/cip21.js");

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

// ---- 11a1. the outer array header is READ, so the body is never walked as the witness set ----
// ⛔ A FATALITY INVERSION, not a cosmetic slip. The top-level dispatch used `bytes[0] & 0x1f` as the
// element count and a hardcoded 1-byte header. With a non-minimal outer header (`9804` — four
// elements in two bytes, valid CBOR) every element shifted by one, so the BODY was walked under the
// label `witnessSet`: its defects became `witnessSet{09}: …` and therefore ADVISORY instead of fatal.
// The same bytes under an `84` header were correctly fatal. Found by the 2026-10-01 pre-merge audit.
{
  // ⛔ THE INPUT MUST HAVE A BODY DEFECT TO MISATTRIBUTE, or these assertions cannot fail. The first
  // version of this test used the CONFORMANT real transaction: its body contains nothing to blame, so
  // "nothing may be attributed to witnessSet" and "every finding must be fatal" were both trivially
  // true and the test could not express a failure. Measured: with that input, reverting the dispatch's
  // OFFSET (`head.first` back to a hardcoded 1) left all 306 checks green while the body really was
  // being walked as the witness set. The count half was defended and the offset half was not, and the
  // test looked directly at the property either way.
  //
  // ⇒ So the input is the real captured ceremony body a Ledger refused — which carries an unsorted
  // mint map, i.e. a body-level defect that WILL be misattributed if the offset is wrong.
  const fs2 = require("node:fs");
  const defective = fs2.readFileSync("test-fixtures/ceremony-genesis-unsorted-mint.hex", "utf8").trim();
  const underMinimal = checkCip21(defective);
  assert.ok(underMinimal.scopedViolations.some((v) => v.message.startsWith("body.mint")),
    `the fixture must carry a body.mint defect for this test to mean anything; got: ` +
    underMinimal.violations.join(" | "));

  // Same bytes, non-minimal outer header. The mint defect must STILL be body-scoped and fatal.
  const r = checkCip21("9804" + defective.slice(2));
  const mislabelled = r.scopedViolations.filter((v) => v.message.startsWith("witnessSet"));
  assert.strictEqual(mislabelled.length, 0,
    `nothing may be attributed to witnessSet when the header is non-minimal; got: ` +
    mislabelled.map((v) => v.message).join(" | "));
  assert.ok(r.scopedViolations.some((v) => v.message.startsWith("body.mint") && v.scope === "body"),
    `the mint defect must still be attributed to the BODY; got ` +
    JSON.stringify(r.scopedViolations.map((v) => `${v.scope}:${v.message.slice(0, 24)}`)));
  assert.ok(r.scopedViolations.every((v) => isFatalScope(v.scope)),
    `every finding on a mis-headered transaction must be fatal; got scopes ` +
    JSON.stringify(r.scopedViolations.map((v) => v.scope)));
  assert.ok(r.scopedViolations.some((v) => /outer array header/.test(v.message)),
    "and the non-minimal header is itself reported");

  // Control, kept: a CONFORMANT transaction under a minimal header stays clean, so the checks above
  // cannot be satisfied by a rule that simply flags everything.
  const real = require("./test-fixtures/real-preview-txs.json").transactions;
  assert.strictEqual(checkCip21(real[1].cbor.toLowerCase()).violations.length, 0,
    "control: a real conformant transaction under `84` reports nothing");
  console.log("  OK   a non-minimal outer header does not reclassify the body as the witness set");
  ran++;
}

// ---- 11a2. an unreadable outer header is blind, not clean ----
{
  // 9f… — indefinite-length outer array. `headerCount` declines, so the body cannot be located.
  const r = checkCip21("9fa0a0f5f6ff");
  assert.ok(r.scopedViolations.some((v) => v.scope === "blind"),
    `an indefinite outer array must be reported blind, not walked at a guessed offset; got ` +
    JSON.stringify(r.scopedViolations.map((v) => v.scope)));
  console.log("  OK   an indefinite outer array header is reported blind rather than guessed at");
  ran++;
}

// ---- 11a3. `blind` is fatal as a MECHANISM, asserted directly ----
// ⛔ THIS ASSERTION EXISTS BECAUSE THE BEHAVIOURAL TEST FOR IT PASSED FOR THE WRONG REASON. A
// ceremony does refuse empty input — but it refuses inside `fromCBORHexWithFormat`, before the
// checker is ever consulted, so dropping "blind" from `isFatalScope` changed nothing and every suite
// stayed green. The mechanism needs its own assertion, not cover borrowed from an earlier guard.
assert.strictEqual(isFatalScope("blind"), true, "`blind` must be fatal: no finding is not a pass");
assert.strictEqual(isFatalScope("transaction"), true, "`transaction` must be fatal");
assert.strictEqual(isFatalScope("body"), true, "`body` must be fatal");
assert.strictEqual(isFatalScope("witnessSet"), false, "`witnessSet` is advisory — we leave its bytes alone");
assert.strictEqual(isFatalScope("auxiliaryData"), false, "`auxiliaryData` is advisory");
console.log("  OK   isFatalScope maps every scope the way the ceremony depends on");
ran++;

// ---- 11a4. two distinct defects with identical messages are both reported ----
// ⛔ The first fix for duplicate reporting deduped by message text, which merged genuinely distinct
// defects: two non-minimal withdrawal keys of the same length produce the same string. A checker that
// hides a defect to avoid printing it twice is worse than one that repeats itself.
{
  // body = { 5: { h'1800...' : 1800, ... } } is awkward to hand-roll; use two non-minimal integers in
  // the same map value position instead: body = {5: {a: 1800, b: 1800}} with 1800 = 0 in 2 bytes.
  const r = checkCip21("84a105a2411a1800411b1800a0f5f6");
  const nonMinimal = r.violations.filter((v) => /shortest encoding|not minimal/.test(v));
  assert.strictEqual(nonMinimal.length, 2,
    `two separate non-minimal encodings must BOTH be reported, not merged into one; got ` +
    `${nonMinimal.length}: ${r.violations.join(" | ")}`);
  console.log(`  OK   ${nonMinimal.length} identical-message defects are each reported, not deduped away`);
  ran++;
}

// ---- 11a6. the outer-header minimality boundaries, in both directions ----
// ⛔ A WRONG BOUNDARY HERE IS A FALSE POSITIVE, and on this lane a false positive is a refused
// ceremony. `< 24 ? 1 : < 256 ? 2 : < 65536 ? 3 : 5` has four transitions and widening either of the
// first two (`<=` instead of `<`) was undetectable: both mutants survived the whole suite. Counts 24
// and 256 are exactly where a correct 2- or 3-byte header must NOT be flagged.
{
  const elem = "f6"; // null — a cheap, conformant array element
  const mk = (count, headerHex) => headerHex + elem.repeat(count);
  const flagged = (hex) =>
    checkCip21(hex).violations.filter((v) => /outer array header/.test(v)).length;

  for (const [count, header, expect, why] of [
    [23, "97", 0, "23 elements in a 1-byte header is minimal"],
    [23, "9817", 1, "23 elements in a 2-byte header is NOT minimal"],
    [24, "9818", 0, "24 elements needs 2 bytes, so 2 bytes is minimal"],
    [24, "990018", 1, "24 elements in a 3-byte header is NOT minimal"],
    [255, "98ff", 0, "255 elements needs 2 bytes"],
    [255, "9900ff", 1, "255 elements in 3 bytes is NOT minimal"],
    [256, "990100", 0, "256 elements needs 3 bytes, so 3 bytes is minimal"],
  ]) {
    const got = flagged(mk(count, header));
    assert.strictEqual(got, expect,
      `${why}: expected ${expect} header finding(s), got ${got}`);
  }
  console.log("  OK   outer-header minimality is exact at 23/24/255/256 in both directions");
  ran++;
}

// ---- 11a5. nothing inside an output is reported twice ----
// ⛔ `body.outputs` is inspected once for the legacy shape and then walked again by the generic
// walker, so findings inside an output used to be recorded TWICE — inflating both the count an
// operator reads and the list they work through. The first fix deduped by message text, which merged
// genuinely distinct defects (11a4 guards that). The real fix is that the pre-pass walks only for the
// offset and discards what it reports, so this pins the absence of duplicates directly.
{
  // body = {1: [ [addr, [coin, {}]] ]} — a token-free output in the forbidden legacy shape, which
  // trips BOTH the legacy-output rule and the empty-map-in-body rule.
  // ⚑ THE INPUT MUST EXERCISE BOTH SINKS, or each rewind defends the other's mutation. `walk` reports
  // into `ctx.violations` (the non-minimal coin `1800` — zero written in two bytes) and accumulates
  // into `ctx.emptyInBody` (the `a0`). Rewinding only one leaves the other duplicated, and an input
  // that trips only one sink cannot tell the two mutations apart — measured: with a minimal coin,
  // removing the `violations` rewind changed nothing and survived.
  const legacyOut = "82" + "581c" + "11".repeat(28) + "82" + "1800" + "a0";
  const r = checkCip21("84" + "a101" + "81" + legacyOut + "a0f5f6");
  assert.ok(r.violations.some((v) => /token-free output/.test(v)), "the legacy shape is reported");
  assert.ok(r.violations.some((v) => /empty map/.test(v)), "the empty map is reported (emptyInBody sink)");
  assert.ok(r.violations.some((v) => /shortest encoding|not minimal/.test(v)),
    `the non-minimal coin is reported (violations sink); got ${r.violations.join(" | ")}`);
  const counts = new Map();
  for (const v of r.violations) counts.set(v, (counts.get(v) ?? 0) + 1);
  const repeated = [...counts.entries()].filter(([, n]) => n > 1);
  assert.strictEqual(repeated.length, 0,
    `no violation may be reported more than once; repeated: ` +
    repeated.map(([v, n]) => `${n}x ${v}`).join(" | "));

  // ⛔ EXACT-MESSAGE EQUALITY MISSES THE DUPLICATE THAT ACTUALLY OCCURRED: the pre-pass reported the
  // same byte the main walk reports, under a different path label, so the strings differed.
  //
  // ⛔ AND "SAME COMPLAINT, DIFFERENT LABEL" IS NOT THE RULE — I tried it and it is WRONG. On an output
  // whose coin position holds an empty map, `body.outputs[0][1][0]` and `body.outputs[0][1][1]` are two
  // GENUINELY DIFFERENT bytes that produce the identical complaint, so that heuristic flags a legitimate
  // pair as a duplicate. The property wanted is "no byte is reported twice", and the path label is not a
  // reliable proxy for the byte either way.
  //
  // ⇒ So this asserts the specific leak instead of a clever general rule: the pre-pass's own label must
  // never reach the output. `checkLegacyOutput` walks the coin as `${path}.coin` to locate the multiasset
  // map, and that label is reported by nothing else — if it appears, the pre-pass is reporting again.
  const prePassLabels = r.scopedViolations.filter((v) => /\.coin$/.test(v.path));
  assert.strictEqual(prePassLabels.length, 0,
    `the legacy-shape pre-pass must report nothing of its own walk; leaked: ` +
    prePassLabels.map((v) => v.path).join(", ") +
    `\n  full list: ${r.violations.join(" | ")}`);
  console.log(`  OK   ${r.violations.length} findings inside an output, each reported exactly once`);
  ran++;
}

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
