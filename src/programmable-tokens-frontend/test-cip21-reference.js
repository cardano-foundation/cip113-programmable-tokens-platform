/**
 * Our CIP-21 verdicts, checked against CIP-21's OWN reference implementation.
 *
 * ⛔ WHY THIS SUITE EXISTS. `lib/utils/cip21.ts` is a CBOR walker we wrote, and a conformance
 * checker that is wrong is worse than none: it green-lights a body a Ledger will refuse, and the
 * only symptom a Ledger gives is "hash mismatch" — after the operator has signed, in a one-shot
 * ceremony. Giovanni's standing instruction on exactly this was "let's try to reuse something we
 * know it works and not do anything ourselves which could break", and the first pass of this work
 * missed that CIP-21's own "Implementation Plan" section names the tool:
 * vacuumlabs/cardano-hw-interop-lib, "Library to make CBOR encoded Cardano transactions comply with
 * CIP-0021".
 *
 * ⚑ SO THE REFERENCE IS THE ORACLE AND OUR WALKER IS THE THING UNDER TEST — not the other way
 * round. Where they disagree, the reference wins and our walker is the bug.
 *
 * ⚑ WHY IT IS A devDependency AND NOT IN THE CLIENT BUNDLE. The browser path needs field-level
 * attribution ("the mint field is unsorted") to be worth showing an operator, which `validateTx`
 * does not give — it answers "CBOR is not canonical" for the whole transaction. Keeping the
 * reference here, as the oracle, buys the proof without putting a second nested copy of `cbor` into
 * a ceremony page. The shipped checker is ours; its VERDICTS are the reference's.
 */
const assert = require("node:assert");
const fs = require("node:fs");
const hw = require("cardano-hw-interop-lib");

let ran = 0;
const ok = (cond, msg) => {
  assert.ok(cond, msg);
  console.log(`  OK   ${msg}`);
  ran++;
};

const referenceFindings = (hex) => hw.validateTx(Buffer.from(hex, "hex")).map((e) => e.reason);

