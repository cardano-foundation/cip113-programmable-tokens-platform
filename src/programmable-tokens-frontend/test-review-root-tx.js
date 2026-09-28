const assert = require("node:assert/strict");
const cbor = require("cbor");
const { reviewMemberRootTransaction } = require("./.root-build/rwa/review-root-tx.js");
const { computeMemberRoot } = require("./.root-build/rwa/member-root.js");

const bytes = (hex) => Buffer.from(hex, "hex");
const hex = (value) => cbor.encode(value).toString("hex");
const policy = "ab".repeat(28);
const gsPolicy = "cd".repeat(28);
const registryPolicy = "dd".repeat(28);
const assetName = "476c6f62616c5374617465";
const admin = "11".repeat(28);
const gsTx = "aa".repeat(32);
const fundingTx = "bb".repeat(32);
const registryTx = "cc".repeat(32);
const one = { credentialHash: "01".repeat(28), credentialType: 0, validUntilMs: 2_000_000_000_000 };
const two = { credentialHash: "02".repeat(28), credentialType: 1, validUntilMs: 2_100_000_000_000 };
const baselineRoot = computeMemberRoot([one], policy, 0);
const newRoot = computeMemberRoot([one, two], policy, 0);
const gsAddress = Buffer.concat([Buffer.from([0x70]), Buffer.alloc(28, 0x99)]);
const adminAddress = Buffer.concat([Buffer.from([0x60]), bytes(admin)]);
const value = [3_000_000, new Map([[bytes(gsPolicy), new Map([[bytes(assetName), 1]])]])];
const fields = (root) => [new cbor.Tagged(121, []), new cbor.Tagged(121, []), 0,
  bytes(admin), bytes("44".repeat(28)), bytes("55".repeat(28)), new cbor.Tagged(121, []),
  new Map(), bytes(root), new cbor.Tagged(121, []), new cbor.Tagged(121, []), 0,
  bytes("66".repeat(28)), new cbor.Tagged(121, [])];
const output = (root) => new Map([[0, gsAddress], [1, value],
  [2, [1, new cbor.Tagged(24, cbor.encode(new cbor.Tagged(121, fields(root))))]]]);
const sourceTx = hex([new Map([[1, [output(baselineRoot)]]]), new Map(), true, null]);
const registryOutput = new Map([
  [0, Buffer.concat([Buffer.from([0x70]), Buffer.alloc(28, 0x88)])],
  [1, [2_000_000, new Map([[bytes(registryPolicy), new Map([[bytes(policy), 1]])]])]],
  [2, [1, new cbor.Tagged(24, cbor.encode(new cbor.Tagged(121,
    [bytes(policy), Buffer.alloc(0), new cbor.Tagged(122, [bytes("77".repeat(28))]),
      new cbor.Tagged(122, [bytes("88".repeat(28))]), new cbor.Tagged(122, [bytes("99".repeat(28))]),
      new cbor.Tagged(121, [Buffer.alloc(0)]), bytes(gsPolicy)])))]],
]);
const registrySourceTx = hex([new Map([[1, [registryOutput]]]), new Map(), true, null]);
const redeemer = [0, 0, new cbor.Tagged(121, [0, new cbor.Tagged(1281, [bytes(newRoot)])]), [1, 1]];
const body = new Map([
  [0, new Set([[bytes(gsTx), 0], [bytes(fundingTx), 0]])],
  [1, [output(newRoot), new Map([[0, adminAddress], [1, 6_000_000]])]],
  [2, 300_000],
  [13, new Set([[bytes(fundingTx), 0]])],
  [14, new Set([bytes(admin)])],
  [16, new Map([[0, adminAddress], [1, 9_380_000]])],
  [17, 620_000],
]);
const candidate = {
  unsignedCborTx: hex([body, new Map([[5, [redeemer]]]), true, null]),
  gsPolicyId: gsPolicy,
  tokenPolicyId: policy,
  adminHash: admin,
  baselineRootHash: baselineRoot,
  newRootHashHex: newRoot,
  baseline: [one],
  added: [two],
  approvedAdded: [two],
  leaves: [one, two],
};

function withInputOrder(fundingHash, gsFirst, redeemerIndex, mapRedeemer = false) {
  const gsInput = [bytes(gsTx), 0];
  const fundingInput = [bytes(fundingHash), 0];
  const changedBody = new Map(body);
  changedBody.set(0, new Set(gsFirst ? [gsInput, fundingInput] : [fundingInput, gsInput]));
  changedBody.set(13, new Set([fundingInput]));
  const changedRedeemer = [0, redeemerIndex, redeemer[2], redeemer[3]];
  const redeemers = mapRedeemer
    ? new Map([[[0, redeemerIndex], [redeemer[2], redeemer[3]]]])
    : [changedRedeemer];
  return { ...candidate, unsignedCborTx: hex([changedBody, new Map([[5, redeemers]]), true, null]) };
}

