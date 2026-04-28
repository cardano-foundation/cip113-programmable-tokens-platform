"use client";

import { useState, useCallback } from 'react';
import { useWallet } from "@/hooks/use-wallet";
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { useToast } from '@/components/ui/use-toast';
import { getSigningEntityVkey } from '@/lib/api/keri';
import { useProtocolVersion } from '@/contexts/protocol-version-context';
import { waitForTxConfirmation } from '@/lib/utils/tx-confirmation';
import type { StepComponentProps } from '@/types/registration';

interface KycConfigData {
  globalStatePolicyId: string;
}

interface GlobalStateInitResponse {
  unsignedCborTx: string;
  metadata: { globalStatePolicyId: string } | null;
  isSuccessful: boolean;
  error: string | null;
}

/**
 * Extract the raw 32-byte Ed25519 public key from a COSE_Key hex string.
 * CIP-30 signData returns the key in COSE_Key format (CBOR map).
 * The marker "215820" = CBOR key -2 (x coordinate of OKP) + bytes(32).
 * Using "215820" is more precise than "5820" alone, which can match elsewhere.
 */
function extractVkeyFromCoseKey(coseKeyHex: string): string | null {
  const marker = '215820';
  const idx = coseKeyHex.indexOf(marker);
  if (idx === -1) return null;
  const vkeyHex = coseKeyHex.substring(idx + marker.length, idx + marker.length + 64);
  if (vkeyHex.length !== 64) return null;
  return vkeyHex;
}

