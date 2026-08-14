"use client";

import { useState, useRef, useEffect, useCallback } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { TxBuilderToggle, type TransactionBuilder } from "@/components/ui/tx-builder-toggle";
import {
  Coins,
  CheckCircle,
  ExternalLink,
  Shield,
  Copy,
  QrCode,
} from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import {
  AdminTokenInfo,
  SecurityTokenCapability,
  hasSecurityTokenCapability,
} from "@/lib/api/admin";
import { decodeAssetNameDisplay } from "@/lib/utils/cip68";
import { mintToken } from "@/lib/api";
import { MintTokenRequest, Cip170AttestationData } from "@/types/api";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import {
  requestAttestation,
  getAgentOobi,
  resolveOobi,
  storeCardanoAddress,
  getAvailableRoles,
  presentCredential,
  cancelPresentation,
  getSession,
  type AvailableRole,
  type CredentialResponse,
} from "@/lib/api/keri";
import {
  getSecurityTokenGlobalState,
  type SecurityTokenGlobalState,
} from "@/lib/api/security-token";

interface MintSectionProps {
  tokens: AdminTokenInfo[];
  feePayerAddress: string;
}

type MintStep = "form" | "attestation" | "signing" | "success";

function getSessionId(): string {
  if (typeof sessionStorage === "undefined") return crypto.randomUUID();
  let id = sessionStorage.getItem("mint-keri-session-id");
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem("mint-keri-session-id", id);
  }
  return id;
}

