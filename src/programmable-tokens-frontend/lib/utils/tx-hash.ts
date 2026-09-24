/**
 * Compute a transaction hash from CBOR hex.
 *
 * Replaces `resolveTxHash()` from `@meshsdk/core`.
 *
 * A Cardano transaction hash is blake2b-256 of the transaction BODY — element 0
 * of the `[body, witnessSet, isValid, auxiliaryData]` array — over the body's
 * bytes exactly as they appear, never a re-encoding of them.
 *
 * ## This was wrong, visibly, for a while
 *
 * Until 2026-09-21 this computed SHA-256 of the WHOLE unsigned transaction and
 * described itself as "a placeholder [that] works for display purposes during
 * development". It was not confined to development: five call sites in the KYC
 * and freeze-and-seize registration flows render its output to users as the
 * transaction's hash, and none of those values could ever match an explorer,
 * a wallet, or the chain.
 *
 * It is a thin wrapper now. The implementation lives in `lib/tx/hash.ts`
 * alongside the signature verification that depends on the same body bytes,
 * because two implementations of "which bytes are the body" is precisely the
 * disagreement that voids signatures.
 */

import { transactionHash } from "../tx/hash";

/**
 * Derive a transaction hash from CBOR hex. Signed or unsigned: the witness set
 * is not part of the hash, which is what lets a signature commit to it.
 *
 * Async only because its callers await it; the work is synchronous.
 *
 * Returns "" for input that is not a transaction, preserving the previous
 * contract — the call sites render it beside a label and an empty string shows
 * as blank rather than throwing inside a build step. A caller that needs to
 * distinguish "not a transaction" from "no hash yet" should use
 * `transactionHash` directly, which says what is wrong.
 */
export async function resolveTxHash(cborHex: string): Promise<string> {
  try {
    return transactionHash(cborHex);
  } catch {
    return "";
  }
}
