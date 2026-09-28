/**
 * Mining a low transaction hash by moving lovelace between two outputs.
 *
 * ## The nonce, and why it is this one
 *
 * Giovanni's: a ~1 ADA output back to the deployer's own address, incremented one lovelace at a
 * time, with the lovelace coming OUT of the change output so the transaction stays balanced.
 *
 * That constant-sum property is the whole reason it works. A nonce that changed the total would
 * change the fee; a fee change rewrites the body; and a rewritten body has a different hash — a
 * nonce that fights itself. Moving a lovelace between two outputs leaves inputs, fee and every
 * other field untouched, so the ONLY thing that changes between attempts is the two integers
 * being patched.
 *
 * ## Why the CBOR width never moves
 *
 * A CBOR unsigned integer is minimal-width. A value near 1 ADA (1,000,000) sits in the 5-byte
 * form — 0x1a plus four bytes — and stays there all the way to 4,294,967,295. From 1 ADA that is
 * 4,293,967,296 increments before the encoding would widen, and that boundary is 4,294.967 ADA.
 * Unreachable by mining. If it were ever crossed the body would grow four bytes, the fee would
 * change, and the search would be chasing a moving target — so {@link patchLovelacePair} refuses
 * rather than letting it happen silently.
 *
 * ## Why this does NOT re-serialise, and why that buys nothing
 *
 * ⛔ DO NOT "OPTIMISE" THIS BY AVOIDING THE COPY. It was measured, and the shortcut is worthless:
 *
 *     body size   patch-in-place   re-serialise each attempt
 *       2048 B      28,131 h/s          27,890 h/s
 *
 * Within noise. Hashing two kilobytes dominates; the buffer work beside it is free. The lever
 * that actually matters is BODY SIZE — the same measurement gives 101,048 h/s at 512 B and
 * 7,105 h/s at 8 KB. Patching in place is done here because it is simpler, not because it is
 * faster.
 */
import { blake2b } from '@noble/hashes/blake2b';

/** CBOR 0x1a: unsigned integer, four bytes following. The width a lovelace value near 1 ADA uses. */
const CBOR_UINT32_HEADER = 0x1a;

/** Largest value the 5-byte form holds. Above this CBOR widens to nine bytes and the body grows. */
const UINT32_MAX = 4_294_967_295;

export interface LovelaceSlot {
  /** Byte offset of the CBOR header for this value within the body. */
  offset: number;
  /** The value as currently encoded. */
  value: number;
}

export interface MineRequest {
  /** The serialised transaction BODY — a transaction id is blake2b-256 over exactly these bytes. */
  body: Uint8Array;
  /** The self-output that gains a lovelace per attempt. */
  gains: LovelaceSlot;
  /** The change output that loses one. Keeps the transaction balanced and the fee fixed. */
  loses: LovelaceSlot;
  targetNibbles: number;
  /** Give up after this many attempts. The UI turns it into a wall-clock promise. */
  maxAttempts: number;
  /** Called every `progressEvery` attempts so a person can watch it and cancel. */
  onProgress?: (attempts: number) => void;
  progressEvery?: number;
  /** Checked at each progress tick. Returning true stops the search. */
  shouldCancel?: () => boolean;
}

export interface MineResult {
  found: boolean;
  attempts: number;
  /** The winning nonce: how many lovelace moved. 0 means the unmodified body already qualified. */
  nonce: number;
  /** The transaction id of the mined body, hex. */
  txHash: string;
  /** The mined body. Byte-identical to the input except the two patched integers. */
  body: Uint8Array;
  cancelled: boolean;
}

/** blake2b-256 of the body — the transaction id, exactly as the ledger computes it. */
export function transactionId(body: Uint8Array): string {
  const digest = blake2b(body, { dkLen: 32 });
  let hex = '';
  for (let i = 0; i < digest.length; i++) hex += digest[i].toString(16).padStart(2, '0');
  return hex;
}

/**
 * Read a 5-byte CBOR unsigned integer, refusing anything that is not one.
 *
 * Refuses rather than coerces: an offset that does not point at a 0x1a header is a caller bug,
 * and patching bytes at a wrong offset would corrupt the transaction into something that might
 * still submit.
 */
export function readLovelaceSlot(body: Uint8Array, offset: number): number {
  if (body[offset] !== CBOR_UINT32_HEADER) {
    throw new Error(
      `offset ${offset} does not hold a 5-byte CBOR unsigned integer (found 0x${
        body[offset]?.toString(16) ?? '??'
      }, expected 0x1a). Mining patches bytes in place, so a wrong offset would corrupt the ` +
        `transaction rather than fail to find one.`,
    );
  }
  return (
    body[offset + 1] * 0x1000000 +
    body[offset + 2] * 0x10000 +
    body[offset + 3] * 0x100 +
    body[offset + 4]
  );
}

