/**
 * The CBOR walk, against a body EVOLUTION serialised — not one this test invented.
 *
 * The fixture is hand-built as canonical Conway CBOR, then round-tripped through Evolution's own
 * TransactionBody codec. Whatever comes back is Evolution's encoding by construction, so the
 * parser is tested against the encoder it will actually meet rather than against the CDDL someone
 * read. That distinction is not academic here: Evolution emits ada-only outputs in the SHELLEY
 * ARRAY form, and a parser written for the Babbage map form alone would fail on every transaction
 * this application builds while passing every test written from the spec.
 */
const assert = require("node:assert");

async function main() {
  const { TransactionBody, Address } = await import("@evolution-sdk/evolution");
  const { readOutputs, locateMiningSlots, changeHasHeadroom } =
    await import("./.mine-build/locate.js");
  const { mineLowTxHash, patchLovelacePair, readLovelaceSlot, transactionId } =
    await import("./.mine-build/mine.js");
  const { meetsTarget } = await import("./.mine-build/target.js");

  let failures = 0;
  const check = (label, fn) => {
    try { fn(); console.log(`  OK   ${label}`); }
    catch (e) { failures++; console.log(`  FAIL ${label}\n       ${e.message}`); }
  };

  const BECH = "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y";
  const addrHex = Buffer.from(Address.toBytes(Address.fromBech32(BECH))).toString("hex");

  /** Canonical Conway body -> through Evolution's codec -> Evolution's own bytes. */
  const evolutionBody = (selfCoinHex, changeCoinHex, extra = "") => {
    const out = (c) => `825839${addrHex}${c}`;
    const hex = "a3"
      + "00" + "81" + "82" + "5820" + "ab".repeat(32) + "00"
      + "01" + "82" + out(selfCoinHex) + out(changeCoinHex)
      + "02" + "1a0002bf20" + extra;
    const parsed = TransactionBody.fromCBORBytes(Uint8Array.from(Buffer.from(hex, "hex")));
    return TransactionBody.toCBORBytes(parsed);
  };

  const body = evolutionBody("1a000f4240", "1a02faf080"); // 1 ADA self, 50 ADA change

  check("the fixture really is Evolution's own serialisation", () => {
    const reparsed = TransactionBody.fromCBORBytes(body);
    assert.ok(reparsed, "Evolution cannot parse the body this test mines against");
  });

  check("outputs are found in the Shelley array form Evolution actually emits", () => {
    const outs = readOutputs(body);
    assert.strictEqual(outs.length, 2, `found ${outs.length} outputs`);
    assert.strictEqual(outs[0].addressHex, addrHex);
    assert.strictEqual(outs[0].slot.value, 1_000_000);
    assert.strictEqual(outs[1].slot.value, 50_000_000);
  });

  check("the located offsets really point at those coins", () => {
    const outs = readOutputs(body);
    for (const o of outs) {
      assert.strictEqual(readLovelaceSlot(body, o.slot.offset), o.slot.value,
        "the walk's offset and its reported value disagree");
    }
  });

  check("the two mining slots are identified by address AND value", () => {
    const slots = locateMiningSlots(body, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    assert.strictEqual(slots.gains.value, 1_000_000);
    assert.strictEqual(slots.loses.value, 50_000_000);
    assert.notStrictEqual(slots.gainsIndex, slots.losesIndex, "the same output was picked twice");
  });

  check("a value that is not there is refused rather than guessed at", () => {
    assert.throws(() => locateMiningSlots(body, {
      selfAddressHex: addrHex, expectedGainsLovelace: 999, expectedLosesLovelace: 50_000_000,
    }), /Refusing to guess/);
  });

  check("a body with only one self-output explains what is missing", () => {
    const single = TransactionBody.toCBORBytes(TransactionBody.fromCBORBytes(
      Uint8Array.from(Buffer.from(
        "a3" + "00" + "81" + "82" + "5820" + "ab".repeat(32) + "00"
        + "01" + "81" + `825839${addrHex}1a02faf080`
        + "02" + "1a0002bf20", "hex"))));
    assert.throws(() => locateMiningSlots(single, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    }), /BEFORE it is built/);
  });

  check("a coin too small for the 5-byte form is refused, not silently widened", () => {
    // 0.05 ADA = 50000 -> CBOR 0x19 (3-byte form). Incrementing could change the body's length.
    const small = evolutionBody("19c350", "1a02faf080");
    assert.throws(() => readOutputs(small), /not the 5-byte form/);
  });

  // ---- S1's invariants, re-asserted on the REAL body ----------------------
  check("REAL BODY: mining conserves total lovelace and body length", () => {
    const slots = locateMiningSlots(body, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    const totalBefore = slots.gains.value + slots.loses.value;

    const r = mineLowTxHash({
      body, gains: slots.gains, loses: slots.loses,
      targetNibbles: 3, maxAttempts: 200_000,
    });
    assert.ok(r.found, "no 3-nibble hash in 200k attempts against the real body");
    assert.ok(meetsTarget(r.txHash, 3));
    assert.strictEqual(r.body.length, body.length, "the body changed length — the fee would move");
    assert.strictEqual(
      readLovelaceSlot(r.body, slots.gains.offset) + readLovelaceSlot(r.body, slots.loses.offset),
      totalBefore, "the mined body does not balance");
    assert.strictEqual(transactionId(r.body), r.txHash);
  });

  check("REAL BODY: the mined body is still a TransactionBody Evolution can parse", () => {
    const slots = locateMiningSlots(body, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    const r = mineLowTxHash({
      body, gains: slots.gains, loses: slots.loses, targetNibbles: 2, maxAttempts: 50_000,
    });
    assert.ok(r.found);
    const reparsed = TransactionBody.fromCBORBytes(r.body);
    assert.ok(reparsed, "mining produced bytes Evolution can no longer parse as a body");
    // And the patched values survive a decode — proof the right bytes moved.
    const outs = readOutputs(r.body);
    assert.strictEqual(outs[0].slot.value, 1_000_000 + r.nonce);
    assert.strictEqual(outs[1].slot.value, 50_000_000 - r.nonce);
  });

  check("REAL BODY: the caller's body is untouched by a search", () => {
    const before = Buffer.from(body).toString("hex");
    const slots = locateMiningSlots(body, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    mineLowTxHash({ body, gains: slots.gains, loses: slots.loses, targetNibbles: 2, maxAttempts: 50_000 });
    assert.strictEqual(Buffer.from(body).toString("hex"), before,
      "the user's unmined transaction was mutated");
  });

  check("min-ADA headroom is checked against the expected attempt count", () => {
    // 50 ADA change, 1 ADA floor, 65,536 expected attempts at target 4: ample.
    assert.ok(changeHasHeadroom(50_000_000, 1_000_000, 65_536).ok);
    // A change output already at the floor cannot afford a single attempt.
    const tight = changeHasHeadroom(1_000_000, 1_000_000, 65_536);
    assert.ok(!tight.ok);
    assert.strictEqual(tight.shortfall, 65_536);
  });

  if (failures > 0) throw new Error(`${failures} locate check(s) failed`);
  console.log("\n  the walk is verified against Evolution's own encoding, not against the CDDL");
}

main().catch((e) => { console.error(e); process.exit(1); });
