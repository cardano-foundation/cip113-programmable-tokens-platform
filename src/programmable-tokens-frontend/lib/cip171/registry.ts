/**
 * Reading CIP-171 script provenance from a uplc.link registry.
 *
 * A CIP-171 record is a published claim that named scripts were compiled from a named source
 * repository at a named commit, which a verifier can check by recompiling. uplc.link indexes
 * those records; this reads them.
 *
 * ⚠ THE SITE IS NOT THE API, and confusing them fails silently. `preview.uplc.link` is a
 * Next.js app whose `/api/registry?action=…` is an internal proxy. The API it proxies onto is
 * `preview-api.uplc.link`, route `/api/v1/scripts/by-hash/{hash}`. The site answers 404 for
 * that route — indistinguishable from "no record published" — so a wrong host here shows up
 * as an empty registry rather than as an error. Hosts measured, not guessed: mainnet
 * `api.uplc.link`, preview `preview-api.uplc.link`, preprod `preprod-api.uplc.link`
 * (`mainnet-api.uplc.link` does not resolve).
 *
 * The API sends `access-control-allow-origin: *`, so the browser calls it directly and no
 * proxy of ours is involved.
 */
import { getCardanoNetwork, type CardanoNetwork } from "@/lib/utils/network";

// ⚑ A DEVNET HAS NO PUBLIC REGISTRY, so its entry is empty and callers must treat empty as
// "unavailable" rather than building a URL from it. Empty-disables is already this project's
// convention for exactly this: the backend's devnet profile sets `uplc-link.base-url: ""`, noting
// that without it devnet would inherit mainnet's host and ask it about devnet scripts.
const API_HOST: Record<CardanoNetwork, string> = {
  mainnet: "https://api.uplc.link",
  preview: "https://preview-api.uplc.link",
  preprod: "https://preprod-api.uplc.link",
  devnet: "",
};

/**
 * Is there a uplc.link for this network at all?
 *
 * ⛔ THE EMPTY SENTINEL WAS NOT BEING HONOURED, which made it worse than no sentinel. devnet maps
 * to "" because no public registry indexes a local chain — but the URL builders interpolated the
 * host directly, so "" produced RELATIVE urls: `/registry`, `/verify?txHash=…` and a fetch of
 * `/api/v1/scripts/by-hash/…` against the app's OWN origin. This app serves `/registry` and
 * `/verify`, so those links silently opened its own pages instead of failing — a wrong answer that
 * looks like a right one. A sentinel nobody checks is just a bug with a comment.
 */
export function uplcLinkAvailable(network: CardanoNetwork = getCardanoNetwork()): boolean {
  return SITE_HOST[network] !== "" && API_HOST[network] !== "";
}

const SITE_HOST: Record<CardanoNetwork, string> = {
  mainnet: "https://uplc.link",
  preview: "https://preview.uplc.link",
  preprod: "https://preprod.uplc.link",
  devnet: "",
};

export interface Cip171Script {
  scriptName: string;
  rawHash: string;
  finalHash: string;
  parameterizationStatus?: string;
}

export interface Cip171Record {
  txHash: string;
  sourceUrl: string;
  commitHash: string;
  sourcePath: string;
  compilerType: string;
  compilerVersion: string;
  status: string;
  scripts?: Cip171Script[];
}

/** Only a record whose source was actually recompiled and matched earns the badge. */
export function isVerified(record: Cip171Record | null): record is Cip171Record {
  return !!record && record.status === "VERIFIED";
}

/**
 * `https://github.com/cardano-foundation/cip113-programmable-tokens-platform`
 * -> `{ organization: "cardano-foundation", repository: "cip113-programmable-tokens-platform" }`
 *
 * Returns nulls rather than throwing: the field is free text published by a third party, and
 * a shape we do not recognise should degrade to showing the raw URL, not break the panel.
 */
export function parseSourceUrl(sourceUrl: string): {
  organization: string | null;
  repository: string | null;
} {
  try {
    const path = new URL(sourceUrl).pathname.replace(/^\/+|\/+$|\.git$/g, "");
    const [organization, repository] = path.split("/");
    return { organization: organization || null, repository: repository || null };
  } catch {
    return { organization: null, repository: null };
  }
}

