import * as cbor from "cbor";
import { getCardanoNetwork } from "../utils/network";
import { bytesHex, computeMemberRoot, hexBytes, type MemberLeaf } from "./member-root";

const GS_ASSET_NAME = "476c6f62616c5374617465";
type CborMap = Map<unknown, unknown>;

function asMap(value: unknown, label: string): CborMap {
  if (!(value instanceof Map)) throw new Error(`${label} is not a CBOR map`);
  return value;
}
function asArray(value: unknown, label: string): unknown[] {
  if (value instanceof cbor.Tagged && value.tag === 258) value = value.value;
  if (value instanceof Set) value = Array.from(value);
  if (!Array.isArray(value)) throw new Error(`${label} is not a CBOR array`);
  return value;
}
function asBytes(value: unknown, label: string): Uint8Array {
  if (!(value instanceof Uint8Array)) throw new Error(`${label} is not CBOR bytes`);
  return value;
}
function asInt(value: unknown, label: string): number {
  const number = typeof value === "bigint" ? Number(value) : value;
  if (typeof number !== "number" || !Number.isSafeInteger(number)) throw new Error(`${label} is not an integer`);
  return number;
}
function txFromHex(hex: string): unknown[] {
  const tx = asArray(cbor.decodeFirstSync(Buffer.from(hexBytes(hex))), "transaction");
  if (tx.length < 3 || !(tx[0] instanceof Map) || tx[2] !== true)
    throw new Error("Unexpected transaction CBOR structure");
  return tx;
}
function outputFromTx(hex: string, index: number): CborMap {
  const body = asMap(txFromHex(hex)[0], "transaction body");
  const outputs = asArray(body.get(1), "outputs");
  return asMap(outputs[index], "GS output");
}
function inlineDatumFields(output: CborMap, fieldCount: number): unknown[] {
  const option = asArray(output.get(2), "inline datum option");
  if (asInt(option[0], "datum option tag") !== 1) throw new Error("GS output has no inline datum");
  let data: unknown = option[1];
  // Ledger CBOR wraps an inline datum in tag 24; cbor.Tagged exposes tag/value.
  if (data instanceof cbor.Tagged && data.tag === 24) data = cbor.decodeFirstSync(asBytes(data.value, "inline datum"));
  if (!(data instanceof cbor.Tagged) || data.tag !== 121) throw new Error("GS datum is not constructor 0");
  const fields = asArray(data.value, "GS datum fields");
  if (fields.length !== fieldCount) throw new Error(`Unexpected ${fieldCount}-field datum layout`);
  return fields;
}
function datumFields(output: CborMap): unknown[] { return inlineDatumFields(output, 14); }
function rootFromFields(fields: unknown[]): string { return bytesHex(asBytes(fields[8], "member root")); }
function assetAmount(output: CborMap, policy: string, assetName: string): number {
  const value = asArray(output.get(1), "GS value");
  const assets = asMap(value[1], "GS assets");
  const matches = Array.from(assets.entries()).find(([key]) => key instanceof Uint8Array && bytesHex(key) === policy);
  if (!matches) throw new Error("GS NFT policy is absent");
  const names = asMap(matches[1], "GS asset names");
  const asset = Array.from(names.entries()).find(([key]) => key instanceof Uint8Array && bytesHex(key) === assetName);
  if (!asset || asInt(asset[1], "NFT quantity") !== 1) throw new Error("Expected exactly one authentic NFT");
  return asInt(value[0], "NFT output lovelace");
}

async function blockfrostJson<T>(path: string): Promise<T> {
  const key = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY;
  if (!key) throw new Error("Direct chain verification needs NEXT_PUBLIC_BLOCKFROST_API_KEY");
  const network = getCardanoNetwork();
  const base = process.env.NEXT_PUBLIC_BLOCKFROST_URL || `https://cardano-${network}.blockfrost.io/api/v0`;
  const response = await fetch(`${base}${path}`, { headers: { project_id: key }, cache: "no-store" });
  if (!response.ok) throw new Error(`Direct chain verification failed (${response.status})`);
  return response.json() as Promise<T>;
}