export function KycConfigStep({
  wizardState,
  onDataChange,
  onComplete,
  onBack,
}: StepComponentProps<KycConfigData>) {
  const { wallet, rawApi } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  const [isProcessing, setIsProcessing] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');

  // Derive default mintable amount from the token-details step quantity
  const tokenDetailsData = wizardState.stepStates['token-details']?.data as {
    quantity?: string;
  } | undefined;
  const defaultQuantity = tokenDetailsData?.quantity || '0';

  const [mintableAmount, setMintableAmount] = useState(defaultQuantity);
  const [securityInfo, setSecurityInfo] = useState('');

  // Trusted entities list — pre-populated with own vkey
  const [trustedEntities, setTrustedEntities] = useState<string[]>([]);
  const [ownVkey, setOwnVkey] = useState<string | null>(null);
  const [signingEntityVkey, setSigningEntityVkey] = useState<string | null>(null);
  const [newEntityInput, setNewEntityInput] = useState('');
  const [isLoadingVkey, setIsLoadingVkey] = useState(false);
  const [isLoadingSigningKey, setIsLoadingSigningKey] = useState(false);

  const loadOwnVkey = useCallback(async () => {
    setIsLoadingVkey(true);
    try {
      // CIP-30 signData requires a hex address + hex payload, so use rawApi directly.
      // (The wrapped `wallet` returns bech32 addresses for general use.)
      const cip30 = rawApi as {
        getUsedAddresses(): Promise<string[]>;
        getChangeAddress(): Promise<string>;
        signData(addr: string, payload: string): Promise<{ signature: string; key: string }>;
      } | null;
      if (!cip30) {
        showToast({
          title: 'Wallet not connected',
          description: 'Connect a wallet before configuring KYC.',
          variant: 'error',
        });
        return;
      }
      const usedHex = await cip30.getUsedAddresses();
      const addressHex = usedHex[0] ?? (await cip30.getChangeAddress());
      if (!addressHex) {
        showToast({
          title: 'No wallet address',
          description: 'Could not find a wallet address. Ensure your wallet is connected.',
          variant: 'error',
        });
        return;
      }
      const payloadHex = Buffer.from('CIP113-GLOBAL-STATE-INIT', 'utf-8').toString('hex');
      const dataSignature = await cip30.signData(addressHex, payloadHex);
      const vkey = extractVkeyFromCoseKey(dataSignature.key);
      if (vkey) {
        setOwnVkey(vkey);
        setTrustedEntities(prev =>
          prev.includes(vkey) ? prev : [vkey, ...prev.filter(e => e !== vkey)]
        );
      } else {
        showToast({
          title: 'Could not extract key',
          description: 'Your wallet returned an unexpected format. Add your key manually.',
          variant: 'error',
        });
      }
    } catch (err) {
      console.error('[KycConfigStep] signData error:', err);
      const msg = err instanceof Error ? err.message : String(err);
      const declined =
        msg.toLowerCase().includes('declined') ||
        msg.toLowerCase().includes('rejected') ||
        msg.toLowerCase().includes('cancelled') ||
        msg.toLowerCase().includes('user');
      showToast({
        title: declined ? 'Signing cancelled' : 'Could not load key',
        description: declined
          ? 'You cancelled the signing request. Add your verification key manually below.'
          : `Wallet error: ${msg}. Add your key manually.`,
        variant: 'error',
      });
    } finally {
      setIsLoadingVkey(false);
    }
  }, [wallet, showToast]);

  const loadSigningEntityVkey = useCallback(async () => {
    setIsLoadingSigningKey(true);
    try {
      const response = await getSigningEntityVkey();
      const vkey = response.vkeyHex;
      setSigningEntityVkey(vkey);
      setTrustedEntities(prev =>
        prev.includes(vkey) ? prev : [...prev, vkey]
      );
    } catch (err) {
      console.error('[KycConfigStep] signing entity vkey error:', err);
      showToast({
        title: 'Could not load signing entity key',
        description: err instanceof Error ? err.message : 'Failed to fetch signing entity key from backend.',
        variant: 'error',
      });
    } finally {
      setIsLoadingSigningKey(false);
    }
  }, [showToast]);

  const addEntity = useCallback(() => {
    const vkey = newEntityInput.trim().toLowerCase();
    if (vkey.length !== 64 || !/^[0-9a-f]+$/.test(vkey)) {
      showToast({
        title: 'Invalid Key',
        description: 'Verification key must be exactly 64 hex characters (32 bytes).',
        variant: 'error',
      });
      return;
    }
    if (trustedEntities.includes(vkey)) {
      showToast({ title: 'Duplicate', description: 'This key is already in the list.', variant: 'error' });
      return;
    }
    setTrustedEntities(prev => [...prev, vkey]);
    setNewEntityInput('');
  }, [newEntityInput, trustedEntities, showToast]);

  const removeEntity = useCallback((vkey: string) => {
    setTrustedEntities(prev => prev.filter(e => e !== vkey));
  }, []);

  const handleContinue = useCallback(async () => {
    try {
      setIsProcessing(true);

      // 1. Get admin address
      setStatusMessage('Reading wallet...');
      const addresses = await wallet.getUsedAddresses();
      const adminAddress = addresses[0];

      // 2. Build global state init transaction
      setStatusMessage('Building Global State transaction...');
      const { initGlobalState } = await import('@/lib/api/compliance');

      const response = await initGlobalState(
        {
          substandardId: 'kyc',
          adminAddress,
          initialVkeys: trustedEntities,
          initialTransfersPaused: false,
          initialMintableAmount: mintableAmount ? parseInt(mintableAmount, 10) : 0,
          initialSecurityInfo: securityInfo || undefined,
        },
        selectedVersion?.txHash
      ) as GlobalStateInitResponse;

      if (!response.isSuccessful || !response.unsignedCborTx) {
        showToast({
          title: 'Global State Init Failed',
          description: response.error || 'Failed to build Global State initialization transaction',
          variant: 'error',
        });
        return;
      }

      // 4. Sign and submit
      setStatusMessage('Please sign the transaction...');
      const signedTx = await wallet.signTx(response.unsignedCborTx, true);
      const txHash = await wallet.submitTx(signedTx);

      const globalStatePolicyId = response.metadata?.globalStatePolicyId || '';

      showToast({
        title: 'Global State Submitted',
        description: `Tx: ${txHash.slice(0, 16)}... — waiting for on-chain confirmation`,
        variant: 'success',
      });

      // 5. Wait for Blockfrost to see the transaction before proceeding
      setStatusMessage('Waiting for on-chain confirmation...');
      await waitForTxConfirmation(txHash, {
        pollInterval: 10000,
        timeout: 300000,
        onPoll: (attempt, elapsed) => {
          const elapsedSec = Math.round(elapsed / 1000);
          setStatusMessage(
            `Waiting for on-chain confirmation... (attempt ${attempt}, ${elapsedSec}s elapsed)`
          );
        },
      });

      showToast({
        title: 'Global State Confirmed',
        description: 'On-chain state confirmed and visible. Proceeding to token registration.',
        variant: 'success',
      });

      onDataChange({ globalStatePolicyId });
      onComplete({
        stepId: 'kyc-config',
        data: { globalStatePolicyId },
        completedAt: Date.now(),
      });
    } catch (error) {
      console.error('KYC config error:', error);
      let errorMessage = 'Failed to initialize Global State';
      if (error instanceof Error) {
        errorMessage = error.message.includes('User declined')
          ? 'Transaction was cancelled'
          : error.message;
      }
      showToast({
        title: 'Global State Setup Failed',
        description: errorMessage,
        variant: 'error',
      });
    } finally {
      setIsProcessing(false);
      setStatusMessage('');
    }
  }, [wallet, selectedVersion, showToast, onDataChange, onComplete, mintableAmount, securityInfo, trustedEntities]);

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">Global State Configuration</h3>
        <p className="text-dark-300 text-sm">
          A Global State UTxO will be created on-chain holding the token&apos;s mutable configuration.
          All fields can be updated later via the Admin Panel.
        </p>
      </div>

      <Card className="p-4 space-y-3">
        <h4 className="text-sm font-medium text-white">What the Global State contains</h4>
        <ul className="text-sm text-dark-300 space-y-2">
          <li className="flex items-start gap-2">
            <span className="text-primary-400 font-mono text-xs mt-0.5">transfers_paused</span>
            <span>Controls whether transfers of this token are allowed (starts unpaused)</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary-400 font-mono text-xs mt-0.5">mintable_amount</span>
            <span>Maximum number of tokens that can still be minted</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary-400 font-mono text-xs mt-0.5">trusted_entities</span>
            <span>Ed25519 verification keys of entities authorized to sign KYC attestations</span>
          </li>
          <li className="flex items-start gap-2">
            <span className="text-primary-400 font-mono text-xs mt-0.5">security_info</span>
            <span>Arbitrary compliance/regulation metadata stored on-chain</span>
          </li>
        </ul>
      </Card>

      <Card className="p-4 space-y-4">
        <h4 className="text-sm font-medium text-white">Initial Values</h4>

        <Input
          label="Mintable Amount"
          type="number"
          min="0"
          value={mintableAmount}
          onChange={(e) => setMintableAmount(e.target.value)}
          disabled={isProcessing}
          helperText={`Defaults to the token supply (${defaultQuantity}). Set to 0 for no cap.`}
        />

        <Input
          label="Security Info (hex, optional)"
          value={securityInfo}
          onChange={(e) => setSecurityInfo(e.target.value)}
          placeholder="Leave empty for none"
          disabled={isProcessing}
          helperText="Optional hex-encoded compliance metadata."
        />
      </Card>

      <Card className="p-4 space-y-4">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-medium text-white">Trusted Entities</h4>
          <div className="flex gap-2">
            <Button
              variant="outline"
              onClick={loadSigningEntityVkey}
              disabled={isLoadingSigningKey || isProcessing}
              isLoading={isLoadingSigningKey}
              className="text-xs h-7 px-3"
              title="Load the KERI signing entity's Ed25519 verification key from the backend"
            >
              {isLoadingSigningKey ? 'Loading…' : signingEntityVkey ? 'Reload signing key' : 'Load signing entity key'}
            </Button>
            <Button
              variant="outline"
              onClick={loadOwnVkey}
              disabled={isLoadingVkey || isProcessing}
              isLoading={isLoadingVkey}
              className="text-xs h-7 px-3"
              title="Sign a message with your wallet to extract your Ed25519 verification key"
            >
              {isLoadingVkey ? 'Loading…' : ownVkey ? 'Reload my key' : 'Load my wallet key'}
            </Button>
          </div>
        </div>
        <p className="text-xs text-dark-400">
          Ed25519 verification keys authorized to sign KYC attestations for this token.
          The <span className="text-primary-400">signing entity key</span> is the KERI backend&apos;s key used to sign KYC proofs — it must be in this list for transfers to work.
          Your wallet key may also be added if you want to sign proofs manually.
        </p>

        {isLoadingVkey && trustedEntities.length === 0 ? (
          <div className="flex items-center gap-2 text-xs text-dark-400">
            <div className="h-3.5 w-3.5 border border-primary-500 border-t-transparent rounded-full animate-spin" />
            <span>Loading your wallet verification key…</span>
          </div>
        ) : trustedEntities.length > 0 ? (
          <ul className="space-y-2">
            {trustedEntities.map((vkey) => (
              <li
                key={vkey}
                className={`flex items-center gap-2 rounded px-3 py-2 ${
                  vkey === signingEntityVkey
                    ? 'bg-green-900/40 border border-green-700/50'
                    : vkey === ownVkey
                      ? 'bg-primary-900/40 border border-primary-700/50'
                      : 'bg-dark-800'
                }`}
              >
                <div className="flex-1 min-w-0">
                  {vkey === signingEntityVkey && (
                    <span className="inline-block text-[10px] font-semibold text-green-300 bg-green-800/60 rounded px-1.5 py-0.5 mb-1 mr-1">
                      Signing entity
                    </span>
                  )}
                  {vkey === ownVkey && (
                    <span className="inline-block text-[10px] font-semibold text-primary-300 bg-primary-800/60 rounded px-1.5 py-0.5 mb-1">
                      Your wallet
                    </span>
                  )}
                  <p className="font-mono text-xs text-dark-200 truncate">{vkey}</p>
                </div>
                <button
                  type="button"
                  onClick={() => removeEntity(vkey)}
                  disabled={isProcessing}
                  className="text-dark-400 hover:text-red-400 transition-colors text-xs shrink-0"
                >
                  remove
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-xs text-dark-500 italic">No trusted entities added yet.</p>
        )}

        <div className="flex gap-2">
          <Input
            label=""
            value={newEntityInput}
            onChange={(e) => setNewEntityInput(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); addEntity(); } }}
            placeholder="64-char hex Ed25519 vkey"
            disabled={isProcessing}
            className="flex-1"
          />
          <Button
            variant="outline"
            onClick={addEntity}
            disabled={isProcessing || newEntityInput.trim().length === 0}
            className="self-end"
          >
            Add
          </Button>
        </div>
      </Card>

      {isProcessing && (
        <Card className="p-4">
          <div className="flex items-center gap-3">
            <div className="h-5 w-5 border-2 border-primary-500 border-t-transparent rounded-full animate-spin" />
            <p className="text-sm text-dark-300">{statusMessage}</p>
          </div>
        </Card>
      )}

      <div className="flex gap-3">
        {onBack && (
          <Button variant="outline" onClick={onBack} disabled={isProcessing}>
            Back
          </Button>
        )}
        <Button
          variant="primary"
          className="flex-1"
          onClick={handleContinue}
          isLoading={isProcessing}
          disabled={isProcessing}
        >
          Initialize Global State & Continue
        </Button>
      </div>
    </div>
  );
}
