/**
 * The hardware-wallet guard on the RWA admin-rotation path, pinned to REAL cardano-client-lib bytes.
 *
 * ## Why this suite exists at all
 *
 * Every other CBOR fixture in this repo is Evolution-built. The admin transactions the rotation
 * flow actually hands to a wallet are built by cardano-client-lib on the backend, and until T-093
 * neither `checkCip21` nor `canonicaliseForHardwareWallets` had ever been shown one. The plan
 * assumed the body would need canonicalising for a Ledger. It does not — and the measurement that
 * found that also found that the acceptance criterion originally written for this ticket was
 * unachievable, and could only have been satisfied by introducing the very bug it was guarding
 * against.
 *
 * ## What is true, and therefore what is pinned
 *
 *   · the BODY is already canonical — canonicalisation is a BYTE-EXACT NO-OP
 *   · `checkCip21` reports ZERO body-scoped violations
 *   · `required_signers` is tag-258 encoded, consistently with inputs and collateral
 *   · `validateTx`'s ONLY finding is indefinite-length Plutus data in the WITNESS SET
 *
 * ⛔ THAT LAST ONE IS NOT A DEFECT TO FIX, AND THE TEST SAYS SO ON PURPOSE. The non-canonical bytes
 * sit inside the redeemer. Body-only canonicalisation deliberately leaves them alone, because
 * rewriting the witness set is exactly what strands `script_data_hash` and gets the transaction
 * rejected with `PPViewHashesDontMatch` (Evolution upstream #585 — the bug that broke the genesis
 * ceremony). A hardware wallet over CIP-30 reconstructs and hashes only the BODY, so witness-set
 * encoding cannot change what a Ledger signs. `cardano-hw-cli` WOULD refuse it, which is why the
 * device probe must not use that tool.
 *
 * ⚑ AND A GUARD MEASURED TO BE A NO-OP IS A GUARD THAT NEVER FIRES, so the last section drives the
 * refusal with a body that really is non-canonical. Without it this whole suite would pass against
 * a `canonicaliseForHardwareWallets` that had been replaced by `(x) => x`.
 */
const assert = require("node:assert");
const fs = require("node:fs");
const hw = require("cardano-hw-interop-lib");

let checks = 0;
const check = (what, fn) => { fn(); checks += 1; console.log(`  ok  ${what}`); };

const findingsOf = (hex) => hw.validateTx(Buffer.from(hex, "hex")).map((e) => e.reason);

