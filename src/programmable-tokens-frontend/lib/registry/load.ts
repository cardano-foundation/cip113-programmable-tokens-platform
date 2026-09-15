/**
 * Loading the registry: three calls, and the one that can silently lie.
 *
 * `/registry/nodes/all` is keyed by a numeric `protocolParamsId` that no other part of this app
 * holds — everything else uses `protocolTxHash`. `/registry/protocols` is the only response
 * carrying both, so it is fetched first purely to translate. A wrong id does not error there; it
 * returns an empty list, which reads exactly like a protocol with nothing registered.
 *
 * Substandard and asset name come from `/token-context/{policyId}`, one call per token. Those are
 * fetched with `allSettled` on purpose: a token whose context is missing must still appear in the
 * registry, because the registry is the on-chain truth and the context row is a database
 * convenience. It shows as unlabelled rather than vanishing.
 */
import { getRegistryProtocols, getRegistryNodes, protocolParamsIdFor } from '../api/registry';
import { getTokenContext } from '../api/protocol';
import { walkRegistry, tokensOf, type WalkResult } from './walk';
import { substandardLabel, type SubstandardLabel } from './substandards';
import type { RegistryNode } from './types';
import type { TokenContext } from '@/types/protocol';

export interface RegistryEntry {
  node: RegistryNode;
  /** Absent when the backend has no row for this token — not an error. */
  context: TokenContext | null;
  label: SubstandardLabel;
  /** True when the walk never reached this node. */
  orphaned: boolean;
}

export interface RegistryView {
  protocolParamsId: number;
  registryNodePolicyId: string;
  walk: WalkResult;
  /** In LIST order — the order `next` gives. */
  entries: RegistryEntry[];
  /** How many token contexts failed to load. Shown, not swallowed. */
  contextFailures: number;
}

export async function loadRegistry(protocolTxHash: string | undefined): Promise<RegistryView> {
  const protocols = await getRegistryProtocols();
  const protocolParamsId = protocolParamsIdFor(protocols, protocolTxHash);
  const protocol = protocols.find((p) => p.protocolParamsId === protocolParamsId)!;

  const nodes = await getRegistryNodes(protocolParamsId);
  const walk = walkRegistry(nodes);

  // Orphans are nodes the walk never reached. They still belong on the page — hiding a node
  // because the chain does not reach it would hide exactly the thing worth seeing.
  const reachable = tokensOf(walk);
  const orphans = nodes.filter(
    (n) => !walk.reached.has(n.key) && n.key !== '' && n.key !== 'ff'.repeat(30),
  );
  const listed = [...reachable, ...orphans];

  const contexts = await Promise.allSettled(listed.map((n) => getTokenContext(n.key)));
  let contextFailures = 0;

  const entries: RegistryEntry[] = listed.map((node, i) => {
    const settled = contexts[i];
    const context = settled.status === 'fulfilled' ? settled.value : null;
    if (settled.status === 'rejected') contextFailures++;
    return {
      node,
      context,
      label: substandardLabel(context?.substandardId),
      orphaned: !walk.reached.has(node.key),
    };
  });

  return {
    protocolParamsId,
    registryNodePolicyId: protocol.registryNodePolicyId,
    walk,
    entries,
    contextFailures,
  };
}

/** Free-text match across the things someone would actually type. */
export function entryMatches(entry: RegistryEntry, query: string): boolean {
  if (!query) return true;
  const q = query.trim().toLowerCase();
  return (
    entry.node.key.toLowerCase().includes(q) ||
    entry.label.label.toLowerCase().includes(q) ||
    (entry.context?.assetName ?? '').toLowerCase().includes(q) ||
    (entry.context?.substandardId ?? '').toLowerCase().includes(q)
  );
}
