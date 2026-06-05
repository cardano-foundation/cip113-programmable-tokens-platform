"use client";

import { useState, useEffect, useRef } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import {
  X,
  Send,
  CheckCircle,
  ExternalLink,
  Shield,
  Loader2,
  AlertCircle,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { transferToken, getTokenContext } from "@/lib/api";
import { TransferTokenRequest, ParsedAsset, ApiException } from "@/types/api";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { KycVerificationFlow } from "./KycVerificationFlow";
import { getKycProof, clearKycProof, type KycProofCookie } from "@/lib/utils/kyc-cookie";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";
import { useSecurityTokenMembershipStatus } from "@/hooks/useSecurityTokenMembershipStatus";
import { getMpfInclusionProof, requestMpfInclusion } from "@/lib/api/kyc-extended";
import { getSecurityTokenInclusionProof, requestSecurityTokenInclusion } from "@/lib/api/security-token";
import { extractStakeCredHashFromAddress } from "@/lib/utils/address";
import { getKeriSessionIdForWallet } from "@/lib/utils/keri-session";

type TransactionBuilder = "sdk" | "backend";

interface TransferModalProps {
  isOpen: boolean;
  onClose: () => void;
  asset: ParsedAsset;
  senderAddress: string;
}

type TransferStep = "form" | "kyc-verify" | "kyc-sender" | "signing" | "success";

type RecipientCheckStatus =
  | { kind: "idle" }
  | { kind: "checking" }
  | { kind: "verified"; proofCborHex: string; validUntilMs: number }
  | { kind: "self" }
  | { kind: "not-verified" }
  | { kind: "expired" }
  | { kind: "publish-pending" }
  | { kind: "error"; message: string };

export function TransferModal({
  isOpen,
  onClose,
  asset,
  senderAddress,
}: TransferModalProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const { getProtocol, ensureSubstandard, available: sdkAvailable } = useCIP113();
  const [step, setStep] = useState<TransferStep>("form");
  const [quantity, setQuantity] = useState("");
  const [recipientAddress, setRecipientAddress] = useState("");
  const [transactionBuilder, setTransactionBuilder] = useState<TransactionBuilder>(
    sdkAvailable ? "sdk" : "backend"
  );
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  // KYC state
  const [isKycToken, setIsKycToken] = useState(false);
  const [isKycExtendedToken, setIsKycExtendedToken] = useState(false);
  const [isSecurityTokenToken, setIsSecurityTokenToken] = useState(false);
  /** Security-token only: per-token toggle from the global-state datum. Defaults to true
   *  (the safer regulatory-compliance posture) until the token context resolves. */
  const [securityTokenRequiresReceiverKyc, setSecurityTokenRequiresReceiverKyc] = useState(true);
  /** Security-token only: live `transfers_paused` flag from the global-state datum.
   *  When true, the on-chain transfer_logic validator rejects every transfer — we
   *  surface a banner and disable the Send button so the user doesn't burn fees
   *  on a tx the network will refuse. */
  const [securityTokenTransfersPaused, setSecurityTokenTransfersPaused] = useState(false);
  const [kycProof, setKycProofState] = useState<KycProofCookie | null>(null);

  const [recipientCheckStatus, setRecipientCheckStatus] = useState<RecipientCheckStatus>({ kind: "idle" });
  const recipientProbingToken = useRef(0);

  const [errors, setErrors] = useState({
    quantity: "",
    recipientAddress: "",
  });

  const policyId = asset.unit.substring(0, 56);

  // Sender MPF membership (kyc-extended only — null policyId disables the hook)
  const senderMembership = useMpfMembershipStatus(
    isKycExtendedToken ? policyId : null,
    isKycExtendedToken ? senderAddress : null,
  );
  // Same shape for security-token, on its own API surface.
  const securityTokenSenderMembership = useSecurityTokenMembershipStatus(
    isSecurityTokenToken ? policyId : null,
    isSecurityTokenToken ? senderAddress : null,
  );

  const senderMpfReady = isSecurityTokenToken
    ? securityTokenSenderMembership.status.kind === "verified"
        && securityTokenSenderMembership.status.onChainSynced
    : senderMembership.status.kind === "verified"
        && senderMembership.status.onChainSynced;
  // For security-token, the on-chain transfer_logic_script requires the sender
  // to be in the MPF tree — a fresh kycProof cookie alone won't satisfy the
  // validator until the new root is published. So gate STRICTLY on on-chain
  // membership for security-token. For kyc-extended, the cookie is an accepted
  // fallback (the validator filters senders out of receiver_witnesses).
  const senderReady = isSecurityTokenToken
    ? senderMpfReady
    : isKycExtendedToken
      ? senderMpfReady || !!kycProof
      : isKycToken
        ? !!kycProof
        : true;

  /** When `requiresReceiverKyc` is false on a security-token, we don't gate Send on the
   *  recipient probe — the validator skips the check anyway. */
  const recipientReady =
    !(isKycExtendedToken || (isSecurityTokenToken && securityTokenRequiresReceiverKyc)) ||
    recipientCheckStatus.kind === "verified" ||
    recipientCheckStatus.kind === "self";

  // Reset state when modal opens
  useEffect(() => {
    if (isOpen) {
      setStep("form");
      setQuantity("");
      setRecipientAddress("");
      setTxHash(null);
      setErrors({ quantity: "", recipientAddress: "" });
      setKycProofState(null);
      setRecipientCheckStatus({ kind: "idle" });
      setIsKycToken(false);
      setIsKycExtendedToken(false);
      setIsSecurityTokenToken(false);
      setSecurityTokenRequiresReceiverKyc(true);
      setSecurityTokenTransfersPaused(false);

      getTokenContext(policyId)
        .then((ctx) => {
          if (ctx.substandardId === "kyc") {
            setIsKycToken(true);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
          } else if (ctx.substandardId === "kyc-extended") {
            setIsKycToken(true);
            setIsKycExtendedToken(true);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
          } else if (ctx.substandardId === "security-token") {
            setIsKycToken(true);
            setIsSecurityTokenToken(true);
            setSecurityTokenRequiresReceiverKyc(ctx.requiresReceiverKyc ?? true);
            setSecurityTokenTransfersPaused(ctx.transfersPaused ?? false);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
          }
        })
        .catch(() => {});
    }
  }, [isOpen, policyId]);

  // Recipient MPF membership probe. We probe for kyc-extended and ALWAYS for
  // security-token so the admin can see the receiver's enrollment status even
  // when {@code requires_receiver_kyc} is false. Whether the probe gates the
  // Send button is decided separately in {@link recipientReady} below.
  useEffect(() => {
    const needsProbe = isKycExtendedToken || isSecurityTokenToken;
    if (!needsProbe) {
      setRecipientCheckStatus({ kind: "idle" });
      return;
    }

    const addr = recipientAddress.trim();
    if (!addr || !addr.startsWith("addr")) {
      setRecipientCheckStatus({ kind: "idle" });
      return;
    }

    let recipientPkh: string;
    let senderPkh: string;
    try {
      recipientPkh = extractStakeCredHashFromAddress(addr);
      senderPkh = extractStakeCredHashFromAddress(senderAddress);
    } catch {
      setRecipientCheckStatus({ kind: "idle" });
      return;
    }

    if (recipientPkh.toLowerCase() === senderPkh.toLowerCase()) {
      setRecipientCheckStatus({ kind: "self" });
      return;
    }

    setRecipientCheckStatus({ kind: "checking" });
    const token = ++recipientProbingToken.current;

    const probeFn = isSecurityTokenToken
      ? () => getSecurityTokenInclusionProof(policyId, recipientPkh)
      : () => getMpfInclusionProof(policyId, recipientPkh);

    probeFn()
      .then((proof) => {
        if (recipientProbingToken.current !== token) return;
        setRecipientCheckStatus({
          kind: "verified",
          proofCborHex: proof.proofCborHex,
          validUntilMs: proof.validUntilMs,
        });
      })
      .catch((e: unknown) => {
        if (recipientProbingToken.current !== token) return;
        if (e instanceof ApiException) {
          if (e.status === 404) { setRecipientCheckStatus({ kind: "not-verified" }); return; }
          if (e.status === 410) { setRecipientCheckStatus({ kind: "expired" }); return; }
          if (e.status === 425) { setRecipientCheckStatus({ kind: "publish-pending" }); return; }
        }
        setRecipientCheckStatus({ kind: "error", message: "Could not check recipient status" });
      });
  }, [recipientAddress, isKycExtendedToken, isSecurityTokenToken, securityTokenRequiresReceiverKyc, policyId, senderAddress]);

  const handleSetMax = () => {
    setQuantity(asset.amount.toString());
    setErrors((prev) => ({ ...prev, quantity: "" }));
  };

  const validateForm = () => {
    const newErrors = { quantity: "", recipientAddress: "" };
    const qty = parseInt(quantity);

    if (!quantity || isNaN(qty) || qty <= 0) {
      newErrors.quantity = "Enter a valid positive amount";
    } else if (qty > parseInt(asset.amount)) {
      newErrors.quantity = `Maximum available: ${asset.amount}`;
    }

    if (!recipientAddress.trim()) {
      newErrors.recipientAddress = "Recipient address is required";
    } else if (!recipientAddress.startsWith("addr")) {
      newErrors.recipientAddress = "Invalid Cardano address";
    }

    if (recipientAddress.trim() === senderAddress) {
      newErrors.recipientAddress = "Cannot transfer to yourself";
    }

    setErrors(newErrors);
    return !Object.values(newErrors).some((error) => error !== "");
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm()) return;

    // Belt-and-braces guard: the Send button is disabled when transfers are
    // paused, but an Enter-key submit or dev-tools poke could still reach
    // here. Surface a clear toast rather than building a tx the on-chain
    // validator will reject.
    if (isSecurityTokenToken && securityTokenTransfersPaused) {
      showToast({
        title: "Transfers paused",
        description: "The token admin has paused transfers via the global-state PauseTransfers action. Wait for the admin to re-enable transfers and try again.",
        variant: "warning",
      });
      return;
    }

    try {
      setIsBuilding(true);

      let unsignedCborTx: string;

      const useSdk = transactionBuilder === "sdk" && !isKycToken;

      if (useSdk) {
        showToast({ title: "Building Transaction", description: "Initializing CIP-113 SDK...", variant: "default" });
        const substandardId = await ensureSubstandard(asset.policyId, asset.assetNameHex);
        const protocol = await getProtocol();
        showToast({ title: "Building Transaction", description: `Building ${substandardId} transfer with CIP-113 SDK...`, variant: "default" });
        const result = await protocol.transfer({
          senderAddress,
          recipientAddress: recipientAddress.trim(),
          tokenPolicyId: asset.policyId,
          assetName: asset.assetNameHex,
          quantity: BigInt(quantity),
          substandardId,
        });
        unsignedCborTx = result.cbor;
      } else {
        const request: TransferTokenRequest = {
          senderAddress,
          unit: asset.unit,
          quantity,
          recipientAddress: recipientAddress.trim(),
        };

        if (isKycExtendedToken || isSecurityTokenToken) {
          // Sender proof: membership (preferred) or attestation cookie
          const ms = isSecurityTokenToken
              ? securityTokenSenderMembership.status
              : senderMembership.status;
          if (ms.kind === "verified" && ms.onChainSynced) {
            request.senderMpfProofCborHex = ms.proofCborHex;
            request.senderMpfValidUntilMs = ms.validUntilMs;
          } else if (kycProof) {
            request.kycPayload = kycProof.payloadHex;
            request.kycSignature = kycProof.signatureHex;
          } else {
            throw new Error("Please complete KYC verification before sending");
          }

          // Receiver proof: required for kyc-extended (always) and for security-token
          // when `requires_receiver_kyc` is true. Self-sends skip the proof either way.
          const receiverRequired =
              isKycExtendedToken || (isSecurityTokenToken && securityTokenRequiresReceiverKyc);
          if (receiverRequired) {
            if (recipientCheckStatus.kind === "verified") {
              request.mpfProofCborHex = recipientCheckStatus.proofCborHex;
              request.mpfValidUntilMs = recipientCheckStatus.validUntilMs;
            } else if (recipientCheckStatus.kind !== "self") {
              throw new Error("Recipient must complete KYC before receiving this token");
            }
          }
        } else if (kycProof) {
          request.kycPayload = kycProof.payloadHex;
          request.kycSignature = kycProof.signatureHex;
        }

        unsignedCborTx = await transferToken(request, selectedVersion?.txHash);
      }

      setIsBuilding(false);
      setStep("signing");
      setIsSigning(true);

      const signedTx = await wallet.signTx(unsignedCborTx, isKycToken);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      showToast({
        title: "Transfer submitted",
        description: `Tx ${submittedTxHash.slice(0, 12)}… — sending ${quantity} ${asset.assetName} tokens to the network. Wait for confirmation.`,
        variant: "success",
      });
    } catch (error) {
      console.error("Transfer error:", error);
      let errorMessage = "Failed to transfer tokens";
      if (error instanceof Error) {
        errorMessage = error.message.includes("User declined") ? "Transaction was cancelled" : error.message;
      }
      // Auto-trigger the one-shot transfer-logic stake-credential registration
      // when the backend reports it's missing. Conway requires a script's stake
      // credential to be registered on-chain before any withdraw-0 against it.
      // We isolate it in its own tx so Eternl can sign it without choking on
      // mixed-script signing.
      const needsCertRegistration =
        isSecurityTokenToken
        && errorMessage.includes("transferLogic stake credential not yet registered");
      if (needsCertRegistration) {
        try {
          showToast({
            title: "One-time setup required",
            description: "Registering the transfer-logic stake credential on-chain. Your wallet will prompt to sign.",
            variant: "info",
          });
          const { buildRegisterTransferLogicTx } = await import(
            "@/lib/api/security-token"
          );
          const { unsignedCborTx: regCbor } = await buildRegisterTransferLogicTx(
            policyId, senderAddress,
          );
          const signedReg = await wallet.signTx(regCbor);
          const regTxHash = await wallet.submitTx(signedReg);
          showToast({
            title: "Stake credential registered",
            description: `Tx ${regTxHash.slice(0, 12)}… — wait for confirmation, then retry the transfer.`,
            variant: "success",
          });
        } catch (regErr) {
          console.error("transferLogic cert registration failed:", regErr);
          showToast({
            title: "Stake-credential registration failed",
            description: regErr instanceof Error ? regErr.message : "registration failed",
            variant: "error",
          });
        }
      } else {
        showToast({ title: "Transfer failed", description: errorMessage, variant: "error" });
      }
      setStep("form");
    } finally {
      setIsBuilding(false);
      setIsSigning(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div
        className="absolute inset-0 bg-black/70 backdrop-blur-sm"
        onClick={step === "form" ? onClose : undefined}
      />

      <div className="relative w-full max-w-md mx-4 bg-dark-800 border border-dark-700 rounded-xl shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-dark-700">
          <div className="flex items-center gap-3">
            <Send className="h-5 w-5 text-primary-500" />
            <h2 className="text-lg font-semibold text-white">Transfer Tokens</h2>
          </div>
          {(step === "form") && (
            <button onClick={onClose} className="p-1 hover:bg-dark-700 rounded transition-colors">
              <X className="h-5 w-5 text-dark-400 hover:text-white" />
            </button>
          )}
        </div>

        {/* Content */}
        <div className="p-6">
          {step === "kyc-sender" && isKycExtendedToken && (
            <KycVerificationFlow
              policyId={policyId}
              senderAddress={senderAddress}
              onBack={() => setStep("form")}
              onComplete={async (proof) => {
                setKycProofState(proof);
                try {
                  await requestMpfInclusion(policyId, {
                    boundAddress: senderAddress,
                    kycSessionId: getKeriSessionIdForWallet(senderAddress),
                    validUntilMs: proof.validUntilMs,
                  });
                  senderMembership.refresh();
                } catch (err) {
                  console.error("Failed to register sender in MPF tree:", err);
                }
                setStep("form");
              }}
            />
          )}

          {step === "kyc-sender" && isSecurityTokenToken && (
            <KycVerificationFlow
              policyId={policyId}
              senderAddress={senderAddress}
              onBack={() => setStep("form")}
              onComplete={async (proof) => {
                setKycProofState(proof);
                try {
                  await requestSecurityTokenInclusion(policyId, {
                    boundAddress: senderAddress,
                    kycSessionId: getKeriSessionIdForWallet(senderAddress),
                    validUntilMs: proof.validUntilMs,
                  });
                  securityTokenSenderMembership.refresh();
                } catch (err) {
                  console.error("Failed to register sender in security-token allowlist:", err);
                }
                setStep("form");
              }}
            />
          )}

          {step === "kyc-verify" && isKycToken && !isKycExtendedToken && !isSecurityTokenToken && (
            <KycVerificationFlow
              policyId={policyId}
              senderAddress={senderAddress}
              onComplete={(proof) => {
                setKycProofState(proof);
                setStep("form");
              }}
              onBack={() => setStep("form")}
            />
          )}

          {step === "form" && (
            <form onSubmit={handleSubmit} className="space-y-5">
              {/* Pause notice — fires when the security-token's GS datum has
                  transfers_paused=true. The on-chain transfer_logic validator
                  rejects every transfer in this state, so we surface a banner
                  AND disable the Send button below to spare the user the fees
                  on a tx the network would refuse anyway. */}
              {isSecurityTokenToken && securityTokenTransfersPaused && (
                <div className="flex items-start gap-3 px-4 py-3 bg-warning-900/20 border border-warning-700/40 rounded-lg">
                  <AlertCircle className="h-5 w-5 text-warning-400 mt-0.5 shrink-0" />
                  <div className="text-sm">
                    <p className="font-medium text-warning-200">Transfers are currently paused</p>
                    <p className="text-warning-300/80 text-xs mt-0.5">
                      The token admin has paused transfers via the global-state
                      <span className="font-mono"> PauseTransfers</span> action.
                      You can&apos;t send this token until the admin re-enables transfers
                      from the Global State admin tab.
                    </p>
                  </div>
                </div>
              )}

              {/* KYC verification badge. For kyc-extended, allowlist membership shows as
                  secondary info — sender membership is not required to send (validator
                  filters senders out of receiver_witnesses). */}
              {isKycToken && (
                <div className="flex items-start justify-between gap-3 px-4 py-3 bg-dark-900 rounded-lg border border-dark-700">
                  <div className="flex items-start gap-2 flex-1 min-w-0">
                    <Shield className="h-4 w-4 text-primary-400 mt-0.5 shrink-0" />
                    <div className="min-w-0 flex-1">
                      <span className="text-xs text-dark-300">KYC Verification</span>
                      {/* Secondary allowlist status for kyc-extended */}
                      {isKycExtendedToken && senderMembership.status.kind === "verified" && senderMembership.status.onChainSynced && (
                        <p className="text-[10px] text-success-400 leading-tight mt-0.5">In allowlist (on-chain)</p>
                      )}
                      {isKycExtendedToken && senderMembership.status.kind === "verified" && !senderMembership.status.onChainSynced && (
                        <p className="text-[10px] text-warning-400 leading-tight mt-0.5">Allowlist sync pending…</p>
                      )}
                      {/* security-token: surface sender's on-chain membership status.
                          Sender MUST be in the on-chain tree to send (BaFin transfer_logic
                          verifies a membership proof for every input's stake credential). */}
                      {isSecurityTokenToken && (() => {
                        const s = securityTokenSenderMembership.status;
                        if (s.kind === "loading") return (
                          <p className="text-[10px] text-dark-400 leading-tight mt-0.5">Checking allowlist…</p>
                        );
                        if (s.kind === "verified" && s.onChainSynced) return (
                          <p className="text-[10px] text-success-400 leading-tight mt-0.5 break-words">In allowlist (on-chain) — ready to send</p>
                        );
                        if (s.kind === "verified" && !s.onChainSynced) return (
                          <p className="text-[10px] text-warning-400 leading-tight mt-0.5 break-words">Allowlist sync pending — admin must publish a new MPF root</p>
                        );
                        if (s.kind === "publish-pending") return (
                          <p className="text-[10px] text-warning-400 leading-tight mt-0.5 break-words">In off-chain allowlist — admin must publish root on chain</p>
                        );
                        if (s.kind === "expired") return (
                          <p className="text-[10px] text-warning-400 leading-tight mt-0.5 break-words">KYC expired — re-verify</p>
                        );
                        if (s.kind === "not-verified") return (
                          <p className="text-[10px] text-dark-400 leading-tight mt-0.5 break-words">Not in allowlist — verify KYC to enroll</p>
                        );
                        if (s.kind === "error") return (
                          <p className="text-[10px] text-red-400 leading-tight mt-0.5 break-words">Could not check allowlist status</p>
                        );
                        return null;
                      })()}
                    </div>
                  </div>
                  {/* For security-token, Verified badge follows on-chain membership
                      (not the cookie). For other substandards, falls back to kycProof. */}
                  <div className="shrink-0">
                  {isSecurityTokenToken ? (
                    securityTokenSenderMembership.status.kind === "verified" && securityTokenSenderMembership.status.onChainSynced ? (
                      <Badge variant="success" size="sm">Verified</Badge>
                    ) : securityTokenSenderMembership.status.kind === "loading" ? (
                      <Loader2 className="h-4 w-4 text-dark-400 animate-spin" />
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        className="h-7 text-xs px-3 whitespace-nowrap"
                        onClick={() => setStep("kyc-sender")}
                        disabled={securityTokenSenderMembership.status.kind === "publish-pending"}
                        title={securityTokenSenderMembership.status.kind === "publish-pending"
                          ? "Already enrolled off-chain. Ask the admin to publish the new MPF root via the Global State tab."
                          : undefined}
                      >
                        {securityTokenSenderMembership.status.kind === "publish-pending"
                          ? "Awaiting publish"
                          : securityTokenSenderMembership.status.kind === "expired"
                          ? "Re-verify"
                          : "Verify KYC"}
                      </Button>
                    )
                  ) : kycProof ? (
                    <div className="flex items-center gap-2">
                      <Badge variant="success" size="sm">Verified</Badge>
                      <button
                        type="button"
                        onClick={() => {
                          clearKycProof(policyId, senderAddress);
                          setKycProofState(null);
                          if (typeof sessionStorage !== "undefined") {
                            const stakeKey = (() => {
                              try {
                                return extractStakeCredHashFromAddress(senderAddress);
                              } catch {
                                return senderAddress;
                              }
                            })();
                            sessionStorage.removeItem(`keri-session-id:${stakeKey}`);
                          }
                          setStep(isKycExtendedToken ? "kyc-sender" : "kyc-verify");
                        }}
                        className="text-xs text-dark-400 hover:text-primary-400 transition-colors"
                      >
                        Re-verify
                      </button>
                    </div>
                  ) : (
                    <Button
                      type="button"
                      variant="outline"
                      className="h-7 text-xs px-3"
                      onClick={() => setStep(isKycExtendedToken ? "kyc-sender" : "kyc-verify")}
                    >
                      Verify KYC
                    </Button>
                  )}
                  </div>
                </div>
              )}

              {/* Transaction Builder Toggle */}
              <div className="flex items-center justify-between px-3 py-2 bg-dark-900 rounded-lg">
                <span className="text-xs text-dark-400">Tx Builder</span>
                <div className="flex gap-1 bg-dark-800 rounded-md p-0.5">
                  <button
                    type="button"
                    onClick={() => setTransactionBuilder("sdk")}
                    disabled={!sdkAvailable}
                    className={cn(
                      "px-3 py-1 text-xs rounded transition-colors",
                      transactionBuilder === "sdk" ? "bg-primary-500 text-white" : "text-dark-400 hover:text-white",
                      !sdkAvailable && "opacity-50 cursor-not-allowed"
                    )}
                  >
                    SDK
                  </button>
                  <button
                    type="button"
                    onClick={() => setTransactionBuilder("backend")}
                    className={cn(
                      "px-3 py-1 text-xs rounded transition-colors",
                      transactionBuilder === "backend" ? "bg-primary-500 text-white" : "text-dark-400 hover:text-white"
                    )}
                  >
                    Backend
                  </button>
                </div>
              </div>

              {/* Token Info */}
              <div className="px-4 py-3 bg-dark-900 rounded-lg">
                <div className="flex items-center justify-between">
                  <div>
                    <p className="text-xs text-dark-400">Token</p>
                    <p className="text-sm font-medium text-white">{asset.assetName}</p>
                  </div>
                  <div className="text-right">
                    <p className="text-xs text-dark-400">Available</p>
                    <p className="text-sm font-bold text-accent-400">{asset.amount}</p>
                  </div>
                </div>
                <p className="mt-2 text-xs text-dark-500 truncate" title={asset.policyId}>
                  Policy: {asset.policyId}
                </p>
              </div>

              {/* Quantity */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <label className="text-sm font-medium text-white">Amount</label>
                  <button
                    type="button"
                    onClick={handleSetMax}
                    disabled={isBuilding}
                    className="text-xs text-primary-400 hover:text-primary-300 transition-colors"
                  >
                    Max
                  </button>
                </div>
                <Input
                  type="number"
                  value={quantity}
                  onChange={(e) => {
                    setQuantity(e.target.value);
                    setErrors((prev) => ({ ...prev, quantity: "" }));
                  }}
                  placeholder="Enter amount"
                  disabled={isBuilding}
                  error={errors.quantity}
                />
              </div>

              {/* Recipient Address */}
              <div>
                <Input
                  label="Recipient Address"
                  value={recipientAddress}
                  onChange={(e) => {
                    setRecipientAddress(e.target.value);
                    setErrors((prev) => ({ ...prev, recipientAddress: "" }));
                  }}
                  placeholder="addr1..."
                  disabled={isBuilding}
                  error={errors.recipientAddress}
                />
                {isKycExtendedToken && <RecipientStatus status={recipientCheckStatus} />}
              </div>

              {/* Submit */}
              <div className="flex gap-3 pt-2">
                <Button type="button" variant="ghost" className="flex-1" onClick={onClose} disabled={isBuilding}>
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  className="flex-1"
                  isLoading={isBuilding}
                  disabled={isBuilding || !senderReady || !recipientReady || securityTokenTransfersPaused}
                >
                  {isBuilding
                    ? "Building..."
                    : securityTokenTransfersPaused
                      ? "Transfers paused"
                      : !senderReady
                        ? "KYC Required"
                        : !recipientReady
                          ? "Recipient Unverified"
                          : "Transfer"}
                </Button>
              </div>
            </form>
          )}

          {step === "signing" && (
            <div className="flex flex-col items-center py-8">
              <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
              <p className="text-white font-medium">
                {isSigning ? "Waiting for signature..." : "Building transaction..."}
              </p>
              <p className="text-sm text-dark-400 mt-2">Please confirm the transaction in your wallet</p>
            </div>
          )}

          {step === "success" && txHash && (
            <div className="flex flex-col items-center py-6">
              <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
                <CheckCircle className="h-8 w-8 text-green-500" />
              </div>
              <h3 className="text-lg font-semibold text-white mb-2">Transfer Complete!</h3>
              <p className="text-sm text-dark-400 text-center mb-4">
                Successfully transferred {quantity} {asset.assetName} tokens
              </p>
              <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
                <p className="text-xs text-dark-400 mb-1">Transaction Hash</p>
                <p className="text-xs text-primary-400 font-mono break-all">{txHash}</p>
              </div>
              <div className="flex gap-3 w-full">
                <a
                  href={getExplorerTxUrl(txHash)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex-1"
                >
                  <Button variant="ghost" className="w-full">
                    <ExternalLink className="h-4 w-4 mr-2" /> View on Explorer
                  </Button>
                </a>
                <Button variant="primary" className="flex-1" onClick={onClose}>
                  Done
                </Button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ── Sub-components ──────────────────────────────────────────────────────────

interface RecipientStatusProps {
  status: RecipientCheckStatus;
}

function RecipientStatus({ status }: RecipientStatusProps) {
  if (status.kind === "idle") return null;

  return (
    <div className="mt-1.5 flex items-center gap-1.5">
      {status.kind === "checking" && (
        <>
          <Loader2 className="h-3 w-3 animate-spin text-primary-400" />
          <span className="text-xs text-dark-400">Checking recipient…</span>
        </>
      )}
      {status.kind === "verified" && (
        <>
          <CheckCircle className="h-3 w-3 text-success-400" />
          <span className="text-xs text-success-400">Recipient verified</span>
        </>
      )}
      {status.kind === "self" && (
        <>
          <CheckCircle className="h-3 w-3 text-primary-400" />
          <span className="text-xs text-dark-400">Sending to yourself</span>
        </>
      )}
      {status.kind === "not-verified" && (
        <>
          <AlertCircle className="h-3 w-3 text-warning-400" />
          <span className="text-xs text-warning-400">
            Recipient hasn&apos;t completed KYC for this token — they cannot receive yet
          </span>
        </>
      )}
      {status.kind === "expired" && (
        <>
          <AlertCircle className="h-3 w-3 text-red-400" />
          <span className="text-xs text-red-400">Recipient KYC has expired — they need to renew</span>
        </>
      )}
      {status.kind === "publish-pending" && (
        <>
          <Loader2 className="h-3 w-3 animate-spin text-warning-400" />
          <span className="text-xs text-warning-400">
            Recipient verified — waiting for on-chain publication (try again in a few minutes)
          </span>
        </>
      )}
      {status.kind === "error" && (
        <>
          <AlertCircle className="h-3 w-3 text-red-400" />
          <span className="text-xs text-red-400">{status.message}</span>
        </>
      )}
    </div>
  );
}
