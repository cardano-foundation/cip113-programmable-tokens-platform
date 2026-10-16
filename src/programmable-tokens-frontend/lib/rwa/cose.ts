import { ed25519 } from "@noble/curves/ed25519.js";
import { blake2b } from "@noble/hashes/blake2";

/**
 * The structure of a CIP-30 `signData` result — COSE_Sign1 + COSE_Key — as the CMTA validator
 * (PR #29 `lib/kyc/verify.ak`) reads it. Browser-side and ADVISORY: the backend repeats every
 * check against the live GlobalState before it builds a transaction, and the validator rebuilds
 * the Sig_structure itself, so a wrong extraction here can only fail closed.
 *
 * A deliberately small CBOR reader: COSE needs arrays, maps, byte/text strings, integers, the
 * simple values and tag 18 — nothing else is accepted.
 */

type Cbor = number | bigint | Uint8Array | string | boolean | null | Cbor[] | Map<Cbor, Cbor>
  | { tag: number; value: Cbor };

export function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim().replace(/^0x/i, "");
  if (clean.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(clean)) throw new Error("not valid hex");
  return Uint8Array.from(clean.match(/../g) ?? [], (b) => parseInt(b, 16));
}

export function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

function decode(bytes: Uint8Array): Cbor {
  let pos = 0;
  const need = (n: number) => { if (pos + n > bytes.length) throw new Error("truncated CBOR"); };
  const arg = (info: number): number | bigint => {
    if (info < 24) return info;
    const width = info === 24 ? 1 : info === 25 ? 2 : info === 26 ? 4 : info === 27 ? 8 : 0;
    if (!width) throw new Error("unsupported CBOR length");
    need(width);
    let v = 0n;
    for (let i = 0; i < width; i++) v = (v << 8n) | BigInt(bytes[pos++]);
    return v <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(v) : v;
  };
  const len = (info: number): number => {
    const v = arg(info);
    if (typeof v !== "number" || v > bytes.length) throw new Error("CBOR length out of range");
    return v;
  };
  const item = (): Cbor => {
    need(1);
    const head = bytes[pos++];
    const major = head >> 5, info = head & 31;
    switch (major) {
      case 0: return arg(info);
      case 1: { const v = arg(info); return typeof v === "number" ? -1 - v : -1n - v; }
      case 2: case 3: {
        if (info === 31) throw new Error("indefinite strings are not used by COSE");
        const n = len(info); need(n);
        const slice = bytes.slice(pos, pos + n); pos += n;
        return major === 2 ? slice : new TextDecoder().decode(slice);
      }
      case 4: { const n = len(info); const out: Cbor[] = []; for (let i = 0; i < n; i++) out.push(item()); return out; }
      case 5: {
        const n = len(info); const out = new Map<Cbor, Cbor>();
        for (let i = 0; i < n; i++) { const k = item(); out.set(k, item()); }
        return out;
      }
      case 6: return { tag: Number(arg(info)), value: item() };
      case 7:
        if (info === 20) return false;
        if (info === 21) return true;
        if (info === 22) return null;
        throw new Error("unsupported CBOR simple value");
    }
    throw new Error("unsupported CBOR");
  };
  const result = item();
  if (pos !== bytes.length) throw new Error("trailing CBOR");
  return result;
}

function mapGet(map: Map<Cbor, Cbor>, key: number | string): Cbor | undefined {
  for (const [k, v] of map) if (k === key) return v;
  return undefined;
}

export interface CoseSign1 {
  protectedHeader: Uint8Array;
  /** null for a detached payload. */
  payload: Uint8Array | null;
  /** CIP-8 unprotected `hashed`; absent = false. */
  hashedFlag: boolean;
  signature: Uint8Array;
}

/** Hex is capped like the backend's 2048-character bound before any parsing. */
export const MAX_COSE_HEX = 2048;

