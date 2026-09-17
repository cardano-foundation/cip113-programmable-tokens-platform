/**
 * Putting a mined body back into its transaction.
 *
 * A wrong splice does not throw: the result is still valid CBOR, still submittable, and carries a
 * body that is no longer the mined one. The hash would simply not be low and nothing downstream
 * would notice — the deployment would quietly lose the property it paid for.
 */
const assert = require("node:assert");

async function main() {
  const { Transaction, TransactionBody, Address } = await import("@evolution-sdk/evolution");
  const { spliceMinedBody, locateMiningSlots, readOutputs } = await import("./.mine-build/locate.js");
  const { mineLowTxHash, transactionId } = await import("./.mine-build/mine.js");
  const { meetsTarget } = await import("./.mine-build/target.js");

  let failures = 0;
  const check = (label, fn) => {
    try { fn(); console.log(`  OK   ${label}`); }
    catch (e) { failures++; console.log(`  FAIL ${label}\n       ${e.message}`); }
  };

  const hex = (b) => Buffer.from(b).toString("hex");
  const bin = (h) => Uint8Array.from(Buffer.from(h, "hex"));
  const addrHex = hex(Address.toBytes(Address.fromBech32(
    "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y")));

  // A transaction shaped like the reference-script one: a mining output, change, and metadata.
  const body = "a300" + "81" + "82" + "5820" + "ab".repeat(32) + "00"
    + "01" + "82" + `825839${addrHex}1a000f4240` + `825839${addrHex}1a02faf080`
    + "02" + "1a0002bf20";
  const txHex = hex(Transaction.toCBORBytes(Transaction.fromCBORBytes(
    bin("84" + body + "a0" + "f5" + "a11907c0a1006673616d706c65"))));
  // TransactionBody.toCBORBytes, not Transaction.toCBORBytes — the latter wants a whole
  // transaction and fails with "body is missing", which reads like a malformed fixture.
  const bodyOf = (h) => hex(TransactionBody.toCBORBytes(Transaction.fromCBORBytes(bin(h)).body));

  check("a mined body splices back, and the transaction still parses", () => {
    const original = bin(bodyOf(txHex));
    const slots = locateMiningSlots(original, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    const r = mineLowTxHash({
      body: original, gains: slots.gains, loses: slots.loses,
      targetNibbles: 3, maxAttempts: 200_000,
    });
    assert.ok(r.found, "no 3-nibble hash found");

    const spliced = spliceMinedBody(txHex, r.body);
    assert.ok(Transaction.fromCBORBytes(bin(spliced)), "the spliced transaction does not parse");

    // ⭐ The point: the spliced transaction's id IS the mined one.
    assert.strictEqual(bodyOf(spliced), hex(r.body), "the spliced body is not the mined body");
    assert.strictEqual(transactionId(bin(bodyOf(spliced))), r.txHash);
    assert.ok(meetsTarget(r.txHash, 3), "the mined hash does not meet the target");
  });

  check("the metadata survives the splice — CIP-171 rides that transaction", () => {
    const original = bin(bodyOf(txHex));
    const slots = locateMiningSlots(original, {
      selfAddressHex: addrHex, expectedGainsLovelace: 1_000_000, expectedLosesLovelace: 50_000_000,
    });
    const r = mineLowTxHash({
      body: original, gains: slots.gains, loses: slots.loses, targetNibbles: 2, maxAttempts: 50_000,
    });
    const spliced = spliceMinedBody(txHex, r.body);
    assert.ok(spliced.includes("a11907c0a1006673616d706c65"),
      "auxiliary data was lost — a CIP-171 record would vanish");
  });

  check("a body of the wrong length is refused, not spliced", () => {
    const short = bin(bodyOf(txHex)).subarray(0, 40);
    assert.throws(() => spliceMinedBody(txHex, short), /must never change the body's LENGTH/);
  });

  if (failures > 0) throw new Error(`${failures} splice check(s) failed`);
  console.log("\n  the mined body reaches the transaction, metadata intact");
}
main().catch((e) => { console.error(e); process.exit(1); });
