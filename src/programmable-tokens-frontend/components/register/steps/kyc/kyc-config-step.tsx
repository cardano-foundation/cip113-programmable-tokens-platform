"use client";

import { useState, useCallback } from 'react';
import { useWallet } from "@/hooks/use-wallet";
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { useToast } from '@/components/ui/use-toast';
import { getSigningEntityVkey } from '@/lib/api/keri';
import { getKycExtendedAdminPkh } from '@/lib/api/kyc-extended';
import {
  buildSecurityTokenChain,
  submitTokenChain,
  PowerUserCapability,
} from '@/lib/api/security-token';
import { useProtocolVersion } from '@/contexts/protocol-version-context';
import { waitForTxConfirmation } from '@/lib/utils/tx-confirmation';
import { toCip68Wire } from '@/lib/utils/cip68-wire';
import type { StepComponentProps, CIP68MetadataFormData } from '@/types/registration';

interface KycConfigData {
  globalStatePolicyId: string;
  /** security-token only: the prog-token policy id returned from the chain build. */
  programmableTokenPolicyId?: string;
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
  const isKycExtendedFlow = wizardState.flowId === 'kyc-extended';
  const isSecurityTokenFlow = wizardState.flowId === 'security-token';
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

      const isKycExtended = wizardState.flowId === 'kyc-extended';
      const isSecurityToken = wizardState.flowId === 'security-token';
      const flowSubstandardId = isKycExtended ? 'kyc-extended' : 'kyc';

      // kyc-extended parameterises the global-state script with the BACKEND's
      // signing key PKH so the backend can autonomously sign UpdateMemberRootHash.
      //
      // security-token follows a different model: the on-chain
      // `admin_credential_hash` is the USER's wallet PKH, because BaFin's
      // global_state validator gates every admin action (AddPowerUser,
      // AddTrustedEntity, RotateAdmin, …) on a signature from that key.
      // Using the backend's PKH here would mean only the backend could ever
      // sign admin txs — but the user signs in their wallet, so the tx would
      // fail with `missingSignatories` pointing at the backend's PKH.
      //
      // (Autonomous MPF root sync for security-token is a separate problem
      // left for a follow-up: either the user signs root-hash updates manually,
      // or admin is later delegated to the backend via RotateAdmin.)
      let adminPkh: string | undefined;
      if (isKycExtended) {
        setStatusMessage('Fetching backend admin key…');
        const adminInfo = await getKycExtendedAdminPkh();
        adminPkh = adminInfo.adminPkh;
      } else if (isSecurityToken) {
        const { getPaymentKeyHash } = await import('@/lib/utils/address');
        adminPkh = getPaymentKeyHash(adminAddress);
      }

