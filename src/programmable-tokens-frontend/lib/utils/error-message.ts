/**
 * Turn anything thrown into something a user can act on.
 *
 * The reason this exists: **CIP-30 wallets do not throw `Error`.** The standard specifies
 * plain objects — `APIError`, `TxSendError`, `TxSignError` — shaped as `{ code, info }`,
 * where `info` carries the node's actual rejection reason. So the idiom
 *
 * ```ts
 * const message = error instanceof Error ? error.message : 'Failed to sign or submit';
 * ```
 *
 * takes the `false` branch for every wallet failure and replaces the one useful string in
 * the whole object with a constant. Every submit error then reads "Failed to sign or
 * submit" whatever actually happened — a declined signature, insufficient collateral, a
 * ledger rule violation — and the `info` needed to tell them apart is discarded before
 * anyone sees it.
 *
 * `describeError` unwraps the shapes that actually occur, in order of specificity, and
 * falls back to a JSON dump rather than to a sentence that says nothing.
 */
export function describeError(error: unknown, fallback = 'Unknown error'): string {
  if (typeof error === 'string') return error;

  if (error instanceof Error) {
    // An Error with an empty message is worse than useless in a toast — name it.
    return error.message || error.name || fallback;
  }

  if (error && typeof error === 'object') {
    const e = error as Record<string, unknown>;

    // CIP-30 APIError / TxSendError / TxSignError: { code: number, info: string }.
    // `info` is the human-readable part and is what the node or wallet actually said.
    if (typeof e.info === 'string' && e.info.length > 0) {
      return e.code !== undefined ? `${e.info} (code ${String(e.code)})` : e.info;
    }

    // Some wallets and SDKs throw { message } without being an Error instance.
    if (typeof e.message === 'string' && e.message.length > 0) return e.message;

    // Last resort before the fallback: show the object rather than hide it. A raw JSON
    // blob in the UI is ugly, but it is evidence, and the alternative is a support
    // conversation that starts with "it just says it failed".
    try {
      const json = JSON.stringify(error);
      if (json && json !== '{}') return json;
    } catch {
      /* circular or non-serialisable — fall through */
    }
  }

  return fallback;
}

/**
 * Log the raw throwable alongside the derived message.
 *
 * Deriving a message is lossy by design; this keeps the original reachable in the console
 * for whoever has to diagnose it. Call it at the top of a catch, before any branching that
 * might swallow the error.
 */
export function logError(context: string, error: unknown): string {
  const message = describeError(error);
  // eslint-disable-next-line no-console
  console.error(`[CIP-113] ${context}:`, message, error);
  return message;
}
