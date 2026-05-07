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
import { getMpfInclusionProof, requestMpfInclusion } from "@/lib/api/kyc-extended";
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

  const senderMpfReady =
    senderMembership.status.kind === "verified" && senderMembership.status.onChainSynced;
  const senderReady = isKycExtendedToken
    ? senderMpfReady || !!kycProof
    : isKycToken
      ? !!kycProof
      : true;

  const recipientReady =
    !isKycExtendedToken ||
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
          }
        })
        .catch(() => {});
    }
  }, [isOpen, policyId]);

  // Recipient MPF membership probe (kyc-extended only).
  useEffect(() => {
    if (!isKycExtendedToken) return;

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

    getMpfInclusionProof(policyId, recipientPkh)
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
  }, [recipientAddress, isKycExtendedToken, policyId, senderAddress]);

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

        if (isKycExtendedToken) {
          // Sender proof: membership (preferred) or attestation cookie
          const ms = senderMembership.status;
          if (ms.kind === "verified" && ms.onChainSynced) {
            request.senderMpfProofCborHex = ms.proofCborHex;
            request.senderMpfValidUntilMs = ms.validUntilMs;
          } else if (kycProof) {
            request.kycPayload = kycProof.payloadHex;
            request.kycSignature = kycProof.signatureHex;
          } else {
            throw new Error("Please complete KYC verification before sending");
          }

          // Receiver proof (omitted for self-send)
          if (recipientCheckStatus.kind === "verified") {
            request.mpfProofCborHex = recipientCheckStatus.proofCborHex;
            request.mpfValidUntilMs = recipientCheckStatus.validUntilMs;
          } else if (recipientCheckStatus.kind !== "self") {
            throw new Error("Recipient must complete KYC before receiving this token");
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
        title: "Transfer Successful",
        description: `Transferred ${quantity} ${asset.assetName} tokens`,
        variant: "success",
      });
    } catch (error) {
      console.error("Transfer error:", error);
      let errorMessage = "Failed to transfer tokens";
      if (error instanceof Error) {
        errorMessage = error.message.includes("User declined") ? "Transaction was cancelled" : error.message;
      }
      showToast({ title: "Transfer Failed", description: errorMessage, variant: "error" });
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

          {step === "kyc-verify" && isKycToken && !isKycExtendedToken && (
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
              {/* KYC verification badge. For kyc-extended, allowlist membership shows as
                  secondary info — sender membership is not required to send (validator
                  filters senders out of receiver_witnesses). */}
              {isKycToken && (
                <div className="flex items-center justify-between px-4 py-3 bg-dark-900 rounded-lg border border-dark-700">
                  <div className="flex items-center gap-2">
                    <Shield className="h-4 w-4 text-primary-400" />
                    <div>
                      <span className="text-xs text-dark-300">KYC Verification</span>
                      {/* Secondary allowlist status for kyc-extended */}
                      {isKycExtendedToken && senderMembership.status.kind === "verified" && senderMembership.status.onChainSynced && (
                        <p className="text-[10px] text-success-400 leading-tight mt-0.5">In allowlist (on-chain)</p>
                      )}
                      {isKycExtendedToken && senderMembership.status.kind === "verified" && !senderMembership.status.onChainSynced && (
                        <p className="text-[10px] text-warning-400 leading-tight mt-0.5">Allowlist sync pending…</p>
                      )}
                    </div>
                  </div>
                  {kycProof ? (
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
                  disabled={isBuilding || !senderReady || !recipientReady}
                >
                  {isBuilding
                    ? "Building..."
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
