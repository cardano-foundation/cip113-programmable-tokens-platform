import { Address } from "@evolution-sdk/evolution";
import type { CmtaAttestation } from "../../types/api";
import { hexToBytes, verifyCip30Claim } from "./cose";

export interface StakeIdentity { credentialHash: string; credentialType: 0 | 1 }

export function stakeIdentityFromBaseAddress(value: string): StakeIdentity {
  const address = Address.fromBech32(value.trim().toLowerCase());
  const credential = address.stakingCredential;
  if (!credential || credential.hash.length !== 28)
    throw new Error("A Cardano base address with a stake credential is required");
  return {
    credentialHash: Array.from(credential.hash, (b) => b.toString(16).padStart(2, "0")).join(""),
    credentialType: credential._tag === "ScriptHash" ? 1 : 0,
  };
}

export function sameStakeIdentity(a: StakeIdentity, b: StakeIdentity): boolean {
  return a.credentialType === b.credentialType && a.credentialHash === b.credentialHash;
}

/** Bytes signed by a trusted CMTA issuer; the signature is over raw bytes, not hex text. */
export function buildCmtaAttestationPayloadHex(address: string, policyId: string,
    networkId: number, tier: number, validUntilMs: number, nowMs = Date.now()): string {
  const subject = stakeIdentityFromBaseAddress(address);
  if (!/^[0-9a-fA-F]{56}$/.test(policyId))
    throw new Error("Token policy ID must be 28 bytes of hex");
  if (!Number.isInteger(networkId) || networkId < 0 || networkId > 255)
    throw new Error("Live CMTA network ID is unavailable");
  if (!Number.isInteger(tier) || tier < 1 || tier > 255)
    throw new Error("KYC tier must be an integer from 1 to 255");
  if (!Number.isSafeInteger(validUntilMs) || validUntilMs < nowMs + 120_000)
    throw new Error("Attestation expiry must be at least two minutes in the future");

  const bytes = new Uint8Array(67);
  for (let i = 0; i < 28; i++) {
    bytes[i] = parseInt(subject.credentialHash.slice(i * 2, i * 2 + 2), 16);
    bytes[37 + i] = parseInt(policyId.slice(i * 2, i * 2 + 2), 16);
  }
  bytes[28] = tier;
  let expiry = BigInt(validUntilMs);
  for (let i = 36; i >= 29; i--) {
    bytes[i] = Number(expiry & 255n);
    expiry >>= 8n;
  }
  bytes[65] = networkId;
  bytes[66] = subject.credentialType;
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}


/**
 * A CIP-30 `signData` result pasted from ANOTHER wallet, as `{signature, key}` JSON or the two
 * hex fields. It only proves a signature over whatever was signed, so it must be over the claim
 * this dialog built and copied — and that claim must not have changed since.
 */
export function prepareCip30Signature(text: string, signedPayloadHex: string,
    currentPayloadHex: string, trustedVkeys: string[]): CmtaAttestation {
  if (!signedPayloadHex)
    throw new Error("Copy the claim before pasting its signData result");
  if (!currentPayloadHex || signedPayloadHex !== currentPayloadHex)
    throw new Error("The claim changed; copy its new payload and sign again");
  const { coseSign1Hex, coseKeyHex } = readSignDataResult(text);
  return checkedAttestation(currentPayloadHex, coseSign1Hex, coseKeyHex, trustedVkeys);
}

/** `{signature, key}` (what signData returns), or `signature` and `key` hex on two lines. */
function readSignDataResult(text: string): { coseSign1Hex: string; coseKeyHex: string } {
  const trimmed = text.trim();
  let signature: unknown, key: unknown;
  if (trimmed.startsWith("{")) {
    let parsed: unknown;
    try { parsed = JSON.parse(trimmed); } catch { throw new Error("Paste the wallet's signData result as JSON"); }
    const record = (parsed ?? {}) as Record<string, unknown>;
    signature = record.signature; key = record.key;
  } else {
    [signature, key] = trimmed.split(/\s+/);
  }
  if (typeof signature !== "string" || typeof key !== "string" || !signature || !key)
    throw new Error("Paste the wallet's signData result: {\"signature\": \"…\", \"key\": \"…\"}");
  return { coseSign1Hex: signature.replace(/^0x/i, "").toLowerCase(), coseKeyHex: key.replace(/^0x/i, "").toLowerCase() };
}

function checkedAttestation(payloadHex: string, coseSign1Hex: string, coseKeyHex: string,
    trustedVkeys: string[]): CmtaAttestation {
  if (!trustedVkeys.some((key) => typeof key === "string" && /^[0-9a-fA-F]{64}$/.test(key)))
    throw new Error("This token has no trusted entity that can sign a claim");
  const verified = verifyCip30Claim(hexToBytes(payloadHex), coseSign1Hex, coseKeyHex);
  if (!trustedVkeys.some((key) => key.toLowerCase() === verified.issuerVkeyHex))
    throw new Error(`Signed by ${verified.issuerVkeyHex}, which is not a trusted issuer of this token`);
  return { payloadHex: payloadHex.toLowerCase(), coseSign1Hex, coseKeyHex };
}

