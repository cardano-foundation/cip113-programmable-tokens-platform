/**
 * Runtime Configuration API
 *
 * This endpoint reads environment variables at runtime (server-side),
 * making it work with Kubernetes ConfigMaps and deployment env vars
 * without requiring a rebuild.
 */

import { NextResponse } from 'next/server';
import { getCardanoNetwork } from '@/lib/utils/network';
import { isModuleAllowedOnNetwork } from '@/lib/registry/available-modules';

export const dynamic = 'force-dynamic'; // Disable caching

export async function GET() {
  const network = getCardanoNetwork();

  // Read flow enablement from server-side env vars
  // These are NOT replaced at build time, so they work with Kubernetes
  const flows = {
    dummy: getEnvBoolean('FLOW_DUMMY_ENABLED', true),
    'freeze-and-seize': getEnvBoolean('FLOW_FREEZE_AND_SEIZE_ENABLED', true),
    'rwa-token': getEnvBoolean('FLOW_SECURITY_TOKEN_ENABLED', true),
    // Disabled by default, matching the backend's modules.disabled. Set
    // FLOW_KYC_ENABLED / FLOW_KYC_EXTENDED_ENABLED to re-enable without a rebuild.
    kyc: getEnvBoolean('FLOW_KYC_ENABLED', false),
    'kyc-extended': getEnvBoolean('FLOW_KYC_EXTENDED_ENABLED', false),
  };

  // ⛔ AND THEN THE NETWORK ALLOWLIST, which no flag can override. On mainnet this answers `false`
  // for every module but the CMTA/eWpG security standard, whatever the ConfigMap says — a flag
  // that was never set, or set to `true` by a copied testnet environment, must not put a template
  // module in front of someone on mainnet. See lib/registry/available-modules.ts.
  const config = {
    network,
    flows: Object.fromEntries(
      Object.entries(flows).map(([id, enabled]) => [
        id,
        enabled && isModuleAllowedOnNetwork(network, id),
      ])
    ),
  };

  return NextResponse.json(config);
}

/**
 * Parse boolean from environment variable
 * Supports: 'true', 'false', '1', '0', 'yes', 'no'
 */
function getEnvBoolean(key: string, defaultValue: boolean): boolean {
  const value = process.env[key];

  if (value === undefined || value === '') {
    return defaultValue;
  }

  const normalized = value.toLowerCase().trim();
  return normalized === 'true' || normalized === '1' || normalized === 'yes';
}