      // Security-token: chained build + single-popup sign + batched submit.
      //
      // The backend assembles the full 3-tx registration chain in one call
      // (genesis → AddPowerUser → registration), deterministically chained
      // via mempool-visible UTxOs. The wallet signs all three in one CIP-30
      // signTxs popup. The backend submits them sequentially via its own
      // submission service, so the wallet's submission backend (which would
      // typically reject mempool-chained txs) never sees the chain.
      //
      // No initial mint of security tokens happens here — registration sets
      // up the prog-token policy in the CIP-113 directory + registers the
      // substandard's stake credentials. The first actual MintSecurity is
      // a separate admin action once everything is on chain.
      if (isSecurityToken) {
        const tokenDetails = wizardState.stepStates['token-details']?.data as {
          assetName?: string;
          quantity?: string;
          cip68Metadata?: CIP68MetadataFormData;
        } | undefined;
        const allCaps =
          PowerUserCapability.ADMIN |
          PowerUserCapability.MINTER |
          PowerUserCapability.BURNER |
          PowerUserCapability.PAUSER |
          PowerUserCapability.FORCE_TRANSFER;

        // ── Phase 1: build the chain on the backend ──
        setStatusMessage('Phase 1/3 — building the registration chain (genesis + AddPowerUser + register + transferLogic cert)…');
        // Seed the GS datum's trusted_entity_vkeys with the wizard's chosen
        // trusted entities (the kyc-config step lets the admin add/remove
        // them). Almost always includes this backend's KERI signing-entity
        // vkey — required so KYC proofs issued by this backend verify on
        // chain immediately. Without this, the admin would have to run a
        // separate AddTrustedEntity GS-update tx before the first transfer.
        const initialTrustedEntityVkeys = trustedEntities.length > 0
          ? trustedEntities
          : (signingEntityVkey ? [signingEntityVkey] : []);

        const chain = await buildSecurityTokenChain({
          feePayerAddress: adminAddress,
          assetName: tokenDetails?.assetName
            ? Buffer.from(tokenDetails.assetName, 'utf8').toString('hex')
            : '',
          adminPubKeyHash: adminPkh!,
          // Defaults to ON per BaFin's compliance posture: recipients must
          // hold a fresh KYC attestation to receive tokens. The admin can
          // toggle this off later from the admin page via the
          // SetRequiresReceiverKyc global-state action.
          requiresReceiverKyc: true,
          initialMintableAmount: mintableAmount ? parseInt(mintableAmount, 10) : 0,
          bootstrapPowerUserPkh: adminPkh,
          bootstrapPowerUserCapabilities: allCaps,
          bootstrapPowerUserLabel: 'Bootstrap admin',
          initialTrustedEntityVkeys,
          // Genesis labels the security asset name and bakes it into the scripts. The (100)
          // reference token cannot be minted here — the registration path rejects a second
          // asset name under the policy — so the pair is completed by the first mint from the
          // admin page, which reads this same metadata back off the registration.
          cip68Metadata: toCip68Wire(tokenDetails?.cip68Metadata),
          // Initial supply minted at registration (directory-mint validator requires
          // a non-zero prog-token mint). The BaFin mintingLogic.withdraw runs in
          // its registration-mode rubber-stamp branch — no GS spend / supply-cap
          // enforcement at registration. Subsequent mints go through MintSecurity.
          quantity: tokenDetails?.quantity || '1',
        });

        // ── Phase 2: single wallet popup signs all txs in the chain ──
        // 4-tx shape when the backend included the transferLogic RegCert
        // (it's optional but on by default). All txs go through the same
        // CIP-103 batch so Eternl signs them as a single popup — including
        // the script-cred RegCert that Eternl refuses via single-tx signTx.
        const unsignedCbors = [
          chain.genesisCborHex,
          chain.addPowerUserCborHex,
          chain.registrationCborHex,
          ...(chain.registerTransferLogicCborHex ? [chain.registerTransferLogicCborHex] : []),
        ];
        const totalTxs = unsignedCbors.length;
        setStatusMessage(`Phase 2/3 — please sign all ${totalTxs} transactions in a single wallet popup…`);
        let signedCbors: string[];
        try {
          // CIP-30 signTxs (CIP-103). Most modern wallets (Eternl, Lace, Nami) support it.
          signedCbors = await wallet.signTxs(unsignedCbors, true);
        } catch (batchErr) {
          // Fallback: sequential signTx (one popup per tx). Surface the reason
          // so we know to keep the batch path primary.
          console.warn('[security-token] signTxs batch failed, falling back to sequential signTx:',
            (batchErr as Error)?.message);
          setStatusMessage(`Phase 2/3 — wallet doesn't support batch sign, falling back to ${totalTxs} popups…`);
          signedCbors = [];
          for (const cbor of unsignedCbors) {
            signedCbors.push(await wallet.signTx(cbor, true));
          }
        }

        // ── Phase 3: backend submits the chain sequentially (mempool-chained) ──
        setStatusMessage(`Phase 3/3 — submitting ${totalTxs} chained transactions to the network…`);
        const submitResp = await submitTokenChain(signedCbors);
        const submitted = submitResp.txHashes ?? [];
        if (submitted.length < totalTxs || submitResp.error) {
          showToast({
            title: 'Chain submission incomplete',
            description: submitResp.error
              ? `Submitted ${submitted.length}/${totalTxs} — ${submitResp.error}`
              : `Only ${submitted.length}/${totalTxs} transactions accepted by the network.`,
            variant: 'error',
          });
        } else {
          showToast({
            title: 'Security token registered',
            description: `All ${submitted.length} transactions submitted: ${submitted.map(h => h.slice(0, 8)).join(', ')}…`,
            variant: 'default',
          });
        }

        // Persist on BOTH .data and .result paths so downstream steps (e.g. the
        // success step) can read globalStatePolicyId via onDataChange.
        onDataChange({
          globalStatePolicyId: chain.globalStatePolicyId,
          programmableTokenPolicyId: chain.programmableTokenPolicyId,
        });
        onComplete({
          stepId: 'kyc-config',
          data: {
            globalStatePolicyId: chain.globalStatePolicyId,
            programmableTokenPolicyId: chain.programmableTokenPolicyId,
            denylistPolicyId: chain.denylistPolicyId,
            powerUsersPolicyId: chain.powerUsersPolicyId,
            mintableAmount,
            securityInfo,
            trustedEntities,
            chainTxHashes: submitted,
          },
          txHash: chain.registrationTxHash,
          completedAt: Date.now(),
        });
        return;
      }

