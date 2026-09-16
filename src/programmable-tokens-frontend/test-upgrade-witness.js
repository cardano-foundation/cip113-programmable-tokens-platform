/**
 * Multisig upgrade: quorum checking and witness assembly.
 *
 * The key-hash derivation is checked against PYTHON's blake2b, not against itself. A test that
 * hashes with the same library it is testing proves only that the library is deterministic;
 * these expected values were produced by hashlib.blake2b(vkey, digest_size=28), an independent
 * implementation, so a wrong digest length or a wrong algorithm would fail here.
 *
 * ## Why every witness assertion runs TWICE
 *
 * These fixtures were hand-built as `a10081 …` — a bare CBOR array under key 0. That is a legal
 * witness set and it is what many CIP-30 wallets return, so it is worth testing. It is NOT what
 * Evolution emits: its codec writes the vkey list as a TAG-258 SET (`a1 00 d9 0102 81 …`). Both
 * shapes reach this code in production — the wallet's encoding arrives from `signTx`, Evolution's
 * from anything the application built and re-serialised — and testing only the hand-built one
 * meant these tests passed while exercising an encoding half the callers never produce.
 *
 * Nothing needed fixing: `splitVkeyWitnesses` already handles both, and the indefinite-length form
 * besides. What changed is what the green MEANS. An untested-but-defended function and an
 * undefended one look identical from outside, and they need completely different work.
 */
const assert = require("node:assert");

// From python: hashlib.blake2b(bytes(range(32)), digest_size=28).hexdigest()
const VKEY_A = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
const HASH_A = "491112dd01155c07dab485f71b572e0cae759e2cd38b1c0e97554297";
const VKEY_B = "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100";
const HASH_B = "9dc7fb65d53743f5960d8306f04e933b35d10715754d6f4f7e35cc84";

const hex = (b) => Buffer.from(b).toString("hex");
const bin = (h) => Uint8Array.from(Buffer.from(h, "hex"));

/** A witness set the way a CIP-30 wallet commonly returns one: a bare array under key 0. */
const walletWitnessSet = (vkeyHex) =>
  "a10081" + "82" + "5820" + vkeyHex + "5840" + "ab".repeat(64);

