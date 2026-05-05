"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Copy, Check, ArrowLeft } from "lucide-react";

interface RecipientUnverifiedPaneProps {
  policyId: string;
  recipientAddress: string;
  /** 404 → "not-verified", 410 → "expired", 425 → "publish-pending". */
  mode: "not-verified" | "expired" | "publish-pending";
  onBack: () => void;
}

export function RecipientUnverifiedPane({
  policyId,
  mode,
  onBack,
}: RecipientUnverifiedPaneProps) {
  const verifyUrl = `${typeof window !== "undefined" ? window.location.origin : ""}/verify/${policyId}`;
  const [copied, setCopied] = useState(false);

  const title =
    mode === "expired"
      ? "Recipient KYC has expired"
      : mode === "publish-pending"
        ? "Recipient verification pending publication"
        : "Recipient hasn't verified for this token";

  const body =
    mode === "expired"
      ? "The recipient was previously verified for this token, but their KYC has expired. " +
        "They need to renew before they can receive transfers."
      : mode === "publish-pending"
        ? "The recipient has completed KYC, but their entry hasn't been published to the on-chain " +
          "allowlist yet. Publication runs automatically every few minutes — try again shortly."
        : "Transfers of this kyc-extended token are only allowed to wallets that have completed KYC. " +
          "Send the recipient the link below — they can verify themselves without owning any of the token first.";

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(verifyUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard not available — input is select-on-click as a fallback.
    }
  };

  return (
    <Card className="p-6 space-y-4">
      <div className="space-y-2">
        <h3 className="text-base font-semibold text-white">{title}</h3>
        <p className="text-sm text-dark-300">{body}</p>
      </div>

      <div className="space-y-2">
        <label className="text-xs text-dark-400 uppercase tracking-wide">
          Verify URL
        </label>
        <div className="flex gap-2">
          <input
            readOnly
            value={verifyUrl}
            onFocus={(e) => e.currentTarget.select()}
            className="flex-1 px-3 py-2 bg-dark-900 border border-dark-700 rounded-md text-xs text-white font-mono"
          />
          <Button
            type="button"
            variant="outline"
            onClick={onCopy}
            className="px-3"
          >
            {copied ? <Check className="h-4 w-4 text-success-400" /> : <Copy className="h-4 w-4" />}
          </Button>
        </div>
      </div>

      <Button type="button" variant="outline" onClick={onBack} className="w-full">
        <ArrowLeft className="h-4 w-4 mr-2" /> Back
      </Button>
    </Card>
  );
}
