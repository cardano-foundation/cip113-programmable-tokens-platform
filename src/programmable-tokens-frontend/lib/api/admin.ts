/**
 * Admin Tokens API
 *
 * Fetches tokens where the connected wallet has admin roles:
 * GET /api/v1/admin/tokens/{pkh}
 */

import { UtxoListResponse } from '@/types/api';
import { apiGet } from './client';

// ============================================================================
// Types
// ============================================================================

export type AdminRole = "ISSUER_ADMIN" | "BLACKLIST_MANAGER";

export interface AdminTokenInfo {
  policyId: string;
  assetName: string;          // Hex encoded
  assetNameDisplay: string;   // Human readable
  substandardId: string;
  roles: AdminRole[];
  details: {
    blacklistNodePolicyId?: string;
    issuerAdminPkh?: string;
    blacklistAdminPkh?: string;
    globalStatePolicyId?: string;
  };
  /** RWA-token only: bitfield of the connected wallet's BaFin power-user
   *  capabilities for this token. Backed by {@link RwaTokenCapability}.
   *  Use {@link hasRwaTokenCapability} to test specific bits. Null/undefined
   *  for tokens of other substandards. */
  rwaTokenCapabilities?: number;
}

/** Bit positions matching the on-chain BaFin PowerUser record. Mirrors
 *  the backend's {@code RwaTokenPowerUserCapability} enum. */
export const RwaTokenCapability = {
  ADMIN: 1 << 0,
  MINTER: 1 << 1,
  BURNER: 1 << 2,
  PAUSER: 1 << 3,
  FORCE_TRANSFER: 1 << 4,
} as const;

/** True iff the connected wallet has AT LEAST ONE of the given capabilities
 *  on the given rwa-token. Returns false for non-RWA tokens (since
 *  these capabilities are BaFin-specific). */
export function hasRwaTokenCapability(
  token: AdminTokenInfo,
  capabilities: number,
): boolean {
  if (token.substandardId !== "rwa-token") return false;
  const cap = token.rwaTokenCapabilities ?? 0;
  return (cap & capabilities) !== 0;
}

export interface AdminTokensResponse {
  adminPkh: string;
  tokens: AdminTokenInfo[];
}

// ============================================================================
// API Functions
// ============================================================================

/**
 * Get all tokens where the given PKH has admin roles
 *
 * @param pkh - Payment key hash of the admin
 * @returns Promise<AdminTokensResponse>
 */
export async function getAdminTokens(pkh: string): Promise<AdminTokensResponse> {
  const endpoint = `/admin/tokens/${pkh}`;
  return apiGet<AdminTokensResponse>(endpoint);
}

/**
 * Check if a PKH has any admin roles
 *
 * @param pkh - Payment key hash to check
 * @returns Promise<boolean>
 */
export async function checkHasAdminRoles(pkh: string): Promise<boolean> {
  try {
    const response = await getAdminTokens(pkh);
    return response.tokens.length > 0;
  } catch {
    return false;
  }
}

/**
 * Get tokens where PKH has a specific role
 *
 * @param pkh - Payment key hash of the admin
 * @param role - Role to filter by
 * @returns Promise<AdminTokenInfo[]>
 */
export async function getTokensWithRole(
  pkh: string,
  role: AdminRole
): Promise<AdminTokenInfo[]> {
  const response = await getAdminTokens(pkh);
  return response.tokens.filter((token) => token.roles.includes(role));
}

/**
 * Extract payment key hash from a Cardano address
 * Uses MeshSDK's deserializeAddress for proper parsing
 */
export async function extractPkhFromAddress(address: string): Promise<string | null> {
  // Simple validation
  if (!address.startsWith("addr")) {
    return null;
  }

  try {
    const { getPaymentKeyHash } = await import('@/lib/utils/address');
    return getPaymentKeyHash(address);
  } catch (error) {
    console.error("Failed to extract PKH from address:", error);
    return null;
  }
}

/**
 * Fetch UTxOs at an address that contain a specific token
 * Used for burning - allows admin to select which UTxOs to burn from
 *
 * @param address - Cardano address to query
 * @param policyId - Policy ID of the token
 * @param assetName - Hex-encoded asset name
 * @returns Promise<UtxoListResponse>
 */
export async function getUtxosForBurning(
  address: string,
  policyId: string,
  assetName: string
): Promise<UtxoListResponse> {
  const params = new URLSearchParams({ address, policyId, assetName });
  const endpoint = `/admin/utxos?${params.toString()}`;
  return apiGet<UtxoListResponse>(endpoint);
}
