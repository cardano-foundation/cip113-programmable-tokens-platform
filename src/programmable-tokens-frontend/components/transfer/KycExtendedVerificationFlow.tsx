"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { ArrowLeft, Loader2, Shield, RefreshCw } from "lucide-react";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";
import { extractStakeCredHashFromAddress } from "@/lib/utils/address";
import { getMpfInclusionProof } from "@/lib/api/kyc-extended";
import { ApiException } from "@/types/api";
import { KycVerificationFlow } from "./KycVerificationFlow";
import { RecipientUnverifiedPane } from "./RecipientUnverifiedPane";
import { getKycProof, type KycProofCookie } from "@/lib/utils/kyc-cookie";

/**
 * Tagged union describing how the sender will be authenticated to the kyc-extended
 * transfer validator. Routed by {@code TransferModal} to the right request fields.
 */
export type SenderProofPayload =
  | { kind: "attestation"; senderProof: KycProofCookie }
  | { kind: "membership"; proofCborHex: string; validUntilMs: number };

export interface KycExtendedVerificationResult {
  sender: SenderProofPayload;
  /** Recipient MPF proof. Empty when sending to self (validator filters self-pkh out of receivers). */
  receiverProofCborHex: string | null;
  receiverValidUntilMs: number | null;
}

interface Props {
  policyId: string;
  senderAddress: string;
  recipientAddress: string;
  onComplete: (result: KycExtendedVerificationResult) => void;
  onBack: () => void;
}

type Phase =
  | { kind: "deciding-sender" }
  | { kind: "have-cookie"; cookie: KycProofCookie }
  | { kind: "probing-membership" }                    // checking if user can use Membership fast path
  | { kind: "running-keri" }
  | { kind: "probing-receiver"; sender: SenderProofPayload }
  | { kind: "receiver-not-verified"; sender: SenderProofPayload; mode: "not-verified" | "expired" | "publish-pending" }
  | { kind: "error"; message: string };

