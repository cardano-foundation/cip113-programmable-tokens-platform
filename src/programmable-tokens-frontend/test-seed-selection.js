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
const {
  selectSeedUtxos, toChainUtxo, isPlainSeedCandidate, assertCeremonyContext,
  resolveSeedUtxos, lovelaceOfUtxo, cborOf,
  buildWithFreshUtxos, isMissingUtxoEvaluation, missingInputOf, fingerprintUtxos, withoutOutputsOf,
} = await import("./.seeds-build/ceremony.js");

const HASHES = [
  "5403b9c6cdf1ecd35403b9c6cdf1ecd35403b9c6cdf1ecd35403b9c6cdf1ecd3",
  "048e2655c6a0335a048e2655c6a0335a048e2655c6a0335a048e2655c6a0335a",
  "9d1b7bfbdead14879d1b7bfbdead14879d1b7bfbdead14879d1b7bfbdead1487",
  "bdb35bee0c6c250dbdb35bee0c6c250dbdb35bee0c6c250dbdb35bee0c6c250d",
];

/** A UTxO shaped as the PROVIDER returns one. */
function providerUtxo(hashHex, index, { assets, scriptRef, lovelace } = {}) {
  return {
    transactionId: TransactionHash.fromHex(hashHex),
    index: BigInt(index),
    assets: assets ?? Assets.fromLovelace(lovelace ?? 40_000_000n),
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
  // Not pinned to a particular hash: equal-value UTxOs tie-break lexicographically on the outref,
  // which is deliberate (reproducible across machines) and not a property worth freezing here.
  ok(/^[0-9a-f]{64}$/.test(seeds.paramsSeed.txHash) &&
     Number.isSafeInteger(seeds.paramsSeed.outputIndex) &&
     HASHES.includes(seeds.paramsSeed.txHash),
    "the first seed carries a REAL hash and index from the input, not undefined");
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

// ── assertCeremonyContext ─────────────────────────────────────────────────────
// ⛔ THE POINT: this SDK declares `Address = string`. A parsed Evolution Address object
// satisfies an `as never` cast and then fails the SDK's own guard at whichever step runs
// FIRST — which in a two-phase ceremony can be after the one-shot seeds are already spent.
{
  const BECH32 =
    "addr_test1qqew0cqw4c59q2325fcu7sszkxcph9x23mlxgt3cpjfatcs9zrqvaqcx7xpujngkxmmy7cs5ka6th8ugs5kx4n6z0yjqcc6wxm";
  const client = { newTx: () => ({}) };
  const utxos = [providerUtxo(HASHES[0], 0)];

  let threw = null;
  try {
    assertCeremonyContext({ client, changeAddress: BECH32, availableUtxos: utxos }, "t");
  } catch (e) {
    threw = e;
  }
  ok(threw === null, "a bech32 STRING changeAddress is accepted");

  const cases = [
    ["a parsed Address OBJECT is refused, naming the type", { client, changeAddress: { bech32: BECH32 }, availableUtxos: utxos }, /bech32 STRING/],
    ["an empty changeAddress is refused", { client, changeAddress: "", availableUtxos: utxos }, /bech32 STRING/],
    ["a missing client is refused", { changeAddress: BECH32, availableUtxos: utxos }, /client/],
    ["availableUtxos omitted is refused, saying why there is no default", { client, changeAddress: BECH32 }, /availableUtxos must be an array/],
    ["availableUtxos EMPTY is refused separately from missing", { client, changeAddress: BECH32, availableUtxos: [] }, /empty/],
  ];
  for (const [name, ctx, re] of cases) {
    let msg = "";
    try {
      assertCeremonyContext(ctx, "t");
    } catch (e) {
      msg = e.message;
    }
    ok(re.test(msg), name);
  }
}

// ── dust seeds ────────────────────────────────────────────────────────────────
// ⛔ MEASURED ON PREVIEW: the wallet offered a 2 ADA output as a seed while 40 ADA outputs sat
// further down the provider's list. Each seed part-funds the transaction that consumes it.
{
  const dusty = [
    providerUtxo(HASHES[0], 0, { lovelace: 2_000_000n }),
    providerUtxo(HASHES[1], 0, { lovelace: 40_000_000n }),
    providerUtxo(HASHES[2], 0, { lovelace: 2_000_000n }),
    providerUtxo(HASHES[3], 0, { lovelace: 40_000_000n }),
    providerUtxo(HASHES[0], 1, { lovelace: 40_000_000n }),
  ];
  const seeds = selectSeedUtxos(dusty, "addr_test1irrelevant");
  const chosen = [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed].map(
    (r) => `${r.txHash}#${r.outputIndex}`,
  );
  const dustRefs = [`${HASHES[0]}#0`, `${HASHES[2]}#0`];
  ok(!chosen.some((c) => dustRefs.includes(c)),
    "the three LARGEST plain UTxOs are chosen, not the first three the provider listed");
  ok(lovelaceOfUtxo(dusty[1]) === 40_000_000n, "lovelaceOfUtxo reads a UTxO's ada");
  ok(lovelaceOfUtxo({ assets: "garbage" }) === 0n, "lovelaceOfUtxo returns 0n for unreadable assets");

  // Deterministic: the same input must give the same three, or two machines disagree.
  const again = selectSeedUtxos([...dusty].reverse(), "addr_test1irrelevant");
  const chosenAgain = [again.paramsSeed, again.issuanceSeed, again.multisigSeed].map(
    (r) => `${r.txHash}#${r.outputIndex}`,
  );
  ok(JSON.stringify(chosen) === JSON.stringify(chosenAgain),
    "selection is order-independent — a reversed UTxO list yields the same three seeds");
}

// ── resolveSeedUtxos ──────────────────────────────────────────────────────────
// ⛔ The plan is parameterised by OUTREFS; the builders SPEND UTxOs. Supplying those from a
// field nobody populated is what produced "the upgradeMultisig seed UTxO is required".
{
  const available = HASHES.map((h, i) => providerUtxo(h, i));
  const seeds = selectSeedUtxos(available, "addr_test1irrelevant");
  const resolved = resolveSeedUtxos(available, seeds);
  ok(resolved.upgradeMultisig !== undefined && resolved.protocolParams !== undefined &&
     resolved.issuance !== undefined, "all three seeds resolve to real UTxO objects");
  ok(toChainUtxo(resolved.upgradeMultisig).txHash === seeds.multisigSeed.txHash &&
     toChainUtxo(resolved.upgradeMultisig).outputIndex === seeds.multisigSeed.outputIndex,
    "the resolved object is the SAME outref the plan is parameterised by");

  let msg = "";
  try {
    resolveSeedUtxos(available.slice(1), seeds);
  } catch (e) {
    msg = e.message;
  }
  ok(/is not among the wallet/.test(msg) && /spent/.test(msg),
    "a seed missing from the wallet is refused by name, suggesting it was spent");
  ok(resolveSeedUtxos([...available, { not: "a utxo" }], seeds).issuance !== undefined,
    "a non-UTxO entry in the wallet list is skipped rather than throwing");
}

// ── cborOf ────────────────────────────────────────────────────────────────────
// ⛔ The SDK's builders return UnsignedTx, which ALREADY carries cbor as hex. Calling
// toTransaction() on it — a method it has never had — failed every one of the five steps.
{
  ok(cborOf({ cbor: "84a300d9010281", txHash: "ab".repeat(32) }) === "84a300d9010281",
    "cborOf reads UnsignedTx.cbor straight through");

  // An Evolution build result is the OTHER object, and must NOT be silently accepted here:
  // accepting it would mean a step whose CBOR came from somewhere unverified.
  for (const [name, value, re] of [
    ["an Evolution build result is refused, listing its keys", { toTransaction: () => {}, effect: {} }, /keys \[/],
    ["undefined is refused by name", undefined, /undefined/],
    ["an empty cbor is refused", { cbor: "" }, /no CBOR/],
    ["a non-string cbor is refused", { cbor: 123 }, /no CBOR/],
  ]) {
    let msg = "";
    try {
      cborOf(value);
    } catch (e) {
      msg = e.message;
    }
    ok(re.test(msg), name);
  }
}

// ── retry classification ─────────────────────────────────────────────────────
// The REAL error, verbatim from preview, nested exactly as Evolution delivers it.
{
  const REF = "ae94a42a38b8865c9399fc69de272fce30ef73d25f7d6c9a2c24c65d5a608bc4#0";
  const real = new Error("Script evaluation failed", {
    cause: {
      message: "Blockfrost evaluateTx failed",
      response: { status: 400, body: { ScriptFailures: { "mint:0": { CannotCreateEvaluationContext: {
        reason: `Unknown transaction input (missing from UTxO set): ${REF}` } } } } },
    },
  });
  ok(isMissingUtxoEvaluation(real) === true, "the missing-input evaluation failure is retryable");
  ok(missingInputOf(real) === REF, "the missing outref is extracted for the progress message");

  // ⛔ Everything else must NOT be retried: repeating a transaction the validator rejected
  // only wastes the operator's time, and each ceremony attempt strands stake deposits.
  ok(isMissingUtxoEvaluation(new Error("Script evaluation failed: validator returned False")) === false,
    "a genuine validator rejection is NOT retried");
  ok(isMissingUtxoEvaluation(new Error("Insufficient funds")) === false, "a funding failure is NOT retried");
  ok(missingInputOf(new Error("boom")) === null, "no outref reported when there is none");
}

// ── buildWithFreshUtxos ───────────────────────────────────────────────────────
{
  const ctx = { client: { newTx: () => ({}) }, changeAddress: "addr_test1q", availableUtxos: [1] };
  const REF = "ae94a42a38b8865c9399fc69de272fce30ef73d25f7d6c9a2c24c65d5a608bc4#0";
  const missing = () => new Error(`Unknown transaction input (missing from UTxO set): ${REF}`);

  let reads = 0;
  const read = async () => { reads += 1; return [{ n: reads }]; };

  // Succeeds on the third try; the wallet is re-read before EVERY attempt, not just the first.
  let tries = 0;
  const out = await buildWithFreshUtxos(ctx, read, async (c) => {
    tries += 1;
    if (tries < 3) throw missing();
    return c.availableUtxos;
  }, { attempts: 4, delayMs: 1 });
  ok(tries === 3 && reads === 3, "re-reads the wallet before every attempt, not once");
  ok(JSON.stringify(out) === JSON.stringify([{ n: 3 }]),
    "the build receives the FRESH utxo set, not the stale one from the context");

  // A non-retryable failure escapes immediately.
  let attempts2 = 0;
  let escaped = "";
  try {
    await buildWithFreshUtxos(ctx, read, async () => { attempts2 += 1; throw new Error("validator said no"); },
      { attempts: 4, delayMs: 1 });
  } catch (e) { escaped = e.message; }
  ok(attempts2 === 1 && /validator said no/.test(escaped),
    "a non-retryable failure is rethrown on the FIRST attempt");

  // Exhaustion rethrows the real error rather than a wrapper.
  let last = "";
  try {
    await buildWithFreshUtxos(ctx, read, async () => { throw missing(); }, { attempts: 2, delayMs: 1 });
  } catch (e) { last = e.message; }
  ok(last.includes(REF), "after the last attempt the ORIGINAL error is rethrown, naming the input");
}

// ── fingerprintUtxos: telling "the wallet changed" from "time passed" ────────
// ⛔ THE POINT: the retry re-reads AND waits together, so a success could not say which one
// mattered. The fingerprint separates a stale UTxO set from an evaluator that was merely behind.
{
  const a = providerUtxo(HASHES[0], 0);
  const b = providerUtxo(HASHES[1], 0);
  ok(fingerprintUtxos([a, b]) === fingerprintUtxos([b, a]),
    "order does not count as a change — provider ordering is not wallet state");
  ok(fingerprintUtxos([a, b]) !== fingerprintUtxos([a]),
    "a spent output IS a change");
  ok(fingerprintUtxos([]) === fingerprintUtxos([]), "empty is stable");
  ok(fingerprintUtxos([a, { junk: true }]) !== fingerprintUtxos([a]),
    "an unreadable entry is counted, not dropped — a set that became unreadable is not unchanged");
}

// ── withoutOutputsOf ─────────────────────────────────────────────────────────
// ⛔ The genesis was funded from phase one's change, which Blockfrost's evaluator had not
// indexed — and the SDK cannot pass it an additionalUtxoSet, so it could not be explained.
{
  const settled = providerUtxo(HASHES[0], 0);
  const phaseOneChange = providerUtxo(HASHES[1], 0);
  const all = [settled, phaseOneChange, providerUtxo(HASHES[1], 1)];

  const kept = withoutOutputsOf(all, [HASHES[1]]);
  ok(kept.length === 1, "every output of a named transaction is dropped, not just the first");
  ok(toChainUtxo(kept[0]).txHash === HASHES[0], "the settled UTxO survives");

  ok(withoutOutputsOf(all, []).length === 3, "an empty exclusion list changes nothing");
  ok(withoutOutputsOf(all, [HASHES[1].toUpperCase()]).length === 1,
    "hash comparison is case-insensitive — a wallet that upper-cases would else fund from it");
  ok(withoutOutputsOf([settled, { junk: true }], []).length === 2,
    "with nothing to exclude the list is returned untouched, unreadable entries included");
  ok(withoutOutputsOf([settled, { junk: true }], [HASHES[1]]).length === 1,
    "when filtering, an unreadable entry is dropped rather than funded from");
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
}

main().catch((e) => {
  console.error("HARNESS ERROR:", e);
  process.exit(1);
});
