/**
 * Shared rwa-token allowlist membership state. 60s TTL with stale-while-revalidate
 * and module-level in-flight dedupe per (policyId, walletAddress).
 *
 * Surfaces that submit transactions MUST block the `verified && !onChainSynced`
 * case — the proof would not validate against the chain's current member_root_hash.
 *
 * Parallel to {@link useMpfMembershipStatus} but bound to the rwa-token API
 * surface; intentionally independent so the kyc-extended hook can be deleted later
 * without breaking this one.
 */

"use client";

import { useEffect, useState, useCallback, useRef } from "react";
import { extractStakeCredHashFromAddress } from "@/lib/utils/address";
import { getRwaTokenInclusionProof } from "@/lib/api/rwa-token";
import { ApiException } from "@/types/api";

export type RwaTokenMembershipStatus =
  | { kind: "loading" }
  | { kind: "not-verified" }
  | { kind: "expired"; expiredAtMs: number }
  /** In the local allowlist but on-chain root publish is pending (HTTP 425). */
  | { kind: "publish-pending"; addedAtMs: number }
  | {
      kind: "verified";
      validUntilMs: number;
      proofCborHex: string;
      onChainSynced: boolean;
    }
  | { kind: "error"; cause: unknown };

interface CacheEntry {
  status: RwaTokenMembershipStatus;
  fetchedAt: number;
}

const TTL_MS = 60_000;

const cache = new Map<string, CacheEntry>();
const inFlight = new Map<string, Promise<RwaTokenMembershipStatus>>();

function cacheKey(policyId: string, walletAddress: string | null) {
  return `${policyId}:${walletAddress ?? ""}`;
}

async function probe(policyId: string, walletAddress: string): Promise<RwaTokenMembershipStatus> {
  let memberPkh: string;
  try {
    memberPkh = extractStakeCredHashFromAddress(walletAddress);
  } catch (e) {
    return { kind: "error", cause: e };
  }
  try {
    const proof = await getRwaTokenInclusionProof(policyId, memberPkh);
    return {
      kind: "verified",
      validUntilMs: proof.validUntilMs,
      proofCborHex: proof.proofCborHex,
      onChainSynced: proof.rootHashLocal === proof.rootHashOnchain,
    };
  } catch (e) {
    if (e instanceof ApiException) {
      if (e.status === 404) return { kind: "not-verified" };
      if (e.status === 410) {
        const expiredAtMs = readNumericFromError(e, "validUntilMs");
        return { kind: "expired", expiredAtMs };
      }
      if (e.status === 425) {
        const addedAtMs = readNumericFromError(e, "addedAt");
        return { kind: "publish-pending", addedAtMs };
      }
    }
    return { kind: "error", cause: e };
  }
}

function readNumericFromError(e: ApiException, field: string): number {
  try {
    const body = JSON.parse(e.message);
    if (body && typeof body[field] === "number") return body[field];
  } catch {
    // not JSON
  }
  return 0;
}

function getOrFetch(policyId: string, walletAddress: string): Promise<RwaTokenMembershipStatus> {
  const key = cacheKey(policyId, walletAddress);
  const existing = inFlight.get(key);
  if (existing) return existing;

  const p = probe(policyId, walletAddress)
    .then((status) => {
      cache.set(key, { status, fetchedAt: Date.now() });
      return status;
    })
    .finally(() => {
      inFlight.delete(key);
    });
  inFlight.set(key, p);
  return p;
}

interface UseRwaTokenMembershipStatusResult {
  status: RwaTokenMembershipStatus;
  refresh: () => void;
}

export function useRwaTokenMembershipStatus(
  policyId: string | null,
  walletAddress: string | null,
): UseRwaTokenMembershipStatusResult {
  const [status, setStatus] = useState<RwaTokenMembershipStatus>({ kind: "loading" });
  const lastKeyRef = useRef<string | null>(null);
  const refreshTokenRef = useRef(0);

  useEffect(() => {
    if (!policyId || !walletAddress) {
      setStatus({ kind: "loading" });
      lastKeyRef.current = null;
      return;
    }
    const key = cacheKey(policyId, walletAddress);
    lastKeyRef.current = key;

    const cached = cache.get(key);
    const isFresh = cached && Date.now() - cached.fetchedAt < TTL_MS;
    if (isFresh) {
      setStatus(cached.status);
      return;
    }
    setStatus({ kind: "loading" });

    const myToken = ++refreshTokenRef.current;
    getOrFetch(policyId, walletAddress).then((next) => {
      if (lastKeyRef.current !== key) return;
      if (myToken !== refreshTokenRef.current) return;
      setStatus(next);
    });
  }, [policyId, walletAddress]);

  const refresh = useCallback(() => {
    if (!policyId || !walletAddress) return;
    const key = cacheKey(policyId, walletAddress);
    cache.delete(key);
    inFlight.delete(key);
    setStatus({ kind: "loading" });
    const myToken = ++refreshTokenRef.current;
    getOrFetch(policyId, walletAddress).then((next) => {
      if (lastKeyRef.current !== key) return;
      if (myToken !== refreshTokenRef.current) return;
      setStatus(next);
    });
  }, [policyId, walletAddress]);

  return { status, refresh };
}
