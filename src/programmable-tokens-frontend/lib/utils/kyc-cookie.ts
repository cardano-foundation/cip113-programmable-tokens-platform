/** Cookie-based KYC proof cache, scoped by (policyId, walletAddress) so switching
 *  wallets in the same browser cannot surface another wallet's credential. */

import { walletKeyForAddress } from "@/lib/utils/keri-session";

export interface KycProofCookie {
  payloadHex: string;
  signatureHex: string;
  entityVkeyHex: string;
  validUntilMs: number;
  role: number;
  roleName: string;
}

function cookieName(policyId: string, walletAddress: string): string {
  return `kyc_proof_${policyId}_${walletKeyForAddress(walletAddress)}`;
}

export function getKycProof(policyId: string, walletAddress: string): KycProofCookie | null {
  if (typeof document === 'undefined') return null;

  const name = cookieName(policyId, walletAddress);
  const match = document.cookie
    .split('; ')
    .find(row => row.startsWith(name + '='));

  if (!match) return null;

  try {
    const value = decodeURIComponent(match.split('=').slice(1).join('='));
    const proof: KycProofCookie = JSON.parse(value);

    if (proof.validUntilMs <= Date.now()) {
      clearKycProof(policyId, walletAddress);
      return null;
    }

    return proof;
  } catch {
    clearKycProof(policyId, walletAddress);
    return null;
  }
}

export function setKycProof(policyId: string, walletAddress: string, proof: KycProofCookie): void {
  if (typeof document === 'undefined') return;

  const name = cookieName(policyId, walletAddress);
  const value = encodeURIComponent(JSON.stringify(proof));
  const maxAgeSeconds = Math.max(0, Math.floor((proof.validUntilMs - Date.now()) / 1000));

  document.cookie = `${name}=${value}; max-age=${maxAgeSeconds}; path=/; SameSite=Strict`;
}

export function clearKycProof(policyId: string, walletAddress: string): void {
  if (typeof document === 'undefined') return;

  const name = cookieName(policyId, walletAddress);
  document.cookie = `${name}=; max-age=0; path=/; SameSite=Strict`;
}
