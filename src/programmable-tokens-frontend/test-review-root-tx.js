const assert = require("node:assert/strict");
const cbor = require("cbor");
const { reviewMemberRootTransaction } = require("./.root-build/rwa/review-root-tx.js");
const { computeMemberRoot } = require("./.root-build/rwa/member-root.js");

const bytes = (hex) => Buffer.from(hex, "hex");
const hex = (value) => cbor.encode(value).toString("hex");
const policy = "ab".repeat(28);
const gsPolicy = "cd".repeat(28);
const registryPolicy = "de".repeat(28);
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
process.env.NEXT_PUBLIC_NETWORK = "preview";
let missingPath = null;
let currentBootstrap = { registry: { scriptHash: registryPolicy } };
let indexedProtocols = [];
let backendStatus = {};
let backendCalls = [];
global.fetch = async (url) => {
  if (url.endsWith("/api/v1/protocol/bootstrap")) {
    backendCalls.push("current");
    if (backendStatus.current) return { ok: false, status: backendStatus.current };
    return { ok: true, json: async () => currentBootstrap };
  }
  if (url.endsWith("/api/v1/registry/protocols")) {
    backendCalls.push("indexed");
    if (backendStatus.indexed) return { ok: false, status: backendStatus.indexed };
    return { ok: true, json: async () => indexedProtocols };
  }
  if (missingPath && url.endsWith(missingPath)) return { ok: false, status: 404 };
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
  const originalFetch = global.fetch;
  assert.match(registryPolicy, /^[0-9a-f]{56}$/);
  // A current deployment absent from the old bundled catalog still works.
  backendCalls = [];
  await reviewMemberRootTransaction(candidate);
  assert.deepEqual(backendCalls, ["current", "indexed"]);

  process.env.NEXT_PUBLIC_NETWORK = "preprod";
  global.fetch = async (url) => {
    if (url.includes("/api/v1/")) return originalFetch(url);
    assert.ok(url.endsWith(`/assets/${registryPolicy}${policy}/utxos`));
    return { ok: false, status: 404 };
  };
  await assert.rejects(() => reviewMemberRootTransaction(candidate),
    /not found in any backend-reported preprod CMTA deployment/);

  process.env.NEXT_PUBLIC_NETWORK = "preview";
  global.fetch = originalFetch;

  await reviewMemberRootTransaction(candidate);
  missingPath = `/assets/${registryPolicy}${policy}/utxos`;
  await assert.rejects(() => reviewMemberRootTransaction(candidate), /not found in any backend-reported preview CMTA deployment/);
  missingPath = null;
  for (const [path, stage] of [
    [`/txs/${registryTx}/cbor`, "Registry NFT transaction CBOR lookup"],
    [`/assets/${gsPolicy}${assetName}/utxos`, "Global State NFT asset lookup"],
    [`/txs/${gsTx}/cbor`, "Global State NFT transaction CBOR lookup"],
  ]) {
    missingPath = path;
    await assert.rejects(() => reviewMemberRootTransaction(candidate), (error) =>
      error.message.includes(`${stage} failed on preview: Blockfrost 404 for ${path}`));
  }
  missingPath = null;

  const olderPolicy = "ef".repeat(28);
  try {
    currentBootstrap = { registry: { scriptHash: olderPolicy } };
    indexedProtocols = [{ registryNodePolicyId: registryPolicy }];
    let olderQueries = 0;
    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) { olderQueries++; return { ok: false, status: 404 }; }
      return originalFetch(url);
    };
    await reviewMemberRootTransaction(candidate);
    assert.equal(olderQueries, 1, "an indexed historical token must be discoverable");

    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) return { ok: true, json: async () => [] };
      return originalFetch(url);
    };
    await reviewMemberRootTransaction(candidate);

    for (const status of [401, 429, 500]) {
      global.fetch = async (url) => {
        if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) return { ok: false, status };
        return originalFetch(url);
      };
      await assert.rejects(() => reviewMemberRootTransaction(candidate),
        new RegExp(`Registry NFT asset lookup failed on preview: Blockfrost ${status}`));
    }
    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`))
        return { ok: true, json: async () => [{ tx_hash: registryTx, output_index: 0 }] };
      return originalFetch(url);
    };
    await assert.rejects(() => reviewMemberRootTransaction(candidate), /multiple backend-reported preview CMTA deployments/);

    indexedProtocols = [{ registryNodePolicyId: olderPolicy }, { registryNodePolicyId: registryPolicy }];
    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) return { ok: false, status: 429 };
      return originalFetch(url);
    };
    await assert.rejects(() => reviewMemberRootTransaction(candidate),
      /Registry NFT asset lookup failed on preview: Blockfrost 429/);
    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) return { ok: false, status: 404 };
      return originalFetch(url);
    };
    await reviewMemberRootTransaction(candidate); // token belongs to the older, first record

    currentBootstrap = { registry: { scriptHash: registryPolicy.toUpperCase() } };
    indexedProtocols = [{ registryNodePolicyId: registryPolicy }];
    let duplicateQueries = 0;
    global.fetch = async (url) => {
      if (url.endsWith(`/assets/${registryPolicy}${policy}/utxos`)) duplicateQueries++;
      return originalFetch(url);
    };
    await reviewMemberRootTransaction(candidate);
    assert.equal(duplicateQueries, 1, "identical registry policies must be queried only once");
  } finally {
    currentBootstrap = { registry: { scriptHash: registryPolicy } };
    indexedProtocols = [];
    global.fetch = originalFetch;
  }

  // Each review fetches deployment identity anew; it cannot retain a stale policy.
  currentBootstrap = { registry: { scriptHash: olderPolicy } };
  global.fetch = async (url) => {
    if (url.endsWith(`/assets/${olderPolicy}${policy}/utxos`)) return { ok: false, status: 404 };
    return originalFetch(url);
  };
  await assert.rejects(() => reviewMemberRootTransaction(candidate), /not found in any backend-reported preview CMTA deployment/);
  currentBootstrap = { registry: { scriptHash: registryPolicy } };
  global.fetch = originalFetch;
  await reviewMemberRootTransaction(candidate);

  for (const [source, status] of [["current", 503], ["indexed", 429]]) {
    backendStatus[source] = status;
    await assert.rejects(() => reviewMemberRootTransaction(candidate),
      new RegExp(`${source === "current" ? "Current deployment" : "Indexed deployments"} lookup failed: backend HTTP ${status}`));
    backendStatus[source] = undefined;
  }
  for (const invalid of [null, {}, { registry: { scriptHash: "xyz" } }]) {
    currentBootstrap = invalid;
    await assert.rejects(() => reviewMemberRootTransaction(candidate), /Current deployment has an invalid registry policy/);
  }
  currentBootstrap = { registry: { scriptHash: registryPolicy } };
  for (const invalid of [null, {}, [{ registryNodePolicyId: "xyz" }]]) {
    indexedProtocols = invalid;
    await assert.rejects(() => reviewMemberRootTransaction(candidate),
      /Indexed deployments response is not an array|Indexed deployment 0 has an invalid registry policy/);
  }
  indexedProtocols = [];

  global.fetch = async (url) => {
    if (url.endsWith(`/assets/${registryPolicy}${policy}/utxos`)) return { ok: true, json: async () => ({ wrong: true }) };
    return originalFetch(url);
  };
  await assert.rejects(() => reviewMemberRootTransaction(candidate), /Malformed Registry NFT asset lookup response/);
  global.fetch = async (url) => {
    if (url.endsWith(`/txs/${registryTx}/cbor`)) return { ok: true, json: async () => ({ cbor: sourceTx }) };
    return originalFetch(url);
  };
  await assert.rejects(() => reviewMemberRootTransaction(candidate), /GS NFT policy is absent/);
  const malformedRegistry = new Map(registryOutput);
  malformedRegistry.set(2, [1, new cbor.Tagged(24, cbor.encode(new cbor.Tagged(121, [bytes(policy)])))]);
  const malformedRegistrySource = hex([new Map([[1, [malformedRegistry]]]), new Map(), true, null]);
  global.fetch = async (url) => {
    if (url.endsWith(`/txs/${registryTx}/cbor`))
      return { ok: true, json: async () => ({ cbor: malformedRegistrySource }) };
    return originalFetch(url);
  };
  await assert.rejects(() => reviewMemberRootTransaction(candidate), /Unexpected 7-field datum layout/);
  global.fetch = originalFetch;

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
