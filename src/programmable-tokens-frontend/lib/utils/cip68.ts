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
  "security-token",
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
  return prefix + assetNameHex;
}

/**
 * The user-token label implied by a requested supply: exactly one is an NFT (222), anything
 * else is fungible (333).
 *
 * MUST match `Cip68.userTokenLabel` in the Java backend — the backend picks the label that
 * actually goes on chain, and the frontend only re-derives it to record the right asset name
 * locally. If the two ever disagree the recorded name stops resolving.
 */
export function userTokenLabelFor(quantity: string | number | bigint): 222 | 333 {
  // Compared as a normalised decimal string rather than via BigInt: supplies can exceed
  // Number.MAX_SAFE_INTEGER, and this module is compiled below the ES2020 target that BigInt
  // needs. Only "is it exactly one" matters, so digits alone answer it.
  const digits = String(quantity).trim().replace(/^\+/, "").replace(/^0+(?=\d)/, "");
  return digits === "1" ? 222 : 333;
}

/** Check if asset name hex has CIP-67 label 333 (FT user token). */
export function isCIP68FT(assetNameHex: string): boolean {
  if (!assetNameHex || assetNameHex.length <= CIP67_PREFIX_LENGTH) return false;
  return assetNameHex.substring(0, CIP67_PREFIX_LENGTH).toLowerCase() === CIP67_LABEL_333;
}
