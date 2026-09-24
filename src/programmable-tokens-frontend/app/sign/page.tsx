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
 * ## Why the witness is verified before it leaves
 *
 * A wallet can sign with a key that is not the one expected, or decline to sign
 * and return an empty set. Both look like success to a page that just forwards
 * whatever came back, and both are discovered by the assembler much later, after
 * everyone else has done their part. Verifying here — the real Ed25519 check
 * against this body hash — turns that into an immediate, local answer.
 */

import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { useWallet } from "@/hooks/use-wallet";
import { getCardanoNetwork } from "@/lib/utils/network";
import { transactionHash, verifyWitnessSet, type VerifiedWitness } from "@/lib/tx/hash";
import { summariseTransaction, type TxSummary } from "@/lib/tx/summary";

export default function SignPage() {
  const network = getCardanoNetwork();
  const { connected, wallet, name } = useWallet();

  const [txHex, setTxHex] = useState("");
  const [witness, setWitness] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

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
      const ws = await wallet.signTx(clean, true);
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

      <section className="space-y-2">
        <h2 className="text-lg font-semibold text-white">1. The transaction</h2>
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
                    <li key={s} className="break-all font-mono text-[11px] text-dark-300">{s}</li>
                  ))}
                </ul>
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
