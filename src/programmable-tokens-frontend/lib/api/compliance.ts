/**
 * Compliance API
 * Handles blacklist management and token seizure operations
 */

import { apiGet, apiPost } from './client';
import type {
  BlacklistInitRequest,
  BlacklistInitResponse,
  AddToBlacklistRequest,
  RemoveFromBlacklistRequest,
  BlacklistOperationResponse,
  SeizeTokensRequest,
  SeizeTokensResponse,
  AddToWhitelistRequest,
  RemoveFromWhitelistRequest,
  WhitelistOperationResponse,
  GlobalStateInitRequest,
  GlobalStateInitResponse,
  AddTrustedEntityRequest,
  RemoveTrustedEntityRequest,
  TrustedEntityOperationResponse,
  GlobalStateUpdateRequest,
  GlobalStateUpdateResponse,
  GlobalStateData,
} from '@/types/compliance';

// Re-export types
export type {
  BlacklistInitRequest,
  BlacklistInitResponse,
  AddToBlacklistRequest,
  RemoveFromBlacklistRequest,
  BlacklistOperationResponse,
  SeizeTokensRequest,
  SeizeTokensResponse,
  AddToWhitelistRequest,
  RemoveFromWhitelistRequest,
  WhitelistOperationResponse,
  GlobalStateInitRequest,
  GlobalStateInitResponse,
  AddTrustedEntityRequest,
  RemoveTrustedEntityRequest,
  TrustedEntityOperationResponse,
  GlobalStateUpdateRequest,
  GlobalStateUpdateResponse,
  GlobalStateData,
};

/**
 * Initialize a new blacklist for a token
 * Creates the blacklist node on-chain
 *
 * @param request - Blacklist initialization parameters
 * @param protocolTxHash - Optional protocol version transaction hash
 * @returns Promise with blacklist node policy ID and unsigned transaction
 */
export async function initBlacklist(
  request: BlacklistInitRequest,
  protocolTxHash?: string
): Promise<BlacklistInitResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/blacklist/init?protocolTxHash=${protocolTxHash}`
    : '/compliance/blacklist/init';

  return apiPost<BlacklistInitRequest, BlacklistInitResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Add an address to the blacklist
 * The address will be frozen and unable to transfer tokens
 *
 * @param request - Add to blacklist parameters
 * @param protocolTxHash - Optional protocol version transaction hash
 * @returns Promise<string> - Unsigned transaction CBOR hex
 */
export async function addToBlacklist(
  request: AddToBlacklistRequest,
  protocolTxHash?: string
): Promise<BlacklistOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/blacklist/add?protocolTxHash=${protocolTxHash}`
    : '/compliance/blacklist/add';

  return apiPost<AddToBlacklistRequest, BlacklistOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Remove an address from the blacklist
 * The address will be unfrozen and able to transfer tokens again
 *
 * @param request - Remove from blacklist parameters
 * @param protocolTxHash - Optional protocol version transaction hash
 * @returns Promise<string> - Unsigned transaction CBOR hex
 */
export async function removeFromBlacklist(
  request: RemoveFromBlacklistRequest,
  protocolTxHash?: string
): Promise<BlacklistOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/blacklist/remove?protocolTxHash=${protocolTxHash}`
    : '/compliance/blacklist/remove';

  return apiPost<RemoveFromBlacklistRequest, BlacklistOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Seize tokens from a blacklisted address
 * Transfers tokens from a target UTxO to the specified recipient
 *
 * @param request - Seize tokens parameters
 * @param protocolTxHash - Optional protocol version transaction hash
 * @returns Promise<string> - Unsigned transaction CBOR hex
 */
export async function seizeTokens(
  request: SeizeTokensRequest,
  protocolTxHash?: string
): Promise<SeizeTokensResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/seize?protocolTxHash=${protocolTxHash}`
    : '/compliance/seize';

  return apiPost<SeizeTokensRequest, SeizeTokensResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Check if an address is blacklisted for a given token
 *
 * @param tokenPolicyId - Policy ID of the token
 * @param address - Address to check
 * @param protocolTxHash - Optional protocol version transaction hash
 * @returns Promise<boolean> - Whether the address is blacklisted
 */
