/**
 * Does `assembleSignedTxPreservingBody` actually preserve the body?
 *
 * ⛔ THIS ASSERTION HAD NEVER BEEN MADE, and it is the one holding up the mining feature. The
 * claim that a mined transaction survives signing rests entirely on this function keeping the
 * body byte-identical while merging a wallet's witnesses — and a wrong slice here does NOT throw.
 * It produces a transaction that is still valid CBOR, still submittable, and carries a body that
 * is no longer the one that was mined. The user pays, the transaction lands, and the outputs
 * simply do not sort where they were meant to. The hash cannot catch it; only this can.
 *
 * Fixtures are round-tripped through Evolution's OWN Transaction codec, so the bytes under test
 * are the encoding this application meets rather than one assembled from the CDDL. That
 * distinction already caught a real defect in the output parser: Evolution emits ada-only outputs
 * in the Shelley array form, not the Babbage map form the spec leads you to write.
 */
const assert = require("node:assert");

async function main() {
  const { Transaction, TransactionBody, Address } = await import("@evolution-sdk/evolution");
  const { blake2b } = await import("@noble/hashes/blake2b");
  const { assembleSignedTxPreservingBody } = await import("./.witness-build/witness-set.js");

  /**
   * Count vkey witnesses by asking EVOLUTION, not by re-parsing with the module under test.
   *
   * An earlier draft counted them with the module's own splitVkeyWitnesses, which is both a
   * misuse (it takes the key-0 VALUE as bytes, not a transaction as hex) and circular — a parser
   * confirming its own output is the shape of check this whole exercise exists to avoid.
   */
  const vkeyCount = (txHex) => {
    const ws = Transaction.fromCBORBytes(bin(txHex)).witnessSet;
    const list = ws?.vkeyWitnesses;
    if (!Array.isArray(list)) {
      throw new Error(
        `witnessSet has no vkeyWitnesses array (keys: ${Object.keys(ws ?? {}).join(", ")}). ` +
        `Failing rather than returning 0 — a count helper that quietly answers zero would turn ` +
        `"the witnesses were dropped" and "I looked in the wrong place" into the same result.`);
    }
    return list.length;
  };

  let failures = 0;
  const check = (label, fn) => {
    try { fn(); console.log(`  OK   ${label}`); }
    catch (e) { failures++; console.log(`  FAIL ${label}\n       ${e.message}`); }
  };

  const hex = (b) => Buffer.from(b).toString("hex");
  const bin = (h) => Uint8Array.from(Buffer.from(h, "hex"));
  const addrHex = hex(Address.toBytes(Address.fromBech32(
    "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y")));

  const bodyHex = (extra = "") =>
    "a" + (extra ? "4" : "3")
    + "00" + "81" + "82" + "5820" + "ab".repeat(32) + "00"
    + "01" + "82" + `825839${addrHex}1a000f4240` + `825839${addrHex}1a02faf080`
    + "02" + "1a0002bf20" + extra;

  /** A whole transaction, as Evolution encodes it: [body, witnessSet, isValid, auxiliaryData]. */
  const evolutionTx = (witnessSetHex, { withMetadata = false } = {}) => {
    // Key 7 is auxiliary_data_hash — present on any transaction carrying CIP-171 metadata.
    const body = bodyHex(withMetadata ? "07" + "5820" + "cd".repeat(32) : "");
    const aux = withMetadata ? "a11907c0a1006673616d706c65" : "f6";
    const whole = "84" + body + witnessSetHex + "f5" + aux;
    return hex(Transaction.toCBORBytes(Transaction.fromCBORBytes(bin(whole))));
  };

  /** A wallet witness set: one vkey witness under key 0. */
  const walletWs = (seed) =>
    "a100" + "81" + "82" + "5820" + seed.repeat(32) + "5840" + seed.repeat(64);

  /** The body bytes of a whole transaction, via Evolution's own decoder. */
  const bodyOf = (txHex) => hex(TransactionBody.toCBORBytes(Transaction.fromCBORBytes(bin(txHex)).body));
  const txIdOf = (txHex) => hex(blake2b(bin(bodyOf(txHex)), { dkLen: 32 }));

  // ---- the assertion that matters ----------------------------------------
  check("⭐ the body is byte-identical after signing, and so is the transaction id", () => {
    const unsigned = evolutionTx("a0");
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("11"));

    assert.strictEqual(bodyOf(signed), bodyOf(unsigned),
      "the body changed while merging witnesses — a mined hash would not survive this");
    assert.strictEqual(txIdOf(signed), txIdOf(unsigned),
      "the transaction id moved; everything mining relies on is void");
  });

  check("⭐ and it holds for a transaction carrying metadata, as every CIP-171 one does", () => {
    const unsigned = evolutionTx("a0", { withMetadata: true });
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("22"));

    assert.strictEqual(bodyOf(signed), bodyOf(unsigned));
    assert.strictEqual(txIdOf(signed), txIdOf(unsigned),
      "auxiliary data shifted the slice — CIP-171 transactions would be mis-assembled");
  });

  check("the witness actually arrives", () => {
    const unsigned = evolutionTx("a0");
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("33"));
    const parsed = Transaction.fromCBORBytes(bin(signed));
    assert.ok(parsed.witnessSet, "no witness set on the assembled transaction");
    assert.ok(signed.includes("33".repeat(32)), "the wallet's vkey is not in the result");
  });

  check("the assembled transaction still parses as a Transaction", () => {
    const unsigned = evolutionTx("a0", { withMetadata: true });
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("44"));
    assert.ok(Transaction.fromCBORBytes(bin(signed)), "Evolution cannot parse the result");
  });

  // ---- the counter-signature path ----------------------------------------
  check("⭐ counter-signing preserves the body AND keeps both witnesses", () => {
    const existing = "a100" + "81" + "82" + "5820" + "55".repeat(32) + "5840" + "55".repeat(64);
    const unsigned = evolutionTx(existing);
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("66"));

    assert.strictEqual(bodyOf(signed), bodyOf(unsigned),
      "counter-signing changed the body — the FIRST signature would be void too");
    assert.strictEqual(txIdOf(signed), txIdOf(unsigned));
    assert.ok(signed.includes("55".repeat(32)), "the existing witness was dropped");
    assert.ok(signed.includes("66".repeat(32)), "the new witness is missing");
    assert.strictEqual(vkeyCount(signed), 2, `expected 2 vkey witnesses, got ${vkeyCount(signed)}`);
  });

  check("a duplicate signature does not produce a duplicate witness", () => {
    const existing = "a100" + "81" + "82" + "5820" + "77".repeat(32) + "5840" + "77".repeat(64);
    const unsigned = evolutionTx(existing);
    const signed = assembleSignedTxPreservingBody(unsigned, walletWs("77"));
    assert.strictEqual(bodyOf(signed), bodyOf(unsigned));
    assert.strictEqual(vkeyCount(signed), 1, "the same key signed twice and produced two witnesses");
  });

  // ---- the refusals ------------------------------------------------------
  check("an empty wallet witness set is refused rather than submitted unsigned", () => {
    const unsigned = evolutionTx("a0");
    assert.throws(() => assembleSignedTxPreservingBody(unsigned, "a0"), /nothing was signed/);
  });

  check("a witness set with no key 0 is refused", () => {
    const unsigned = evolutionTx("a0");
    // key 1 = native scripts, no vkeys at all
    assert.throws(() => assembleSignedTxPreservingBody(unsigned, "a10180"), /nothing was/);
  });

  if (failures > 0) throw new Error(`${failures} witness-preservation check(s) failed`);
  console.log("\n  the body survives signing — the seam mining depends on holds");
}

main().catch((e) => { console.error(e); process.exit(1); });
