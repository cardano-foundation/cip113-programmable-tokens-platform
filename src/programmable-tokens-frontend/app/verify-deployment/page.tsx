"use client";

/**
 * Verify a CIP-113 deployment someone else made.
 *
 * ## Why this is not under /ops
 *
 * Same reason as `/sign`: `/ops` is gated off between deployments (see middleware.ts), and the
 * people who most want to check a published deployment are precisely the people not running it.
 * It is safe to leave open because it grants nothing — it reads a paste, re-derives every script
 * hash from the pinned blueprint, and compares. There is no wallet, no plan, no key, and nothing
 * it can submit.
 *
 * ## Why it left the bootstrap page
 *
 * It was the last section of `/ops/bootstrap-protocol`, where it is never wanted: verification is
 * not a step of a ceremony, and during one it is a wall of unrelated controls between the
 * operator and the button they need. Giovanni's call, 2026-09-29.
 *
 * ## ⚠ This page is NOT offline-capable, and `/sign` is
 *
 * `/sign` has a test (T-061) asserting it makes no network calls, because a co-signer may be on
 * an air-gapped machine. This page necessarily fetches the blueprint from the Next route
 * handlers — verifying against whatever blueprint happened to be on disk would only prove the
 * paste is self-consistent, which is the failure mode the whole exercise exists to avoid. So
 * that check must NOT be extended here; ungated and offline are different properties.
 */

import { useCallback, useMemo, useState } from "react";
import { getCardanoNetwork } from "@/lib/utils/network";
import { loadPinnedBlueprint } from "@/lib/deployment/blueprint";
import {
  verifyDeployment,
  toBootstrapRecord,
  type VerificationResult,
} from "@/lib/deployment/verify";
import { SdkRecordDownload } from "@/components/deployment/sdk-record-download";

const FIELD =
  "rounded border border-dark-600 bg-dark-900 px-2 py-1.5 font-mono text-xs text-white placeholder:text-dark-500 focus:border-cyan-600 focus:outline-none focus:ring-1 focus:ring-cyan-600/40";

export default function VerifyDeploymentPage() {
  const network = getCardanoNetwork();
  const [pasted, setPasted] = useState("");
  const [verification, setVerification] = useState<VerificationResult | null>(null);
  const [verifiedParams, setVerifiedParams] = useState<Record<string, unknown> | null>(null);

  const runVerification = useCallback(async () => {
    setVerification(null);
    setVerifiedParams(null);
    let parsed: unknown;
    try {
      parsed = JSON.parse(pasted);
    } catch (e) {
      setVerification({
        ok: false, checks: [], mismatches: [],
        error: `Not valid JSON: ${(e as Error).message}`,
      });
      return;
    }
    // Accept either a bare DeploymentParams or a one-entry bootstrap record file, because
    // both are things an operator plausibly has in front of them.
    if (Array.isArray(parsed)) {
      if (parsed.length !== 1) {
        setVerification({
          ok: false, checks: [], mismatches: [],
          error: `A bootstrap file with ${parsed.length} entries is ambiguous — paste the one deployment to verify.`,
        });
        return;
      }
      parsed = parsed[0];
    }
    const { schemaVersion: _ignored, ...params } = parsed as Record<string, unknown>;
    try {
      const { blueprint } = await loadPinnedBlueprint();
      const result = verifyDeployment(blueprint, params);
      setVerification(result);
      if (result.ok) setVerifiedParams(params);
    } catch (e) {
      setVerification({ ok: false, checks: [], mismatches: [], error: (e as Error).message });
    }
  }, [pasted]);

  // The SDK-shaped download is derived from the SAME record the platform download
  // emits — one deployment must not be able to produce two artefacts that disagree.
  const verifiedEntry = useMemo(() => {
    if (!verifiedParams || !verification?.ok) return null;
    try {
      return toBootstrapRecord(verifiedParams, verification)[0] ?? null;
    } catch {
      return null;
    }
  }, [verifiedParams, verification]);

  const downloadVerifiedRecord = useCallback(() => {
    if (!verifiedParams || !verification) return;
    const record = toBootstrapRecord(verifiedParams, verification);
    const blob = new Blob([JSON.stringify(record, null, 2)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `protocol-bootstraps-${network}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  }, [verifiedParams, verification, network]);

  return (
    <main className="mx-auto max-w-4xl space-y-4 px-4 py-10">
      <h1 className="text-2xl font-bold text-white">Verify a deployment</h1>
      <p className="text-xs text-dark-400">
        Paste a <code>DeploymentParams</code> block, or a one-entry{" "}
        <code>protocol-bootstraps-{network}.json</code>: every script hash is re-derived from the
        pinned blueprint and compared. A record is offered only if all match — the platform
        indexes against this file, so one wrong hash points the indexer at scripts that were never
        deployed.
      </p>
      <textarea
        id="pasted-deployment"
        value={pasted}
        onChange={(e) => setPasted(e.target.value)}
        rows={8}
        spellCheck={false}
        placeholder='{ "protocolParams": { … }, "transfer": { … }, … }'
        className={`w-full ${FIELD}`}
      />
      <button
        type="button"
        onClick={runVerification}
        disabled={!pasted.trim()}
        className="rounded border border-dark-600 px-3 py-1.5 text-xs text-white disabled:opacity-40"
      >
        Verify
      </button>

      {verification && (
        <div className="space-y-2">
          {verification.error && (
            <p className="rounded border border-red-800 bg-red-950/30 p-2 text-xs text-red-200">
              {verification.error}
            </p>
          )}
          {verification.checks.length > 0 && (
            <dl className="grid grid-cols-[auto_auto_1fr] gap-x-3 gap-y-1 font-mono text-xs">
              {verification.checks.map((c) => (
                <div key={c.name} className="contents">
                  <dt className={c.matches ? "text-green-400" : "text-red-400"}>
                    {c.matches ? "match" : "MISMATCH"}
                  </dt>
                  <dd className="text-dark-400">{c.name}</dd>
                  <dd className="break-all text-white">
                    {c.matches ? c.deployed : `deployed ${c.deployed} — derives to ${c.derived}`}
                  </dd>
                </div>
              ))}
            </dl>
          )}
          {verification.ok ? (
            <>
              <p className="text-xs text-green-300">
                All {verification.checks.length} hashes re-derived and matched.
              </p>
              <button
                type="button"
                onClick={downloadVerifiedRecord}
                className="rounded border border-green-700 px-3 py-1.5 text-xs text-green-200"
              >
                Download bootstrap record
              </button>
              {verifiedEntry && <SdkRecordDownload entry={verifiedEntry} network={network} />}
            </>
          ) : (
            <p className="text-xs text-red-300">
              Not verified — no bootstrap record is produced.
              {verification.mismatches.length > 0 &&
                ` ${verification.mismatches.length} of ${verification.checks.length} hashes do not derive from this blueprint.`}
            </p>
          )}
        </div>
      )}
    </main>
  );
}
