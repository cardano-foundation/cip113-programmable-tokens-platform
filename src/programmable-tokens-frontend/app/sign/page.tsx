"use client";

/**
 * Sign a transaction someone sent you, and hand back the witness.
 *
 * ## Why this is not under /ops
 *
 * /ops is gated off between deployments (see middleware.ts), and co-signers are
 * precisely the people NOT running the deployment — gating this would mean the
 * tools are available to everyone except the participants. It is safe to leave
 * open because it grants nothing: it signs what you paste, with your own wallet,
 * and returns a witness that is useful for exactly one transaction.
 *
 * ## Why the hash is the biggest thing on the page
 *
 * A witness commits to the blake2b-256 hash of the transaction body. That hash
 * is therefore the entire question "are we all signing the same thing", and it
 * is the value participants should compare over a channel the transaction did
 * NOT arrive on. Everything else here is context; the hash is the check.
 *
 * ## Why fetching is a BUTTON and never happens on render
 *
 * This page must issue no request when it loads, so a participant on a laptop with no route to
 * the server still gets a working page — signing is local. Fetching from the hand-off is
 * therefore something the participant DOES, never something the page does on mount: `?tx=` only
 * prefills the field. And the id is a LOOKUP KEY, not proof: the server derived it by hashing
 * the bytes, so recomputing it here can only catch a broken server, never a substitution. The
 * hash still has to be confirmed on the call, which is what section 2 is for.
 *
 * ## Why "is your key required" is answered mechanically
 *
 * The transaction declares its required signers, and the wallet knows which credentials it
 * holds, so the page can answer the question instead of printing a 56-character hash for a human
 * to compare — which is the failure mode it would exist to prevent. It checks EVERY address the
 * wallet reports, not just the change address: a participant who sent the driver an address from
 * their wallet's Receive tab sent an external one, whose payment key hash differs from the change
 * address's in any HD wallet.
 *
 * ## Why the witness is verified before it leaves
 *
 * A wallet can sign with a key that is not the one expected, or decline to sign
 * and return an empty set. Both look like success to a page that just forwards
 * whatever came back, and both are discovered by the assembler much later, after
 * everyone else has done their part. Verifying here — the real Ed25519 check
 * against this body hash — turns that into an immediate, local answer.
 */

import { useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { useWallet } from "@/hooks/use-wallet";
import { getCardanoNetwork } from "@/lib/utils/network";
import { transactionHash, verifyWitnessSet, type VerifiedWitness } from "@/lib/tx/hash";
import { summariseTransaction, type TxSummary } from "@/lib/tx/summary";
import { asWitnessSetHex } from "@/lib/tx/hash";
import { paymentCredentialHash } from "@easy1staking/cip113-sdk-ts";

export default function SignPage() {
  const network = getCardanoNetwork();
  const { connected, wallet, name } = useWallet();

  const [txHex, setTxHex] = useState("");
  const [witness, setWitness] = useState<string | null>(null);
  /** Noted when the wallet returned more than a witness set, for wallet-compatibility notes. */
  const [note, setNote] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  /**
   * Prefilled from `?tx=` so a driver's link saves typing 64 characters — read once, from
   * `window`, deliberately: `useSearchParams` would pull this page into Suspense for no gain,
   * and nothing here should fetch on mount.
   */
  const [relayId, setRelayId] = useState(() => {
    if (typeof window === "undefined") return "";
    return new URLSearchParams(window.location.search).get("tx")?.trim().toLowerCase() ?? "";
  });
  const [fetching, setFetching] = useState(false);
  const [relayError, setRelayError] = useState<string | null>(null);
  /**
   * Every payment credential this wallet reports, WITH the address it came from.
   *
   * ⚑ THE ADDRESS IS THE POINT, not just the hash. A participant is asked for an address before any
   * transaction exists, and bech32 is checksummed where a bare key hash is 56 valid-looking
   * characters — so what this page shows them to copy is the address, with the hash beside it for
   * matching against a member list later.
   */
  const [myCredentials, setMyCredentials] = useState<
    readonly { address: string; keyHash: string; kind: "change" | "used" }[]
  >([]);
  const myKeyHashes = useMemo(
    () => [...new Set(myCredentials.map((c) => c.keyHash))],
    [myCredentials],
  );

  const clean = txHex.replace(/\s+/g, "").toLowerCase();

  const parsed = useMemo(() => {
    if (!clean) return null;
    try {
      return {
        hash: transactionHash(clean),
        summary: summariseTransaction(clean),
        error: null as string | null,
      };
    } catch (e) {
      return {
        hash: null,
        summary: null as TxSummary | null,
        error: e instanceof Error ? e.message : String(e),
      };
    }
  }, [clean]);

  /** The witness this page produced, checked against this transaction. */
  const checks: VerifiedWitness[] = useMemo(() => {
    if (!witness || !parsed?.hash) return [];
    try {
      return verifyWitnessSet(clean, witness);
    } catch {
      return [];
    }
  }, [witness, clean, parsed]);

  /**
   * Pull the transaction out of the hand-off.
   *
   * ⛔ RECOMPUTE, THEN REFUSE — but be honest about what that proves. The id the server keyed
   * this under was derived from these same bytes, so a mismatch means the server is broken, not
   * that someone swapped the transaction. It is a transport check. The control that catches
   * substitution is a human reading the hash aloud on a channel the transaction did not arrive
   * on, which is why this does not mark the transaction "confirmed" in any way.
   */
  const fetchFromRelay = async () => {
    const id = relayId.trim().toLowerCase();
    setRelayError(null);
    setFetching(true);
    try {
      const res = await fetch(`/api/deployment/relay?id=${encodeURIComponent(id)}`);
      const body = (await res.json()) as { tx?: string; reason?: string; error?: string };
      if (!res.ok || !body.tx) {
        throw new Error(
          body.reason === "expired"
            ? "That transaction was held but has expired — ask whoever sent it to push it again."
            : body.reason === "unknown"
              ? "Nothing is held under that id. It may never have been pushed, the server may have " +
                "restarted, or the id is not the one you were given. Ask for it to be pushed again, " +
                "or paste the transaction instead."
              : (body.error ?? `The server returned ${res.status}.`),
        );
      }
      const got = body.tx.trim().toLowerCase();
      const recomputed = transactionHash(got);
      if (recomputed !== id) {
        throw new Error(
          `The server returned a transaction whose id is ${recomputed}, not the ${id} you asked ` +
            "for. Nothing has been filled in. Do not sign anything from this source — paste the " +
            "transaction you were sent directly.",
        );
      }
      setTxHex(got);
    } catch (e) {
      setRelayError(
        (e as Error).message.includes("fetch")
          ? "Could not reach the server. If you are offline this is expected — paste the " +
            "transaction instead; signing itself needs no network."
          : (e as Error).message,
      );
    } finally {
      setFetching(false);
    }
  };

  /**
   * Which credentials does this wallet hold?
   *
   * ⛔ RE-READ ON FOCUS AND VISIBILITY. CIP-30 has no account-change event, so a value captured
   * once at connect silently describes an account the participant may have switched away from —
   * and this page would then put its own authority behind the wrong key hash. Re-reading when the
   * tab comes back is the cheapest approximation of an event that does not exist.
   */
  useEffect(() => {
    if (!connected) {
      setMyCredentials([]);
      return;
    }
    let live = true;
    const read = async () => {
      try {
        const [change, used] = await Promise.all([
          wallet.getChangeAddress(),
          wallet.getUsedAddresses().catch(() => [] as string[]),
        ]);
        if (!live) return;
        const seen = new Set<string>();
        const out: { address: string; keyHash: string; kind: "change" | "used" }[] = [];
        for (const [address, kind] of [
          [change, "change"] as const,
          ...used.filter(Boolean).map((a) => [a, "used"] as const),
        ]) {
          if (!address || seen.has(address)) continue;
          seen.add(address);
          try {
            out.push({ address, keyHash: paymentCredentialHash(address).toLowerCase(), kind });
          } catch {
            /* not a payment address — a reward address has no payment credential */
          }
        }
        setMyCredentials(out);
      } catch {
        if (live) setMyCredentials([]);
      }
    };
    read();
    const onWake = () => { if (document.visibilityState === "visible") read(); };
    window.addEventListener("focus", onWake);
    document.addEventListener("visibilitychange", onWake);
    return () => {
      live = false;
      window.removeEventListener("focus", onWake);
      document.removeEventListener("visibilitychange", onWake);
    };
  }, [connected, wallet]);

  /**
   * Is this wallet one of the keys this transaction requires?
   *
   * `null` when the question cannot be answered — no wallet, or a transaction that declares no
   * required signers at all — because "no" and "cannot tell" must not look the same.
   */
  const amRequired = useMemo(() => {
    const required = parsed?.summary?.requiredSigners;
    if (!required || required.length === 0 || myKeyHashes.length === 0) return null;
    const wanted = required.map((r) => r.toLowerCase());
    const mine = myKeyHashes.find((h) => wanted.includes(h));
    return { required: mine !== undefined, matched: mine ?? null };
  }, [parsed, myKeyHashes]);

  const sign = async () => {
    setError(null);
    setWitness(null);
    setCopied(false);
    if (!connected) {
      setError("Connect a wallet first.");
      return;
    }
    if (!parsed?.hash) {
      setError("Paste a valid transaction first.");
      return;
    }
    setBusy(true);
    try {
      // partialSign = true: this is one signature among several, so the wallet
      // must NOT refuse for the keys it cannot provide. It returns a witness
      // SET, not a transaction — which is exactly what the assembler merges.
      const returned = await wallet.signTx(clean, true);
      // ⛔ NOT EVERY WALLET RETURNS A WITNESS SET. CIP-30 says signTx yields
      // cbor<transaction_witness_set>, and some wallets hand back the whole signed transaction.
      // The witness set is element 1 of it, so take it rather than refuse — and say so, because
      // "your wallet returned more than asked" is worth knowing when comparing wallets.
      const { hex: ws, fromTransaction } = asWitnessSetHex(returned);
      if (fromTransaction) setNote(`${name} returned a whole transaction; the witness was extracted from it.`);
      const verified = verifyWitnessSet(clean, ws);
      if (verified.length === 0) {
        throw new Error(
          "The wallet returned no signature. If a dialog appeared and you approved it, " +
            "this wallet may hold no key that this transaction asks for."
        );
      }
      const bad = verified.filter((v) => !v.valid);
      if (bad.length > 0) {
        throw new Error(
          `The wallet returned ${bad.length} signature(s) that do not verify against this ` +
            "transaction. Do not send this on — it would fail after everyone else has signed."
        );
      }
      setWitness(ws);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  /** A labelled copy, so more than one button can report "Copied" independently. */
  const [copiedWhat, setCopiedWhat] = useState<string | null>(null);
  const copyValue = async (what: string, value: string) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopiedWhat(what);
      setTimeout(() => setCopiedWhat((c) => (c === what ? null : c)), 2000);
    } catch {
      setError("Could not copy — select the text and copy it manually.");
    }
  };

  const copy = async () => {
    if (!witness) return;
    try {
      await navigator.clipboard.writeText(witness);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      setError("Could not copy — select the text and copy it manually.");
    }
  };

  return (
    <main className="mx-auto max-w-3xl px-4 py-10 space-y-8">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-white">Sign a transaction</h1>
          <Badge variant="warning" size="sm">{network}</Badge>
        </div>
        <p className="text-sm text-dark-400">
          Someone building a multi-signature transaction sent you its hex. Paste it, check what it
          is, sign it with your wallet, and send the witness back. Nothing is stored here, and the
          witness is useful only for this one transaction.
        </p>
      </header>

      {/*
        ⛔ ABOVE THE TRANSACTION, AND NOT CONDITIONAL ON ONE. The previous round put this inside the
        required-signers block, so it appeared only once a transaction had been pasted — which is
        after the moment it is for. A participant is asked for their address BEFORE the transaction
        that will require it exists.
      */}
      {connected && myCredentials.length > 0 && (
        <section className="space-y-2 rounded border border-primary-500/30 bg-primary-500/5 p-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-semibold text-white">Your wallet — send this to the driver</h2>
            <button
              type="button"
              onClick={() => copyValue("addr", myCredentials[0].address)}
              className="rounded border border-dark-600 px-2 py-0.5 text-[10px] text-dark-200 hover:text-primary-400"
            >
              {copiedWhat === "addr" ? "Copied" : "Copy address"}
            </button>
          </div>
          <p className="text-[11px] text-dark-400">
            Give the <strong>address</strong>, not the key hash — it carries a checksum, so a
            mangled one is refused on sight where a wrong hash is 56 valid-looking characters. The
            hash is shown so you can match it against a member list afterwards.
          </p>
          <ul className="space-y-1.5">
            {myCredentials.map((c) => (
              <li key={c.address} className="space-y-0.5">
                <p className="flex items-center gap-2 text-[10px] uppercase tracking-wider text-dark-400">
                  {c.kind === "change" ? "change address" : "used address"}
                  {c.kind === "change" && (
                    <span className="normal-case tracking-normal text-dark-500">
                      — the one a deployer checks
                    </span>
                  )}
                </p>
                <p className="break-all font-mono text-xs text-primary-400">{c.address}</p>
                <p className="break-all font-mono text-[10px] text-dark-400">
                  payment key hash {c.keyHash}
                </p>
              </li>
            ))}
          </ul>
          <p className="text-[10px] text-dark-500">
            Re-read whenever this tab regains focus: CIP-30 has no account-change event, so a value
            captured once would quietly describe an account you had switched away from.
          </p>
        </section>
      )}

      <section className="space-y-2">
        <h2 className="text-lg font-semibold text-white">1. The transaction</h2>
        <div className="flex flex-wrap items-center gap-2">
          <input
            id="relay-id"
            className="min-w-[22rem] flex-1 rounded border border-dark-700 bg-dark-900 px-2 py-1.5 font-mono text-xs text-white placeholder:text-dark-500 focus:border-primary-500/50 focus:outline-none"
            placeholder="transaction id, if you were given one (64 hex)"
            value={relayId}
            onChange={(e) => setRelayId(e.target.value)}
            spellCheck={false}
          />
          <button
            type="button"
            onClick={fetchFromRelay}
            disabled={fetching || !/^[0-9a-f]{64}$/.test(relayId.trim().toLowerCase())}
            className="rounded border border-dark-600 bg-dark-800 px-3 py-1.5 text-xs text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400 disabled:opacity-40"
          >
            {fetching ? "Fetching…" : "Fetch from server"}
          </button>
        </div>
        <p className="text-[11px] text-dark-500">
          The id is how you FIND the transaction, not proof of which one you got — confirm the hash
          below on the call. Or paste the transaction directly; signing needs no network either way.
        </p>
        {relayError && <p className="text-xs text-red-400">{relayError}</p>}
        <textarea
          id="sign-tx-hex"
          className="h-28 w-full rounded bg-dark-900 px-3 py-2 font-mono text-xs text-white border border-dark-700 focus:border-primary-500/50 focus:outline-none"
          placeholder="84a400d9010281825820… (paste the transaction hex)"
          value={txHex}
          onChange={(e) => setTxHex(e.target.value)}
          spellCheck={false}
        />
        {parsed?.error && <p className="text-xs text-red-400">{parsed.error}</p>}
      </section>

      {parsed?.hash && parsed.summary && (
        <>
          <section className="space-y-2">
            <h2 className="text-lg font-semibold text-white">2. Check this is the right one</h2>
            <p className="text-xs text-dark-400">
              Confirm this hash with whoever sent it, over a different channel than the one the
              transaction arrived on. Every signature commits to exactly these bytes: if two people
              see different hashes, they are signing different transactions.
            </p>
            <div className="rounded border border-primary-500/30 bg-primary-500/5 p-4">
              <p className="text-[10px] uppercase tracking-wider text-dark-400">Transaction hash</p>
              <p className="mt-1 break-all font-mono text-base text-primary-400">{parsed.hash}</p>
            </div>

            <dl className="grid grid-cols-2 gap-x-6 gap-y-1 pt-2 text-xs sm:grid-cols-3">
              <Fact label="Inputs" value={String(parsed.summary.inputCount)} />
              <Fact label="Outputs" value={String(parsed.summary.outputCount)} />
              <Fact
                label="Fee"
                value={parsed.summary.fee ? `${(Number(parsed.summary.fee) / 1e6).toFixed(6)} ADA` : "—"}
              />
              <Fact label="Mints tokens" value={parsed.summary.mints ? "yes" : "no"} />
              <Fact label="Certificates" value={String(parsed.summary.certificateCount)} />
              <Fact label="Withdrawals" value={String(parsed.summary.withdrawalCount)} />
            </dl>

            {parsed.summary.requiredSigners.length > 0 && (
              <div className="pt-2">
                <p className="text-[10px] uppercase tracking-wider text-dark-400">
                  Declared required signers
                </p>
                <ul className="mt-1 space-y-0.5">
                  {parsed.summary.requiredSigners.map((s) => (
                    <li
                      key={s}
                      className={`break-all font-mono text-[11px] ${
                        myKeyHashes.includes(s.toLowerCase()) ? "text-green-300" : "text-dark-300"
                      }`}
                    >
                      {s}
                      {myKeyHashes.includes(s.toLowerCase()) && " ← you"}
                    </li>
                  ))}
                </ul>
                {/*
                  The answer, not the evidence. Printing a key hash for a participant to compare
                  by eye is the mistake this replaces.
                */}
                {amRequired === null ? (
                  <p className="mt-2 text-[11px] text-dark-500">
                    {connected
                      ? "Connect a wallet that holds one of these keys to check whether yours is among them."
                      : "Connect your wallet and this page will tell you whether your key is one of them."}
                  </p>
                ) : amRequired.required ? (
                  <p className="mt-2 text-xs text-green-300">
                    Your wallet holds one of the keys this transaction requires. Signing here is
                    what was asked of you.
                  </p>
                ) : (
                  <p className="mt-2 text-xs text-accent-300">
                    <strong>None of your wallet&apos;s keys is among the required signers.</strong>{" "}
                    Either this is not the transaction meant for you, or you are on a different
                    account than the one whose address you gave — check the wallet&apos;s account
                    selector. Signing anyway produces a witness that will be reported as coming
                    from an undeclared key.
                  </p>
                )}
                {myKeyHashes.length > 0 && (
                  <p className="mt-1 text-[10px] text-dark-500">
                    Checked against {myKeyHashes.length} credential
                    {myKeyHashes.length === 1 ? "" : "s"} from this wallet, listed at the top of
                    this page.
                  </p>
                )}
              </div>
            )}

            {parsed.summary.unknownFields.length > 0 && (
              <p className="text-xs text-accent-400">
                This transaction carries {parsed.summary.unknownFields.length} field(s) this
                summary does not interpret (keys {parsed.summary.unknownFields.join(", ")}). The
                summary above is therefore incomplete — treat it as a sanity check, not an audit.
              </p>
            )}
            <p className="text-[11px] text-dark-500">
              This is a summary of the transaction&apos;s shape, not a full decode. It cannot tell
              you that a transaction is safe — only that it looks like what you were expecting.
            </p>
          </section>

          <section className="space-y-3">
            <h2 className="text-lg font-semibold text-white">3. Sign</h2>
            {!connected ? (
              <p className="text-sm text-dark-400">
                Connect your wallet using the button in the header, then return here.
              </p>
            ) : (
              <p className="text-xs text-dark-400">
                Signing with <span className="text-dark-200">{name}</span>. Your wallet will be
                asked for a partial signature, so it will not object to the keys it does not hold.
              </p>
            )}
            <button
              type="button"
              onClick={sign}
              disabled={busy || !connected}
              className="rounded bg-primary-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-primary-500 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {busy ? "Waiting for your wallet…" : "Sign this transaction"}
            </button>
            {error && <p className="text-xs text-red-400">{error}</p>}
            {/* Wallets differ on what signTx returns; say when one returned more than a witness. */}
            {note && <p className="text-xs text-amber-200">{note}</p>}
          </section>
        </>
      )}

      {witness && (
        <section className="space-y-2">
          <h2 className="text-lg font-semibold text-white">4. Send this back</h2>
          {checks.length > 0 && (
            <div className="space-y-1">
              {checks.map((c) => (
                <div key={c.vkeyHex} className="flex flex-wrap items-center gap-2 text-xs">
                  <Badge variant={c.valid ? "success" : "error"} size="sm">
                    {c.valid ? "Verified" : "Invalid"}
                  </Badge>
                  <span className="font-mono text-[11px] text-dark-300 break-all">
                    key hash {c.keyHash}
                  </span>
                </div>
              ))}
              <p className="text-[11px] text-dark-500">
                Checked here against this transaction&apos;s body hash, so it is known good before
                you send it — not discovered to be wrong after everyone else has signed.
              </p>
            </div>
          )}
          <textarea
            id="sign-witness-out"
            readOnly
            className="h-24 w-full rounded bg-dark-900 px-3 py-2 font-mono text-xs text-primary-300 border border-dark-700"
            value={witness}
            spellCheck={false}
          />
          <button
            type="button"
            onClick={copy}
            className="rounded border border-dark-600 bg-dark-800 px-3 py-1.5 text-xs text-dark-100 transition-colors hover:border-primary-500/40 hover:text-primary-400"
          >
            {copied ? "Copied" : "Copy witness"}
          </button>
        </section>
      )}
    </main>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wider text-dark-500">{label}</dt>
      <dd className="font-mono text-dark-200">{value}</dd>
    </div>
  );
}
