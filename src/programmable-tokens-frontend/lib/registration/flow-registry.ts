/**
 * Flow Registry
 * Central registry for registration flows by module
 */

import type { RegistrationFlow } from '@/types/registration';

// ============================================================================
// Flow Enablement Utilities
// ============================================================================

/**
 * Check if a flow is enabled via environment variables
 * Priority: runtime env var > .env file
 *
 * Environment variable format: NEXT_PUBLIC_FLOW_{FLOW_ID}_ENABLED
 * Examples:
 *   NEXT_PUBLIC_FLOW_DUMMY_ENABLED=true
 *   NEXT_PUBLIC_FLOW_FREEZE_AND_SEIZE_ENABLED=false
 *
 * @param flowId - The flow identifier (e.g., 'dummy', 'freeze-and-seize')
 * @param defaultValue - Default value if env var is not set
 * @returns boolean indicating if the flow is enabled
 */
/**
 * Get environment variable value with static references
 * Next.js requires static references to process.env for build-time replacement
 */
function getFlowEnvVar(flowId: string): string | undefined {
  // Must use static references for Next.js/webpack to replace at build time
  switch (flowId) {
    case 'dummy':
      return process.env.NEXT_PUBLIC_FLOW_DUMMY_ENABLED;
    case 'freeze-and-seize':
      return process.env.NEXT_PUBLIC_FLOW_FREEZE_AND_SEIZE_ENABLED;
    case 'kyc':
      return process.env.NEXT_PUBLIC_FLOW_KYC_ENABLED;
    case 'kyc-extended':
      return process.env.NEXT_PUBLIC_FLOW_KYC_EXTENDED_ENABLED;
    case 'rwa-token':
      return process.env.NEXT_PUBLIC_FLOW_SECURITY_TOKEN_ENABLED;
    default:
      return undefined;
  }
}

export function isFlowEnabled(flowId: string, defaultValue: boolean = true): boolean {
  const envValue = getFlowEnvVar(flowId);

  console.log(`[Flow Registry] Checking ${flowId}: envValue="${envValue}", default=${defaultValue}`);

  // If env var is not set, return default
  if (envValue === undefined) {
    return defaultValue;
  }

  // Parse boolean from string (handles 'true', 'false', '1', '0', 'yes', 'no')
  const result = envValue.toLowerCase() === 'true' || envValue === '1' || envValue.toLowerCase() === 'yes';
  console.log(`[Flow Registry] ${flowId} enabled: ${result}`);
  return result;
}

// ============================================================================
// Registry
// ============================================================================

const flowRegistry = new Map<string, RegistrationFlow>();

/**
 * Register a flow for a module
 */
export function registerFlow(flow: RegistrationFlow): void {
  flowRegistry.set(flow.id, flow);
}

/**
 * Get a flow by module ID
 */
export function getFlow(moduleId: string): RegistrationFlow | undefined {
  return flowRegistry.get(moduleId);
}

/**
 * Get all registered flows
 * @param includeDisabled - If true, returns all flows including disabled ones
 * @returns Array of flows (by default, only enabled flows)
 */
export function getAllFlows(includeDisabled: boolean = false): RegistrationFlow[] {
  const allFlows = Array.from(flowRegistry.values());

  if (includeDisabled) {
    return allFlows;
  }

  // Filter to only enabled flows
  return allFlows.filter(flow => flow.enabled);
}

/**
 * Get all registered flow IDs
 */
export function getFlowIds(): string[] {
  return Array.from(flowRegistry.keys());
}

/**
 * Check if a flow exists for a module
 */
export function hasFlow(moduleId: string): boolean {
  return flowRegistry.has(moduleId);
}

/**
 * Clear all registered flows (useful for testing)
 */
export function clearFlows(): void {
  flowRegistry.clear();
}
