"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { ArrowRight } from "lucide-react";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";

/** Common shape across substandards that support KYC enrollment on the
 *  verify page. Kept minimal so it can wrap kyc-extended and rwa-token
 *  summaries (and any future MPF-allowlist substandard). */
export interface VerifiableTokenSummary {
  policyId: string;
  displayName: string;
  description?: string | null;
  /** Drives the destination URL:
   *    kyc-extended   → /verify/[policyId]
   *    rwa-token → /verify/rwa-token/[policyId]
   *  The rwa-token verify page handles the optional requires_receiver_kyc
   *  toggle (skips MPF enrollment when off). */
  kind: "kyc-extended" | "rwa-token";
  /** Substandard-specific hint shown as helper text. */
  requiresReceiverKyc?: boolean;
}

interface Props {
  token: VerifiableTokenSummary;
  walletAddress: string | null;
}

export function TokenRowVerify({ token, walletAddress }: Props) {
  // useMpfMembershipStatus probes the kyc-extended allowlist endpoint by
  // policy id. The rwa-token inclusion endpoint lives at
  // /rwa-token/{policyId}/proofs/{memberPkh} (same shape); extending
  // the hook to multi-substandard is a follow-up. For now only show the
  // membership badge for kyc-extended.
  const { status } = useMpfMembershipStatus(
    token.kind === "kyc-extended" ? token.policyId : "",
    token.kind === "kyc-extended" ? walletAddress : null,
  );

  let badge: React.ReactNode = null;
  if (token.kind === "kyc-extended" && walletAddress) {
    if (status.kind === "verified") {
      badge = status.onChainSynced
        ? <Badge variant="success" size="sm">Verified</Badge>
        : <Badge variant="warning" size="sm">Publishing on-chain…</Badge>;
    } else if (status.kind === "expired") {
      badge = <Badge variant="warning" size="sm">Expired</Badge>;
    }
  }
  const substandardBadge = token.kind === "rwa-token"
    ? <Badge variant="default" size="sm">RWA Token</Badge>
    : null;

  const href = token.kind === "rwa-token"
    ? `/verify/rwa-token/${token.policyId}`
    : `/verify/${token.policyId}`;

  return (
    <Link href={href}>
      <Card className="p-4 hover:border-primary-500 transition-colors cursor-pointer">
        <div className="flex items-start justify-between gap-4">
          <div className="flex-1 space-y-1">
            <div className="flex items-center gap-2">
              <h3 className="text-base font-semibold text-white">{token.displayName}</h3>
              {substandardBadge}
            </div>
            <p className="text-xs font-mono text-dark-400 truncate">{token.policyId}</p>
            {token.description && (
              <p className="text-sm text-dark-300 mt-1">{token.description}</p>
            )}
            {token.kind === "rwa-token" && token.requiresReceiverKyc === false && (
              <p className="text-xs text-dark-500 italic mt-1">
                Receiver KYC is currently disabled for this token — enrollment is optional.
              </p>
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
