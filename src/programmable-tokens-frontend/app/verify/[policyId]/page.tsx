"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { PageContainer } from "@/components/layout/page-container";
import { Card } from "@/components/ui/card";
import { Loader2 } from "lucide-react";
import { useWallet } from "@/hooks/use-wallet";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";
import {
  bindSessionToToken,
  listKycExtendedTokens,
  requestMpfInclusion,
  type KycExtendedTokenSummary,
} from "@/lib/api/kyc-extended";
import { getTokenContext } from "@/lib/api/protocol";
import { ConnectWalletPrompt } from "@/components/verify/ConnectWalletPrompt";
import { VerifyTokenView } from "@/components/verify/VerifyTokenView";
import { KycVerificationFlow } from "@/components/transfer/KycVerificationFlow";
import { getKeriSessionIdForWallet } from "@/lib/utils/keri-session";

const RENEW_GRACE_MS = 7 * 24 * 60 * 60 * 1000;

type ViewState =
  | { kind: "loading" }
  | { kind: "wrong-substandard"; substandardId: string }
  | { kind: "ready"; token: KycExtendedTokenSummary };

export default function VerifyPolicyPage() {
  const params = useParams<{ policyId: string }>();
  const policyId = params?.policyId ?? "";
  const { connected, wallet } = useWallet();

  const [viewState, setViewState] = useState<ViewState>({ kind: "loading" });
  const [walletAddress, setWalletAddress] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  const { status, refresh } = useMpfMembershipStatus(policyId, walletAddress);

  // Resolve wallet address.
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

  // On mount: validate substandard and load token metadata.
  useEffect(() => {
    if (!policyId) return;
    let cancelled = false;
    (async () => {
      try {
        const ctx = await getTokenContext(policyId);
        if (cancelled) return;
        if (ctx.substandardId !== "kyc-extended") {
          setViewState({ kind: "wrong-substandard", substandardId: ctx.substandardId });
          return;
        }
        // Look up display info via the discovery list (cheap; cached at most once).
        const tokens = await listKycExtendedTokens();
        if (cancelled) return;
        const token = tokens.find((t) => t.policyId === policyId)
          ?? { policyId, assetName: "", displayName: policyId.slice(0, 12) + "…", description: null, registeredAt: 0 };
        setViewState({ kind: "ready", token });
      } catch (e) {
        if (cancelled) return;
        setViewState({
          kind: "wrong-substandard",
          substandardId: e instanceof Error ? e.message : "Unknown error",
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [policyId]);

  // Bind session to this token whenever wallet is connected and token is loaded.
  // Fail-soft: if binding fails the user can still verify; auto-upsert just won't
  // happen and they may need to call POST /members manually.
  useEffect(() => {
    if (viewState.kind !== "ready") return;
    if (!walletAddress) return;
    bindSessionToToken(policyId).catch((e) => {
      // eslint-disable-next-line no-console
      console.warn("[verify] bindSessionToToken failed", e);
    });
  }, [viewState, walletAddress, policyId]);

  // Poll while publish is pending so the user sees the transition to "verified"
  // without having to reload — backend root sync runs every ~30s.
  useEffect(() => {
    if (status.kind !== "publish-pending") return;
    const id = setInterval(() => refresh(), 10_000);
    return () => clearInterval(id);
  }, [status.kind, refresh]);

  if (viewState.kind === "loading") {
    return (
      <PageContainer>
        <div className="max-w-2xl mx-auto py-10 flex items-center gap-2 text-sm text-dark-300">
          <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Loading…
        </div>
      </PageContainer>
    );
  }

  if (viewState.kind === "wrong-substandard") {
    return (
      <PageContainer>
        <div className="max-w-2xl mx-auto py-10">
          <Card className="p-6 space-y-2">
            <h1 className="text-lg font-semibold text-white">Verification not required</h1>
            <p className="text-sm text-dark-300">
              This token is not a kyc-extended token (substandard:{" "}
              <code>{viewState.substandardId}</code>). No verification is needed.
            </p>
          </Card>
        </div>
      </PageContainer>
    );
  }

  const { token } = viewState;

  if (!walletAddress) {
    return (
      <PageContainer>
        <div className="max-w-2xl mx-auto py-10 space-y-6">
          <Header token={token} />
          <ConnectWalletPrompt />
        </div>
      </PageContainer>
    );
  }

  if (running) {
    return (
      <PageContainer>
        <div className="max-w-2xl mx-auto py-10 space-y-6">
          <Header token={token} />
          <KycVerificationFlow
            policyId={policyId}
            senderAddress={walletAddress}
            forceFresh
            onBack={() => setRunning(false)}
            onComplete={async (proof) => {
              try {
                // Per-wallet session id — must match the one KycVerificationFlow used.
                const sessionId = getKeriSessionIdForWallet(walletAddress!);
                await requestMpfInclusion(policyId, {
                  boundAddress: walletAddress!,
                  kycSessionId: sessionId,
                  validUntilMs: proof.validUntilMs,
                });
              } catch (e) {
                console.error("[verify] requestMpfInclusion failed", e);
              }
              setRunning(false);
              refresh();
            }}
          />
        </div>
      </PageContainer>
    );
  }

  if (status.kind === "loading") {
    return (
      <PageContainer>
        <div className="max-w-2xl mx-auto py-10 space-y-6">
          <Header token={token} />
          <Card className="p-6 flex items-center gap-2 text-sm text-dark-300">
            <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Checking your status…
          </Card>
        </div>
      </PageContainer>
    );
  }

  let view: React.ReactNode;
  if (status.kind === "verified") {
    const showRenew = status.validUntilMs - Date.now() < RENEW_GRACE_MS;
    view = (
      <VerifyTokenView
        token={token}
        status="verified"
        validUntilMs={status.validUntilMs}
        showRenew={showRenew}
        onChainSynced={status.onChainSynced}
        onStart={() => setRunning(true)}
      />
    );
  } else if (status.kind === "publish-pending") {
    view = (
      <VerifyTokenView
        token={token}
        status="publish-pending"
        onStart={() => setRunning(true)}
      />
    );
  } else if (status.kind === "expired") {
    view = (
      <VerifyTokenView
        token={token}
        status="expired"
        validUntilMs={status.expiredAtMs || undefined}
        onStart={() => setRunning(true)}
      />
    );
  } else {
    view = (
      <VerifyTokenView
        token={token}
        status="not-verified"
        onStart={() => setRunning(true)}
      />
    );
  }

  return (
    <PageContainer>
      <div className="max-w-2xl mx-auto py-10 space-y-6">
        <Header token={token} />
        {view}
      </div>
    </PageContainer>
  );
}

function Header({ token }: { token: KycExtendedTokenSummary }) {
  return (
    <div className="space-y-2">
      <h1 className="text-3xl font-bold text-white">Verify for {token.displayName}</h1>
      <p className="text-xs font-mono text-dark-400 break-all">{token.policyId}</p>
      {token.description && <p className="text-sm text-dark-300">{token.description}</p>}
    </div>
  );
}
