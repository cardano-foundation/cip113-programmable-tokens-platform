/**
 * CBOR surgery on a Cardano transaction's witness set.
 *
 * Extracted verbatim from `contexts/wallet-context.tsx`, where it was written for the
 * dual-signature admin rotation, so that multisig upgrade assembly uses the SAME
 * implementation rather than a second one that drifts. Behaviour unchanged; only the location
 * and which functions are exported.
 *
 * Why this is hand-rolled CBOR rather than decode-and-re-encode is in the comment below and is
 * load-bearing: re-encoding changes bytes the body hash and `script_integrity_hash` are taken
 * over, which invalidates every signature already present.
 */

import { CBOR as EvoCBOR } from "@evolution-sdk/evolution";
/**
 * Body-preserving signed-tx assembler for CIP-30 wallets.
 *
 * Evolution SDK's `addVKeyWitnessesHex` decodes the entire transaction and
 * re-encodes it through a format-reconciliation pass. For Conway txs that mix
 * mints + a RegCert + a publish redeemer, the re-encoded body is not byte-for-
 * byte identical to the input, which invalidates the wallet's signature
 * (`InvalidWitnessesUTXOW`).
 *
 * To avoid that we splice at the CBOR-byte level:
 *   1. Locate the byte ranges of body and witness-set in the unsigned tx's
 *      outer `[body, witnessSet, isValid, auxiliaryData]` array.
 *   2. Extract the wallet's vkey-witness value (key 0 — typically
 *      `Tag(258, [[vkey, sig], …])`) as raw bytes from the wallet's witness
 *      set CBOR.
 *   3. Prepend the new (key=0, value=<wallet bytes>) entry to the unsigned
 *      tx's witness-set map, bumping the entry count by one.
 *   4. Concatenate `0x84 + body + new witness-set + isValid + auxData` —
 *      everything except witness-set is kept verbatim, so both the body hash
 *      (signature target) and the witness-set bytes that script_integrity_hash
 *      commits to (datums at key 4, redeemers at key 5) remain unchanged.
 *
 * When the unsigned tx's witness set ALREADY carries key 0 — which is exactly
 * what happens on the second leg of a dual-signature action like RotateAdmin,
 * where the outgoing admin has already partially signed — the two vkey-witness
 * arrays are UNIONed instead (see `mergeVkeyWitnessValues`). Merging touches
 * only the key-0 value; script_integrity_hash commits to redeemers (key 5) and
 * datums (key 4), never to vkey witnesses, so every earlier signature stays
 * valid.
 */
function decodeHeader(
  buf: Uint8Array,
  offset: number,
  expectedMajor: number,
  what: string,
): { count: number; bodyStart: number } {
  const firstByte = buf[offset];
  const majorType = (firstByte >> 5) & 0x07;
  if (majorType !== expectedMajor) {
    throw new Error(
      `expected CBOR ${what} (major type ${expectedMajor}), got major type ${majorType} at offset ${offset}`,
    );
  }
  const additionalInfo = firstByte & 0x1f;
  if (additionalInfo < 24) return { count: additionalInfo, bodyStart: offset + 1 };
  if (additionalInfo === 24) return { count: buf[offset + 1], bodyStart: offset + 2 };
  if (additionalInfo === 25) return { count: (buf[offset + 1] << 8) | buf[offset + 2], bodyStart: offset + 3 };
  if (additionalInfo === 26) {
    return {
      count: (buf[offset + 1] * 0x1000000) + (buf[offset + 2] << 16) + (buf[offset + 3] << 8) + buf[offset + 4],
      bodyStart: offset + 5,
    };
  }
  // 31 = indefinite length. Not emitted by any Cardano tooling for these
  // structures, and supporting it would mean tracking break markers.
  throw new Error(`unsupported CBOR ${what} count encoding: additionalInfo=${additionalInfo}`);
}

function decodeMapHeader(buf: Uint8Array, offset: number): { count: number; bodyStart: number } {
  return decodeHeader(buf, offset, 5, "map");
}

function encodeHeader(major: number, count: number): Uint8Array {
  const m = major << 5;
  if (count < 24) return Uint8Array.of(m | count);
  if (count < 256) return Uint8Array.of(m | 24, count);
  if (count < 65536) return Uint8Array.of(m | 25, (count >> 8) & 0xff, count & 0xff);
  return Uint8Array.of(m | 26,
    (count >> 24) & 0xff, (count >> 16) & 0xff, (count >> 8) & 0xff, count & 0xff);
}

function encodeMapHeader(count: number): Uint8Array {
  return encodeHeader(5, count);
}

/** Conway wraps set-valued fields in tag 258. A witness-set key-0 value is
 *  therefore either `[[vkey, sig], …]` or `258([[vkey, sig], …])`. */
