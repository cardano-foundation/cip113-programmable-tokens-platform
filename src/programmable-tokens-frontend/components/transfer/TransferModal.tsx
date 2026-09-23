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
  ChevronDown,
  Copy,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { transferToken, getTokenContext } from "@/lib/api";
import { TransferTokenRequest, ParsedAsset, ApiException, type CmtaAttestation } from "@/types/api";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { KycVerificationFlow } from "./KycVerificationFlow";
import { getKycProof, clearKycProof, type KycProofCookie } from "@/lib/utils/kyc-cookie";
import { useMpfMembershipStatus } from "@/hooks/useMpfMembershipStatus";
import { useRwaTokenMembershipStatus } from "@/hooks/useRwaTokenMembershipStatus";
import { getMpfInclusionProof, requestMpfInclusion } from "@/lib/api/kyc-extended";
import { getRwaTokenInclusionProof, getRwaTokenGlobalState } from "@/lib/api/rwa-token";
import { extractStakeCredHashFromAddress } from "@/lib/utils/address";
import { buildCmtaAttestationPayloadHex, prepareCmtaSignature, sameStakeIdentity, stakeIdentityFromBaseAddress } from "@/lib/rwa/attestation";
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

type SignatureInput = { text: string; signedPayloadHex: string };

function preparedPayload(address: string, policyId: string, networkId: number | null,
  tier: string, expiry: string): { payloadHex: string; error: string | null } {
  try {
    if (networkId === null) throw new Error("Waiting for live CMTA token details");
    return { payloadHex: buildCmtaAttestationPayloadHex(address, policyId, networkId,
      Number(tier), new Date(expiry).getTime()), error: null };
  } catch (e) {
    return { payloadHex: "", error: e instanceof Error ? e.message : String(e) };
  }
}

function parsePastedSignature(input: SignatureInput, payloadHex: string,
  context: { trustedVkeys: string[] } | null, expiry: string):
  { value: CmtaAttestation | null; validUntilMs: number | null; error: string | null } {
  if (!input.text.trim()) return { value: null, validUntilMs: null, error: null };
  if (!context) return { value: null, validUntilMs: null, error: "Loading the live trusted-entity list" };
  try {
    const value = prepareCmtaSignature(input.text, input.signedPayloadHex,
      payloadHex, context.trustedVkeys);
    return { value, validUntilMs: new Date(expiry).getTime(), error: null };
  } catch (e) {
    return { value: null, validUntilMs: null, error: e instanceof Error ? e.message : String(e) };
  }
}