async function main() {
  const { keyHashOfVkey, keyHashesInWitnessSet, checkQuorum, assembleUpgradeTx } =
    require("./.upgrade-build/upgrade/witness.js");
  const { Transaction, TransactionWitnessSet, Address } = await import("@evolution-sdk/evolution");

  /** The same witness set as EVOLUTION encodes it — tag 258 — by round-tripping through its codec. */
  const evolutionWitnessSet = (vkeyHex) =>
    hex(TransactionWitnessSet.toCBORBytes(
      TransactionWitnessSet.fromCBORBytes(bin(walletWitnessSet(vkeyHex)))));

  // Proof the two fixtures really are different encodings, so running both is not running one
  // twice. If Evolution ever stopped tagging, this would say so rather than silently halving
  // the coverage.
  assert.notStrictEqual(walletWitnessSet(VKEY_A), evolutionWitnessSet(VKEY_A),
    "the two witness-set fixtures are byte-identical — the tagged form is not being exercised");
  assert.ok(evolutionWitnessSet(VKEY_A).includes("d90102"),
    "Evolution's witness set is no longer tag-258; this test's premise needs rechecking");

  /**
   * A transaction with a REAL body, not the empty `a0` this test used to assemble against.
   *
   * An empty body cannot reveal a slicing bug: there are no fields to land on the wrong side of a
   * boundary. The body below carries inputs, outputs and a fee, and is round-tripped through
   * Evolution's own Transaction codec so it is the encoding the application actually produces.
   */
  const addressHex = hex(Address.toBytes(Address.fromBech32(
    "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y")));
  const realBodyHex =
    "a300" + "81" + "82" + "5820" + "ab".repeat(32) + "00" +
    "01" + "82" + `825839${addressHex}1a000f4240` + `825839${addressHex}1a02faf080` +
    "02" + "1a0002bf20";
  const UNSIGNED_TX = hex(Transaction.toCBORBytes(Transaction.fromCBORBytes(
    bin("84" + realBodyHex + "a0" + "f5" + "f6"))));
  const bodyOf = (txHex) => {
    const { TransactionBody } = require("@evolution-sdk/evolution");
    return hex(TransactionBody.toCBORBytes(Transaction.fromCBORBytes(bin(txHex)).body));
  };
  const witnessSetOf = (txHex) =>
    hex(TransactionWitnessSet.toCBORBytes(Transaction.fromCBORBytes(bin(txHex)).witnessSet));
  // ⛔ The baseline is the body AS EVOLUTION ENCODES IT, read back out of UNSIGNED_TX — not the
  // hand-built hex above. Evolution canonicalises on the way in (it tags the input list as a
  // set, d9 0102), so comparing against the hand-built string reports a body change that never
  // happened. That is the document-versus-encoder error again, this time in the test's own
  // baseline rather than in the code under test.
  const BODY_BEFORE = bodyOf(UNSIGNED_TX);

  // ---- key hash derivation, cross-checked against python ----
  assert.strictEqual(keyHashOfVkey(VKEY_A), HASH_A);
  assert.strictEqual(keyHashOfVkey(VKEY_B), HASH_B);
  assert.throws(() => keyHashOfVkey("00".repeat(31)), /32 bytes/);
  console.log("  OK   key hash is blake2b-224, matching an independent implementation");

  const members = [HASH_A, HASH_B, "cc".repeat(28)];

  // Every witness-dependent assertion, against BOTH encodings that reach this code.
  for (const [encoding, ws] of [
    ["wallet (bare array)", walletWitnessSet],
    ["evolution (tag 258)", evolutionWitnessSet],
  ]) {
    assert.deepStrictEqual(keyHashesInWitnessSet(ws(VKEY_A)), [HASH_A],
      `key hashes not read from the ${encoding} form`);

    const two = checkQuorum([ws(VKEY_A), ws(VKEY_B)], members, 2);
    assert.strictEqual(two.satisfied, true, `2-of-3 failed on the ${encoding} form`);
    assert.strictEqual(two.signed.length, 2);
    assert.strictEqual(two.missing.length, 1);

    // The failure a counting tool makes: one signer pasted twice.
    const dup = checkQuorum([ws(VKEY_A), ws(VKEY_A)], members, 2);
    assert.strictEqual(dup.signed.length, 1, "duplicate signer must collapse");
    assert.strictEqual(dup.satisfied, false, "two pastes from one signer is not a quorum");

    // The other failure: a signature from outside the authority.
    const stranger = checkQuorum([ws(VKEY_A), ws("11".repeat(32))], members, 2);
    assert.strictEqual(stranger.strangers.length, 1);
    assert.strictEqual(stranger.satisfied, false, "a stranger's witness must not count");

    // ---- assembly ----
    const assembled = assembleUpgradeTx(UNSIGNED_TX, [ws(VKEY_A), ws(VKEY_B)]);
    assert.ok(assembled.startsWith("84"), "assembled tx must still be a 4-element array");
    assert.ok(assembled.includes(VKEY_A) && assembled.includes(VKEY_B),
      `both witnesses must survive assembly of the ${encoding} form`);
    // ⛔ The witness set is extracted with Evolution's decoder, NOT by character arithmetic.
    // This line used to read `assembled.slice(4, length - 4)`, which worked only because the
    // transaction body was the two characters "a0" — give it a real body and the slice cuts
    // straight through the middle of one. An empty fixture hid a broken extraction AND made the
    // body-preservation check below unfalsifiable at the same time.
    const back = keyHashesInWitnessSet(witnessSetOf(assembled));
    assert.deepStrictEqual(new Set(back), new Set([HASH_A, HASH_B]));

    // ⭐ The body is what every signature commits to. Asserted against a REAL body via
    // Evolution's decoder — the old check compared the two characters "a0", which an empty
    // body makes true no matter how the slicing behaves.
    assert.strictEqual(bodyOf(assembled), BODY_BEFORE,
      `the transaction body changed during assembly of the ${encoding} form — every signature ` +
      `on it, including the first, would be void`);

    assert.throws(() => assembleUpgradeTx(UNSIGNED_TX, []), /no witnesses/);
    console.log(`  OK   quorum, assembly and body preservation — ${encoding}`);
  }
}

main().catch((e) => { console.error(e); process.exitCode = 1; });
