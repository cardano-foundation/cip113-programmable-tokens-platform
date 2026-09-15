/**
 * Display names for substandards.
 *
 * ## Why a map and not a rename
 *
 * A substandard id is a key: it is written into the database, sent in registration callbacks, and
 * matched by the SDK when it routes an operation. Renaming one is a migration. Renaming one to fix
 * a LABEL would be a migration to fix a caption.
 *
 * So the ids stay exactly as they are and this maps them to what a reader should see. The ids
 * describe a mechanism — `freeze-and-seize` is three capabilities — and a reader wants the
 * category that mechanism serves.
 *
 * ## The two choices worth knowing about
 *
 * **`freeze-and-seize` → "Stablecoin"** is Giovanni's word (2026-09-15) and the one an issuer
 * would use. Freeze, seize and denylist are the compliance floor a regulated payment token needs.
 * The alternative on the table is "Payment token", FINMA's own term, which would pair exactly with
 * the Swiss framework the security substandard implements — a matched taxonomy rather than one
 * marketing word and one legal one. OPEN.
 *
 * **`rwa-token` → "CMTA · eWpG"** names the FRAMEWORK the contracts implement rather than
 * asserting an asset class, deliberately, on two counts. "RWA" is too vague to mean anything —
 * real estate, invoices and commodities all qualify. And the obvious fix, "Security token", is the
 * reference implementation's own term but is also a legal term of art, AND this repository already
 * renamed `security-token` → `rwa-token` (migration V21). Somebody moved away from that word on
 * purpose; a caption is not the place to move back without asking. OPEN.
 */

export type SubstandardKind = 'stable' | 'security' | 'template' | 'unknown';

export interface SubstandardLabel {
  /** What a reader sees. */
  label: string;
  /** Drives the tag colour. Semantic, not decorative — a security is not a stablecoin. */
  kind: SubstandardKind;
  /** One line for the detail panel. Says what the substandard actually does. */
  blurb: string;
}

const LABELS: Record<string, SubstandardLabel> = {
  'freeze-and-seize': {
    label: 'Stablecoin',
    kind: 'stable',
    blurb: 'Denylist, freeze and seize — the compliance floor a regulated payment token needs.',
  },
  'rwa-token': {
    label: 'CMTA · eWpG',
    kind: 'security',
    blurb:
      'Tokenised securities under the Swiss CMTA framework and the German Electronic Securities ' +
      'Act: KYC-gated transfers, denylist, global pause, forced transfers, supply caps.',
  },
  dummy: {
    label: 'Template',
    kind: 'template',
    blurb: 'A permissioned-transfer starting point. Not a product.',
  },
  kyc: {
    label: 'KYC',
    kind: 'template',
    blurb: 'Trusted-attestation KYC.',
  },
  'kyc-extended': {
    label: 'KYC allowlist',
    kind: 'template',
    blurb: 'Merkle-proof receiver allowlist.',
  },
};

/**
 * An id this map has never heard of shows the ID ITSELF, not "Unknown".
 *
 * A new substandard should look unlabelled, not broken — and the raw id is still the most useful
 * thing anyone can be told about it.
 */
export function substandardLabel(id: string | null | undefined): SubstandardLabel {
  if (!id) {
    return {
      label: 'Unregistered',
      kind: 'unknown',
      blurb: 'This token is in the registry but the backend has no substandard recorded for it.',
    };
  }
  return (
    LABELS[id] ?? { label: id, kind: 'unknown', blurb: `Substandard "${id}" has no display label yet.` }
  );
}

/** Ids offered as filter chips, in the order they are offered. */
export const LABELLED_SUBSTANDARDS = ['freeze-and-seize', 'rwa-token', 'dummy'] as const;
