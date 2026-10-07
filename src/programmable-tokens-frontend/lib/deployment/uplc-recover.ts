/**
 * Recovering a deployment's parameterisation from uplc.link, by NAME rather than by position.
 *
 * ⛔ WHY THIS EXISTS. The mainnet ceremony of 2026-10-02 published the protocol genesis and crashed
 * before the seven reference scripts. Publishing them later needs the EXACT parameterisation the
 * genesis was built from — a set derived from different inputs hands the protocol reference inputs
 * carrying the wrong scripts, which fails at redeemer evaluation rather than at submission. The
 * ceremony's own inputs live in one browser's localStorage; this recovers them from a public record
 * instead, so recovery does not depend on one machine.
 *
 * ⚑ THE SOURCE IS CIP-171, WHICH THE GENESIS ITSELF PUBLISHED. uplc.link indexes it and answers
 * `GET {API_HOST}/api/v1/scripts/by-hash/{rawScriptHash}` with, per script: `scriptName`,
 * `rawHash`, **`finalHash`** (the APPLIED hash, i.e. what is actually deployed),
 * `requiredParameters[].title` and `providedParameters[]` — plus `status` and
 * `parameterizationStatus`. Measured against mainnet `bfefbd22…` on 2026-10-02: all eleven scripts
 * come back `VERIFIED` / `COMPLETE`.
 *
 * ⛔ NAMED PARAMETERS ARE WHAT MAKES THIS SAFE RATHER THAN REVERSE-ENGINEERING. Reading
 * `providedParameters[3]` and hoping it is the inline-datum bound is the kind of guess that produces
 * a plausible wrong answer. The API pairs each value with its `title`, so `max_inline_datum_bytes`,
 * `utxo_ref` and `_nonce` are read by the name the validator declares:
 *
 *   protocol_params        utxo_ref               -> the params seed
 *   upgrade_multisig       utxo_ref               -> the multisig seed
 *   issuance_cbor_hex_mint utxo_ref               -> the issuance seed
 *   always_fail            _nonce                 -> alwaysFailNonce
 *   transfer (and others)  max_inline_datum_bytes -> maxInlineDatumBytes
 *   programmable_logic_global unfracking_hash     -> the unfracking choice (sentinel = disabled)
 *
 * ⚠ AND RECOVERY IS NEVER THE PROOF. Whatever this returns must still be put through the
 * derivation and checked against the chain: every derived applied hash against `finalHash` here,
 * and the derived protocol-params datum against the one the genesis published. This module supplies
 * a candidate; `inspectDeployment` and the hash comparison are what accept or refuse it.
 */
import { UNFRACKING_DISABLED } from "@easy1staking/cip113-sdk-ts";

/** One script as uplc.link records it. */
export interface RecoveredScript {
  scriptName: string;
  rawHash: string;
  /** The APPLIED hash — what the chain actually holds. The cross-check our derivation must match. */
  finalHash: string;
  plutusVersion: string;
  parameterizationStatus: string;
  /** `title` -> the provided value, paired by index as the API returns them. */
  params: Record<string, string>;
}

export interface RecoveredRecord {
  txHash: string | null;
  sourceUrl: string;
  commitHash: string;
  compilerType: string;
  compilerVersion: string;
  status: string;
  scripts: RecoveredScript[];
}

/** The six `BootstrapConfig` values, as recovered. `null` for anything not found. */
export interface RecoveredConfig {
  paramsSeed: { txHash: string; outputIndex: number } | null;
  issuanceSeed: { txHash: string; outputIndex: number } | null;
  multisigSeed: { txHash: string; outputIndex: number } | null;
  alwaysFailNonce: string | null;
  maxInlineDatumBytes: string | null;
  unfrackingEnabled: boolean | null;
  /** Anything that could not be recovered, named — so the operator knows what to supply by hand. */
  missing: string[];
}

/** `581c<28 bytes>` -> the 28 bytes. Anything else -> null. */
export function unwrapBytes(hex: string | undefined): string | null {
  if (typeof hex !== "string") return null;
  const m = /^58([0-9a-f]{2})([0-9a-f]+)$/i.exec(hex.trim());
  if (!m) return null;
  const len = parseInt(m[1], 16);
  return m[2].length === len * 2 ? m[2].toLowerCase() : null;
}

/**
 * `d8799f5820<32 bytes><index>ff` -> an outref.
 *
 * ⚑ THE INDEX IS A CBOR INTEGER, NOT A DIGIT. `…ac03ff` is index 3 and `…ac00ff` is index 0, but an
 * index of 10 or more is `18 0a`, and reading the byte as a nibble would silently give the wrong
 * output. Decoded as the minor-type integer it is.
 */
