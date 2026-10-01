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

// ---- 4. a canonicalisation that would MOVE the witness set is refused ----
// ⛔ THE HOLE THE REFERENCE IMPLEMENTATION REFUSES TO LEAVE OPEN. `script_data_hash` is computed over
// the redeemers, datums and language views AS ENCODED IN THE WITNESS SET, and we re-encode the whole
// transaction. Change one redeemer byte and the body commits to a preimage that no longer exists —
// the ledger rejects it in phase 2, AFTER the Ledger has signed, with the seeds already spent.
// vacuumlabs' `transformTx` throws MISSING_COST_MODELS_FOR_SCRIPT_DATA_HASH rather than guess; we
// cannot recompute the hash at all from a hex string, so refusing is the whole correct behaviour.
//
// The input is built by reversing the witness-set key order of the REAL 3489-byte on-chain
// transaction above, which is the cheapest way to produce a body whose witness set is genuinely
// non-canonical while everything else stays a transaction Evolution will parse.
{
  const { CBOR } = await import("@evolution-sdk/evolution");
  const sdhTx = real.find((t) => t.cbor === real[1].cbor);
  const { value } = CBOR.fromCBORHexWithFormat(sdhTx.cbor);
  const witnessSet = value[1];
  assert.ok(CBOR.isMap(witnessSet) && witnessSet.size > 1,
    "this construction needs a multi-entry witness set; the fixture changed and the check is blind");
  const reversed = new Map([...witnessSet.entries()].reverse());
  const movedWitnessSet = CBOR.toCBORHex([value[0], reversed, value[2], value[3]]);

  let refused = "";
  try {
    canonicaliseForHardwareWallets(movedWitnessSet);
  } catch (e) {
    refused = e.message;
  }
  ok(
    /WITNESS SET/.test(refused) && /script_data_hash/.test(refused),
    "a canonicalisation that would move the witness set is REFUSED, naming script_data_hash",
  );
  ok(
    /cardano-hw-interop-lib|MISSING_COST_MODELS/.test(refused),
    "and the refusal points at the reference implementation that refuses the same transformation",
  );
}

// ---- 5. the oracle is not vacuous ----
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
