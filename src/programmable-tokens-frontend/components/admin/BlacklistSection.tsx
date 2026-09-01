"use client";

import { useState } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { TxBuilderToggle, type TransactionBuilder } from "@/components/ui/tx-builder-toggle";
import { Shield, Plus, Minus, CheckCircle, ExternalLink } from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import {
  AdminTokenInfo,
  RwaTokenCapability,
  hasRwaTokenCapability,
} from "@/lib/api/admin";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useCIP113 } from "@/contexts/cip113-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { cn } from "@/lib/utils";

interface BlacklistSectionProps {
  tokens: AdminTokenInfo[];
  adminAddress: string;
}

type BlacklistAction = "add" | "remove";
type BlacklistStep = "form" | "signing" | "success";

export function BlacklistSection({ tokens, adminAddress }: BlacklistSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const { getProtocol, ensureSubstandard, available: sdkAvailable, sdkUnavailableReason } = useCIP113();
  // Default to the backend builder even when the SDK is available. Parity means the SDK
  // CAN build a transaction, not that it becomes the default route (PLAN.md A-5) — the
  // SDK path is opt-in per operation via the toggle until T-018 has verified all seven
  // operations against a live deployment. Deriving this from `sdkAvailable` would flip
  // every user onto an unverified path the moment the capability was re-enabled.
  const [txBuilder, setTxBuilder] = useState<TransactionBuilder>("backend");
  const network = process.env.NEXT_PUBLIC_NETWORK || "preview";

  // Per-page capability gate. Show:
  //   - tokens where the wallet has BLACKLIST_MANAGER role (legacy F&S)
  //   - rwa-tokens where the wallet has ADMIN capability — BaFin's
  //     denylist mutations (AddDenylist / RemoveDenylist) are admin-gated
  //     directly in the GS validator, not a separate PowerUser role
  const manageableTokens = tokens.filter((t) => {
    if (t.substandardId === "rwa-token") {
      return hasRwaTokenCapability(t, RwaTokenCapability.ADMIN);
    }
    return t.roles.includes("BLACKLIST_MANAGER");
  });

  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(null);
  const [action, setAction] = useState<BlacklistAction>("add");
  const [targetAddress, setTargetAddress] = useState("");
  const [step, setStep] = useState<BlacklistStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  const [errors, setErrors] = useState({
    token: "",
    targetAddress: "",
  });

  const validateForm = (): boolean => {
    const newErrors = {
      token: "",
      targetAddress: "",
    };

    if (!selectedToken) {
      newErrors.token = "Please select a token";
    }

    if (!targetAddress.trim()) {
      newErrors.targetAddress = "Address is required";
    } else if (!targetAddress.startsWith("addr")) {
      newErrors.targetAddress = "Invalid Cardano address format";
    }

    setErrors(newErrors);
    return !Object.values(newErrors).some((error) => error !== "");
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!validateForm() || !selectedToken) {
      return;
    }

    try {
      setIsBuilding(true);

      let unsignedCborTx: string;

      // SDK (cip113-sdk-ts) only knows about dummy + freeze-and-seize.
      // rwa-token MUST go through the backend path (its on-chain denylist
      // mutations are wired into /compliance/blacklist/{add,remove} now via the
      // BlacklistManageable interface on RwaTokenSubstandardHandler).
      const forceBackend = selectedToken.substandardId === "rwa-token";

      if (txBuilder === "sdk" && !forceBackend) {
        await ensureSubstandard(selectedToken.policyId, selectedToken.assetName);
        const protocol = await getProtocol();
        const params = {
          // Route explicitly. 0.4.0 made this REQUIRED rather than falling back to
          // trying every registered substandard: freeze/unfreeze are administrative
          // operations over someone else's tokens, and a try-all reports "no
          // substandard can handle this" when the truth is "it was handled and the
          // chain refused". Passing the token's own id keeps that distinction.
          substandardId: selectedToken.substandardId,
          feePayerAddress: adminAddress,
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          targetAddress: targetAddress.trim(),
        };
        if (action === "add") {
          const result = await protocol.compliance.freeze(params);
          unsignedCborTx = result.cbor;
        } else {
          const result = await protocol.compliance.unfreeze(params);
          unsignedCborTx = result.cbor;
        }
      } else {
        const { addToBlacklist, removeFromBlacklist } = await import(
          "@/lib/api/compliance"
        );
        const request = {
          tokenPolicyId: selectedToken.policyId,
          assetName: selectedToken.assetName,
          targetAddress: targetAddress.trim(),
          feePayerAddress: adminAddress,
        };
        if (action === "add") {
          const response = await addToBlacklist(request, selectedVersion?.txHash);
          unsignedCborTx = response.unsignedCborTx;
        } else {
          const response = await removeFromBlacklist(request, selectedVersion?.txHash);
          unsignedCborTx = response.unsignedCborTx;
        }
      }

      setIsBuilding(false);
      setStep("signing");
      setIsSigning(true);

      // Sign and submit
      const signedTx = await wallet.signTx(unsignedCborTx);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      const isDenylist = selectedToken?.substandardId === "rwa-token";
      const listName = isDenylist ? "Denylist" : "Blacklist";
      showToast({
        title: `${action === "add" ? "Added to" : "Removed from"} ${listName}`,
        description: action === "add"
          ? `Recipient added — future transfers to this ${isDenylist ? "stake credential" : "address"} will be rejected on-chain.`
          : `Recipient removed — transfers to this ${isDenylist ? "stake credential" : "address"} will be allowed again.`,
        variant: "success",
      });
    } catch (error) {
      console.error("Blacklist error:", error);

      let errorMessage = `Failed to ${action} address to blacklist`;
      if (error instanceof Error) {
        if (error.message.includes("User declined")) {
          errorMessage = "Transaction was cancelled";
        } else {
          errorMessage = error.message;
        }
      }

      showToast({
        title: "Blacklist Operation Failed",
        description: errorMessage,
        variant: "error",
      });

      setStep("form");
    } finally {
      setIsBuilding(false);
      setIsSigning(false);
    }
  };

  const handleReset = () => {
    setStep("form");
    setTargetAddress("");
    setTxHash(null);
    setErrors({ token: "", targetAddress: "" });
  };

  if (manageableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <Shield className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">
          No Blacklist Management Access
        </h3>
        <p className="text-sm text-dark-400 text-center">
          You don&apos;t have blacklist manager permissions for any tokens.
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
        <h3 className="text-lg font-semibold text-white mb-2">
          {action === "add" ? "Address Blacklisted" : "Address Removed"}
        </h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          Successfully {action === "add" ? "added to" : "removed from"} the blacklist
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
              <ExternalLink className="h-4 w-4 mr-2" />
              View on Explorer
            </Button>
          </a>
          <Button variant="primary" className="flex-1" onClick={handleReset}>
            Manage More
          </Button>
        </div>
      </div>
    );
  }

  if (step === "signing") {
    return (
      <div className="flex flex-col items-center py-12">
        <div className="h-12 w-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin mb-4" />
        <p className="text-white font-medium">
          {isSigning ? "Waiting for signature..." : "Building transaction..."}
        </p>
        <p className="text-sm text-dark-400 mt-2">
          Please confirm the transaction in your wallet
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      <TxBuilderToggle value={txBuilder} onChange={setTxBuilder} sdkAvailable={sdkAvailable}
        sdkUnavailableReason={sdkUnavailableReason} />

      {/* Token Selector */}
      <div>
        <AdminTokenSelector
          tokens={manageableTokens}
          selectedToken={selectedToken}
          onSelect={(token) => {
            setSelectedToken(token);
            setErrors((prev) => ({ ...prev, token: "" }));
          }}
          disabled={isBuilding}
          filterByRole="BLACKLIST_MANAGER"
        />
        {errors.token && (
          <p className="mt-2 text-sm text-red-400">{errors.token}</p>
        )}
      </div>

      {/* Action Toggle */}
      <div>
        <label className="block text-sm font-medium text-white mb-2">Action</label>
        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => setAction("add")}
            disabled={isBuilding}
            className={cn(
              "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
              action === "add"
                ? "bg-red-500/10 border-red-500 text-red-400"
                : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
            )}
          >
            <Plus className="h-4 w-4" />
            Add to Blacklist
          </button>
          <button
            type="button"
            onClick={() => setAction("remove")}
            disabled={isBuilding}
            className={cn(
              "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
              action === "remove"
                ? "bg-green-500/10 border-green-500 text-green-400"
                : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
            )}
          >
            <Minus className="h-4 w-4" />
            Remove from Blacklist
          </button>
        </div>
      </div>

      {/* Target Address */}
      <Input
        label="Target Address"
        value={targetAddress}
        onChange={(e) => {
          setTargetAddress(e.target.value);
          setErrors((prev) => ({ ...prev, targetAddress: "" }));
        }}
        placeholder="addr1..."
        disabled={isBuilding || !selectedToken}
        error={errors.targetAddress}
        helperText={
          selectedToken?.substandardId === "rwa-token"
            ? (action === "add"
                ? "Address whose stake credential will be added to the on-chain denylist. Transfers to it will be rejected."
                : "Address whose stake credential will be removed from the denylist. Transfers to it will be allowed again.")
            : (action === "add"
                ? "Address to add to the blacklist (will be frozen)"
                : "Address to remove from the blacklist (will be unfrozen)")
        }
      />

      <Button
        type="submit"
        variant={action === "add" ? "danger" : "primary"}
        className="w-full"
        isLoading={isBuilding}
        disabled={isBuilding || !selectedToken}
      >
        {isBuilding
          ? "Building Transaction..."
          : action === "add"
          ? (selectedToken?.substandardId === "rwa-token" ? "Add to Denylist" : "Add to Blacklist")
          : (selectedToken?.substandardId === "rwa-token" ? "Remove from Denylist" : "Remove from Blacklist")}
      </Button>
    </form>
  );
}
