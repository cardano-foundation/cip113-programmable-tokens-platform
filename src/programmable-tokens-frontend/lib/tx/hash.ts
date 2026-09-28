/**
 * The transaction hash, and whether a witness actually signed it.
 *
 * Both halves of this file exist because of one fact: an Ed25519 vkey witness
 * signs the blake2b-256 hash of the transaction BODY — not the transaction, not
 * the witness set, not a re-encoding of any of them. Everything else here
 * follows from taking that literally.
 *
 * ## Why the body bytes are sliced, never re-encoded
 *
 * The body's hash is taken over its bytes AS THEY ARE, so a decode/re-encode
 * round trip that produces equivalent-but-different CBOR produces a different
 * hash and silently invalidates every signature already collected. That is the
 * same reason `assembleSignedTxPreservingBody` splices rather than rebuilds, and
 * this module locates the body exactly as that assembler does.
 *
 * ## Why verification is not optional
 *
 * `checkQuorum` reduces a witness to the key hash that produced it and matches
 * that against the declared members. That answers "does this witness CLAIM to be
 * from a declared key". It does not answer "did that key sign THIS transaction",
 * because nothing in it inspects the signature. A witness carrying a declared
 * vkey beside sixty-four bytes of noise passes the claim check and fails at the
 * ledger.
 *
 * The distinction is the entire point when signatures are collected to prove
 * that participants control the keys they declared: an unverified witness proves
 * possession of a PUBLIC key, which is not a secret and not control of anything.
 */

import { CBOR as EvoCBOR } from "@evolution-sdk/evolution";
import { blake2b } from "@noble/hashes/blake2";
import { ed25519 } from "@noble/curves/ed25519.js";
import { splitVkeyWitnesses } from "./witness-set";

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.trim().toLowerCase();
  if (clean.length % 2 !== 0 || /[^0-9a-f]/.test(clean)) {
    throw new Error("not hex");
  }
  return Uint8Array.from((clean.match(/../g) ?? []).map((b) => parseInt(b, 16)));
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/**
 * The exact bytes of the transaction body, as a slice of the input.
 *
 * A Cardano transaction is `[body, witnessSet, isValid, auxiliaryData]`. The
 * body is element 0, and this returns its span verbatim — a subarray, so the
 * bytes are the input's own and cannot have been normalised on the way through.
 */
export function transactionBodyBytes(txHex: string): Uint8Array {
  const tx = hexToBytes(txHex);
  if (tx.length === 0) throw new Error("empty transaction");
  // 0x84 is a 4-element definite array. Signed and unsigned transactions alike
  // carry four elements; a different header means this is not a transaction, and
  // guessing would produce a confident hash of the wrong bytes.
  if (tx[0] !== 0x84) {
    throw new Error(
      `transaction CBOR must start with 0x84 (4-element array), got 0x${tx[0]?.toString(16)}`
    );
  }
  const afterBody = EvoCBOR.decodeItemWithOffset(tx, 1).newOffset;
  return tx.subarray(1, afterBody);
}

/**
 * The transaction hash: blake2b-256 of the body bytes.
 *
 * This is the identity the chain knows the transaction by, the value an explorer
 * shows, and — the reason it is here — the message every vkey witness signs. It
 * is therefore also what co-signers should compare out of band before signing:
 * two parties holding the same hash are holding the same transaction.
 */
export function transactionHash(txHex: string): string {
  return bytesToHex(blake2b(transactionBodyBytes(txHex), { dkLen: 32 }));
}

/** The `[vkey, signature]` pair of one witness entry, or null if it is not that shape. */
function vkeyAndSignature(
  entry: Uint8Array
): { vkey: Uint8Array; signature: Uint8Array } | null {
  try {
    const first = EvoCBOR.decodeItemWithOffset(entry, 1);
    if (!(first.item instanceof Uint8Array)) return null;
    const second = EvoCBOR.decodeItemWithOffset(entry, first.newOffset);
    if (!(second.item instanceof Uint8Array)) return null;
    return { vkey: first.item, signature: second.item };
  } catch {
    return null;
  }
}

export interface VerifiedWitness {
  /** The public key, hex. */
  vkeyHex: string;
  /** blake2b-224 of the vkey — the key hash the ledger and the multisig datum use. */
  keyHash: string;
  /** Whether this signature verifies against this transaction's body hash. */
  valid: boolean;
  /** Set when the entry could not be checked at all, rather than checked and refused. */
  problem?: string;
}

