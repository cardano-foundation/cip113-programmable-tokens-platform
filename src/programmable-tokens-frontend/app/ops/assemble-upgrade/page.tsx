"use client";

/**
 * Hidden operator page: assemble M-of-N signatures for a protocol upgrade.
 *
 * Unlisted and unauthenticated, like the bootstrap page, and for the same reason — it holds no
 * secrets and stores nothing. Every input is public: an unsigned transaction, a set of key
 * hashes that are already on chain in the multisig datum, and witnesses that are only useful
 * for the one transaction they were made for.
 *
 * ## Storage-free by decision
 *
 * There is no shared draft. The assembler copies the unsigned transaction hex to the signers
 * over whatever channel they already use, each signs it in their own wallet, and pastes the
 * witness hex back. Nothing is persisted, so there is nothing to secure — which matters,
 * because this platform authenticates nothing and a stored draft would be another
 * unauthenticated write that changes later behaviour.
 *
 * ## Counting witnesses is not checking them
 *
 * A tool that counts witness sets and calls M of them a quorum fails in two ways that both
 * surface only after everyone has done their part: the same signer pasted twice looks like
 * two, and a signature from outside the authority looks like one. Every witness here is
 * reduced to the key hash that produced it and matched against the declared members.
 */
import { useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { getCardanoNetwork } from "@/lib/utils/network";
import { checkQuorum, assembleUpgradeTx, type QuorumResult } from "@/lib/upgrade/witness";

export default function AssembleUpgradePage() {
  const network = getCardanoNetwork();

  const [unsignedTx, setUnsignedTx] = useState("");
  const [membersText, setMembersText] = useState("");
  const [threshold, setThreshold] = useState("2");
  const [witnessText, setWitnessText] = useState("");
  const [assembled, setAssembled] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const members = useMemo(
    () => membersText.split(/[\s,]+/).map((s) => s.trim().toLowerCase()).filter(Boolean),
    [membersText],
  );
  const witnesses = useMemo(
    () => witnessText.split(/[\s,]+/).map((s) => s.trim()).filter(Boolean),
    [witnessText],
  );

  const quorum: QuorumResult | null = useMemo(() => {
    if (members.length === 0 || witnesses.length === 0) return null;
    try {
      return checkQuorum(witnesses, members, Number(threshold));
    } catch {
      return null;
    }
  }, [witnesses, members, threshold]);

  const assemble = () => {
    setError(null);
    setAssembled(null);
    try {
      if (!quorum?.satisfied) {
        throw new Error(
          "Refusing to assemble: the quorum is not satisfied. Assembling anyway would produce " +
            "a transaction the ledger rejects after every signer has already done their part.",
        );
      }
      setAssembled(assembleUpgradeTx(unsignedTx.trim(), witnesses));
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <main className="mx-auto max-w-4xl px-4 py-10 space-y-8">
      <header className="space-y-2">
        <div className="flex items-center gap-3">
          <h1 className="text-2xl font-bold text-white">Assemble a protocol upgrade</h1>
          <Badge variant="warning" size="sm">{network}</Badge>
        </div>
        <p className="text-sm text-dark-400">
          Collect M-of-N multisig signatures into one submittable transaction. Nothing is stored:
          share the unsigned transaction, collect witnesses, assemble here.
        </p>
      </header>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold text-white">1. The unsigned transaction</h2>
        <p className="text-xs text-dark-400">
          Share this exact hex with every signer. A witness only fits the transaction it was made
          for, so a signer who signs a different draft produces a witness that cannot be used.
        </p>
        <textarea
          className="h-24 w-full rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
          placeholder="84a4..."
          value={unsignedTx}
          onChange={(e) => setUnsignedTx(e.target.value)}
        />
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold text-white">2. The authority</h2>
        <p className="text-xs text-dark-400">
          The member key hashes from the multisig datum, one per line, and how many must sign.
        </p>
        <textarea
          className="h-24 w-full rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
          placeholder="32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2"
          value={membersText}
          onChange={(e) => setMembersText(e.target.value)}
        />
        <div className="flex items-center gap-2 text-sm text-dark-300">
          <span>Required</span>
          <input
            className="w-20 rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
            value={threshold}
            onChange={(e) => setThreshold(e.target.value)}
          />
          <span className="text-xs text-dark-400">of {members.length || "—"}</span>
        </div>
      </section>

      <section className="space-y-2">
        <h2 className="text-lg font-semibold text-white">3. Collected witnesses</h2>
        <p className="text-xs text-dark-400">
          One witness-set hex per line, as each wallet returned it from a partial sign.
        </p>
        <textarea
          className="h-28 w-full rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
          placeholder="a10081825820..."
          value={witnessText}
          onChange={(e) => setWitnessText(e.target.value)}
        />
      </section>

      {quorum && (
        <section className="space-y-2 rounded border border-dark-700 bg-dark-950 p-3 text-xs">
          <p className="text-white">
            {quorum.signed.length} of {quorum.required} required signature
            {quorum.required === 1 ? "" : "s"} present
            {quorum.satisfied ? " — quorum satisfied" : ""}
          </p>
          {quorum.missing.length > 0 && (
            <p className="text-dark-400">
              Not yet signed: {quorum.missing.map((h) => h.slice(0, 12) + "…").join(", ")}
            </p>
          )}
          {quorum.strangers.length > 0 && (
            <p className="text-red-300">
              {quorum.strangers.length} witness
              {quorum.strangers.length === 1 ? "" : "es"} from outside the authority:{" "}
              {quorum.strangers.map((h) => h.slice(0, 12) + "…").join(", ")}. These are not
              members of this multisig — assembling them would produce a transaction the ledger
              rejects.
            </p>
          )}
          {quorum.checks.some((c) => c.members.length === 0 && c.strangers.length === 0) && (
            <p className="text-amber-300">One pasted witness carried no vkey at all.</p>
          )}
        </section>
      )}

      <button
        type="button"
        onClick={assemble}
        disabled={!quorum?.satisfied || unsignedTx.trim().length === 0}
        className="rounded bg-accent-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
      >
        Assemble signed transaction
      </button>

      {error && (
        <div className="rounded border border-red-700 bg-red-950/40 p-3 text-sm text-red-200">
          {error}
        </div>
      )}

      {assembled && (
        <section className="space-y-2">
          <h2 className="text-lg font-semibold text-white">Signed transaction</h2>
          <p className="text-xs text-dark-400">
            The body is byte-identical to what everyone signed — witnesses were merged without
            re-encoding it, because re-encoding would invalidate every signature present.
          </p>
          <textarea
            readOnly
            className="h-28 w-full rounded bg-dark-900 px-2 py-1 font-mono text-xs text-white"
            value={assembled}
          />
          <button
            type="button"
            onClick={() => navigator.clipboard.writeText(assembled)}
            className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white"
          >
            Copy
          </button>
          <div className="rounded border border-amber-700 bg-amber-950/30 p-3 text-xs text-amber-200">
            Submission is not wired here. Submit with whatever already talks to the chain for
            this network — the transaction is complete and needs nothing further.
          </div>
        </section>
      )}
    </main>
  );
}