async function main() {
  const { checkCip21, isFatalScope } = await import("./.cclguard-build/utils/cip21.js");
  const { canonicaliseForHardwareWallets } = await import("./.cclguard-build/deployment/ceremony.js");
  const { CBOR } = await import("@evolution-sdk/evolution");

  const fixture = JSON.parse(fs.readFileSync("test-fixtures/ccl-rotate-admin-unsigned.json", "utf8"));
  const cclHex = fixture.unsignedCborHex;

  console.log("--- the fixture is what it claims to be ---");

  check("the fixture carries its provenance and is a RotateAdmin", () => {
    assert.match(fixture._source, /cardano-client-lib/);
    assert.match(fixture._source, /BootstrapFixture/, "a committed fixture must say whose keys it holds");
    assert.strictEqual(fixture.action, "RotateAdmin");
  });

  check("it is a 4-element Conway transaction of a realistic size", () => {
    assert.ok(/^84/.test(cclHex), "must open 0x84 (4-element array)");
    assert.ok(cclHex.length > 8000, `suspiciously small: ${cclHex.length} hex chars`);
    assert.strictEqual(cclHex.length % 2, 0);
  });

  console.log("\n--- what cardano-client-lib actually emits ---");

  const report = checkCip21(cclHex);

  // ⛔ THE FINDING THAT RETIRED AN OPEN ITEM. required_signers is body field 14 and never appeared
  // in a ceremony transaction, so whether CCL tagged it was unknown and was the epic's named risk.
  check("required_signers, inputs and collateral are ALL tag-258 — no mixed tagging", () => {
    assert.deepStrictEqual([...report.bareSetFields], [],
      "a bare set beside a tagged one is the all-or-nothing violation CIP-21 cannot have, and "
      + "canonicalisation cannot fix it from here — it would mean fixing the Java builder");
    for (const field of ["body.inputs", "body.collateral_inputs", "body.required_signers"]) {
      assert.ok(report.taggedSetFields.includes(field), `${field} is not tag-258: ${report.taggedSetFields}`);
    }
  });

  check("ZERO body-scoped violations — the CCL body is CIP-21 clean", () => {
    const fatal = report.scopedViolations.filter((v) => isFatalScope(v.scope));
    assert.deepStrictEqual(fatal, [],
      "a body-scoped violation means a Ledger would hash a body we never submit:\n"
      + fatal.map((v) => `  [${v.scope}] ${v.path}: ${v.message}`).join("\n"));
  });

  check("the only violations are in the WITNESS SET, and they are indefinite-length Plutus data", () => {
    assert.ok(report.scopedViolations.length > 0,
      "if this ever reaches zero the fixture changed; re-measure before relaxing the assertion");
    for (const v of report.scopedViolations) {
      assert.strictEqual(v.scope, "witnessSet", `unexpected scope ${v.scope} at ${v.path}`);
      assert.match(v.message, /INDEFINITE length/);
    }
  });

  console.log("\n--- canonicalisation is a byte-exact no-op, and that is the measured truth ---");

  const canonical = canonicaliseForHardwareWallets(cclHex);

  check("canonicalising the CCL body changes NOTHING", () =>
    assert.strictEqual(canonical, cclHex,
      "the body was measured canonical on 2026-10-06; if this fires, either the builder changed or "
      + "the canonicaliser did, and the rotation path needs re-measuring before it ships"));

  check("elements 1-3 survive byte-identical, so script_data_hash stays valid", () => {
    const tail = (h) => {
      const d = CBOR.fromCBORHexWithFormat(h);
      return d.value.map((el, i) => CBOR.toCBORHexWithFormat(el, d.format.children[i])).slice(1).join("");
    };
    assert.strictEqual(tail(canonical), tail(cclHex));
  });

  console.log("\n--- the vacuumlabs oracle, and the finding we must NOT fix ---");

  check("validateTx reports the witness-set finding, NOT nothing", () => {
    const findings = findingsOf(canonical);
    // ⛔ The criterion first written for this ticket was "reports NOTHING". T-093 measured that to
    // be unachievable: the only way to clear it is to rewrite the witness set, which strands
    // script_data_hash. Asserting the finding is PRESENT is what stops someone "fixing" it.
    assert.deepStrictEqual(findings, ["CBOR is not canonical"],
      `expected exactly the witness-set canonicality finding, got: ${JSON.stringify(findings)}`);
  });

  check("the oracle says the same thing before and after — we fixed nothing it can see", () =>
    assert.deepStrictEqual(findingsOf(canonical), findingsOf(cclHex)));

  console.log("\n--- prepareRotation: the guard's call site ---");

  const { prepareRotation, requiredSignersOf } =
    await import("./.cclguard-build/rwa/rotate-admin.js");

  check("requiredSignersOf decodes the key hashes as HEX, not as [object Uint8Array]", () => {
    const signers = requiredSignersOf(cclHex);
    assert.strictEqual(signers.length, 2, `expected two signers, got ${JSON.stringify(signers)}`);
    for (const s of signers) {
      assert.match(s, /^[0-9a-f]{56}$/, `not a 28-byte hex key hash: ${s}`);
    }
    assert.notStrictEqual(signers[0], signers[1], "two IDENTICAL signers would make the dual-signature property vacuous");
  });

  check("prepareRotation returns the canonical bytes, their hash, and who must sign", () => {
    const p = prepareRotation(cclHex);
    assert.strictEqual(p.canonicalHex, cclHex, "measured no-op; see the fixture note");
    assert.ok(p.bodyWasAlreadyCanonical, "T-093 measured the CCL body canonical");
    assert.match(p.txHash, /^[0-9a-f]{64}$/);
    assert.deepStrictEqual([...p.requiredSigners], [...requiredSignersOf(cclHex)]);
  });

  check("prepareRotation is idempotent — canonicalising twice cannot move the hash", () => {
    const once = prepareRotation(cclHex);
    const twice = prepareRotation(once.canonicalHex);
    assert.strictEqual(twice.canonicalHex, once.canonicalHex);
    assert.strictEqual(twice.txHash, once.txHash);
  });

  check("prepareRotation accepts upper-case and whitespace-padded input", () => {
    const p = prepareRotation(`  ${cclHex.toUpperCase()}\n`);
    assert.strictEqual(p.canonicalHex, cclHex);
  });

  // ⛔ A ROTATION DECLARING ONE SIGNER CANNOT VALIDATE, so collecting signatures for it wastes two
  // people's time and ends at MissingRequiredSigners. Refuse before the hand-off, not after.
  check("prepareRotation REFUSES a transaction that declares fewer than two signers", () => {
    const real = fs.readFileSync("test-fixtures/ceremony-genesis-unsorted-mint.hex", "utf8").trim();
    let saw = null;
    try { prepareRotation(real); } catch (e) { saw = e; }
    assert.ok(saw, "a ceremony genesis declares no rotation signers and must not be accepted here");
  });

  console.log("\n--- FAIL-FIRST: the guard must actually refuse something ---");

  // A guard that is a no-op on every real input is indistinguishable from `(x) => x`. These two
  // fixtures are real ceremony transactions whose mint map is unsorted — a BODY violation.
  for (const name of ["ceremony-genesis-unsorted-mint.hex", "ceremony-genesis-unsorted-mint-2.hex"]) {
    check(`the guard REFUSES or REPAIRS ${name} rather than passing it through`, () => {
      const bad = fs.readFileSync(`test-fixtures/${name}`, "utf8").trim();
      let threw = null, out = null;
      try { out = canonicaliseForHardwareWallets(bad); } catch (e) { threw = e; }
      if (threw === null) {
        assert.notStrictEqual(out, bad,
          "a body with an unsorted mint map was returned untouched — the guard is inert");
        assert.deepStrictEqual(findingsOf(out), [],
          "the guard claimed to repair this body but the oracle still rejects it");
      } else {
        assert.match(threw.message, /canonical|CIP-21|sorted|body/i,
          `refusal must say what is wrong; got: ${threw.message}`);
      }
    });
  }

  check("a body that is NOT a Conway transaction is refused, not silently passed", () => {
    assert.throws(() => canonicaliseForHardwareWallets("a0"),
      /Conway transaction|does not decode/i);
  });

  console.log(`\n${checks} checks passed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
