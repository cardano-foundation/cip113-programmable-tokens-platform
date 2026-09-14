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

const API_HOST: Record<CardanoNetwork, string> = {
  mainnet: "https://api.uplc.link",
  preview: "https://preview-api.uplc.link",
  preprod: "https://preprod-api.uplc.link",
};

const SITE_HOST: Record<CardanoNetwork, string> = {
  mainnet: "https://uplc.link",
  preview: "https://preview.uplc.link",
  preprod: "https://preprod.uplc.link",
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
 * The registry browser. uplc.link has no per-script page today — its routes are `/`, `/docs`,
 * `/registry` and `/verify` — so "view on uplc.link" lands on the registry rather than deep
 * linking to this script.
 */
export function registrySiteUrl(): string {
  return `${SITE_HOST[getCardanoNetwork()]}/registry`;
}

/** In-flight and settled lookups, negatives included: a 404 here is stable. */
const cache = new Map<string, Promise<Cip171Record | null>>();

/**
 * Look a script hash up. Resolves to null for "no record", a failed request, or a slow one —
 * a provenance badge is never worth surfacing an error over.
 *
 * `by-hash` matches both the parameterised `finalHash` and the unparameterised `rawHash`.
 */
export function lookupByScriptHash(scriptHash: string): Promise<Cip171Record | null> {
  const key = scriptHash.toLowerCase();
  const existing = cache.get(key);
  if (existing) return existing;

  const pending = (async (): Promise<Cip171Record | null> => {
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
  return pending;
}