/**
 * The registry browser. There is no per-SCRIPT page, so "view on uplc.link" lands on the registry
 * and the hash is pasted there. For a transaction, use {@link verifyTxUrl} instead.
 */
export function registrySiteUrl(): string | null {
  const network = getCardanoNetwork();
  if (!uplcLinkAvailable(network)) return null;
  return `${SITE_HOST[network]}/registry`;
}

/**
 * Replay a transaction's CIP-171 verification on uplc.link.
 *
 * ⛔ THIS ROUTE IS REAL AND THIS FILE USED TO DENY IT. The note above said uplc.link had no deep
 * link at all, which was half right and led us to hand operators a bare registry link: there is no
 * per-SCRIPT page, but `/verify` takes a `txHash` — so a transaction carrying a provenance record
 * has a direct replay URL. Giovanni knew; the comment was stale.
 *
 * MEASURED, not guessed, 2026-09-30: the `/verify` page bundle
 * (`_next/static/chunks/app/verify/page-*.js`) calls `useSearchParams().get("txHash")` and seeds
 * its initial state from whether that parameter is present, so the parameter selects the
 * replay-from-transaction mode rather than merely prefilling a field. The path-shaped candidates
 * (`/verify/{hash}`, `/registry/{hash}`, `/script/{hash}`, `/{hash}`) all 404.
 *
 * ⚠ A 200 from this URL means the PAGE loaded, not that a record exists — indexing is not instant
 * and an unindexed transaction is indistinguishable from an unverifiable one until it resolves.
 */
export function verifyTxUrl(txHash: string): string | null {
  const network = getCardanoNetwork();
  if (!uplcLinkAvailable(network)) return null;
  return `${SITE_HOST[network]}/verify?txHash=${encodeURIComponent(txHash)}`;
}

/**
 * In-flight and settled lookups.
 *
 * ⛔ A 404 HERE IS NOT STABLE, which this comment used to claim. uplc.link indexes CIP-171 records
 * on a TIMER, so a record published moments ago is absent now and present minutes later. Memoising
 * that absence for the page's lifetime means no retry ever re-asks and a record that DID arrive can
 * never be picked up. The backend's UplcLinkClient had the identical bug, with the identical
 * reasoning, and was fixed by expiring negatives; this is the same fix on this side.
 *
 * Positives are kept indefinitely — a CIP-171 record is immutable once published.
 */
const NEGATIVE_TTL_MS = 60_000;
const cache = new Map<string, Promise<Cip171Record | null>>();
const negativeAt = new Map<string, number>();

/**
 * Look a script hash up. Resolves to null for "no record", a failed request, or a slow one —
 * a provenance badge is never worth surfacing an error over.
 *
 * `by-hash` matches both the parameterised `finalHash` and the unparameterised `rawHash`.
 */
export function lookupByScriptHash(scriptHash: string): Promise<Cip171Record | null> {
  const key = scriptHash.toLowerCase();
  const existing = cache.get(key);
  if (existing) {
    // A remembered NEGATIVE expires; a remembered record does not. Without this, the first lookup
    // after a registration — which uplc.link has not indexed yet — is remembered as "no record"
    // for the lifetime of the page and no retry ever re-asks.
    const missedAt = negativeAt.get(key);
    if (missedAt === undefined || Date.now() - missedAt < NEGATIVE_TTL_MS) return existing;
    cache.delete(key);
    negativeAt.delete(key);
  }

  const pending = (async (): Promise<Cip171Record | null> => {
    // No registry for this network: do not fetch a relative URL against our own origin.
    if (!uplcLinkAvailable()) return null;
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 6000);
      const response = await fetch(
        `${API_HOST[getCardanoNetwork()]}/api/v1/scripts/by-hash/${key}`,
        { signal: controller.signal },
      );
      clearTimeout(timer);
      if (!response.ok) return null;
      const body = (await response.json()) as Cip171Record | Cip171Record[];
      const record = Array.isArray(body) ? body[0] ?? null : body;
      return record && record.sourceUrl ? record : null;
    } catch {
      return null;
    }
  })();

  cache.set(key, pending);
  // Record WHEN a miss happened, so the entry above can expire. A hit records nothing and is kept.
  void pending.then((record) => {
    if (record === null) negativeAt.set(key, Date.now());
  });
  return pending;
}
