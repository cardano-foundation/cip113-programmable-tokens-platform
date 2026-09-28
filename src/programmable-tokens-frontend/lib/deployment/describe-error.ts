/**
 * Render an error with its whole cause chain, for ceremony failures.
 *
 * ⛔ WHY THIS EXISTS. Evolution wraps provider failures as
 * `new ProviderError({ message: "Blockfrost evaluateTx failed", cause })` — the message is a
 * category, and everything that identifies the problem is in `cause`. A UI that prints
 * `(e as Error).message` therefore shows "Script evaluation failed: Provider evaluation failed:
 * Blockfrost evaluateTx failed", which names neither the script nor the reason and is
 * indistinguishable between a rate limit, a 400 on a malformed body, and a validator that
 * genuinely said no.
 *
 * The SDK's own advice is to inject an evaluator so the validator's traces come back instead.
 * That needs `effect` as a direct dependency and an endpoint this deployment may not have, so
 * until then the cheap half is simply to stop discarding what we were already given.
 *
 * ⚑ It also digs out an HTTP response body when one is present, because Blockfrost puts the
 * useful part there — Ogmios evaluation errors arrive as a JSON body under a 400, not as a
 * message on the exception.
 */
export function describeError(err: unknown, maxDepth = 6): string {
  const parts: string[] = [];
  const seen = new Set<unknown>();
  let current: unknown = err;

  for (let depth = 0; depth < maxDepth && current != null; depth += 1) {
    if (seen.has(current)) break;
    seen.add(current);

    const text = oneLevel(current);
    // Skip a level that adds nothing — wrappers often repeat their child's words.
    if (text && !parts.some((p) => p === text)) parts.push(text);

    const next = (current as { cause?: unknown }).cause;
    if (next === undefined) break;
    current = next;
  }

  return parts.length > 0 ? parts.join("\n  ↳ ") : String(err);
}

/** One link of the chain: its message, plus any response body or payload it carries. */
function oneLevel(value: unknown): string {
  if (value == null) return "";
  if (typeof value === "string") return value;

  const e = value as {
    message?: unknown;
    _tag?: unknown;
    reason?: unknown;
    response?: { status?: unknown; body?: unknown };
    error?: unknown;
  };

  const bits: string[] = [];
  if (typeof e.message === "string" && e.message.length > 0) bits.push(e.message);
  else if (typeof e._tag === "string") bits.push(e._tag);

  if (typeof e.reason === "string") bits.push(`(${e.reason})`);

  // The part that usually holds the answer.
  const status = e.response?.status;
  if (status !== undefined) bits.push(`HTTP ${String(status)}`);
  const body = e.response?.body ?? e.error;
  if (body !== undefined) {
    const rendered = typeof body === "string" ? body : safeJson(body);
    if (rendered && rendered !== "{}") bits.push(rendered.slice(0, 600));
  }

  if (bits.length === 0) {
    const rendered = safeJson(value);
    return rendered && rendered !== "{}" ? rendered.slice(0, 300) : String(value);
  }
  return bits.join(" ");
}

function safeJson(value: unknown): string {
  try {
    return JSON.stringify(value, (_k, v) => (typeof v === "bigint" ? `${v}n` : v));
  } catch {
    return "";
  }
}
