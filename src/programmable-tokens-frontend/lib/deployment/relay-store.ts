/**
 * A short-lived, in-memory hand-off for ONE unsigned transaction.
 *
 * ## The problem it solves
 *
 * A protocol genesis is ~15 KB of hex. Every declared participant has to sign it, and pasting
 * 15 KB into Slack, Discord or Telegram is where a ceremony with five people on a call actually
 * breaks — messages get truncated, wrapped, or split across two posts.
 *
 * So the BYTES travel over HTTP and the ID travels in the chat. The id is the transaction id,
 * which `/sign` already prints in the largest type on the page, so there is nothing new for the
 * driver to communicate.
 *
 * ## ⛔ THE ID IS A LOOKUP KEY, NOT A SECURITY CONTROL
 *
 * This is the thing to be clear about, because it reads like one. The server derives the key by
 * hashing the bytes it was given, so `/sign` recomputing the id from what it received can only
 * ever fail if THIS CODE is buggy. It cannot catch:
 *
 *   - a driver who quotes the wrong-but-real id,
 *   - an attacker who substitutes both the id and the transaction in a compromised channel,
 *   - a plan that was rebuilt after the id was circulated.
 *
 * The real control is unchanged and lives outside this file: participants compare the hash over a
 * channel the transaction did NOT arrive on — voice, on the call. See `app/sign/page.tsx`.
 *
 * ## ⛔ A READ DOES NOT CONSUME THE ENTRY
 *
 * Every declared participant fetches the SAME id, and a ceremony has four or five of them. So this
 * is read-many by design and stays readable until its TTL: the only two deletions in this file are
 * expiry. A hand-off is exactly the kind of thing someone later "tidies" into a single-use handle —
 * and the failure would be the fourth signer getting a 404 mid-ceremony, with the driver unable to
 * tell a consumed entry from a restarted pod. There is a test for this.
 *
 * ## Why in-memory is acceptable here
 *
 * Ruled by Giovanni 2026-09-29: one pod, and — decisively — the paste path STAYS. A restart
 * degrades the ceremony to the flow that already works rather than losing one whose one-shot
 * seeds are already spent. Durable storage was offered and declined with that reasoning. If this
 * ever runs multi-replica, a push to one pod will 404 on another, and the answer is a table, not
 * a sticky session.
 */

import { EvoTransaction } from "@easy1staking/cip113-sdk-ts";
import { transactionHash } from "../tx/hash";

/**
 * Cap in HEX CHARACTERS, deliberately — the driver posts hex, so a limit stated in bytes is
 * wrong by a factor of two, in whichever direction you did not mean.
 *
 * ⚑ SET FAR ABOVE ANY TRANSACTION THE LEDGER WOULD TAKE. The largest in a bootstrap measured
 * 12,496 bytes against a 16,384 limit, and the SDK deliberately REPORTS rather than refuses on
 * genesis size, so that it never rejects something the ledger might still accept. A cap below
 * that ceiling would make this hand-off the gate the SDK declined to be — so 64 KB of bytes,
 * four times the ledger's own maximum.
 */
export const MAX_TX_HEX_CHARS = 131_072;

/** Long enough for a signing round across time zones; short enough not to be a blob store. */
export const RELAY_TTL_MS = 24 * 60 * 60 * 1000;

/** A ceremony needs one. The rest of the room is for retries and a second attempt. */
export const MAX_RELAY_ENTRIES = 32;

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

interface Entry {
  hex: string;
  storedAt: number;
}

const store = new Map<string, Entry>();

/** Lazy expiry: there is no timer to leak, and every path that reads sweeps first. */
function sweep(now: number): void {
  for (const [id, e] of store) {
    if (now - e.storedAt >= RELAY_TTL_MS) store.delete(id);
  }
}

export class RelayError extends Error {
  constructor(readonly status: number, readonly reason: string, message: string) {
    super(message);
    this.name = "RelayError";
  }
}

export interface PutResult {
  id: string;
  storedAt: number;
  /** True when this exact transaction was already held — the push is idempotent, not a conflict. */
  alreadyHeld: boolean;
}

/**
 * Store a transaction and return its id.
 *
 * ⛔ THE KEY IS DERIVED, NEVER SUPPLIED. `transactionHash` is blake2b-256 over body element 0,
 * sliced out by byte span — the same value the co-signature panel displays and `/sign` recomputes.
 * A client-chosen key could be poisoned; a derived one cannot, which is also why an existing
 * entry is never displaced: identical bytes produce an identical id, so a re-push is a no-op
 * rather than an overwrite, and there is no id under which two different transactions can live.
 */
