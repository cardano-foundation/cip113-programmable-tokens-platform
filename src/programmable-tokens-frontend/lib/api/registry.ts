/**
 * The CIP-113 registry: the on-chain linked list of programmable tokens.
 *
 * ## Why this file exists at all
 *
 * Nothing in the frontend called `/registry/*` before it. The endpoints have been there since the
 * backend was written and have never had a consumer, so treat their shapes as unverified until
 * something reads them — which this is the first thing to do.
 *
 * ## The id problem, which is the one thing to know before reading further
 *
 * Every other API in this app is keyed by `protocolTxHash`. The registry endpoints are keyed by a
 * NUMERIC `protocolParamsId`, and `ProtocolVersionInfo` — what the version context holds — does
 * not carry one. `/registry/protocols` is the only response in the system that contains both, so
 * it is the bridge: fetch it, match on txHash, keep the id.
 *
 * Getting this wrong does not error. `/registry/nodes/all?protocolParamsId=<wrong>` returns an
 * empty list, which is indistinguishable from a protocol with no tokens registered — so the
 * lookup below fails loudly instead of returning nothing.
 */
import { apiGet } from './client';
import { MAX_NEXT, SENTINEL_KEY, type RegistryNode } from '../registry/types';

// One import site for callers: the domain model lives in lib/registry/types, which is
// dependency-free so the walk can be tested without the HTTP client.
export { MAX_NEXT, SENTINEL_KEY };
export type { RegistryNode };

/** One deployment the indexer knows about, with its registry statistics. */
export interface RegistryProtocol {
  protocolParamsId: number;
  registryNodePolicyId: string;
  progLogicScriptHash: string;
  slot: number;
  txHash: string;
  tokenCount: number;
}

/** The API groups nodes by the protocol they belong to. */
export interface RegistryNodes {
  protocolParams: { registryNodePolicyId: string; progLogicScriptHash: string };
  registryNodes: RegistryNode[];
}

export async function getRegistryProtocols(): Promise<RegistryProtocol[]> {
  return apiGet<RegistryProtocol[]>('/registry/protocols');
}

/**
 * Every node for a protocol, INCLUDING the sentinel.
 *
 * ⛔ `/registry/nodes/all`, never `/registry/tokens`. The latter excludes the sentinel, and without
 * a head there is nothing to walk from — the integrity check then degrades into the sorted table
 * it exists to replace.
 */
export async function getRegistryNodes(protocolParamsId: number): Promise<RegistryNode[]> {
  const grouped = await apiGet<RegistryNodes[]>(
    `/registry/nodes/all?protocolParamsId=${protocolParamsId}`,
  );
  return grouped.flatMap((g) => g.registryNodes ?? []);
}

/**
 * The numeric id for a protocol the rest of the app knows only by transaction hash.
 *
 * Fails loudly rather than returning null: every caller would turn a null into "no tokens", which
 * is exactly what a wrong id already looks like.
 */
export function protocolParamsIdFor(
  protocols: readonly RegistryProtocol[],
  protocolTxHash: string | undefined,
): number {
  if (protocols.length === 0) {
    throw new Error(
      'The indexer reports no protocol deployments. Either nothing has been bootstrapped on this ' +
        'network, or the indexer has not reached the deployment yet.',
    );
  }
  if (!protocolTxHash) {
    // No explicit selection: the lowest id is the earliest deployment the indexer saw.
    return protocols.reduce((a, b) => (a.protocolParamsId <= b.protocolParamsId ? a : b))
      .protocolParamsId;
  }
  const found = protocols.find((p) => p.txHash === protocolTxHash);
  if (!found) {
    throw new Error(
      `The indexer has no protocol with transaction hash ${protocolTxHash}. It knows ` +
        `${protocols.length}: ${protocols.map((p) => p.txHash.slice(0, 12)).join(', ')}. The ` +
        `registry is keyed by a numeric id that only this endpoint reports, so an unmatched hash ` +
        `cannot be turned into a query.`,
    );
  }
  return found.protocolParamsId;
}
