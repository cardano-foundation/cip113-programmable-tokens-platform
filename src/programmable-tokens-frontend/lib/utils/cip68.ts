/**
 * CIP-67/CIP-68 asset name utilities.
 *
 * CIP-67 defines 4-byte label prefixes on asset names to indicate token purpose.
 * CIP-68 uses these labels to distinguish user tokens (333) from reference tokens (100).
 */

// CIP-67 label prefixes (4 bytes = 8 hex chars)
const CIP67_LABEL_333 = "0014df10"; // FT user token (holds value)
const CIP67_LABEL_100 = "000643b0"; // Reference token (holds metadata)
const CIP67_LABEL_222 = "000de140"; // NFT token
const CIP67_PREFIX_LENGTH = 8;

/**
 * Substandards whose registration actually mints the CIP-68 pair.
 *
 * Single source of truth for the wizard: `token-details-step` hides the CIP-68 form for
 * anything not listed here, so the fields can never be collected and then dropped. The Java
 * backend enforces the same list — `KycSubstandardHandler` and `KycExtendedSubstandardHandler`
 * reject a non-null `cip68Metadata` outright rather than ignoring it — so a hand-rolled API
 * call gets a clear error instead of a token whose label promises metadata that was never
 * written.
 */
export const CIP68_SUPPORTED_SUBSTANDARDS = [
  "dummy",
  "freeze-and-seize",
  "rwa-token",
] as const;

/** Whether the given substandard/flow id supports CIP-68 registration. */
export function supportsCIP68(substandardId: string | null | undefined): boolean {
  return (
    !!substandardId &&
    (CIP68_SUPPORTED_SUBSTANDARDS as readonly string[]).includes(substandardId)
  );
}

const LABEL_MAP: Record<string, { label: number; name: string }> = {
  [CIP67_LABEL_333]: { label: 333, name: "FT" },
  [CIP67_LABEL_100]: { label: 100, name: "Reference" },
  [CIP67_LABEL_222]: { label: 222, name: "NFT" },
};

export interface CIP68Info {
  /** Whether this asset name has a CIP-67 label prefix */
  isCIP68: boolean;
  /** Numeric label (333, 100, 222) or null */
  label: number | null;
  /** Human-readable label name ("FT", "Reference", "NFT") or null */
  labelName: string | null;
  /** Asset name hex WITHOUT the label prefix */
  rawAssetNameHex: string;
  /** Human-readable decoded name (prefix stripped, UTF-8 decoded) */
  displayName: string;
}

/** Decode hex to UTF-8 string, returning hex on failure */
function hexDecode(hex: string): string {
  if (!hex) return "";
  try {
    const bytes = new Uint8Array(
      hex.match(/.{1,2}/g)?.map((b) => parseInt(b, 16)) || []
    );
    const decoder = new TextDecoder("utf-8", { fatal: true });
    return decoder.decode(bytes);
  } catch {
    return hex;
  }
}

/** Parse an asset name hex string and detect CIP-67 labels. */
export function parseCIP68AssetName(assetNameHex: string): CIP68Info {
  if (!assetNameHex || assetNameHex.length <= CIP67_PREFIX_LENGTH) {
    return {
      isCIP68: false,
      label: null,
      labelName: null,
      rawAssetNameHex: assetNameHex || "",
      displayName: hexDecode(assetNameHex || ""),
    };
  }

  const prefix = assetNameHex.substring(0, CIP67_PREFIX_LENGTH).toLowerCase();
  const entry = LABEL_MAP[prefix];

  if (entry) {
    const raw = assetNameHex.substring(CIP67_PREFIX_LENGTH);
    return {
      isCIP68: true,
      label: entry.label,
      labelName: entry.name,
      rawAssetNameHex: raw,
      displayName: hexDecode(raw),
    };
  }

  return {
    isCIP68: false,
    label: null,
    labelName: null,
    rawAssetNameHex: assetNameHex,
    displayName: hexDecode(assetNameHex),
  };
}

/** Strip CIP-67 label prefix from hex asset name. Returns raw hex without prefix. */
export function stripCIP67Label(assetNameHex: string): string {
  if (!assetNameHex || assetNameHex.length <= CIP67_PREFIX_LENGTH) return assetNameHex || "";
  const prefix = assetNameHex.substring(0, CIP67_PREFIX_LENGTH).toLowerCase();
  return prefix in LABEL_MAP ? assetNameHex.substring(CIP67_PREFIX_LENGTH) : assetNameHex;
}

