/**
 * Runtime Configuration API
 *
 * This endpoint reads environment variables at runtime (server-side),
 * making it work with Kubernetes ConfigMaps and deployment env vars
 * without requiring a rebuild.
 */

import { NextResponse } from 'next/server';
import { getCardanoNetwork } from '@/lib/utils/network';
import { gateFlowFlags } from '@/lib/registry/available-modules';

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
  // for every module but "RWA Token (German & Swiss profiles)", whatever the ConfigMap says — a
  // flag that was never set, or set to `true` by a copied testnet environment, must not put a
  // template module in front of someone on mainnet.
  //
  // ⚑ THE INTERSECTION ITSELF LIVES IN lib/registry/available-modules.ts AND IS TESTED THERE. It
  // used to be inline here, defended only by a regex over this file's text — and an adversarial
  // review broke it by moving the gated expression into an unused field and returning the ungated
  // one, with the suite green and this endpoint serving dummy:true on mainnet.
  const config = { network, flows: gateFlowFlags(network, flows) };

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