async function uniqueAssetOutput(policyId: string, assetName: string): Promise<{ ref: string; output: CborMap }> {
  const asset = `${policyId}${assetName}`;
  const utxos = await blockfrostJson<Array<{ tx_hash: string; output_index: number }>>(`/assets/${asset}/utxos`);
  if (utxos.length !== 1) throw new Error("Expected exactly one live registry or GS NFT UTxO");
  const ref = `${utxos[0].tx_hash}#${utxos[0].output_index}`;
  const response = await blockfrostJson<{ cbor: string }>(`/txs/${utxos[0].tx_hash}/cbor`);
  const output = outputFromTx(response.cbor, utxos[0].output_index);
  assetAmount(output, policyId, assetName);
  return { ref, output };
}

function trustedRegistryPolicy(): string {
  const configured = process.env.NEXT_PUBLIC_CMTA_REGISTRY_POLICY_ID;
  const previewPinned = "12e2737454317f2ff77accd9f96aa74685bc8dd2a6f69bed0a08aec5";
  const policy = configured || (getCardanoNetwork() === "preview" ? previewPinned : "");
  if (!/^[0-9a-f]{56}$/i.test(policy))
    throw new Error("Configure a trusted CMTA registry policy for this network");
  return policy.toLowerCase();
}

async function gsPolicyForToken(tokenPolicyId: string): Promise<string> {
  const registryPolicy = trustedRegistryPolicy();
  const { output } = await uniqueAssetOutput(registryPolicy, tokenPolicyId);
  const fields = inlineDatumFields(output, 7);
  if (bytesHex(asBytes(fields[0], "registry token key")) !== tokenPolicyId.toLowerCase())
    throw new Error("Registry node does not belong to the selected token");
  const gsPolicy = bytesHex(asBytes(fields[6], "registry GS policy"));
  if (gsPolicy.length !== 56) throw new Error("Registry GS policy is malformed");
  return gsPolicy;
}

interface ChainGs { ref: string; output: CborMap; fields: unknown[]; root: string }
async function chainGs(gsPolicyId: string): Promise<ChainGs> {
  const { ref, output } = await uniqueAssetOutput(gsPolicyId, GS_ASSET_NAME);
  const fields = datumFields(output);
  return { ref, output, fields, root: rootFromFields(fields) };
}

function inputIdentity(input: unknown): { txHash: string; outputIndex: number } {
  const fields = asArray(input, "transaction input");
  return { txHash: bytesHex(asBytes(fields[0], "input hash")), outputIndex: asInt(fields[1], "input index") };
}
function inputRef(input: { txHash: string; outputIndex: number }): string {
  return `${input.txHash}#${input.outputIndex}`;
}
function sortedInputRefs(inputs: { txHash: string; outputIndex: number }[]): string[] {
  // Spending redeemer indices use the ledger's ordered input set, which can
  // differ from the order of inputs in the transaction body's CBOR array.
  return [...inputs].sort((a, b) => a.txHash === b.txHash
    ? a.outputIndex - b.outputIndex : a.txHash < b.txHash ? -1 : 1).map(inputRef);
}

function assertAdminPaymentAddress(value: unknown, adminHash: string, network: string, label: string): void {
  const address = asBytes(value, label);
  if (address.length < 29) throw new Error(`${label} is too short`);
  const type = address[0] >> 4;
  const validLength = ((type === 0 || type === 2) && address.length === 57)
    || (type === 4 && address.length > 29 && address.length <= 57)
    || (type === 6 && address.length === 29);
  const expectedNetwork = network === "mainnet" ? 1 : 0;
  if (!validLength || (address[0] & 15) !== expectedNetwork
      || bytesHex(address.slice(1, 29)) !== adminHash.toLowerCase())
    throw new Error(`${label} is not payable to the admin payment key`);
}

