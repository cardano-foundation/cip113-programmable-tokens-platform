"use client";

/**
 * Choosing a difficulty and watching the search.
 *
 * ⛔ THE POINT OF THIS PANEL IS THE NUMBERS, NOT THE CONTROLS. A difficulty picker that shows
 * only "1 2 3 4 5 6 7" is a trap with a default on it: each step multiplies the work by sixteen,
 * so seven is hours where four is seconds, and nothing on screen would say so. Every option
 * carries the wall-clock it will actually cost, computed from a rate MEASURED ON THIS MACHINE —
 * a phone and a workstation are not the same instrument, and an estimate from a benchmark taken
 * somewhere else is a guess wearing a number's clothes.
 *
 * The default is four, and the panel says WHY rather than asserting it: about a 0.008% chance
 * that any of five unmined inputs sorts ahead. A number with a reason attached is something
 * someone can disagree with; "4 is recommended" is not.
 */
import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Cpu, X } from "lucide-react";
import { useMiner } from "@/lib/mining/use-miner";
import {
  DEFAULT_TARGET_NIBBLES,
  MAX_TARGET_NIBBLES,
  collisionRisk,
  expectedAttempts,
  expectedSeconds,
  humaniseSeconds,
} from "@/lib/mining/target";
import { changeHasHeadroom } from "@/lib/mining/locate";
import type { LovelaceSlot } from "@/lib/mining/mine";

/** Typical number of unmined inputs to quote the collision risk against. */
const TYPICAL_UNMINED_INPUTS = 5;

export interface MiningPanelProps {
  /** The serialised transaction body to mine. */
  body: Uint8Array;
  /** The self-output that gains a lovelace per attempt. */
  gains: LovelaceSlot;
  /** The change output that loses one. */
  loses: LovelaceSlot;
  /** Min-ADA for the change output, so headroom can be checked before starting. */
  minUtxoLovelace: number;
  /** Handed the mined body and its transaction id. Sign THIS body — mining it again would move it. */
  onMined?: (mined: { body: Uint8Array; txHash: string; nonce: number }) => void;
}

