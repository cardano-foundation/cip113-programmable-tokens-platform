"use client";

import { useEffect, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { getTokenContext } from "@/lib/api/protocol";
import {
  lookupByScriptHash,
  isVerified,
  parseSourceUrl,
  registrySiteUrl,
  type Cip171Record,
} from "@/lib/cip171/registry";

/**
 * Shows that a token's scripts have VERIFIED CIP-171 provenance, and what that record says.
 *
 * <p>Renders NOTHING until a verified record is found: no badge, no placeholder, no spinner.
 * Most tokens have no published record, and a row that reserves space for an absent badge
 * would make the common case look like a failure. The consequence is deliberate — the badge
 * appears a moment after the row does.
 *
 * <p>Two hops, because neither endpoint alone is enough: the wallet knows a token's policy id,
 * the backend maps that to the transfer-logic script hash, and the registry maps that hash to
 * a source. Both are cached, so a repeated token costs nothing.
 */
export function Cip171ProvenanceBadge({ policyId }: { policyId: string }) {
  const [record, setRecord] = useState<Cip171Record | null>(null);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const context = await getTokenContext(policyId);
        const hash = context?.transferLogicScript;
        if (!hash) return;
        const found = await lookupByScriptHash(hash);
        if (!cancelled && isVerified(found)) setRecord(found);
      } catch {
        // A provenance badge is not worth surfacing an error over.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [policyId]);

  if (!record) return null;

  const { organization, repository } = parseSourceUrl(record.sourceUrl);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        title="Source provenance published under CIP-171 and verified by recompilation"
        className="cursor-pointer"
      >
        <Badge variant="success" size="sm">
          CIP-171 {open ? "▾" : "▸"}
        </Badge>
      </button>

      {open && (
        <div className="mt-2 w-full rounded border border-dark-700 bg-dark-950 p-3 text-xs">
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1">
            <Row label="Organization" value={organization ?? "—"} />
            <Row label="Repository" value={repository ?? record.sourceUrl} />
            <Row label="Path" value={record.sourcePath || "(repository root)"} />
            <Row label="Commit" value={record.commitHash} mono />
            <Row label="Compiler" value={`${record.compilerType} ${record.compilerVersion}`} />
            <Row label="Status" value={record.status} />
          </dl>
          <a
            href={registrySiteUrl()}
            target="_blank"
            rel="noopener noreferrer"
            className="mt-2 inline-block text-accent-400 hover:underline"
          >
            View on uplc.link ↗
          </a>
        </div>
      )}
    </>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <>
      <dt className="text-dark-400">{label}</dt>
      <dd className={`text-white break-all ${mono ? "font-mono" : ""}`}>{value}</dd>
    </>
  );
}
