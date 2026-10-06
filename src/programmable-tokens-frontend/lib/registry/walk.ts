/**
 * Walking the registry, which is the only way to find out it is broken.
 *
 * ## Why this is not a sort
 *
 * The obvious way to show a registry is to sort the nodes by key and draw a table. That view can
 * never fail: it sorts whatever rows it was handed, so a node the indexer never wrote is simply
 * absent, and the page looks exactly as healthy as it would if everything were there.
 *
 * The registry carries its own integrity proof and it costs nothing to check. Every node stores
 * `next`, and the on-chain insert rule is `covering.key < newKey < covering.next` (SDK
 * `core/registry.ts:93`) — so the list is strictly ordered BY CONSTRUCTION and following `next`
 * from the sentinel must reach every node exactly once and stop at the terminator.
 *
 * Anything else means the view is wrong, and it is worth saying which way:
 *   · a `next` naming a key that is not present  → that node was never indexed
 *     (but `next == MAX_NEXT` is the TERMINATOR, a value and not a node — see the walk below)
 *   · nodes present but never reached            → the chain skips them, or there are two chains
 *   · `next <= key`                              → impossible on chain; the data is not what it claims
 *   · no terminator                              → the walk ran out before the end of the list
 *
 * Every one of those has the same root cause in practice — an indexer that is behind or missed a
 * block — and every one of them is invisible in a sorted table.
 */
import { MAX_NEXT, SENTINEL_KEY, type RegistryNode } from './types';

export interface WalkProblem {
  kind: 'dangling' | 'unreachable' | 'order' | 'no-terminator' | 'no-sentinel' | 'cycle';
  /** Prose an operator can act on. Names the node. */
  message: string;
  /** The node the problem is anchored at, so the UI can point at it. */
  at: string | null;
}

export interface WalkResult {
  /** Nodes in LIST order — the order `next` gives, not the order they arrived in. */
  ordered: RegistryNode[];
  /** Keys reached from the sentinel. */
  reached: Set<string>;
  problems: WalkProblem[];
  /** The node whose `next` names something absent, if any. */
  danglingFrom: string | null;
  /** True when the walk reached every node and ended at the terminator. */
  intact: boolean;
}

/**
 * Follow `next` from the sentinel.
 *
 * Returns partial results on a broken chain rather than throwing: the operator needs to see the
 * part that IS there, and which node it stops at.
 */