(async () => {
const { checkCip21 } = await import("./.cip21ref-build/utils/cip21.js");
const { canonicaliseForHardwareWallets } = await import("./.cip21ref-build/deployment/ceremony.js");

// ---- 1. the reference implementation agrees the two real failing bodies were broken ----
// These are the actual transactions a Ledger refused on 2026-10-01, witness sets replaced by `a0`.
const broken = ["ceremony-genesis-unsorted-mint.hex", "ceremony-genesis-unsorted-mint-2.hex"];
for (const f of broken) {
  const hex = fs.readFileSync(`test-fixtures/${f}`, "utf8").trim();
  const findings = referenceFindings(hex);
  ok(
    findings.includes("CBOR is not canonical"),
    `${f}: the reference validator independently calls it non-canonical (${JSON.stringify(findings)})`,
  );
  // And our own walker must not have called it clean.
  ok(
    checkCip21(hex).violations.length > 0,
    `${f}: our walker flags it too — a checker that passes this body is the defect`,
  );
}

// ---- 2. what we SHIP is conformant by the reference's own verdict ----
// ⛔ THE CLAIM THAT MATTERS. Everything else is diagnosis; this is the fix.
for (const f of broken) {
  const hex = fs.readFileSync(`test-fixtures/${f}`, "utf8").trim();
  const fixed = canonicaliseForHardwareWallets(hex);
  assert.notStrictEqual(fixed, hex, `${f}: canonicalisation was a no-op — it must have changed the body`);
  const findings = referenceFindings(fixed);
  ok(
    findings.length === 0,
    `${f}: after canonicalisation the reference validator reports NOTHING (was ${JSON.stringify(
      referenceFindings(hex),
    )})`,
  );
  ok(
    checkCip21(fixed).violations.length === 0,
    `${f}: and our walker agrees, so the two implementations do not disagree about the fix`,
  );
}

// ---- 3. real on-chain transactions are conformant AND untouched ----
// Guards the direction nobody checks: a "fixer" that rewrites a transaction it should leave alone
// changes its id. The 3489-byte one carries redeemers, Plutus scripts and a script data hash.
const real = JSON.parse(fs.readFileSync("test-fixtures/real-preview-txs.json", "utf8")).transactions;
assert.ok(real.length >= 2, "expected at least two real transactions in the fixture");
for (const t of real) {
  const findings = referenceFindings(t.cbor);
  ok(
    findings.length === 0,
    `${t.txHash.slice(0, 12)}…: a real on-chain Conway tx passes the reference validator as-is`,
  );
  ok(
    canonicaliseForHardwareWallets(t.cbor) === t.cbor.toLowerCase(),
    `${t.txHash.slice(0, 12)}…: canonicalisation leaves it BYTE-IDENTICAL, so the tx id is stable`,
  );
}

// ---- 4. the witness set is NEVER touched, so script_data_hash stays true ----
// ⛔ THE DEFECT THIS PINS IS ONE WE SHIPPED AND THEN REMOVED. Canonicalising the WHOLE transaction
// rewrites the redeemers (Plutus data goes indefinite → definite length) while the body keeps the
// `script_data_hash` the builder computed over the originals. The node answers
// `PPViewHashesDontMatch` — for a transaction that passes every CIP-21 check there is.
//
// Reported upstream as IntersectMBO/evolution-sdk#585, whose reproduction is this protocol's own
// shape: "3 mints, 4 PlutusV3 redeemers, 1 script withdrawal". Our own fixtures could NOT have caught
// it, because their witness sets are stripped to `a0` — which is exactly why this case is built here
// instead of being hoped for.
//
// The input reverses the witness-set key order of the real 3489-byte on-chain transaction above: a
// body still committing to a script data hash, over a witness set that is genuinely non-canonical.
{
  const { CBOR } = await import("@evolution-sdk/evolution");
  const { value, format } = CBOR.fromCBORHexWithFormat(real[1].cbor.toLowerCase());
  const witnessSet = value[1];
  assert.ok(
    CBOR.isMap(witnessSet) && witnessSet.size > 1 && CBOR.isMap(value[0]) && value[0].get(11n) !== undefined,
    "this construction needs a multi-entry witness set and a script_data_hash; the fixture changed " +
      "and the check is now blind",
  );
  const reversed = new Map([...witnessSet.entries()].reverse());
  const nonCanonicalWitnessSet = CBOR.toCBORHex([value[0], reversed, value[2], value[3]]);

  // Sanity: canonical encoding really would rewrite this witness set, so the case is not vacuous.
  const wouldChange =
    CBOR.toCBORHex(reversed, CBOR.CANONICAL_OPTIONS) !== CBOR.toCBORHex(reversed);
  ok(wouldChange, "the constructed witness set is one canonical encoding WOULD rewrite");

  const out = canonicaliseForHardwareWallets(nonCanonicalWitnessSet);

  const before = CBOR.fromCBORHexWithFormat(nonCanonicalWitnessSet);
  const after = CBOR.fromCBORHexWithFormat(out);
  const wsBefore = CBOR.toCBORHexWithFormat(before.value[1], before.format.children[1]);
  const wsAfter = CBOR.toCBORHexWithFormat(after.value[1], after.format.children[1]);
  ok(
    wsBefore === wsAfter,
    "the witness set comes back BYTE-IDENTICAL, so the body's script_data_hash still commits to " +
      "bytes that exist (this is what #585 reports going wrong)",
  );
  ok(
    CBOR.toCBORHexWithFormat(after.value[0], after.format.children[0]) ===
      CBOR.toCBORHex(before.value[0], CBOR.CANONICAL_OPTIONS),
    "while the BODY is canonical — which is the only part a hardware wallet reconstructs",
  );

  // And the fee stays exact: canonical ordering is a permutation, so the body cannot change length.
  ok(out.length === nonCanonicalWitnessSet.length,
    "the transaction's total length is unchanged, so the builder's fee is still correct");
}

// ---- 5. THE GUARD ACTUALLY FIRES — a body-level violation that survives canonicalisation ----
// ⛔ THIS IS THE TEST WHOSE ABSENCE WAS A FINDING. An adversarial pre-merge audit replaced the fatal
// classification in ceremony.ts with a never-matching predicate, making the `throw` dead code, and
// ALL 278 CHECKS STAYED GREEN. The only cover was a source-text regex asserting that the string
// `checkCip21(canonical)` and the word `throw` both appear — which the mutation left intact. A test
// that greps for a mechanism is not a test of the mechanism.
//
// ⚑ AND THE PREMISE THAT EXCUSED IT WAS FALSE. The old comment claimed "the guard cannot be triggered
// behaviourally while a correct SDK is installed". It can: tag-258 presence is NOT normalised by
// Evolution's canonical encoder, so a body with `inputs` tagged and `reference_inputs` bare survives
// canonicalisation unchanged and reaches the guard. That is the input below.
{
  const { CBOR } = await import("@evolution-sdk/evolution");

  // inputs (field 0) TAGGED with 258; reference_inputs (field 18) BARE. Both are body fields, and
  // CIP-21: "either there are no tags 258 in sets, or there are such tags everywhere".
  const outRef = [new Uint8Array(32).fill(0xab), 0n];
  const body = new Map([
    [0n, { _tag: "Tag", tag: 258, value: [outRef] }],
    [18n, [outRef]],
  ]);
  const mixed = CBOR.toCBORHex([body, new Map(), true, null]);

  // Non-vacuity: canonicalisation must NOT silently fix this, or the guard is never reached.
  ok(
    CBOR.toCBORHex(body, CBOR.CANONICAL_OPTIONS) === CBOR.toCBORHex(body),
    "Evolution's canonical encoder does not normalise tag-258 presence, so the mixture survives to the guard",
  );

  const report = checkCip21(mixed);
  const tagFinding = report.scopedViolations.find((v) => v.message.startsWith("tag 258"));
  ok(tagFinding !== undefined, "the checker reports the tag-258 inconsistency");
  ok(
    tagFinding.scope === "transaction" && !tagFinding.message.startsWith("body"),
    "and its scope is structural, NOT inferred from a message that does not begin with \"body\" — " +
      "the exact mismatch that made this fatal defect a console warning",
  );

  let refused = "";
  try {
    canonicaliseForHardwareWallets(mixed);
  } catch (e) {
    refused = e.message;
  }
  ok(
    /tag 258 is INCONSISTENT/.test(refused),
    "canonicaliseForHardwareWallets THROWS on it rather than warning and returning the body unchanged",
  );
}

// ---- 6. the checker cannot be blind and silent at the same time ----
// ⛔ The "could not walk the CBOR" message has always ended "treat this check as blind, not as a
// pass" — and the caller used to do exactly what it forbids, because it classified by message prefix
// and the message does not begin with "body". `blind` is now a scope, and a scope is fatal or it is
// not; no wording is consulted.
{
  // Empty input is the one input that reaches the blind branch; everything else the walker can read
  // far enough to report on.
  const blindReport = checkCip21("");
  ok(
    blindReport.scopedViolations.length === 1 && blindReport.scopedViolations[0].scope === "blind",
    "a checker handed nothing reports `blind` rather than an empty, reassuring violation list",
  );

  // ⚑ TRUNCATION DOES NOT GO BLIND — it is READ, and reported as a body defect. Pinned because the
  // obvious assumption (unreadable ⇒ blind) is wrong here, and a future reader would otherwise
  // "fix" this test by asserting the scope it does not have.
  const truncated = "84a30081825820" + "ab".repeat(8);
  const scopes = checkCip21(truncated).scopedViolations.map((v) => v.scope);
  ok(
    scopes.length > 0 && scopes.every((sc) => sc === "body"),
    `truncated CBOR is reported as a body defect, not as blind and not as clean (got ${JSON.stringify(scopes)})`,
  );

  // Both must stop a ceremony, which is the property that actually matters.
  for (const [hex, what] of [["", "empty input"], [truncated, "truncated input"]]) {
    let threw = false;
    try {
      canonicaliseForHardwareWallets(hex);
    } catch {
      threw = true;
    }
    ok(threw, `${what} stops the ceremony — absence of a finding is never a pass`);
  }

  // ⚠ A KNOWN HOLE, PINNED SO IT STAYS KNOWN. A bare "84" — an array header promising four elements
  // with nothing after it — is reported by the checker as CONFORMANT (zero violations), because the
  // walker's element loop simply never runs. It is harmless today only because
  // `canonicaliseForHardwareWallets` refuses it at the element-count guard before the checker is
  // consulted. That refusal is the real protection, so it is what gets asserted.
  ok(checkCip21("84").violations.length === 0, "documenting the hole: a bare `84` produces no findings");
  let barRefused = false;
  try {
    canonicaliseForHardwareWallets("84");
  } catch {
    barRefused = true;
  }
  ok(barRefused, "but the element-count guard refuses it, which is what keeps the hole harmless");
}

// ---- 7. the faithful-replay post-condition is what makes the hardcoded "84" safe ----
// ⛔ THE OUTER ARRAY HEADER IS WRITTEN AS A LITERAL "84". That is only safe because the replay check
// proves the input really was a definite 4-element array encoded minimally. The audit showed that
// disabling the check left every suite green.
//
// ⛔ AND MY FIRST ATTEMPT AT THIS TEST PASSED FOR THE WRONG REASON — recorded because it is the trap
// this whole file exists to avoid. It used `a0` as the body, which is itself a CIP-21 violation (an
// empty map in the body), so every case threw at the conformance check and the structural guard was
// never exercised. The mutation still survived. So the cases below are built from the REAL conformant
// transaction's own elements, re-headered: the body is beyond reproach, and the ONLY thing left to
// object to is the shape of the outer array.
{
  const { CBOR } = await import("@evolution-sdk/evolution");
  const good = real[1].cbor.toLowerCase();
  const { value, format } = CBOR.fromCBORHexWithFormat(good);
  const parts = value.map((v, i) => CBOR.toCBORHexWithFormat(v, format.children[i]));

  // Control: re-headered with the correct "84" this IS the real transaction, and must pass cleanly.
  ok(
    canonicaliseForHardwareWallets("84" + parts.join("")) === good,
    "control: the real transaction's own elements under an \"84\" header pass through unchanged",
  );

  const cases = [
    ["9f" + parts.join("") + "ff", "an INDEFINITE-length outer array"],
    ["9804" + parts.join(""), "a non-minimal 4-element header (9804 rather than 84)"],
    ["83" + parts.slice(0, 3).join(""), "a 3-element transaction array"],
    ["85" + parts.join("") + "f6", "a 5-element transaction array"],
    [parts[0], "a bare body with no transaction array at all"],
  ];
  for (const [hex, what] of cases) {
    let threw = false;
    try {
      canonicaliseForHardwareWallets(hex);
    } catch {
      threw = true;
    }
    ok(threw, `${what} is refused rather than spliced against a hardcoded "84" header`);
  }
}

// ---- 8b. the evaluate logger cannot print a credential that rides in the URL ----
// ⛔ A CREDENTIAL-HYGIENE GUARD WITH NO TEST IS A GUARD THAT GETS REVERTED — reverting `safeUrl` broke
// nothing. Blockfrost carries its key in a `project_id` HEADER, which this logger never reads; the URL
// was logged verbatim, so a provider that ever put one in a query parameter would print it into a
// console an operator may paste into an issue.
//
// ⚑ EXERCISED, NOT GREPPED. The first version of this test asserted the SOURCE matched a regex — the
// same shape that let the round-1 guard ship undefended. Four leak shapes, including userinfo, which
// `u.origin` drops and nothing else would.
{
  const { safeUrl } = await import("./.cip21ref-build/deployment/evaluate-request-log.js");
  const SECRET = "preprodSECRETKEY0123456789";
  const shapes = [
    [`https://host/api/v0/utils/txs/evaluate?project_id=${SECRET}`, "a query parameter"],
    [`https://user:${SECRET}@host/api/v0/utils/txs/evaluate`, "URL userinfo"],
    [`https://host/api/v0/utils/txs/evaluate#${SECRET}`, "a fragment"],
    [`not-a-url://;;;/evaluate?key=${SECRET}`, "a string that does not parse as a URL"],
  ];
  for (const [url, what] of shapes) {
    const reduced = safeUrl(url);
    ok(!reduced.includes(SECRET), `${what} is stripped before logging (got ${reduced})`);
  }
  ok(
    safeUrl("https://host/api/v0/utils/txs/evaluate") === "https://host/api/v0/utils/txs/evaluate",
    "and an ordinary URL survives intact, so the log stays useful",
  );
}

// ---- 8. the oracle is not vacuous ----
// ⛔ WITHOUT THIS THE WHOLE SUITE COULD PASS BY CALLING A LIBRARY THAT ANSWERS "fine" TO EVERYTHING.
// "1800" is integer 0 written in two bytes: valid CBOR, not canonical.
const notCanonical = "84ac00d901028282582018000000000000000000000000000000000000000000000000000000000000001800";
let sawError = false;
try {
  sawError = hw.validateTx(Buffer.from(notCanonical, "hex")).length > 0;
} catch {
  sawError = true; // refusing to parse a malformed body is also a non-empty verdict
}
ok(sawError, "the reference validator rejects a deliberately malformed body, so it can say no");

console.log(`\n${ran} checks passed`);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
