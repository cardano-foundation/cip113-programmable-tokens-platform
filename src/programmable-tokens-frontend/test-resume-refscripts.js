/**
 * The recovery gate must refuse, not reassure.
 *
 * ⛔ WHAT THIS GUARDS. A ceremony that stopped after the genesis can publish its reference scripts
 * later — but ONLY against the parameterisation already deployed. Publishing a set derived from
 * different inputs hands the protocol reference inputs carrying the WRONG scripts, which the SDK
 * warns "does not fail loudly": it fails at redeemer evaluation, later, for whoever first tries to
 * use the deployment. So every refusal path here is load-bearing, and each is tested by making it
 * fire rather than by reading the code.
 */
const assert = require("node:assert");

let ran = 0;
const ok = (c, m) => { assert.ok(c, m); console.log(`  OK   ${m}`); ran++; };

(async () => {
const { inspectDeployment, inlineDatumHex } = await import("./.resumeref-build/resume.js");
const { Data, Assets } = await import("@evolution-sdk/evolution");

const PARAMS_ADDR = "addr_params";
const ALWAYS_FAIL = "addr_always_fail";
const POLICY = "aa".repeat(28);
const NAME = "50726f746f636f6c506172616d73"; // "ProtocolParams"
const UNIT = POLICY + NAME;

// A real Plutus datum and a DIFFERENT one, so "matches" is a byte comparison of real encodings.
const datumA = Data.fromCBORHex("d87980");
const datumB = Data.fromCBORHex("d87a80");
const hexA = Data.toCBORHex(datumA).toLowerCase();
const hexB = Data.toCBORHex(datumB).toLowerCase();
assert.notStrictEqual(hexA, hexB, "the two fixture datums must differ, or every check is vacuous");

const plan = {
  addresses: { protocolParams: PARAMS_ADDR, issuanceCborHex: ALWAYS_FAIL },
  assetUnits: { protocolParamsNft: UNIT },
  datums: { protocolParams: datumA },
  referenceScripts: [1, 2, 3, 4, 5, 6, 7],
};

// ⛔ REAL `Assets`, NOT A LOOKALIKE. `unitsOf` reads Evolution's internal MultiAsset shape through
// its schema encoders; hand-rolled objects make them throw, and a test built on them would pass
// while production could not read a single unit.
const nftUtxo = (datum) => ({
  assets: Assets.fromRecord({ lovelace: 2_000_000n, [UNIT]: 1n }),
  datumOption: { _tag: "InlineDatum", data: datum },
});
const refScriptUtxo = () => ({ assets: Assets.fromLovelace(20_000_000n), scriptRef: { bytes: new Uint8Array(10) } });
const plainUtxo = () => ({ assets: Assets.fromLovelace(5_000_000n) });

const at = (params, alwaysFail) => async (a) =>
  a === PARAMS_ADDR ? params : a === ALWAYS_FAIL ? alwaysFail : [];

// ---- 1. the happy path: genesis matches, no reference scripts yet ----
{
  const r = await inspectDeployment({ plan, utxosAt: at([nftUtxo(datumA)], [plainUtxo()]) });
  ok(r.paramsUtxoFound === true, "the params UTxO is found by its NFT, not by position");
  ok(r.paramsDatumMatches === true, "the derived datum matches the one on chain");
  ok(r.referenceScriptOutputs === 0, "a plain UTxO at always_fail is not counted as a reference script");
  ok(r.blockers.length === 0, "and with both checks passing there are NO blockers");
}

// ---- 2. ⛔ THE ONE THAT MATTERS: a different parameterisation is REFUSED ----
{
  const r = await inspectDeployment({ plan, utxosAt: at([nftUtxo(datumB)], [plainUtxo()]) });
  ok(r.paramsDatumMatches === false, "a datum that differs by one byte is reported as NOT matching");
  ok(r.blockers.length === 1, "and it blocks");
  ok(/does not match/i.test(r.blockers[0]), "naming the mismatch");
  ok(r.blockers[0].includes(hexA) && r.blockers[0].includes(hexB),
    "and printing BOTH datums, so an operator can see which deployment these inputs belong to");
}

// ---- 3. no genesis on chain at all ----
{
  const r = await inspectDeployment({ plan, utxosAt: at([], []) });
  ok(r.paramsUtxoFound === false && r.paramsDatumMatches === null,
    "an absent params UTxO is `null` for matching — not `false`, which would imply it was compared");
  ok(r.blockers.some((b) => /never landed|not on chain|No protocol-params/i.test(b)),
    "and it blocks rather than offering to publish into a deployment that does not exist");
}

// ---- 4. the NFT must be the DECIDER, not just any UTxO at the address ----
{
  // A decoy at the right address with the right datum but WITHOUT the NFT must not satisfy the check.
  const decoy = { assets: Assets.fromLovelace(2_000_000n), datumOption: { _tag: "InlineDatum", data: datumA } };
  const r = await inspectDeployment({ plan, utxosAt: at([decoy], [plainUtxo()]) });
  ok(r.paramsUtxoFound === false,
    "a UTxO at the params address that does not carry the NFT is NOT accepted as the params UTxO");
}

// ---- 5. already published — must refuse, or it burns another ~140 ADA ----
{
  const seven = Array.from({ length: 7 }, refScriptUtxo);
  const r = await inspectDeployment({ plan, utxosAt: at([nftUtxo(datumA)], seven) });
  ok(r.referenceScriptOutputs === 7, "seven reference-script outputs are counted");
  ok(r.blockers.some((b) => /ALREADY/i.test(b) && /140 ADA/.test(b)),
    "and publishing is refused, naming the ADA that a second run would lock for nothing");
}

// ---- 6. a PARTIAL set is a distinct refusal, not the same one ----
{
  const r = await inspectDeployment({ plan, utxosAt: at([nftUtxo(datumA)], [refScriptUtxo(), refScriptUtxo()]) });
  ok(r.blockers.some((b) => /PARTIAL/i.test(b) && /by hand/i.test(b)),
    "a partial set refuses differently — the step publishes all seven in order and cannot repair a subset");
}

// ---- 7. inlineDatumHex does not mistake a datum HASH for a datum ----
{
  ok(inlineDatumHex({ datumOption: { _tag: "DatumHash", hash: new Uint8Array(32) } }) === null,
    "a datum HASH yields null — comparing it against an inline datum would always 'differ'");
  ok(inlineDatumHex({}) === null, "and a UTxO with no datum yields null rather than throwing");
  ok(inlineDatumHex({ datumOption: { _tag: "InlineDatum", data: datumA } }) === hexA,
    "while an inline datum round-trips to its own hex");
}

console.log(`\n${ran} checks passed`);
})().catch((e) => { console.error(e); process.exit(1); });
