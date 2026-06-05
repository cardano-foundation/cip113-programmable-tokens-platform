"use client";

import { useState, useEffect, useCallback } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Settings,
  PauseCircle,
  PlayCircle,
  Shield,
  Plus,
  Trash2,
  CheckCircle,
  ExternalLink,
  RefreshCw,
  Loader2,
} from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import {
  AdminTokenInfo,
  SecurityTokenCapability,
  hasSecurityTokenCapability,
} from "@/lib/api/admin";
import { getSigningEntityVkey } from "@/lib/api/keri";
import { readGlobalState, updateGlobalState } from "@/lib/api/compliance";
import {
  buildGlobalStateUpdateChain,
  submitTokenChain,
  getSecurityTokenGlobalState,
  acknowledgeRootPublish,
  type GsChangeSpec,
} from "@/lib/api/security-token";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { cn } from "@/lib/utils";
import type { GlobalStateAction, GlobalStateData } from "@/types/compliance";

interface GlobalStateSectionProps {
  tokens: AdminTokenInfo[];
  adminAddress: string;
}

type SectionStep = "form" | "signing" | "success";

export function GlobalStateSection({
  tokens,
  adminAddress,
}: GlobalStateSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  // kyc: legacy ISSUER_ADMIN role gating.
  // security-token: BaFin's GS spend validator is admin-gated via the
  //   admin_credential_hash field of the datum — anyone with the ADMIN
  //   capability bit in the on-chain power-users LL counts as such.
  const manageableTokens = tokens.filter((t) => {
    if (t.substandardId === "security-token") {
      return hasSecurityTokenCapability(t, SecurityTokenCapability.ADMIN);
    }
    return t.roles.includes("ISSUER_ADMIN") && t.substandardId === "kyc";
  });

  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(null);
  const [step, setStep] = useState<SectionStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  // On-chain state loaded from backend
  const [globalState, setGlobalState] = useState<GlobalStateData | null>(null);
  const [isLoadingState, setIsLoadingState] = useState(false);
  const [signingEntityVkey, setSigningEntityVkey] = useState<string | null>(null);

  // Editable form fields (initialized from on-chain state)
  const [transfersPaused, setTransfersPaused] = useState(false);
  const [mintableAmount, setMintableAmount] = useState("");
  const [securityInfo, setSecurityInfo] = useState("");
  const [trustedEntities, setTrustedEntities] = useState<string[]>([]);
  const [newEntityInput, setNewEntityInput] = useState("");

  // Load signing entity vkey once
  useEffect(() => {
    getSigningEntityVkey()
      .then((r) => setSigningEntityVkey(r.vkeyHex))
      .catch(() => {});
  }, []);

  // Load current global state when token is selected
  const loadGlobalState = useCallback(async (policyId: string) => {
    setIsLoadingState(true);
    try {
      const state = await readGlobalState(policyId);
      setGlobalState(state);
      // Initialize form from on-chain state
      setTransfersPaused(state.transfersPaused);
      setMintableAmount(state.mintableAmount.toString());
      setSecurityInfo(state.securityInfo || "");
      setTrustedEntities([...state.trustedEntities]);
    } catch (error) {
      console.error("Failed to load global state:", error);
      showToast({
        title: "Error Loading State",
        description: error instanceof Error ? error.message : "Could not read on-chain global state",
        variant: "error",
      });
      setGlobalState(null);
    } finally {
      setIsLoadingState(false);
    }
  }, [showToast]);

  useEffect(() => {
    if (!selectedToken) {
      setGlobalState(null);
      return;
    }
    // security-token has its own GS endpoint at /api/v1/security-token/.../global-state
    // and renders SecurityTokenGlobalStatePanel below; the kyc compliance endpoint
    // would 404 for security-token policies, so skip the load here.
    if (selectedToken.substandardId === "security-token") {
      setGlobalState(null);
      return;
    }
    loadGlobalState(selectedToken.policyId);
  }, [selectedToken, loadGlobalState]);

  // Detect which fields changed
  const entitiesChanged = globalState && (
    trustedEntities.length !== globalState.trustedEntities.length ||
    trustedEntities.some((e, i) => e !== globalState.trustedEntities[i])
  );
  const pauseChanged = globalState && transfersPaused !== globalState.transfersPaused;
  const mintableChanged = globalState && mintableAmount !== globalState.mintableAmount.toString();
  const securityChanged = globalState && securityInfo !== (globalState.securityInfo || "");
  const hasChanges = entitiesChanged || pauseChanged || mintableChanged || securityChanged;

  const handleAddEntity = () => {
    const vkey = newEntityInput.trim().toLowerCase();
    if (!/^[0-9a-f]{64}$/.test(vkey)) {
      showToast({ title: "Invalid Key", description: "Must be exactly 64 hex characters", variant: "error" });
      return;
    }
    if (trustedEntities.includes(vkey)) {
      showToast({ title: "Duplicate", description: "Key already in the list", variant: "error" });
      return;
    }
    setTrustedEntities((prev) => [...prev, vkey]);
    setNewEntityInput("");
  };

  const handleRemoveEntity = (vkey: string) => {
    setTrustedEntities((prev) => prev.filter((e) => e !== vkey));
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedToken || !globalState) return;

    // Determine which changes need to be submitted.
    // The on-chain validator allows only ONE action per tx, so we submit sequentially.
    // ORDER MATTERS: MODIFY_SECURITY_INFO is the only action whose validator allows the
    // global-state UTxO's lovelace to change (via the `without_lovelace` equality check).
    // The other actions require exact value preservation, so any datum growth (e.g. a new
    // trusted entity) can push the output past the on-chain min-utxo and cause Ogmios to
    // reject submission. Running security info first lets the backend top up the UTxO's
    // lovelace before we attempt the strict-equality actions.
    const changes: Array<{ label: string; action: () => Promise<string> }> = [];

    if (securityChanged) {
      changes.push({
        label: "Updating security info",
        action: async () => {
          const response = await updateGlobalState(
            { adminAddress, policyId: selectedToken.policyId, action: "MODIFY_SECURITY_INFO" as GlobalStateAction, securityInfo: securityInfo || undefined },
            selectedVersion?.txHash
          );
          if (!response.isSuccessful || !response.unsignedCborTx) {
            throw new Error(response.error || "Failed to build security info tx");
          }
          const signedTx = await wallet.signTx(response.unsignedCborTx, true);
          return wallet.submitTx(signedTx);
        },
      });
    }

    if (entitiesChanged) {
      changes.push({
        label: "Updating trusted entities",
        action: async () => {
          const response = await updateGlobalState(
            { adminAddress, policyId: selectedToken.policyId, action: "MODIFY_TRUSTED_ENTITIES" as GlobalStateAction, trustedEntities },
            selectedVersion?.txHash
          );
          if (!response.isSuccessful || !response.unsignedCborTx) {
            throw new Error(response.error || "Failed to build modify trusted entities tx");
          }
          const signedTx = await wallet.signTx(response.unsignedCborTx, true);
          return wallet.submitTx(signedTx);
        },
      });
    }

    if (pauseChanged) {
      changes.push({
        label: transfersPaused ? "Pausing transfers" : "Unpausing transfers",
        action: async () => {
          const response = await updateGlobalState(
            { adminAddress, policyId: selectedToken.policyId, action: "PAUSE_TRANSFERS" as GlobalStateAction, transfersPaused },
            selectedVersion?.txHash
          );
          if (!response.isSuccessful || !response.unsignedCborTx) {
            throw new Error(response.error || "Failed to build pause tx");
          }
          const signedTx = await wallet.signTx(response.unsignedCborTx, true);
          return wallet.submitTx(signedTx);
        },
      });
    }

    if (mintableChanged) {
      changes.push({
        label: "Updating mintable amount",
        action: async () => {
          const response = await updateGlobalState(
            { adminAddress, policyId: selectedToken.policyId, action: "UPDATE_MINTABLE_AMOUNT" as GlobalStateAction, mintableAmount: parseInt(mintableAmount, 10) },
            selectedVersion?.txHash
          );
          if (!response.isSuccessful || !response.unsignedCborTx) {
            throw new Error(response.error || "Failed to build mintable amount tx");
          }
          const signedTx = await wallet.signTx(response.unsignedCborTx, true);
          return wallet.submitTx(signedTx);
        },
      });
    }

    if (changes.length === 0) return;

    try {
      setIsBuilding(true);
      let lastHash = "";

      for (const change of changes) {
        setStep("signing");
        showToast({ title: change.label, description: "Please sign in your wallet...", variant: "default" });
        lastHash = await change.action();
      }

      setTxHash(lastHash);
      setStep("success");
      showToast({ title: "Global state updated", description: `${changes.length} change(s) submitted`, variant: "success" });
    } catch (error) {
      console.error("Global state update error:", error);
      const msg = error instanceof Error
        ? (error.message.includes("User declined") ? "Transaction was cancelled" : error.message)
        : "Failed to update global state";
      showToast({ title: "Update Failed", description: msg, variant: "error" });
      setStep("form");
    } finally {
      setIsBuilding(false);
    }
  };

  const handleReset = () => {
    setStep("form");
    setTxHash(null);
    if (selectedToken) {
      loadGlobalState(selectedToken.policyId);
    }
  };

  if (manageableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <Settings className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">No Global-State Management Access</h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t hold admin capability for any registered tokens.
        </p>
      </div>
    );
  }

  if (step === "success" && txHash) {
    return (
      <div className="flex flex-col items-center py-8">
        <div className="w-16 h-16 rounded-full bg-green-500/10 flex items-center justify-center mb-4">
          <CheckCircle className="h-8 w-8 text-green-500" />
        </div>
        <h3 className="text-lg font-semibold text-white mb-2">Global State Updated</h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          The on-chain global state has been updated.
        </p>
        <div className="w-full px-4 py-3 bg-dark-900 rounded-lg mb-4">
          <p className="text-xs text-dark-400 mb-1">Transaction Hash</p>
          <p className="text-xs text-primary-400 font-mono break-all">{txHash}</p>
        </div>
        <div className="flex gap-3 w-full">
          <a href={getExplorerTxUrl(txHash)} target="_blank" rel="noopener noreferrer" className="flex-1">
            <Button variant="ghost" className="w-full">
              <ExternalLink className="h-4 w-4 mr-2" />
              View on Explorer
            </Button>
          </a>
          <Button variant="primary" className="flex-1" onClick={handleReset}>Update More</Button>
        </div>
      </div>
    );
  }

  if (step === "signing") {
    return (
      <div className="flex flex-col items-center py-12">
        <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-white font-medium">Waiting for signature...</p>
        <p className="text-sm text-dark-400 mt-2">Please confirm the transaction in your wallet</p>
      </div>
    );
  }

  // Branch render: security-tokens use the BaFin GS validator which has a
  // different datum shape, different actions, and admin gating via
  // admin_credential_hash (not a power-user role). Render its own panel.
  if (selectedToken?.substandardId === "security-token") {
    return (
      <div className="space-y-6">
        <AdminTokenSelector
          tokens={manageableTokens}
          selectedToken={selectedToken}
          onSelect={setSelectedToken}
          disabled={false}
          filterByRole="ISSUER_ADMIN"
        />
        <SecurityTokenGlobalStatePanel
          policyId={selectedToken.policyId}
          adminAddress={adminAddress}
          signingEntityVkey={signingEntityVkey}
        />
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {/* Token Selector */}
      <AdminTokenSelector
        tokens={manageableTokens}
        selectedToken={selectedToken}
        onSelect={setSelectedToken}
        disabled={isBuilding}
        filterByRole="ISSUER_ADMIN"
      />

      {/* Loading State */}
      {isLoadingState && (
        <div className="flex items-center justify-center gap-3 py-8">
          <Loader2 className="h-5 w-5 text-primary-400 animate-spin" />
          <span className="text-sm text-dark-300">Loading on-chain state...</span>
        </div>
      )}

      {/* Current State Display & Editor */}
      {globalState && !isLoadingState && (
        <>
          {/* Refresh button */}
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium text-white">On-Chain State</span>
            <button
              type="button"
              onClick={() => selectedToken && loadGlobalState(selectedToken.policyId)}
              disabled={isBuilding}
              className="flex items-center gap-1.5 text-xs text-dark-400 hover:text-primary-400 transition-colors"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Refresh
            </button>
          </div>

          {/* Transfer Pause Toggle */}
          <div>
            <label className="block text-sm font-medium text-white mb-2">Transfer Status</label>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={() => setTransfersPaused(true)}
                disabled={isBuilding}
                className={cn(
                  "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
                  transfersPaused
                    ? "bg-red-500/10 border-red-500 text-red-400"
                    : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
                )}
              >
                <PauseCircle className="h-4 w-4" />
                Paused
              </button>
              <button
                type="button"
                onClick={() => setTransfersPaused(false)}
                disabled={isBuilding}
                className={cn(
                  "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
                  !transfersPaused
                    ? "bg-green-500/10 border-green-500 text-green-400"
                    : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
                )}
              >
                <PlayCircle className="h-4 w-4" />
                Active
              </button>
            </div>
            {pauseChanged && (
              <p className="mt-1.5 text-xs text-yellow-400">
                Changed from {globalState.transfersPaused ? "paused" : "active"}
              </p>
            )}
          </div>

          {/* Mintable Amount */}
          <Input
            label="Mintable Amount"
            type="number"
            min="0"
            value={mintableAmount}
            onChange={(e) => setMintableAmount(e.target.value)}
            disabled={isBuilding}
            helperText={mintableChanged
              ? `Current on-chain value: ${globalState.mintableAmount}`
              : undefined}
          />

          {/* Security Info */}
          <Input
            label="Security Info (hex)"
            value={securityInfo}
            onChange={(e) => setSecurityInfo(e.target.value)}
            placeholder="Leave empty for none"
            disabled={isBuilding}
            helperText={securityChanged
              ? `Current on-chain value: ${globalState.securityInfo || "(empty)"}`
              : undefined}
          />

          {/* Trusted Entities */}
          <div className="space-y-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Shield className="h-4 w-4 text-primary-400" />
                <span className="text-sm font-medium text-white">
                  Trusted Entities ({trustedEntities.length})
                </span>
              </div>
              {entitiesChanged && (
                <span className="text-xs text-yellow-400">Modified</span>
              )}
            </div>

            {trustedEntities.length > 0 ? (
              <ul className="space-y-2">
                {trustedEntities.map((vkey) => {
                  const isSigningKey = vkey === signingEntityVkey;
                  const isNew = !globalState.trustedEntities.includes(vkey);
                  return (
                    <li
                      key={vkey}
                      className={cn(
                        "flex items-center gap-2 rounded px-3 py-2",
                        isSigningKey
                          ? "bg-green-900/40 border border-green-700/50"
                          : isNew
                            ? "bg-blue-900/30 border border-blue-700/50"
                            : "bg-dark-800 border border-dark-700"
                      )}
                    >
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5 mb-0.5">
                          {isSigningKey && (
                            <span className="inline-block text-[10px] font-semibold text-green-300 bg-green-800/60 rounded px-1.5 py-0.5">
                              Signing entity
                            </span>
                          )}
                          {isNew && (
                            <span className="inline-block text-[10px] font-semibold text-blue-300 bg-blue-800/60 rounded px-1.5 py-0.5">
                              New
                            </span>
                          )}
                        </div>
                        <p className="font-mono text-xs text-dark-200 truncate">{vkey}</p>
                      </div>
                      <button
                        type="button"
                        onClick={() => handleRemoveEntity(vkey)}
                        disabled={isBuilding}
                        className="text-dark-400 hover:text-red-400 transition-colors shrink-0 p-1"
                        title="Remove entity"
                      >
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p className="text-xs text-dark-500 italic py-2">No trusted entities configured.</p>
            )}

            {/* Add entity input */}
            <div className="flex gap-2">
              <Input
                label=""
                value={newEntityInput}
                onChange={(e) => setNewEntityInput(e.target.value)}
                onKeyDown={(e) => { if (e.key === "Enter") { e.preventDefault(); handleAddEntity(); } }}
                placeholder="64-char hex Ed25519 vkey"
                disabled={isBuilding}
                className="flex-1"
              />
              <Button
                type="button"
                variant="outline"
                onClick={handleAddEntity}
                disabled={isBuilding || newEntityInput.trim().length === 0}
                className="self-end"
              >
                <Plus className="h-4 w-4" />
              </Button>
            </div>

            {/* Quick add signing entity key */}
            {signingEntityVkey && !trustedEntities.includes(signingEntityVkey) && (
              <Button
                type="button"
                variant="outline"
                className="text-xs h-7 px-3"
                onClick={() => setTrustedEntities((prev) => [...prev, signingEntityVkey])}
                disabled={isBuilding}
              >
                + Add signing entity key
              </Button>
            )}
          </div>

          {/* Submit Button */}
          <Button
            type="submit"
            variant="primary"
            className="w-full"
            isLoading={isBuilding}
            disabled={isBuilding || !hasChanges}
          >
            {isBuilding
              ? "Submitting Changes..."
              : hasChanges
                ? `Submit Changes`
                : "No Changes"}
          </Button>

          {hasChanges && (
            <p className="text-xs text-dark-400 text-center">
              {[
                entitiesChanged && "trusted entities",
                pauseChanged && "transfer status",
                mintableChanged && "mintable amount",
                securityChanged && "security info",
              ].filter(Boolean).join(", ")}{" "}
              will be updated. Each change requires a separate on-chain transaction.
            </p>
          )}
        </>
      )}

      {/* No token selected placeholder */}
      {!selectedToken && !isLoadingState && (
        <div className="flex flex-col items-center py-8 text-center">
          <Settings className="h-10 w-10 text-dark-600 mb-3" />
          <p className="text-sm text-dark-400">Select a KYC token above to view and manage its global state.</p>
        </div>
      )}
    </form>
  );
}

// ── Security-token global state panel ───────────────────────────────────────
//
// Each BaFin GlobalStateSpendAction is one redeemer variant, and the
// global_state_spend_validator enforces the action's effect on the continuing
// datum. That means EACH datum field change must be a separate transaction
// signed by the admin wallet. v1 ships UpdateMemberRootHash; PauseTransfers /
// SetRequiresReceiverKyc / ModifySecurityInfo / RotateAdmin / trusted-entity
// changes all follow the same pattern (one backend endpoint that builds the
// one-action tx, the same signing flow here).

function SecurityTokenGlobalStatePanel({
  policyId,
  adminAddress,
  signingEntityVkey,
}: { policyId: string; adminAddress: string; signingEntityVkey: string | null }) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();

  // On-chain (source of truth on load + after Refresh / save)
  const [onchain, setOnchain] = useState<{
    transfersPaused: boolean;
    mintableAmount: number;
    requiresReceiverKyc: boolean;
    securityInfoHex: string | null;
    memberRootHash: string | null;
    memberRootHashLocal: string | null;
    trustedEntityVkeys: string[];
  } | null>(null);

  // Editable mirror — initialised from on-chain, modified by user
  const [transfersPaused, setTransfersPaused] = useState(false);
  const [requiresReceiverKyc, setRequiresReceiverKyc] = useState(false);
  const [securityInfo, setSecurityInfo] = useState("");
  const [trustedEntities, setTrustedEntities] = useState<string[]>([]);
  const [newEntityInput, setNewEntityInput] = useState("");
  const [republishRoot, setRepublishRoot] = useState(false);

  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submittedHashes, setSubmittedHashes] = useState<string[]>([]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const gs = await getSecurityTokenGlobalState(policyId);
      const next = {
        transfersPaused: gs.transfersPaused,
        mintableAmount: gs.mintableAmount,
        requiresReceiverKyc: gs.requiresReceiverKyc,
        securityInfoHex: gs.securityInfoHex ?? null,
        memberRootHash: gs.memberRootHash ?? null,
        memberRootHashLocal: gs.memberRootHashLocal ?? null,
        trustedEntityVkeys: gs.trustedEntityVkeys ?? [],
      };
      setOnchain(next);
      // Reset editable mirror to chain values
      setTransfersPaused(next.transfersPaused);
      setRequiresReceiverKyc(next.requiresReceiverKyc);
      setSecurityInfo(next.securityInfoHex ?? "");
      setTrustedEntities([...next.trustedEntityVkeys]);
      // Default the re-publish checkbox to TRUE when the on-chain root is stale,
      // so the admin doesn't have to remember to tick it.
      setRepublishRoot(
        next.memberRootHashLocal !== null
          && next.memberRootHash !== null
          && next.memberRootHashLocal.toLowerCase() !== next.memberRootHash.toLowerCase()
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [policyId]);

  useEffect(() => { refresh(); }, [refresh]);

  // Detect changes
  const trustedAdded = onchain
    ? trustedEntities.filter((v) => !onchain.trustedEntityVkeys.includes(v))
    : [];
  const trustedRemoved = onchain
    ? onchain.trustedEntityVkeys.filter((v) => !trustedEntities.includes(v))
    : [];
  const pauseChanged = onchain && transfersPaused !== onchain.transfersPaused;
  const requiresChanged = onchain && requiresReceiverKyc !== onchain.requiresReceiverKyc;
  const securityChanged = onchain && securityInfo !== (onchain.securityInfoHex ?? "");
  const hasChanges = pauseChanged || requiresChanged || securityChanged
    || trustedAdded.length > 0 || trustedRemoved.length > 0 || republishRoot;

  const buildChangeList = (): GsChangeSpec[] => {
    const changes: GsChangeSpec[] = [];
    if (pauseChanged) changes.push({ action: "PauseTransfers", transfersPaused });
    if (securityChanged) {
      // We treat the input as raw hex of a CBOR Data value. If the user typed
      // plain text, we wrap it as CBOR bytes (major type 2). For most BaFin
      // setups the field is opaque, so either is fine — backend just relays.
      changes.push({
        action: "ModifySecurityInfo",
        newSecurityInfoHex: isHex(securityInfo)
          ? securityInfo
          : textToCborBytesHex(securityInfo),
      });
    }
    for (const v of trustedRemoved) {
      changes.push({ action: "RemoveTrustedEntity", trustedVkeyHex: v });
    }
    for (const v of trustedAdded) {
      // Backend takes metadataHex as Data CBOR — pass an empty Map (a0).
      changes.push({
        action: "AddTrustedEntity",
        trustedVkeyHex: v,
        trustedMetadataHex: "a0",
      });
    }
    if (requiresChanged) {
      changes.push({
        action: "SetRequiresReceiverKyc",
        requiresReceiverKycEnabled: requiresReceiverKyc,
      });
    }
    if (republishRoot) {
      // Backend pulls the current local MPF root when newMemberRootHashHex omitted.
      changes.push({ action: "UpdateMemberRootHash" });
    }
    return changes;
  };

  const handleSave = async () => {
    if (!wallet) { setError("Connect a wallet first"); return; }
    const changes = buildChangeList();
    if (changes.length === 0) return;
    setBusy(true);
    setError(null);
    setSubmittedHashes([]);
    try {
      const { unsignedCborTxs } = await buildGlobalStateUpdateChain(
        policyId, adminAddress, changes);
      // Sign all N at once via CIP-103 batch signing (wallet wrapper falls
      // back to sequential signTx if the wallet doesn't support cip103).
      const signedCbors = await wallet.signTxs(unsignedCborTxs, true);
      const submit = await submitTokenChain(signedCbors);
      if (submit.error) throw new Error(submit.error);
      setSubmittedHashes(submit.txHashes);
      showToast({
        title: "Global state updated",
        description: `${submit.txHashes.length} transaction${submit.txHashes.length !== 1 ? "s" : ""} submitted`,
        variant: "success",
      });
      // If we just published a new member root, notify the backend so it can
      // update memberRootHashOnchain / lastRootUpdateTxHash / lastRootUpdateAt
      // and mark current leaves as published. The autonomous sync job used to
      // do this after submit+confirm; now it's user-driven so we have to ack.
      const rootIdx = changes.findIndex((c) => c.action === "UpdateMemberRootHash");
      if (rootIdx >= 0 && submit.txHashes[rootIdx]) {
        try {
          const newRoot = changes[rootIdx].newMemberRootHashHex
            ?? onchain?.memberRootHashLocal
            ?? "";
          if (newRoot) {
            await acknowledgeRootPublish(policyId, {
              txHash: submit.txHashes[rootIdx],
              newRootHashHex: newRoot,
            });
          }
        } catch (ackErr) {
          // Non-fatal: the chain has the new root regardless; the DB just
          // shows stale "needs publish" until the next refresh fixes it.
          console.warn("root-publish ack failed:", ackErr);
        }
      }
      // Optimistic refresh after a short delay (chain propagation)
      setTimeout(() => { refresh(); }, 10_000);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      setError(msg);
      showToast({ title: "Save failed", description: msg, variant: "error" });
    } finally {
      setBusy(false);
    }
  };

  if (loading || !onchain) {
    return (
      <div className="flex items-center justify-center gap-3 py-8">
        <Loader2 className="h-5 w-5 text-primary-400 animate-spin" />
        <span className="text-sm text-dark-300">Loading on-chain state…</span>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium text-white">On-chain state</span>
        <button
          type="button"
          onClick={refresh}
          disabled={busy}
          className="flex items-center gap-1.5 text-xs text-dark-400 hover:text-primary-400 transition-colors"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
      </div>

      {/* Pause toggle */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Transfer status</label>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setTransfersPaused(true)}
            disabled={busy}
            className={cn("flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
              transfersPaused ? "bg-red-500/10 border-red-500 text-red-400" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >
            <PauseCircle className="h-4 w-4" /> Paused
          </button>
          <button
            type="button"
            onClick={() => setTransfersPaused(false)}
            disabled={busy}
            className={cn("flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
              !transfersPaused ? "bg-green-500/10 border-green-500 text-green-400" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >
            <PlayCircle className="h-4 w-4" /> Active
          </button>
        </div>
        {pauseChanged && <p className="mt-1 text-xs text-amber-400">Will submit: PauseTransfers</p>}
      </div>

      {/* requires_receiver_kyc */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Requires receiver KYC</label>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setRequiresReceiverKyc(true)}
            disabled={busy}
            className={cn("flex-1 px-4 py-2 rounded-lg border text-sm transition-colors",
              requiresReceiverKyc ? "bg-primary-500/10 border-primary-500 text-primary-300" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >Enabled</button>
          <button
            type="button"
            onClick={() => setRequiresReceiverKyc(false)}
            disabled={busy}
            className={cn("flex-1 px-4 py-2 rounded-lg border text-sm transition-colors",
              !requiresReceiverKyc ? "bg-primary-500/10 border-primary-500 text-primary-300" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >Disabled</button>
        </div>
        {requiresChanged && <p className="mt-1 text-xs text-amber-400">Will submit: SetRequiresReceiverKyc</p>}
      </div>

      {/* mintable_amount (read-only — there's no direct update action) */}
      <ReadOnlyField label="mintable_amount (no direct update — decremented by MintSecurity)"
                     value={String(onchain.mintableAmount)} />

      {/* security_info */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Security info</label>
        <Input
          value={securityInfo}
          onChange={(e) => setSecurityInfo(e.target.value)}
          placeholder="hex (CBOR Data) or plain text"
          disabled={busy}
        />
        {securityChanged && <p className="mt-1 text-xs text-amber-400">Will submit: ModifySecurityInfo</p>}
      </div>

      {/* Trusted entities (KYC issuers) — fetched from on-chain GS datum's
          trusted_entity_vkeys field. These vkeys are the only signers whose
          KYC attestations are accepted by transfer_logic_script.withdraw. */}
      <div className="pt-3 border-t border-dark-700">
        <div className="flex items-center justify-between mb-2">
          <label className="block text-sm font-medium text-white">
            Trusted entities (KYC proof issuers)
          </label>
          <span className="text-[10px] text-dark-500">
            on-chain: {onchain.trustedEntityVkeys.length}
          </span>
        </div>
        <p className="text-xs text-dark-400 mb-2">
          Ed25519 vkeys whose KYC attestations transfer_logic_script will accept.
          {signingEntityVkey && !onchain.trustedEntityVkeys.includes(signingEntityVkey) && (
            <span className="text-amber-400 ml-1">
              ⚠ Your backend&apos;s signing entity isn&apos;t in this list yet —
              add it so KYC proofs issued here verify on chain.
            </span>
          )}
        </p>
        <div className="space-y-1">
          {trustedEntities.length === 0 && (
            <p className="text-xs text-dark-500 italic">
              No trusted entities on chain yet. Add one below (or use the
              &quot;+ Add backend signing entity&quot; quick-add).
            </p>
          )}
          {trustedEntities.map((v) => {
            const isNew = !onchain.trustedEntityVkeys.includes(v);
            return (
              <div key={v} className="flex items-center justify-between gap-2 p-2 bg-dark-900 rounded">
                <p className={cn("text-xs font-mono break-all", isNew ? "text-amber-300" : "text-dark-300")}>{v}</p>
                <button
                  type="button"
                  onClick={() => setTrustedEntities((cur) => cur.filter((x) => x !== v))}
                  disabled={busy}
                  className="text-dark-400 hover:text-red-400"
                  title="Remove"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            );
          })}
        </div>
        <div className="flex gap-2 mt-2">
          <Input
            value={newEntityInput}
            onChange={(e) => setNewEntityInput(e.target.value)}
            placeholder="vkey hex (32 bytes)"
            disabled={busy}
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => {
              const v = newEntityInput.trim().toLowerCase();
              if (!v) return;
              if (!/^[0-9a-f]{64}$/.test(v)) {
                setError("trusted vkey must be 32 bytes hex (64 chars)");
                return;
              }
              if (trustedEntities.includes(v)) return;
              setTrustedEntities((cur) => [...cur, v]);
              setNewEntityInput("");
            }}
            disabled={busy}
          >
            <Plus className="h-4 w-4 mr-1" /> Add
          </Button>
        </div>
        {/* Quick-add the backend's KERI signing-entity vkey — it's the issuer
            of KYC proofs this backend produces, so it MUST be in the trusted
            entity list for any KYC proof generated locally to verify on chain. */}
        {signingEntityVkey && !trustedEntities.includes(signingEntityVkey) && (
          <Button
            type="button"
            variant="outline"
            className="text-xs h-7 px-3 mt-2"
            onClick={() => setTrustedEntities((cur) => [...cur, signingEntityVkey])}
            disabled={busy}
            title={"Add this backend's KERI signing-entity vkey: " + signingEntityVkey}
          >
            + Add backend signing entity
          </Button>
        )}
        {(trustedAdded.length > 0 || trustedRemoved.length > 0) && (
          <p className="mt-1 text-xs text-amber-400">
            Will submit: {trustedRemoved.length} RemoveTrustedEntity + {trustedAdded.length} AddTrustedEntity
          </p>
        )}
      </div>

      {/* member_root_hash — show on-chain vs local with a divergence indicator */}
      {(() => {
        const local = onchain.memberRootHashLocal;
        const chain = onchain.memberRootHash;
        const diverged =
          local !== null && chain !== null
            && local.toLowerCase() !== chain.toLowerCase();
        return (
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="block text-sm font-medium text-white">Member root hash</label>
              {diverged ? (
                <span className="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider text-amber-300 bg-amber-500/10 border border-amber-500/30 rounded px-2 py-0.5">
                  <RefreshCw className="h-3 w-3" /> needs publish
                </span>
              ) : (
                <span className="inline-flex items-center gap-1 text-[10px] uppercase tracking-wider text-success-300 bg-success-500/10 border border-success-500/30 rounded px-2 py-0.5">
                  in sync
                </span>
              )}
            </div>
            <div className="space-y-2">
              <div className="p-2 bg-dark-900 rounded border border-dark-700">
                <p className="text-[10px] uppercase tracking-wider text-dark-500">On-chain</p>
                <p className="text-xs font-mono text-dark-200 mt-0.5 break-all">{chain ?? "—"}</p>
              </div>
              <div className={cn(
                "p-2 rounded border",
                diverged ? "bg-amber-500/5 border-amber-500/40" : "bg-dark-900 border-dark-700"
              )}>
                <p className="text-[10px] uppercase tracking-wider text-dark-500">
                  Local (computed from {trustedEntities.length === 0 ? "0" : "current"} enrolled members)
                </p>
                <p className={cn(
                  "text-xs font-mono mt-0.5 break-all",
                  diverged ? "text-amber-200" : "text-dark-200"
                )}>{local ?? "—"}</p>
              </div>
            </div>
            <label className="inline-flex items-center gap-2 mt-3 text-xs text-dark-300">
              <input
                type="checkbox"
                checked={republishRoot}
                onChange={(e) => setRepublishRoot(e.target.checked)}
                disabled={busy || !diverged}
              />
              {diverged
                ? "Publish the new local root in this batch (recommended)"
                : "Re-publish current local root in this batch"}
            </label>
            {republishRoot && <p className="mt-1 text-xs text-amber-400">Will submit: UpdateMemberRootHash</p>}
          </div>
        );
      })()}

      {/* Save */}
      <div className="pt-3 border-t border-dark-700 space-y-2">
        <Button type="button" variant="primary" onClick={handleSave} disabled={busy || !hasChanges}>
          {busy ? "Building + signing…" : hasChanges ? "Save & submit chain" : "No changes"}
        </Button>
        <p className="text-xs text-dark-400">
          Each change = one mempool-chained admin tx. Your wallet will be asked to
          sign N transactions in a single popup (CIP-103) where supported.
        </p>
        {submittedHashes.length > 0 && (
          <div className="space-y-1 pt-2">
            <p className="text-xs text-green-400">Submitted {submittedHashes.length} tx{submittedHashes.length !== 1 ? "es" : ""}:</p>
            {submittedHashes.map((h, i) => (
              <p key={h} className="text-xs font-mono text-green-300 break-all">#{i + 1}: {h}</p>
            ))}
          </div>
        )}
        {error && <p className="text-xs text-red-400">{error}</p>}
      </div>
    </div>
  );
}

function isHex(s: string): boolean {
  return /^[0-9a-fA-F]*$/.test(s) && s.length % 2 === 0;
}

function textToCborBytesHex(s: string): string {
  const bytes = new TextEncoder().encode(s);
  // CBOR major type 2 (byte string). Use 1-byte length if < 24, else 1-/2-/4-byte lengths.
  let header: number[];
  if (bytes.length < 24) header = [0x40 | bytes.length];
  else if (bytes.length < 256) header = [0x58, bytes.length];
  else if (bytes.length < 65536) header = [0x59, (bytes.length >> 8) & 0xff, bytes.length & 0xff];
  else header = [0x5a,
    (bytes.length >>> 24) & 0xff, (bytes.length >>> 16) & 0xff,
    (bytes.length >>> 8) & 0xff, bytes.length & 0xff];
  const all = new Uint8Array(header.length + bytes.length);
  all.set(header, 0);
  all.set(bytes, header.length);
  return Array.from(all).map((b) => b.toString(16).padStart(2, "0")).join("");
}

function ReadOnlyField({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="p-2 bg-dark-900 rounded border border-dark-700">
      <p className="text-[10px] uppercase tracking-wider text-dark-500">{label}</p>
      <p className={cn("text-xs text-dark-200 mt-0.5 break-all", mono && "font-mono")}>
        {value}
      </p>
    </div>
  );
}
