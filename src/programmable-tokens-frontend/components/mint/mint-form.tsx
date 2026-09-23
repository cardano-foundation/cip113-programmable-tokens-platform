"use client";

import { useState } from "react";
import { useWallet } from "@/hooks/use-wallet";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ModuleSelector } from "./module-selector";
import { Module, LegacyMintFormData } from "@/types/api";
import {
  prepareLegacyMintRequest,
  legacyMintToken,
  stringToHex,
} from "@/lib/api";
import { useToast } from "@/components/ui/use-toast";
import { useProtocolVersion } from "@/contexts/protocol-version-context";

interface MintFormProps {
  modules: Module[];
  onTransactionBuilt: (
    txHex: string,
    assetName: string,
    quantity: string
  ) => void;
  preSelectedModule?: string;
  preSelectedIssueContract?: string;
}

export function MintForm({
  modules,
  onTransactionBuilt,
  preSelectedModule,
  preSelectedIssueContract,
}: MintFormProps) {
  const { connected, wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  const [tokenName, setTokenName] = useState("");
  const [quantity, setQuantity] = useState("");
  const [recipientAddress, setRecipientAddress] = useState("");
  const [moduleId, setModuleId] = useState("");
  const [validatorTitle, setValidatorTitle] = useState("");
  const [isBuilding, setIsBuilding] = useState(false);

  const [errors, setErrors] = useState({
    tokenName: "",
    quantity: "",
    recipientAddress: "",
    module: "",
  });

  const handleModuleSelect = (
    selectedModuleId: string,
    selectedValidatorTitle: string
  ) => {
    setModuleId(selectedModuleId);
    setValidatorTitle(selectedValidatorTitle);
    setErrors((prev) => ({ ...prev, module: "" }));
  };

  const validateForm = (): boolean => {
    const newErrors = {
      tokenName: "",
      quantity: "",
      recipientAddress: "",
      module: "",
    };

    if (!tokenName.trim()) {
      newErrors.tokenName = "Token name is required";
    } else if (tokenName.length > 32) {
      newErrors.tokenName = "Token name must be 32 characters or less";
    }

    if (!quantity.trim()) {
      newErrors.quantity = "Quantity is required";
    } else if (!/^\d+$/.test(quantity)) {
      newErrors.quantity = "Quantity must be a positive number";
    } else if (BigInt(quantity) <= 0) {
      newErrors.quantity = "Quantity must be greater than 0";
    }

    if (recipientAddress.trim() && !recipientAddress.startsWith("addr")) {
      newErrors.recipientAddress = "Invalid Cardano address format";
    }

    if (!moduleId || !validatorTitle) {
      newErrors.module = "Please select a module and validator";
    }

    setErrors(newErrors);
    return !Object.values(newErrors).some((error) => error !== "");
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (!connected) {
      showToast({
        title: "Wallet Not Connected",
        description: "Please connect your wallet to mint tokens",
        variant: "error",
      });
      return;
    }

    if (!validateForm()) {
      showToast({
        title: "Validation Error",
        description: "Please fix the errors in the form",
        variant: "error",
      });
      return;
    }

    try {
      setIsBuilding(true);

      // Get issuer address from wallet
      const addresses = await wallet.getUsedAddresses();
      if (!addresses || addresses.length === 0) {
        throw new Error("No addresses found in wallet");
      }
      const issuerAddress = addresses[0];

      const formData: LegacyMintFormData = {
        tokenName,
        quantity,
        moduleId,
        validatorTitle,
        recipientAddress: recipientAddress.trim() || undefined,
      };

      const request = prepareLegacyMintRequest(formData, issuerAddress);
      const unsignedTxCborHex = await legacyMintToken(request);

      showToast({
        title: "Transaction Built",
        description: "Transaction built successfully",
        variant: "success",
      });

      // Pass transaction to parent for preview and signing
      onTransactionBuilt(unsignedTxCborHex, tokenName, quantity);
    } catch (error) {
      console.error("Error building transaction:", error);
      showToast({
        title: "Transaction Build Failed",
        description:
          error instanceof Error
            ? error.message
            : "Failed to build transaction",
        variant: "error",
      });
    } finally {
      setIsBuilding(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6">
      {/* Token Name */}
      <div>
        <Input
          label="Token Name"
          type="text"
          value={tokenName}
          onChange={(e) => {
            setTokenName(e.target.value);
            setErrors((prev) => ({ ...prev, tokenName: "" }));
          }}
          disabled={isBuilding}
          error={errors.tokenName}
          helperText="Human-readable name (max 32 characters)"
          placeholder="MyToken"
        />
        {tokenName && (
          <p className="mt-1 text-xs text-dark-400">
            Hex: {stringToHex(tokenName)}
          </p>
        )}
      </div>

      {/* Quantity */}
      <Input
        label="Quantity"
        type="text"
        value={quantity}
        onChange={(e) => {
          setQuantity(e.target.value);
          setErrors((prev) => ({ ...prev, quantity: "" }));
        }}
        disabled={isBuilding}
        error={errors.quantity}
        helperText="Number of tokens to mint"
        placeholder="1000000"
      />

      {/* Recipient Address (Optional) */}
      <Input
        label="Recipient Address (Optional)"
        type="text"
        value={recipientAddress}
        onChange={(e) => {
          setRecipientAddress(e.target.value);
          setErrors((prev) => ({ ...prev, recipientAddress: "" }));
        }}
        disabled={isBuilding}
        error={errors.recipientAddress}
        helperText="Leave empty to mint to your own address"
        placeholder="addr..."
      />

      {/* Module Selector */}
      <div>
        <ModuleSelector
          modules={modules}
          onSelect={handleModuleSelect}
          disabled={isBuilding}
          initialModule={preSelectedModule}
          initialValidator={preSelectedIssueContract}
        />
        {errors.module && (
          <p className="mt-1 text-sm text-red-400">{errors.module}</p>
        )}
      </div>

      {/* Submit Button */}
      <Button
        type="submit"
        variant="primary"
        className="w-full"
        isLoading={isBuilding}
        disabled={!connected || isBuilding}
      >
        {isBuilding ? "Building Transaction..." : "Build Transaction"}
      </Button>

      {!connected && (
        <p className="text-sm text-center text-dark-400">
          Connect your wallet to continue
        </p>
      )}
    </form>
  );
}