/**
 * Verify every vkey witness in a witness set against a transaction.
 *
 * A witness that cannot be parsed is reported with `problem` and `valid: false`
 * rather than skipped: a witness set that yields FEWER results than it has
 * entries would otherwise read as a smaller, cleaner set than it is, and the
 * entry that vanished is exactly the malformed one worth seeing.
 */
export function verifyWitnessSet(
  txHex: string,
  witnessSetHex: string
): VerifiedWitness[] {
  const bodyHash = blake2b(transactionBodyBytes(txHex), { dkLen: 32 });
  const ws = hexToBytes(witnessSetHex);

  // A CIP-30 witness set is a map whose key 0 holds the vkey witnesses. Reuse the
  // splitter the assembler uses so tag-258 sets and indefinite-length arrays are
  // handled identically in both places rather than twice, differently.
  // This consumes text a human pasted from a chat window, so a truncated or
  // mistyped witness set is the NORMAL failure, not an exceptional one. Evolution's
  // decoder throws a bare `CBORError: Insufficient data for byte string`, which
  // tells a signer nothing about what to do; name it instead.
  let key0: Uint8Array | null;
  try {
    key0 = vkeyWitnessValue(ws);
  } catch (e) {
    throw new Error(
      "this does not parse as a witness set — check the whole value was copied, " +
        "with no line breaks or truncation " +
        `(${e instanceof Error ? e.message : String(e)})`
    );
  }
  if (key0 === null) return [];

  const { entries } = splitVkeyWitnesses(key0);
  return entries.map((entry) => {
    const pair = vkeyAndSignature(entry);
    if (!pair) {
      return {
        vkeyHex: "",
        keyHash: "",
        valid: false,
        problem: "entry is not a [vkey, signature] pair",
      };
    }
    const { vkey, signature } = pair;
    if (vkey.length !== 32) {
      return {
        vkeyHex: bytesToHex(vkey),
        keyHash: "",
        valid: false,
        problem: `a vkey is 32 bytes; got ${vkey.length}`,
      };
    }
    if (signature.length !== 64) {
      return {
        vkeyHex: bytesToHex(vkey),
        keyHash: bytesToHex(blake2b(vkey, { dkLen: 28 })),
        valid: false,
        problem: `an Ed25519 signature is 64 bytes; got ${signature.length}`,
      };
    }
    let valid = false;
    try {
      valid = ed25519.verify(signature, bodyHash, vkey);
    } catch {
      // A malformed point or scalar throws rather than returning false. That is a
      // failed verification, not an exception the caller should handle.
      valid = false;
    }
    return {
      vkeyHex: bytesToHex(vkey),
      keyHash: bytesToHex(blake2b(vkey, { dkLen: 28 })),
      valid,
    };
  });
}

/** The raw value under key 0 of a witness-set map, or null when absent. */
function vkeyWitnessValue(ws: Uint8Array): Uint8Array | null {
  const initial = ws[0];
  if (initial === undefined || initial >> 5 !== 5) {
    throw new Error(`witness set is not a CBOR map (major type ${initial === undefined ? "?" : initial >> 5})`);
  }
  const header = EvoCBOR.decodeItemWithOffset(ws, 0);
  // decodeItemWithOffset returns the whole map; walk it manually to keep the raw
  // byte span of the value, which splitVkeyWitnesses needs unmodified.
  void header;
  const count = initial & 0x1f;
  let off: number;
  let pairs: number;
  if (count < 24) {
    pairs = count;
    off = 1;
  } else if (count === 24) {
    pairs = ws[1]!;
    off = 2;
  } else if (count === 25) {
    pairs = (ws[1]! << 8) | ws[2]!;
    off = 3;
  } else {
    throw new Error("witness set map header too large to be a witness set");
  }
  for (let i = 0; i < pairs; i++) {
    const key = EvoCBOR.decodeItemWithOffset(ws, off);
    const value = EvoCBOR.decodeItemWithOffset(ws, key.newOffset);
    const k = typeof key.item === "bigint" ? Number(key.item) : key.item;
    if (k === 0) return ws.subarray(key.newOffset, value.newOffset);
    off = value.newOffset;
  }
  return null;
}
