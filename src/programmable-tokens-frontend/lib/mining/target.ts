/**
 * What "a low transaction hash" means here, and how long it takes to get one.
 *
 * ⛔ THIS IS NOT VANITY, AND THE NEXT PERSON TO FIND A MINER IN A WALLET UI WILL ASSUME IT IS AND
 * DELETE IT. Read this before you do.
 *
 * ## What it is for
 *
 * A Cardano transaction's inputs are sorted into canonical order by `(txHash, outputIndex)`. So a
 * transaction whose own hash is low produces UTxOs that sort EARLY in the input list of every
 * future transaction that spends them — which makes their input positions, and therefore the
 * redeemer indices that point at them, predictable.
 *
 * ## Why four, as arithmetic rather than taste
 *
 * THE OBJECTIVE IS TO SORT AHEAD OF UNMINED INPUTS, not to beat other mined ones. An unmined hash
 * is uniform over 2^256, so four leading zero nibbles means a given unmined input sorts below you
 * with probability 2^-16 — about 1 in 65,536. Against five unmined inputs the chance any of them
 * does is roughly 0.008%. {@link collisionRisk} computes it; show that number rather than
 * asserting the default is fine.
 *
 * ## Why meeting another mined UTxO is NOT a problem, which is the question everyone asks
 *
 * It looks like a tie and it is not a contest at all. Ordering is DETERMINISTIC: every party
 * computes the same `(txHash, index)` sequence from the same bytes, so there is no winner and
 * loser, only a sequence. Two outputs of the SAME mined transaction share a hash and are separated
 * by their index, so they sort adjacently. And being second among several mined UTxOs still leaves
 * you ahead of everything unmined, which is the whole objective.
 *
 * Ruled by Giovanni, 2026-09-16: "it's fine for multiple utxos to be in the same tx, they are
 * deduped by index."
 */

/** The default target: four leading zero nibbles. Sufficient against ordinary inputs, see above. */
export const DEFAULT_TARGET_NIBBLES = 4;

/**
 * The most the UI will let anyone ask for.
 *
 * Each nibble multiplies the work by 16. At a measured ~28k hashes/second, 6 nibbles is ten
 * minutes and 7 is nearly three hours — and those are node figures, with a browser typically
 * 1.5-3x slower. The cap is not a guess about patience; it is the point past which the honest
 * estimate stops being a number anyone would accept.
 */
export const MAX_TARGET_NIBBLES = 7;

/** How many leading hex digits of this hash are zero. */
export function leadingZeroNibbles(hashHex: string): number {
  let n = 0;
  while (n < hashHex.length && hashHex[n] === '0') n++;
  return n;
}

export function meetsTarget(hashHex: string, targetNibbles: number): boolean {
  return leadingZeroNibbles(hashHex) >= targetNibbles;
}

/** Expected attempts for a target: 16^n, since each nibble is one hex digit of 16. */
export function expectedAttempts(targetNibbles: number): number {
  return Math.pow(16, targetNibbles);
}

/**
 * Expected wall-clock, from a rate MEASURED on the machine that will do the work.
 *
 * Never from a constant. A phone and a workstation are not the same instrument, and the whole
 * point of showing an estimate is that someone decides whether to start based on it.
 */
export function expectedSeconds(targetNibbles: number, hashesPerSecond: number): number {
  if (hashesPerSecond <= 0) return Number.POSITIVE_INFINITY;
  return expectedAttempts(targetNibbles) / hashesPerSecond;
}

/** An estimate as something to put in front of a person. */
export function humaniseSeconds(seconds: number): string {
  if (!Number.isFinite(seconds)) return 'unknown';
  if (seconds < 1) return `${Math.round(seconds * 1000)} ms`;
  if (seconds < 90) return `${seconds.toFixed(1)} s`;
  if (seconds < 5400) return `${(seconds / 60).toFixed(1)} min`;
  if (seconds < 172800) return `${(seconds / 3600).toFixed(1)} h`;
  return `${Math.round(seconds / 86400)} days`;
}

/**
 * The probability that at least one of `unminedInputs` sorts BELOW a hash mined to this target.
 *
 * The number that justifies the default, so the UI should show it rather than assert "4 is
 * enough". Unmined hashes are uniform over 2^256, so each sorts below a hash with `n` leading zero
 * nibbles with probability 16^-n.
 */
export function collisionRisk(targetNibbles: number, unminedInputs: number): number {
  const perInput = Math.pow(16, -targetNibbles);
  return 1 - Math.pow(1 - perInput, Math.max(0, unminedInputs));
}

/*
 * ⛔ THERE IS DELIBERATELY NO `contested()` HERE, and this note exists so it is not added.
 *
 * An earlier draft had one, on the reasoning that two mined hashes at the same target are a coin
 * flip and that a threshold cannot settle a race. That reasoning was wrong, and wrong in a way
 * worth recording: transaction input ordering is DETERMINISTIC, not raced. Every party derives the
 * same sequence from the same bytes. There is no contest to lose, so a function implying one would
 * send a reader looking for a fix to a problem that does not exist.
 */
