/**
 * Seed selection reads EVOLUTION's UTxO fields, not the platform's record fields.
 *
 * ⛔ WHY THIS EXISTS. `client.getUtxos(address)` resolves to the PROVIDER's method
 * (`ReadOnlyClientEffect extends Provider.ProviderEffect`), so it returns Evolution `UTxO`
 * objects whose reference fields are `transactionId` (a TransactionHash) and `index` (a bigint).
 * The selection read `txHash` and `outputIndex` — the names of the record format the platform
 * WRITES — and got `undefined` with no type error, because the value crossed an `as` boundary.
 *
 * Measured against a real preview wallet holding 84 plain UTxOs and 3642 ADA: the page reported
 * no usable UTxOs. Nothing was wrong with the wallet.
 *
 * Run: npm run test:seeds
 */
let pass = 0;
let fail = 0;
function ok(cond, name) {
  if (cond) {
    pass++;
    console.log(`ok   ${name}`);
  } else {
    fail++;
    console.log(`FAIL ${name}`);
  }
}

async function main() {
const { TransactionHash, Assets } = await import("@evolution-sdk/evolution");
const { selectSeedUtxos, toChainUtxo, isPlainSeedCandidate } = await import(
  "./.seeds-build/ceremony.js"
);

const HASHES = [
  "5403b9c6cdf1ecd35403b9c6cdf1ecd35403b9c6cdf1ecd35403b9c6cdf1ecd3",
  "048e2655c6a0335a048e2655c6a0335a048e2655c6a0335a048e2655c6a0335a",
  "9d1b7bfbdead14879d1b7bfbdead14879d1b7bfbdead14879d1b7bfbdead1487",
  "bdb35bee0c6c250dbdb35bee0c6c250dbdb35bee0c6c250dbdb35bee0c6c250d",
];

/** A UTxO shaped as the PROVIDER returns one. */
function providerUtxo(hashHex, index, { assets, scriptRef } = {}) {
  return {
    transactionId: TransactionHash.fromHex(hashHex),
    index: BigInt(index),
    assets: assets ?? Assets.fromLovelace(40_000_000n),
    ...(scriptRef ? { scriptRef } : {}),
  };
}

// ── toChainUtxo ───────────────────────────────────────────────────────────────
{
  const ref = toChainUtxo(providerUtxo(HASHES[0], 0));
  ok(ref.txHash === HASHES[0], "toChainUtxo reads transactionId into a 64-char lowercase hex txHash");
  ok(ref.outputIndex === 0 && typeof ref.outputIndex === "number",
    "toChainUtxo converts the bigint index to a number");
}
{
  // ⛔ The regression itself: the OLD record shape must be rejected loudly, not read as valid.
  let threw = false;
  try {
    toChainUtxo({ txHash: HASHES[0], outputIndex: 0 });
  } catch (e) {
    threw = /transaction id/i.test(e.message);
  }
  ok(threw, "toChainUtxo REFUSES an object carrying only txHash/outputIndex, naming the field");
}
{
  let threw = false;
  try {
    toChainUtxo({ transactionId: TransactionHash.fromHex(HASHES[0]), index: undefined });
  } catch (e) {
    threw = /output index/i.test(e.message);
  }
  ok(threw, "toChainUtxo REFUSES a missing index, naming the field");
}

// ── isPlainSeedCandidate ──────────────────────────────────────────────────────
{
  ok(isPlainSeedCandidate(providerUtxo(HASHES[0], 0)) === true, "an ada-only UTxO is a seed candidate");
  ok(isPlainSeedCandidate(providerUtxo(HASHES[0], 0, { scriptRef: { cbor: "00" } })) === false,
    "a UTxO carrying a reference script is NOT a candidate");
  const withAsset = Assets.fromRecord({
    lovelace: 40_000_000n,
    ["a".repeat(56) + "deadbeef"]: 1n,
  });
  ok(isPlainSeedCandidate(providerUtxo(HASHES[0], 0, { assets: withAsset })) === false,
    "a UTxO carrying a native asset is NOT a candidate");
  ok(isPlainSeedCandidate({ assets: "not-an-assets-object" }) === false,
    "a NON-OBJECT assets value is treated as NOT plain (getUnits would call it ada-only)");
  ok(isPlainSeedCandidate({ assets: undefined }) === false,
    "a missing assets value is treated as NOT plain");
}

// ── selectSeedUtxos ───────────────────────────────────────────────────────────
{
  const utxos = HASHES.map((h, i) => providerUtxo(h, i));
  const seeds = selectSeedUtxos(utxos, "addr_test1irrelevant");
  ok(seeds !== null, "four provider-shaped ada-only UTxOs yield seeds");
  ok(seeds.paramsSeed.txHash === HASHES[0] && seeds.paramsSeed.outputIndex === 0,
    "the first seed carries a real hash and index, not undefined");
  const refs = new Set(
    [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed].map((r) => `${r.txHash}#${r.outputIndex}`),
  );
  // ⛔ paramsSeed and upgradeMultisig are the same type; one UTxO in both slots deploys fine and
  // makes the upgrade-multisig check vacuous.
  ok(refs.size === 3, "the three seeds are DISTINCT outrefs");
}
{
  const two = HASHES.slice(0, 2).map((h, i) => providerUtxo(h, i));
  ok(selectSeedUtxos(two, "addr_test1irrelevant") === null, "two usable UTxOs yield null, not a partial set");
}
{
  // A wallet full of UTxOs that all carry reference scripts is NOT a wallet to split.
  const all = HASHES.map((h, i) => providerUtxo(h, i, { scriptRef: { cbor: "00" } }));
  ok(selectSeedUtxos(all, "addr_test1irrelevant") === null,
    "reference-script UTxOs are never selected as seeds");
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("HARNESS ERROR:", e);
  process.exit(1);
});