function assertAdminAdaOutput(value: unknown, adminHash: string, network: string, label: string): void {
  let address: unknown;
  let amount: unknown;
  if (Array.isArray(value) && value.length === 2) {
    // CCL serializes datum-free outputs in the legacy [address, amount] form.
    [address, amount] = value;
  } else if (value instanceof Map && value.size === 2 && value.has(0) && value.has(1)) {
    // Other builders may use the Babbage map form for the same plain output.
    address = value.get(0);
    amount = value.get(1);
  } else {
    throw new Error(`${label} must contain only an address and ADA value`);
  }
  assertAdminPaymentAddress(address, adminHash, network, `${label} address`);
  if (asInt(amount, `${label} ADA value`) < 0)
    throw new Error(`${label} ADA value must be nonnegative`);
}

function checkRootRedeemer(witnesses: CborMap, inputIndex: number, root: string): void {
  const value = witnesses.get(5);
  let entries: unknown[];
  if (value instanceof Map) {
    entries = Array.from(value.entries()).map(([key, payload]) => {
      const [tag, index] = asArray(key, "redeemer key");
      const [data, units] = asArray(payload, "redeemer payload");
      return [tag, index, data, units];
    });
  } else {
    entries = asArray(value, "redeemers");
  }
  if (entries.length !== 1) throw new Error("Expected exactly one GS spending redeemer");
  const [tag, index, data] = asArray(entries[0], "GS spending redeemer");
  if (asInt(tag, "redeemer tag") !== 0 || asInt(index, "redeemer index") !== inputIndex)
    throw new Error("Redeemer is not attached to the GS input");
  if (!(data instanceof cbor.Tagged) || data.tag !== 121)
    throw new Error("Unexpected GS redeemer constructor");
  const fields = asArray(data.value, "GS redeemer fields");
  if (fields.length !== 2 || asInt(fields[0], "continuing output index") !== 0)
    throw new Error("GS redeemer does not select output zero");
  const action = fields[1];
  if (!(action instanceof cbor.Tagged) || action.tag !== 1281)
    throw new Error("GS action is not UpdateMemberRootHash");
  const actionFields = asArray(action.value, "UpdateMemberRootHash fields");
  if (actionFields.length !== 1 || bytesHex(asBytes(actionFields[0], "redeemer root")) !== root)
    throw new Error("GS redeemer commits a different member root");
}

/** Refuse to ask the wallet to sign until independently sourced chain state,
 * MPF roots, the continuing GS datum, and the transaction body all agree. */
