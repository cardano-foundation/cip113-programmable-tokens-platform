/**
 * The ceremony must hand a hardware wallet a body it will reconstruct identically.
 *
 * ⛔ THE DEFECT, CAPTURED AS A FIXTURE. test-fixtures/ceremony-genesis-unsorted-mint.hex is the
 * REAL protocol-genesis body from the ceremony that failed on a Ledger with "hash mismatch",
 * 2026-10-01 (its witness set is replaced by `a0` — no signatures, nothing secret; the body is what
 * is hashed and the body is the evidence). Its `mint` field lists three policy IDs in builder order,
 * 021761ef… / c639b35c… / 27e581ff…, where canonical order is 021761ef… / 27e581ff… / c639b35c….
 * CIP-21 requires multiasset maps to be sorted, so the device re-sorted, hashed a different body,
 * and signed something we would never submit.
 *
 * This suite pins all four facts the fix rests on, because each one of them can regress silently.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let ran = 0;
let checkCip21;
const fixture = fs.readFileSync("test-fixtures/ceremony-genesis-unsorted-mint.hex", "utf8").trim();

(async () => {
  ({ checkCip21 } = await import("./.cip21can-build/utils/cip21.js"));
  const { CBOR, Transaction } = await import("@evolution-sdk/evolution");
  const canonicalise = (hex) =>
    Transaction.toCBORHex(Transaction.fromCBORHex(hex, CBOR.CANONICAL_OPTIONS), CBOR.CANONICAL_OPTIONS);

  // ---- 1. the fixture really is the defect, so this suite cannot pass vacuously ----
  const before = checkCip21(fixture);
  const mintViolation = before.violations.find((v) => v.startsWith("body.mint"));
  assert.ok(mintViolation,
    "the captured ceremony body is no longer non-conformant — if the fixture was regenerated, this " +
    `suite has lost the defect it exists to prove. Violations: ${before.violations.join(" | ")}`);
  console.log("  OK   the captured ceremony body reproduces the unsorted mint map");
  ran++;

  // ---- 2. canonicalising fixes it ----
  const after = checkCip21(canonicalise(fixture));
  assert.deepStrictEqual(after.violations, [],
    `canonicalising left violations behind: ${after.violations.join(" | ")}`);
  console.log("  OK   canonicalising makes it CIP-21 conformant");
  ran++;

  // ---- 3. ⛔ THE BEHAVIOURAL VERSION GUARD ----
  // Through @evolution-sdk/evolution 0.5.2 the canonical comparator sorted by LENGTH ONLY, so
  // equal-length keys kept insertion order and canonical mode was a no-op on exactly this field.
  // Fixed in IntersectMBO/evolution-sdk#555. Asserted as BEHAVIOUR, not as a version string: a
  // version check passes while a resolution silently serves something older, which is how this
  // defect reached a ceremony in the first place.
  assert.notStrictEqual(canonicalise(fixture), fixture,
    "canonicalising changed NOTHING on a body known to be non-canonical. @evolution-sdk/evolution " +
    "has regressed below 0.5.16 (PR #555): its canonical comparator sorts equal-length map keys by " +
    "length alone, so every 29-byte policy ID ties and insertion order survives. A Ledger will " +
    "refuse the next ceremony and the only symptom will be \"hash mismatch\".");
  console.log("  OK   the installed SDK sorts equal-length map keys bytewise (PR #555 present)");
  ran++;

  // ---- 4. and it is a NO-OP on transactions that are already canonical ----
  // This is what makes it safe to apply to every ceremony step. Upstream issue #576 warns that
  // re-encoding can change Plutus data and break the script data hash; these two REAL Conway
  // transactions — one of them 3489 bytes WITH a script data hash — must come back byte-identical.
  const real = require("./test-fixtures/real-preview-txs.json");
  for (const tx of real.transactions) {
    assert.strictEqual(canonicalise(tx.cbor), tx.cbor,
      `canonicalising altered the already-canonical tx ${tx.txHash}. If Plutus data or the script ` +
      "data hash can move, this must NOT be applied to a built transaction (upstream issue #576).");
  }
  console.log(`  OK   a no-op on ${real.transactions.length} real transactions, script data hash intact`);
  ran++;

  // ---- 5. IT REPRODUCES ON EVERY CEREMONY, not just one plan ----
  // A second real genesis from a later run, 2026-10-01: same defect, different policy IDs
  // (3511a165… , 7395ab08… , 20bac61c… where canonical is 20bac61c… , 3511a165… , 7395ab08…).
  // Two independent instances are what make this a property of the builder rather than bad luck.
  const second = fs.readFileSync("test-fixtures/ceremony-genesis-unsorted-mint-2.hex", "utf8").trim();
  assert.ok(checkCip21(second).violations.some((v) => v.startsWith("body.mint")),
    "the second captured ceremony body no longer reproduces the unsorted mint map");
  assert.deepStrictEqual(checkCip21(canonicalise(second)).violations, [],
    "canonicalising did not fix the second captured ceremony body");
  console.log("  OK   a second real ceremony body shows the same defect and the same fix");
  ran++;

  // ---- 6. cborOf turns a non-conformant body into a conformant one ----
  // The end-to-end contract, exercised through the real entry point rather than the helper.
  const { cborOf } = await import("./.cip21can-build/deployment/ceremony.js");
  const out = cborOf({ cbor: second, txHash: "ab".repeat(32) });
  assert.notStrictEqual(out, second, "cborOf returned the non-canonical body unchanged");
  assert.deepStrictEqual(checkCip21(out).violations, [],
    `cborOf returned a body that is still not CIP-21 conformant: ${checkCip21(out).violations.join(" | ")}`);
  console.log("  OK   cborOf turns a real non-conformant ceremony body into a conformant one");
  ran++;

  // ---- 6b. ⛔ AND A SILENT NO-OP WOULD BE REFUSED, not shipped ----
  // This is the regression that reached Giovanni TWICE on 2026-10-01: the canonical option was
  // honoured and did nothing, because the installed SDK was too old, so the second ceremony came
  // back with the identical violation. The guard cannot be triggered behaviourally while a correct
  // SDK is installed — which is exactly why it is asserted structurally instead of quietly trusted.
  const ceremonySrc = fs.readFileSync("lib/deployment/ceremony.ts", "utf8");
  const guard = ceremonySrc.slice(ceremonySrc.indexOf("export function canonicaliseForHardwareWallets"));
  assert.ok(/checkCip21\(canonical\)/.test(guard) && /throw new Error\(/.test(guard),
    "canonicaliseForHardwareWallets no longer verifies its own output. Asking for canonical " +
    "encoding and getting nothing is indistinguishable from success, and that silence shipped a " +
    "body a Ledger rejected twice.");
  assert.ok(/0\.5\.16/.test(guard) && /npm ci/.test(guard),
    "the refusal message must name the likely cause and the action — an operator meeting this " +
    "mid-ceremony needs the version and the command, not just the field.");
  console.log("  OK   the canonicaliser verifies its own output and names cause and remedy");
  ran++;

  // ---- 7. the ceremony's serializer actually calls it ----
  const src = fs.readFileSync("lib/deployment/ceremony.ts", "utf8");
  const body = src.slice(src.indexOf("export function cborOf"), src.indexOf("export function canonicaliseForHardwareWallets"));
  assert.ok(/return canonicaliseForHardwareWallets\(u\.cbor\)/.test(body),
    "cborOf returns the builder's hex unchanged again. Every ceremony step is serialized through " +
    "it, so this is the one line that keeps all five CIP-21 conformant.");
  console.log("  OK   cborOf serializes every ceremony step through the canonicaliser");
  ran++;

  console.log(`\n${ran} checks passed`);
})().catch((e) => { console.error(e); process.exit(1); });
