/**
 * Preparing an RWA admin rotation for signing — the one place the hardware-wallet guard sits.
 *
 * ## Why a module and not three lines in the component
 *
 * A rotation is the only admin action that needs TWO signatures, collected from two people who are
 * not in the same browser. That means the transaction's bytes are handed around, and every party
 * must sign THE SAME bytes. The two facts that make that work — which bytes, and who must sign —
 * are decisions, not rendering, so they live here where a test can reach them.
 *
 * ## What the guard is actually for, measured 2026-10-06 (T-093)
 *
 * It is NOT a fix. A real RotateAdmin transaction built by cardano-client-lib was measured through
 * the whole stack and its BODY is already canonical: `canonicaliseForHardwareWallets` returns the
 * input byte-for-byte, `checkCip21` reports zero body-scoped violations, and `required_signers`
 * is tag-258 encoded consistently with inputs and collateral.
 *
 * ⇒ So this call exists to REFUSE a future regression, not to repair today's output. If the Java
 * builder ever emits a non-canonical body — an unsorted map, an indefinite length, a bare set
 * beside a tagged one — a Ledger would reconstruct the body canonically, sign THAT hash, and hand
 * back a witness for a transaction we never submit. The device reports only "hash mismatch". This
 * refuses first, and names the field.
 *
 * ⚠ `validateTx` still reports "CBOR is not canonical" on a perfectly good rotation, and that is
 * expected and must not be "fixed": the non-canonical bytes are indefinite-length Plutus data
 * inside the redeemer, in the WITNESS SET. Body-only canonicalisation leaves them alone on purpose,
 * because rewriting the witness set strands `script_data_hash` and the node answers
 * `PPViewHashesDontMatch` (Evolution upstream #585 — the bug that broke the genesis ceremony). A
 * hardware wallet over CIP-30 hashes only the body, so this cannot affect what it signs.
 * `cardano-hw-cli` WOULD refuse it, so it is the wrong tool for probing a device here.
 */
import { Transaction } from "@evolution-sdk/evolution";
import { canonicaliseForHardwareWallets } from "../deployment/ceremony";
import { transactionHash } from "../tx/hash";

/**
 * Every credential the transaction itself says must sign, lowercased.
 *
 * ⛔ READ FROM THE TRANSACTION, NEVER ASSUMED TO BE TWO. The backend declares the fee payer, the
 * outgoing admin from the live datum, and the incoming admin, deduplicated — so it is two when the
 * connected wallet IS the datum's admin, and THREE whenever those differ, which is ordinary in any
 * HD wallet whose selected address is not the one originally registered. A UI that hardcodes two
 * would tell an operator the rotation is ready to submit while it is one signature short, and the
 * ledger would answer `MissingRequiredSigners` after both humans had already signed.
 */
export function requiredSignersOf(cborHex: string): readonly string[] {
  const tx = Transaction.fromCBORBytes(Buffer.from(cborHex, "hex"));
  const signers = tx.body.requiredSigners ?? [];
  // ⚠ `KeyHash.hash` is BYTES, not hex. It serialises as hex through JSON.stringify, which makes
  // `String(s.hash)` look right in a console and produce "[object Uint8Array]" in production.
  return signers.map((s) => Buffer.from(s.hash).toString("hex").toLowerCase());
}

/** A rotation that is ready to be hashed, relayed and signed — or a refusal naming what is wrong. */
export interface PreparedRotation {
  /** The bytes every party signs and the bytes that get submitted. Canonical, or we would not be here. */
  readonly canonicalHex: string;
  /** The hash those bytes have. Both admins should confirm this out of band before signing. */
  readonly txHash: string;
  /** Who must sign, from the transaction. Drives the collection UI; never a hardcoded count. */
  readonly requiredSigners: readonly string[];
  /** True when canonicalisation changed nothing, which is what T-093 measured and expects. */
  readonly bodyWasAlreadyCanonical: boolean;
}

/**
 * Canonicalise once, then never again.
 *
 * ⛔ CALL THIS EXACTLY ONCE, BEFORE THE TRANSACTION IS SHOWN, HASHED, RELAYED OR SIGNED. A canonical
 * body can have a different hash from the one the backend built, so the canonical form must be the
 * only form anyone ever sees. Canonicalising twice is harmless; canonicalising AFTER a witness has
 * been collected is not — that witness would target the earlier hash, and the mismatch surfaces at
 * submit, after both humans have done their part.
 *
 * Throws, with the offending field named, if the body cannot be made CIP-21 conformant.
 */
export function prepareRotation(unsignedCborHex: string): PreparedRotation {
  const clean = unsignedCborHex.trim().toLowerCase();
  const canonicalHex = canonicaliseForHardwareWallets(clean);
  const requiredSigners = requiredSignersOf(canonicalHex);
  if (requiredSigners.length < 2) {
    throw new Error(
      `This transaction declares ${requiredSigners.length} required signer(s), but an admin ` +
        `rotation needs at least two — the outgoing admin and the incoming one. The validator ` +
        `calls must_be_signed_by_credential for both, so a transaction declaring fewer cannot ` +
        `validate no matter who signs it. Rebuild it rather than collecting signatures for it.`
    );
  }
  return {
    canonicalHex,
    txHash: transactionHash(canonicalHex),
    requiredSigners,
    bodyWasAlreadyCanonical: canonicalHex === clean,
  };
}

/** The constructor index of `RotateAdmin` in `GlobalStateSpendAction`. */
const GS_ACTION_ROTATE_ADMIN = 9;

/** "GlobalState" — the asset name of the NFT that marks the global-state UTxO. */
const GLOBAL_STATE_ASSET_NAME_HEX = "476c6f62616c5374617465";

