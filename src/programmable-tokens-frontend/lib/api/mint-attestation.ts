import { apiGet, apiPostRaw } from "./client";
import { bytesHex } from "../rwa/member-root";
import { payerAddressHex } from "../rwa/creation-auth";

export interface MintAttestationRequest {
  requestId?: string;
  sessionId: string;
  network: string;
  protocolTxHash: string | null;
  tokenPolicyId: string;
  assetName: string;
  quantity: string;
  feePayerAddress: string;
  recipientAddress: string;
  programmableRecipientAddress: string | null;
}

export interface MintAttestationView {
  intentId: string;
  status: string;
  signerAid: string;
  digest: string;
  seqNumber?: string;
  documentUrl?: string | null;
  targetTxHash?: string | null;
  attestationTxHash?: string | null;
  expiresAt?: string;
  fields: MintAttestationRequest;
}

export interface MintAttestationChain {
  mintCborHex: string;
  attestationCborHex: string;
  mintTxHash: string;
  attestationTxHash: string;
}

export interface MintAttestationConfig { network: string; audience: string; }

export function getMintAttestationConfig(): Promise<MintAttestationConfig> {
  return apiGet<MintAttestationConfig>("/keri/mint-attestations/config");
}

export function getMintAttestation(intentId: string, sessionId: string): Promise<MintAttestationView> {
  return apiGet<MintAttestationView>(`/keri/mint-attestations/${encodeURIComponent(intentId)}`,
    { headers: { "X-Session-Id": sessionId } });
}

interface SignDataWallet {
  signData(addressHex: string, payloadHex: string): Promise<{ signature: string; key: string }>;
}

export async function signMintRequest(rawApi: unknown, payer: string, path: string,
                               body: string, config: MintAttestationConfig): Promise<Record<string, string>> {
  const wallet = rawApi as SignDataWallet | null;
  if (!wallet?.signData)
    throw new Error("Connected wallet does not support CIP-30 signData");
  const payerHex = payerAddressHex(payer);
  const nonceBytes = new Uint8Array(32);
  crypto.getRandomValues(nonceBytes);
  const nonce = bytesHex(nonceBytes);
  const issued = Date.now();
  const expires = issued + 300_000;
  const bodyDigest = bytesHex(new Uint8Array(await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(body))));
  const payload = "CIP-170 mint API v1\n"
    + `audience=${config.audience}\nnetwork=${config.network}\nmethod=POST\n`
    + `path=${path}\nbody-sha256=${bodyDigest}\nnonce=${nonce}\n`
    + `issued=${issued}\nexpires=${expires}\n`;
  const signed = await wallet.signData(payerHex, bytesHex(new TextEncoder().encode(payload)));
  return {
    "X-CMTA-Address": payerHex,
    "X-CMTA-Nonce": nonce,
    "X-CMTA-Issued": String(issued),
    "X-CMTA-Expires": String(expires),
    "X-CMTA-Signature": signed.signature,
    "X-CMTA-Key": signed.key,
  };
}

export async function prepareMintAttestation(rawApi: unknown,
                                               request: MintAttestationRequest,
                                               config: MintAttestationConfig): Promise<MintAttestationView> {
  const path = "/keri/mint-attestations/prepare";
  const body = JSON.stringify(request);
  const headers = await signMintRequest(rawApi, request.feePayerAddress, path, body, config);
  return apiPostRaw<MintAttestationView>(path, body, { headers, timeout: 60_000 });
}

export async function anchorMintAttestation(rawApi: unknown, intentId: string,
                                              request: MintAttestationRequest,
                                              config: MintAttestationConfig): Promise<MintAttestationView> {
  const path = `/keri/mint-attestations/${encodeURIComponent(intentId)}/anchor`;
  const body = JSON.stringify(request);
  const headers = await signMintRequest(rawApi, request.feePayerAddress, path, body, config);
  return apiPostRaw<MintAttestationView>(path, body, { headers, timeout: 330_000 });
}

export async function buildMintAttestationChain(rawApi: unknown, intentId: string,
                                                request: MintAttestationRequest,
                                                config: MintAttestationConfig): Promise<MintAttestationChain> {
  const path = `/keri/mint-attestations/${encodeURIComponent(intentId)}/build-chain`;
  const body = JSON.stringify(request);
  const headers = await signMintRequest(rawApi, request.feePayerAddress, path, body, config);
  return apiPostRaw<MintAttestationChain>(path, body, { headers, timeout: 180_000 });
}
