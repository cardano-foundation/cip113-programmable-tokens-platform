"use client";

import { useState } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { UserCheck, Plus, Minus, CheckCircle, ExternalLink } from "lucide-react";
import { AdminTokenSelector } from "./AdminTokenSelector";
import { AdminTokenInfo } from "@/lib/api/admin";
import { useProtocolVersion } from "@/contexts/protocol-version-context";
import { useToast } from "@/components/ui/use-toast";
import { getExplorerTxUrl } from "@/lib/utils";
import { cn } from "@/lib/utils";

interface TelSectionProps {
  tokens: AdminTokenInfo[];
  adminAddress: string;
}

type TelAction = "add" | "remove";
type TelStep = "form" | "signing" | "success";

export function TelSection({ tokens, adminAddress }: TelSectionProps) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  // Filter tokens where user has ISSUER_ADMIN role and substandardId is kyc
  const manageableTokens = tokens.filter(
    (t) => t.roles.includes("ISSUER_ADMIN") && t.substandardId === "kyc"
  );

  const [selectedToken, setSelectedToken] = useState<AdminTokenInfo | null>(null);
  const [action, setAction] = useState<TelAction>("add");
  const [targetVkey, setTargetVkey] = useState("");
  const [step, setStep] = useState<TelStep>("form");
  const [isBuilding, setIsBuilding] = useState(false);
  const [isSigning, setIsSigning] = useState(false);
  const [txHash, setTxHash] = useState<string | null>(null);

  const [errors, setErrors] = useState({
    token: "",
    targetVkey: "",
  });

  const validateForm = (): boolean => {
    const newErrors = {
      token: "",
      targetVkey: "",
    };

    if (!selectedToken) {
      newErrors.token = "Please select a token";
    }

    if (!targetVkey.trim()) {
      newErrors.targetVkey = "Verification key is required";
    } else if (!/^[0-9a-fA-F]{64}$/.test(targetVkey.trim())) {
      newErrors.targetVkey = "Must be 64 hex characters (32 bytes Ed25519 vkey)";
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

      const { addToGlobalState, removeFromGlobalState } = await import(
        "@/lib/api/compliance"
      );

      const request = {
        adminAddress: adminAddress,
        verificationKey: targetVkey.trim(),
        policyId: selectedToken.policyId,
      };

      let unsignedCborTx: string;
      if (action === "add") {
        const response = await addToGlobalState(request, selectedVersion?.txHash);
        unsignedCborTx = response.unsignedCborTx;
      } else {
        const response = await removeFromGlobalState(request, selectedVersion?.txHash);
        unsignedCborTx = response.unsignedCborTx;
      }

      setIsBuilding(false);
      setStep("signing");
      setIsSigning(true);

      // Sign and submit
      const signedTx = await wallet.signTx(unsignedCborTx);
      const submittedTxHash = await wallet.submitTx(signedTx);

      setTxHash(submittedTxHash);
      setStep("success");

      showToast({
        title: `Trusted Entity ${action === "add" ? "Added" : "Removed"}`,
        description: `Successfully ${action === "add" ? "added" : "removed"} the verification key`,
        variant: "success",
      });
    } catch (error) {
      console.error("Global state entity error:", error);

      let errorMessage = `Failed to ${action} trusted entity`;
      if (error instanceof Error) {
        if (error.message.includes("User declined")) {
          errorMessage = "Transaction was cancelled";
        } else {
          errorMessage = error.message;
        }
      }

      showToast({
        title: "Trusted Entity Operation Failed",
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
    setTargetVkey("");
    setTxHash(null);
    setErrors({ token: "", targetVkey: "" });
  };

  if (manageableTokens.length === 0) {
    return (
      <div className="flex flex-col items-center py-12 px-6">
        <UserCheck className="h-16 w-16 text-dark-600 mb-4" />
        <h3 className="text-lg font-semibold text-white mb-2">
          No KYC Token Management Access
        </h3>
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
        <h3 className="text-lg font-semibold text-white mb-2">
          {action === "add" ? "Entity Added" : "Entity Removed"}
        </h3>
        <p className="text-sm text-dark-400 text-center mb-4">
          Successfully {action === "add" ? "added to" : "removed from"} the trusted entity list
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
          filterByRole="ISSUER_ADMIN"
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
                ? "bg-green-500/10 border-green-500 text-green-400"
                : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
            )}
          >
            <Plus className="h-4 w-4" />
            Add Entity
          </button>
          <button
            type="button"
            onClick={() => setAction("remove")}
            disabled={isBuilding}
            className={cn(
              "flex-1 flex items-center justify-center gap-2 px-4 py-3 rounded-lg border transition-colors",
              action === "remove"
                ? "bg-red-500/10 border-red-500 text-red-400"
                : "bg-dark-800 border-dark-700 text-dark-400 hover:border-dark-600"
            )}
          >
            <Minus className="h-4 w-4" />
            Remove Entity
          </button>
        </div>
      </div>

      {/* Verification Key Input */}
      <Input
        label="Verification Key"
        value={targetVkey}
        onChange={(e) => {
          setTargetVkey(e.target.value);
          setErrors((prev) => ({ ...prev, targetVkey: "" }));
        }}
        placeholder="64-character hex Ed25519 verification key"
        disabled={isBuilding || !selectedToken}
        error={errors.targetVkey}
        helperText={
          action === "add"
            ? "Ed25519 verification key of the trusted entity to add"
            : "Ed25519 verification key of the trusted entity to remove"
        }
      />

      {/* Submit Button */}
      <Button
        type="submit"
        variant={action === "add" ? "primary" : "danger"}
        className="w-full"
        isLoading={isBuilding}
        disabled={isBuilding || !selectedToken}
      >
        {isBuilding
          ? "Building Transaction..."
          : action === "add"
          ? "Add Trusted Entity"
          : "Remove Trusted Entity"}
      </Button>
    </form>
  );
}