export function decodeCoseSign1(hex: string): CoseSign1 {
  const clean = hex.trim().replace(/^0x/i, "");
  if (clean.length > MAX_COSE_HEX) throw new Error(`COSE_Sign1 must be at most ${MAX_COSE_HEX} hex characters`);
  let value = decode(hexToBytes(clean));
  if (value && typeof value === "object" && "tag" in value && !(value instanceof Uint8Array) && !Array.isArray(value)
      && !(value instanceof Map)) {
    if (value.tag !== 18) throw new Error("invalid COSE_Sign1 tag");
    value = value.value;
  }
  if (!Array.isArray(value) || value.length !== 4) throw new Error("invalid COSE_Sign1");
  const [ph, unprotected, payload, signature] = value;
  if (!(ph instanceof Uint8Array) || !(unprotected instanceof Map) || !(signature instanceof Uint8Array)
      || signature.length !== 64) throw new Error("invalid COSE_Sign1");
  let detachedOrBytes: Uint8Array | null;
  if (payload instanceof Uint8Array) detachedOrBytes = payload;
  else if (payload === null) detachedOrBytes = null;
  else throw new Error("invalid COSE_Sign1 payload");
  const flag = mapGet(unprotected, "hashed");
  if (flag !== undefined && typeof flag !== "boolean") throw new Error("COSE `hashed` header is not a boolean");
  return { protectedHeader: ph, payload: detachedOrBytes, hashedFlag: flag === true, signature };
}

/** The 32-byte Ed25519 key of a COSE_Key {1: 1, 3: -8, -1: 6, -2: x}. */
export function coseKeyVkey(hex: string): Uint8Array {
  const clean = hex.trim().replace(/^0x/i, "");
  if (clean.length > MAX_COSE_HEX) throw new Error(`COSE_Key must be at most ${MAX_COSE_HEX} hex characters`);
  const key = decode(hexToBytes(clean));
  if (!(key instanceof Map) || mapGet(key, 1) !== 1 || mapGet(key, 3) !== -8 || mapGet(key, -1) !== 6)
    throw new Error("unsupported COSE key");
  const x = mapGet(key, -2);
  if (!(x instanceof Uint8Array) || x.length !== 32) throw new Error("unsupported COSE key");
  return x;
}

function bstrHeader(n: number): number[] {
  if (n < 24) return [0x40 | n];
  if (n < 0x100) return [0x58, n];
  if (n < 0x10000) return [0x59, n >> 8, n & 0xff];
  throw new Error("COSE field too long");
}

/** `["Signature1", protected, h'', signedPayload]` — what a CIP-30 wallet signs. */
export function sigStructure(protectedHeader: Uint8Array, signedPayload: Uint8Array): Uint8Array {
  const text = new TextEncoder().encode("Signature1");
  return Uint8Array.from([0x84, 0x60 | text.length, ...text,
    ...bstrHeader(protectedHeader.length), ...protectedHeader, 0x40,
    ...bstrHeader(signedPayload.length), ...signedPayload]);
}

export function blake2b224(data: Uint8Array): Uint8Array {
  return blake2b(data, { dkLen: 28 });
}

export interface VerifiedCip30 { hashed: boolean; issuerVkeyHex: string }

/**
 * Exactly the validator's check: the COSE payload (if present) is the claim or its
 * Blake2b-224, the protected header is 1–255 bytes, and the signature verifies over the
 * Sig_structure rebuilt from OUR claim.
 */
export function verifyCip30Claim(claim: Uint8Array, coseSign1Hex: string, coseKeyHex: string): VerifiedCip30 {
  const sign1 = decodeCoseSign1(coseSign1Hex);
  const vkey = coseKeyVkey(coseKeyHex);
  if (sign1.protectedHeader.length < 1 || sign1.protectedHeader.length > 255)
    throw new Error("COSE protected header must be 1–255 bytes");
  const digest = blake2b224(claim);
  const same = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((v, i) => v === b[i]);
  let hashed: boolean;
  if (sign1.payload === null) hashed = sign1.hashedFlag;
  else if (same(sign1.payload, claim)) hashed = false;
  else if (same(sign1.payload, digest)) hashed = true;
  else throw new Error("The wallet signed a different claim; sign the current claim again");
  if (!ed25519.verify(sign1.signature, sigStructure(sign1.protectedHeader, hashed ? digest : claim), vkey))
    throw new Error("The CIP-30 signature does not verify");
  return { hashed, issuerVkeyHex: bytesToHex(vkey) };
}