      const response = await initGlobalState(
        {
          substandardId: flowSubstandardId,
          adminAddress,
          adminPkh,
          initialVkeys: trustedEntities,
          initialTransfersPaused: false,
          initialMintableAmount: mintableAmount ? parseInt(mintableAmount, 10) : 0,
          initialSecurityInfo: securityInfo || undefined,
        },
        selectedVersion?.txHash
      ) as GlobalStateInitResponse;

      if (!response.isSuccessful || !response.unsignedCborTx) {
        showToast({
          title: 'Global state initialization failed',
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
        title: 'Global state submitted',
        description: `Tx: ${txHash.slice(0, 16)}… — waiting for on-chain confirmation`,
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
        title: 'Global state confirmed',
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
        title: 'Global state setup failed',
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

      {isSecurityTokenFlow && (
        <Card className="p-4 space-y-3 border border-primary-700/40 bg-primary-900/10">
          <h4 className="text-sm font-medium text-white">What happens when you click &ldquo;Initialize&rdquo;</h4>
          <p className="text-sm text-dark-300">
            Setting up a BaFin-style security token chains <span className="text-white">four transactions</span>.
            The backend builds them all up-front, your wallet signs them as a single batch (CIP-103), and the
            backend submits them sequentially without waiting for confirmations.
          </p>
          <ol className="text-sm text-dark-300 space-y-2 list-decimal list-inside">
            <li>
              <span className="text-white">Genesis</span> — mints three NFTs and registers the minting-logic stake credential:
              <ul className="ml-5 mt-1 list-disc list-inside text-xs text-dark-400 space-y-0.5">
                <li><span className="font-mono text-primary-400">GlobalState NFT</span> — carries the configuration datum below</li>
                <li><span className="font-mono text-primary-400">Denylist root NFT</span> — sentinel for the blocked-recipients list (starts empty)</li>
                <li><span className="font-mono text-primary-400">Power-users root NFT</span> — sentinel for the role-holders list (starts empty)</li>
              </ul>
            </li>
            <li>
              <span className="text-white">AddPowerUser</span> — inserts your wallet into the power-users list with
              all 5 capabilities (admin, mint, burn, pause, force-transfer).
            </li>
            <li>
              <span className="text-white">Registration</span> — inserts the policy into the CIP-113 directory and mints the initial token supply.
            </li>
            <li>
              <span className="text-white">TransferLogic cert</span> — registers the transfer-logic stake credential (Conway RegCert) so the first transfer can issue a withdraw-0.
            </li>
          </ol>
          <p className="text-xs text-dark-400">
            Each tx feeds the next via mempool chaining — no on-chain confirmation pauses. If your wallet
            doesn&apos;t support CIP-103 batch signing, the flow falls back to one popup per transaction.
          </p>
        </Card>
      )}

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
          {(isKycExtendedFlow || isSecurityTokenFlow) && (
            <li className="flex items-start gap-2">
              <span className="text-primary-400 font-mono text-xs mt-0.5">member_root_hash</span>
              <span>Blake2b-256 root of the Merkle Patricia Forestry allowlist. Updated automatically by the backend whenever a user completes KYC. Transfers to recipients not in the tree are rejected on-chain.</span>
            </li>
          )}
          {isSecurityTokenFlow && (
            <>
              <li className="flex items-start gap-2">
                <span className="text-primary-400 font-mono text-xs mt-0.5">admin_credential_hash</span>
                <span>The pub-key hash of the BaFin admin (you). Gates rotate-admin and direct denylist updates.</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-primary-400 font-mono text-xs mt-0.5">power_user_linked_list_policy_id</span>
                <span>Policy of the linked list whose nodes are the role-holders (admin/mint/burn/pause/force).</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-primary-400 font-mono text-xs mt-0.5">denylist_linked_list_policy_id</span>
                <span>Policy of the linked list of blocked recipient pkhs. Transfers to anyone on the list are rejected on-chain.</span>
              </li>
              <li className="flex items-start gap-2">
                <span className="text-primary-400 font-mono text-xs mt-0.5">requires_receiver_kyc</span>
                <span>If on, recipients must hold a fresh KYC attestation to receive tokens. Toggle later from the admin page.</span>
              </li>
            </>
          )}
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
          {isSecurityTokenFlow ? 'Register Security Token' : 'Initialize Global State & Continue'}
        </Button>
      </div>
    </div>
  );
}