export function walkRegistry(nodes: readonly RegistryNode[]): WalkResult {
  const problems: WalkProblem[] = [];
  const reached = new Set<string>();
  const ordered: RegistryNode[] = [];
  const byKey = new Map(nodes.map((n) => [n.key, n]));

  const head = byKey.get(SENTINEL_KEY);
  if (!head) {
    problems.push({
      kind: 'no-sentinel',
      at: null,
      message:
        'There is no sentinel node (the one with an empty key), so there is no head to walk from. ' +
        'Either the indexer has not reached the protocol genesis, or these nodes came from ' +
        '/registry/tokens, which excludes it.',
    });
    return { ordered: [], reached, problems, danglingFrom: null, intact: false };
  }

  let cur: RegistryNode | undefined = head;
  let danglingFrom: string | null = null;
  let sawTerminator = false;

  // Bounded, because a cycle in the data must not hang the page. The bound is the node count: a
  // correct walk visits each node at most once, so exceeding it IS the cycle.
  const limit = nodes.length + 1;
  for (let step = 0; cur && step <= limit; step++) {
    if (reached.has(cur.key)) {
      problems.push({
        kind: 'cycle',
        at: cur.key,
        message: `The chain returns to ${describe(cur.key)}, so it contains a loop. A registry cannot
          contain one — every insert goes strictly between two existing keys.`.replace(/\s+/g, ' '),
      });
      break;
    }
    reached.add(cur.key);
    ordered.push(cur);

    // A view that MATERIALISES the terminator as a node — we stepped into it deliberately below.
    if (cur.key === MAX_NEXT) {
      sawTerminator = true;
      break;
    }
    if (cur.next <= cur.key) {
      problems.push({
        kind: 'order',
        at: cur.key,
        message: `${describe(cur.key)} points to ${describe(cur.next)}, which does not sort after it.
          On chain an insert must land strictly between two keys, so this ordering cannot have been
          produced by a valid transaction.`.replace(/\s+/g, ' '),
      });
    }

    // ⛔ THE END OF THE LIST IS A VALUE IN `next`, NOT A NODE. `MAX_NEXT` is thirty bytes of 0xff
    // held by the LAST node's `next` field; no node is keyed by it. Confirmed against both live
    // deployments on 2026-10-06 — mainnet returns
    //   "" -> 01c24df7… -> b025efe5… -> ffff…ff        (3 nodes, tokenCount 2)
    // and preprod the same shape with 10 — and neither has a node whose key is the marker.
    //
    // ⚑ THIS WAS A BUG, AND IT FIRED ON EVERY NETWORK, ALWAYS, ON THE LAST NODE. Without this
    // branch the walk looked the marker up like any other key, found nothing, and reported
    // `dangling` — with a message that then asserted something false: "That node exists on chain
    // … so the indexer is behind or missed a block." The chain was intact; the walk's model of the
    // terminator was wrong, and it accused the indexer of losing a block that was never missing.
    //
    // ⚠ AND THE TESTS AGREED WITH THE BUG. Every fixture in test-registry-walk.js fabricated a
    // `node(MAX_NEXT, MAX_NEXT)` tail, so the suite was green against a list shape that does not
    // occur. Test data that shares the implementation's assumption cannot test that assumption.
    if (cur.next === MAX_NEXT) {
      const materialised: RegistryNode | undefined = byKey.get(MAX_NEXT);
      if (!materialised) {
        // The ordinary case: the marker terminates the list and there is nothing to fetch.
        sawTerminator = true;
        break;
      }
      // Tolerated: if some view ever does hand us a node keyed by the marker, step INTO it rather
      // than stopping short — stopping would leave it unvisited and report it as unreachable.
      cur = materialised;
      continue;
    }

    const next: RegistryNode | undefined = byKey.get(cur.next);
    if (!next) {
      danglingFrom = cur.key;
      problems.push({
        kind: 'dangling',
        at: cur.key,
        message: `${describe(cur.key)} points to ${describe(cur.next)}, which is not in the
          indexer's view. That node exists on chain — a node only gets named by another node's
          \`next\` once it has been inserted — so the indexer is behind or missed a block.`.replace(/\s+/g, ' '),
      });
      break;
    }
    cur = next;
  }

  if (!sawTerminator && !danglingFrom && problems.length === 0) {
    problems.push({
      kind: 'no-terminator',
      at: ordered.at(-1)?.key ?? null,
      message:
        'The walk ended without reaching the end-of-list marker. The list is longer than the nodes ' +
        'on hand, so this view is incomplete.',
    });
  }

  const unreachable = nodes.filter((n) => !reached.has(n.key));
  if (unreachable.length > 0) {
    problems.push({
      kind: 'unreachable',
      at: unreachable[0].key,
      message:
        `${unreachable.length} node${unreachable.length > 1 ? 's are' : ' is'} present but never ` +
        `reached by following \`next\` from the head, starting with ${describe(unreachable[0].key)}. ` +
        `A registry has exactly one chain, so these are either orphaned or belong to another protocol.`,
    });
  }

  return { ordered, reached, problems, danglingFrom, intact: problems.length === 0 };
}

/** The tokens of a walk, in list order, with the sentinel and terminator dropped. */
export function tokensOf(result: WalkResult): RegistryNode[] {
  return result.ordered.filter((n) => n.key !== SENTINEL_KEY && n.key !== MAX_NEXT);
}

/** Hooks are credentials; an unset one is `VerificationKey(#"")`, which arrives as empty. */
export function isHookSet(credential: string | null | undefined): boolean {
  return !!credential && credential.length > 0;
}

function describe(key: string): string {
  if (key === SENTINEL_KEY) return 'the list head';
  if (key === MAX_NEXT) return 'the end-of-list marker';
  return `${key.slice(0, 8)}…${key.slice(-6)}`;
}
