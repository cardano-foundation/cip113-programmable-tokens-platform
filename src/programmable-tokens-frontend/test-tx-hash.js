/**
 * The transaction hash, and whether a witness really signed it.
 *
 * ## The fixtures are real transactions, not ones we wrote
 *
 * `test-fixtures/real-preview-txs.json` holds two Conway transactions pulled from
 * the chain — they are this protocol's own preview deployment, and their hashes
 * appear in `protocol-bootstraps-preview.json`. That matters more than it looks:
 * a hash function can only be shown correct against an encoder it did not
 * produce, and the node is the only encoder whose opinion counts. A fixture we
 * built ourselves would agree with our own body extraction by construction and
 * prove nothing.
 *
 * The two differ in size by an order of magnitude (420 vs 3489 bytes) so the
 * CBOR walk is exercised across both a short body and one with real scripts,
 * datums and a tag-258 witness set.
 *
 * ## The signature check is the point
 *
 * `checkQuorum` reduces a witness to its key hash and matches that against the
 * declared members — so it answers "does this CLAIM to be a declared key". The
 * case it cannot see is a real declared vkey beside a forged signature, which is
 * exactly what an attacker submits and exactly what "prove you control this key"
 * has to reject. That case has its own test below, and it is the reason this
 * file exists.
 */
const assert = require("node:assert");
const { readFileSync } = require("node:fs");