export function TransferModal({
  isOpen,
  onClose,
  asset,
  senderAddress,
}: TransferModalProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const { getProtocol, ensureModule, available: sdkAvailable, sdkUnavailableReason } = useCIP113();
  const [step, setStep] = useState<TransferStep>("form");
  const [quantity, setQuantity] = useState("");
  const [recipientAddress, setRecipientAddress] = useState("");
  // Default to the backend builder even when the SDK is available. Parity means the SDK
  // CAN build a transaction, not that it becomes the default route (PLAN.md A-5) — the
  // SDK path is opt-in per operation via the toggle until T-018 has verified all seven
  // operations against a live deployment. Deriving this from `sdkAvailable` would flip
  // every user onto an unverified path the moment the capability was re-enabled.
  const [transactionBuilder, setTransactionBuilder] = useState<TransactionBuilder>("backend");
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  // KYC state
  const [isKycToken, setIsKycToken] = useState(false);
  const [isKycExtendedToken, setIsKycExtendedToken] = useState(false);
  const [isRwaTokenToken, setIsRwaTokenToken] = useState(false);
  const [tokenContextReady, setTokenContextReady] = useState(false);
  const [tokenContextError, setTokenContextError] = useState<string | null>(null);
  /** RWA-token only: per-token toggle from the global-state datum. Defaults to true
   *  (the safer regulatory-compliance posture) until the token context resolves. */
  const [rwaTokenRequiresReceiverKyc, setRwaTokenRequiresReceiverKyc] = useState(true);
  /** RWA-token only: per-token toggle from the global-state datum, INDEPENDENT of
   *  {@link rwaTokenRequiresReceiverKyc}. Defaults to true until the token context
   *  resolves, so we never let a send through un-gated on a stale default. */
  const [rwaTokenRequiresSenderKyc, setRwaTokenRequiresSenderKyc] = useState(true);
  /** RWA-token only: live `transfers_paused` flag from the global-state datum.
   *  When true, the on-chain transfer_logic validator rejects every transfer — we
   *  surface a banner and disable the Send button so the user doesn't burn fees
   *  on a tx the network will refuse. */
  const [rwaTokenTransfersPaused, setRwaTokenTransfersPaused] = useState(false);
  const [rwaAttestationContext, setRwaAttestationContext] = useState<{ networkId: number; trustedVkeys: string[] } | null>(null);
  const [rwaAttestationContextError, setRwaAttestationContextError] = useState<string | null>(null);
  const [senderSignature, setSenderSignature] = useState<SignatureInput>({ text: "", signedPayloadHex: "" });
  const [recipientSignature, setRecipientSignature] = useState<SignatureInput>({ text: "", signedPayloadHex: "" });
  const [senderCopiedPayloadHex, setSenderCopiedPayloadHex] = useState("");
  const [recipientCopiedPayloadHex, setRecipientCopiedPayloadHex] = useState("");
  const [senderTier, setSenderTier] = useState("1");
  const [recipientTier, setRecipientTier] = useState("1");
  const [senderExpiry, setSenderExpiry] = useState(() => localDateTimeValue(Date.now() + 60 * 60 * 1000));
  const [recipientExpiry, setRecipientExpiry] = useState(() => localDateTimeValue(Date.now() + 60 * 60 * 1000));
  const [senderProofNeededForChange, setSenderProofNeededForChange] = useState(false);
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
  // Same shape for rwa-token, on its own API surface.
  const rwaTokenSenderMembership = useRwaTokenMembershipStatus(
    isRwaTokenToken ? policyId : null,
    isRwaTokenToken ? senderAddress : null,
  );

  const senderMpfReady = isRwaTokenToken
    ? rwaTokenSenderMembership.status.kind === "verified"
        && rwaTokenSenderMembership.status.onChainSynced
    : senderMembership.status.kind === "verified"
        && senderMembership.status.onChainSynced;
  // For rwa-token, the on-chain transfer_logic_script requires the sender
  // to be in the MPF tree — a fresh kycProof cookie alone won't satisfy the
  // validator until the new root is published. So gate STRICTLY on on-chain
  // membership for rwa-token. For kyc-extended, the cookie is an accepted
  // fallback (the validator filters senders out of receiver_witnesses).
  const senderPayload = preparedPayload(senderAddress, policyId, rwaAttestationContext?.networkId ?? null,
    senderTier, senderExpiry);
  const recipientPayload = preparedPayload(recipientAddress, policyId, rwaAttestationContext?.networkId ?? null,
    recipientTier, recipientExpiry);
  const parsedSenderAttestation = parsePastedSignature(senderSignature, senderPayload.payloadHex,
    rwaAttestationContext, senderExpiry);
  const parsedRecipientAttestation = parsePastedSignature(recipientSignature, recipientPayload.payloadHex,
    rwaAttestationContext, recipientExpiry);
  // Only the backend knows whether the selected token UTxOs create change.
  // The sender flag is the only unconditional sender gate in this form.
  const rwaTokenSenderProofRequired = rwaTokenRequiresSenderKyc;

  const senderReady = isRwaTokenToken
    ? !rwaTokenSenderProofRequired || senderMpfReady || !!parsedSenderAttestation.value
    : isKycExtendedToken
      ? senderMpfReady || !!kycProof
      : isKycToken
        ? !!kycProof
        : true;

  /** When `requiresReceiverKyc` is false on a rwa-token, we don't gate Send on the
   *  recipient probe — the validator skips the check anyway. */
  const recipientReady =
    !(isKycExtendedToken || (isRwaTokenToken && rwaTokenRequiresReceiverKyc)) ||
    recipientCheckStatus.kind === "verified" ||
    (isRwaTokenToken && !!parsedRecipientAttestation.value) ||
    (isRwaTokenToken && recipientCheckStatus.kind === "self"
      && (senderMpfReady || !!parsedSenderAttestation.value)) ||
    (!isRwaTokenToken && recipientCheckStatus.kind === "self");

  const freshSignatureAttestation = (party: "sender" | "receiver"): CmtaAttestation => {
    if (!rwaAttestationContext) throw new Error("Live CMTA token details are unavailable");
    const input = party === "sender" ? senderSignature : recipientSignature;
    const prepared = preparedPayload(party === "sender" ? senderAddress : recipientAddress,
      policyId, rwaAttestationContext.networkId,
      party === "sender" ? senderTier : recipientTier,
      party === "sender" ? senderExpiry : recipientExpiry);
    if (prepared.error) throw new Error(`${party} ${prepared.error}`);
    return prepareCmtaSignature(input.text, input.signedPayloadHex,
      prepared.payloadHex, rwaAttestationContext.trustedVkeys);
  };

  // Reset state when modal opens
  useEffect(() => {
    let cancelled = false;
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
      setIsRwaTokenToken(false);
      setTokenContextReady(false);
      setTokenContextError(null);
      setRwaTokenRequiresReceiverKyc(true);
      setRwaTokenRequiresSenderKyc(true);
      setRwaTokenTransfersPaused(false);
      setRwaAttestationContext(null);
      setRwaAttestationContextError(null);
      setSenderSignature({ text: "", signedPayloadHex: "" });
      setRecipientSignature({ text: "", signedPayloadHex: "" });
      setSenderCopiedPayloadHex("");
      setRecipientCopiedPayloadHex("");
      setSenderTier("1");
      setRecipientTier("1");
      setSenderExpiry(localDateTimeValue(Date.now() + 60 * 60 * 1000));
      setRecipientExpiry(localDateTimeValue(Date.now() + 60 * 60 * 1000));
      setSenderProofNeededForChange(false);

      getTokenContext(policyId)
        .then((ctx) => {
          if (cancelled) return;
          if (ctx.moduleId === "kyc") {
            setIsKycToken(true);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
            setTokenContextReady(true);
          } else if (ctx.moduleId === "kyc-extended") {
            setIsKycToken(true);
            setIsKycExtendedToken(true);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
            setTokenContextReady(true);
          } else if (ctx.moduleId === "rwa-token") {
            setIsKycToken(true);
            setIsRwaTokenToken(true);
            setRwaTokenRequiresReceiverKyc(ctx.requiresReceiverKyc ?? true);
            setRwaTokenRequiresSenderKyc(ctx.requiresSenderKyc ?? true);
            setRwaTokenTransfersPaused(ctx.transfersPaused ?? false);
            const cachedProof = getKycProof(policyId, senderAddress);
            if (cachedProof) setKycProofState(cachedProof);
            getRwaTokenGlobalState(policyId).then((gs) => {
              if (cancelled) return;
              setRwaAttestationContext({ networkId: gs.networkId, trustedVkeys: gs.trustedEntityVkeys });
              setRwaTokenRequiresReceiverKyc(gs.requiresReceiverKyc);
              setRwaTokenRequiresSenderKyc(gs.requiresSenderKyc);
              setRwaTokenTransfersPaused(gs.transfersPaused);
              setTokenContextReady(true);
            }).catch((e) => {
              if (!cancelled) {
                const message = e instanceof Error ? e.message : String(e);
                setRwaAttestationContextError(message);
                setTokenContextError(message);
              }
            });
          } else {
            setTokenContextReady(true);
          }
        })
        .catch((e) => { if (!cancelled) setTokenContextError(e instanceof Error ? e.message : String(e)); });
    }
    return () => { cancelled = true; };
  }, [isOpen, policyId, senderAddress]);

  // Recipient MPF membership probe. We probe for kyc-extended and ALWAYS for
  // rwa-token so the admin can see the receiver's enrollment status even
  // when {@code requires_receiver_kyc} is false. Whether the probe gates the
  // Send button is decided separately in {@link recipientReady} below.
  useEffect(() => {
    const token = ++recipientProbingToken.current;
    const needsProbe = isKycExtendedToken || isRwaTokenToken;
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
    let recipientType: 0 | 1 = 0;
    let sameCredential = false;
    try {
      if (isRwaTokenToken) {
        const recipient = stakeIdentityFromBaseAddress(addr);
        const sender = stakeIdentityFromBaseAddress(senderAddress);
        recipientPkh = recipient.credentialHash;
        senderPkh = sender.credentialHash;
        recipientType = recipient.credentialType;
        sameCredential = sameStakeIdentity(recipient, sender);
      } else {
        recipientPkh = extractStakeCredHashFromAddress(addr);
        senderPkh = extractStakeCredHashFromAddress(senderAddress);
        sameCredential = recipientPkh.toLowerCase() === senderPkh.toLowerCase();
      }
    } catch {
      setRecipientCheckStatus({ kind: "idle" });
      return;
    }

    if (sameCredential) {
      setRecipientCheckStatus({ kind: "self" });
      return;
    }

    setRecipientCheckStatus({ kind: "checking" });

    const probeFn = isRwaTokenToken
      ? () => getRwaTokenInclusionProof(policyId, recipientPkh, recipientType)
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
  }, [recipientAddress, isKycExtendedToken, isRwaTokenToken, rwaTokenRequiresReceiverKyc, policyId, senderAddress]);

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

    if (!tokenContextReady) {
      showToast({ title: "Token details unavailable", description: tokenContextError ?? "Wait for token verification to load", variant: "error" });
      return;
    }

    // Belt-and-braces guard: the Send button is disabled when transfers are
    // paused, but an Enter-key submit or dev-tools poke could still reach
    // here. Surface a clear toast rather than building a tx the on-chain
    // validator will reject.
    if (isRwaTokenToken && rwaTokenTransfersPaused) {
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
        const moduleId = await ensureModule(asset.policyId, asset.assetNameHex);
        const protocol = await getProtocol();
        showToast({ title: "Building Transaction", description: `Building ${moduleId} transfer with CIP-113 SDK...`, variant: "default" });
        const result = await protocol.transfer({
          senderAddress,
          recipientAddress: recipientAddress.trim(),
          tokenPolicyId: asset.policyId,
          assetName: asset.assetNameHex,
          quantity: BigInt(quantity),
          substandardId: moduleId,
        });
        unsignedCborTx = result.cbor;
      } else {
        const request: TransferTokenRequest = {
          senderAddress,
          unit: asset.unit,
          quantity,
          recipientAddress: recipientAddress.trim(),
        };

        if (isKycExtendedToken || isRwaTokenToken) {
          const ms = isRwaTokenToken
              ? rwaTokenSenderMembership.status
              : senderMembership.status;
          if (ms.kind === "verified" && ms.onChainSynced) {
            request.senderMpfProofCborHex = ms.proofCborHex;
            request.senderMpfValidUntilMs = ms.validUntilMs;
          } else if (isRwaTokenToken && parsedSenderAttestation.value) {
            request.senderAttestation = freshSignatureAttestation("sender");
          } else if (!isRwaTokenToken && kycProof) {
            request.kycPayload = kycProof.payloadHex;
            request.kycSignature = kycProof.signatureHex;
          } else if (!isRwaTokenToken || rwaTokenRequiresSenderKyc) {
            throw new Error("Sender not verified: publish Merkle membership or paste a trusted-entity attestation");
          }

          // Receiver proof: required for kyc-extended (always) and for rwa-token
          // when `requires_receiver_kyc` is true. Same-credential sends reuse
          // the sender proof if the receiver has no separate one.
          const receiverRequired =
              isKycExtendedToken || (isRwaTokenToken && rwaTokenRequiresReceiverKyc);
          if (receiverRequired) {
            if (recipientCheckStatus.kind === "verified") {
              request.mpfProofCborHex = recipientCheckStatus.proofCborHex;
              request.mpfValidUntilMs = recipientCheckStatus.validUntilMs;
            } else if (isRwaTokenToken && parsedRecipientAttestation.value) {
              request.recipientAttestation = freshSignatureAttestation("receiver");
            } else if (recipientCheckStatus.kind !== "self" ||
                (!request.senderMpfProofCborHex && !request.senderAttestation)) {
              throw new Error("Receiver not verified: publish Merkle membership or paste a trusted-entity attestation");
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
      if (isRwaTokenToken && errorMessage.includes("sender proof required"))
        setSenderProofNeededForChange(true);
      // Auto-trigger the one-shot transfer-logic stake-credential registration
      // when the backend reports it's missing. Conway requires a script's stake
      // credential to be registered on-chain before any withdraw-0 against it.
      // We isolate it in its own tx so Eternl can sign it without choking on
      // mixed-script signing.
      const needsCertRegistration =
        isRwaTokenToken
        && errorMessage.includes("transferLogic stake credential not yet registered");
      if (needsCertRegistration) {
        try {
          showToast({
            title: "One-time setup required",
            description: "Registering the transfer-logic stake credential on-chain. Your wallet will prompt to sign.",
            variant: "info",
          });
          const { buildRegisterTransferLogicTx } = await import(
            "@/lib/api/rwa-token"
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
        <div className="max-h-[calc(100vh-5rem)] overflow-y-auto p-6">
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

          {step === "kyc-sender" && isRwaTokenToken && (
            <KycVerificationFlow
              policyId={policyId}
              senderAddress={senderAddress}
              forceFresh
              stageMembership
              onBack={() => setStep("form")}
              onComplete={(proof) => {
                setKycProofState(proof);
                rwaTokenSenderMembership.refresh();
                setStep("form");
              }}
            />
          )}

          {step === "kyc-verify" && isKycToken && !isKycExtendedToken && !isRwaTokenToken && (
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
              {tokenContextError && <p className="rounded-lg border border-red-700/40 bg-red-900/10 p-3 text-xs text-red-300">
                Could not load live token details: {tokenContextError}
              </p>}
              {/* Pause notice — fires when the rwa-token's GS datum has
                  transfers_paused=true. The on-chain transfer_logic validator
                  rejects every transfer in this state, so we surface a banner
                  AND disable the Send button below to spare the user the fees
                  on a tx the network would refuse anyway. */}
              {isRwaTokenToken && rwaTokenTransfersPaused && (
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
                      {/* rwa-token: surface the sender's on-chain membership status.
                          Whether it BLOCKS the send is `rwaTokenSenderProofRequired`, not
                          this status: the transfer_logic sender loop only runs when
                          `requires_sender_kyc` is on, and the sender's change output is
                          only checked when `requires_receiver_kyc` is on. */}
                      {isRwaTokenToken && (() => {
                        const s = rwaTokenSenderMembership.status;
                        if (!rwaTokenSenderProofRequired) return (
                          <p className="text-[10px] text-dark-400 leading-tight mt-0.5 break-words">
                            {s.kind === "verified" && s.onChainSynced
                              ? "In allowlist (on-chain)"
                              : "Not required for this token \u2014 requires_sender_kyc is off on chain"}
                          </p>
                        );
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
                  {/* For rwa-token, Verified badge follows on-chain membership
                      (not the cookie). For other modules, falls back to kycProof. */}
                  <div className="shrink-0">
                  {isRwaTokenToken ? (
                    rwaTokenSenderMembership.status.kind === "verified" && rwaTokenSenderMembership.status.onChainSynced ? (
                      <Badge variant="success" size="sm">Verified</Badge>
                    ) : !rwaTokenSenderProofRequired ? (
                      <Badge variant="default" size="sm">Not required</Badge>
                    ) : rwaTokenSenderMembership.status.kind === "loading" ? (
                      <Loader2 className="h-4 w-4 text-dark-400 animate-spin" />
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        className="h-7 text-xs px-3 whitespace-nowrap"
                        onClick={() => setStep("kyc-sender")}
                        disabled={rwaTokenSenderMembership.status.kind === "publish-pending"}
                        title={rwaTokenSenderMembership.status.kind === "publish-pending"
                          ? "Already enrolled off-chain. Ask the admin to publish the new MPF root via the Global State tab."
                          : undefined}
                      >
                        {rwaTokenSenderMembership.status.kind === "publish-pending"
                          ? "Awaiting publish"
                          : rwaTokenSenderMembership.status.kind === "expired"
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

              {isRwaTokenToken && !senderMpfReady &&
                (rwaTokenRequiresSenderKyc || rwaTokenRequiresReceiverKyc) && (
                <AttestationPaste
                  party="Sender"
                  address={senderAddress}
                  policyId={policyId}
                  networkId={rwaAttestationContext?.networkId ?? null}
                  trustedIssuerCount={rwaAttestationContext?.trustedVkeys.length ?? null}
                  tier={senderTier}
                  onTierChange={(value) => { setSenderTier(value); setSenderCopiedPayloadHex(""); setSenderSignature({ text: "", signedPayloadHex: "" }); }}
                  expiry={senderExpiry}
                  onExpiryChange={(value) => { setSenderExpiry(value); setSenderCopiedPayloadHex(""); setSenderSignature({ text: "", signedPayloadHex: "" }); }}
                  payloadHex={senderPayload.payloadHex}
                  payloadError={senderPayload.error}
                  onPayloadCopied={setSenderCopiedPayloadHex}
                  required={rwaTokenRequiresSenderKyc || senderProofNeededForChange}
                  text={senderSignature.text}
                  onChange={(value) => setSenderSignature({ text: value, signedPayloadHex: senderCopiedPayloadHex })}
                  attestation={parsedSenderAttestation.value}
                  validUntilMs={parsedSenderAttestation.validUntilMs}
                  error={parsedSenderAttestation.error ?? rwaAttestationContextError}
                />
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
                    setSenderProofNeededForChange(false);
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
                    setRecipientSignature({ text: "", signedPayloadHex: "" });
                    setRecipientCopiedPayloadHex("");
                    setSenderProofNeededForChange(false);
                    setErrors((prev) => ({ ...prev, recipientAddress: "" }));
                  }}
                  placeholder="addr1..."
                  disabled={isBuilding}
                  error={errors.recipientAddress}
                />
                {isKycExtendedToken && <RecipientStatus status={recipientCheckStatus} />}
              </div>

              {isRwaTokenToken && rwaTokenRequiresReceiverKyc &&
                recipientCheckStatus.kind !== "verified" &&
                !(recipientCheckStatus.kind === "self" && (senderMpfReady || !!parsedSenderAttestation.value)) && (
                <AttestationPaste
                  party="Receiver"
                  address={recipientAddress}
                  policyId={policyId}
                  networkId={rwaAttestationContext?.networkId ?? null}
                  trustedIssuerCount={rwaAttestationContext?.trustedVkeys.length ?? null}
                  tier={recipientTier}
                  onTierChange={(value) => { setRecipientTier(value); setRecipientCopiedPayloadHex(""); setRecipientSignature({ text: "", signedPayloadHex: "" }); }}
                  expiry={recipientExpiry}
                  onExpiryChange={(value) => { setRecipientExpiry(value); setRecipientCopiedPayloadHex(""); setRecipientSignature({ text: "", signedPayloadHex: "" }); }}
                  payloadHex={recipientPayload.payloadHex}
                  payloadError={recipientPayload.error}
                  onPayloadCopied={setRecipientCopiedPayloadHex}
                  required
                  text={recipientSignature.text}
                  onChange={(value) => setRecipientSignature({ text: value, signedPayloadHex: recipientCopiedPayloadHex })}
                  attestation={parsedRecipientAttestation.value}
                  validUntilMs={parsedRecipientAttestation.validUntilMs}
                  error={parsedRecipientAttestation.error ?? rwaAttestationContextError}
                />
              )}

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
                  disabled={isBuilding || !tokenContextReady || !senderReady || !recipientReady || rwaTokenTransfersPaused}
                >
                  {isBuilding
                    ? "Building..."
                    : !tokenContextReady
                      ? tokenContextError ? "Token unavailable" : "Loading token…"
                    : rwaTokenTransfersPaused
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

function localDateTimeValue(timestamp: number): string {
  const date = new Date(timestamp);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function AttestationPaste({ party, address, policyId, networkId, trustedIssuerCount, tier, onTierChange,
  expiry, onExpiryChange, payloadHex, payloadError, onPayloadCopied, required, text, onChange,
  attestation, validUntilMs, error }: {
  party: "Sender" | "Receiver";
  address: string;
  policyId: string;
  networkId: number | null;
  trustedIssuerCount: number | null;
  tier: string;
  onTierChange: (value: string) => void;
  expiry: string;
  onExpiryChange: (value: string) => void;
  payloadHex: string;
  payloadError: string | null;
  onPayloadCopied: (payloadHex: string) => void;
  required: boolean;
  text: string;
  onChange: (value: string) => void;
  attestation: CmtaAttestation | null;
  validUntilMs: number | null;
  error: string | null;
}) {
  const id = `${party.toLowerCase()}-cmta-attestation`;
  const [expanded, setExpanded] = useState(false);
  const [copyStatus, setCopyStatus] = useState<"idle" | "copied" | "failed">("idle");
  const currentPayloadRef = useRef(payloadHex);
  currentPayloadRef.current = payloadHex;
  useEffect(() => { setCopyStatus("idle"); }, [payloadHex]);
  const copyPayload = async () => {
    try {
      if (networkId === null) throw new Error("Live CMTA token details are unavailable");
      // Recheck expiry at the moment of copying, including after an idle dialog.
      const freshPayload = buildCmtaAttestationPayloadHex(address, policyId, networkId,
        Number(tier), new Date(expiry).getTime());
      await navigator.clipboard.writeText(freshPayload);
      if (currentPayloadRef.current !== freshPayload) return;
      onPayloadCopied(freshPayload);
      setCopyStatus("copied");
    } catch {
      setCopyStatus("failed");
    }
  };
  return <div className={cn("rounded-lg border p-3", attestation
    ? "border-success-700/40 bg-success-900/10" : "border-warning-700/40 bg-warning-900/10")}>
    <button type="button" aria-expanded={expanded} aria-controls={`${id}-panel`}
      onClick={() => setExpanded((open) => !open)}
      className="flex w-full items-center justify-between gap-3 text-left">
      <span className="space-y-0.5">
        <span className={cn("block text-sm font-medium", attestation ? "text-success-300" : "text-warning-200")}>
          {attestation ? `${party} signature provided` : `${party} not verified`}
        </span>
        <span className="block text-xs text-dark-300">
          {attestation ? `Pending backend verification; claim expires ${new Date(validUntilMs!).toLocaleString()}`
            : required ? "Provide a trusted-entity attestation" : "May need an attestation for token change"}
        </span>
      </span>
      <ChevronDown aria-hidden="true" className={cn("h-4 w-4 shrink-0 text-dark-300 transition-transform", expanded && "rotate-180")} />
    </button>
    {error && text.trim() && !expanded && <p className="mt-2 text-xs text-red-400">{error}</p>}
    <div id={`${id}-panel`} hidden={!expanded} className="mt-4 space-y-3 border-t border-dark-700/70 pt-4">
      <p className="text-xs text-dark-300">
        {required ? "Required without published Merkle membership. "
          : "May be needed if selected token inputs return change to the sender. "}
        Set the claim below, then give its payload to a trusted issuer. The issuer must hex-decode
        and sign the 67 raw bytes with Ed25519. CIP-30 signData has a different format.
      </p>
      {trustedIssuerCount === 0 && <p className="text-xs text-red-400">
        This token has no trusted issuer in its live global state. Ask the token admin to add one before signing.
      </p>}
      <div className="grid gap-3 sm:grid-cols-[7rem_1fr]">
        <label className="space-y-1 text-xs text-dark-300">
          <span className="block">KYC tier</span>
          <input type="number" min="1" max="255" step="1" value={tier}
            onChange={(e) => onTierChange(e.target.value)}
            className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 text-sm text-white focus:border-primary-500 focus:outline-none" />
          <span className="block text-dark-400">1 User · 2 Institutional · 3 vLEI</span>
        </label>
        <label className="space-y-1 text-xs text-dark-300">
          <span className="block">Attestation valid until (local time)</span>
          <input type="datetime-local" value={expiry}
            onChange={(e) => onExpiryChange(e.target.value)}
            className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 text-sm text-white focus:border-primary-500 focus:outline-none" />
        </label>
      </div>
      <div className="space-y-1">
        <label htmlFor={`${id}-payload`} className="text-xs text-dark-300">Payload to sign (hex)</label>
        <div className="flex items-start gap-2">
          <textarea id={`${id}-payload`} value={payloadHex} readOnly rows={3} spellCheck={false}
            onCopy={(event) => {
              if (payloadHex && event.currentTarget.selectionStart === 0
                  && event.currentTarget.selectionEnd === payloadHex.length) {
                onPayloadCopied(payloadHex);
                setCopyStatus("copied");
              }
            }}
            className="min-w-0 flex-1 resize-none rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 font-mono text-xs text-white focus:border-primary-500 focus:outline-none" />
          <Button type="button" variant="outline" onClick={copyPayload} disabled={!payloadHex || trustedIssuerCount === 0}
            className="shrink-0 px-3 text-xs"><Copy className="mr-1 h-3.5 w-3.5" />Copy</Button>
        </div>
        {payloadError && <p className="text-xs text-red-400">{payloadError}</p>}
        {copyStatus === "copied" && <p role="status" className="text-xs text-success-400">Payload copied</p>}
        {copyStatus === "failed" && <p role="alert" className="text-xs text-red-400">Copy failed; retry or select and copy the hex above</p>}
        <p className="text-xs text-dark-400">This claim can be used for applicable transfers until expiry while the issuer remains trusted.</p>
      </div>
      <div className="space-y-1">
        <label htmlFor={id} className="text-xs text-dark-300">Paste raw Ed25519 signature (hex)</label>
        <textarea id={id} value={text} onChange={(e) => onChange(e.target.value)} rows={3}
          spellCheck={false} placeholder="128 hex characters, with optional 0x prefix"
          className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 font-mono text-xs text-white placeholder:text-dark-500 focus:border-primary-500 focus:outline-none" />
        <p className="text-xs text-dark-400">The backend checks this signature against the token&apos;s live trusted issuers and adds the matching key to the transaction proof.</p>
        {error && text.trim() && <p className="text-xs text-red-400">{error}</p>}
      </div>
      {attestation && validUntilMs !== null && <div className="space-y-1 text-xs text-success-400">
        <p>Signature format is valid; the backend verifies the issuer before building. Tier {parseInt(attestation.payloadHex.slice(56, 58), 16)};
          expires {new Date(validUntilMs).toLocaleString()}.</p>
        <p className="font-mono break-all">Stake credential: {attestation.payloadHex.slice(0, 56)}</p>
      </div>}
    </div>
  </div>;
}

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
