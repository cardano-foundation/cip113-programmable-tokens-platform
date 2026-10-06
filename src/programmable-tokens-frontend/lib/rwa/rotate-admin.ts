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
