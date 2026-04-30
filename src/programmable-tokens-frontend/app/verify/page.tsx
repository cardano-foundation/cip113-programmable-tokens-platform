"use client";

import { useEffect, useState, useMemo } from "react";
import { PageContainer } from "@/components/layout/page-container";
import { Input } from "@/components/ui/input";
import { useWallet } from "@/hooks/use-wallet";
import { listKycExtendedTokens, type KycExtendedTokenSummary } from "@/lib/api/kyc-extended";
import { TokenRowVerify } from "@/components/verify/TokenRowVerify";
import { EmptyState } from "@/components/verify/EmptyState";

export default function VerifyIndexPage() {
  const { connected, wallet } = useWallet();
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [tokens, setTokens] = useState<KycExtendedTokenSummary[] | null>(null);
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

  // Load token list once on mount.
  useEffect(() => {
    let cancelled = false;
    listKycExtendedTokens()
      .then((list) => {
        if (cancelled) return;
        setTokens(list);
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
            Browse all kyc-extended tokens registered on this network. Pick one to
            complete KYC — you don&apos;t need to own the token to verify.
          </p>
        </header>

        <Input
          type="search"
          placeholder="Search by name or policy id…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />

        {error && (
          <EmptyState message={`Error loading tokens: ${error}`} />
        )}

        {!error && filtered === null && (
          <EmptyState message="Loading…" />
        )}

        {!error && filtered && filtered.length === 0 && (
          <EmptyState message={tokens && tokens.length === 0
            ? "No kyc-extended tokens registered yet."
            : "No tokens match your search."} />
        )}

        {!error && filtered && filtered.length > 0 && (
          <div className="space-y-3">
            {filtered.map((token) => (
              <TokenRowVerify
                key={token.policyId}
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