export function unwrapOutref(hex: string | undefined): { txHash: string; outputIndex: number } | null {
  if (typeof hex !== "string") return null;
  const m = /^d8799f5820([0-9a-f]{64})(.+)ff$/i.exec(hex.trim());
  if (!m) return null;
  const tail = m[2].toLowerCase();
  let outputIndex: number;
  if (/^[0-9a-f]{2}$/.test(tail) && parseInt(tail, 16) <= 0x17) outputIndex = parseInt(tail, 16);
  else if (/^18[0-9a-f]{2}$/.test(tail)) outputIndex = parseInt(tail.slice(2), 16);
  else if (/^19[0-9a-f]{4}$/.test(tail)) outputIndex = parseInt(tail.slice(2), 16);
  else return null;
  return { txHash: m[1].toLowerCase(), outputIndex };
}

/** `190400` -> "1024". A CBOR unsigned integer, decoded rather than pattern-matched. */
export function unwrapInt(hex: string | undefined): string | null {
  if (typeof hex !== "string") return null;
  const h = hex.trim().toLowerCase();
  if (/^[0-9a-f]{2}$/.test(h) && parseInt(h, 16) <= 0x17) return String(parseInt(h, 16));
  if (/^18[0-9a-f]{2}$/.test(h)) return String(parseInt(h.slice(2), 16));
  if (/^19[0-9a-f]{4}$/.test(h)) return String(parseInt(h.slice(2), 16));
  if (/^1a[0-9a-f]{8}$/.test(h)) return String(parseInt(h.slice(2), 16));
  return null;
}

/** Fetches one script's record. `null` on any failure — the caller reports what is missing. */
export async function fetchByRawHash(
  apiHost: string,
  rawHash: string,
  fetchImpl: typeof fetch = fetch,
): Promise<RecoveredRecord | null> {
  if (apiHost === "") return null;
  try {
    const res = await fetchImpl(`${apiHost}/api/v1/scripts/by-hash/${rawHash}`);
    if (!res.ok) return null;
    const body = (await res.json()) as Record<string, unknown>;
    const scripts = (body.scripts as Record<string, unknown>[] | undefined) ?? [];
    return {
      txHash: (body.txHash as string) ?? null,
      sourceUrl: (body.sourceUrl as string) ?? "",
      commitHash: (body.commitHash as string) ?? "",
      compilerType: (body.compilerType as string) ?? "",
      compilerVersion: (body.compilerVersion as string) ?? "",
      status: (body.status as string) ?? "",
      scripts: scripts.map((s) => {
        const titles = ((s.requiredParameters as { title?: string }[] | undefined) ?? []).map(
          (r) => r.title ?? "",
        );
        const values = (s.providedParameters as string[] | undefined) ?? [];
        const params: Record<string, string> = {};
        titles.forEach((t, i) => {
          if (t && values[i] !== undefined) params[t] = values[i];
        });
        return {
          scriptName: (s.scriptName as string) ?? "",
          rawHash: (s.rawHash as string) ?? rawHash,
          finalHash: (s.finalHash as string) ?? "",
          plutusVersion: (s.plutusVersion as string) ?? "",
          parameterizationStatus: (s.parameterizationStatus as string) ?? "",
          params,
        };
      }),
    };
  } catch {
    return null;
  }
}

/** Reads the six config values out of whatever scripts were recovered, by name. */
export function configFromScripts(scripts: readonly RecoveredScript[]): RecoveredConfig {
  const by = (name: string) => scripts.find((s) => s.scriptName === name);
  const missing: string[] = [];

  const seedOf = (scriptName: string, label: string) => {
    const s = by(scriptName);
    if (!s) { missing.push(`${label} (no \`${scriptName}\` record)`); return null; }
    const ref = unwrapOutref(s.params["utxo_ref"]);
    if (!ref) { missing.push(`${label} (\`${scriptName}.utxo_ref\` unreadable)`); return null; }
    return ref;
  };

  const paramsSeed = seedOf("protocol_params", "the protocol-params seed");
  const multisigSeed = seedOf("upgrade_multisig", "the upgrade-multisig seed");
  const issuanceSeed = seedOf("issuance_cbor_hex_mint", "the issuance seed");

  const af = by("always_fail");
  const alwaysFailNonce = af ? unwrapBytes(af.params["_nonce"]) : null;
  if (!alwaysFailNonce) missing.push("always_fail's nonce");

  // Declared by four delegates; any of them answers, and they must agree.
  const bounds = new Set(
    scripts
      .map((s) => unwrapInt(s.params["max_inline_datum_bytes"]))
      .filter((v): v is string => v !== null),
  );
  const maxInlineDatumBytes = bounds.size === 1 ? [...bounds][0] : null;
  if (bounds.size === 0) missing.push("max_inline_datum_bytes");
  else if (bounds.size > 1) missing.push(`max_inline_datum_bytes DISAGREES across scripts: ${[...bounds].join(", ")}`);

  const global = by("programmable_logic_global");
  let unfrackingEnabled: boolean | null = null;
  if (global) {
    const h = unwrapBytes(global.params["unfracking_hash"]);
    if (h !== null) unfrackingEnabled = h !== String(UNFRACKING_DISABLED).toLowerCase();
  }
  if (unfrackingEnabled === null) missing.push("the unfracking choice");

  return {
    paramsSeed, issuanceSeed, multisigSeed,
    alwaysFailNonce, maxInlineDatumBytes, unfrackingEnabled,
    missing,
  };
}