/**
 * Add a verification key to the trusted entity list (whitelist)
 */
export async function addToWhitelist(
  request: AddToWhitelistRequest,
  protocolTxHash?: string
): Promise<WhitelistOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/whitelist/add?protocolTxHash=${protocolTxHash}`
    : '/compliance/whitelist/add';

  return apiPost<AddToWhitelistRequest, WhitelistOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Remove a verification key from the trusted entity list (whitelist)
 */
export async function removeFromWhitelist(
  request: RemoveFromWhitelistRequest,
  protocolTxHash?: string
): Promise<WhitelistOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/whitelist/remove?protocolTxHash=${protocolTxHash}`
    : '/compliance/whitelist/remove';

  return apiPost<RemoveFromWhitelistRequest, WhitelistOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Initialize the global state UTxO for a new KYC token deployment.
 * Uses the /global-state/init endpoint (GlobalStateManageable — not WhitelistManageable).
 */
export async function initGlobalState(
  request: GlobalStateInitRequest,
  protocolTxHash?: string
): Promise<GlobalStateInitResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/global-state/init?protocolTxHash=${protocolTxHash}`
    : '/compliance/global-state/init';

  return apiPost<GlobalStateInitRequest, GlobalStateInitResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Add a trusted entity (verification key) to the global state.
 * Uses the /global-state/add-entity endpoint.
 */
export async function addToGlobalState(
  request: AddTrustedEntityRequest,
  protocolTxHash?: string
): Promise<TrustedEntityOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/global-state/add-entity?protocolTxHash=${protocolTxHash}`
    : '/compliance/global-state/add-entity';

  return apiPost<AddTrustedEntityRequest, TrustedEntityOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Remove a trusted entity (verification key) from the global state.
 * Uses the /global-state/remove-entity endpoint.
 */
export async function removeFromGlobalState(
  request: RemoveTrustedEntityRequest,
  protocolTxHash?: string
): Promise<TrustedEntityOperationResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/global-state/remove-entity?protocolTxHash=${protocolTxHash}`
    : '/compliance/global-state/remove-entity';

  return apiPost<RemoveTrustedEntityRequest, TrustedEntityOperationResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

/**
 * Read the current on-chain global state for a KYC token.
 */
export async function readGlobalState(policyId: string): Promise<GlobalStateData> {
  return apiGet<GlobalStateData>(
    `/compliance/global-state/read?policyId=${encodeURIComponent(policyId)}`,
    { timeout: 30000 }
  );
}

/**
 * Update the global state UTxO (pause transfers, mintable amount, security info)
 */
export async function updateGlobalState(
  request: GlobalStateUpdateRequest,
  protocolTxHash?: string
): Promise<GlobalStateUpdateResponse> {
  const endpoint = protocolTxHash
    ? `/compliance/global-state/update?protocolTxHash=${protocolTxHash}`
    : '/compliance/global-state/update';

  return apiPost<GlobalStateUpdateRequest, GlobalStateUpdateResponse>(
    endpoint,
    request,
    { timeout: 60000 }
  );
}

export async function isAddressBlacklisted(
  tokenPolicyId: string,
  address: string,
  protocolTxHash?: string
): Promise<boolean> {
  // This would typically be a GET endpoint
  // For now, we'll assume it returns a boolean
  const endpoint = protocolTxHash
    ? `/compliance/blacklist/check?policyId=${tokenPolicyId}&address=${address}&protocolTxHash=${protocolTxHash}`
    : `/compliance/blacklist/check?policyId=${tokenPolicyId}&address=${address}`;

  try {
    const response = await fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8080'}/api/v1${endpoint}`);
    if (!response.ok) {
      return false;
    }
    const result = await response.json();
    return result.blacklisted === true;
  } catch {
    // If check fails, assume not blacklisted
    return false;
  }
}
