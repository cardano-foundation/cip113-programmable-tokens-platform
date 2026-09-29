/**
 * The transaction hand-off for a co-signing ceremony.
 *
 * `POST { tx }` → `{ id, storedAt, alreadyHeld }`; `GET ?id=<64 hex>` → `{ id, tx, storedAt }`.
 *
 * ## Why this is a Next route and not a backend endpoint
 *
 * The ceremony makes ZERO calls to the Java indexer, and that is a deliberate, test-asserted
 * property: `ProtocolBootstrapService` refuses to start when no deployment is recorded, printing
 * "THIS IS NOT A DEADLOCK … Deploy from /ops/bootstrap-protocol with this service DOWN". On a
 * fresh network — preprod, mainnet — a backend relay would therefore be unreachable exactly when
 * the first bootstrap needs it, and the failure would land inside phase two, after the one-shot
 * seeds are spent and with participants waiting on a call.
 *
 * Same origin also means no CORS, and no world-writable endpoint on a cardanofoundation-operated
 * host: the backend has no Spring Security and sets `allowedOriginPatterns("*")`.
 *
 * ## GET must work with /ops switched off
 *
 * Participants are precisely the people who are not operators. `middleware.ts` gates `/ops/:path*`
 * and nothing else, so this route and `/sign` are both reachable — the same reasoning that put
 * `/sign` outside `/ops` in the first place.
 *
 * All the rules — the derived key, reject-when-full, the TTL, the hex-denominated cap, and why
 * the id is a lookup key rather than a security control — live in `lib/deployment/relay-store.ts`.
 */

import { NextResponse } from "next/server";
import {
  putTransaction,
  getTransaction,
  RelayError,
  MAX_TX_HEX_CHARS,
  RELAY_TTL_MS,
} from "@/lib/deployment/relay-store";

// Module state is per-server-process; a cached render would never reach it.
export const dynamic = "force-dynamic";

function fail(e: unknown) {
  if (e instanceof RelayError) {
    return NextResponse.json({ reason: e.reason, error: e.message }, { status: e.status });
  }
  return NextResponse.json(
    { reason: "internal", error: (e as Error).message ?? "unknown error" },
    { status: 500 },
  );
}

export async function POST(request: Request) {
  try {
    const body = (await request.json()) as { tx?: unknown };
    if (typeof body.tx !== "string") {
      throw new RelayError(400, "bad-request", 'Expected a JSON body of the form { "tx": "<hex>" }.');
    }
    const result = putTransaction(body.tx);
    return NextResponse.json({ ...result, ttlMs: RELAY_TTL_MS });
  } catch (e) {
    return fail(e);
  }
}

export async function GET(request: Request) {
  try {
    const id = new URL(request.url).searchParams.get("id");
    if (!id) throw new RelayError(400, "bad-request", "Pass the transaction id as ?id=<64 hex>.");
    const held = getTransaction(id);
    return NextResponse.json({ id: held.id, tx: held.hex, storedAt: held.storedAt });
  } catch (e) {
    return fail(e);
  }
}

/** So a client can show the limit before it posts 15 KB and is refused. */
export async function OPTIONS() {
  return NextResponse.json({ maxTxHexChars: MAX_TX_HEX_CHARS, ttlMs: RELAY_TTL_MS });
}
