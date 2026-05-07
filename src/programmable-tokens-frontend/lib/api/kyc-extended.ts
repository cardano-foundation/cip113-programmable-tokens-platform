/** API client for the kyc-extended substandard. */

import { apiGet, apiPost } from './client';

export interface MpfInclusionProof {
  memberPkh: string;
  proofCborHex: string;
  validUntilMs: number;
  rootHashOnchain: string;
  rootHashLocal: string;
}

export const getMpfInclusionProof = (policyId: string, memberPkh: string) =>
  apiGet<MpfInclusionProof>(`/kyc-extended/${policyId}/proofs/${memberPkh}`);

export interface RequestMpfInclusionResponse {
  memberPkh: string;
  currentRootLocal: string;
}

export const requestMpfInclusion = (
  policyId: string,
  body: { boundAddress: string; kycSessionId?: string; validUntilMs: number },
) =>
  apiPost<typeof body, RequestMpfInclusionResponse>(
    `/kyc-extended/${policyId}/members`,
    body,
  );

export interface KycExtendedTokenSummary {
  policyId: string;
  assetName: string;
  displayName: string;
  description?: string | null;
  registeredAt: number;
}

export const listKycExtendedTokens = () =>
  apiGet<KycExtendedTokenSummary[]>(`/kyc-extended/tokens`);

export interface KycExtendedAdminInfo {
  adminPkh: string;
  adminAddress: string;
}

/** Backend's admin signing key PKH. Must be used as issuerAdminPkh when registering a
 *  kyc-extended token so the backend can autonomously sign UpdateMemberRootHash. */
export const getKycExtendedAdminPkh = () =>
  apiGet<KycExtendedAdminInfo>(`/kyc-extended/admin-pkh`);

/** Bind the current KERI session to a kyc-extended token policy — the next proof
 *  generation auto-upserts the user's PKH into the per-policy MPF tree. */
export const bindSessionToToken = (policyId: string) =>
  apiPost<{ policyId: string }, void>(`/keri/session/bound-token`, { policyId });
