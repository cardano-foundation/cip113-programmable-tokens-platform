import { Address } from "@evolution-sdk/evolution";
import type { CmtaAttestation } from "../../types/api";

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

/** The dialog accepts only a signature; the issuer key is resolved by the backend. */
export function prepareCmtaSignature(signatureText: string, signedPayloadHex: string,
    currentPayloadHex: string, trustedVkeys: string[]): CmtaAttestation {
  if (!signedPayloadHex)
    throw new Error("Copy the payload before pasting its signature");
  if (!currentPayloadHex || signedPayloadHex !== currentPayloadHex)
    throw new Error("The claim changed; copy its new payload and paste a new signature");
  if (!trustedVkeys.some((key) => typeof key === "string" && /^[0-9a-fA-F]{64}$/.test(key)))
    throw new Error("This token has no trusted entity that can sign a claim");
  const signatureHex = signatureText.trim().replace(/^0x/i, "");
  if (!/^[0-9a-fA-F]{128}$/.test(signatureHex))
    throw new Error("Paste a 64-byte raw Ed25519 signature in hex");
  return { payloadHex: currentPayloadHex, signatureHex: signatureHex.toLowerCase() };
}

export interface ParsedCmtaAttestation { attestation: CmtaAttestation; tier: number; validUntilMs: number }

/** Advisory UI check; the backend repeats it against the live GS and verifies Ed25519. */
export function parseCmtaAttestationJson(text: string, address: string, policyId: string,
    networkId: number, trustedVkeys: string[], nowMs = Date.now()): ParsedCmtaAttestation {
  let input: unknown;
  try { input = JSON.parse(text); } catch { throw new Error("Paste a JSON attestation bundle"); }
  if (!input || typeof input !== "object" || Array.isArray(input))
    throw new Error("Attestation must be a JSON object");
  const record = input as Record<string, unknown>;
  const readHex = (name: string, bytes: number): string => {
    const value = record[name];
    if (typeof value !== "string" || !new RegExp(`^[0-9a-fA-F]{${bytes * 2}}$`).test(value))
      throw new Error(`${name} must be ${bytes} bytes of hex`);
    return value.toLowerCase();
  };
  const payloadHex = readHex("payloadHex", 67);
  const signatureHex = readHex("signatureHex", 64);
  const issuerVkeyHex = readHex("issuerVkeyHex", 32);
  const payload = Uint8Array.from(payloadHex.match(/../g)!, (part) => parseInt(part, 16));
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
  if (!trustedVkeys.some((key) => key.toLowerCase() === issuerVkeyHex))
    throw new Error("Issuer is not trusted by this token");
  return { attestation: { payloadHex, signatureHex, issuerVkeyHex }, tier, validUntilMs: Number(expiry) };
}