async function main() {
  const { transactionHash, transactionBodyBytes, verifyWitnessSet } =
    await import("./.hash-build/hash.js");
  const { blake2b } = await import("@noble/hashes/blake2");
  const { ed25519 } = await import("@noble/curves/ed25519.js");

  const toHex = (b) =>
    Array.from(b).map((x) => x.toString(16).padStart(2, "0")).join("");

  const fixture = JSON.parse(
    readFileSync("./test-fixtures/real-preview-txs.json", "utf8")
  );
  assert.strictEqual(fixture.transactions.length, 2, "expected two real transactions");

  let ran = 0;

  // ── The hash reproduces what the chain calls these transactions ────────────
  for (const tx of fixture.transactions) {
    const got = transactionHash(tx.cbor);
    assert.strictEqual(
      got,
      tx.txHash,
      `hash mismatch for a REAL transaction: got ${got}, chain says ${tx.txHash}`
    );
    console.log(`  OK   real tx ${tx.txHash.slice(0, 12)}… hashes to its chain id (${tx.cbor.length / 2} B)`);
    ran++;
  }

  // Guard the premise: if both fixtures were the same size the "short body AND
  // big body" claim above would be decoration.
  const sizes = fixture.transactions.map((t) => t.cbor.length / 2);
  assert.ok(
    Math.max(...sizes) > Math.min(...sizes) * 4,
    `fixtures are too similar in size (${sizes}) to claim they exercise different shapes`
  );
  console.log(`  OK   fixtures differ in scale (${sizes[0]} B vs ${sizes[1]} B)`);
  ran++;

  // ── The real signatures in those transactions verify ──────────────────────
  const big = fixture.transactions[1];
  const bigWitnessSet = witnessSetHexOf(big.cbor);
  const realChecks = verifyWitnessSet(big.cbor, bigWitnessSet);
  assert.ok(realChecks.length > 0, "the real transaction carried no vkey witness to check");
  assert.ok(
    realChecks.every((c) => c.valid),
    `a signature the chain ACCEPTED failed verification: ${JSON.stringify(realChecks)}`
  );
  console.log(`  OK   ${realChecks.length} real on-chain signature(s) verify against the body hash`);
  ran++;

  // ── A forged signature under a real declared key is refused ───────────────
  // The case checkQuorum cannot see. The vkey is genuine and would match a
  // declared member; only the 64 signature bytes are wrong.
  const genuineVkey = realChecks[0].vkeyHex;
  const forged = witnessSetOf(genuineVkey, "ab".repeat(64));
  const forgedChecks = verifyWitnessSet(big.cbor, forged);
  assert.strictEqual(forgedChecks.length, 1);
  assert.strictEqual(
    forgedChecks[0].keyHash,
    realChecks[0].keyHash,
    "the forged witness must carry the SAME key hash, or it is not testing what it claims"
  );
  assert.strictEqual(
    forgedChecks[0].valid,
    false,
    "a forged signature under a genuine declared vkey was accepted"
  );
  console.log("  OK   forged signature under a genuine vkey is refused (same key hash, invalid)");
  ran++;

  // ── A freshly generated key signing THIS body verifies ────────────────────
  const sk = ed25519.utils.randomSecretKey
    ? ed25519.utils.randomSecretKey()
    : ed25519.utils.randomPrivateKey();
  const pk = ed25519.getPublicKey(sk);
  const bodyHash = blake2b(transactionBodyBytes(big.cbor), { dkLen: 32 });
  const sig = ed25519.sign(bodyHash, sk);
  const mine = verifyWitnessSet(big.cbor, witnessSetOf(toHex(pk), toHex(sig)));
  assert.strictEqual(mine[0].valid, true, "a signature we just made over this body did not verify");
  console.log("  OK   a signature made over this body verifies");
  ran++;

  // ── The same signature against a DIFFERENT transaction is refused ─────────
  // This is what makes the collected witnesses body-bound: if the deployer
  // rebuilds the transaction, every signature already gathered must stop
  // verifying, or "frozen body" is a convention rather than a fact.
  const other = fixture.transactions[0];
  const reused = verifyWitnessSet(other.cbor, witnessSetOf(toHex(pk), toHex(sig)));
  assert.strictEqual(
    reused[0].valid,
    false,
    "a signature over one transaction verified against another — signatures are not body-bound"
  );
  console.log("  OK   that same signature is refused against a different transaction");
  ran++;

  // ── A wrong-LENGTH signature is reported, not silently dropped ────────────
  // Encoded as a VALID CBOR byte string of 10 bytes (0x4a), not as a 64-byte
  // header with 10 bytes behind it. The latter is truncated CBOR and is a
  // different failure — covered separately below. Getting this wrong the first
  // time is why both cases are here.
  const shortSig = "a10081" + "82" + "5820" + toHex(pk) + "4a" + "ab".repeat(10);
  const short = verifyWitnessSet(big.cbor, shortSig);
  assert.strictEqual(short[0].valid, false);
  assert.match(short[0].problem ?? "", /64 bytes/);
  console.log("  OK   a wrong-length signature is reported rather than skipped");
  ran++;

  // ── Truncated CBOR is refused in words a signer can act on ────────────────
  // What a half-copied paste actually looks like. Evolution answers this with
  // "Insufficient data for byte string", which does not tell anyone to re-copy.
  const truncated = "a10081" + "82" + "5820" + toHex(pk) + "5840" + "ab".repeat(10);
  assert.throws(
    () => verifyWitnessSet(big.cbor, truncated),
    /whole value was copied/,
    "truncated input must say what to do about it"
  );
  console.log("  OK   truncated CBOR is refused with an actionable message");
  ran++;

  // ── An absent key 0 is empty, not an exception ────────────────────────────
  assert.deepStrictEqual(verifyWitnessSet(big.cbor, "a0"), []);
  console.log("  OK   a witness set with no vkey witnesses yields no checks");
  ran++;

  console.log(`\n${ran} checks passed`);
}

/** A minimal CIP-30 witness set: {0: [[vkey, signature]]}. */
function witnessSetOf(vkeyHex, sigHex) {
  return "a10081" + "82" + "5820" + vkeyHex + "5840" + sigHex;
}

/** The witness-set element of a transaction, as hex. */
function witnessSetHexOf(txHex) {
  const {
    CBOR: EvoCBOR,
  } = require("@evolution-sdk/evolution");
  const bytes = Uint8Array.from((txHex.match(/../g) ?? []).map((b) => parseInt(b, 16)));
  const afterBody = EvoCBOR.decodeItemWithOffset(bytes, 1).newOffset;
  const afterWs = EvoCBOR.decodeItemWithOffset(bytes, afterBody).newOffset;
  return Buffer.from(bytes.subarray(afterBody, afterWs)).toString("hex");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
