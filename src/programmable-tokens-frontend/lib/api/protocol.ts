/**
 * API functions for fetching protocol blueprints and bootstrap data
 */

import { apiGet } from './client';
import type { ProtocolBlueprint, ProtocolBootstrapParams, ModuleBlueprint, TokenContext } from '@/types/protocol';

/**
 * Get protocol blueprint (validators and compiled code)
 *
 * @returns Protocol blueprint with all validators
 */
export async function getProtocolBlueprint(): Promise<ProtocolBlueprint> {
  return apiGet<ProtocolBlueprint>('/protocol/blueprint');
}

/**
 * Get protocol bootstrap parameters
 *
 * @param protocolTxHash - Optional protocol version tx hash (uses default if not provided)
 * @returns Protocol bootstrap parameters
 */
export async function getProtocolBootstrap(protocolTxHash?: string): Promise<ProtocolBootstrapParams> {
  const endpoint = protocolTxHash
    ? `/protocol/bootstrap?txHash=${protocolTxHash}`
    : '/protocol/bootstrap';

  return apiGet<ProtocolBootstrapParams>(endpoint);
}

/**
 * Get module blueprint (validators for a specific module)
 *
 * @param moduleId - The module identifier (e.g., "dummy", "kyc", "kyc-extended", "rwa-token")
 * @returns Module blueprint with validators
 */
export async function getModuleBlueprint(moduleId: string): Promise<ModuleBlueprint> {
  return apiGet<ModuleBlueprint>(`/modules/${moduleId}`);
}

/**
 * Get token context (moduleId, blacklistNodePolicyId, etc.) for a given policy ID
 *
 * @param policyId - The programmable token policy ID
 * @returns Token context with module info and optional compliance parameters
 */
export async function getTokenContext(policyId: string): Promise<TokenContext> {
  return apiGet<TokenContext>(`/token-context/${policyId}`);
}
