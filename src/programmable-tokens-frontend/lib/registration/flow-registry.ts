/**
 * Flow Registry
 * Central registry for registration flows by module
 */

import type { RegistrationFlow } from '@/types/registration';
import { getCardanoNetwork } from '@/lib/utils/network';
import { isModuleAllowedOnNetwork, isFlowOffered } from '@/lib/registry/available-modules';

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
  // ⛔ THE NETWORK ALLOWLIST IS A FLOOR AND COMES FIRST. A flag cannot turn a module back on for a
  // network that does not offer it — see lib/registry/available-modules.ts.
  //
  // ⚠ THIS PARTICULAR GATE IS NOW DEFENCE IN DEPTH, AND IT IS AN EQUIVALENT MUTANT — removing it
  // leaves every suite green, and that is honest rather than a gap in the tests. Measured: the
  // only live consumers of the `enabled` it computes are `isFlowOffered` and `getAllFlows`, and
  // both ask the allowlist about the network BEFORE reading `enabled`. So there is no observable
  // difference, and contriving a test to pin it would be pinning a value nothing reads.
  // It stays because `enabled` is a public field of a registered flow and the next reader of it
  // should find it already correct — not because a test demands it.
  if (!isModuleAllowedOnNetwork(getCardanoNetwork(), flowId)) {
    return false;
  }

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
 * Get a flow by module ID — or `undefined` if this network does not offer it.
 *
 * ⛔ THE GATE IS HERE, NOT ONLY AT THE PICKER, AND THAT IS THE WHOLE POINT. An adversarial review
 * found the fifth path: `contexts/registration-wizard-context.tsx` resolves a flow by id from
 * SELECT_FLOW, RESTORE_STATE, NEXT_STEP, GO_TO_STEP and `currentFlow`, and none of those passes
 * through `select-module-step`. A wizard state restored from localStorage therefore ran a
 * complete `dummy` or `freeze-and-seize` registration on mainnet.
 *
 * ⚑ AND IT IS STRUCTURALLY THE ONLY THING RESUME CAN DO THERE. That context persists only flows
 * OUTSIDE its `VOLATILE_CIP170_FLOWS` set — which is exactly {dummy, freeze-and-seize}, the two
 * mainnet forbids, while `rwa-token` can never be restored. So on mainnet the resume feature
 * existed solely to restore a forbidden module.
 *
 * Gating the lookup closes every one of those callers in one place, because a flow that cannot be
 * looked up cannot be started, resumed or stepped through.
 */
export function getFlow(moduleId: string): RegistrationFlow | undefined {
  if (!isModuleAllowedOnNetwork(getCardanoNetwork(), moduleId)) return undefined;
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

  // ⛔ THE NETWORK FLOOR, NOT JUST `enabled`. This branch has no caller today — the picker asks
  // for `getAllFlows(true)` and filters with `isFlowOffered` itself — and that is exactly why it
  // was worth hardening: a future caller writing the obvious `getAllFlows()` would otherwise get
  // a list built from `flow.enabled` alone, which is a SECOND copy of the decision. Delegating to
  // the same tested predicate means there is only ever one.
  return allFlows.filter((flow) => isFlowOffered(getCardanoNetwork(), flow, null));
}

/**
 * Get all registered flow IDs
 */
export function getFlowIds(): string[] {
  return Array.from(flowRegistry.keys());
}

/**
 * Check if a flow exists for a module ON THIS NETWORK.
 *
 * Kept consistent with `getFlow`: a caller that asks "does this exist?" and then acts on the
 * answer must not be told yes about something `getFlow` will refuse to return.
 */
export function hasFlow(moduleId: string): boolean {
  if (!isModuleAllowedOnNetwork(getCardanoNetwork(), moduleId)) return false;
  return flowRegistry.has(moduleId);
}

/**
 * Clear all registered flows (useful for testing)
 */
export function clearFlows(): void {
  flowRegistry.clear();
}
