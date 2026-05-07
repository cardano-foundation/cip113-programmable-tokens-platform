"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { ArrowRight } from "lucide-react";
import type { KycExtendedTokenSummary } from "@/lib/api/kyc-extended";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";

interface Props {
  token: KycExtendedTokenSummary;
  walletAddress: string | null;
}

export function TokenRowVerify({ token, walletAddress }: Props) {
  const { status } = useMpfMembershipStatus(token.policyId, walletAddress);

  let badge: React.ReactNode = null;
  if (walletAddress) {
    if (status.kind === "verified") {
      badge = status.onChainSynced
        ? <Badge variant="success" size="sm">Verified</Badge>
        : <Badge variant="warning" size="sm">Publishing on-chain…</Badge>;
    } else if (status.kind === "expired") {
      badge = <Badge variant="warning" size="sm">Expired</Badge>;
    }
  }

  return (
    <Link href={`/verify/${token.policyId}`}>
      <Card className="p-4 hover:border-primary-500 transition-colors cursor-pointer">
        <div className="flex items-start justify-between gap-4">
          <div className="flex-1 space-y-1">
            <h3 className="text-base font-semibold text-white">{token.displayName}</h3>
            <p className="text-xs font-mono text-dark-400 truncate">{token.policyId}</p>
            {token.description && (
              <p className="text-sm text-dark-300 mt-1">{token.description}</p>
            )}
          </div>
          <div className="flex flex-col items-end gap-2 shrink-0">
            {badge}
            <span className="text-xs text-primary-400 inline-flex items-center gap-1">
              Verify <ArrowRight className="h-3 w-3" />
            </span>
          </div>
        </div>
      </Card>
    </Link>
  );
}
