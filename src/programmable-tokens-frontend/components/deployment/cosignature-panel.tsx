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
import { asWitnessSetHex, transactionHash, verifyWitnessSet } from "@/lib/tx/hash";
import { participantColour, confusablePairs } from "@/lib/deployment/participant-colour";

export interface CosignatureState {
  witnesses: string[];
  /** Every declared member has supplied a signature that verifies. */
  complete: boolean;
}

export function CosignaturePanel({
  unsignedCbor,
  memberKeyHashes,
  onChange,
  signSelf,
}: {
  unsignedCbor: string;
  memberKeyHashes: readonly string[];
  onChange: (state: CosignatureState) => void;
  /**
   * Sign with the CONNECTED wallet and return whatever it hands back.
   *
   * ⛔ THE WITNESS IS STILL VERIFIED. This is a shortcut past the copy-paste round trip, NOT past
   * the check: the result is appended to the same list every pasted witness goes through, so the
   * connected wallet proves key control exactly as a remote participant does. Skipping the proof
   * because a wallet is connected would replace a verified signature with the wallet's own claim
   * about which key it holds — and these are the keys that can upgrade the protocol forever.
   *
   * Optional: without it the panel behaves as before, which is what a participant on /sign gets.
   */
  signSelf?: () => Promise<string>;
}) {
  const [text, setText] = useState("");
  const [copied, setCopied] = useState<string | null>(null);
  /** The hand-off: what was pushed, and when. `null` until the driver pushes. */
  const [pushed, setPushed] = useState<{ id: string; at: number; alreadyHeld: boolean } | null>(null);
  const [pushing, setPushing] = useState(false);
  const [pushError, setPushError] = useState<string | null>(null);
  const [signingSelf, setSigningSelf] = useState(false);
  const [selfError, setSelfError] = useState<string | null>(null);

  const witnesses = useMemo(
    () =>
      text
        .split(/[\s,]+/)
        .map((s) => s.trim())
        .filter(Boolean)
        // ⛔ ACCEPT A WHOLE TRANSACTION TOO. A participant whose wallet returned one, or who
        // copied from a wallet's UI rather than from /sign, is not making a mistake worth
        // refusing — the witness set is element 1 and we already have the bytes. Anything
        // genuinely unusable is left as-is so the per-witness check below names it.
        .map((v) => {
          try {
            return asWitnessSetHex(v).hex;
          } catch {
            return v;
          }
        }),
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

  const confusable = useMemo(
    () => confusablePairs(memberKeyHashes.map((h) => h.toLowerCase())),
    [memberKeyHashes],
  );

  useEffect(() => {
    onChange({ witnesses, complete: status.complete });
    // `onChange` is the parent's setState; including it would loop on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [witnesses, status.complete]);

  const addOwnSignature = async () => {
    if (!signSelf) return;
    setSigningSelf(true);
    setSelfError(null);
    try {
      const returned = await signSelf();
      // Tolerate a wallet that hands back a whole transaction — see asWitnessSetHex.
      const { hex } = asWitnessSetHex(returned);
      setText((current) => {
        const already = current
          .split(/[\s,]+/)
          .map((t) => t.trim().toLowerCase())
          .filter(Boolean);
        // Signing twice is harmless but confusing; it would show as one member, two witnesses.
        if (already.includes(hex.toLowerCase())) return current;
        return current.trim().length === 0 ? hex : `${current.trim()}\n${hex}`;
      });
    } catch (e) {
      setSelfError((e as Error).message);
    } finally {
      setSigningSelf(false);
    }
  };

  /**
   * Hand the transaction to the relay so participants fetch it instead of pasting 15 KB.
   *
   * ⛔ IDEMPOTENT BY CONSTRUCTION. The relay keys entries by the transaction id it derives from
   * the bytes, so pushing the same transaction twice returns the same id and displaces nothing.
   * That is why this button stays enabled: if a participant reports "not found" because the
   * service restarted, pressing it again is always the right move and can never fork the ceremony.
   */
  const push = async () => {
    setPushing(true);
    setPushError(null);
    try {
      const res = await fetch("/api/deployment/relay", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ tx: unsignedCbor }),
      });
      const body = (await res.json()) as { id?: string; alreadyHeld?: boolean; error?: string };
      if (!res.ok || !body.id) throw new Error(body.error ?? `relay returned ${res.status}`);
      // ⚑ CHECK THE RELAY AGREES WITH US. The id it returns must be the hash this panel already
      // displays; if it is not, the relay is deriving keys differently and participants would be
      // told to fetch under an id that is not the one being compared on the call.
      if (txHash && body.id !== txHash) {
        throw new Error(
          `The relay stored this under ${body.id}, but this transaction's hash is ${txHash}. ` +
            "Not circulating a mismatched id — use the paste path.",
        );
      }
      setPushed({ id: body.id, at: Date.now(), alreadyHeld: body.alreadyHeld === true });
    } catch (e) {
      setPushError((e as Error).message);
    } finally {
      setPushing(false);
    }
  };

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
        Every declared member signs, not just enough to meet the threshold — this is where each
        proves they hold the key being recorded for them.
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
          They sign at <span className="text-primary-400">/sign</span>. Don&apos;t rebuild the
          plan after sending: a rebuilt transaction has a new hash and every signature already
          collected stops verifying.
        </p>

        {/*
          The alternative to pasting 15 KB into a chat client that will wrap or truncate it. What
          travels in the chat is the id; the bytes travel over HTTP.
        */}
        <div className="flex flex-wrap items-center gap-2 pt-1">
          <button
            type="button"
            onClick={push}
            disabled={pushing}
            className="rounded border border-dark-600 bg-dark-800 px-3 py-1.5 text-xs text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400 disabled:opacity-40"
          >
            {pushing ? "Pushing…" : pushed ? "Push again" : "Push to server"}
          </button>
          {pushed && (
            <button
              type="button"
              onClick={() => copy("link", `${window.location.origin}/sign?tx=${pushed.id}`)}
              className="rounded border border-dark-600 px-2 py-1 text-[10px] text-dark-200 hover:text-primary-400"
            >
              {copied === "link" ? "Copied" : "Copy /sign link"}
            </button>
          )}
        </div>
        {pushError && <p className="text-xs text-red-400">{pushError}</p>}
        {pushed && (
          <p className="text-[11px] text-dark-400">
            Held since {new Date(pushed.at).toLocaleTimeString()}
            {pushed.alreadyHeld && " (already held — nothing was replaced)"}. Participants fetch it
            on <span className="text-primary-400">/sign</span> with the hash above.{" "}
            <strong className="text-dark-300">
              Read that hash out on the call anyway.
            </strong>{" "}
            It is how they find the transaction, not proof of which one they got — a link carries
            both halves, so only a channel the transaction did not arrive on can confirm it. If
            anyone reports it missing, push again; the id is derived from the bytes, so a second
            push replaces nothing.
          </p>
        )}
      </div>

      <div className="space-y-1">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-[10px] uppercase tracking-wider text-dark-400">
            Witnesses received — one per line
          </p>
          {/*
            A shortcut past the copy-paste round trip for whoever is driving, NOT past the check:
            the witness lands in the same list as every other and is verified identically.
          */}
          {signSelf && (
            <button
              type="button"
              onClick={addOwnSignature}
              disabled={signingSelf}
              className="rounded border border-dark-600 bg-dark-800 px-3 py-1.5 text-xs text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400 disabled:opacity-40"
            >
              {signingSelf ? "Waiting for your wallet…" : "Sign as me"}
            </button>
          )}
        </div>
        <textarea
          id="cosign-witnesses"
          className="h-24 w-full rounded border border-dark-700 bg-dark-900 px-2 py-1 font-mono text-xs text-white focus:border-primary-500/50 focus:outline-none"
          placeholder="a10081825820…"
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
        />
        {selfError && <p className="text-xs text-red-400">{selfError}</p>}
      </div>

      <ul className="space-y-1">
        {status.members.map((h) => {
          const ok = status.signed.has(h);
          const bad = status.forged.has(h);
          return (
            <li key={h} className="flex flex-wrap items-center gap-2 text-xs">
              <ParticipantChip keyHash={h} />
              <Badge variant={ok ? "success" : bad ? "error" : "default"} size="sm">
                {ok ? "Verified" : bad ? "Does not verify" : "Waiting"}
              </Badge>
              <span className="break-all font-mono text-[11px] text-dark-300">{h}</span>
            </li>
          );
        })}
      </ul>

      {confusable.length > 0 && (
        <p className="text-[11px] text-dark-400">
          {confusable.length === 1 ? "Two participants have" : `${confusable.length} pairs have`}{" "}
          similar colours. Read the hashes for those, not the chips.
        </p>
      )}

      {status.forged.size > 0 && (
        <p className="text-xs text-red-300">
          A witness for {status.forged.size} declared member
          {status.forged.size === 1 ? "" : "s"} does not verify — they signed a different draft.
          Ask them to sign the hash above.
        </p>
      )}
      {status.strangers.length > 0 && (
        <p className="text-xs text-red-300">
          {status.strangers.length} witness
          {status.strangers.length === 1 ? "" : "es"} came from undeclared keys:{" "}
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

/**
 * One participant's colour, with the short hash beside it.
 *
 * The chip and the text ship together everywhere, deliberately: the colour makes the common
 * case ("who is missing?") instant, and the hash makes every case correct — for a colour-blind
 * reader, a greyscale screenshot, or two hues that happen to land near each other.
 */
function ParticipantChip({ keyHash }: { keyHash: string }) {
  const colour = participantColour(keyHash);
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        aria-hidden
        className="h-2.5 w-2.5 shrink-0 rounded-full border"
        style={colour.swatch}
      />
      <span className="font-mono text-[10px] text-dark-400">{colour.shortHash}</span>
    </span>
  );
}
