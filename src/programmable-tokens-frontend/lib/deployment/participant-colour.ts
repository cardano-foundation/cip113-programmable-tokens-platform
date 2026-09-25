/**
 * A stable colour per participant, so a signature can be matched to a signer at a glance.
 *
 * ## Derived from the key hash, never from position
 *
 * During a ceremony the operator is reading a list while four people wait, and the question is
 * always "who are we missing?". A 56-character hash answers that slowly. A colour answers it
 * immediately — but only if it is the SAME colour every time, which is why it is a pure
 * function of the key hash and not of the member's index. Add a participant and nobody else's
 * chip moves; re-open the page and every chip is where it was. An index-based palette would
 * reshuffle the whole list on an edit, at exactly the moment that is most confusing.
 *
 * ## Colour is the fast channel, never the only one
 *
 * Roughly one man in twelve cannot separate some of these hues, screenshots get printed in
 * grey, and two hashes can land near each other. So every place a chip appears, the short hash
 * prefix and the operator's label appear with it. The chip makes the common case instant; the
 * text makes every case correct.
 */

/** A participant's visual identity. Hue only — the surrounding CSS supplies the rest. */
export interface ParticipantColour {
  /** 0–359. */
  hue: number;
  /** Ready to drop into a style attribute for a small chip. */
  swatch: { backgroundColor: string; borderColor: string };
  /** What to show beside the chip so colour is never load-bearing on its own. */
  shortHash: string;
}

/**
 * FNV-1a over the hash's bytes.
 *
 * Any stable hash would do; this one is chosen because it is four lines, has no dependency,
 * and spreads adjacent inputs well — two key hashes differing in one character must not land
 * on neighbouring hues, or the chips stop distinguishing exactly the pair most likely to be a
 * transposition of each other.
 */
function fnv1a(input: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < input.length; i++) {
    h ^= input.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h >>> 0;
}

/**
 * The colour for a key hash.
 *
 * Saturation and lightness are fixed and deliberately low — these sit in a dense operator
 * table, not on a landing page, and a row of saturated dots would compete with the content
 * they are meant to index. The values read on both the light and dark ground the app ships.
 */
export function participantColour(keyHash: string): ParticipantColour {
  const normalised = keyHash.trim().toLowerCase();
  const hue = fnv1a(normalised) % 360;
  return {
    hue,
    swatch: {
      backgroundColor: `hsl(${hue} 55% 55% / 0.85)`,
      borderColor: `hsl(${hue} 55% 42%)`,
    },
    shortHash: normalised.slice(0, 8),
  };
}

/**
 * Hues close enough that a glance could confuse them.
 *
 * Reported rather than corrected. Nudging one member's hue to make a pair distinct would break
 * the property the whole thing rests on — that a key's colour never changes — so the page says
 * "these two are similar, read the hashes" instead of quietly making it prettier.
 */
export function confusablePairs(
  keyHashes: readonly string[],
  minSeparation = 25,
): Array<[string, string]> {
  const pairs: Array<[string, string]> = [];
  for (let i = 0; i < keyHashes.length; i++) {
    for (let j = i + 1; j < keyHashes.length; j++) {
      const a = participantColour(keyHashes[i]).hue;
      const b = participantColour(keyHashes[j]).hue;
      const d = Math.abs(a - b);
      if (Math.min(d, 360 - d) < minSeparation) pairs.push([keyHashes[i], keyHashes[j]]);
    }
  }
  return pairs;
}