export function MiningPanel({ body, gains, loses, minUtxoLovelace, onMined }: MiningPanelProps) {
  const { state, calibrate, mine, cancel, reset } = useMiner();
  const [target, setTarget] = useState(DEFAULT_TARGET_NIBBLES);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [elapsed, setElapsed] = useState(0);

  // Calibrate against a body of the size actually being mined — rate scales inversely with size,
  // so a fixed dummy would mis-estimate in proportion to how unusual this transaction is.
  useEffect(() => {
    if (!state.hashesPerSecond && state.phase === "idle") calibrate(body);
  }, [body, calibrate, state.hashesPerSecond, state.phase]);

  useEffect(() => {
    if (state.phase !== "mining" || startedAt === null) return;
    const id = setInterval(() => setElapsed((Date.now() - startedAt) / 1000), 200);
    return () => clearInterval(id);
  }, [state.phase, startedAt]);

  useEffect(() => {
    if (state.phase === "done" && state.txHash && state.minedBody) {
      onMined?.({ body: state.minedBody, txHash: state.txHash, nonce: state.nonce });
    }
  }, [state.phase, state.txHash, state.minedBody, state.nonce, onMined]);

  const rate = state.hashesPerSecond;

  const headroom = useMemo(
    () => changeHasHeadroom(loses.value, minUtxoLovelace, expectedAttempts(target)),
    [loses.value, minUtxoLovelace, target],
  );

  const risk = collisionRisk(target, TYPICAL_UNMINED_INPUTS);

  const start = useCallback(() => {
    setStartedAt(Date.now());
    setElapsed(0);
    mine({ body, gains, loses, targetNibbles: target });
  }, [body, gains, loses, target, mine]);

  return (
    <section className="space-y-4 rounded-lg border border-dark-700 bg-dark-900 p-4">
      <header className="space-y-1">
        <h3 className="flex items-center gap-2 text-sm font-semibold text-white">
          <Cpu className="h-4 w-4 text-primary-400" aria-hidden />
          Mine a low transaction hash
        </h3>
        <p className="max-w-2xl text-xs text-dark-400">
          Inputs are sorted by transaction hash, so a low one makes this transaction&apos;s outputs
          sort early wherever they are later spent — which keeps their input positions, and the
          redeemer indices pointing at them, predictable.
        </p>
      </header>

      {/* ---- difficulty, with the cost of each step visible ------------------ */}
      <div className="space-y-2">
        <div className="flex items-baseline justify-between">
          <span className="font-mono text-[0.68rem] uppercase tracking-wider text-dark-400">
            Leading zeros
          </span>
          <span className="text-[0.72rem] text-dark-400">
            {rate ? `${rate.toLocaleString()} hashes/s on this machine` : "measuring this machine…"}
          </span>
        </div>

        <div className="flex flex-wrap gap-1.5">
          {Array.from({ length: MAX_TARGET_NIBBLES }, (_, i) => i + 1).map((n) => {
            const seconds = rate ? expectedSeconds(n, rate) : null;
            const slow = seconds !== null && seconds > 60;
            const selected = n === target;
            return (
              <button
                key={n}
                type="button"
                onClick={() => setTarget(n)}
                disabled={state.phase === "mining"}
                aria-pressed={selected}
                className={`flex min-w-[4.5rem] flex-col items-center gap-0.5 rounded border px-2 py-1.5 transition-colors disabled:opacity-40 ${
                  selected
                    ? "border-primary-500 bg-primary-950/30 text-primary-300"
                    : "border-dark-700 text-dark-300 hover:border-dark-500"
                }`}
              >
                <span className="font-mono text-sm">{n}</span>
                <span className={`font-mono text-[0.62rem] ${slow ? "text-accent-400" : "text-dark-500"}`}>
                  {seconds === null ? "—" : humaniseSeconds(seconds)}
                </span>
              </button>
            );
          })}
        </div>

        {/* The reason for the default, rather than the assertion that it is one. */}
        <p className="text-xs text-dark-400">
          At {target} zero{target === 1 ? "" : "s"}, the chance any of {TYPICAL_UNMINED_INPUTS} unmined
          inputs sorts ahead of these outputs is{" "}
          <span className="font-mono text-dark-200">
            {risk < 0.0001 ? risk.toExponential(1) : `${(risk * 100).toFixed(3)}%`}
          </span>
          . Each extra zero multiplies the work by 16 — {MAX_TARGET_NIBBLES} is the cap, and the
          estimate above it is why.
        </p>
      </div>

      {/* ---- the two costs, stated rather than discovered -------------------- */}
      <div className="space-y-1.5 rounded border border-dark-700 bg-dark-950 p-3 text-xs">
        <p className="text-dark-300">
          Mining adds <strong className="text-white">one extra output of about 1 ADA</strong> back to
          your own address. It is a real UTxO: it stays in your wallet until you spend it, and it
          cannot be folded back into change afterwards, because changing the transaction would
          destroy the hash just mined.
        </p>
        {!headroom.ok && (
          <p className="flex items-start gap-2 text-accent-300">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 flex-none" aria-hidden />
            <span>
              The change output cannot afford this search. It holds{" "}
              {(loses.value / 1_000_000).toFixed(6)} ADA and a search at {target} zeros is expected
              to move up to {expectedAttempts(target).toLocaleString()} lovelace out of it, which
              would leave it {(headroom.shortfall / 1_000_000).toFixed(6)} ADA below the minimum a
              UTxO may hold. Choose a lower target or fund the wallet first — this fails at
              submission as &ldquo;insufficient Ada&rdquo;, which does not mention mining.
            </span>
          </p>
        )}
      </div>

      {/* ---- running -------------------------------------------------------- */}
      {state.phase === "mining" ? (
        <div className="space-y-2">
          <div className="flex items-center justify-between text-xs">
            <span className="font-mono text-dark-300">
              {state.attempts.toLocaleString()} attempts · {elapsed.toFixed(1)}s
            </span>
            <button
              type="button"
              onClick={cancel}
              className="inline-flex items-center gap-1 rounded border border-dark-600 px-2 py-1 text-dark-300 hover:border-red-700 hover:text-red-300"
            >
              <X className="h-3 w-3" aria-hidden />
              Cancel
            </button>
          </div>
          <div className="h-1 overflow-hidden rounded bg-dark-800">
            {/* Progress against the EXPECTED attempts, which is a median and not a deadline —
                a search legitimately runs past 100%, and the bar saturating says so honestly. */}
            <div
              className="h-full bg-primary-500 transition-all"
              style={{
                width: `${Math.min(100, (state.attempts / expectedAttempts(target)) * 100).toFixed(1)}%`,
              }}
            />
          </div>
          <p className="text-[0.7rem] text-dark-500">
            {state.attempts > expectedAttempts(target)
              ? "Past the expected number of attempts — that is normal; the estimate is a median, not a deadline."
              : `Expected around ${expectedAttempts(target).toLocaleString()} attempts.`}
          </p>
        </div>
      ) : (
        <button
          type="button"
          onClick={state.phase === "done" ? reset : start}
          disabled={!rate || !headroom.ok}
          className="rounded border border-primary-600 px-3 py-1.5 text-xs text-primary-300 disabled:opacity-40"
        >
          {state.phase === "done" ? "Mine again" : `Mine ${target} leading zeros`}
        </button>
      )}

      {/* ---- outcome -------------------------------------------------------- */}
      {state.phase === "done" && state.txHash && (
        <div className="space-y-1 rounded border border-primary-600/40 bg-primary-950/20 p-3">
          <p className="flex items-center gap-2 text-xs text-primary-300">
            <Check className="h-4 w-4 flex-none" aria-hidden />
            Found in {state.attempts.toLocaleString()} attempts, {elapsed.toFixed(1)}s
          </p>
          <p className="break-all font-mono text-xs text-white">
            <span className="text-primary-400">{state.txHash.slice(0, target)}</span>
            {state.txHash.slice(target)}
          </p>
          <p className="text-[0.7rem] text-dark-400">
            {state.nonce.toLocaleString()} lovelace moved from change into the extra output. Sign
            this transaction as it now stands — any further change moves the hash.
          </p>
        </div>
      )}

      {state.phase === "done" && !state.txHash && (
        <p className="text-xs text-dark-400">
          {state.cancelled
            ? "Cancelled. Nothing was changed — the transaction is exactly as it was."
            : "No hash found within the attempt limit. Try a lower target."}
        </p>
      )}

      {state.phase === "failed" && (
        <p className="rounded border border-red-800 bg-red-950/25 p-2 text-xs text-red-200">
          {state.error}
        </p>
      )}
    </section>
  );
}