const CBOR_TAG_258 = Uint8Array.of(0xd9, 0x01, 0x02);

function hasTag258(buf: Uint8Array): boolean {
  return buf.length >= 3 && buf[0] === 0xd9 && buf[1] === 0x01 && buf[2] === 0x02;
}

/** Split a key-0 (vkey witness) value into its individual entries, keeping each
 *  entry's bytes VERBATIM. Re-encoding a signature is never necessary and would
 *  risk perturbing it, so the merge below concatenates these slices as-is.
 *
 *  Handles both definite- and INDEFINITE-length arrays. The latter is not
 *  hypothetical: Plutus Data in this codebase's own redeemers serialises as
 *  `d8799f…ff`, and a wallet is free to emit key 0 the same way. Rejecting it
 *  would break counter-signing for exactly the wallets that do. */
export function splitVkeyWitnesses(value: Uint8Array): { tagged: boolean; entries: Uint8Array[] } {
  const tagged = hasTag258(value);
  const arrayStart = tagged ? 3 : 0;
  const initial = value[arrayStart];
  if ((initial >> 5) !== 4) {
    throw new Error(`vkey witness value is not a CBOR array (major type ${initial >> 5})`);
  }
  const entries: Uint8Array[] = [];
  if ((initial & 0x1f) === 31) {
    // Indefinite: entries run until the 0xff break marker.
    let off = arrayStart + 1;
    while (off < value.length && value[off] !== 0xff) {
      const { newOffset } = EvoCBOR.decodeItemWithOffset(value, off);
      entries.push(value.subarray(off, newOffset));
      off = newOffset;
    }
    if (off >= value.length) throw new Error("unterminated indefinite-length vkey witness array");
    return { tagged, entries };
  }
  const { count, bodyStart } = decodeHeader(value, arrayStart, 4, "array");
  let off = bodyStart;
  for (let i = 0; i < count; i++) {
    const { newOffset } = EvoCBOR.decodeItemWithOffset(value, off);
    entries.push(value.subarray(off, newOffset));
    off = newOffset;
  }
  return { tagged, entries };
}

/** The vkey of a `[vkey, signature]` witness entry, as a hex string — the
 *  identity we de-duplicate on. The ledger treats key 0 as a SET, so a wallet
 *  that re-signs an already-signed tx must not leave two entries for one key.
 *
 *  Falls back to the entry's full bytes if the first element does not decode as
 *  a byte string; that still de-duplicates the realistic case (Ed25519 is
 *  deterministic, so the same key over the same body yields identical bytes)
 *  without depending on the CBOR decoder's exact return type. */
export function vkeyOfWitnessEntry(entry: Uint8Array): string {
  try {
    const { bodyStart } = decodeHeader(entry, 0, 4, "array");
    const { item } = EvoCBOR.decodeItemWithOffset(entry, bodyStart);
    if (item instanceof Uint8Array) return Buffer.from(item).toString("hex");
  } catch {
    // Fall through to whole-entry identity.
  }
  return Buffer.from(entry).toString("hex");
}

/** Union two key-0 values, preserving order (existing witnesses first) and
 *  dropping any incoming entry whose vkey is already present. */
function mergeVkeyWitnessValues(existing: Uint8Array, incoming: Uint8Array): Uint8Array {
  const a = splitVkeyWitnesses(existing);
  const b = splitVkeyWitnesses(incoming);

  const seen = new Set<string>();
  const merged: Uint8Array[] = [];
  for (const entry of [...a.entries, ...b.entries]) {
    const vkey = vkeyOfWitnessEntry(entry);
    if (seen.has(vkey)) continue;
    seen.add(vkey);
    merged.push(entry);
  }

  const parts: Uint8Array[] = [];
  // Keep the tag if either side had it — dropping it on a Conway tx would change
  // how the ledger reads the field.
  if (a.tagged || b.tagged) parts.push(CBOR_TAG_258);
  parts.push(encodeHeader(4, merged.length));
  parts.push(...merged);
  return Buffer.concat(parts.map((p) => Buffer.from(p)));
}