/** Decode asset name hex to display string, stripping CIP-67 prefix if present. */
export function decodeAssetNameDisplay(assetNameHex: string): string {
  return parseCIP68AssetName(assetNameHex).displayName;
}

/** Check if asset name hex has CIP-67 label 100 (reference token). */
export function isReferenceToken(assetNameHex: string): boolean {
  if (!assetNameHex || assetNameHex.length <= CIP67_PREFIX_LENGTH) return false;
  return assetNameHex.substring(0, CIP67_PREFIX_LENGTH).toLowerCase() === CIP67_LABEL_100;
}

/**
 * Prefix an asset name hex with a CIP-67 label.
 *
 * Mirrors the Java backend's `Cip68.labeledAssetName` and the SDK's `labeledAssetName`; the
 * three agree on 100/222/333, which is all this platform mints.
 */
export function labelAssetNameHex(label: 100 | 222 | 333, assetNameHex: string): string {
  const prefix = Object.entries(LABEL_MAP).find(([, v]) => v.label === label)?.[0];
  if (!prefix) throw new Error(`unsupported CIP-67 label: ${label}`);
  // A blank base yields a name that is nothing but the 4-byte label — and `parseCIP68AssetName`
  // above rejects any name of 8 hex chars or fewer as unlabelled, so such a token would be
  // permanently unresolvable as a CIP-68 pair: the (100) and the (333) would each read as
  // "unlabelled" and neither could derive the other. `Cip68.labeledAssetName` in the Java backend
  // refuses this; the mirror has to agree, or the wizard builds a name the backend will reject
  // (or worse, an SDK-built transaction mints one nothing can read).
  if (!assetNameHex || !assetNameHex.trim()) {
    throw new Error(
      "CIP-68 needs a non-empty base asset name: a label-only name is not resolvable as a " +
        "CIP-68 pair by any consumer, including this platform's own readers."
    );
  }
  // The ledger caps asset names at 32 bytes and the label eats 4 of them.
  const labeled = prefix + assetNameHex;
  if (labeled.length > 64) {
    throw new Error(
      `asset name is too long for CIP-68: the 4-byte (${label}) label plus a ` +
        `${assetNameHex.length / 2}-byte name exceeds the ledger's 32-byte limit. ` +
        "Use a name of at most 28 bytes."
    );
  }
  return labeled;
}

/**
 * The user-token label for a substandard, given a requested supply.
 *
 * MUST match `Cip68.userTokenLabel` in the Java backend — the backend picks the label that
 * actually goes on chain, and the frontend only re-derives it to record the right asset name
 * locally. If the two ever disagree the recorded name stops resolving.
 *
 * The rule is NOT "one unit means 222". `(222)` asserts non-fungibility — one unit, forever — and
 * only a validator that bounds LIFETIME issuance can keep that promise. None of the three
 * substandards does, so in practice every user token this platform mints is `(333)`; see
 * `userTokenLabelForSubstandard`.
 *
 * @param quantity the supply — a lifetime ceiling only when `lifetimeSupplyCapped`
 * @param lifetimeSupplyCapped whether a validator bounds total lifetime issuance at `quantity`
 */
export function userTokenLabelFor(
  quantity: string | number | bigint,
  lifetimeSupplyCapped = false
): 222 | 333 {
  if (!lifetimeSupplyCapped) return 333;
  // Compared as a normalised decimal string rather than via BigInt: supplies can exceed
  // Number.MAX_SAFE_INTEGER, and this module is compiled below the ES2020 target that BigInt
  // needs. Only "is it exactly one" matters, so digits alone answer it.
  const digits = String(quantity).trim().replace(/^\+/, "").replace(/^0+(?=\d)/, "");
  return digits === "1" ? 222 : 333;
}

/**
 * The label a given substandard will apply — always `(333)`, for every substandard and every
 * quantity. Keeps the capped/uncapped decision in one place rather than at each call site.
 *
 * `rwa-token` used to be treated as capped, on the grounds that its GlobalState
 * `mintable_amount` only ever decreases. It does not: `global_state.ak` computes
 * `remaining = mintable_amount - minted_amount` with a signed `minted_amount`, so a burn passes a
 * negative and restores the allowance. `mint 1 → burn 1 → mint 1` is accepted, which is exactly
 * the lifetime supply of two that `(222)` promises cannot happen. `dummy` and `freeze-and-seize`
 * cap nothing at all. MUST match `Cip68.userTokenLabel` and the rwa-token genesis path in
 * the Java backend — the backend picks the label that actually goes on chain, and it participates
 * in the policy id, so a disagreement means the recorded asset name stops resolving.
 */
