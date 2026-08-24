"use client";

import { useState, useEffect } from 'react';
import { useWallet } from '@/hooks/use-wallet';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import type { StepComponentProps, TokenDetailsData } from '@/types/registration';
import { supportsCIP68, CIP68_FIELD_MAX_LENGTHS, validateCip68Metadata } from '@/lib/utils/cip68';

interface TokenDetailsStepProps extends StepComponentProps<TokenDetailsData, TokenDetailsData> {}

export function TokenDetailsStep({
  stepData,
  wizardState,
  onDataChange,
  onComplete,
  onBack,
  isProcessing,
}: TokenDetailsStepProps) {
  // For the RWA/rwa-token flow this figure IS `initialMintQuantity` — the
  // supply the registration transaction itself mints. Zero is meaningful there: it
  // registers the policy structurally and leaves the first mint as a separate admin
  // action. Every other flow mints unconditionally, so zero stays an error.
  const allowsZeroSupply = wizardState?.flowId === 'rwa-token';
  const { connected, wallet } = useWallet();
  // This step is shared by all five flows, but only three of them mint the CIP-68 pair. For the
  // rest the form is not rendered at all — collecting metadata that goes nowhere is exactly the
  // bug this gate exists to prevent.
  const cip68Supported = supportsCIP68(wizardState?.flowId);
  const [assetName, setAssetName] = useState(stepData.assetName || '');
  const [quantity, setQuantity] = useState(stepData.quantity || '');
  const [recipientAddress, setRecipientAddress] = useState(stepData.recipientAddress || '');

  // CIP-68 metadata state
  const [cip68Enabled, setCip68Enabled] = useState(stepData.cip68Metadata?.enabled || false);
  const [cip68Name, setCip68Name] = useState(stepData.cip68Metadata?.name || '');
  const [cip68Description, setCip68Description] = useState(stepData.cip68Metadata?.description || '');
  const [cip68Ticker, setCip68Ticker] = useState(stepData.cip68Metadata?.ticker || '');
  const [cip68Decimals, setCip68Decimals] = useState(stepData.cip68Metadata?.decimals || '0');
  const [cip68Url, setCip68Url] = useState(stepData.cip68Metadata?.url || '');
  const [cip68Logo, setCip68Logo] = useState(stepData.cip68Metadata?.logo || '');
  const [cip68NameEdited, setCip68NameEdited] = useState(false);

  const [errors, setErrors] = useState<Record<string, string>>({});

  // Auto-fill recipient with wallet address if empty
  useEffect(() => {
    const fillWalletAddress = async () => {
      if (!recipientAddress && connected && wallet) {
        try {
          const addresses = await wallet.getUsedAddresses();
          if (addresses?.[0]) {
            setRecipientAddress(addresses[0]);
            onDataChange({ recipientAddress: addresses[0] });
          }
        } catch (error) {
          console.error('Failed to get wallet address:', error);
        }
      }
    };
    fillWalletAddress();
  }, [connected, wallet, recipientAddress, onDataChange]);

  // Sync CIP-68 name with assetName when not manually edited
  useEffect(() => {
    if (cip68Enabled && !cip68NameEdited) {
      setCip68Name(assetName);
    }
  }, [assetName, cip68Enabled, cip68NameEdited]);

  const validateForm = (): boolean => {
    const newErrors: Record<string, string> = {};

    if (!assetName.trim()) {
      newErrors.assetName = 'Token name is required';
    } else if (assetName.length > 32) {
      newErrors.assetName = 'Token name must be 32 characters or less';
    } else if (cip68Enabled && cip68Supported && assetName.length > 28) {
      // The CIP-67 label costs 4 of the ledger's 32 bytes. Caught here rather than at the
      // backend, which would only reject after the user had walked the rest of the wizard.
      newErrors.assetName =
        'With CIP-68 enabled the name must be 28 characters or less — the CIP-67 label takes 4 of the 32 bytes';
    }

    if (!quantity.trim()) {
      newErrors.quantity = 'Quantity is required';
    } else if (!/^\d+$/.test(quantity)) {
      newErrors.quantity = 'Quantity must be a whole, non-negative number';
    } else if (!allowsZeroSupply && BigInt(quantity) <= 0) {
      newErrors.quantity = 'Quantity must be greater than 0';
    }

    if (recipientAddress.trim() && !recipientAddress.startsWith('addr')) {
      newErrors.recipientAddress = 'Invalid Cardano address format';
    }

    if (cip68Enabled && cip68Supported) {
      // The `maxLength` attributes on the inputs below are a typing convenience, not an
      // enforcement point: they do not apply to state restored from a persisted wizard, nor to a
      // value set programmatically. The ceilings are re-checked here, against the same shared
      // table the Java backend mirrors, so an over-long field is caught before the user pays for
      // anything rather than surfacing as a build failure afterwards.
      const metadataErrors = validateCip68Metadata({
        name: cip68Name,
        description: cip68Description,
        ticker: cip68Ticker,
        decimals: cip68Decimals,
        url: cip68Url,
        logo: cip68Logo,
      });
      // Map the shared field names onto this form's error keys.
      const keyFor: Record<string, string> = {
        name: 'cip68Name',
        description: 'cip68Description',
        ticker: 'cip68Ticker',
        decimals: 'cip68Decimals',
        url: 'cip68Url',
        logo: 'cip68Logo',
      };
      Object.entries(metadataErrors).forEach(([field, message]) => {
        newErrors[keyFor[field] ?? field] = message;
      });
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleChange = (field: keyof TokenDetailsData, value: string) => {
    switch (field) {
      case 'assetName':
        setAssetName(value);
        break;
      case 'quantity':
        setQuantity(value);
        break;
      case 'recipientAddress':
        setRecipientAddress(value);
        break;
    }
    onDataChange({ [field]: value });

    if (errors[field]) {
      setErrors((prev) => ({ ...prev, [field]: '' }));
    }
  };

  // Belt and braces: even if `cip68Enabled` were somehow restored from persisted wizard state
  // for an unsupported flow, nothing is emitted for it.
  const buildCip68Data = () => cip68Enabled && cip68Supported ? {
    enabled: true,
    name: cip68Name.trim(),
    description: cip68Description.trim(),
    ticker: cip68Ticker.trim(),
    decimals: cip68Decimals.trim(),
    url: cip68Url.trim(),
    logo: cip68Logo.trim(),
  } : undefined;

  const handleCip68Toggle = (enabled: boolean) => {
    setCip68Enabled(enabled);
    if (enabled && !cip68NameEdited) {
      setCip68Name(assetName);
    }
  };

  const handleContinue = () => {
    if (!validateForm()) return;

    const cip68Metadata = buildCip68Data();
    const data: TokenDetailsData = {
      assetName: assetName.trim(),
      quantity: quantity.trim(),
      recipientAddress: recipientAddress.trim() || undefined,
      cip68Metadata,
    };

    // Ensure step data includes CIP-68 metadata before completing
    onDataChange({ cip68Metadata });

    onComplete({
      stepId: 'token-details',
      data,
      completedAt: Date.now(),
    });
  };

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">Token Details</h3>
        <p className="text-dark-300 text-sm">
          Define your token&apos;s name, initial supply, and recipient
        </p>
      </div>

      <div className="space-y-4">
        <Input
          label="Token Name"
          value={assetName}
          onChange={(e) => handleChange('assetName', e.target.value)}
          placeholder="e.g., MyToken"
          disabled={isProcessing}
          error={errors.assetName}
          helperText="Human-readable name (max 32 characters)"
        />

        <Input
          label="Initial Supply"
          type="number"
          value={quantity}
          onChange={(e) => handleChange('quantity', e.target.value)}
          placeholder="e.g., 1000000"
          disabled={isProcessing}
          error={errors.quantity}
          helperText={allowsZeroSupply
            ? 'Minted by the registration transaction itself. Enter 0 to register the policy only and mint later from the admin page.'
            : 'Number of tokens to mint during registration'}
        />

        <Input
          label="Recipient Address"
          value={recipientAddress}
          onChange={(e) => handleChange('recipientAddress', e.target.value)}
          placeholder="addr1..."
          disabled={isProcessing}
          error={errors.recipientAddress}
          helperText="Address to receive initial tokens (defaults to your wallet)"
        />
      </div>

      {/* CIP-68 Metadata Section — only for substandards that actually mint the pair */}
      {cip68Supported && (
        <div className="border-t border-dark-700 pt-4">
          <label className="flex items-center gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={cip68Enabled}
              onChange={(e) => handleCip68Toggle(e.target.checked)}
              disabled={isProcessing}
              className="w-4 h-4 rounded border-dark-600 bg-dark-800 text-primary-500 focus:ring-primary-500"
            />
            <div>
              <span className="text-sm font-medium text-white">Enable CIP-68 Metadata</span>
              <p className="text-xs text-dark-400">
                Attach on-chain metadata (name, ticker, decimals, etc.) via a CIP-68 reference token
              </p>
            </div>
          </label>
        </div>
      )}

      {cip68Supported && cip68Enabled && (
        <div className="space-y-4 p-4 bg-dark-800/50 rounded-lg border border-dark-700">
          <Input
            label="Display Name"
            value={cip68Name}
            onChange={(e) => {
              setCip68Name(e.target.value);
              setCip68NameEdited(true);
            }}
            placeholder="e.g., My Token"
            disabled={isProcessing}
            maxLength={CIP68_FIELD_MAX_LENGTHS.name}
            error={errors.cip68Name}
            helperText={`Token display name stored on-chain (required, max ${CIP68_FIELD_MAX_LENGTHS.name} chars)`}
          />

          <Input
            label="Description"
            value={cip68Description}
            onChange={(e) => setCip68Description(e.target.value)}
            placeholder="A brief description of your token"
            disabled={isProcessing}
            maxLength={CIP68_FIELD_MAX_LENGTHS.description}
            error={errors.cip68Description}
            helperText={`Brief description (optional, max ${CIP68_FIELD_MAX_LENGTHS.description} chars)`}
          />

          <div className="grid grid-cols-2 gap-4">
            <Input
              label="Ticker"
              value={cip68Ticker}
              onChange={(e) => setCip68Ticker(e.target.value)}
              placeholder="e.g., MYTKN"
              disabled={isProcessing}
              maxLength={CIP68_FIELD_MAX_LENGTHS.ticker}
              error={errors.cip68Ticker}
              helperText={`Short symbol (max ${CIP68_FIELD_MAX_LENGTHS.ticker} chars)`}
            />
            <Input
              label="Decimals"
              type="number"
              value={cip68Decimals}
              onChange={(e) => setCip68Decimals(e.target.value)}
              disabled={isProcessing}
              error={errors.cip68Decimals}
              helperText="Decimal places (0-19)"
            />
          </div>

          <Input
            label="URL"
            value={cip68Url}
            onChange={(e) => setCip68Url(e.target.value)}
            placeholder="https://..."
            disabled={isProcessing}
            maxLength={CIP68_FIELD_MAX_LENGTHS.url}
            error={errors.cip68Url}
            helperText={`Project website (optional, max ${CIP68_FIELD_MAX_LENGTHS.url} chars)`}
          />

          <Input
            label="Logo URL"
            value={cip68Logo}
            onChange={(e) => setCip68Logo(e.target.value)}
            placeholder="https://..."
            disabled={isProcessing}
            maxLength={CIP68_FIELD_MAX_LENGTHS.logo}
            error={errors.cip68Logo}
            helperText={`Token logo image URL (optional, max ${CIP68_FIELD_MAX_LENGTHS.logo} chars)`}
          />
        </div>
      )}

      <div className="flex gap-3">
        {onBack && (
          <Button
            variant="outline"
            onClick={onBack}
            disabled={isProcessing}
          >
            Back
          </Button>
        )}
        <Button
          variant="primary"
          className="flex-1"
          onClick={handleContinue}
          disabled={isProcessing || !connected}
        >
          Continue
        </Button>
      </div>

      {!connected && (
        <p className="text-sm text-center text-dark-400">
          Connect your wallet to continue
        </p>
      )}
    </div>
  );
}