export interface ParsedCmtaAttestation { attestation: CmtaAttestation; tier: number; validUntilMs: number; hashed: boolean }

/** The copy-paste bundle an issuer hands a holder. Compact JSON, version 1. */
export function buildAttestationBundle(attestation: CmtaAttestation): string {
  return JSON.stringify({ v: 1, payloadHex: attestation.payloadHex,
    coseSign1Hex: attestation.coseSign1Hex, coseKeyHex: attestation.coseKeyHex });
}

/**
 * Advisory UI check of a bundle against THIS party, token and network; the backend repeats it
 * against the live GlobalState. The bundle's own claim is the claim — nothing is rebuilt.
 */
export function parseCmtaAttestationJson(text: string, address: string, policyId: string,
    networkId: number, trustedVkeys: string[], nowMs = Date.now()): ParsedCmtaAttestation {
  let input: unknown;
  try { input = JSON.parse(text); } catch { throw new Error("Paste a JSON attestation bundle"); }
  if (!input || typeof input !== "object" || Array.isArray(input))
    throw new Error("Attestation must be a JSON object");
  const record = input as Record<string, unknown>;
  if (record.v !== 1) throw new Error("Unsupported attestation bundle version (expected v: 1)");
  const readHex = (name: string, bytes?: number): string => {
    const value = record[name];
    if (typeof value !== "string" || !/^[0-9a-fA-F]+$/.test(value) || value.length % 2 !== 0
        || (bytes !== undefined && value.length !== bytes * 2))
      throw new Error(bytes !== undefined ? `${name} must be ${bytes} bytes of hex` : `${name} must be hex`);
    return value.toLowerCase();
  };
  const payloadHex = readHex("payloadHex", 67);
  const coseSign1Hex = readHex("coseSign1Hex");
  const coseKeyHex = readHex("coseKeyHex");
  const payload = hexToBytes(payloadHex);
  const identity = stakeIdentityFromBaseAddress(address);
  if (payloadHex.slice(0, 56) !== identity.credentialHash)
    throw new Error("Attestation is for a different stake credential");
  const tier = payload[28];
  if (tier === 0) throw new Error("Attestation has an invalid KYC tier");
  let expiry = 0n;
  for (const byte of payload.slice(29, 37)) expiry = (expiry << 8n) | BigInt(byte);
  if (expiry > BigInt(Number.MAX_SAFE_INTEGER) || expiry <= BigInt(nowMs))
    throw new Error("Attestation expiry is invalid or has passed");
  if (payloadHex.slice(74, 130) !== policyId.toLowerCase())
    throw new Error("Attestation is for a different token");
  if (payload[65] !== networkId) throw new Error("Attestation is for a different network");
  if (payload[66] !== identity.credentialType)
    throw new Error("Attestation credential type does not match the address");
  const verified = verifyCip30Claim(payload, coseSign1Hex, coseKeyHex);
  if (!trustedVkeys.some((key) => key.toLowerCase() === verified.issuerVkeyHex))
    throw new Error("Issuer is not trusted by this token");
  return { attestation: { payloadHex, coseSign1Hex, coseKeyHex }, tier, validUntilMs: Number(expiry), hashed: verified.hashed };
}

export interface PasteContext {
  /** The address of the party this paste is for (sender or recipient). */
  address: string;
  policyId: string;
  networkId: number;
  trustedVkeys: string[];
  /** The claim the dialog built when the user copied it (raw signData path only). */
  signedPayloadHex: string;
  /** The claim the dialog's tier/expiry inputs build NOW (raw signData path only). */
  currentPayloadHex: string;
  nowMs?: number;
}

export type ResolvedAttestation =
  | { kind: "bundle"; attestation: CmtaAttestation; tier: number; validUntilMs: number }
  | { kind: "signData"; attestation: CmtaAttestation };

/**
 * The holder-side data flow (plan "Holder-side data flow"):
 * - a BUNDLE (`{"v":1,…}`) carries its own claim: it is checked against this party and sent
 *   as-is — no copied payload needed, the modal's tier/expiry do not apply;
 * - a raw signData `{signature,key}` signs whatever the dialog copied, so it needs the copied,
 *   unchanged claim.
 */
export function resolvePastedAttestation(text: string, ctx: PasteContext): ResolvedAttestation {
  const trimmed = text.trim();
  let isBundle = false;
  if (trimmed.startsWith("{")) {
    try { isBundle = (JSON.parse(trimmed) as Record<string, unknown>)?.v !== undefined; } catch { /* reported below */ }
  }
  if (isBundle) {
    const parsed = parseCmtaAttestationJson(trimmed, ctx.address, ctx.policyId, ctx.networkId,
      ctx.trustedVkeys, ctx.nowMs);
    return { kind: "bundle", attestation: parsed.attestation, tier: parsed.tier, validUntilMs: parsed.validUntilMs };
  }
  return { kind: "signData",
    attestation: prepareCip30Signature(trimmed, ctx.signedPayloadHex, ctx.currentPayloadHex, ctx.trustedVkeys) };
}
