"use client";

import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { ArrowRight, ShieldAlert } from "lucide-react";

interface Props {
  policyId: string;
  tokenName: string;
  mode: "not-verified" | "expired" | "publishing";
}

/**
 * Replaces the per-row Send button on a kyc-extended token when the user lacks
 * a valid leaf or the on-chain root is still catching up.
 *
 * The "publishing" variant is informational and intentionally has no Verify
 * button — the user is verified, just waiting for the next root publish.
 */
export function KycRequiredBanner({ policyId, tokenName, mode }: Props) {
  let message: string;
  let badgeVariant: "warning" | "error" = "error";
  if (mode === "expired") {
    message = `Your KYC for ${tokenName} has expired. Renew to send.`;
  } else if (mode === "publishing") {
    message = "Verification publishing on-chain — Send will be available shortly…";
    badgeVariant = "warning";
  } else {
    message = `KYC required. Verify yourself for ${tokenName} before sending.`;
  }

  return (
    <div className="flex items-center gap-2">
      <Badge variant={badgeVariant} size="sm" className="hidden sm:inline-flex">
        <ShieldAlert className="h-3 w-3 mr-1" />
        {mode === "publishing" ? "Publishing" : mode === "expired" ? "Expired" : "KYC required"}
      </Badge>
      {mode !== "publishing" ? (
        <Link
          href={`/verify/${policyId}`}
          className="text-xs text-primary-400 hover:text-primary-300 inline-flex items-center gap-1"
          title={message}
        >
          Verify <ArrowRight className="h-3 w-3" />
        </Link>
      ) : (
        <span className="text-[10px] text-dark-400 max-w-[10rem]" title={message}>
          publishing…
        </span>
      )}
    </div>
  );
}