export function putTransaction(
  hex: string,
  now: number = Date.now(),
  /**
   * Injectable so the refuse-when-full path is testable. Evolution's Transaction codec is strict
   * — rightly — so there is no way to synthesise distinct valid transactions to fill the store
   * with, and a limit that cannot be exercised is a limit nobody knows still works.
   */
  maxEntries: number = MAX_RELAY_ENTRIES,
): PutResult {
  const clean = hex.trim().toLowerCase();
  if (clean.length === 0) {
    throw new RelayError(400, "empty", "No transaction was supplied.");
  }
  if (clean.length > MAX_TX_HEX_CHARS) {
    throw new RelayError(
      413,
      "too-large",
      `Transaction is ${clean.length} hex characters; the limit is ${MAX_TX_HEX_CHARS} ` +
        "(64 KB of bytes, four times the ledger's own maximum).",
    );
  }
  if (clean.length % 2 !== 0 || !/^[0-9a-f]+$/.test(clean)) {
    throw new RelayError(400, "not-hex", "Transaction is not an even-length hex string.");
  }

  // ⛔ MEASURED: `transactionHash` IS NOT A VALIDATOR, and the first version of this file used it
  // as one. It checks a single byte — that the value opens 0x84 — and then hashes whatever span
  // Evolution's offset reports. So `"84"` alone yields a 0-byte body and a perfectly well-formed
  // 64-hex id (0e5751c0…, the blake2b of nothing), and `"8400000000"` yields another. Neither is
  // a transaction. An unauthenticated endpoint gated that way is a blob store with extra steps.
  //
  // Evolution's `CBOR.decodeItemWithOffset` cannot close the hole either: on a 1-byte input it
  // returns `value: undefined` and `newOffset: 5`, claiming to have consumed five bytes of one.
  //
  // So the gate is a real DECODE through Evolution's own Transaction codec — the same parser
  // `/sign` already runs on pasted input, so this adds no new surface. Decode ONLY: the stored
  // bytes are the bytes that arrived, never `toCBORBytes` of the decoded value, because
  // re-encoding moves bytes in this stack (see docs/TESTING-SERIALISED-BYTES.md) and would hand
  // participants a body nobody is collecting witnesses for.
  try {
    EvoTransaction.fromCBORBytes(hexToBytes(clean));
  } catch (e) {
    throw new RelayError(
      400,
      "not-a-transaction",
      `Not a Cardano transaction: ${(e as Error).message.slice(0, 200)}`,
    );
  }
  const id = transactionHash(clean);

  sweep(now);
  const held = store.get(id);
  if (held) return { id, storedAt: held.storedAt, alreadyHeld: true };

  // Reject when full rather than evict. Evicting would let anyone push 32 transactions and
  // silently displace the one a ceremony is waiting on, at the worst possible moment.
  if (store.size >= maxEntries) {
    throw new RelayError(
      507,
      "full",
      `The hand-off already holds ${maxEntries} transactions. Nothing was displaced — ` +
        "wait for an entry to expire, or circulate the transaction by paste.",
    );
  }
  store.set(id, { hex: clean, storedAt: now });
  return { id, storedAt: now, alreadyHeld: false };
}

export interface HeldTransaction {
  id: string;
  hex: string;
  storedAt: number;
}

/**
 * Fetch by id.
 *
 * ⚑ "UNKNOWN" AND "EXPIRED" ARE DIFFERENT ANSWERS and both are 404s, so they must be
 * distinguishable in the body. To a participant, a pod restart and a mistyped id look identical
 * otherwise — and one means "type it again", the other means "ask the driver to push again".
 */
export function getTransaction(id: string, now: number = Date.now()): HeldTransaction {
  const key = id.trim().toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(key)) {
    throw new RelayError(400, "bad-id", "A transaction id is 64 hexadecimal characters.");
  }
  const held = store.get(key);
  if (held && now - held.storedAt >= RELAY_TTL_MS) {
    store.delete(key);
    throw new RelayError(404, "expired", "That transaction was held but has expired. Ask the driver to push it again.");
  }
  if (!held) {
    throw new RelayError(
      404,
      "unknown",
      "No transaction is held under that id. Either it was never pushed, the service restarted, " +
        "or the id is not the one the driver circulated.",
    );
  }
  return { id: key, hex: held.hex, storedAt: held.storedAt };
}

/** Tests only. */
export function resetRelayStore(): void {
  store.clear();
}