export function MintSection({ tokens, feePayerAddress }: MintSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const { getProtocol, ensureSubstandard, available: sdkAvailable } = useCIP113();
  const [txBuilder, setTxBuilder] = useState<TransactionBuilder>(sdkAvailable ? "sdk" : "backend");

  // The cip113-sdk-ts SDK only ships dummy + freeze-and-seize substandards. KYC and
  // security-token have no SDK implementation, so their mints must go through the
  // backend regardless of the toggle.
  const sdkAvailableForSelected = (token: AdminTokenInfo | null) =>
    sdkAvailable
    && token?.substandardId !== "kyc"
    && token?.substandardId !== "security-token";

  // Per-page capability gate. Show:
  //   - any token where the wallet has ISSUER_ADMIN (legacy substandards)
  //   - all dummy tokens (open mint by design)
  //   - security-tokens where the wallet has MINTER or ADMIN capability
  //     in the on-chain power-users linked list (BaFin model)
  // The backend's /admin/tokens endpoint already filters security-tokens by
  // power-user membership; this just enforces the per-page capability bit so
  // a token where the wallet is e.g. only PAUSER doesn't show up here.
  const mintableTokens = tokens.filter((t) => {
    if (t.substandardId === "security-token") {
      return hasSecurityTokenCapability(
        t,
        SecurityTokenCapability.MINTER | SecurityTokenCapability.ADMIN,
      );
    }
    return t.roles.includes("ISSUER_ADMIN") || t.substandardId === "dummy";
  });

  // Form state
  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(
    null
  );
  const [quantity, setQuantity] = useState("");
  const [recipientAddress, setRecipientAddress] = useState(feePayerAddress);
  const [enableAttestation, setEnableAttestation] = useState(false);

  // Flow state
  const [step, setStep] = useState<MintStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);
  const [attestError, setAttestError] = useState<string | null>(null);

  // CIP-170 state
  const [attestationData, setAttestationData] =
    useState<Cip170AttestationData | null>(null);
  const sessionIdRef = useRef(getSessionId());

  // OOBI + credential state for the inline KERI handshake. The backend's
  // /keri/attest/request requires the session to already have an admitted
  // credential, so we walk the user through OOBI exchange + presentation
  // before allowing the attest call.
  const [oobiUrl, setOobiUrl] = useState<string | null>(null);
  const [oobiCopied, setOobiCopied] = useState(false);
  const [partnerOobi, setPartnerOobi] = useState("");
  const [isConnecting, setIsConnecting] = useState(false);
  const [isOobiConnected, setIsOobiConnected] = useState(false);
  const [connectError, setConnectError] = useState<string | null>(null);
  const [hasCredential, setHasCredential] = useState(false);
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(null);
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const [credential, setCredential] = useState<CredentialResponse | null>(null);
  const [isPresenting, setIsPresenting] = useState(false);
  const [presentError, setPresentError] = useState<string | null>(null);

  // Probe the session on mount so a refresh / reused tab can skip OOBI when
  // the backend already knows about us.
  useEffect(() => {
    let cancelled = false;
    getSession(sessionIdRef.current)
      .then((s) => {
        if (cancelled) return;
        if (s.exists) {
          setIsOobiConnected(true);
          if (s.hasCredential) setHasCredential(true);
        }
      })
      .catch(() => {/* best effort */});
    return () => { cancelled = true; };
  }, []);

  const [errors, setErrors] = useState({
    token: "",
    quantity: "",
    recipientAddress: "",
  });

  const isKycToken = selectedToken?.substandardId === "kyc";
  const isSecurityToken = selectedToken?.substandardId === "security-token";

  // For security-token: fetch the on-chain GS datum whenever the selected
  // token changes so we can show the admin the remaining mintable_amount
  // (and warn them before they exceed it). The backend exposes this via
  // GET /security-token/{policyId}/global-state — it reads the GS UTxO and
  // parses the 9-field BaFin datum.
  const [securityTokenGs, setSecurityTokenGs] =
    useState<SecurityTokenGlobalState | null>(null);
  const [securityTokenGsError, setSecurityTokenGsError] = useState<string | null>(null);
  const [securityTokenGsLoading, setSecurityTokenGsLoading] = useState(false);

  // D8 — a failed mint must not look like a successful one. The old flow reset the
  // form and left the previously-fetched mintable_amount on screen, so a mint that
  // never reached the chain was indistinguishable from one that did: the number
  // hadn't moved either way. These three pieces of state make the difference
  // legible — a persistent failure banner, the pre-mint figure to compare against,
  // and whether the chain has actually reflected the mint yet.
  const [mintFailure, setMintFailure] = useState<string | null>(null);
  const [preMintMintable, setPreMintMintable] = useState<number | null>(null);
  const [mintConfirmed, setMintConfirmed] = useState(false);
  const [confirmPolling, setConfirmPolling] = useState(false);

  // Guards against a stale in-flight read overwriting a newer one when the admin
  // switches tokens quickly, or when a manual refetch races the mount effect.
  const gsFetchSeq = useRef(0);

  const refreshSecurityTokenGs = useCallback(async () => {
    if (!isSecurityToken || !selectedToken) {
      gsFetchSeq.current++;
      setSecurityTokenGs(null);
      setSecurityTokenGsError(null);
      setSecurityTokenGsLoading(false);
      return null;
    }
    const seq = ++gsFetchSeq.current;
    setSecurityTokenGsLoading(true);
    setSecurityTokenGsError(null);
    try {
      const gs = await getSecurityTokenGlobalState(selectedToken.policyId);
      if (seq !== gsFetchSeq.current) return null;
      setSecurityTokenGs(gs);
      return gs;
    } catch (err: unknown) {
      if (seq !== gsFetchSeq.current) return null;
      // Drop the previous value rather than keeping it on screen. A number we
      // can no longer verify is worse than an explicit "unknown" — the whole
      // point of D8 is that stale figures were being read as fresh ones.
      setSecurityTokenGs(null);
      setSecurityTokenGsError(err instanceof Error ? err.message : String(err));
      return null;
    } finally {
      if (seq === gsFetchSeq.current) setSecurityTokenGsLoading(false);
    }
  }, [isSecurityToken, selectedToken]);

  useEffect(() => { void refreshSecurityTokenGs(); }, [refreshSecurityTokenGs]);

  /** Poll the GS datum until `mintable_amount` moves off `before`, i.e. until the
   *  mint is actually in a block.
   *
   *  `wallet.submitTx` returning a hash means "accepted into the mempool", not
   *  "applied" — the old flow declared success there and never looked again. That
   *  is precisely how a mint that never landed could present as a stale number.
   *  Bounded, best-effort: giving up just leaves the UI saying "not yet visible",
   *  which is the honest state. */
  const pollForMintOnChain = useCallback(async (policyId: string, before: number) => {
    const seq = gsFetchSeq.current;
    setConfirmPolling(true);
    try {
      for (let attempt = 0; attempt < 12; attempt++) {
        await new Promise((r) => setTimeout(r, 5_000));
        if (seq !== gsFetchSeq.current) return;   // token switched — abandon
        try {
          const gs = await getSecurityTokenGlobalState(policyId);
          if (seq !== gsFetchSeq.current) return;
          setSecurityTokenGs(gs);
          if (gs.mintableAmount !== before) {
            setMintConfirmed(true);
            return;
          }
        } catch {
          // Transient read failure — keep trying for the remaining attempts.
        }
      }
    } finally {
      if (seq === gsFetchSeq.current) setConfirmPolling(false);
    }
  }, []);

  const validateForm = (): boolean => {
    const newErrors = {
      token: "",
      quantity: "",
      recipientAddress: "",
    };

    if (!selectedToken) {
      newErrors.token = "Please select a token to mint";
    }

    if (!quantity.trim()) {
      newErrors.quantity = "Quantity is required";
    } else if (!/^\d+$/.test(quantity)) {
      newErrors.quantity = "Quantity must be a positive number";
    } else if (BigInt(quantity) <= 0) {
      newErrors.quantity = "Quantity must be greater than 0";
    } else if (isSecurityToken && securityTokenGs !== null
        && BigInt(quantity) > BigInt(securityTokenGs.mintableAmount)) {
      // Hard supply cap from the GS datum. The on-chain GS spend's MintSecurity
      // branch also enforces this (remaining_amount >= 0), but failing here
      // gives a clearer error than a Plutus eval failure.
      newErrors.quantity = `Exceeds remaining mintable amount (${securityTokenGs.mintableAmount.toLocaleString()})`;
    }

    if (!recipientAddress.trim()) {
      newErrors.recipientAddress = "Recipient address is required";
    } else if (!recipientAddress.startsWith("addr")) {
      newErrors.recipientAddress = "Invalid Cardano address format";
    }

    setErrors(newErrors);
    return !Object.values(newErrors).some((error) => error !== "");
  };

  // ── Form submission ──────────────────────────────────────────────────────

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validateForm() || !selectedToken) return;

    if (enableAttestation && isKycToken) {
      // Go to attestation step — request digest anchoring then mint
      setStep("attestation");
    } else {
      // Direct mint (no attestation)
      await buildAndSignMint(null);
    }
  };

  // ── Direct mint flow ─────────────────────────────────────────────────────

  const buildAndSignMint = async (
    attestation: Cip170AttestationData | null
  ) => {
    if (!selectedToken) return;

    // Snapshot what the chain said before we touched it, so the post-mint poll
    // has something to compare against.
    const mintableBefore = securityTokenGs?.mintableAmount ?? null;

    try {
      setIsBuilding(true);
      setStep("signing");
      setMintFailure(null);
      setMintConfirmed(false);
      setPreMintMintable(mintableBefore);

      let unsignedCborTx: string;

      // KYC has no SDK implementation in cip113-sdk-ts; force the backend path.
      const useSdk = txBuilder === "sdk" && sdkAvailableForSelected(selectedToken);

      if (useSdk) {
        const substandardId = await ensureSubstandard(selectedToken.policyId, selectedToken.assetName);
        const protocol = await getProtocol();
        const result = await protocol.mint({
          feePayerAddress,
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          quantity: BigInt(quantity),
          recipientAddress: recipientAddress.trim(),
          substandardId,
        });
        unsignedCborTx = result.cbor;
      } else {
        const request: MintTokenRequest = {
          feePayerAddress,
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          quantity,
          recipientAddress: recipientAddress.trim(),
          ...(attestation ? { attestation } : {}),
        };
        unsignedCborTx = await mintToken(request, selectedVersion?.txHash);
      }

      setIsBuilding(false);
      setIsSigning(true);

      const signedTx = await wallet.signTx(unsignedCborTx);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      showToast({
        title: "Mint Submitted",
        description: `Submitted a mint of ${quantity} ${decodeAssetNameDisplay(selectedToken.assetName)} tokens`,
        variant: "success",
      });

      // D8 — the mint changed on-chain state, so the cached global state is now
      // wrong by definition. Refetch immediately, then keep polling until
      // mintable_amount actually moves (or we give up saying so).
      if (isSecurityToken && selectedToken) {
        void refreshSecurityTokenGs();
        if (mintableBefore !== null) {
          void pollForMintOnChain(selectedToken.policyId, mintableBefore);
        }
      }
    } catch (error) {
      console.error("Mint error:", error);

      let errorMessage = "Failed to mint tokens";
      if (error instanceof Error) {
        if (error.message.includes("User declined")) {
          errorMessage = "Transaction was cancelled";
        } else {
          errorMessage = error.message;
        }
      }

      showToast({
        title: "Mint Failed",
        description: errorMessage,
        variant: "error",
      });

      // D8 — a toast disappears; the reason a failed mint has to stay on screen is
      // that the form looks identical either way. Keep the message, and re-read the
      // chain so the displayed cap is known-current rather than assumed-unchanged.
      setMintFailure(errorMessage);
      setStep("form");
      if (isSecurityToken) void refreshSecurityTokenGs();
    } finally {
      setIsBuilding(false);
      setIsSigning(false);
    }
  };

  // ── KERI handshake (OOBI + credential presentation) ─────────────────────

  const handleLoadOobi = useCallback(async () => {
    try {
      setIsConnecting(true);
      setConnectError(null);
      const data = await getAgentOobi(sessionIdRef.current);
      setOobiUrl(data.oobi);
    } catch (err) {
      setConnectError(err instanceof Error ? err.message : "Failed to load agent OOBI");
    } finally {
      setIsConnecting(false);
    }
  }, []);

  const handleResolveOobi = useCallback(async () => {
    if (!partnerOobi.trim()) return;
    try {
      setIsConnecting(true);
      setConnectError(null);
      await resolveOobi(sessionIdRef.current, partnerOobi.trim());
      const addresses = await wallet.getUsedAddresses();
      if (addresses?.[0]) {
        await storeCardanoAddress(sessionIdRef.current, addresses[0]);
      }
      const rolesData = await getAvailableRoles(sessionIdRef.current);
      setAvailableRoles(rolesData.availableRoles);
      setIsOobiConnected(true);
      showToast({
        title: "Connected",
        description: "OOBI resolved. Present a credential to continue.",
        variant: "success",
      });
    } catch (err) {
      setConnectError(err instanceof Error ? err.message : "Failed to resolve OOBI. Make sure the URL is correct.");
    } finally {
      setIsConnecting(false);
    }
  }, [partnerOobi, wallet, showToast]);

  const loadAvailableRoles = useCallback(async () => {
    try {
      const rolesData = await getAvailableRoles(sessionIdRef.current);
      setAvailableRoles(rolesData.availableRoles);
    } catch (err) {
      setPresentError(err instanceof Error ? err.message : "Failed to load roles");
    }
  }, []);

  const handlePresentCredential = useCallback(async () => {
    if (!selectedRole) return;
    try {
      setIsPresenting(true);
      setPresentError(null);
      const cred = await presentCredential(sessionIdRef.current, selectedRole);
      setCredential(cred);
      setHasCredential(true);
      showToast({
        title: "Credential Verified",
        description: `${cred.label} credential admitted.`,
        variant: "success",
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to present credential";
      if (msg.includes("409")) setPresentError("Presentation was cancelled.");
      else if (msg.includes("408")) setPresentError("Timed out waiting for credential.");
      else setPresentError(msg);
    } finally {
      setIsPresenting(false);
    }
  }, [selectedRole, showToast]);

  const handleCancelPresent = useCallback(() => {
    cancelPresentation(sessionIdRef.current).catch(() => {});
    setIsPresenting(false);
  }, []);

  // ── Attestation Anchoring ────────────────────────────────────────────────

  const handleAttestation = async () => {
    if (!selectedToken) return;

    try {
      setIsBuilding(true);
      setAttestError(null);

      const unit = selectedToken.policyId + selectedToken.assetName;

      const attestData = await requestAttestation(
        sessionIdRef.current,
        unit,
        quantity
      );

      setAttestationData(attestData);

      showToast({
        title: "Attestation Anchored",
        description: "Digest anchored in KEL. Building mint transaction...",
        variant: "success",
      });

      // Proceed to build and sign mint tx with attestation
      await buildAndSignMint(attestData);
    } catch (error) {
      console.error("Attestation error:", error);
      let errorMessage = "Failed to anchor attestation";
      if (error instanceof Error) {
        if (error.message.includes("408")) {
          errorMessage =
            "Wallet did not respond to anchor request in time.";
        } else if (error.message.includes("409")) {
          errorMessage = "Attestation request was cancelled.";
        } else {
          errorMessage = error.message;
        }
      }
      setAttestError(errorMessage);
    } finally {
      setIsBuilding(false);
    }
  };

  // ── Reset ────────────────────────────────────────────────────────────────

  const handleReset = () => {
    setStep("form");
    setQuantity("");
    setRecipientAddress(feePayerAddress);
    setTxHash(null);
    // D8 — "Mint More" used to return to a form still showing the PRE-mint
    // mintable_amount, because the fetch effect keys on [isSecurityToken,
    // selectedToken] and neither changes here. Refetch explicitly.
    setMintFailure(null);
    setMintConfirmed(false);
    setPreMintMintable(null);
    if (isSecurityToken) void refreshSecurityTokenGs();
    setEnableAttestation(false);
    setAttestationData(null);
    setAttestError(null);
    setErrors({ token: "", quantity: "", recipientAddress: "" });
    sessionStorage.removeItem("mint-keri-session-id");
    sessionIdRef.current = getSessionId();
    // Reset KERI handshake state — the new session needs a fresh OOBI exchange
    setOobiUrl(null);
    setPartnerOobi("");
    setIsOobiConnected(false);
    setHasCredential(false);
    setAvailableRoles(null);
    setSelectedRole(null);
    setCredential(null);
    setConnectError(null);
    setPresentError(null);
  };

  // ── Renders ──────────────────────────────────────────────────────────────

  if (mintableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <Coins className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">
          No Minting Access
        </h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t hold admin or minter capabilities for any registered tokens.
        </p>
      </div>
    );
  }

  // ── Success step ─────────────────────────────────────────────────────────

  if (step === "success" && txHash) {
    return (
      <div className="flex flex-col items-center py-8">
        <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
          <CheckCircle className="h-8 w-8 text-green-500" />
        </div>
        <h3 className="text-lg font-semibold text-white mb-2">
          {isSecurityToken && !mintConfirmed ? "Mint Submitted" : "Mint Complete!"}
        </h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          {isSecurityToken && !mintConfirmed ? "Submitted a mint of " : "Successfully minted "}
          {quantity}{" "}
          {selectedToken ? decodeAssetNameDisplay(selectedToken.assetName) : ""}{" "}
          tokens
        </p>

        <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
          <p className="text-xs text-dark-400 mb-1">Mint Transaction Hash</p>
          <p className="text-xs text-primary-400 font-mono break-all">
            {txHash}
          </p>
        </div>

        {/* D8 — submitting is not the same as landing. Report which one we have
            observed, by watching mintable_amount rather than assuming. */}
        {isSecurityToken && (
          <div className={`w-full px-4 py-3 rounded-lg mb-4 border ${
            mintConfirmed
              ? "bg-green-500/5 border-green-500/40"
              : "bg-dark-900 border-dark-700"
          }`}>
            {mintConfirmed ? (
              <>
                <p className="text-xs text-green-400 font-medium">Confirmed on chain</p>
                <p className="text-xs text-dark-400 mt-1">
                  Remaining mintable went from{" "}
                  <span className="font-mono text-dark-300">{preMintMintable ?? "?"}</span> to{" "}
                  <span className="font-mono text-green-300">
                    {securityTokenGs?.mintableAmount ?? "?"}
                  </span>.
                </p>
              </>
            ) : (
              <>
                <p className="text-xs text-dark-300 font-medium">
                  {confirmPolling
                    ? "Waiting for the transaction to appear on chain…"
                    : "Not yet visible on chain"}
                </p>
                <p className="text-xs text-dark-400 mt-1">
                  The wallet accepted the transaction into the mempool. Remaining mintable
                  still reads{" "}
                  <span className="font-mono text-dark-300">
                    {securityTokenGs?.mintableAmount ?? "unknown"}
                  </span>
                  {preMintMintable !== null && (
                    <>, the same as before the mint (
                    <span className="font-mono">{preMintMintable}</span>)</>
                  )}
                  {" "}— it will change once the transaction is included in a block.
                  {!confirmPolling && " If it never does, the mint did not reach the chain."}
                </p>
              </>
            )}
          </div>
        )}

        {attestationData && (
          <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
            <div className="flex items-center gap-2 mb-2">
              <Shield className="h-4 w-4 text-green-400" />
              <p className="text-xs text-green-400 font-medium">
                CIP-170 Attested
              </p>
            </div>
            <p className="text-xs text-dark-400">
              Signer: {attestationData.signerAid}
            </p>
            <p className="text-xs text-dark-400">
              Digest: {attestationData.digest}
            </p>
          </div>
        )}

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
          <Button variant="primary" className="flex-1" onClick={handleReset}>
            Mint More
          </Button>
        </div>
      </div>
    );
  }

  // ── Signing step ─────────────────────────────────────────────────────────

  if (step === "signing") {
    return (
      <div className="flex flex-col items-center py-12">
        <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-white font-medium">
          {isSigning
            ? "Waiting for signature..."
            : "Building transaction..."}
        </p>
        <p className="text-sm text-dark-400 mt-2">
          Please confirm the transaction in your wallet
        </p>
      </div>
    );
  }

  // ── Attestation step ─────────────────────────────────────────────────────

  if (step === "attestation") {
    // Walk the user through the four logical phases. Each phase is "done" once the
    // corresponding state flips, which mirrors what the jbang script does on the CLI.
    const phaseDone = {
      connect: isOobiConnected,
      present: hasCredential,
      anchor: !!attestationData,
      mint: false, // happens after this step exits to "signing"
    };
    const stepRow = (n: number, label: string, done: boolean, active: boolean) => (
      <div className={`flex items-center gap-2 text-xs ${
        done ? "text-green-400" : active ? "text-primary-400" : "text-dark-500"
      }`}>
        <span className={`w-5 h-5 rounded-full border flex items-center justify-center ${
          done ? "border-green-500 bg-green-500/10"
            : active ? "border-primary-500 bg-primary-500/10"
              : "border-dark-700"
        }`}>{done ? "✓" : n}</span>
        <span>{label}</span>
      </div>
    );

    return (
      <div className="space-y-6">
        <h3 className="text-lg font-semibold text-white">
          CIP-170 Attestation
        </h3>

        <Card className="p-4 space-y-2">
          {stepRow(1, "Connect Veridian wallet (mutual OOBI)", phaseDone.connect, !phaseDone.connect)}
          {stepRow(2, "Present a verifiable credential",        phaseDone.present, phaseDone.connect && !phaseDone.present)}
          {stepRow(3, "Anchor mint digest in your KEL",         phaseDone.anchor,  phaseDone.present && !phaseDone.anchor)}
          {stepRow(4, "Submit the mint transaction",            phaseDone.mint,    phaseDone.anchor)}
        </Card>

        <p className="text-sm text-dark-400">
          The mint digest (token unit + quantity) will be sent as a remote-sign request to
          your Veridian wallet. After you approve, the wallet anchors the digest as an
          interact event in its KEL, and we attach a CIP-170 ATTEST metadata block (label
          170) to the on-chain mint transaction so verifiers can correlate the two.
        </p>

        {attestError && (
          <div className="px-4 py-3 bg-red-500/10 border border-red-500/30 rounded-lg">
            <p className="text-sm text-red-400">{attestError}</p>
          </div>
        )}

        <div className="px-4 py-3 bg-dark-900 rounded-lg space-y-1">
          <p className="text-xs text-dark-400">Token</p>
          <p className="text-sm text-white">
            {selectedToken?.assetNameDisplay}
          </p>
          <p className="text-xs text-dark-400 mt-2">Quantity</p>
          <p className="text-sm text-white">{quantity}</p>
        </div>

        {(connectError || presentError) && (
          <div className="px-4 py-3 bg-red-500/10 border border-red-500/30 rounded-lg">
            <p className="text-sm text-red-400">{connectError || presentError}</p>
          </div>
        )}

        {/* Step 1: load agent OOBI */}
        {!isOobiConnected && !oobiUrl && (
          <Button
            variant="primary"
            className="w-full"
            onClick={handleLoadOobi}
            isLoading={isConnecting}
            disabled={isConnecting}
          >
            <QrCode className="h-4 w-4 mr-2" />
            {isConnecting ? "Loading..." : "Connect Veridian Wallet"}
          </Button>
        )}

        {/* Step 2: share agent OOBI for the user to paste into Veridian */}
        {!isOobiConnected && oobiUrl && (
          <div className="space-y-3">
            <p className="text-xs text-dark-400">
              Share this OOBI URL with your Veridian wallet, then paste your wallet&apos;s OOBI below.
            </p>
            <div className="px-3 py-2 bg-dark-900 rounded-lg">
              <p className="text-xs text-primary-400 font-mono break-all">{oobiUrl}</p>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                navigator.clipboard.writeText(oobiUrl);
                setOobiCopied(true);
                setTimeout(() => setOobiCopied(false), 2000);
              }}
            >
              <Copy className="h-4 w-4 mr-2" />
              {oobiCopied ? "Copied!" : "Copy OOBI"}
            </Button>
            <Input
              label="Wallet OOBI URL"
              value={partnerOobi}
              onChange={(e) => setPartnerOobi(e.target.value)}
              placeholder="Paste your Veridian wallet OOBI..."
              disabled={isConnecting}
            />
            <Button
              variant="primary"
              className="w-full"
              onClick={handleResolveOobi}
              isLoading={isConnecting}
              disabled={!partnerOobi.trim() || isConnecting}
            >
              {isConnecting ? "Resolving..." : "Resolve & Connect"}
            </Button>
          </div>
        )}

        {/* Step 3: present credential (only if connected but no credential admitted yet) */}
        {isOobiConnected && !hasCredential && (
          <div className="space-y-3">
            <p className="text-xs text-dark-400">
              Select a role and present the matching credential from your Veridian wallet.
            </p>

            {!availableRoles && (
              <Button
                variant="outline"
                className="w-full text-xs h-7"
                onClick={loadAvailableRoles}
              >
                Load available roles
              </Button>
            )}

            {availableRoles && availableRoles.length > 0 && (
              <div className="space-y-2">
                {availableRoles.map((r) => (
                  <button
                    key={r.role}
                    type="button"
                    onClick={() => setSelectedRole(r.role)}
                    disabled={isPresenting}
                    className={`w-full px-4 py-3 rounded-lg border text-left transition-colors ${
                      selectedRole === r.role
                        ? "border-primary-500 bg-primary-500/10 text-white"
                        : "border-dark-700 bg-dark-800 text-dark-300 hover:border-dark-600"
                    }`}
                  >
                    <span className="font-medium">{r.label}</span>
                  </button>
                ))}
              </div>
            )}

            {availableRoles && availableRoles.length === 0 && (
              <p className="text-xs text-yellow-400">
                No issuable roles configured on the backend.
              </p>
            )}

            <Button
              variant="primary"
              className="w-full"
              onClick={handlePresentCredential}
              isLoading={isPresenting}
              disabled={!selectedRole || isPresenting}
            >
              {isPresenting ? "Waiting for wallet..." : "Request Credential Presentation"}
            </Button>

            {isPresenting && (
              <Button variant="ghost" className="w-full" onClick={handleCancelPresent}>
                Cancel
              </Button>
            )}
          </div>
        )}

        {/* Step 4: anchor + mint (only when session is fully ready) */}
        {credential && (
          <div className="px-4 py-3 bg-green-500/10 border border-green-500/30 rounded-lg">
            <div className="flex items-center gap-2">
              <Shield className="h-4 w-4 text-green-400" />
              <span className="text-sm text-green-400 font-medium">{credential.label} verified</span>
            </div>
          </div>
        )}

        <div className="flex gap-3">
          <Button
            variant="outline"
            onClick={() => {
              setStep("form");
              setAttestError(null);
            }}
          >
            Back
          </Button>
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleAttestation}
            isLoading={isBuilding}
            disabled={isBuilding || !isOobiConnected || !hasCredential}
          >
            {isBuilding
              ? "Waiting for Veridian approval…"
              : !isOobiConnected
                ? "Connect wallet first"
                : !hasCredential
                  ? "Present a credential first"
                  : "Anchor Digest & Mint with Attestation"}
          </Button>
        </div>

        {isBuilding && phaseDone.present && !phaseDone.anchor && (
          <p className="text-xs text-dark-400 text-center">
            👉 Open the Veridian app, look for an incoming remote-sign request, and tap
            Approve. The backend is polling KEL — this can take up to 5 minutes.
          </p>
        )}
      </div>
    );
  }

  // ── Form step ────────────────────────────────────────────────────────────

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <TxBuilderToggle
        value={sdkAvailableForSelected(selectedToken) ? txBuilder : "backend"}
        onChange={setTxBuilder}
        sdkAvailable={sdkAvailableForSelected(selectedToken)}
      />
      {selectedToken?.substandardId === "kyc" && (
        <p className="text-xs text-dark-400 -mt-3">
          KYC tokens are minted via the backend (no SDK substandard available).
        </p>
      )}
      {isSecurityToken && (
        <p className="text-xs text-dark-400 -mt-3">
          RWA tokens are minted via the backend (BaFin MintSecurity flow:
          spends the global-state UTxO and decrements the on-chain supply cap).
        </p>
      )}

      {/* Token Selector */}
      <div>
        <AdminTokenSelector
          tokens={mintableTokens}
          selectedToken={selectedToken}
          onSelect={(token) => {
            setSelectedToken(token);
            setEnableAttestation(false);
            setErrors((prev) => ({ ...prev, token: "" }));
          }}
          disabled={isBuilding}
          filterByRole="ISSUER_ADMIN"
        />
        {errors.token && (
          <p className="mt-2 text-sm text-red-400">{errors.token}</p>
        )}
      </div>

      {/* D8 — a failed mint leaves the form looking exactly like an untouched one.
          Keep the reason visible until the admin acts on it, and say plainly that
          nothing changed on chain, since the identical mintable_amount below is
          otherwise ambiguous. */}
      {mintFailure && (
        <div className="px-4 py-3 rounded-lg border border-red-500/50 bg-red-500/10">
          <p className="text-sm font-semibold text-red-300">The last mint failed</p>
          <p className="mt-1 text-xs text-red-200/90 break-words">{mintFailure}</p>
          <p className="mt-2 text-xs text-red-200/70">
            Nothing was written to the chain, so the figures below are unchanged — that is
            the failure, not a stale view. They were re-read from the chain just now.
          </p>
          <button
            type="button"
            onClick={() => setMintFailure(null)}
            className="mt-2 text-xs text-red-300 underline hover:text-red-200"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Security-token: remaining mintable_amount from the GS datum. The
          cap is enforced on-chain via the GS spend's MintSecurity branch;
          showing it here lets the admin size their mint accordingly. */}
      {isSecurityToken && (
        <div className="px-4 py-3 bg-dark-900 rounded-lg border border-dark-700">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="flex items-center gap-2">
                <p className="text-xs text-dark-400 uppercase tracking-wide">
                  Remaining mintable
                </p>
                <button
                  type="button"
                  onClick={() => { void refreshSecurityTokenGs(); }}
                  disabled={securityTokenGsLoading}
                  className="text-xs text-dark-500 hover:text-primary-400 transition-colors"
                  title="Re-read the global state from chain"
                >
                  refresh
                </button>
              </div>
              {securityTokenGsLoading ? (
                <p className="mt-1 text-sm text-dark-400 italic">
                  Reading on-chain global state…
                </p>
              ) : securityTokenGsError ? (
                <>
                  <p className="mt-1 text-lg font-semibold text-dark-500">unknown</p>
                  <p className="mt-1 text-sm text-red-400">
                    Could not read GS datum: {securityTokenGsError}
                  </p>
                </>
              ) : securityTokenGs ? (
                <>
                  <p className="mt-1 text-lg font-semibold text-white">
                    {securityTokenGs.mintableAmount.toLocaleString()}
                    <span className="ml-2 text-sm text-dark-400 font-normal">tokens</span>
                  </p>
                  {securityTokenGs.transfersPaused && (
                    <p className="mt-1 text-xs text-orange-400">
                      Transfers are currently paused on this token.
                    </p>
                  )}
                  {securityTokenGs.deactivated && (
                    <p className="mt-1 text-xs text-red-400">
                      This contract has been decommissioned — no further mint can succeed.
                    </p>
                  )}
                </>
              ) : null}
            </div>
            <div className="text-right">
              <p className="text-xs text-dark-500">
                Each mint spends the GS UTxO with{" "}
                <span className="font-mono text-dark-400">MintSecurity</span> and
                decrements this amount on chain.
              </p>
            </div>
          </div>
        </div>
      )}

      {/* Quantity */}
      <Input
        label="Quantity"
        type="number"
        value={quantity}
        onChange={(e) => {
          setQuantity(e.target.value);
          setErrors((prev) => ({ ...prev, quantity: "" }));
        }}
        placeholder="Enter amount to mint"
        disabled={isBuilding || !selectedToken}
        error={errors.quantity}
        helperText={
          isSecurityToken && securityTokenGs
            ? `Up to ${securityTokenGs.mintableAmount.toLocaleString()} can be minted before the supply cap is reached`
            : "Number of tokens to mint"
        }
      />

      {/* Recipient Address */}
      <Input
        label="Recipient Address"
        value={recipientAddress}
        onChange={(e) => {
          setRecipientAddress(e.target.value);
          setErrors((prev) => ({ ...prev, recipientAddress: "" }));
        }}
        placeholder="addr1..."
        disabled={isBuilding || !selectedToken}
        error={errors.recipientAddress}
        helperText="Address to receive the minted tokens"
      />

      {/* CIP-170 Attestation Toggle (only for KYC tokens) */}
      {isKycToken && (
        <div className="px-4 py-3 bg-dark-900 rounded-lg">
          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={enableAttestation}
              onChange={(e) => setEnableAttestation(e.target.checked)}
              disabled={isBuilding}
              className="w-4 h-4 rounded border-dark-600 bg-dark-800 text-primary-500 focus:ring-primary-500"
            />
            <div>
              <p className="text-sm text-white font-medium">
                Enable CIP-170 Attestation
              </p>
              <p className="text-xs text-dark-400">
                Attest this mint with your Veridian wallet (anchors digest in
                KEL and attaches ATTEST metadata to the mint transaction)
              </p>
            </div>
          </label>
        </div>
      )}

      {/* Submit Button */}
      <Button
        type="submit"
        variant="primary"
        className="w-full"
        isLoading={isBuilding}
        disabled={isBuilding || !selectedToken}
      >
        {isBuilding
          ? "Building Transaction..."
          : enableAttestation
            ? "Start Attestation & Mint"
            : "Mint Tokens"}
      </Button>
    </form>
  );
}
