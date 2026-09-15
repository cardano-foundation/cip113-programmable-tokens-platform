"use client";

/**
 * The CIP-113 registry.
 *
 * A searchable table is the way in — Giovanni's read of the design proposal: "maybe a tabular
 * list is better ... but not the walkable thing as main experience". The chain view is one click
 * from any row and lands on that row's node.
 *
 * The chain view is not decoration and is not there because linked lists look nice. It is the
 * only view that can show the list is BROKEN: a table sorts whatever rows it was handed, so a
 * node the indexer never wrote is simply absent from it. Both views share the integrity banner;
 * see `lib/registry/walk.ts` for what it can catch and why each failure means what it says.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Link2, RefreshCw, Search } from "lucide-react";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { Cip171ProvenanceBadge } from "@/components/cip171/provenance-badge";
import { CopyButton } from "@/components/ui/copy-button";
import { truncateAddress } from "@/lib/utils/format";
import { loadRegistry, entryMatches, type RegistryEntry, type RegistryView } from "@/lib/registry/load";
import { LABELLED_SUBSTANDARDS, substandardLabel } from "@/lib/registry/substandards";
import { isHookSet } from "@/lib/registry/walk";
import { MAX_NEXT, SENTINEL_KEY } from "@/lib/registry/types";

const HOOKS = [
  { field: "mintingLogicScript", label: "minting" },
  { field: "transferLogicScript", label: "transfer" },
  { field: "thirdPartyTransferLogicScript", label: "third party" },
  { field: "unfrackingLogicScript", label: "unfracking" },
] as const;

const TAG_CLASS: Record<string, string> = {
  stable: "text-primary-400 border-primary-500/60",
  security: "text-purple-300 border-purple-400/60",
  template: "text-dark-300 border-dark-600",
  unknown: "text-dark-400 border-dark-700",
};

export default function RegistryPage() {
  const { selectedVersion, isLoading: versionsLoading } = useProtocolVersion();

  const [view, setView] = useState<RegistryView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [types, setTypes] = useState<Set<string>>(new Set());
  const [chainMode, setChainMode] = useState(false);
  const [focusKey, setFocusKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setView(await loadRegistry(selectedVersion?.txHash));
    } catch (e) {
      // Shown, never swallowed. The wallet list catches its own failure and renders an empty
      // state, which is how "the backend is down" becomes "you have no tokens".
      setError((e as Error).message);
      setView(null);
    } finally {
      setLoading(false);
    }
  }, [selectedVersion?.txHash]);

  useEffect(() => {
    if (!versionsLoading) void load();
  }, [versionsLoading, load]);

  const shown = useMemo(() => {
    if (!view) return [];
    return view.entries.filter(
      (e) =>
        entryMatches(e, query) &&
        (types.size === 0 || (e.context?.substandardId ? types.has(e.context.substandardId) : false)),
    );
  }, [view, query, types]);

  const toggleType = (id: string) =>
    setTypes((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });

  const openInChain = (key: string) => {
    setFocusKey(key);
    setChainMode(true);
    requestAnimationFrame(() => {
      document
        .querySelector(`[data-node-key="${key}"]`)
        ?.scrollIntoView({ behavior: "smooth", inline: "center", block: "nearest" });
    });
  };

  return (
    <main className="mx-auto max-w-7xl space-y-6 px-4 py-8">
      <header className="space-y-2">
        <h1 className="text-2xl font-semibold text-white">Registry</h1>
        <p className="max-w-2xl text-sm text-dark-400">
          Every programmable token registered against this protocol. On chain these are a singly
          linked list ordered by policy id — the chain view follows those pointers, which is the
          only way to see a node the indexer has not caught up with.
        </p>
      </header>

      {view && <IntegrityBanner view={view} />}

      <div className="rounded-lg border border-dark-700 bg-dark-900">
        <div className="flex flex-wrap items-center gap-2 border-b border-dark-800 bg-dark-800/60 p-3">
          <label className="relative flex min-w-[16rem] flex-1 items-center">
            <Search className="pointer-events-none absolute left-2 h-4 w-4 text-dark-500" aria-hidden />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="policy id, asset name or type…"
              aria-label="Search the registry"
              className="w-full rounded border border-dark-600 bg-dark-900 py-1.5 pl-8 pr-2 font-mono text-xs text-white placeholder:text-dark-500 focus:border-primary-500 focus:outline-none focus:ring-1 focus:ring-primary-500/40"
            />
          </label>

          <div className="flex flex-wrap gap-1.5">
            {LABELLED_SUBSTANDARDS.map((id) => {
              const l = substandardLabel(id);
              const on = types.has(id);
              return (
                <button
                  key={id}
                  type="button"
                  onClick={() => toggleType(id)}
                  aria-pressed={on}
                  title={l.blurb}
                  className={`rounded-full border px-2.5 py-1 font-mono text-[0.68rem] uppercase tracking-wider transition-colors ${
                    on ? TAG_CLASS[l.kind] : "border-dark-700 text-dark-400 hover:text-dark-200"
                  }`}
                >
                  {l.label}
                </button>
              );
            })}
          </div>

          <div className="ml-auto flex items-center gap-2">
            <button
              type="button"
              onClick={() => setChainMode((v) => !v)}
              aria-pressed={chainMode}
              className={`rounded border px-2.5 py-1 text-xs transition-colors ${
                chainMode
                  ? "border-primary-500 text-primary-400"
                  : "border-dark-600 text-dark-300 hover:text-white"
              }`}
            >
              {chainMode ? "Table view" : "Chain view"}
            </button>
            <button
              type="button"
              onClick={() => void load()}
              className="rounded border border-dark-600 p-1.5 text-dark-300 hover:text-white"
              aria-label="Reload the registry"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${loading ? "animate-spin" : ""}`} />
            </button>
          </div>
        </div>

        {loading && <p className="p-8 text-center text-sm text-dark-400">Reading the registry…</p>}

        {!loading && error && (
          <div className="flex items-start gap-3 p-6">
            <AlertTriangle className="mt-0.5 h-5 w-5 flex-none text-accent-500" aria-hidden />
            <div className="space-y-2">
              <p className="text-sm text-white">The registry could not be read.</p>
              <p className="max-w-2xl text-xs text-dark-400">{error}</p>
              <button
                type="button"
                onClick={() => void load()}
                className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white"
              >
                Try again
              </button>
            </div>
          </div>
        )}

        {!loading && !error && view && !chainMode && (
          <RegistryTable entries={shown} total={view.entries.length} onOpenInChain={openInChain} />
        )}

        {!loading && !error && view && chainMode && (
          <ChainView view={view} focusKey={focusKey} onFocus={setFocusKey} />
        )}
      </div>

      {view && view.contextFailures > 0 && (
        <p className="text-xs text-accent-300">
          {view.contextFailures} token{view.contextFailures > 1 ? "s" : ""} could not be described —
          the registry node is on chain, but the backend has no record for it. They are listed
          unlabelled rather than hidden.
        </p>
      )}
    </main>
  );
}

function IntegrityBanner({ view }: { view: RegistryView }) {
  const { walk } = view;
  if (walk.intact) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-primary-600/40 bg-primary-950/20 px-3 py-2 text-xs text-primary-300">
        <Check className="h-4 w-4 flex-none" aria-hidden />
        <span>
          Chain intact — every node is reachable from the head and the list ends where it should.
        </span>
      </div>
    );
  }
  return (
    <div className="space-y-1.5 rounded-lg border border-red-800 bg-red-950/25 p-3 text-xs text-red-200">
      <p className="flex items-center gap-2 font-medium">
        <AlertTriangle className="h-4 w-4 flex-none" aria-hidden />
        This view of the registry is incomplete.
      </p>
      {walk.problems.map((p, i) => (
        <p key={i} className="pl-6">{p.message}</p>
      ))}
    </div>
  );
}

function RegistryTable({
  entries,
  total,
  onOpenInChain,
}: {
  entries: RegistryEntry[];
  total: number;
  onOpenInChain: (key: string) => void;
}) {
  const [open, setOpen] = useState<string | null>(null);

  if (total === 0) {
    return (
      <p className="p-8 text-center text-sm text-dark-400">
        No tokens are registered against this protocol yet.
      </p>
    );
  }
  if (entries.length === 0) {
    return <p className="p-8 text-center text-sm text-dark-400">Nothing matches that search.</p>;
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[46rem] text-sm">
        <thead>
          <tr className="bg-dark-800/60 text-left font-mono text-[0.66rem] uppercase tracking-wider text-dark-400">
            <th className="px-3 py-2 font-medium">Token</th>
            <th className="px-3 py-2 font-medium">Type</th>
            <th className="px-3 py-2 font-medium">Standards</th>
            <th className="px-3 py-2 font-medium">Hooks</th>
            <th className="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <RegistryRow
              key={e.node.key}
              entry={e}
              expanded={open === e.node.key}
              onToggle={() => setOpen(open === e.node.key ? null : e.node.key)}
              onOpenInChain={() => onOpenInChain(e.node.key)}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function RegistryRow({
  entry,
  expanded,
  onToggle,
  onOpenInChain,
}: {
  entry: RegistryEntry;
  expanded: boolean;
  onToggle: () => void;
  onOpenInChain: () => void;
}) {
  const { node, context, label, orphaned } = entry;
  return (
    <>
      <tr
        onClick={onToggle}
        className={`cursor-pointer border-b border-dark-800 hover:bg-dark-800/50 ${
          orphaned ? "bg-red-950/10" : ""
        }`}
      >
        <td className="px-3 py-2">
          <div className="flex flex-col">
            <span className="font-medium text-white">
              {context?.assetName ? decodeName(context.assetName) : "Unnamed token"}
            </span>
            <span className="font-mono text-[0.72rem] text-dark-400">
              {truncateAddress(node.key, 10, 8)}
            </span>
          </div>
        </td>
        <td className="px-3 py-2">
          <span
            title={label.blurb}
            className={`inline-block rounded border px-1.5 py-0.5 font-mono text-[0.64rem] uppercase tracking-wider ${TAG_CLASS[label.kind]}`}
          >
            {label.label}
          </span>
          {orphaned && (
            <span className="ml-1.5 inline-block rounded border border-red-700 px-1.5 py-0.5 font-mono text-[0.62rem] uppercase text-red-300">
              unreachable
            </span>
          )}
        </td>
        <td className="px-3 py-2">
          <Cip171ProvenanceBadge policyId={node.key} />
        </td>
        <td className="px-3 py-2">
          <div className="flex gap-1">
            {HOOKS.map((h) => (
              <span
                key={h.field}
                title={`${h.label}: ${isHookSet(node[h.field]) ? "set" : "unset"}`}
                className={`rounded px-1.5 py-0.5 font-mono text-[0.6rem] ${
                  isHookSet(node[h.field])
                    ? "border border-highlight-600 text-highlight-400"
                    : "border border-dark-700 text-dark-500"
                }`}
              >
                {h.label.slice(0, 5)}
              </span>
            ))}
          </div>
        </td>
        <td className="px-3 py-2 text-right">
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onOpenInChain();
            }}
            className="inline-flex items-center gap-1 rounded border border-dark-600 px-2 py-1 font-mono text-[0.66rem] uppercase tracking-wide text-dark-300 hover:border-primary-500 hover:text-primary-400"
          >
            <Link2 className="h-3 w-3" aria-hidden />
            View in list
          </button>
        </td>
      </tr>
      {expanded && (
        <tr className="border-b border-dark-800 bg-dark-950/60">
          <td colSpan={5} className="px-3 py-3">
            <NodeDetail entry={entry} />
          </td>
        </tr>
      )}
    </>
  );
}

function NodeDetail({ entry }: { entry: RegistryEntry }) {
  const { node, label } = entry;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <div className="space-y-1.5">
        <p className="font-mono text-[0.66rem] uppercase tracking-wider text-dark-400">
          Registry node
        </p>
        <Field name="key" value={node.key} copy />
        <Field
          name="next"
          value={node.next === MAX_NEXT ? `${MAX_NEXT} — end of list` : node.next}
          unset={node.next === MAX_NEXT}
        />
        {HOOKS.map((h) => (
          <Field
            key={h.field}
            name={h.label.replace(" ", "_")}
            value={isHookSet(node[h.field]) ? node[h.field] : 'VerificationKey(#"") — unset'}
            unset={!isHookSet(node[h.field])}
            copy={isHookSet(node[h.field])}
          />
        ))}
        <Field
          name="global_state"
          value={node.globalStatePolicyId || '#"" — none'}
          unset={!node.globalStatePolicyId}
        />
      </div>

      <div className="space-y-3">
        <div className="rounded border border-dark-700 bg-dark-900 p-3">
          <p className="font-mono text-[0.66rem] uppercase tracking-wider text-dark-400">Type</p>
          <p className="mt-1 text-sm text-white">{label.label}</p>
          <p className="mt-1 text-xs text-dark-400">{label.blurb}</p>
        </div>
        <div className="rounded border border-dark-700 bg-dark-900 p-3">
          <p className="font-mono text-[0.66rem] uppercase tracking-wider text-dark-400">
            CIP-68 metadata
          </p>
          <p className="mt-1 text-xs text-dark-400">
            Name, ticker, logo and decimals live in the reference token&apos;s datum. Reading it
            back needs a decoder the backend does not have yet — tracked as T-052, not missing data.
          </p>
        </div>
      </div>
    </div>
  );
}

function Field({
  name,
  value,
  unset,
  copy,
}: {
  name: string;
  value: string;
  unset?: boolean;
  copy?: boolean;
}) {
  return (
    <div className="flex items-start gap-2">
      <span className="w-28 flex-none font-mono text-[0.68rem] text-dark-500">{name}</span>
      <span
        className={`flex-1 break-all font-mono text-[0.72rem] ${
          unset ? "italic text-dark-500" : "text-dark-200"
        }`}
      >
        {value}
      </span>
      {copy && <CopyButton value={value} size="sm" />}
    </div>
  );
}

function ChainView({
  view,
  focusKey,
  onFocus,
}: {
  view: RegistryView;
  focusKey: string | null;
  onFocus: (key: string) => void;
}) {
  // The walk's order, plus any node it never reached — an unreachable node belongs on screen,
  // since it is exactly what this view exists to surface.
  const chain = view.walk.ordered;
  const orphans = view.entries.filter((e) => e.orphaned).map((e) => e.node);

  return (
    <div className="space-y-4 p-4">
      <div className="overflow-x-auto pb-2">
        <div className="flex min-w-min items-stretch">
          {chain.map((n, i) => (
            <div key={n.key} className="flex items-stretch">
              <button
                type="button"
                data-node-key={n.key}
                onClick={() => onFocus(n.key)}
                className={`flex w-56 flex-col gap-1.5 rounded-lg border p-3 text-left transition-colors ${
                  focusKey === n.key
                    ? "border-primary-500 ring-1 ring-primary-500"
                    : "border-dark-700 hover:border-dark-500"
                } ${n.key === SENTINEL_KEY || n.key === MAX_NEXT ? "w-40 bg-dark-900" : "bg-dark-800"}`}
              >
                <span className="font-mono text-[0.6rem] uppercase tracking-wider text-dark-500">
                  {n.key === SENTINEL_KEY ? 'key = ""' : n.key === MAX_NEXT ? "ff × 30" : "policy id"}
                </span>
                <span className="text-sm font-medium text-white">
                  {n.key === SENTINEL_KEY
                    ? "List head"
                    : n.key === MAX_NEXT
                      ? "List end"
                      : nameFor(view, n.key)}
                </span>
                {n.key !== SENTINEL_KEY && n.key !== MAX_NEXT && (
                  <span className="break-all font-mono text-[0.7rem] text-dark-400">
                    {truncateAddress(n.key, 8, 6)}
                  </span>
                )}
              </button>
              {i < chain.length - 1 && (
                <div className="flex w-10 items-center justify-center text-dark-500" aria-hidden>
                  <svg width="30" height="16" viewBox="0 0 30 16">
                    <path d="M0 8 H22" stroke="currentColor" strokeWidth="2" fill="none" />
                    <path
                      d="M18 3 L24 8 L18 13"
                      stroke="currentColor"
                      strokeWidth="2"
                      fill="none"
                      strokeLinejoin="round"
                    />
                  </svg>
                </div>
              )}
            </div>
          ))}

          {view.walk.danglingFrom && (
            <div className="flex items-center gap-2 pl-2 text-red-400">
              <svg width="30" height="16" viewBox="0 0 30 16" aria-label="points to a node that is not here">
                <path d="M0 8 H10" stroke="currentColor" strokeWidth="2" fill="none" />
                <path d="M14 3 L20 13 M20 3 L14 13" stroke="currentColor" strokeWidth="2" fill="none" />
              </svg>
              <span className="font-mono text-[0.7rem]">not indexed</span>
            </div>
          )}
        </div>
      </div>

      {orphans.length > 0 && (
        <div className="space-y-2">
          <p className="font-mono text-[0.66rem] uppercase tracking-wider text-red-300">
            Present, but not reachable from the head
          </p>
          <div className="flex flex-wrap gap-2">
            {orphans.map((n) => (
              <span
                key={n.key}
                className="rounded border border-dashed border-red-700 bg-dark-800 px-2 py-1 font-mono text-[0.7rem] text-red-200"
              >
                {truncateAddress(n.key, 8, 6)}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function nameFor(view: RegistryView, key: string): string {
  const e = view.entries.find((x) => x.node.key === key);
  return e?.context?.assetName ? decodeName(e.context.assetName) : "Unnamed token";
}

/** Asset names are hex at every API boundary; show something readable when it decodes. */
function decodeName(assetNameHex: string): string {
  try {
    const bytes = assetNameHex.match(/.{1,2}/g)?.map((b) => parseInt(b, 16)) ?? [];
    const text = new TextDecoder().decode(new Uint8Array(bytes)).replace(/[^\x20-\x7e]/g, "");
    return text.trim() || "Unnamed token";
  } catch {
    return "Unnamed token";
  }
}
