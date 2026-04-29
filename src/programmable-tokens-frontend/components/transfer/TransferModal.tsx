"use client";

import { useState, useEffect } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { X, Send, CheckCircle, ExternalLink, Shield } from "lucide-react";
import { cn } from "@/lib/utils";
import { transferToken, getTokenContext } from "@/lib/api";
import { TransferTokenRequest, ParsedAsset } from "@/types/api";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { KycVerificationFlow } from "./KycVerificationFlow";
import { getKycProof, clearKycProof, type KycProofCookie } from "@/lib/utils/kyc-cookie";

type TransactionBuilder = "sdk" | "backend";

interface TransferModalProps {
  isOpen: boolean;
  onClose: () => void;
  asset: ParsedAsset;
  senderAddress: string;
}

type TransferStep = "form" | "kyc-verify" | "signing" | "success";

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
  const network = process.env.NEXT_PUBLIC_NETWORK || "preview";

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
  const [kycProof, setKycProofState] = useState<KycProofCookie | null>(null);

  const [errors, setErrors] = useState({
    quantity: "",
    recipientAddress: "",
  });

  // Reset state and detect KYC tokens when modal opens
  useEffect(() => {
    if (isOpen) {
      setStep("form");
      setQuantity("");
      setRecipientAddress("");
      setTxHash(null);
      setErrors({ quantity: "", recipientAddress: "" });
      setKycProofState(null);
      setIsKycToken(false);

      // Check if this is a KYC token and if we have a cached proof
      const tokenPolicyId = asset.unit.substring(0, 56);
      getTokenContext(tokenPolicyId)
        .then((ctx) => {
          if (ctx.substandardId === "kyc") {
            setIsKycToken(true);
            const cachedProof = getKycProof(tokenPolicyId);
            if (cachedProof) {
              setKycProofState(cachedProof);
            }
          }
        })
        .catch(() => {
          // Not a registered token or error — proceed without KYC
        });
    }
  }, [isOpen, asset.unit]);

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

    if (!validateForm()) {
      return;
    }

    try {
      setIsBuilding(true);

      let unsignedCborTx: string;

      // KYC substandard is not yet implemented in @easy1staking/cip113-sdk-ts;
      // route KYC tokens through the backend which has a full KYC handler.
      const useSdk = transactionBuilder === "sdk" && !isKycToken;

      if (useSdk) {
        // ----- Client-side tx building via CIP-113 SDK -----
        showToast({
          title: "Building Transaction",
          description: "Initializing CIP-113 SDK...",
          variant: "default",
        });

        // Resolve substandard from backend + register if needed
        const substandardId = await ensureSubstandard(asset.policyId, asset.assetNameHex);

        const protocol = await getProtocol();

        showToast({
          title: "Building Transaction",
          description: `Building ${substandardId} transfer with CIP-113 SDK...`,
          variant: "default",
        });

        const result = await protocol.transfer({
          senderAddress,
          recipientAddress: recipientAddress.trim(),
          tokenPolicyId: asset.policyId,
          assetName: asset.assetNameHex,
          quantity: BigInt(quantity),
          substandardId, // Route directly — no try/catch guessing
        });

        unsignedCborTx = result.cbor;
      } else {
        // ----- Server-side tx building via backend API -----
        const request: TransferTokenRequest = {
          senderAddress,
          unit: asset.unit,
          quantity,
          recipientAddress: recipientAddress.trim(),
          // Include KYC proof fields if available
          ...(kycProof && {
            kycPayload: kycProof.payloadHex,
            kycSignature: kycProof.signatureHex,
          }),
        };

        unsignedCborTx = await transferToken(request, selectedVersion?.txHash);
      }

      setIsBuilding(false);
      setStep("signing");
      setIsSigning(true);

      // Sign and submit transaction (partial signing for KYC tokens — Plutus scripts present)
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
        if (error.message.includes("User declined")) {
          errorMessage = "Transaction was cancelled";
        } else {
          errorMessage = error.message;
        }
      }

      showToast({
        title: "Transfer Failed",
        description: errorMessage,
        variant: "error",
      });

      setStep("form");
    } finally {
      setIsBuilding(false);
      setIsSigning(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/70 backdrop-blur-sm"
        onClick={step === "form" ? onClose : undefined}
      />

      {/* Modal */}
      <div className="relative w-full max-w-md mx-4 bg-dark-800 border border-dark-700 rounded-xl shadow-2xl">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-dark-700">
          <div className="flex items-center gap-3">
            <Send className="h-5 w-5 text-primary-500" />
            <h2 className="text-lg font-semibold text-white">Transfer Tokens</h2>
          </div>
          {step === "form" && (
            <button
              onClick={onClose}
              className="p-1 hover:bg-dark-700 rounded transition-colors"
            >
              <X className="h-5 w-5 text-dark-400 hover:text-white" />
            </button>
          )}
        </div>

        {/* Content */}
        <div className="p-6">
          {/* KYC Verification Step */}
          {step === "kyc-verify" && (
            <KycVerificationFlow
              policyId={asset.unit.substring(0, 56)}
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
              {/* KYC Status Badge */}
              {isKycToken && (
                <div className="flex items-center justify-between px-4 py-3 bg-dark-900 rounded-lg border border-dark-700">
                  <div className="flex items-center gap-2">
                    <Shield className="h-4 w-4 text-primary-400" />
                    <span className="text-xs text-dark-300">KYC Verification</span>
                  </div>
                  {kycProof ? (
                    <div className="flex items-center gap-2">
                      <div className="relative group">
                        <Badge variant="success" size="sm" className="cursor-help">Verified</Badge>
                        <div className="absolute right-0 top-full mt-2 w-80 p-3 bg-dark-900 border border-dark-700 rounded-lg shadow-xl opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all duration-150 z-50">
                          <p className="text-xs font-semibold text-white mb-2">KYC Proof Details</p>
                          <div className="space-y-1.5">
                            <div>
                              <p className="text-[10px] text-dark-500 uppercase tracking-wide">Role</p>
                              <p className="text-xs text-white">{kycProof.roleName} ({kycProof.role})</p>
                            </div>
                            <div>
                              <p className="text-[10px] text-dark-500 uppercase tracking-wide">Valid Until</p>
                              <p className="text-xs text-white">{new Date(kycProof.validUntilMs).toLocaleString()}</p>
                            </div>
                            <div>
                              <p className="text-[10px] text-dark-500 uppercase tracking-wide">Payload</p>
                              <p className="text-[10px] text-primary-400 font-mono break-all">{kycProof.payloadHex}</p>
                            </div>
                            <div>
                              <p className="text-[10px] text-dark-500 uppercase tracking-wide">Signature</p>
                              <p className="text-[10px] text-primary-400 font-mono break-all">{kycProof.signatureHex}</p>
                            </div>
                            <div>
                              <p className="text-[10px] text-dark-500 uppercase tracking-wide">Entity Vkey</p>
                              <p className="text-[10px] text-primary-400 font-mono break-all">{kycProof.entityVkeyHex}</p>
                            </div>
                          </div>
                        </div>
                      </div>
                      <button
                        type="button"
                        onClick={() => {
                          clearKycProof(asset.unit.substring(0, 56));
                          setKycProofState(null);
                          // Clear KERI session so the flow restarts fresh
                          if (typeof sessionStorage !== "undefined") {
                            sessionStorage.removeItem("keri-session-id");
                          }
                          setStep("kyc-verify");
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
                      onClick={() => setStep("kyc-verify")}
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
                      transactionBuilder === "sdk"
                        ? "bg-primary-500 text-white"
                        : "text-dark-400 hover:text-white",
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
                      transactionBuilder === "backend"
                        ? "bg-primary-500 text-white"
                        : "text-dark-400 hover:text-white"
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
              </div>

              {/* Submit Button */}
              <div className="flex gap-3 pt-2">
                <Button
                  type="button"
                  variant="ghost"
                  className="flex-1"
                  onClick={onClose}
                  disabled={isBuilding}
                >
                  Cancel
                </Button>
                <Button
                  type="submit"
                  variant="primary"
                  className="flex-1"
                  isLoading={isBuilding}
                  disabled={isBuilding || (isKycToken && !kycProof)}
                >
                  {isBuilding ? "Building..." : isKycToken && !kycProof ? "KYC Required" : "Transfer"}
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
              <p className="text-sm text-dark-400 mt-2">
                Please confirm the transaction in your wallet
              </p>
            </div>
          )}

          {step === "success" && txHash && (
            <div className="flex flex-col items-center py-6">
              <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
                <CheckCircle className="h-8 w-8 text-green-500" />
              </div>
              <h3 className="text-lg font-semibold text-white mb-2">
                Transfer Complete!
              </h3>
              <p className="text-sm text-dark-400 text-center mb-4">
                Successfully transferred {quantity} {asset.assetName} tokens
              </p>

              <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
                <p className="text-xs text-dark-400 mb-1">Transaction Hash</p>
                <p className="text-xs text-primary-400 font-mono break-all">
                  {txHash}
                </p>
              </div>

              <div className="flex gap-3 w-full">
                <a
                  href={getExplorerTxUrl(txHash)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="flex-1"
                >
                  <Button variant="ghost" className="w-full">
                    <ExternalLink className="h-4 w-4 mr-2" />
                    View on Explorer
                  </Button>
                </a>
                <Button
                  variant="primary"
                  className="flex-1"
                  onClick={onClose}
                >
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
