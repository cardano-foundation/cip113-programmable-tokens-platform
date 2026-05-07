"use client";

import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { CheckCircle, AlertCircle, Loader2, Shield } from "lucide-react";
import type { KycExtendedTokenSummary } from "@/lib/api/kyc-extended";

interface Props {
  token: KycExtendedTokenSummary;
  status: "not-verified" | "verified" | "expired" | "publish-pending";
  validUntilMs?: number;
  showRenew?: boolean;
  onChainSynced?: boolean;
  onStart: () => void;
}

export function VerifyTokenView({
  token,
  status,
  validUntilMs,
  showRenew,
  onChainSynced,
  onStart,
}: Props) {
  if (status === "verified") {
    const expiresOn = validUntilMs ? new Date(validUntilMs).toLocaleDateString() : "—";
    return (
      <Card className="p-6 space-y-4">
        <div className="flex items-center gap-3">
          <CheckCircle className="h-8 w-8 text-success-400" />
          <div>
            <h3 className="text-lg font-semibold text-white">Verified</h3>
            <p className="text-sm text-dark-300">
              You&apos;re verified for {token.displayName}. Expires on {expiresOn}.
            </p>
            {onChainSynced === false && (
              <p className="text-xs text-warning-400 mt-1">
                Publishing on-chain — Send will be available shortly…
              </p>
            )}
          </div>
        </div>
        {showRenew && (
          <Button type="button" variant="outline" onClick={onStart} className="w-full">
            Renew KYC
          </Button>
        )}
      </Card>
    );
  }

  if (status === "publish-pending") {
    return (
      <Card className="p-6 space-y-4">
        <div className="flex items-center gap-3">
          <div className="relative">
            <CheckCircle className="h-8 w-8 text-success-400" />
            <Loader2 className="h-4 w-4 absolute -bottom-1 -right-1 animate-spin text-warning-400" />
          </div>
          <div>
            <h3 className="text-lg font-semibold text-white">Verification submitted</h3>
            <p className="text-sm text-dark-300">
              Your KYC is complete and you&apos;ve been added to the local allowlist for {token.displayName}.
              Publishing the new allowlist on-chain takes a minute or two — once it&apos;s confirmed,
              other holders can send this token to you. This page will refresh automatically.
            </p>
          </div>
        </div>
      </Card>
    );
  }

  if (status === "expired") {
    const expiredOn = validUntilMs ? new Date(validUntilMs).toLocaleDateString() : "—";
    return (
      <Card className="p-6 space-y-4">
        <div className="flex items-center gap-3">
          <AlertCircle className="h-8 w-8 text-warning-400" />
          <div>
            <h3 className="text-lg font-semibold text-white">Verification expired</h3>
            <p className="text-sm text-dark-300">
              Your KYC for {token.displayName} expired on {expiredOn}. Verify again to continue.
            </p>
          </div>
        </div>
        <Button type="button" variant="primary" onClick={onStart} className="w-full">
          Verify Again
        </Button>
      </Card>
    );
  }

  // not-verified
  return (
    <Card className="p-6 space-y-4">
      <div className="flex items-center gap-3">
        <Shield className="h-8 w-8 text-primary-400" />
        <div>
          <h3 className="text-lg font-semibold text-white">Verify for {token.displayName}</h3>
          <p className="text-sm text-dark-300">
            You haven&apos;t verified for this token yet. Complete KYC to be added to the
            on-chain allowlist — required so other holders can send this token to you.
            (Sending tokens you already hold uses your own KYC attestation and doesn&apos;t
            require being in the allowlist.)
          </p>
        </div>
      </div>
      <Button type="button" variant="primary" onClick={onStart} className="w-full">
        Verify Now
      </Button>
    </Card>
  );
}
