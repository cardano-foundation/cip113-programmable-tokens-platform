import { apiGet, apiPostRaw } from './client';
import { signMintRequest, type MintAttestationConfig } from './mint-attestation';
import type { RwaTokenInitRequest, RwaTokenChainBuildResponse } from './rwa-token';

const base = '/rwa-token/create-attested';

export interface InitialMintAttestationView {
  intentId: string;
  status: string;
  signerAid: string;
  digest: string;
  targetTxHash?: string;
  seqNumber?: string;
  documentUrl?: string | null;
  expiresAt?: string;
  authorityStatus: string;
  submissionStatus?: string;
  transactionHashes?: Record<string, string>;
  registration: RwaTokenInitRequest;
  fields: {
    tokenPolicyId: string;
    assetName: string;
    quantity: string;
    feePayerAddress: string;
    recipientAddress: string;
    programmableRecipientAddress: string;
    network: string;
    protocolTxHash: string;
  };
}

export interface InitialMintRecovery {
  status: string;
  canStartNewPolicy: boolean;
  tipHash: string | null;
  tipSlot: number | null;
  reason: string;
}

export function getInitialMintRecovery(id: string, sessionId: string): Promise<InitialMintRecovery> {
  return apiGet<InitialMintRecovery>(`${base}/${encodeURIComponent(id)}/recovery`,
    { headers: { 'X-Session-Id': sessionId } });
}

export function archiveExpiredInitialMint(rawApi: unknown, intentId: string,
                                          sessionId: string, payer: string,
                                          config: MintAttestationConfig): Promise<InitialMintRecovery> {
  return signedPost(rawApi, payer, `${base}/${encodeURIComponent(intentId)}/archive-expired`,
    { sessionId, feePayerAddress: payer }, config, 60_000);
}

export function getInitialMintAttestationConfig(): Promise<MintAttestationConfig> {
  return apiGet<MintAttestationConfig>(`${base}/config`);
}

export function getInitialMintAttestation(id: string, sessionId: string): Promise<InitialMintAttestationView> {
  return apiGet<InitialMintAttestationView>(`${base}/${encodeURIComponent(id)}`,
    { headers: { 'X-Session-Id': sessionId } });
}

async function signedPost<T>(rawApi: unknown, payer: string, path: string,
                             value: object, config: MintAttestationConfig, timeout: number): Promise<T> {
  const body = JSON.stringify(value);
  const headers = await signMintRequest(rawApi, payer, path, body, config);
  return apiPostRaw<T>(path, body, { headers, timeout });
}

export function prepareInitialMintAttestation(rawApi: unknown, requestId: string, sessionId: string,
                                               registration: RwaTokenInitRequest,
                                               config: MintAttestationConfig,
                                               beforeSend: () => Promise<void>): Promise<InitialMintAttestationView> {
  const path = `${base}/prepare`;
  const body = JSON.stringify({ requestId, sessionId,
    registration: { moduleId: 'rwa-token', quantity: '0', ...registration } });
  return signMintRequest(rawApi, registration.feePayerAddress, path, body, config)
    .then(async headers => {
      await beforeSend();
      return apiPostRaw<InitialMintAttestationView>(path, body, { headers, timeout: 60_000 });
    });
}

export function anchorInitialMintAttestation(rawApi: unknown, intentId: string,
                                              sessionId: string, payer: string,
                                              config: MintAttestationConfig): Promise<InitialMintAttestationView> {
  return signedPost(rawApi, payer, `${base}/${encodeURIComponent(intentId)}/anchor`,
    { sessionId, feePayerAddress: payer }, config, 330_000);
}

export function finalizeInitialMintAttestation(rawApi: unknown, intentId: string,
                                                sessionId: string, payer: string,
                                                config: MintAttestationConfig): Promise<RwaTokenChainBuildResponse> {
  return signedPost(rawApi, payer, `${base}/${encodeURIComponent(intentId)}/finalize`,
    { sessionId, feePayerAddress: payer }, config, 180_000);
}

/** One wallet authorization covers Veridian approval and construction of the saved chain. */
export function approveAndBuildInitialMintAttestation(rawApi: unknown, intentId: string,
                                                       sessionId: string, payer: string,
                                                       config: MintAttestationConfig): Promise<RwaTokenChainBuildResponse> {
  return signedPost(rawApi, payer, `${base}/${encodeURIComponent(intentId)}/approve-and-build`,
    { sessionId, feePayerAddress: payer }, config, 540_000);
}

export function cancelInitialMintAttestation(rawApi: unknown, intentId: string,
                                              sessionId: string, payer: string,
                                              config: MintAttestationConfig): Promise<InitialMintAttestationView> {
  return signedPost(rawApi, payer, `${base}/${encodeURIComponent(intentId)}/cancel`,
    { sessionId, feePayerAddress: payer }, config, 60_000);
}
