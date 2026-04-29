/**
 * KERI API client for KYC credential verification flow.
 * All calls include X-Session-Id header for session tracking.
 */

import { apiGet, apiPost, type FetchOptions } from './client';

export interface OobiResponse {
  oobi: string;
}

export interface CredentialResponse {
  role: string;
  roleValue: number;
  label: string;
  attributes: Record<string, unknown>;
}

export interface KycProofResponse {
  payloadHex: string;
  signatureHex: string;
  entityVkeyHex: string;
  validUntilPosixMs: number;
  role: number;
  roleName: string;
}

export interface SessionResponse {
  exists: boolean;
  hasCredential?: boolean;
  hasCardanoAddress?: boolean;
  attributes?: Record<string, unknown>;
  credentialRole?: number;
  credentialRoleName?: string;
  cardanoAddress?: string;
  kycProofPayload?: string;
  kycProofSignature?: string;
  kycProofEntityVkey?: string;
  kycProofValidUntil?: number;
}

export interface SchemaItem {
  roleName: string;
  roleValue: number;
  label: string;
  said: string;
}

export interface SchemaListResponse {
  schemas: SchemaItem[];
}

function sessionHeaders(sessionId: string): FetchOptions {
  return { headers: { 'X-Session-Id': sessionId } };
}

export async function getAgentOobi(sessionId: string): Promise<OobiResponse> {
  return apiGet<OobiResponse>('/keri/oobi', sessionHeaders(sessionId));
}

export async function resolveOobi(sessionId: string, oobi: string): Promise<boolean> {
  return apiGet<boolean>(`/keri/oobi/resolve?oobi=${encodeURIComponent(oobi)}`, sessionHeaders(sessionId));
}

export async function getSchemas(sessionId: string): Promise<SchemaListResponse> {
  return apiGet<SchemaListResponse>('/keri/schemas', sessionHeaders(sessionId));
}

export async function presentCredential(
  sessionId: string,
  role: string = 'USER'
): Promise<CredentialResponse> {
  return apiGet<CredentialResponse>(
    `/keri/credential/present?role=${encodeURIComponent(role)}`,
    { ...sessionHeaders(sessionId), timeout: 120000 }
  );
}

export async function cancelPresentation(sessionId: string): Promise<void> {
  await apiPost('/keri/credential/cancel', {}, sessionHeaders(sessionId));
}

export async function getSession(sessionId: string): Promise<SessionResponse> {
  return apiGet<SessionResponse>('/keri/session', sessionHeaders(sessionId));
}

export async function storeCardanoAddress(
  sessionId: string,
  cardanoAddress: string
): Promise<void> {
  await apiPost('/keri/session/cardano-address', { cardanoAddress }, sessionHeaders(sessionId));
}

export async function generateKycProof(sessionId: string): Promise<KycProofResponse> {
  return apiPost<Record<string, never>, KycProofResponse>(
    '/keri/kyc-proof/generate',
    {},
    sessionHeaders(sessionId)
  );
}

export interface SigningEntityVkeyResponse {
  vkeyHex: string;
}

export async function getSigningEntityVkey(): Promise<SigningEntityVkeyResponse> {
  return apiGet<SigningEntityVkeyResponse>('/keri/signing-entity-vkey');
}

export interface AvailableRole {
  role: string;
  roleValue: number;
  label: string;
}

export interface AvailableRolesResponse {
  availableRoles: AvailableRole[];
}

export async function getAvailableRoles(sessionId: string): Promise<AvailableRolesResponse> {
  return apiGet<AvailableRolesResponse>('/keri/available-roles', sessionHeaders(sessionId));
}

export interface IssueCredentialRequest {
  firstName: string;
  lastName: string;
  email: string;
}

export async function issueCredential(
  sessionId: string,
  data: IssueCredentialRequest
): Promise<CredentialResponse> {
  return apiPost<IssueCredentialRequest, CredentialResponse>(
    '/keri/credential/issue',
    data,
    sessionHeaders(sessionId)
  );
}

// ── CIP-170 Attestation ──────────────────────────────────────────────────────

export interface Cip170AttestationData {
  signerAid: string;
  digest: string;
  seqNumber: string;
  cipVersion: string;
}

/**
 * Publish credential chain on-chain as a CIP-170 AUTH_BEGIN transaction.
 * Returns unsigned CBOR hex for wallet signing.
 */
export async function publishCredentialChain(
  sessionId: string,
  feePayerAddress: string
): Promise<string> {
  return apiPost<{ feePayerAddress: string }, string>(
    '/keri/credential-chain/publish',
    { feePayerAddress },
    { ...sessionHeaders(sessionId), timeout: 60000 }
  );
}

/**
 * Request the user's Veridian wallet to anchor a digest for CIP-170 attestation.
 * The backend sends an exchange message to the wallet and waits for the interact event.
 * Returns the attestation data to be included in the mint transaction.
 */
export async function requestAttestation(
  sessionId: string,
  unit: string,
  quantity: string
): Promise<Cip170AttestationData> {
  return apiPost<{ unit: string; quantity: string }, Cip170AttestationData>(
    '/keri/attest/request',
    { unit, quantity },
    { ...sessionHeaders(sessionId), timeout: 120000 }
  );
}
