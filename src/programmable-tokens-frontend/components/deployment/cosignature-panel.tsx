"use client";

/**
 * Collect a signature from EVERY declared participant, before the protocol exists.
 *
 * ## Why all N, when the script only needs M
 *
 * The threshold governs OPERATIONS — an upgrade needs M of N, forever. This is a
 * different question asked once: does each declared participant actually hold the
 * key they were declared under? A key that never signs at genesis is a key nobody
 * has demonstrated control of, and the datum would record it as an authority
 * anyway. Discovering at the first upgrade that one member's hash was a typo, or
 * belongs to a wallet nobody can open, is discovering it after the protocol is
 * load-bearing.
 *
 * So the rule here is stricter than the rule on chain, deliberately (Giovanni,
 * 2026-09-21). The extra witnesses cost about 100 bytes each and the transaction
 * satisfies its own threshold comfortably; attaching all of them is free and is
 * the proof.
 *
 * ## Why the witness is checked, not counted
 *
 * A vkey is public. A witness carrying a declared member's real vkey beside
 * sixty-four bytes of noise matches every key-hash check and is not a signature.
 * Since the ENTIRE POINT of this panel is proving key control, counting witnesses
 * would prove exactly nothing — each one is verified against this transaction's
 * body hash with Ed25519.
 */

import { useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { transactionHash, verifyWitnessSet } from "@/lib/tx/hash";

export interface CosignatureState {
  witnesses: string[];
  /** Every declared member has supplied a signature that verifies. */
  complete: boolean;
}

export function CosignaturePanel({
  unsignedCbor,
  memberKeyHashes,
  onChange,
}: {
  unsignedCbor: string;
  memberKeyHashes: readonly string[];
  onChange: (state: CosignatureState) => void;
}) {
  const [text, setText] = useState("");
  const [copied, setCopied] = useState<string | null>(null);

  const witnesses = useMemo(
    () => text.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
    [text]
  );

  const txHash = useMemo(() => {
    try {
      return transactionHash(unsignedCbor);
    } catch {
      return null;
    }
  }, [unsignedCbor]);

  /** Per declared member: did a verifying signature arrive for them? */
  const status = useMemo(() => {
    const members = memberKeyHashes.map((h) => h.toLowerCase());
    const signed = new Set<string>();
    const forged = new Set<string>();
    const strangers = new Set<string>();
    const unreadable: string[] = [];

    for (const w of witnesses) {
      let checks;
      try {
        checks = verifyWitnessSet(unsignedCbor, w);
      } catch (e) {
        unreadable.push(e instanceof Error ? e.message : String(e));
        continue;
      }
      for (const c of checks) {
        const h = c.keyHash.toLowerCase();
        if (!members.includes(h)) {
          strangers.add(h || "(no vkey)");
          continue;
        }
        if (c.valid) signed.add(h);
        else forged.add(h);
      }
    }
    return {
      members,
      signed,
      forged,
      strangers: [...strangers],
      unreadable,
      complete: members.length > 0 && members.every((h) => signed.has(h)),
    };
  }, [witnesses, memberKeyHashes, unsignedCbor]);

  useEffect(() => {
    onChange({ witnesses, complete: status.complete });
    // `onChange` is the parent's setState; including it would loop on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [witnesses, status.complete]);

  const copy = async (what: string, value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(what);
      setTimeout(() => setCopied(null), 2000);
    } catch {
      /* selection + manual copy still works */
    }
  };

  return (
    <section className="space-y-3 rounded border border-dark-700 bg-dark-950 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 className="text-sm font-semibold text-white">
          Participant signatures
        </h3>
        <Badge variant={status.complete ? "success" : "warning"} size="sm">
          {status.signed.size} of {status.members.length} verified
        </Badge>
      </div>

      <p className="text-xs text-dark-400">
        Every declared member signs this transaction, not just enough of them to meet the
        threshold. The threshold governs later upgrades; this is the one moment each
        participant proves they hold the key being recorded for them.
      </p>

      {txHash && (
        <div className="rounded border border-primary-500/30 bg-primary-500/5 p-3">
          <div className="flex items-center justify-between gap-2">
            <p className="text-[10px] uppercase tracking-wider text-dark-400">
              Transaction hash — confirm this with each signer
            </p>
            <button
              type="button"
              onClick={() => copy("hash", txHash)}
              className="rounded border border-dark-600 px-2 py-0.5 text-[10px] text-dark-200 hover:text-primary-400"
            >
              {copied === "hash" ? "Copied" : "Copy"}
            </button>
          </div>
          <p className="mt-1 break-all font-mono text-sm text-primary-400">{txHash}</p>
        </div>
      )}

      <div className="space-y-1">
        <div className="flex items-center justify-between gap-2">
          <p className="text-[10px] uppercase tracking-wider text-dark-400">
            Send this to every participant
          </p>
          <button
            type="button"
            onClick={() => copy("tx", unsignedCbor)}
            className="rounded border border-dark-600 px-2 py-0.5 text-[10px] text-dark-200 hover:text-primary-400"
          >
            {copied === "tx" ? "Copied" : "Copy transaction"}
          </button>
        </div>
        <p className="text-[11px] text-dark-500">
          They can sign it at <span className="text-primary-400">/sign</span>, which stays
          reachable while these operator tools are switched off. Do not rebuild the plan after
          sending it: a rebuilt transaction has a different hash and every signature already
          collected stops verifying.
        </p>
      </div>

      <div className="space-y-1">
        <p className="text-[10px] uppercase tracking-wider text-dark-400">
          Witnesses received — one per line
        </p>
        <textarea
          id="cosign-witnesses"
          className="h-24 w-full rounded border border-dark-700 bg-dark-900 px-2 py-1 font-mono text-xs text-white focus:border-primary-500/50 focus:outline-none"
          placeholder="a10081825820…"
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
        />
      </div>

      <ul className="space-y-1">
        {status.members.map((h) => {
          const ok = status.signed.has(h);
          const bad = status.forged.has(h);
          return (
            <li key={h} className="flex flex-wrap items-center gap-2 text-xs">
              <Badge variant={ok ? "success" : bad ? "error" : "default"} size="sm">
                {ok ? "Verified" : bad ? "Does not verify" : "Waiting"}
              </Badge>
              <span className="break-all font-mono text-[11px] text-dark-300">{h}</span>
            </li>
          );
        })}
      </ul>

      {status.forged.size > 0 && (
        <p className="text-xs text-red-300">
          A witness arrived for {status.forged.size} declared member
          {status.forged.size === 1 ? "" : "s"} that does not verify against this transaction.
          They signed a different draft, or it was altered in transit — ask them to sign the
          hash above again.
        </p>
      )}
      {status.strangers.length > 0 && (
        <p className="text-xs text-red-300">
          {status.strangers.length} witness
          {status.strangers.length === 1 ? "" : "es"} came from a key that is not declared here:{" "}
          {status.strangers.map((h) => h.slice(0, 12) + "…").join(", ")}.
        </p>
      )}
      {status.unreadable.length > 0 && (
        <p className="text-xs text-accent-400">
          {status.unreadable.length} pasted value could not be read as a witness set.{" "}
          {status.unreadable[0]}
        </p>
      )}
    </section>
  );
}