export function userTokenLabelForSubstandard(
  _substandardId: string | null | undefined,
  quantity: string | number | bigint
): 222 | 333 {
  return userTokenLabelFor(quantity, /* lifetimeSupplyCapped */ false);
}

/**
 * Field ceilings for the CIP-68 metadata form, mirroring `Cip68`'s per-field limits in the Java
 * backend. The datum rides inside a transaction bounded at 16384 bytes, and the tightest measured
 * path (the rwa-token mint) leaves only ~1.4 KB of headroom — so these are enforced on the
 * server regardless. They exist here to stop the user typing 2 KB of description and only finding
 * out after they have paid for a blacklist init or a genesis.
 *
 * Nothing is ever truncated, on either side: the datum is the token's permanent on-chain
 * description, and silently shipping half a sentence would be worse than an error.
 */
export const CIP68_FIELD_MAX_LENGTHS = {
  name: 64,
  description: 256,
  ticker: 16,
  url: 128,
  logo: 128,
} as const;

/** The CIP-68 metadata fields the wizard and the SDK route both collect. */
export interface Cip68MetadataInput {
  name?: string;
  description?: string;
  ticker?: string;
  decimals?: number | string;
  url?: string;
  logo?: string;
}

/** Human-readable field labels, so an error names what the user typed. */
const FIELD_LABELS: Record<keyof typeof CIP68_FIELD_MAX_LENGTHS, string> = {
  name: "Display name",
  description: "Description",
  ticker: "Ticker",
  url: "URL",
  logo: "Logo URI",
};

/**
 * Validate CIP-68 metadata against the same ceilings the Java backend enforces.
 *
 * An input `maxLength` attribute is a UI convenience, not an enforcement point: it does not apply
 * to state restored from a persisted wizard, to a value set programmatically, or to the SDK route
 * in `cip113-context`, which never renders these inputs at all. Both call sites run this instead,
 * so an over-long field is caught before a transaction is built rather than after the user has
 * paid for a blacklist init.
 *
 * Nothing is truncated, here or on the server: the datum is the token's permanent on-chain
 * description.
 *
 * @returns a map of field name to error message; empty when the metadata is acceptable
 */
export function validateCip68Metadata(
  metadata: Cip68MetadataInput | null | undefined
): Record<string, string> {
  const errors: Record<string, string> = {};
  if (!metadata) return errors;

  if (!metadata.name || !metadata.name.trim()) {
    errors.name = "Display name is required for CIP-68 metadata";
  }

  (Object.keys(CIP68_FIELD_MAX_LENGTHS) as (keyof typeof CIP68_FIELD_MAX_LENGTHS)[]).forEach(
    (field) => {
      const value = metadata[field];
      const max = CIP68_FIELD_MAX_LENGTHS[field];
      if (typeof value === "string" && value.length > max) {
        errors[field] =
          `${FIELD_LABELS[field]} is ${value.length} characters, over the ${max}-character ` +
          "limit. The metadata rides on chain inside a transaction bounded at 16384 bytes; " +
          "shorten it — nothing is truncated for you.";
      }
    }
  );

  if (metadata.decimals !== undefined && metadata.decimals !== null && metadata.decimals !== "") {
    const dec = typeof metadata.decimals === "number"
      ? metadata.decimals
      : parseInt(metadata.decimals, 10);
    if (isNaN(dec) || dec < 0 || dec > 19) {
      errors.decimals = "Decimals must be 0-19";
    }
  }

  return errors;
}

/**
 * Throwing form of {@link validateCip68Metadata}, for non-form call sites (the SDK route) that
 * have no error map to render into.
 */
export function assertValidCip68Metadata(metadata: Cip68MetadataInput | null | undefined): void {
  const errors = validateCip68Metadata(metadata);
  const messages = Object.values(errors);
  if (messages.length > 0) {
    throw new Error(`CIP-68 metadata is not acceptable: ${messages.join(" ")}`);
  }
}

/** Check if asset name hex has CIP-67 label 333 (FT user token). */
export function isCIP68FT(assetNameHex: string): boolean {
  if (!assetNameHex || assetNameHex.length <= CIP67_PREFIX_LENGTH) return false;
  return assetNameHex.substring(0, CIP67_PREFIX_LENGTH).toLowerCase() === CIP67_LABEL_333;
}