function withPlainOutputs(change, collateralReturn) {
  const changedBody = new Map(body);
  changedBody.set(1, [output(newRoot), change]);
  changedBody.set(16, collateralReturn);
  return { ...candidate, unsignedCborTx: hex([changedBody, new Map([[5, [redeemer]]]), true, null]) };
}

process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY = "fixture-key";
process.env.NEXT_PUBLIC_CMTA_REGISTRY_POLICY_ID = registryPolicy;
global.fetch = async (url) => {
  if (url.endsWith(`/assets/${registryPolicy}${policy}/utxos`))
    return { ok: true, json: async () => [{ tx_hash: registryTx, output_index: 0 }] };
  if (url.endsWith(`/txs/${registryTx}/cbor`))
    return { ok: true, json: async () => ({ cbor: registrySourceTx }) };
  if (url.endsWith(`/assets/${gsPolicy}${assetName}/utxos`))
    return { ok: true, json: async () => [{ tx_hash: gsTx, output_index: 0 }] };
  if (url.endsWith(`/txs/${gsTx}/cbor`))
    return { ok: true, json: async () => ({ cbor: sourceTx }) };
  throw new Error(`Unexpected chain request: ${url}`);
};

async function main() {
  await reviewMemberRootTransaction(candidate);
  // Redeemer pointers address the lexically sorted ledger input set, not the
  // order in which the backend serialized the two inputs into CBOR.
  for (const gsFirst of [true, false]) {
    await reviewMemberRootTransaction(withInputOrder("00".repeat(32), gsFirst, 1));
    await reviewMemberRootTransaction(withInputOrder("ff".repeat(32), gsFirst, 0));
  }
  await reviewMemberRootTransaction(withInputOrder("00".repeat(32), true, 1, true));
  await assert.rejects(() => reviewMemberRootTransaction(withInputOrder("00".repeat(32), true, 0)),
    /Redeemer is not attached to the GS input/);
  const mapChange = new Map([[0, adminAddress], [1, 6_000_000]]);
  const arrayChange = [adminAddress, 6_000_000];
  const mapReturn = new Map([[0, adminAddress], [1, 9_380_000]]);
  const arrayReturn = [adminAddress, 9_380_000];
  for (const change of [mapChange, arrayChange]) {
    for (const collateralReturn of [mapReturn, arrayReturn]) {
      await reviewMemberRootTransaction(withPlainOutputs(change, collateralReturn));
    }
  }
  const badPlainOutputs = [
    [adminAddress, 6_000_000, null],
    new Map([[0, adminAddress], [1, 6_000_000], [2, null]]),
    new Map([[0, adminAddress], [1, 6_000_000], [3, bytes("ff")]]),
    [adminAddress, [6_000_000, new Map()]],
    [adminAddress, -1],
    [Buffer.concat([Buffer.from([0x70]), bytes(admin)]), 6_000_000],
    new Set([adminAddress, 6_000_000]),
  ];
  for (const bad of badPlainOutputs) {
    await assert.rejects(() => reviewMemberRootTransaction(withPlainOutputs(bad, arrayReturn)));
    await assert.rejects(() => reviewMemberRootTransaction(withPlainOutputs(arrayChange, bad)));
  }
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate, approvedAdded: [] }), /did not select/);
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate, baselineRootHash: "ff".repeat(32) }), /baseline/);
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate, gsPolicyId: "ee".repeat(28) }), /different GS policy/);
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate, newRootHashHex: "ff".repeat(32) }), /candidate root/);
  const withMint = new Map(body); withMint.set(9, 1);
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate,
    unsignedCborTx: hex([withMint, new Map([[5, [redeemer]]]), true, null]) }), /Unexpected transaction body field/);
  const otherAction = [0, 0, new cbor.Tagged(121, [0, new cbor.Tagged(1282, [bytes(newRoot)])]), [1, 1]];
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate,
    unsignedCborTx: hex([body, new Map([[5, [otherAction]]]), true, null]) }), /not UpdateMemberRootHash/);
  const scriptChange = new Map(body);
  scriptChange.set(1, [output(newRoot), new Map([[0, Buffer.concat([Buffer.from([0x70]), bytes(admin)])], [1, 6_000_000]])]);
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate,
    unsignedCborTx: hex([scriptChange, new Map([[5, [redeemer]]]), true, null]) }), /not payable to the admin payment key/);
  const scriptCollateral = new Map(body);
  scriptCollateral.set(16, new Map([[0, Buffer.concat([Buffer.from([0x70]), bytes(admin)])], [1, 9_380_000]]));
  await assert.rejects(() => reviewMemberRootTransaction({ ...candidate,
    unsignedCborTx: hex([scriptCollateral, new Map([[5, [redeemer]]]), true, null]) }), /not payable to the admin payment key/);
  console.log("CMTA transaction review accepts the exact root update and rejects unapproved members, roots, minting and redeemers");
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
