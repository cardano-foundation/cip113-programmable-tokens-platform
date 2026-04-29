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
import { AdminTokenInfo } from "@/lib/api/admin";
import { getSigningEntityVkey } from "@/lib/api/keri";
import { readGlobalState, updateGlobalState } from "@/lib/api/compliance";
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

  const manageableTokens = tokens.filter(
    (t) => t.roles.includes("ISSUER_ADMIN") && t.substandardId === "kyc"
  );

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
    if (selectedToken) {
      loadGlobalState(selectedToken.policyId);
    } else {
      setGlobalState(null);
    }
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
      showToast({ title: "Global State Updated", description: `${changes.length} change(s) submitted`, variant: "success" });
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
        <h3 className="text-lg font-semibold text-white mb-2">No KYC Token Management Access</h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t have issuer admin permissions for any KYC tokens.
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
