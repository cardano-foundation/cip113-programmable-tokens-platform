/**
 * Cookie-based storage for KYC proofs.
 * Each cookie is scoped per policy ID and auto-expires when the proof's valid_until passes.
 */

export interface KycProofCookie {
  payloadHex: string;
  signatureHex: string;
  entityVkeyHex: string;
  validUntilMs: number;
  role: number;
  roleName: string;
}

function cookieName(policyId: string): string {
  return `kyc_proof_${policyId}`;
}

export function getKycProof(policyId: string): KycProofCookie | null {
  if (typeof document === 'undefined') return null;

  const name = cookieName(policyId);
  const match = document.cookie
    .split('; ')
    .find(row => row.startsWith(name + '='));

  if (!match) return null;

  try {
    const value = decodeURIComponent(match.split('=').slice(1).join('='));
    const proof: KycProofCookie = JSON.parse(value);

    // Check if proof has expired
    if (proof.validUntilMs <= Date.now()) {
      clearKycProof(policyId);
      return null;
    }

    return proof;
  } catch {
    clearKycProof(policyId);
    return null;
  }
}

export function setKycProof(policyId: string, proof: KycProofCookie): void {
  if (typeof document === 'undefined') return;

  const name = cookieName(policyId);
  const value = encodeURIComponent(JSON.stringify(proof));
  const maxAgeSeconds = Math.max(0, Math.floor((proof.validUntilMs - Date.now()) / 1000));

  document.cookie = `${name}=${value}; max-age=${maxAgeSeconds}; path=/; SameSite=Strict`;
}

export function clearKycProof(policyId: string): void {
  if (typeof document === 'undefined') return;

  const name = cookieName(policyId);
  document.cookie = `${name}=; max-age=0; path=/; SameSite=Strict`;
}
