/**
 * Multisig upgrade: quorum checking and witness assembly.
 *
 * The key-hash derivation is checked against PYTHON's blake2b, not against itself. A test that
 * hashes with the same library it is testing proves only that the library is deterministic;
 * these expected values were produced by hashlib.blake2b(vkey, digest_size=28), an independent
 * implementation, so a wrong digest length or a wrong algorithm would fail here.
 */
const assert = require("node:assert");

// From python: hashlib.blake2b(bytes(range(32)), digest_size=28).hexdigest()
const VKEY_A = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
const HASH_A = "491112dd01155c07dab485f71b572e0cae759e2cd38b1c0e97554297";
const VKEY_B = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";
const HASH_B = "9dc7fb65d53743f5960d8306f04e933b35d10715754d6f4f7e35cc84";

/** A CIP-30 witness set: map{0: [[vkey, signature]]}. */
const witnessSet = (vkeyHex) =>
  "a10081" + "82" + "5820" + vkeyHex + "5840" + "ab".repeat(64);

/** Minimal valid tx shell: [body, witnessSet, isValid, auxiliaryData]. */
const UNSIGNED_TX = "84" + "a0" + "a0" + "f5" + "f6";

async function main() {
  const { keyHashOfVkey, keyHashesInWitnessSet, checkQuorum, assembleUpgradeTx } =
    require("./.upgrade-build/upgrade/witness.js");

  // ---- key hash derivation, cross-checked against python ----
  assert.strictEqual(keyHashOfVkey(VKEY_A), HASH_A);
  assert.strictEqual(keyHashOfVkey(VKEY_B), HASH_B);
  assert.throws(() => keyHashOfVkey("00".repeat(31)), /32 bytes/);
  console.log("  OK   key hash is blake2b-224, matching an independent implementation");

  assert.deepStrictEqual(keyHashesInWitnessSet(witnessSet(VKEY_A)), [HASH_A]);
  console.log("  OK   key hashes read out of a CIP-30 witness set");

  // ---- quorum ----
  const members = [HASH_A, HASH_B, "cc".repeat(28)];

  const two = checkQuorum([witnessSet(VKEY_A), witnessSet(VKEY_B)], members, 2);
  assert.strictEqual(two.satisfied, true);
  assert.strictEqual(two.signed.length, 2);
  assert.strictEqual(two.missing.length, 1);
  console.log("  OK   2-of-3 satisfied by two distinct members");

  // The failure a counting tool makes: one signer pasted twice.
  const dup = checkQuorum([witnessSet(VKEY_A), witnessSet(VKEY_A)], members, 2);
  assert.strictEqual(dup.signed.length, 1, "duplicate signer must collapse");
  assert.strictEqual(dup.satisfied, false, "two pastes from one signer is not a quorum");
  console.log("  OK   the same signer twice is one signature, not a quorum");

  // The other failure: a signature from outside the authority.
  const stranger = checkQuorum([witnessSet(VKEY_A), witnessSet("11".repeat(32))], members, 2);
  assert.strictEqual(stranger.strangers.length, 1);
  assert.strictEqual(stranger.satisfied, false, "a stranger's witness must not count");
  console.log("  OK   a non-member's witness is reported, and blocks the quorum");

  // ---- assembly ----
  const assembled = assembleUpgradeTx(UNSIGNED_TX, [witnessSet(VKEY_A), witnessSet(VKEY_B)]);
  assert.ok(assembled.startsWith("84"), "assembled tx must still be a 4-element array");
  assert.ok(assembled.includes(VKEY_A) && assembled.includes(VKEY_B),
    "both witnesses must survive assembly");
  const back = keyHashesInWitnessSet(assembled.slice(4, assembled.length - 4));
  assert.deepStrictEqual(new Set(back), new Set([HASH_A, HASH_B]));
  console.log("  OK   both witnesses merge into one transaction");

  // The body is what every signature commits to; it must come through untouched.
  assert.strictEqual(assembled.slice(2, 4), "a0", "transaction body was modified during assembly");
  console.log("  OK   the body is preserved byte-for-byte");

  assert.throws(() => assembleUpgradeTx(UNSIGNED_TX, []), /no witnesses/);
  console.log("  OK   assembling nothing is refused");
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