/**
 * Move `delta` lovelace from one slot to the other, in place.
 *
 * ⛔ CONSERVATION IS THE INVARIANT. The two writes are a single logical move: whatever one output
 * gains the other loses, so the transaction's total output value — and therefore its fee and its
 * size — are exactly what they were. Any change to this function that can write one side without
 * the other produces a transaction that does not balance, and it will not be caught by the hash.
 */
export function patchLovelacePair(
  body: Uint8Array,
  gains: LovelaceSlot,
  loses: LovelaceSlot,
  delta: number,
): void {
  const gained = gains.value + delta;
  const lost = loses.value - delta;

  if (gained > UINT32_MAX || lost > UINT32_MAX) {
    throw new Error(
      `mining would push a lovelace value past ${UINT32_MAX} (4,294.967 ADA), where CBOR widens ` +
        `from five bytes to nine. The body would grow, the fee would change, and the search would ` +
        `be chasing a hash that moves every attempt.`,
    );
  }
  if (lost < 0) {
    throw new Error('mining would drive the change output negative');
  }
  writeUint32(body, gains.offset, gained);
  writeUint32(body, loses.offset, lost);
}

function writeUint32(body: Uint8Array, offset: number, value: number): void {
  body[offset + 1] = (value >>> 24) & 0xff;
  body[offset + 2] = (value >>> 16) & 0xff;
  body[offset + 3] = (value >>> 8) & 0xff;
  body[offset + 4] = value & 0xff;
}

/** How many leading hex digits of a digest are zero, without formatting it first. */
function leadingZeroNibblesOf(digest: Uint8Array): number {
  let n = 0;
  for (let i = 0; i < digest.length; i++) {
    const byte = digest[i];
    if (byte === 0) { n += 2; continue; }
    if (byte < 0x10) n += 1;
    break;
  }
  return n;
}

/**
 * Search for a body whose transaction id has at least `targetNibbles` leading zeros.
 *
 * Synchronous and blocking by design — it belongs in a Worker, and the caller is responsible for
 * putting it there. Cancellation and progress are checked on a tick rather than every attempt,
 * because at ~28,000 attempts a second a per-attempt callback would cost more than the hashing.
 */
export function mineLowTxHash(request: MineRequest): MineResult {
  const {
    body, gains, loses, targetNibbles, maxAttempts,
    onProgress, progressEvery = 2048, shouldCancel,
  } = request;

  // Worked on a copy: a cancelled or failed search must leave the caller's body untouched, or a
  // transaction they still intend to submit unmined has silently moved a lovelace.
  const working = body.slice();
  const gainsSlot = { ...gains };
  const losesSlot = { ...loses };

  for (let nonce = 0; nonce <= maxAttempts; nonce++) {
    if (nonce > 0) patchLovelacePair(working, gainsSlot, losesSlot, nonce);

    const digest = blake2b(working, { dkLen: 32 });
    if (leadingZeroNibblesOf(digest) >= targetNibbles) {
      return {
        found: true, attempts: nonce + 1, nonce,
        txHash: transactionId(working), body: working, cancelled: false,
      };
    }

    if (nonce > 0 && nonce % progressEvery === 0) {
      onProgress?.(nonce);
      if (shouldCancel?.()) {
        return { found: false, attempts: nonce, nonce: 0, txHash: '', body, cancelled: true };
      }
    }
  }
  return { found: false, attempts: maxAttempts, nonce: 0, txHash: '', body, cancelled: false };
}

/**
 * Hashes per second on THIS machine, measured against a body of the size actually being mined.
 *
 * Size matters more than anything else here — 512 B runs at 101k h/s and 8 KB at 7k — so
 * calibrating against a fixed dummy would produce an estimate that is wrong in proportion to how
 * unusual the transaction is.
 */
export function calibrate(body: Uint8Array, milliseconds = 300): number {
  const working = body.slice();
  const started = performance.now();
  let attempts = 0;
  while (performance.now() - started < milliseconds) {
    for (let i = 0; i < 128; i++) {
      working[0] = (working[0] + 1) & 0xff;
      blake2b(working, { dkLen: 32 });
      attempts++;
    }
  }
  const elapsed = (performance.now() - started) / 1000;
  return Math.round(attempts / elapsed);
}
