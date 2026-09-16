/**
 * The miner, checked against the ways it could be wrong without the hash noticing.
 *
 * A mined transaction that does not balance still HASHES fine — the digest cannot tell you the
 * outputs no longer sum to the inputs. So conservation is asserted directly, not inferred from a
 * search succeeding.
 */
const assert = require("node:assert");

async function main() {
  const { mineLowTxHash, patchLovelacePair, readLovelaceSlot, transactionId, calibrate } =
    await import("./.mine-build/mine.js");
  const {
    leadingZeroNibbles, meetsTarget, expectedAttempts, expectedSeconds,
    collisionRisk, humaniseSeconds, DEFAULT_TARGET_NIBBLES,
  } = await import("./.mine-build/target.js");

  let failures = 0;
  const check = (label, fn) => {
    try { fn(); console.log(`  OK   ${label}`); }
    catch (e) { failures++; console.log(`  FAIL ${label}\n       ${e.message}`); }
  };

  /** A stand-in body with two 5-byte CBOR uints at known offsets, like a real pair of outputs. */
  const makeBody = (size, gainsAt, losesAt, gains = 1_000_000, loses = 50_000_000) => {
    const b = new Uint8Array(size);
    for (let i = 0; i < size; i++) b[i] = (i * 31) & 0xff;
    const put = (off, v) => {
      b[off] = 0x1a;
      b[off + 1] = (v >>> 24) & 0xff; b[off + 2] = (v >>> 16) & 0xff;
      b[off + 3] = (v >>> 8) & 0xff;  b[off + 4] = v & 0xff;
    };
    put(gainsAt, gains); put(losesAt, loses);
    return b;
  };

  check("leading zero nibbles are counted per hex digit, not per byte", () => {
    assert.strictEqual(leadingZeroNibbles("0abc"), 1);
    assert.strictEqual(leadingZeroNibbles("00abc"), 2);
    assert.strictEqual(leadingZeroNibbles("000abc"), 3);
    assert.strictEqual(leadingZeroNibbles("abc"), 0);
    assert.ok(meetsTarget("0000ffff", 4));
    assert.ok(!meetsTarget("000fffff", 4));
  });

  check("a slot that is not a 5-byte CBOR uint is refused, not patched", () => {
    const b = makeBody(256, 100, 150);
    assert.throws(() => readLovelaceSlot(b, 101), /does not hold a 5-byte CBOR unsigned integer/);
  });

  // ---- the invariant the hash cannot check for you -------------------------
  check("every attempt conserves total lovelace", () => {
    const b = makeBody(512, 100, 200);
    const before = readLovelaceSlot(b, 100) + readLovelaceSlot(b, 200);
    for (const delta of [1, 7, 1000, 65_535, 1_000_000]) {
      const work = b.slice();
      patchLovelacePair(work, { offset: 100, value: 1_000_000 }, { offset: 200, value: 50_000_000 }, delta);
      const after = readLovelaceSlot(work, 100) + readLovelaceSlot(work, 200);
      assert.strictEqual(after, before, `delta ${delta} changed the total by ${after - before}`);
    }
  });

  check("the body length never changes, so the fee cannot", () => {
    const b = makeBody(512, 100, 200);
    const work = b.slice();
    patchLovelacePair(work, { offset: 100, value: 1_000_000 }, { offset: 200, value: 50_000_000 }, 123_456);
    assert.strictEqual(work.length, b.length);
    assert.strictEqual(work[100], 0x1a, "the CBOR header must stay the 5-byte form");
    assert.strictEqual(work[200], 0x1a);
  });

  check("crossing the 4-byte CBOR boundary is refused rather than silently widening", () => {
    const b = makeBody(512, 100, 200, 4_294_967_290, 50_000_000);
    assert.throws(
      () => patchLovelacePair(b, { offset: 100, value: 4_294_967_290 }, { offset: 200, value: 50_000_000 }, 10),
      /CBOR widens/);
  });

  check("driving change negative is refused", () => {
    const b = makeBody(512, 100, 200, 1_000_000, 5);
    assert.throws(
      () => patchLovelacePair(b, { offset: 100, value: 1_000_000 }, { offset: 200, value: 5 }, 10),
      /negative/);
  });

  // ---- the search --------------------------------------------------------
  check("mining finds a hash that actually meets the target", () => {
    const b = makeBody(1024, 300, 400);
    const r = mineLowTxHash({
      body: b, gains: { offset: 300, value: 1_000_000 }, loses: { offset: 400, value: 50_000_000 },
      targetNibbles: 3, maxAttempts: 200_000,
    });
    assert.ok(r.found, `no hash found in 200k attempts at 3 nibbles (expected ~4k)`);
    assert.ok(meetsTarget(r.txHash, 3), `returned ${r.txHash} which does not meet the target`);
    // The returned body must be the one that hashes to the returned id — not the original.
    assert.strictEqual(transactionId(r.body), r.txHash,
      "the returned body does not hash to the returned transaction id");
  });

  check("the mined body conserves lovelace too", () => {
    const b = makeBody(1024, 300, 400);
    const before = readLovelaceSlot(b, 300) + readLovelaceSlot(b, 400);
    const r = mineLowTxHash({
      body: b, gains: { offset: 300, value: 1_000_000 }, loses: { offset: 400, value: 50_000_000 },
      targetNibbles: 3, maxAttempts: 200_000,
    });
    assert.ok(r.found);
    assert.strictEqual(readLovelaceSlot(r.body, 300) + readLovelaceSlot(r.body, 400), before);
    assert.strictEqual(readLovelaceSlot(r.body, 300), 1_000_000 + r.nonce,
      "the winning nonce must be the number of lovelace actually moved");
  });

  check("the caller's body is never mutated", () => {
    const b = makeBody(1024, 300, 400);
    const copy = b.slice();
    mineLowTxHash({
      body: b, gains: { offset: 300, value: 1_000_000 }, loses: { offset: 400, value: 50_000_000 },
      targetNibbles: 3, maxAttempts: 50_000,
    });
    assert.deepStrictEqual(Array.from(b), Array.from(copy),
      "mining mutated the caller's body — an abandoned search would leave a moved lovelace behind");
  });

  check("cancellation stops the search and reports it", () => {
    const b = makeBody(1024, 300, 400);
    let ticks = 0;
    const r = mineLowTxHash({
      body: b, gains: { offset: 300, value: 1_000_000 }, loses: { offset: 400, value: 50_000_000 },
      // 8 nibbles will not be found; the point is that cancelling works.
      targetNibbles: 8, maxAttempts: 10_000_000,
      progressEvery: 512, onProgress: () => { ticks++; },
      shouldCancel: () => ticks >= 3,
    });
    assert.ok(r.cancelled, "a cancelled search must say so");
    assert.ok(!r.found);
    assert.ok(r.attempts < 10_000_000, "cancellation did not actually stop the loop");
  });

  check("an already-qualifying body is returned with nonce 0, not re-mined", () => {
    // Target 0 is met by every hash.
    const b = makeBody(512, 100, 200);
    const r = mineLowTxHash({
      body: b, gains: { offset: 100, value: 1_000_000 }, loses: { offset: 200, value: 50_000_000 },
      targetNibbles: 0, maxAttempts: 10,
    });
    assert.ok(r.found);
    assert.strictEqual(r.nonce, 0);
  });

  // ---- estimates ---------------------------------------------------------
  check("estimates are 16^n and scale with the measured rate", () => {
    assert.strictEqual(expectedAttempts(4), 65536);
    assert.strictEqual(expectedAttempts(6), 16777216);
    assert.strictEqual(expectedSeconds(4, 65536), 1);
    assert.strictEqual(expectedSeconds(4, 0), Infinity);
    assert.match(humaniseSeconds(0.25), /ms$/);
    assert.match(humaniseSeconds(45), /s$/);
    assert.match(humaniseSeconds(600), /min$/);
  });

  check("the default target's collision risk matches the stated 0.008%", () => {
    const risk = collisionRisk(DEFAULT_TARGET_NIBBLES, 5);
    assert.ok(risk > 0.00007 && risk < 0.00008, `risk was ${risk}`);
    // And a weaker target is meaningfully worse, or the number is not doing any work.
    assert.ok(collisionRisk(2, 5) > risk * 100);
  });

  // ---- calibration, measured here rather than asserted --------------------
  const b2k = makeBody(2048, 300, 400);
  const rate = calibrate(b2k, 400);
  console.log(`\n  measured on this machine: ${rate.toLocaleString()} h/s over a 2 KB body`);
  console.log(`  target 4 -> ${humaniseSeconds(expectedSeconds(4, rate))}` +
              `   target 5 -> ${humaniseSeconds(expectedSeconds(5, rate))}` +
              `   target 6 -> ${humaniseSeconds(expectedSeconds(6, rate))}`);

  if (failures > 0) throw new Error(`${failures} mining check(s) failed`);
  console.log("\n  conservation, width stability and cancellation all hold");
}

main().catch((e) => { console.error(e); process.exit(1); });