export function assembleSignedTxPreservingBody(unsignedTxHex: string, walletWitnessSetHex: string): string {
  const txBytes = Buffer.from(unsignedTxHex, "hex");
  if (txBytes[0] !== 0x84) {
    throw new Error(
      `unsigned tx CBOR must start with 0x84 (4-element array), got 0x${txBytes[0]?.toString(16)}`
    );
  }
  const afterBody = EvoCBOR.decodeItemWithOffset(txBytes, 1).newOffset;
  const bodyBytes = txBytes.subarray(1, afterBody);
  const afterWs = EvoCBOR.decodeItemWithOffset(txBytes, afterBody).newOffset;
  const restBytes = txBytes.subarray(afterWs);

  const walletWsBytes = Buffer.from(walletWitnessSetHex, "hex");
  const walletHdr = decodeMapHeader(walletWsBytes, 0);
  // Returning the UNSIGNED tx here would be indistinguishable from a signed one to
  // every caller, and it would be submitted. In a mempool-chained batch that
  // surfaces as a failure at index i with changes 0..i-1 already on chain. There is
  // no legitimate case where "the wallet signed" means "no vkey witnesses were
  // added", so fail loudly.
  if (walletHdr.count === 0) {
    throw new Error(
      "wallet returned an empty witness set — nothing was signed. If a signing "
      + "dialog appeared, the wallet may have declined to sign for any key this "
      + "transaction requires.",
    );
  }

  let walkOff = walletHdr.bodyStart;
  let walletKey0ValueStart = -1;
  let walletKey0ValueEnd = -1;
  for (let i = 0; i < walletHdr.count; i++) {
    const { item: keyVal, newOffset: afterKey } =
      EvoCBOR.decodeItemWithOffset(walletWsBytes, walkOff);
    const { newOffset: afterValue } =
      EvoCBOR.decodeItemWithOffset(walletWsBytes, afterKey);
    if (typeof keyVal === "bigint" ? keyVal === BigInt(0) : keyVal === 0) {
      walletKey0ValueStart = afterKey;
      walletKey0ValueEnd = afterValue;
      break;
    }
    walkOff = afterValue;
  }
  if (walletKey0ValueStart < 0) {
    throw new Error(
      "wallet returned a witness set with no vkey witnesses (key 0) — nothing was "
      + "signed. Submitting this would send an unsigned transaction.",
    );
  }
  const walletKey0ValueBytes = walletWsBytes.subarray(walletKey0ValueStart, walletKey0ValueEnd);

  const oldWsHdr = decodeMapHeader(txBytes, afterBody);
  const oldWsBodyBytes = txBytes.subarray(oldWsHdr.bodyStart, afterWs);

  // Locate an existing key-0 (vkey witnesses) entry, if any.
  let existingKey0EntryStart = -1;   // start of the KEY item
  let existingKey0ValueStart = -1;
  let existingKey0ValueEnd = -1;
  let scanOff = oldWsHdr.bodyStart;
  for (let i = 0; i < oldWsHdr.count; i++) {
    const { item: keyVal, newOffset: afterKey } = EvoCBOR.decodeItemWithOffset(txBytes, scanOff);
    const { newOffset: afterVal } = EvoCBOR.decodeItemWithOffset(txBytes, afterKey);
    if (typeof keyVal === "bigint" ? keyVal === BigInt(0) : keyVal === 0) {
      existingKey0EntryStart = scanOff;
      existingKey0ValueStart = afterKey;
      existingKey0ValueEnd = afterVal;
      break;
    }
    scanOff = afterVal;
  }

  let newWsBytes: Buffer;
  if (existingKey0ValueStart >= 0) {
    // ── Counter-signature path ────────────────────────────────────────────
    // The tx already carries someone's vkey witnesses (the outgoing admin on a
    // RotateAdmin, say). Union the two arrays in place: the entry COUNT of the
    // witness-set map is unchanged, only key 0's value grows. Everything else —
    // body, redeemers, datums, and therefore both the signature target and
    // script_integrity_hash — is copied verbatim, so the first signature stays
    // valid.
    const mergedKey0Value = mergeVkeyWitnessValues(
      txBytes.subarray(existingKey0ValueStart, existingKey0ValueEnd),
      walletKey0ValueBytes,
    );
    newWsBytes = Buffer.concat([
      encodeMapHeader(oldWsHdr.count),
      txBytes.subarray(oldWsHdr.bodyStart, existingKey0EntryStart),  // entries before key 0
      Uint8Array.of(0x00),                                           // key 0
      mergedKey0Value,
      txBytes.subarray(existingKey0ValueEnd, afterWs),               // entries after key 0
    ].map((p) => Buffer.from(p)));
  } else {
    // Key 0 sorts before any other unsigned-int key under canonical CBOR map
    // ordering, so prepending it keeps the witness-set map canonical.
    newWsBytes = Buffer.concat([
      encodeMapHeader(oldWsHdr.count + 1),
      Uint8Array.of(0x00),
      walletKey0ValueBytes,
      oldWsBodyBytes,
    ].map((p) => Buffer.from(p)));
  }

  return Buffer.concat([
    Buffer.from([0x84]),
    bodyBytes,
    newWsBytes,
    restBytes,
  ]).toString("hex");
}