export async function reviewMemberRootTransaction(args: {
  unsignedCborTx: string; gsPolicyId: string; tokenPolicyId: string;
  adminHash: string; baselineRootHash: string; newRootHashHex: string;
  baseline: MemberLeaf[]; added: MemberLeaf[]; approvedAdded: MemberLeaf[]; leaves: MemberLeaf[];
}): Promise<void> {
  const network = getCardanoNetwork();
  const networkId = { preview: 0, preprod: 1, mainnet: 2 }[network];
  const registeredGsPolicy = await gsPolicyForToken(args.tokenPolicyId);
  if (registeredGsPolicy !== args.gsPolicyId.toLowerCase())
    throw new Error("Selected token is registered under a different GS policy");
  const live = await chainGs(args.gsPolicyId);
  if (asInt(live.fields[11], "GS network ID") !== networkId)
    throw new Error("Frontend network does not match the live CMTA GS network ID");
  const expectedBaseline = computeMemberRoot(args.baseline, args.tokenPolicyId, networkId);
  if (live.root !== expectedBaseline || live.root !== args.baselineRootHash.toLowerCase())
    throw new Error("The reviewed baseline does not match the live GS root");
  const byCredential = new Map(args.baseline.map((leaf) => [`${leaf.credentialType}:${leaf.credentialHash.toLowerCase()}`, leaf]));
  for (const leaf of args.added) byCredential.set(`${leaf.credentialType}:${leaf.credentialHash.toLowerCase()}`, leaf);
  const expected = Array.from(byCredential.values());
  const key = (leaf: MemberLeaf) => `${leaf.credentialType}:${leaf.credentialHash.toLowerCase()}:${leaf.validUntilMs}`;
  if (args.added.map(key).sort().join("|") !== args.approvedAdded.map(key).sort().join("|"))
    throw new Error("Candidate adds a member you did not select");
  if (expected.map(key).sort().join("|") !== args.leaves.map(key).sort().join("|"))
    throw new Error("The candidate contains members outside the reviewed change");
  const newRoot = computeMemberRoot(expected, args.tokenPolicyId, networkId);
  if (newRoot !== args.newRootHashHex.toLowerCase()) throw new Error("The candidate root is incorrect");

  const tx = txFromHex(args.unsignedCborTx);
  const body = asMap(tx[0], "transaction body");
  const allowedFields = new Set([0, 1, 2, 3, 8, 11, 13, 14, 15, 16, 17, 18]);
  for (const field of body.keys()) if (!allowedFields.has(asInt(field, "transaction field")))
    throw new Error(`Unexpected transaction body field ${String(field)}`);
  const inputs = asArray(body.get(0), "transaction inputs");
  const inputRefs = inputs.map(inputIdentity);
  if (inputRefs.length !== 2 || !inputRefs.some((input) => inputRef(input) === live.ref))
    throw new Error("Transaction does not spend exactly the live GS UTxO and one funding input");
  checkRootRedeemer(asMap(tx[1], "witness set"), sortedInputRefs(inputRefs).indexOf(live.ref), newRoot);
  const outputs = asArray(body.get(1), "transaction outputs");
  if (outputs.length < 1 || outputs.length > 2) throw new Error("Unexpected transaction outputs");
  const continuing = asMap(outputs[0], "continuing GS output");
  if (bytesHex(asBytes(continuing.get(0), "GS address")) !== bytesHex(asBytes(live.output.get(0), "live GS address")))
    throw new Error("GS NFT is being sent to another address");
  if (assetAmount(continuing, args.gsPolicyId, GS_ASSET_NAME) !== assetAmount(live.output, args.gsPolicyId, GS_ASSET_NAME))
    throw new Error("GS lovelace or NFT value changed");
  if (!cbor.encodeCanonical(continuing.get(1)).equals(cbor.encodeCanonical(live.output.get(1))))
    throw new Error("GS value changed");
  const newFields = datumFields(continuing);
  if (rootFromFields(newFields) !== newRoot) throw new Error("Transaction commits a different member root");
  for (let i = 0; i < newFields.length; i++) {
    if (i === 8) continue;
    if (!cbor.encodeCanonical(newFields[i]).equals(cbor.encodeCanonical(live.fields[i])))
      throw new Error(`Transaction changes GS field ${i}`);
  }
  if (bytesHex(asBytes(live.fields[3], "live admin hash")) !== args.adminHash.toLowerCase())
    throw new Error("Connected wallet is not the current GS admin");
  const signers = asArray(body.get(14), "required signers");
  if (signers.length !== 1 || bytesHex(asBytes(signers[0], "required signer")) !== args.adminHash.toLowerCase())
    throw new Error("Transaction requires an unexpected signer");
  if (asInt(body.get(2), "transaction fee") > 5_000_000) throw new Error("Transaction fee exceeds 5 ADA");
  if (outputs.length === 2) {
    assertAdminAdaOutput(outputs[1], args.adminHash, network, "change output");
  }
  const collateral = asArray(body.get(13), "collateral");
  if (collateral.length !== 1 || !inputRefs.some((input) => inputRef(input) === inputRef(inputIdentity(collateral[0]))))
    throw new Error("Unexpected collateral input");
  if (!body.has(17) || asInt(body.get(17), "total collateral") > 5_000_000)
    throw new Error("Collateral is not explicitly capped at 5 ADA");
  assertAdminAdaOutput(body.get(16), args.adminHash, network, "collateral return");
}
