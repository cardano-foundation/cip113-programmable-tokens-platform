/**
 * Signing and submitting a CHAIN of transactions, as the freeze-and-seize registration does.
 *
 * Generalised from `combined-build-sign-submit-step.tsx`, which had this sequence inline for
 * its two transactions. A protocol bootstrap is the same shape with more steps, and the parts
 * that are easy to get wrong are the same parts.
 *
 * ## What this is careful about
 *
 * **Sign everything first, submit afterwards.** A wallet that refuses the third signature
 * should cost nothing; if the first two were already on chain, it costs two transactions and
 * leaves a half-built protocol. `signTxs` signs the batch in one prompt where the wallet
 * supports it, falling back to sequential `signTx` where it does not — the same fallback the
 * FES path already needed in the field.
 *
 * **Wait for each before submitting the next.** These are chained: a later transaction spends
 * an output the earlier one creates, so submitting eagerly gets it rejected for a missing
 * input.
 *
 * **A failure after the first submission is NOT a rollback.** Nothing can unwind a landed
 * transaction. The result says exactly which ones are on chain and which never left, because
 * that is what someone has to act on — and a caller that resumes must resume from the failure,
 * not from the start.
 */
export interface MultiTxWallet {
  signTxs?(txs: string[], partialSign: boolean): Promise<string[]>;
  signTx(tx: string, partialSign: boolean): Promise<string>;
  submitTx(signedTx: string): Promise<string>;
}

export interface MultiTxStep {
  /** Shown to the operator; also what an error names. */
  label: string;
  unsignedCbor: string;
}

export type MultiTxPhase =
  | { phase: "signing" }
  | { phase: "submitting"; index: number; label: string }
  | { phase: "confirming"; index: number; label: string; txHash: string }
  | { phase: "confirmed"; index: number; label: string; txHash: string };

export interface MultiTxResult {
  /** Hashes of transactions that reached the chain, in order. */
  submitted: { label: string; txHash: string }[];
  /** Steps that were never submitted, because an earlier one failed. */
  unsubmitted: string[];
}

export class MultiTxError extends Error {
  constructor(
    message: string,
    readonly result: MultiTxResult,
    readonly failedAt: { index: number; label: string },
    readonly cause?: unknown,
  ) {
    super(message);
    this.name = "MultiTxError";
  }
}

export interface MultiTxOptions {
  onPhase?: (phase: MultiTxPhase) => void;
  /** Resolves when a transaction is on chain. Injected so this stays testable. */
  waitForConfirmation: (txHash: string) => Promise<unknown>;
}

export async function signAndSubmitSequence(
  wallet: MultiTxWallet,
  steps: readonly MultiTxStep[],
  options: MultiTxOptions,
): Promise<MultiTxResult> {
  if (steps.length === 0) {
    throw new Error("nothing to submit");
  }
  const { onPhase, waitForConfirmation } = options;

  // ---- sign everything before anything is submitted -------------------------
  onPhase?.({ phase: "signing" });
  const unsigned = steps.map((s) => s.unsignedCbor);
  let signed: string[];
  if (wallet.signTxs) {
    try {
      signed = await wallet.signTxs(unsigned, true);
    } catch {
      // Not every wallet implements signTxs, and some implement it badly. The FES path already
      // needed this fallback in the field.
      signed = [];
      for (const cbor of unsigned) signed.push(await wallet.signTx(cbor, true));
    }
  } else {
    signed = [];
    for (const cbor of unsigned) signed.push(await wallet.signTx(cbor, true));
  }
  if (signed.length !== steps.length) {
    throw new Error(
      `wallet returned ${signed.length} signed transactions for ${steps.length} requested`,
    );
  }

  // ---- submit in order, waiting for each ------------------------------------
  const submitted: { label: string; txHash: string }[] = [];
  for (let i = 0; i < steps.length; i++) {
    const { label } = steps[i];
    const remaining = () => steps.slice(i + 1).map((s) => s.label);
    try {
      onPhase?.({ phase: "submitting", index: i, label });
      const txHash = await wallet.submitTx(signed[i]);
      submitted.push({ label, txHash });

      onPhase?.({ phase: "confirming", index: i, label, txHash });
      await waitForConfirmation(txHash);
      onPhase?.({ phase: "confirmed", index: i, label, txHash });
    } catch (cause) {
      const failedStepLanded = submitted.length === i + 1;
      throw new MultiTxError(
        `"${label}" failed at step ${i + 1} of ${steps.length}. ` +
          (submitted.length === 0
            ? "Nothing reached the chain."
            : `${submitted.length} transaction(s) ARE on chain and cannot be undone: ` +
              submitted.map((s) => `${s.label} (${s.txHash})`).join(", ") + ". ") +
          (failedStepLanded
            ? "The failing step was submitted but not confirmed — check it before retrying, or it may be submitted twice."
            : "The failing step was not submitted."),
        { submitted, unsubmitted: failedStepLanded ? remaining() : [label, ...remaining()] },
        { index: i, label },
        cause,
      );
    }
  }

  return { submitted, unsubmitted: [] };
}