export function KycExtendedVerificationFlow({
  policyId,
  senderAddress,
  recipientAddress,
  onComplete,
  onBack,
}: Props) {
  // Membership is an optional fast-path; an Attestation cookie alone is sufficient.
  const senderStatus = useMpfMembershipStatus(policyId, senderAddress);
  const [phase, setPhase] = useState<Phase>({ kind: "deciding-sender" });

  useEffect(() => {
    if (phase.kind !== "deciding-sender") return;
    const cookie = getKycProof(policyId, senderAddress);
    if (cookie) {
      setPhase({ kind: "have-cookie", cookie });
      return;
    }
    setPhase({ kind: "probing-membership" });
  }, [phase, policyId, senderAddress]);

  useEffect(() => {
    if (phase.kind !== "probing-membership") return;
    if (senderStatus.status.kind === "loading") return;

    if (senderStatus.status.kind === "verified" && senderStatus.status.onChainSynced) {
      setPhase({
        kind: "probing-receiver",
        sender: {
          kind: "membership",
          proofCborHex: senderStatus.status.proofCborHex,
          validUntilMs: senderStatus.status.validUntilMs,
        },
      });
      return;
    }
    // verified-but-publishing, expired, not-verified, error → fall through to KERI.
    setPhase({ kind: "running-keri" });
  }, [phase, senderStatus.status]);

  useEffect(() => {
    if (phase.kind !== "probing-receiver") return;
    let cancelled = false;

    if (!recipientAddress || !recipientAddress.startsWith("addr")) {
      setPhase({ kind: "error", message: "Please enter a valid recipient Cardano address (starting with addr1) before verifying." });
      return;
    }
    if (!senderAddress || !senderAddress.startsWith("addr")) {
      setPhase({ kind: "error", message: "Sender wallet address is not available. Please reconnect your wallet." });
      return;
    }

    let recipientPkh: string;
    let senderPkh: string;
    try {
      recipientPkh = extractStakeCredHashFromAddress(recipientAddress);
      senderPkh = extractStakeCredHashFromAddress(senderAddress);
    } catch (e) {
      setPhase({ kind: "error", message: e instanceof Error ? e.message : String(e) });
      return;
    }
    // Validator filters sender pkh out of receivers, so self-sends need no recipient proof.
    if (recipientPkh.toLowerCase() === senderPkh.toLowerCase()) {
      onComplete({ sender: phase.sender, receiverProofCborHex: null, receiverValidUntilMs: null });
      return;
    }
    getMpfInclusionProof(policyId, recipientPkh)
      .then((proof) => {
        if (cancelled) return;
        onComplete({
          sender: phase.sender,
          receiverProofCborHex: proof.proofCborHex,
          receiverValidUntilMs: proof.validUntilMs,
        });
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        if (e instanceof ApiException) {
          if (e.status === 404) {
            setPhase({ kind: "receiver-not-verified", sender: phase.sender, mode: "not-verified" });
            return;
          }
          if (e.status === 410) {
            setPhase({ kind: "receiver-not-verified", sender: phase.sender, mode: "expired" });
            return;
          }
          if (e.status === 425) {
            setPhase({ kind: "receiver-not-verified", sender: phase.sender, mode: "publish-pending" });
            return;
          }
        }
        setPhase({
          kind: "error",
          message: e instanceof Error ? e.message : "Could not fetch recipient inclusion proof",
        });
      });
    return () => {
      cancelled = true;
    };
  }, [phase, onComplete, policyId, recipientAddress, senderAddress]);

  // ── Render ────────────────────────────────────────────────────────────────

  if (phase.kind === "deciding-sender" || phase.kind === "probing-membership") {
    return (
      <Card className="p-6 flex items-center gap-2 text-sm text-dark-300">
        <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Preparing your KYC…
      </Card>
    );
  }

  if (phase.kind === "have-cookie") {
    const cookie = phase.cookie;
    const expires = new Date(cookie.validUntilMs).toLocaleString();
    return (
      <Card className="p-6 space-y-4">
        <div className="flex items-start gap-3">
          <Shield className="h-8 w-8 text-success-400" />
          <div>
            <h3 className="text-base font-semibold text-white">Use existing KYC?</h3>
            <p className="text-sm text-dark-300 mt-1">
              You already have a KYC attestation for this token (expires {expires}). Reuse it,
              or refresh to fetch a new one.
            </p>
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            type="button"
            variant="primary"
            className="flex-1"
            onClick={() =>
              setPhase({
                kind: "probing-receiver",
                sender: { kind: "attestation", senderProof: cookie },
              })
            }
          >
            Use existing
          </Button>
          <Button
            type="button"
            variant="outline"
            className="flex-1"
            onClick={() => setPhase({ kind: "running-keri" })}
          >
            <RefreshCw className="h-4 w-4 mr-2" /> Refresh
          </Button>
        </div>
        <Button type="button" variant="ghost" onClick={onBack} className="w-full">
          <ArrowLeft className="h-4 w-4 mr-2" /> Cancel
        </Button>
      </Card>
    );
  }

  if (phase.kind === "running-keri") {
    return (
      <KycVerificationFlow
        policyId={policyId}
        senderAddress={senderAddress}
        onBack={onBack}
        onComplete={(senderProof) => {
          setPhase({
            kind: "probing-receiver",
            sender: { kind: "attestation", senderProof },
          });
        }}
      />
    );
  }

  if (phase.kind === "probing-receiver") {
    return (
      <Card className="p-6 flex items-center gap-2 text-sm text-dark-300">
        <Loader2 className="h-4 w-4 animate-spin text-primary-400" /> Checking recipient verification…
      </Card>
    );
  }

  if (phase.kind === "receiver-not-verified") {
    return (
      <RecipientUnverifiedPane
        policyId={policyId}
        recipientAddress={recipientAddress}
        mode={phase.mode}
        onBack={onBack}
      />
    );
  }

  return (
    <Card className="p-6 space-y-3">
      <h3 className="text-base font-semibold text-white">Something went wrong</h3>
      <p className="text-sm text-dark-300">{phase.message}</p>
      <Button type="button" variant="outline" onClick={onBack} className="w-full">
        <ArrowLeft className="h-4 w-4 mr-2" /> Back
      </Button>
    </Card>
  );
}
