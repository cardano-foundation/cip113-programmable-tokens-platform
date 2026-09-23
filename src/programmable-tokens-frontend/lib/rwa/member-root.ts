import { blake2b } from "@noble/hashes/blake2";

/** Browser-side MPF root calculation, independent of the public backend. */
export interface MemberLeaf {
  credentialHash: string;
  credentialType: number;
  validUntilMs: number;
}

export function hexBytes(hex: string): Uint8Array {
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/i.test(hex)) throw new Error("Invalid hex value");
  return Uint8Array.from(hex.match(/../g)?.map((part) => parseInt(part, 16)) ?? []);
}

export function bytesHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

const hash = (bytes: Uint8Array): Uint8Array => blake2b(bytes, { dkLen: 32 });
const concat = (...parts: Uint8Array[]): Uint8Array => {
  const result = new Uint8Array(parts.reduce((length, part) => length + part.length, 0));
  let offset = 0;
  for (const part of parts) { result.set(part, offset); offset += part.length; }
  return result;
};

interface Item { path: string; valueHash: Uint8Array }

function leafHash(suffix: string, valueHash: Uint8Array): Uint8Array {
  return hash(suffix.length % 2 === 0
    ? concat(Uint8Array.of(0xff), hexBytes(suffix), valueHash)
    : concat(Uint8Array.of(0x00, parseInt(suffix[0], 16)), hexBytes(suffix.slice(1)), valueHash));
}

function subtree(items: Item[], offset: number): Uint8Array {
  if (items.length === 1) return leafHash(items[0].path.slice(offset), items[0].valueHash);
  let common = 0;
  while (offset + common < 64 && items.every((item) => item.path[offset + common] === items[0].path[offset + common])) common++;
  if (offset + common >= 64) throw new Error("MPF path collision");
  const childHashes: Uint8Array[] = [];
  for (let digit = 0; digit < 16; digit++) {
    const group = items.filter((item) => parseInt(item.path[offset + common], 16) === digit);
    childHashes.push(group.length ? subtree(group, offset + common + 1) : new Uint8Array(32));
  }
  let layer = childHashes;
  while (layer.length > 1) {
    const next: Uint8Array[] = [];
    for (let i = 0; i < layer.length; i += 2) next.push(hash(concat(layer[i], layer[i + 1])));
    layer = next;
  }
  const prefix = items[0].path.slice(offset, offset + common);
  return hash(concat(Uint8Array.from(prefix, (nibble) => parseInt(nibble, 16)), layer[0]));
}

/** The value binds expiry, policy and CMTA network byte exactly as the validator does. */
export function computeMemberRoot(leaves: MemberLeaf[], policyId: string, networkId: number): string {
  if (!/^[0-9a-f]{56}$/i.test(policyId)) throw new Error("Invalid token policy ID");
  if (!Number.isInteger(networkId) || networkId < 0 || networkId > 255) throw new Error("Invalid CMTA network ID");
  const seen = new Set<string>();
  const items = leaves.map((leaf) => {
    if (!/^[0-9a-f]{56}$/i.test(leaf.credentialHash)) throw new Error("Stake credential hash must be 56 hex characters");
    if (leaf.credentialType !== 0 && leaf.credentialType !== 1) throw new Error("Select key or script credential type");
    if (!Number.isSafeInteger(leaf.validUntilMs) || leaf.validUntilMs < 0) throw new Error("Invalid membership expiry");
    const memberKey = `${leaf.credentialType}:${leaf.credentialHash.toLowerCase()}`;
    if (seen.has(memberKey)) throw new Error("Duplicate member credential");
    seen.add(memberKey);
    const key = concat(Uint8Array.of(leaf.credentialType), hexBytes(leaf.credentialHash));
    const expiry = new Uint8Array(8);
    let ms = BigInt(leaf.validUntilMs);
    for (let i = 7; i >= 0; i--) { expiry[i] = Number(ms & 255n); ms >>= 8n; }
    const value = concat(expiry, hexBytes(policyId), Uint8Array.of(networkId));
    return { path: bytesHex(hash(key)), valueHash: hash(value) };
  });
  if (items.length === 0) return "";
  items.sort((a, b) => a.path.localeCompare(b.path));
  return bytesHex(subtree(items, 0));
}
