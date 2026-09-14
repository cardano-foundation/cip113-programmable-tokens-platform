/**
 * Collecting M-of-N signatures for a protocol-params upgrade.
 *
 * The exchange is deliberately storage-free (Giovanni, 2026-09-14): the assembler shares the
 * unsigned transaction hex, each signer signs it with their own wallet and hands back a witness
 * set hex, and this merges them. Nothing is persisted, so there is no shared draft to secure —
 * which matters because nothing in this platform authenticates anything, and a stored draft
 * would be one more unauthenticated write that changes later behaviour.
 *
 * ## A witness is checked, not counted
 *
 * The naive tool counts witness sets and calls M of them a quorum. That is wrong in two ways
 * that both produce a transaction the ledger rejects after everyone has done their part: the
 * same signer pasted twice looks like two, and a signature from someone outside the authority
 * looks like one. So every witness is reduced to the key hash that produced it — blake2b-224
 * of the vkey, which is how Cardano derives a key hash — and matched against the declared
 * member set.
 */
import { blake2b } from "@noble/hashes/blake2";
import { splitVkeyWitnesses, vkeyOfWitnessEntry, assembleSignedTxPreservingBody } from "../tx/witness-set";

/** Cardano key hashes are blake2b-224 (28 bytes) of the public key. */
export function keyHashOfVkey(vkeyHex: string): string {
  const vkey = Uint8Array.from(
    (vkeyHex.match(/../g) ?? []).map((b) => parseInt(b, 16)),
  );
  if (vkey.length !== 32) {
    throw new Error(`a vkey is 32 bytes; got ${vkey.length}`);
  }
  return Array.from(blake2b(vkey, { dkLen: 28 }))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

/** Every key hash that signed, in the order the witness set carries them. */
export function keyHashesInWitnessSet(witnessSetHex: string): string[] {
  const bytes = Uint8Array.from(
    (witnessSetHex.match(/../g) ?? []).map((b) => parseInt(b, 16)),
  );
  // A CIP-30 witness set is a map; key 0 holds the vkey witnesses. splitVkeyWitnesses works on
  // that value, so find it the same way the assembler does — by reusing the shared helpers
  // rather than re-implementing the CBOR walk a second time.
  const { entries } = splitVkeyWitnesses(extractKeyZeroValue(bytes));
  return entries.map((e) => keyHashOfVkey(vkeyOfWitnessEntry(e)));
}

/**
 * The key-0 value of a witness-set map.
 *
 * Kept small and explicit rather than pulled into the shared module: the assembler needs the
 * surrounding map to rebuild it, this only needs the one value.
 */
function extractKeyZeroValue(witnessSet: Uint8Array): Uint8Array {
  const major = (witnessSet[0] >> 5) & 0x07;
  if (major !== 5) {
    throw new Error(
      `a witness set is a CBOR map (major type 5); got major type ${major}. ` +
        "Did you paste a whole transaction instead of the witness set the wallet returned?",
    );
  }
  // The wallet's witness set for a partial sign carries key 0 and nothing else in practice;
  // anything more and we would need the full walk, which is what the assembler already does.
  if (witnessSet[1] !== 0x00) {
    throw new Error(
      "witness set does not begin with key 0 (vkey witnesses). This tool expects the witness " +
        "set a CIP-30 wallet returns from signTx(tx, true).",
    );
  }
  return witnessSet.slice(2);
}

export interface SignerCheck {
  witnessSetHex: string;
  keyHashes: string[];
  /** Hashes that are declared members. */
  members: string[];
  /** Hashes that are not — a signature from outside the authority. */
  strangers: string[];
}

export interface QuorumResult {
  required: number;
  /** Distinct declared members who signed. Duplicates collapse. */
  signed: string[];
  missing: string[];
  strangers: string[];
  satisfied: boolean;
  checks: SignerCheck[];
}

/**
 * Who has actually signed, against the declared authority.
 *
 * Duplicates collapse and strangers are reported rather than ignored: a stranger's witness in
 * the assembled transaction is not merely useless, it is a signature the ledger will weigh
 * against a quorum that does not include them.
 */
export function checkQuorum(
  witnessSetHexes: readonly string[],
  memberKeyHashes: readonly string[],
  required: number,
): QuorumResult {
  const members = new Set(memberKeyHashes.map((h) => h.toLowerCase()));
  const signed = new Set<string>();
  const strangers = new Set<string>();
  const checks: SignerCheck[] = [];

  for (const hex of witnessSetHexes) {
    const keyHashes = keyHashesInWitnessSet(hex);
    const mine: string[] = [];
    const theirs: string[] = [];
    for (const h of keyHashes) {
      if (members.has(h)) {
        mine.push(h);
        signed.add(h);
      } else {
        theirs.push(h);
        strangers.add(h);
      }
    }
    checks.push({ witnessSetHex: hex, keyHashes, members: mine, strangers: theirs });
  }

  const missing = [...members].filter((h) => !signed.has(h));
  return {
    required,
    signed: [...signed],
    missing,
    strangers: [...strangers],
    satisfied: signed.size >= required && strangers.size === 0,
    checks,
  };
}

/**
 * Merge every collected witness into the unsigned transaction.
 *
 * Applied one at a time through the same body-preserving assembler the wallet path uses, so a
 * transaction signed by five people is assembled exactly as one signed by two — and the body
 * bytes, which every one of those signatures commits to, are never re-encoded.
 */
export function assembleUpgradeTx(
  unsignedTxHex: string,
  witnessSetHexes: readonly string[],
): string {
  if (witnessSetHexes.length === 0) {
    throw new Error("no witnesses to assemble");
  }
  return witnessSetHexes.reduce(
    (tx, witnessSet) => assembleSignedTxPreservingBody(tx, witnessSet),
    unsignedTxHex,
  );
}
