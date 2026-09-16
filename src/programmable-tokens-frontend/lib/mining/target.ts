/**
 * What "a low transaction hash" means here, and how long it takes to get one.
 *
 * ## Why anyone wants this
 *
 * A Cardano transaction's inputs are sorted into canonical order by `(txHash, outputIndex)`. So a
 * transaction whose own hash is low produces UTxOs that sort EARLY in the input list of every
 * future transaction that spends them — which makes their positions, and therefore the redeemer
 * indices that point at them, predictable. That is the purpose; it is not cosmetic.
 *
 * ## Why four is the default, as arithmetic rather than taste
 *
 * To sort first you only have to be lower than the OTHER inputs in the same transaction, and an
 * un-mined hash is uniform over 2^256. Four leading zero nibbles means a given ordinary input
 * beats you with probability 2^-16, about 1 in 65,536. Against five ordinary inputs the chance
 * any of them sorts below you is roughly 0.008%.
 *
 * ⚠ THE EXCEPTION, AND IT IS NOT COVERED BY THAT NUMBER: another MINED hash. Four against four is
 * a coin flip, and no threshold fixes a contest — if two mined UTxOs routinely meet in one
 * transaction the target has to be reasoned about as a race, not as a bar. See {@link contested}.
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
 * The probability that at least one of `otherInputs` ordinary inputs sorts BELOW a mined hash.
 *
 * This is the number that justifies the default, so the UI should show it rather than assert
 * "4 is enough". Ordinary inputs are uniform over 2^256, so each beats a hash with `n` leading
 * zero nibbles with probability 16^-n.
 */
export function collisionRisk(targetNibbles: number, otherInputs: number): number {
  const perInput = Math.pow(16, -targetNibbles);
  return 1 - Math.pow(1 - perInput, Math.max(0, otherInputs));
}

/**
 * Whether the target is being used as a BAR or as a RACE.
 *
 * Against ordinary inputs a target is a bar and {@link collisionRisk} answers it. Against another
 * mined hash at the same target it is a coin flip and the target answers nothing — the only fix
 * is to out-mine the other party, which is an arms race and not a setting. Surfaced as its own
 * function so the distinction cannot be quietly lost in a percentage.
 */
export function contested(targetNibbles: number, otherMinedAtSameTarget: number): boolean {
  return otherMinedAtSameTarget > 0 && targetNibbles > 0;
}
