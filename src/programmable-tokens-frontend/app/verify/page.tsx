"use client";

import { useEffect, useState, useMemo } from "react";
import { PageContainer } from "@/components/layout/page-container";
import { Input } from "@/components/ui/input";
import { useWallet } from "@/hooks/use-wallet";
import { listKycExtendedTokens } from "@/lib/api/kyc-extended";
import { listRwaTokens } from "@/lib/api/rwa-token";
import {
  TokenRowVerify,
  type VerifiableTokenSummary,
} from "@/components/verify/TokenRowVerify";
import { EmptyState } from "@/components/verify/EmptyState";

export default function VerifyIndexPage() {
  const { connected, wallet } = useWallet();
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [tokens, setTokens] = useState<VerifiableTokenSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");

  // Resolve wallet address (when connected) so we can probe per-token membership.
  useEffect(() => {
    if (!connected || !wallet) {
      setWalletAddress(null);
      return;
    }
    let cancelled = false;
    wallet.getUsedAddresses()
      .then((addrs: string[]) => {
        if (cancelled) return;
        setWalletAddress(addrs[0] ?? null);
      })
      .catch(() => {
        if (cancelled) return;
        setWalletAddress(null);
      });
    return () => {
      cancelled = true;
    };
  }, [connected, wallet]);

  // Load both kyc-extended AND rwa-token registrations in parallel and
  // merge into a single list tagged with `kind`. Failure on one list doesn't
  // hide rows from the other — surface a partial-error notice instead.
  useEffect(() => {
    let cancelled = false;
    Promise.allSettled([listKycExtendedTokens(), listRwaTokens()])
      .then(([kycExtRes, secTokRes]) => {
        if (cancelled) return;
        const merged: VerifiableTokenSummary[] = [];
        if (kycExtRes.status === "fulfilled") {
          for (const t of kycExtRes.value) {
            merged.push({
              policyId: t.policyId,
              displayName: t.displayName,
              description: t.description,
              kind: "kyc-extended",
            });
          }
        }
        if (secTokRes.status === "fulfilled") {
          for (const t of secTokRes.value) {
            merged.push({
              policyId: t.policyId,
              displayName: t.displayName,
              description: t.description,
              kind: "rwa-token",
              requiresReceiverKyc: t.requiresReceiverKyc,
            });
          }
        }
        setTokens(merged);
        if (kycExtRes.status === "rejected" && secTokRes.status === "rejected") {
          setError("Failed to load tokens from both lists");
        } else if (kycExtRes.status === "rejected") {
          setError("Could not load kyc-extended tokens (rwa-tokens shown).");
        } else if (secTokRes.status === "rejected") {
          setError("Could not load rwa-tokens (kyc-extended shown).");
        }
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : "Failed to load tokens");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(() => {
    if (!tokens) return null;
    const q = search.trim().toLowerCase();
    if (!q) return tokens;
    return tokens.filter((t) => {
      return (
        t.displayName.toLowerCase().includes(q) ||
        t.policyId.toLowerCase().startsWith(q)
      );
    });
  }, [tokens, search]);

  return (
    <PageContainer>
      <div className="max-w-3xl mx-auto space-y-6">
        <header className="space-y-2">
          <h1 className="text-3xl font-bold text-white">Verify for a Token</h1>
          <p className="text-sm text-dark-300">
            Browse every token that supports on-chain KYC enrollment (kyc-extended
            and rwa-token). Pick one to complete KYC — you don&apos;t need to
            own the token to verify.
          </p>
        </header>

        <Input
          type="search"
          placeholder="Search by name or policy id…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />

        {error && tokens === null && (
          <EmptyState message={`Error loading tokens: ${error}`} />
        )}

        {error && tokens !== null && tokens.length > 0 && (
          <p className="text-xs text-orange-400 -mt-2">{error}</p>
        )}

        {!error && filtered === null && (
          <EmptyState message="Loading…" />
        )}

        {filtered && filtered.length === 0 && (
          <EmptyState message={tokens && tokens.length === 0
            ? "No kyc-extended or rwa-token registrations on this network yet."
            : "No tokens match your search."} />
        )}

        {filtered && filtered.length > 0 && (
          <div className="space-y-3">
            {filtered.map((token) => (
              <TokenRowVerify
                key={`${token.kind}:${token.policyId}`}
                token={token}
                walletAddress={walletAddress}
              />
            ))}
          </div>
        )}
      </div>
    </PageContainer>
  );
}
