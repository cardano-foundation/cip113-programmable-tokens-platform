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
  AlertTriangle,
  Pencil,
  KeyRound,
  Copy,
  Skull,
  X,
} from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import {
  AdminTokenInfo,
  RwaTokenCapability,
  hasRwaTokenCapability,
} from "@/lib/api/admin";
import { getSigningEntityVkey } from "@/lib/api/keri";
import { readGlobalState, updateGlobalState } from "@/lib/api/compliance";
import {
  buildGlobalStateUpdateChain,
  submitTokenChain,
  parseSubmitChainFailure,
  getRwaTokenGlobalState,
  acknowledgeRootPublish,
  type GsChangeSpec,
  type SubmitChainPartialFailure,
} from "@/lib/api/rwa-token";
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
  // rwa-token: BaFin's GS spend validator is admin-gated via the
  //   admin_credential_hash field of the datum — anyone with the ADMIN
  //   capability bit in the on-chain power-users LL counts as such.
  const manageableTokens = tokens.filter((t) => {
    if (t.substandardId === "rwa-token") {
      return hasRwaTokenCapability(t, RwaTokenCapability.ADMIN);
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
    // rwa-token has its own GS endpoint at /api/v1/rwa-token/.../global-state
    // and renders RwaTokenGlobalStatePanel below; the kyc compliance endpoint
    // would 404 for rwa-token policies, so skip the load here.
    if (selectedToken.substandardId === "rwa-token") {
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

  // Branch render: rwa-tokens use the BaFin GS validator which has a
  // different datum shape, different actions, and admin gating via
  // admin_credential_hash (not a power-user role). Render its own panel.
  if (selectedToken?.substandardId === "rwa-token") {
    return (
      <div className="space-y-6">
        <AdminTokenSelector
          tokens={manageableTokens}
          selectedToken={selectedToken}
          onSelect={setSelectedToken}
          disabled={false}
          filterByRole="ISSUER_ADMIN"
        />
        <RwaTokenGlobalStatePanel
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
          <p className="text-sm text-dark-400">Select a token above to view and manage its global state.</p>
        </div>
      )}
    </form>
  );
}

// ── RWA-token global state panel ───────────────────────────────────────
//
// Each BaFin GlobalStateSpendAction is one redeemer variant, and the
// global_state_spend_validator enforces the action's effect on the continuing
// datum. That means EACH datum field change must be a separate transaction
// signed by the admin wallet. v1 ships UpdateMemberRootHash; PauseTransfers /
// SetRequiresReceiverKyc / ModifySecurityInfo / RotateAdmin / trusted-entity
// changes all follow the same pattern (one backend endpoint that builds the
// one-action tx, the same signing flow here).

function RwaTokenGlobalStatePanel({
  policyId,
  adminAddress,
  signingEntityVkey,
}: { policyId: string; adminAddress: string; signingEntityVkey: string | null }) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();

  // On-chain (source of truth on load + after Refresh / save)
  const [onchain, setOnchain] = useState<{
    transfersPaused: boolean;
    deactivated: boolean;
    mintableAmount: number;
    requiresReceiverKyc: boolean;
    requiresSenderKyc: boolean;
    securityInfoHex: string | null;
    memberRootHash: string | null;
    memberRootHashLocal: string | null;
    trustedEntityVkeys: string[];
    adminCredentialHash: string | null;
    /** Which minting authority the permanent proxy currently delegates to. */
    mintingScriptCredentialHash: string | null;
    /** One-way: once locked, neither the authority nor the transfer logic can change. */
    upgradesLocked: boolean;
  } | null>(null);

  // Editable mirror — initialised from on-chain, modified by user
  const [transfersPaused, setTransfersPaused] = useState(false);
  const [requiresReceiverKyc, setRequiresReceiverKyc] = useState(false);
  const [requiresSenderKyc, setRequiresSenderKyc] = useState(false);
  const [securityInfo, setSecurityInfo] = useState("");
  const [trustedEntities, setTrustedEntities] = useState<string[]>([]);
  const [newEntityInput, setNewEntityInput] = useState("");
  const [republishRoot, setRepublishRoot] = useState(false);
  // D5 — staged UpdateTrustedEntity operations, keyed by the vkey being replaced.
  // Kept separate from the add/remove diff so a rename is ONE transaction
  // (Remove + Add is two, and leaves the entity briefly absent on chain).
  const [trustedUpdates, setTrustedUpdates] = useState<TrustedEntityUpdate[]>([]);
  const [editingEntity, setEditingEntity] = useState<TrustedEntityUpdate | null>(null);

  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [submittedHashes, setSubmittedHashes] = useState<string[]>([]);
  // D9 — structured partial-failure result, so the hashes that DID land survive.
  const [partialFailure, setPartialFailure] = useState<
    (SubmitChainPartialFailure & { labels: string[] }) | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const gs = await getRwaTokenGlobalState(policyId);
      const next = {
        transfersPaused: gs.transfersPaused,
        deactivated: gs.deactivated ?? false,
        mintableAmount: gs.mintableAmount,
        requiresReceiverKyc: gs.requiresReceiverKyc,
        requiresSenderKyc: gs.requiresSenderKyc ?? false,
        securityInfoHex: gs.securityInfoHex ?? null,
        memberRootHash: gs.memberRootHash ?? null,
        memberRootHashLocal: gs.memberRootHashLocal ?? null,
        mintingScriptCredentialHash: gs.mintingScriptCredentialHash ?? null,
        upgradesLocked: gs.upgradesLocked ?? false,
        trustedEntityVkeys: gs.trustedEntityVkeys ?? [],
        adminCredentialHash: gs.adminCredentialHash ?? null,
      };
      setOnchain(next);
      // Reset editable mirror to chain values
      setTransfersPaused(next.transfersPaused);
      setRequiresReceiverKyc(next.requiresReceiverKyc);
      setRequiresSenderKyc(next.requiresSenderKyc);
      setSecurityInfo(next.securityInfoHex ?? "");
      setTrustedEntities([...next.trustedEntityVkeys]);
      setTrustedUpdates([]);
      setEditingEntity(null);
      // Default the re-publish checkbox to TRUE when the on-chain root is stale,
      // so the admin doesn't have to remember to tick it.
      setRepublishRoot(
        next.memberRootHashLocal !== null
          && next.memberRootHash !== null
          && next.memberRootHashLocal.toLowerCase() !== next.memberRootHash.toLowerCase()
      );
    } catch (e) {
      // Drop whatever was on screen. Leaving the previous token's datum in place
      // would render ITS pause flag, supply and trusted entities under the newly
      // selected policy id — and every action built from this panel is keyed on
      // `policyId`, so acting on those stale values would sign the wrong change.
      setOnchain(null);
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [policyId]);

  useEffect(() => { refresh(); }, [refresh]);

  // Detect changes. Keys taking part in a staged UpdateTrustedEntity are excluded
  // from the add/remove diff — the old key leaves the displayed list and the new
  // one joins it, which would otherwise read as a removal plus an addition.
  const updateOldKeys = new Set(trustedUpdates.map((u) => u.oldVkey));
  const updateNewKeys = new Set(trustedUpdates.map((u) => u.newVkey));
  const trustedAdded = onchain
    ? trustedEntities.filter((v) => !onchain.trustedEntityVkeys.includes(v) && !updateNewKeys.has(v))
    : [];
  const trustedRemoved = onchain
    ? onchain.trustedEntityVkeys.filter((v) => !trustedEntities.includes(v) && !updateOldKeys.has(v))
    : [];
  const pauseChanged = onchain && transfersPaused !== onchain.transfersPaused;
  const requiresChanged = onchain && requiresReceiverKyc !== onchain.requiresReceiverKyc;
  const senderKycChanged = onchain && requiresSenderKyc !== onchain.requiresSenderKyc;
  const securityChanged = onchain && securityInfo !== (onchain.securityInfoHex ?? "");
  // D7 — the field is opaque `Data` on chain, so anything well-formed is allowed,
  // but "valid hex" is NOT the same as "valid CBOR Data". `deadbeef` is perfectly
  // good hex and not a CBOR value at all; forwarding it used to fail deep in the
  // backend's build with a raw parser message. Catch it here instead.
  //
  // The empty string is its own case: it is "valid hex" by the regex, so it used to
  // sail through and reach the backend as newSecurityInfoHex: "", which fails the
  // whole chain build with a bare "is required" and no inline signal. There is no
  // "unset" for this field — it is Data, so clearing it means choosing an empty
  // VALUE.
  const securityInfoError = !securityChanged
    ? null
    : securityInfo.length === 0
      ? "cannot be empty — security_info is an on-chain Data value, so there is no "
        + "\"unset\". Use d87980 for Constr 0 [] (the genesis default), a0 for an empty "
        + "map, or 40 for empty bytes."
      : isHex(securityInfo)
        ? cborDataError(securityInfo)
        : null;
  const deactivated = onchain?.deactivated ?? false;
  const hasChanges = pauseChanged || requiresChanged || senderKycChanged || securityChanged
    || trustedAdded.length > 0 || trustedRemoved.length > 0 || trustedUpdates.length > 0
    || republishRoot;

  /** Each staged change as (spec, human label). The labels are kept alongside so a
   *  partial submit failure can name WHICH change failed and which ones landed —
   *  the chain is index-aligned with what the backend submits. */
  const buildChangeList = (): Array<{ spec: GsChangeSpec; label: string }> => {
    const changes: Array<{ spec: GsChangeSpec; label: string }> = [];
    if (pauseChanged) {
      changes.push({
        spec: { action: "PauseTransfers", transfersPaused },
        label: transfersPaused ? "Pause transfers" : "Resume transfers",
      });
    }
    if (securityChanged) {
      // We treat the input as raw hex of a CBOR Data value. If the user typed
      // plain text, we wrap it as CBOR bytes (major type 2). For most BaFin
      // setups the field is opaque, so either is fine — backend just relays.
      changes.push({
        spec: {
          action: "ModifySecurityInfo",
          newSecurityInfoHex: isHex(securityInfo)
            ? securityInfo
            : textToCborBytesHex(securityInfo),
        },
        label: "Update security info",
      });
    }
    for (const v of trustedRemoved) {
      changes.push({
        spec: { action: "RemoveTrustedEntity", trustedVkeyHex: v },
        label: `Remove trusted entity ${shortHex(v)}`,
      });
    }
    // D5 — rename and/or re-key a trusted entity in a single transaction. The
    // on-chain branch also accepts old == new as a metadata-only edit.
    for (const u of trustedUpdates) {
      changes.push({
        spec: {
          action: "UpdateTrustedEntity",
          trustedOldVkeyHex: u.oldVkey,
          trustedNewVkeyHex: u.newVkey,
          trustedNewMetadataHex: u.metadataHex,
        },
        label: u.oldVkey === u.newVkey
          ? `Update metadata for ${shortHex(u.oldVkey)}`
          : `Replace trusted entity ${shortHex(u.oldVkey)} with ${shortHex(u.newVkey)}`,
      });
    }
    for (const v of trustedAdded) {
      // Backend takes metadataHex as Data CBOR — pass an empty Map (a0).
      changes.push({
        spec: { action: "AddTrustedEntity", trustedVkeyHex: v, trustedMetadataHex: "a0" },
        label: `Add trusted entity ${shortHex(v)}`,
      });
    }
    // D4 — previously unreachable: GsChangeSpec had no requiresSenderKycEnabled
    // field, so the backend's `SetRequiresSenderKyc requires requiresSenderKycEnabled`
    // check rejected every attempt.
    if (senderKycChanged) {
      changes.push({
        spec: { action: "SetRequiresSenderKyc", requiresSenderKycEnabled: requiresSenderKyc },
        label: `Set requires-sender-KYC ${requiresSenderKyc ? "on" : "off"}`,
      });
    }
    if (requiresChanged) {
      changes.push({
        spec: {
          action: "SetRequiresReceiverKyc",
          requiresReceiverKycEnabled: requiresReceiverKyc,
        },
        label: `Set requires-receiver-KYC ${requiresReceiverKyc ? "on" : "off"}`,
      });
    }
    if (republishRoot) {
      // Backend pulls the current local MPF root when newMemberRootHashHex omitted.
      changes.push({ spec: { action: "UpdateMemberRootHash" }, label: "Publish member root" });
    }
    return changes;
  };

  /** Build → sign → submit a list of changes as one mempool chain.
   *
   *  Shared by the ordinary Save button and the decommission flow, because both
   *  need identical partial-failure handling: the backend submits sequentially and
   *  stops at the first rejection, so changes before that index ARE on chain. */
  const runChain = async (
    changes: Array<{ spec: GsChangeSpec; label: string }>,
    successTitle: string,
  ): Promise<boolean> => {
    if (!wallet) { setError("Connect a wallet first"); return false; }
    if (changes.length === 0) return false;
    setBusy(true);
    setError(null);
    setSubmittedHashes([]);
    setPartialFailure(null);
    const labels = changes.map((c) => c.label);
    try {
      const { unsignedCborTxs } = await buildGlobalStateUpdateChain(
        policyId, adminAddress, changes.map((c) => c.spec));
      // Sign all N at once via CIP-103 batch signing (wallet wrapper falls
      // back to sequential signTx if the wallet doesn't support cip103).
      const signedCbors = await wallet.signTxs(unsignedCborTxs, true);
      const submit = await submitTokenChain(signedCbors);
      // Defensive: the backend signals partial failure with HTTP 400, which
      // apiPost turns into a throw, so this branch is not the primary path —
      // but a 200 carrying an error field would otherwise pass silently.
      if (submit.error) throw new Error(submit.error);
      setSubmittedHashes(submit.txHashes);
      showToast({
        title: successTitle,
        description: `${submit.txHashes.length} transaction${submit.txHashes.length !== 1 ? "s" : ""} submitted`,
        variant: "success",
      });
      await ackRootPublishIfNeeded(changes.map((c) => c.spec), submit.txHashes);
      setTimeout(() => { refresh(); }, 10_000);
      return true;
    } catch (e) {
      // D9 — recover the structured body. Losing the hashes of the transactions
      // that DID land makes a half-applied chain very hard to reconstruct: the GS
      // UTxO has moved, so the remaining changes have to be rebuilt against the
      // new state, and without the hashes there is no record of what happened.
      const failure = parseSubmitChainFailure(e);
      if (failure.txHashes.length > 0 || failure.failedIndex !== undefined) {
        setPartialFailure({ ...failure, labels });
        setSubmittedHashes(failure.txHashes);
        // Anything that landed before the failure is real on-chain state, so a
        // member-root publish among them still needs acknowledging.
        await ackRootPublishIfNeeded(changes.map((c) => c.spec), failure.txHashes);
        showToast({
          title: failure.txHashes.length > 0 ? "Chain partially applied" : "Submit failed",
          description: failure.txHashes.length > 0
            ? `${failure.txHashes.length} of ${changes.length} change(s) landed on chain before the failure.`
            : failure.error,
          variant: "error",
        });
        setTimeout(() => { refresh(); }, 10_000);
      } else {
        setError(failure.error);
        showToast({ title: "Save failed", description: failure.error, variant: "error" });
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  /** If a member-root publish is among the changes that actually landed, tell the
   *  backend so it can update memberRootHashOnchain / lastRootUpdateTxHash and
   *  mark the current leaves as published. The autonomous sync job used to do this
   *  after submit+confirm; with user-driven publishing the frontend has to ack. */
  const ackRootPublishIfNeeded = async (specs: GsChangeSpec[], txHashes: string[]) => {
    const rootIdx = specs.findIndex((c) => c.action === "UpdateMemberRootHash");
    if (rootIdx < 0 || !txHashes[rootIdx]) return;
    try {
      const newRoot = specs[rootIdx].newMemberRootHashHex
        ?? onchain?.memberRootHashLocal
        ?? "";
      if (newRoot) {
        await acknowledgeRootPublish(policyId, {
          txHash: txHashes[rootIdx],
          newRootHashHex: newRoot,
        });
      }
    } catch (ackErr) {
      // Non-fatal: the chain has the new root regardless; the DB just
      // shows stale "needs publish" until the next refresh fixes it.
      console.warn("root-publish ack failed:", ackErr);
    }
  };

  const handleSave = async () => {
    if (securityInfoError) {
      setError(`Security info: ${securityInfoError}`);
      return;
    }
    await runChain(buildChangeList(), "Global state updated");
  };

  // ── D3: decommission (DeactivateContract) ─────────────────────────────────
  // Deliberately NOT part of the ordinary Save batch. It is terminal — the
  // on-chain validator's `expect !input_datum.deactivated` runs before branch
  // dispatch, so afterwards NOTHING can spend the global state again: no unpause,
  // no mint, no burn, no second deactivation. It gets its own control, its own
  // confirmation, and its own submit.
  const [showDecommission, setShowDecommission] = useState(false);
  const [decommissionConfirm, setDecommissionConfirm] = useState("");
  const DECOMMISSION_PHRASE = "DECOMMISSION";

  const handleDecommission = async () => {
    if (decommissionConfirm.trim() !== DECOMMISSION_PHRASE) return;
    const changes: Array<{ spec: GsChangeSpec; label: string }> = [];
    // The contract requires `input_datum.transfers_paused` — deactivating an
    // unpaused token is rejected on chain. Chain the pause in front rather than
    // making the operator discover the precondition from a failed transaction;
    // each tx in the chain spends the previous one's global state, so the
    // DeactivateContract step sees transfers_paused = True.
    if (!onchain?.transfersPaused) {
      changes.push({
        spec: { action: "PauseTransfers", transfersPaused: true },
        label: "Pause transfers (required before decommissioning)",
      });
    }
    changes.push({
      spec: { action: "DeactivateContract" },
      label: "Decommission the contract (irreversible)",
    });
    const ok = await runChain(changes, "Contract decommissioned");
    if (ok) {
      setShowDecommission(false);
      setDecommissionConfirm("");
    }
  };

  // ── Minting authority: rotate, and lock ───────────────────────────────────
  // The minting logic is a permanent PROXY plus a swappable AUTHORITY. The proxy's
  // hash is frozen into the CIP-113 registry node at insert and can never change —
  // it IS the token's identity — so the only way to change the mint rules is to
  // point `minting_script_credential_hash` at a different authority. That is what
  // this control does, and it is why the token's policy id survives an upgrade.
  //
  // Single-signature by design, unlike RotateAdmin: the outgoing authority does not
  // co-sign, because an authority being replaced for being broken or compromised is
  // exactly the case this has to keep working for.
  const [showRotateMinting, setShowRotateMinting] = useState(false);
  const [newMintingScript, setNewMintingScript] = useState("");
  const MINTING_HASH_RE = /^[0-9a-f]{56}$/;
  const newMintingScriptValid = MINTING_HASH_RE.test(newMintingScript.trim().toLowerCase());

  const handleRotateMintingScript = async () => {
    if (!newMintingScriptValid) return;
    const ok = await runChain(
      [{
        spec: {
          action: "RotateMintingScript",
          newMintingScriptCredentialHashHex: newMintingScript.trim().toLowerCase(),
        },
        label: "Rotate the minting authority",
      }],
      "Minting authority rotated",
    );
    if (ok) {
      setShowRotateMinting(false);
      setNewMintingScript("");
    }
  };

  // Irreversible, and broader than it looks: it freezes the minting-authority
  // rotation AND the CIP-113 registry-node upgrade path (transfer + third-party
  // logic) in one flag that no branch ever clears. Own control, own confirmation.
  const [showLockUpgrades, setShowLockUpgrades] = useState(false);
  const [lockConfirm, setLockConfirm] = useState("");
  const LOCK_PHRASE = "LOCK UPGRADES";

  const handleLockUpgrades = async () => {
    if (lockConfirm.trim() !== LOCK_PHRASE) return;
    const ok = await runChain(
      [{ spec: { action: "LockUpgrades" }, label: "Lock upgrades permanently (irreversible)" }],
      "Upgrades locked permanently",
    );
    if (ok) {
      setShowLockUpgrades(false);
      setLockConfirm("");
    }
  };

  // ── D2: RotateAdmin (dual-signature, two-step) ────────────────────────────
  // global_state.ak requires BOTH `must_be_signed_by_credential(input_datum
  // .admin_credential_hash)` AND `must_be_signed_by_credential(new_admin_
  // credential_hash)`. The backend already declares both in required_signers, so
  // this is a ledger-level requirement, not just a Plutus one — a single-wallet
  // signature is rejected at submit with MissingRequiredSigners.
  //
  // One browser session holds one wallet at a time, so rotation is an explicit
  // two-step: the outgoing admin partial-signs, the resulting CBOR is handed to
  // the incoming admin (in-app after connecting their wallet, or copied out of
  // band), and only the fully co-signed transaction is submitted.
  const [rotateNewAdmin, setRotateNewAdmin] = useState("");
  const [rotatePartialCbor, setRotatePartialCbor] = useState<string | null>(null);
  const [rotatePastedCbor, setRotatePastedCbor] = useState("");
  const [rotateBusy, setRotateBusy] = useState(false);
  const [rotateError, setRotateError] = useState<string | null>(null);
  const [rotateTxHash, setRotateTxHash] = useState<string | null>(null);

  const rotateNewAdminValid = /^[0-9a-f]{56}$/i.test(rotateNewAdmin.trim());

  const resetRotate = () => {
    setRotateNewAdmin("");
    setRotatePartialCbor(null);
    setRotatePastedCbor("");
    setRotateError(null);
    setRotateTxHash(null);
  };

  /** Step 1 — build the RotateAdmin tx and add the CURRENT admin's signature.
   *  `partialSign = true` matters: the transaction is knowingly incomplete at
   *  this point, and a wallet asked to fully sign would refuse. */
  const handleRotateBuildAndSign = async () => {
    if (!wallet) { setRotateError("Connect a wallet first"); return; }
    if (!rotateNewAdminValid) {
      setRotateError("New admin credential hash must be 28 bytes (56 hex characters)");
      return;
    }
    setRotateBusy(true);
    setRotateError(null);
    try {
      const { unsignedCborTxs } = await buildGlobalStateUpdateChain(policyId, adminAddress, [
        { action: "RotateAdmin", newAdminCredentialHashHex: rotateNewAdmin.trim().toLowerCase() },
      ]);
      const partial = await wallet.signTx(unsignedCborTxs[0], true);
      setRotatePartialCbor(partial);
      showToast({
        title: "Signed by the current admin",
        description: "Now collect the incoming admin's signature.",
        variant: "default",
      });
    } catch (e) {
      setRotateError(e instanceof Error ? e.message : String(e));
    } finally {
      setRotateBusy(false);
    }
  };

  /** Step 2a — the incoming admin counter-signs with the wallet connected now.
   *  Requires them to have switched wallets in the connector first; the merge in
   *  `assembleSignedTxPreservingBody` unions the two witness sets rather than
   *  replacing the first. */
  const handleRotateCounterSign = async () => {
    if (!wallet || !rotatePartialCbor) return;
    setRotateBusy(true);
    setRotateError(null);
    try {
      const cosigned = await wallet.signTx(rotatePartialCbor, true);
      setRotatePartialCbor(cosigned);
      showToast({
        title: "Counter-signed",
        description: "Both signatures collected — you can submit now.",
        variant: "success",
      });
    } catch (e) {
      setRotateError(e instanceof Error ? e.message : String(e));
    } finally {
      setRotateBusy(false);
    }
  };

  /** Step 3 — submit. Goes through the backend's submit endpoint (same path the
   *  change chain uses) rather than the wallet's, so the transaction is not
   *  bounced by a wallet backend that dislikes multi-witness admin txs. */
  const handleRotateSubmit = async (cborHex: string) => {
    setRotateBusy(true);
    setRotateError(null);
    try {
      const submit = await submitTokenChain([cborHex]);
      if (submit.error) throw new Error(submit.error);
      setRotateTxHash(submit.txHashes[0] ?? null);
      showToast({
        title: "Admin rotated",
        description: "The new admin credential is now on chain.",
        variant: "success",
      });
      setTimeout(() => { refresh(); }, 10_000);
    } catch (e) {
      const failure = parseSubmitChainFailure(e);
      setRotateError(
        failure.error.includes("MissingRequiredSigners")
          ? "Rejected: MissingRequiredSigners — the transaction is still missing one of "
            + "the two required signatures. Both the outgoing and the incoming admin "
            + "must sign before it can be submitted."
          : failure.error,
      );
    } finally {
      setRotateBusy(false);
    }
  };

  // A failed load has to SAY so. `refresh()` records the reason in `error` but
  // leaves `onchain` null, and the spinner below keys on `!onchain` — so without
  // this branch every failure rendered as an endless "Loading on-chain state…"
  // and the only rendering of `error` sits far below, unreachable. The common
  // cause is a registration whose global-state UTxO is not on the chain the
  // backend is pointed at (a token from an earlier devnet, or a genesis that
  // never landed): the backend answers 404 and the panel simply spun.
  if (error && !onchain) {
    const missingOnChain = /not found on chain/i.test(error);
    return (
      <div className="space-y-4">
        <div className="flex items-start gap-3 p-4 rounded-lg border border-red-500/40 bg-red-500/10">
          <AlertTriangle className="h-5 w-5 text-red-400 shrink-0 mt-0.5" />
          <div className="space-y-1">
            <p className="text-sm font-semibold text-red-300">
              {missingOnChain
                ? "No global state on chain for this token"
                : "Could not read the on-chain global state"}
            </p>
            <p className="text-xs text-red-200/80 break-words">{error}</p>
            {missingOnChain && (
              <p className="text-xs text-red-200/60">
                The registration exists in the backend database but its global-state UTxO is not
                on the network this backend is connected to. Tokens registered against a different
                network — or a devnet that has since been recreated — look exactly like this.
                Nothing here can be repaired from this panel; pick a token that was registered on
                the current network.
              </p>
            )}
            <p className="text-xs font-mono text-red-200/50 break-all pt-1">{policyId}</p>
          </div>
        </div>
        <Button variant="outline" size="sm" onClick={() => refresh()} disabled={loading}>
          {loading
            ? <Loader2 className="h-4 w-4 mr-2 animate-spin" />
            : <RefreshCw className="h-4 w-4 mr-2" />}
          Retry
        </Button>
      </div>
    );
  }

  if (loading || !onchain) {
    return (
      <div className="flex items-center justify-center gap-3 py-8">
        <Loader2 className="h-5 w-5 text-primary-400 animate-spin" />
        <span className="text-sm text-dark-300">Loading on-chain state…</span>
      </div>
    );
  }

  // Terminal state. Once `deactivated` is set the on-chain validator rejects every
  // spend of the global-state UTxO, so there is nothing left to edit — rendering
  // the usual controls would only offer actions that cannot succeed.
  if (deactivated) {
    return (
      <div className="space-y-4">
        <div className="flex items-start gap-3 p-4 rounded-lg border border-red-500/40 bg-red-500/10">
          <Skull className="h-5 w-5 text-red-400 shrink-0 mt-0.5" />
          <div className="space-y-1">
            <p className="text-sm font-semibold text-red-300">This contract has been decommissioned</p>
            <p className="text-xs text-red-200/80">
              Its global state carries <span className="font-mono">deactivated = True</span>. The
              on-chain validator rejects every further spend of the global-state UTxO, so transfers
              stay frozen and no admin action, mint, or burn can ever run against this token again.
              This is irreversible by design — there is no action that clears the flag.
            </p>
            <p className="text-xs text-red-200/60">
              Existing holders keep their tokens; they are immobilised, not destroyed.
            </p>
          </div>
        </div>
        <ReadOnlyField label="policy id" value={policyId} mono />
        <ReadOnlyField label="transfers paused" value={String(onchain.transfersPaused)} />
        <ReadOnlyField label="mintable_amount (frozen)" value={String(onchain.mintableAmount)} />
        <button
          type="button"
          onClick={refresh}
          className="flex items-center gap-1.5 text-xs text-dark-400 hover:text-primary-400 transition-colors"
        >
          <RefreshCw className="h-3.5 w-3.5" />
          Refresh
        </button>
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

      {/* requires_sender_kyc (D4) */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Requires sender KYC</label>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setRequiresSenderKyc(true)}
            disabled={busy}
            className={cn("flex-1 px-4 py-2 rounded-lg border text-sm transition-colors",
              requiresSenderKyc ? "bg-primary-500/10 border-primary-500 text-primary-300" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >Enabled</button>
          <button
            type="button"
            onClick={() => setRequiresSenderKyc(false)}
            disabled={busy}
            className={cn("flex-1 px-4 py-2 rounded-lg border text-sm transition-colors",
              !requiresSenderKyc ? "bg-primary-500/10 border-primary-500 text-primary-300" : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600")}
          >Disabled</button>
        </div>
        {/* Enforced on chain: transfer_logic_script.ak:123 gates the per-sender KYC
            loop on requires_sender_kyc, independently of the per-destination loop
            at :157 which reads requires_receiver_kyc. (Before the re-pin to
            @e69c66a both loops read the receiver flag — that was the F-20 bug.) */}
        <p className="mt-1.5 text-xs text-dark-400">
          When enabled, every <strong>sender</strong> in a transfer must present a valid KYC
          proof. Independent of the receiver requirement &mdash; the transfer logic gates the
          sender loop on <span className="font-mono">requires_sender_kyc</span> and the
          destination loop on <span className="font-mono">requires_receiver_kyc</span>.
        </p>
        {senderKycChanged && <p className="mt-1 text-xs text-amber-400">Will submit: SetRequiresSenderKyc</p>}
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
        {securityInfoError ? (
          <p className="mt-1 text-xs text-red-400">
            {securityInfoError} Plain text is fine too — anything that isn&apos;t valid hex is
            wrapped as a CBOR byte string automatically.
          </p>
        ) : securityChanged ? (
          <p className="mt-1 text-xs text-amber-400">Will submit: ModifySecurityInfo</p>
        ) : null}
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
            const staged = trustedUpdates.find((u) => u.newVkey === v);
            const isNew = !onchain.trustedEntityVkeys.includes(v) && !staged;
            return (
              <div key={v} className="flex items-center justify-between gap-2 p-2 bg-dark-900 rounded">
                <div className="min-w-0">
                  <p className={cn("text-xs font-mono break-all",
                    staged ? "text-blue-300" : isNew ? "text-amber-300" : "text-dark-300")}>{v}</p>
                  {staged && (
                    <p className="text-[10px] text-blue-300/80 mt-0.5">
                      {staged.oldVkey === staged.newVkey
                        ? "metadata edit staged"
                        : `replaces ${shortHex(staged.oldVkey)}`}
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  {/* D5 — UpdateTrustedEntity in ONE transaction. Remove + Add is two
                      txs and leaves the entity absent in between, which is visible
                      on chain. Only offered for entities that actually exist on
                      chain, since the action's `old_exists` check is on the
                      committed datum. */}
                  {onchain.trustedEntityVkeys.includes(v) && !staged && (
                    <button
                      type="button"
                      onClick={() => setEditingEntity({ oldVkey: v, newVkey: v, metadataHex: "a0" })}
                      disabled={busy}
                      className="text-dark-400 hover:text-primary-400"
                      title="Update this entity's key and/or metadata in one transaction"
                    >
                      <Pencil className="h-4 w-4" />
                    </button>
                  )}
                  {staged && (
                    <button
                      type="button"
                      onClick={() => {
                        setTrustedUpdates((cur) => cur.filter((u) => u.newVkey !== v));
                        setTrustedEntities((cur) =>
                          cur.map((x) => (x === staged.newVkey ? staged.oldVkey : x)));
                      }}
                      disabled={busy}
                      className="text-dark-400 hover:text-amber-400"
                      title="Discard the staged update"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => {
                      setTrustedEntities((cur) => cur.filter((x) => x !== v));
                      setTrustedUpdates((cur) => cur.filter((u) => u.newVkey !== v));
                    }}
                    disabled={busy}
                    className="text-dark-400 hover:text-red-400"
                    title="Remove"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
                </div>
              </div>
            );
          })}
        </div>

        {/* D5 — inline editor for UpdateTrustedEntity */}
        {editingEntity && (
          <TrustedEntityEditor
            edit={editingEntity}
            busy={busy}
            existingVkeys={trustedEntities}
            onChange={setEditingEntity}
            onCancel={() => setEditingEntity(null)}
            onStage={(u) => {
              setTrustedUpdates((cur) => [...cur.filter((x) => x.oldVkey !== u.oldVkey), u]);
              setTrustedEntities((cur) => cur.map((x) => (x === u.oldVkey ? u.newVkey : x)));
              setEditingEntity(null);
            }}
          />
        )}
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
              // Re-adding a key that a staged update is replacing would be silently
              // dropped: it is not in `trustedEntities` (the update renamed it away)
              // so it looks addable, but `trustedAdded` filters on "not already on
              // chain" and this key IS on chain — so no AddTrustedEntity is emitted
              // and the key quietly disappears. Refuse and say why.
              const stagedAway = trustedUpdates.find((u) => u.oldVkey === v);
              if (stagedAway) {
                setError(
                  `${shortHex(v)} is being replaced by a staged update `
                  + `(→ ${shortHex(stagedAway.newVkey)}). Discard that update first if you `
                  + `want to keep this key.`,
                );
                return;
              }
              setError(null);
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
        {(trustedAdded.length > 0 || trustedRemoved.length > 0 || trustedUpdates.length > 0) && (
          <p className="mt-1 text-xs text-amber-400">
            Will submit: {[
              trustedRemoved.length > 0 && `${trustedRemoved.length} RemoveTrustedEntity`,
              trustedUpdates.length > 0 && `${trustedUpdates.length} UpdateTrustedEntity`,
              trustedAdded.length > 0 && `${trustedAdded.length} AddTrustedEntity`,
            ].filter(Boolean).join(" + ")}
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
        <Button
          type="button"
          variant="primary"
          onClick={handleSave}
          disabled={busy || !hasChanges || !!securityInfoError}
        >
          {busy ? "Building + signing…" : hasChanges ? "Save & submit chain" : "No changes"}
        </Button>
        <p className="text-xs text-dark-400">
          Each change = one mempool-chained admin tx. Your wallet will be asked to
          sign N transactions in a single popup (CIP-103) where supported.
        </p>

        {/* D9 — a partial failure is the dangerous case: the chain is mempool-
            chained, so everything before the failing index IS on chain and the
            global state has moved. Show exactly what landed. */}
        {partialFailure && (
          <div className="mt-2 p-3 rounded-lg border border-amber-500/40 bg-amber-500/10 space-y-2">
            <div className="flex items-start gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0 mt-0.5" />
              <div>
                <p className="text-xs font-semibold text-amber-300">
                  {partialFailure.txHashes.length > 0
                    ? `Chain partially applied — ${partialFailure.txHashes.length} of ${partialFailure.labels.length} change(s) are on chain`
                    : "Nothing was submitted"}
                </p>
                {partialFailure.txHashes.length > 0 && (
                  <p className="text-[11px] text-amber-200/80 mt-0.5">
                    The on-chain state has already moved. Refresh before retrying — the
                    remaining changes must be rebuilt against the new global state.
                  </p>
                )}
              </div>
            </div>
            <ol className="space-y-1">
              {partialFailure.labels.map((label, i) => {
                const landed = i < partialFailure.txHashes.length;
                const failed = partialFailure.failedIndex === i;
                return (
                  <li key={`${label}-${i}`} className="text-[11px] flex items-start gap-1.5">
                    <span className={cn("shrink-0 font-mono",
                      landed ? "text-green-400" : failed ? "text-red-400" : "text-dark-500")}>
                      {landed ? "✓" : failed ? "✗" : "–"}
                    </span>
                    <span className="min-w-0">
                      <span className={cn(
                        landed ? "text-green-300" : failed ? "text-red-300" : "text-dark-500")}>
                        {label}
                      </span>
                      {landed && (
                        <span className="block font-mono text-green-400/70 break-all">
                          {partialFailure.txHashes[i]}
                        </span>
                      )}
                      {failed && !landed && (
                        <span className="block text-red-300/80">
                          rejected{partialFailure.failedTxHash
                            ? ` (${shortHex(partialFailure.failedTxHash)})` : ""}
                        </span>
                      )}
                      {!landed && !failed && (
                        <span className="block text-dark-500">not attempted</span>
                      )}
                    </span>
                  </li>
                );
              })}
            </ol>
            <p className="text-[11px] text-red-300 break-all">{partialFailure.error}</p>
          </div>
        )}

        {!partialFailure && submittedHashes.length > 0 && (
          <div className="space-y-1 pt-2">
            <p className="text-xs text-green-400">Submitted {submittedHashes.length} tx{submittedHashes.length !== 1 ? "es" : ""}:</p>
            {submittedHashes.map((h, i) => (
              <p key={h} className="text-xs font-mono text-green-300 break-all">#{i + 1}: {h}</p>
            ))}
          </div>
        )}
        {error && <p className="text-xs text-red-400">{error}</p>}
      </div>

      {/* ── D2: Rotate admin ─────────────────────────────────────────────── */}
      <div className="pt-4 border-t border-dark-700 space-y-3">
        <div className="flex items-center gap-2">
          <KeyRound className="h-4 w-4 text-primary-400" />
          <span className="text-sm font-medium text-white">Rotate admin</span>
        </div>
        <p className="text-xs text-dark-400">
          Moves <span className="font-mono">admin_credential_hash</span> to a new credential.
          The contract requires <strong>both</strong> the outgoing and the incoming admin to
          sign, so this is a two-step flow: sign here, then have the new admin counter-sign.
          A single signature is rejected at submit with{" "}
          <span className="font-mono">MissingRequiredSigners</span>.
        </p>
        {onchain.adminCredentialHash && (
          <ReadOnlyField label="current admin credential hash" value={onchain.adminCredentialHash} mono />
        )}

        {rotateTxHash ? (
          <div className="p-3 rounded-lg border border-green-500/40 bg-green-500/10 space-y-2">
            <p className="text-xs text-green-300">Admin rotated. Transaction:</p>
            <p className="text-xs font-mono text-green-400 break-all">{rotateTxHash}</p>
            <Button type="button" variant="outline" size="sm" onClick={resetRotate}>Done</Button>
          </div>
        ) : !rotatePartialCbor ? (
          <>
            <Input
              value={rotateNewAdmin}
              onChange={(e) => setRotateNewAdmin(e.target.value)}
              placeholder="new admin credential hash — 28 bytes (56 hex chars)"
              disabled={rotateBusy || busy}
            />
            {rotateNewAdmin.trim().length > 0 && !rotateNewAdminValid && (
              <p className="text-xs text-red-400">
                Must be exactly 56 hex characters (a 28-byte payment key hash).
              </p>
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleRotateBuildAndSign}
              disabled={rotateBusy || busy || !rotateNewAdminValid}
            >
              {rotateBusy ? "Building + signing…" : "1. Build & sign as current admin"}
            </Button>
          </>
        ) : (
          <div className="space-y-3">
            <div className="p-3 rounded-lg border border-blue-500/40 bg-blue-500/10 space-y-2">
              <p className="text-xs text-blue-300 font-semibold">
                2. Collect the incoming admin&apos;s signature
              </p>
              <p className="text-[11px] text-blue-200/80">
                Either connect the new admin&apos;s wallet in the header and press
                &quot;Counter-sign&quot;, or copy this partially signed transaction, have them sign
                it elsewhere, and paste the result below.
              </p>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    navigator.clipboard?.writeText(rotatePartialCbor).then(
                      () => showToast({ title: "Copied", description: "Partially signed CBOR copied to clipboard", variant: "default" }),
                      () => showToast({ title: "Copy failed", description: "Select and copy the text manually", variant: "error" }),
                    );
                  }}
                >
                  <Copy className="h-3.5 w-3.5 mr-1" /> Copy CBOR
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={handleRotateCounterSign}
                  disabled={rotateBusy}
                >
                  Counter-sign with connected wallet
                </Button>
              </div>
              <p className="text-[10px] font-mono text-dark-400 break-all max-h-24 overflow-y-auto">
                {rotatePartialCbor}
              </p>
            </div>

            <Input
              value={rotatePastedCbor}
              onChange={(e) => setRotatePastedCbor(e.target.value)}
              placeholder="…or paste the counter-signed CBOR here"
              disabled={rotateBusy}
            />

            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={() => handleRotateSubmit(
                  rotatePastedCbor.trim() ? rotatePastedCbor.trim() : rotatePartialCbor)}
                disabled={rotateBusy}
              >
                {rotateBusy ? "Submitting…" : "3. Submit"}
              </Button>
              <Button type="button" variant="ghost" size="sm" onClick={resetRotate} disabled={rotateBusy}>
                Cancel
              </Button>
            </div>
          </div>
        )}
        {rotateError && <p className="text-xs text-red-400 break-all">{rotateError}</p>}
      </div>

      {/* ── Minting authority ─────────────────────────────────────────────── */}
      <div className="pt-4 border-t border-white/10 space-y-3">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-white/80">Minting authority</span>
        </div>
        <p className="text-xs text-white/50">
          The rules that decide whether a mint or burn is allowed live in a swappable
          authority script. Rotating it changes those rules without touching the CIP-113
          registry or the token&apos;s policy id.
        </p>
        {onchain?.mintingScriptCredentialHash && (
          <p className="text-xs text-white/40 font-mono break-all">
            current: {onchain.mintingScriptCredentialHash}
          </p>
        )}

        {onchain?.upgradesLocked ? (
          <p className="text-xs text-amber-300">
            Upgrades are permanently locked for this token. The minting authority and the
            transfer-logic scripts can no longer be changed by any action.
          </p>
        ) : !showRotateMinting ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setShowRotateMinting(true)}
            disabled={busy}
          >
            Rotate the minting authority…
          </Button>
        ) : (
          <div className="p-4 rounded-lg border border-white/15 bg-white/5 space-y-3">
            <p className="text-xs text-amber-300">
              This is not verified on chain. The proxy checks only that the script you name
              here withdraws — it cannot check that the script actually enforces anything.
              Pointing it at any other withdraw-capable script in this deployment silently
              disables every mint control, and the failure is silent and fails OPEN. After
              rotating, confirm that a mint lacking a power-user signature is REJECTED, for
              all five power-user roles.
            </p>
            <input
              type="text"
              value={newMintingScript}
              onChange={(e) => setNewMintingScript(e.target.value)}
              placeholder="new minting authority script hash (56 hex chars)"
              className="w-full px-3 py-2 rounded bg-black/40 border border-white/15 text-sm font-mono"
            />
            {newMintingScript.trim() !== "" && !newMintingScriptValid && (
              <p className="text-xs text-red-400">
                Must be exactly 56 lower-case hex characters (a 28-byte script hash).
              </p>
            )}
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={handleRotateMintingScript}
                disabled={busy || !newMintingScriptValid}
              >
                Rotate
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => { setShowRotateMinting(false); setNewMintingScript(""); }}
                disabled={busy}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}
      </div>

      {/* ── Danger zone — lock upgrades ───────────────────────────────────── */}
      {!onchain?.upgradesLocked && (
        <div className="pt-4 border-t border-red-900/50 space-y-3">
          <div className="flex items-center gap-2">
            <Skull className="h-4 w-4 text-red-400" />
            <span className="text-sm font-medium text-red-300">Danger zone — lock upgrades</span>
          </div>
          {!showLockUpgrades ? (
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setShowLockUpgrades(true)}
              disabled={busy}
              className="border-red-500/50 text-red-300 hover:bg-red-500/10"
            >
              Lock upgrades permanently…
            </Button>
          ) : (
            <div className="p-4 rounded-lg border border-red-500/50 bg-red-500/10 space-y-3">
              <p className="text-xs text-red-200">
                This freezes BOTH the minting authority and the CIP-113 transfer-logic
                upgrade path. Nothing clears the flag afterwards — that is the point: the
                token&apos;s rules become final in a way an admin key compromise cannot undo.
              </p>
              <p className="text-xs text-red-200">
                It also gives up the ability to patch a buggy or compromised minting
                authority. After this, the only remaining lever over the token is
                decommissioning it. Lock only when the deployment is final.
              </p>
              <label className="block text-xs text-white/60">
                Type <span className="font-mono font-semibold">{LOCK_PHRASE}</span> to confirm:
              </label>
              <input
                type="text"
                value={lockConfirm}
                onChange={(e) => setLockConfirm(e.target.value)}
                placeholder={LOCK_PHRASE}
                className="w-full px-3 py-2 rounded bg-black/40 border border-red-500/40 text-sm font-mono"
              />
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="primary"
                  size="sm"
                  onClick={handleLockUpgrades}
                  disabled={busy || lockConfirm.trim() !== LOCK_PHRASE}
                  className="bg-red-600 hover:bg-red-500"
                >
                  Lock upgrades permanently
                </Button>
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => { setShowLockUpgrades(false); setLockConfirm(""); }}
                  disabled={busy}
                >
                  Cancel
                </Button>
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── D3: Danger zone — decommission ───────────────────────────────── */}
      <div className="pt-4 border-t border-red-900/50 space-y-3">
        <div className="flex items-center gap-2">
          <Skull className="h-4 w-4 text-red-400" />
          <span className="text-sm font-medium text-red-300">Danger zone — decommission</span>
        </div>
        {!showDecommission ? (
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => setShowDecommission(true)}
            disabled={busy}
            className="border-red-500/50 text-red-300 hover:bg-red-500/10"
          >
            Decommission this contract…
          </Button>
        ) : (
          <div className="p-4 rounded-lg border border-red-500/50 bg-red-500/10 space-y-3">
            <div className="flex items-start gap-2">
              <AlertTriangle className="h-5 w-5 text-red-400 shrink-0 mt-0.5" />
              <div className="space-y-2">
                <p className="text-sm font-semibold text-red-300">
                  This is permanent and cannot be undone.
                </p>
                <ul className="text-xs text-red-200/90 space-y-1 list-disc list-inside">
                  <li>
                    Sets <span className="font-mono">deactivated = True</span> in the global state.
                  </li>
                  <li>
                    Afterwards the validator rejects <strong>every</strong> spend of the
                    global-state UTxO. No unpause, no mint, no burn, no admin action — ever.
                  </li>
                  <li>
                    Transfers stay frozen permanently. Holders keep their tokens, but they are
                    immobilised and can never move again.
                  </li>
                  <li>
                    There is <strong>no</strong> reversing action in the contract. Recovering
                    would mean bootstrapping an entirely new token.
                  </li>
                </ul>
                {!onchain.transfersPaused && (
                  <p className="text-xs text-amber-300 border-t border-red-500/30 pt-2">
                    <AlertTriangle className="inline h-3 w-3 mr-1" />
                    The contract requires transfers to be paused first. Transfers are currently
                    <strong> active</strong>, so a <span className="font-mono">PauseTransfers</span>{" "}
                    transaction will be chained ahead of the decommission — you will be asked to
                    sign <strong>two</strong> transactions.
                  </p>
                )}
              </div>
            </div>
            <div className="space-y-2">
              <label className="block text-xs text-red-200">
                Type <span className="font-mono font-semibold">{DECOMMISSION_PHRASE}</span> to confirm:
              </label>
              <Input
                value={decommissionConfirm}
                onChange={(e) => setDecommissionConfirm(e.target.value)}
                placeholder={DECOMMISSION_PHRASE}
                disabled={busy}
              />
            </div>
            <div className="flex items-center gap-2">
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={handleDecommission}
                disabled={busy || decommissionConfirm.trim() !== DECOMMISSION_PHRASE}
                className="bg-red-600 hover:bg-red-500"
              >
                {busy ? "Submitting…" : "Permanently decommission"}
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => { setShowDecommission(false); setDecommissionConfirm(""); }}
                disabled={busy}
              >
                Cancel
              </Button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/** A staged {@code UpdateTrustedEntity}: replace {@code oldVkey} with
 *  {@code newVkey} and set new metadata, in one transaction. When the two vkeys
 *  are equal the on-chain branch treats it as a metadata-only edit. */
interface TrustedEntityUpdate {
  oldVkey: string;
  newVkey: string;
  /** CBOR Data hex. "a0" (empty map) is the platform's default. */
  metadataHex: string;
}

function TrustedEntityEditor({
  edit, busy, existingVkeys, onChange, onCancel, onStage,
}: {
  edit: TrustedEntityUpdate;
  busy: boolean;
  existingVkeys: string[];
  onChange: (u: TrustedEntityUpdate) => void;
  onCancel: () => void;
  onStage: (u: TrustedEntityUpdate) => void;
}) {
  const newVkey = edit.newVkey.trim().toLowerCase();
  const renaming = newVkey !== edit.oldVkey;
  const vkeyValid = /^[0-9a-f]{64}$/.test(newVkey);
  // Mirrors the contract's `new_clash_ok`: when renaming, the new vkey must not
  // already be a trusted entity, or the update would merge two entries into one.
  const clashes = renaming && existingVkeys.some((v) => v === newVkey);
  const metaError = cborDataError(edit.metadataHex);
  const canStage = vkeyValid && !clashes && !metaError;

  return (
    <div className="mt-2 p-3 rounded-lg border border-primary-500/40 bg-primary-500/5 space-y-2">
      <p className="text-xs font-semibold text-primary-300">Update trusted entity</p>
      <p className="text-[11px] text-dark-400 break-all">
        Replacing <span className="font-mono">{edit.oldVkey}</span>
      </p>
      <Input
        value={edit.newVkey}
        onChange={(e) => onChange({ ...edit, newVkey: e.target.value })}
        placeholder="new vkey hex (32 bytes) — leave unchanged to edit metadata only"
        disabled={busy}
      />
      {!vkeyValid && edit.newVkey.trim().length > 0 && (
        <p className="text-xs text-red-400">vkey must be 32 bytes hex (64 characters)</p>
      )}
      {clashes && (
        <p className="text-xs text-red-400">
          That vkey is already a trusted entity — renaming onto it would merge two entries.
        </p>
      )}
      <Input
        value={edit.metadataHex}
        onChange={(e) => onChange({ ...edit, metadataHex: e.target.value })}
        placeholder="metadata as CBOR Data hex (a0 = empty map)"
        disabled={busy}
      />
      {metaError && <p className="text-xs text-red-400">Metadata: {metaError}</p>}
      <p className="text-[11px] text-dark-500">
        One transaction, versus two for Remove + Add — which would also leave the entity
        absent on chain in between.
      </p>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => onStage({ ...edit, newVkey })}
          disabled={busy || !canStage}
        >
          Stage update
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

function isHex(s: string): boolean {
  return /^[0-9a-fA-F]*$/.test(s) && s.length % 2 === 0;
}

function shortHex(s: string): string {
  return s.length <= 16 ? s : `${s.slice(0, 8)}…${s.slice(-6)}`;
}

/** Validate that `hex` is exactly one well-formed CBOR data item (D7).
 *  Returns null when it is, or a human-readable reason when it isn't.
 *
 *  The GS datum's `security_info` and a trusted entity's `metadata` are opaque
 *  `Data` on chain, so the platform relays whatever the operator types — but
 *  "even-length hex" was the only check, and that admits `deadbeef`, which is
 *  perfectly good hex and not a CBOR value at all. The backend now returns a
 *  typed 400 for this; catching it here saves the round trip and points at the
 *  field directly.
 *
 *  This is a structural scan, not a decoder: it walks the item tree to confirm
 *  every header is well-formed and that the item ends exactly at the end of the
 *  input. That is enough to distinguish the two failure modes that matter —
 *  unparseable input, and a valid item with trailing bytes that would be
 *  silently dropped. */
function cborDataError(hex: string): string | null {
  const s = hex.trim();
  if (s.length === 0) return "must not be empty.";
  if (s.length % 2 !== 0) return "must be an even number of hex characters.";
  if (!/^[0-9a-fA-F]+$/.test(s)) return "must be hex.";
  const bytes = new Uint8Array(s.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(s.substring(i * 2, i * 2 + 2), 16);
  }
  let end: number;
  try {
    end = scanCborItem(bytes, 0);
  } catch (e) {
    return `is not valid CBOR: ${e instanceof Error ? e.message : String(e)}.`;
  }
  if (end < bytes.length) {
    return `has ${bytes.length - end} trailing byte(s) after a complete CBOR value; `
      + "those bytes would be silently dropped.";
  }
  return null;
}

/** Advance past one CBOR data item starting at `off`; returns the offset just
 *  past it. Throws on a malformed or unsupported header. */
function scanCborItem(buf: Uint8Array, off: number): number {
  if (off >= buf.length) throw new Error("unexpected end of input");
  const initial = buf[off];
  const major = initial >> 5;
  const ai = initial & 0x1f;
  let cursor = off + 1;
  let value = 0;

  if (ai < 24) {
    value = ai;
  } else if (ai === 24) { value = readUint(buf, cursor, 1); cursor += 1; }
  else if (ai === 25) { value = readUint(buf, cursor, 2); cursor += 2; }
  else if (ai === 26) { value = readUint(buf, cursor, 4); cursor += 4; }
  else if (ai === 27) { value = readUint(buf, cursor, 8); cursor += 8; }
  else if (ai === 31) {
    // Indefinite length — only legal for major types 2,3,4,5.
    if (major < 2 || major > 5) throw new Error(`indefinite length is not allowed for major type ${major}`);
    for (;;) {
      if (cursor >= buf.length) throw new Error("unterminated indefinite-length item");
      if (buf[cursor] === 0xff) return cursor + 1;
      cursor = scanCborItem(buf, cursor);
    }
  } else {
    throw new Error(`reserved additional-information value ${ai}`);
  }

  switch (major) {
    case 0: // unsigned int
    case 1: // negative int
      return cursor;
    case 2: // byte string
    case 3: // text string
      // Plutus Data has no text constructor, but the backend's decoder accepts a
      // CBOR text string and silently stores it as a BYTE string (verified: input
      // 6161 lands in the datum as 4161). Accepting it here keeps this check
      // aligned with the server — a UI that rejects what the server accepts is as
      // unhelpful as one that accepts what the server rejects.
      if (cursor + value > buf.length) throw new Error("string length runs past the end of the input");
      return cursor + value;
    case 4: // array
      for (let i = 0; i < value; i++) cursor = scanCborItem(buf, cursor);
      return cursor;
    case 5: // map
      for (let i = 0; i < value; i++) {
        cursor = scanCborItem(buf, cursor);
        cursor = scanCborItem(buf, cursor);
      }
      return cursor;
    case 6: // tag — one nested item follows
      return scanCborItem(buf, cursor);
    case 7:
      // Simple values / floats. Plutus Data has no float or simple-value
      // constructors, so anything here is a payload the on-chain `Data` type
      // cannot represent.
      throw new Error("floats and simple values are not valid Plutus Data");
    default:
      throw new Error(`unknown major type ${major}`);
  }
}

function readUint(buf: Uint8Array, off: number, len: number): number {
  if (off + len > buf.length) throw new Error("unexpected end of input while reading a length");
  let n = 0;
  for (let i = 0; i < len; i++) n = n * 256 + buf[off + i];
  return n;
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