/**
 * Hex out of whatever the decoder hands back for a policy id or asset name.
 *
 * ⚠ THESE ARE Map KEYS AND THEY ARE NOT STRINGS. A `MultiAsset` decodes as a `Map` whose policy
 * keys are byte arrays and whose inner keys are `{_tag: "AssetName", bytes}` objects, so
 * `Object.entries` sees nothing and `String(key)` yields JSON. Reading them as strings is how this
 * silently returned null for a transaction that plainly carried the NFT.
 */
function hexOf(value: unknown): string | null {
  if (value instanceof Uint8Array) return Buffer.from(value).toString("hex");
  if (typeof value === "string") return value;
  // ⚠ THE FIELD NAME DEPENDS ON THE NEWTYPE, and guessing one of them costs a silent null:
  // `PolicyId` and `KeyHash` carry `.hash`, `AssetName` carries `.bytes`, and both are byte arrays
  // rather than hex strings. Measured against a real decoded transaction — reading only `.bytes`
  // made this return null for an output that plainly held the NFT.
  const inner = (value as { bytes?: unknown; hash?: unknown } | null);
  for (const candidate of [inner?.bytes, inner?.hash]) {
    if (typeof candidate === "string") return candidate;
    if (candidate instanceof Uint8Array) return Buffer.from(candidate).toString("hex");
  }
  return null;
}

/** What a rotation transaction is asking for, read out of the transaction itself. */
export interface RotationIntent {
  /** The credential the rotation moves authority TO, from the redeemer the validator reads. */
  readonly newAdminCredentialHash: string;
  /** The global-state NFT's policy id, from the output the redeemer points at. Null if unreadable. */
  readonly globalStatePolicyId: string | null;
  /** Everyone else the transaction requires — in a two-signer rotation, the outgoing admin. */
  readonly otherRequiredSigners: readonly string[];
}

/**
 * Decode a rotation so a human can see what they are signing.
 *
 * ⛔ FROM THE REDEEMER, NOT FROM THE OUTPUT DATUM. The redeemer is what
 * `global_state.ak` actually reads to decide who must sign — it compares
 * `new_admin_credential_hash` against `extra_signatories`. The output datum carries the same value,
 * but it is the builder's claim about the result; the redeemer is the validator's input. If the two
 * ever disagreed, the redeemer is the one that decides whether the transaction validates.
 *
 * ⚑ WHY THIS EXISTS AT ALL: without it the incoming admin is pasting a hex blob into a wallet. The
 * reassuring part is that they cannot be tricked into rotating to a THIRD party — the validator
 * requires a signature by `new_admin_credential_hash`, so if that is not them, their signature is
 * not the one needed and the transaction cannot validate. What this catches is the mistake that is
 * actually reachable: signing a rotation of the WRONG TOKEN.
 *
 * Returns null when the transaction is not a rotation, which is the normal answer on `/sign` — the
 * page signs whatever it is given, and most of that is not this.
 */
export function decodeRotationIntent(cborHex: string): RotationIntent | null {
  let tx: ReturnType<typeof Transaction.fromCBORBytes>;
  try {
    tx = Transaction.fromCBORBytes(Buffer.from(cborHex.trim(), "hex"));
  } catch {
    return null;
  }

  const redeemers = (tx.witnessSet as { redeemers?: { value?: unknown } } | undefined)?.redeemers?.value;
  if (!(redeemers instanceof Map)) return null;

  const asNumber = (v: unknown): number | null =>
    typeof v === "bigint" ? Number(v) : typeof v === "number" ? v : null;

  for (const entry of redeemers.values()) {
    const data = (entry as { data?: { index?: unknown; fields?: unknown[] } }).data;
    if (!data || !Array.isArray(data.fields)) continue;
    // Constr(0, [Int(globalStateOutputIndex), Constr(action, …)])
    const action = data.fields[1] as { index?: unknown; fields?: unknown[] } | undefined;
    if (!action || asNumber(action.index) !== GS_ACTION_ROTATE_ADMIN) continue;
    const raw = action.fields?.[0];
    if (!(raw instanceof Uint8Array)) continue;
    const newAdminCredentialHash = Buffer.from(raw).toString("hex").toLowerCase();

    const outputIndex = asNumber(data.fields[0]);
    let globalStatePolicyId: string | null = null;
    try {
      const outputs = (tx.body as unknown as { outputs?: readonly unknown[] }).outputs ?? [];
      const out = outputIndex === null ? undefined : outputs[outputIndex];
      // The global-state UTxO holds exactly one NFT, asset name "GlobalState", and its policy id is
      // what identifies this token's global state. Matching on the asset name as well means a
      // change of output shape yields null rather than some other policy id confidently displayed.
      const multiAsset = (out as {
        assets?: { multiAsset?: { map?: unknown } };
      } | undefined)?.assets?.multiAsset?.map;
      if (multiAsset instanceof Map) {
        for (const [policyKey, names] of multiAsset.entries()) {
          const policyId = hexOf(policyKey);
          if (!policyId || !/^[0-9a-f]{56}$/i.test(policyId)) continue;
          const assetNames =
            names instanceof Map ? [...names.keys()].map(hexOf) : [];
          if (assetNames.includes(GLOBAL_STATE_ASSET_NAME_HEX)) {
            globalStatePolicyId = policyId.toLowerCase();
            break;
          }
        }
      }
    } catch {
      globalStatePolicyId = null;
    }

    const otherRequiredSigners = requiredSignersOf(cborHex).filter(
      (s) => s !== newAdminCredentialHash
    );

    return { newAdminCredentialHash, globalStatePolicyId, otherRequiredSigners };
  }
  return null;
}
