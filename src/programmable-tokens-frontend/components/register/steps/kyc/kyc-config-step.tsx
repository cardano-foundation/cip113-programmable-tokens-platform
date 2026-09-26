"use client";

import { useState, useCallback, useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useWallet } from "@/hooks/use-wallet";
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { useToast } from '@/components/ui/use-toast';
import { getSigningEntityVkey } from '@/lib/api/keri';
import { getKycExtendedAdminPkh } from '@/lib/api/kyc-extended';
import {
  buildRwaTokenChain,
  submitTokenChain,
  getTokenChainStatus,
  parseSubmitChainFailure,
  PowerUserCapability,
  type RwaTokenInitRequest,
  type RwaTokenChainBuildResponse,
  type ChainObservation,
} from '@/lib/api/rwa-token';
import { InitialMintAttestationPanel } from './initial-mint-attestation-panel';
import { getInitialMintAttestation, getInitialMintRecovery } from '@/lib/api/rwa-initial-attestation';
import { useProtocolVersion } from '@/contexts/protocol-version-context';
import { waitForTxConfirmation } from '@/lib/utils/tx-confirmation';
import { toCip68Wire } from '@/lib/utils/cip68-wire';
import type { StepComponentProps, CIP68MetadataFormData } from '@/types/registration';
import { logError } from '@/lib/utils/error-message';
import { mintRecoveryStorage, scanWalletInitialMintAttempts,
  removeInitialMintAttempt, withRegistrationLock,
  type SavedInitialMintAttempt } from '@/lib/rwa/mint-recovery-storage';
import { canBuildInitialRegistration, initialRegistrationAction } from '@/lib/rwa/initial-mint-draft';
import { payerAddressHex } from '@/lib/rwa/creation-auth';
import { parseInitialMintCap } from '@/lib/rwa/initial-mint-cap';
import { currentRegistrationRun, assertRegistrationRun } from '@/lib/rwa/registration-run';
import { useRegistrationWizard } from '@/contexts/registration-wizard-context';
import { allChainTransactionsConfirmed, canResumeRegistrationChain,
  validateChainObservations, type SubmissionPhase } from '@/lib/rwa/registration-chain-status';

interface PendingRegistrationChain {
  chain: RwaTokenChainBuildResponse;
  signedCbors: string[];
  expectedHashes: string[];
  names: string[];
  phase: SubmissionPhase;
  acceptedCount: number;
  startedAt: number;
  detail: string;
  observations: ChainObservation[];
  intentId?: string;
  approvedRegistration?: RwaTokenInitRequest;
  completeCurrentWizard: boolean;
}

interface KycConfigData {
  globalStatePolicyId: string;
  /** rwa-token only: the prog-token policy id returned from the chain build. */
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
  const isRwaTokenFlow = wizardState.flowId === 'rwa-token';
  const { wallet, rawApi, connected } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();
  const run = useRef(currentRegistrationRun());
  const { setRegistrationNavigationLocked } = useRegistrationWizard();

  const [isProcessing, setIsProcessing] = useState(false);
  const [statusMessage, setStatusMessage] = useState('');
  const [pendingChain, setPendingChain] = useState<PendingRegistrationChain | null>(null);
  const pendingChainRef = useRef<PendingRegistrationChain | null>(null);
  pendingChainRef.current = pendingChain;
  const pendingFinalizing = useRef(false);
  const pendingChecking = useRef(false);
  const holdPendingChain = useCallback((saved: PendingRegistrationChain) => {
    pendingChainRef.current = saved;
    setPendingChain(saved);
    setRegistrationNavigationLocked(true);
  }, [setRegistrationNavigationLocked]);
  const [approveInitialMint, setApproveInitialMint] = useState(false);
  const [attestationActive, setAttestationActive] = useState(false);
  const [restoredAttempt, setRestoredAttempt] = useState(false);
  const [attestationModalOpen, setAttestationModalOpen] = useState(false);
  const [readyAttestation, setReadyAttestation] = useState<{
    chain: RwaTokenChainBuildResponse; intentId: string; registration: RwaTokenInitRequest;
  } | null>(null);
  const [frozenRegistration, setFrozenRegistration] = useState<RwaTokenInitRequest | null>(null);
  const [attestationPayer, setAttestationPayer] = useState('');
  const [newPayerAddress, setNewPayerAddress] = useState('');
  const [savedAttempts, setSavedAttempts] = useState<SavedInitialMintAttempt[]>([]);
  const [selectedSavedAttempt, setSelectedSavedAttempt] = useState<SavedInitialMintAttempt | undefined>();
  const [currentAttempt, setCurrentAttempt] = useState<SavedInitialMintAttempt | undefined>();
  const [panelResumeAttempt, setPanelResumeAttempt] = useState<SavedInitialMintAttempt | undefined>();
  const [activeAttemptId, setActiveAttemptId] = useState<string | null>(null);
  const lastPayerHexRef = useRef<string | null>(null);
  const [recoveryLoaded, setRecoveryLoaded] = useState(false);
  const [recoveryError, setRecoveryError] = useState<string | null>(null);
  const [recoveryCheck, setRecoveryCheck] = useState(0);
  const attestationChoiceRef = useRef<HTMLInputElement>(null);
  const attestationResumeRef = useRef<HTMLButtonElement>(null);

  const tokenDetailsData = wizardState.stepStates['token-details']?.data as {
    quantity?: string;
  } | undefined;
  const defaultQuantity = tokenDetailsData?.quantity || '0';

  useEffect(() => {
    if (!isRwaTokenFlow) { setRecoveryLoaded(true); return; }
    let active = true;
    setRecoveryLoaded(false);
    setRecoveryError(null);
    if (!connected) {
      lastPayerHexRef.current = null;
      setNewPayerAddress('');
      setSavedAttempts([]);
      setRestoredAttempt(false);
      setRecoveryError('Connect your wallet to check for a saved registration attempt');
      setRecoveryLoaded(true);
      return;
    }
    (async () => {
      try {
        const { payerAddress: firstPayer, addresses, attempts: found } = await scanWalletInitialMintAttempts(
          wallet, payerAddressHex);
        const payer = addresses.find(address => payerAddressHex(address) === lastPayerHexRef.current)
          ?? firstPayer;
        const finished: SavedInitialMintAttempt[] = [];
        for (const attempt of found) {
          if (attempt.intentId === activeAttemptId) continue;
          try {
            const view = await getInitialMintAttestation(attempt.intentId, attempt.sessionId);
            if (['RELEASED', 'CANCELED', 'ARCHIVED_EXPIRED'].includes(view.status)) {
              finished.push(attempt);
            } else if (view.status === 'BUILT') {
              const recovery = await getInitialMintRecovery(attempt.intentId, attempt.sessionId);
              if (recovery.status === 'CONFIRMED') finished.push(attempt);
            }
          } catch { /* Unknown status is still blocking. */ }
        }
        if (finished.length) await withRegistrationLock(async () => {
          const current = await scanWalletInitialMintAttempts(wallet, payerAddressHex);
          for (const attempt of finished) {
            if (current.attempts.some(record => record.intentId === attempt.intentId &&
                record.payerHex === attempt.payerHex)) removeInitialMintAttempt(attempt.intentId);
          }
        });
        const { attempts } = await scanWalletInitialMintAttempts(wallet, payerAddressHex);
        if (!active) return;
        const payerHex = payerAddressHex(payer);
        if (lastPayerHexRef.current !== payerHex) {
          lastPayerHexRef.current = payerHex;
          setMintableAmount(defaultQuantity);
          setSecurityInfo('');
          setRequiresReceiverKyc(true);
          setSeedRecipientInAllowlist(false);
          setTrustedEntities([]);
          setApproveInitialMint(false);
          setAttestationActive(false);
          setAttestationModalOpen(false);
          setReadyAttestation(null);
          setFrozenRegistration(null);
          setAttestationPayer('');
          setSelectedSavedAttempt(undefined);
          setCurrentAttempt(undefined);
          setPanelResumeAttempt(undefined);
          setActiveAttemptId(null);
        }
        const previous = attempts.filter(attempt => attempt.intentId !== activeAttemptId);
        if (selectedSavedAttempt && !previous.some(attempt =>
            attempt.intentId === selectedSavedAttempt.intentId)) {
          setSelectedSavedAttempt(undefined);
          setReadyAttestation(null);
          setAttestationActive(false);
          setAttestationModalOpen(false);
          setFrozenRegistration(null);
          setAttestationPayer('');
        }
        setNewPayerAddress(payer);
        setSavedAttempts(previous);
        setRestoredAttempt(previous.length > 0);
        setRecoveryError(null);
      } catch (error) {
        if (active) setRecoveryError(error instanceof Error ? error.message : 'Could not check saved initial mint');
      } finally { if (active) setRecoveryLoaded(true); }
    })();
    return () => { active = false; };
  }, [wallet, connected, isRwaTokenFlow, recoveryCheck, defaultQuantity,
    activeAttemptId, selectedSavedAttempt]);

  useEffect(() => {
    const refresh = (event: StorageEvent) => {
      if (event.key?.startsWith('rwa-initial-mint-'))
        setRecoveryCheck(value => value + 1);
    };
    window.addEventListener('storage', refresh);
    return () => window.removeEventListener('storage', refresh);
  }, []);

  const [mintableAmount, setMintableAmount] = useState(defaultQuantity);
  const [securityInfo, setSecurityInfo] = useState('');

  // Supply minted BY the registration transaction itself — NOT a field of this
  // step. The user already entered it one step earlier, as token-details'
  // "Initial Supply"; asking again here was the same number collected twice, with
  // nothing keeping the two in sync. This is the value the backend actually
  // honours (`initialMintQuantity`); the request's `quantity` field is ignored by
  // the chained registration, which overwrites it.
  const initialMintQuantity = defaultQuantity;

  // BaFin's compliance posture is receiver-KYC ON, but with it on the token's
  // first mint cannot be folded into the registration: the minting logic wants a
  // membership proof against `member_root_hash`, genesis writes that root EMPTY,
  // and no root can be published beforehand because the policy id does not exist
  // until genesis picks its bootstrap UTxO. So it has to be the issuer's call,
  // not a hardcoded default that silently makes the mint unbuildable.
  const [requiresReceiverKyc, setRequiresReceiverKyc] = useState(true);

  // OPT-IN, and deliberately off by default. Enrolls the mint recipient in the
  // token's compliance allowlist at genesis so that receiver-KYC and a first mint
  // can coexist — see the checkbox copy below for what that asserts on chain.
  const [seedRecipientInAllowlist, setSeedRecipientInAllowlist] = useState(false);

  // Trusted entities list — pre-populated with own vkey
  const [trustedEntities, setTrustedEntities] = useState<string[]>([]);
  const [ownVkey, setOwnVkey] = useState<string | null>(null);
  const [signingEntityVkey, setSigningEntityVkey] = useState<string | null>(null);
  const [newEntityInput, setNewEntityInput] = useState('');
  const [isLoadingVkey, setIsLoadingVkey] = useState(false);
  const [isLoadingSigningKey, setIsLoadingSigningKey] = useState(false);

  const displayedMintableAmount = mintableAmount;
  const displayedRequiresReceiverKyc = requiresReceiverKyc;
  const displayedSeedRecipient = seedRecipientInAllowlist;
  const displayedTrustedEntities = trustedEntities;

  const trimmedMint = initialMintQuantity.trim();
  const trimmedCap = displayedMintableAmount.trim();
  const mintQty = /^\d+$/.test(trimmedMint) ? BigInt(trimmedMint) : null;
  const capValue = parseInitialMintCap(trimmedCap);
  const capInvalid = capValue === null;
  const capQty = capValue === null ? null : BigInt(capValue);
  const mintQtyInvalid = trimmedMint !== '' && mintQty === null;
  // BigInt(0), not a `0n` literal: tsconfig targets below ES2020.
  const willFirstMint = mintQty !== null && mintQty > BigInt(0);
  // Mirrors the backend's up-front refusals in buildFullRegistrationChain, so the
  // wizard never starts a chain whose phase 1 would persist rows for a token that
  // can never be registered.
  // …unless the issuer opted into the genesis allowlist seed, which writes a real
  // member_root_hash covering the recipient into the same genesis datum and so makes
  // the ordinary membership-proof path satisfiable.
  const mintBlockedByKyc = willFirstMint && displayedRequiresReceiverKyc && !displayedSeedRecipient;
  const mintExceedsCap = willFirstMint && capQty !== null && mintQty > capQty;
  const registrationBlocked =
    isRwaTokenFlow && (mintQtyInvalid || capInvalid || mintBlockedByKyc || mintExceedsCap);

  // Clear the opt-in the moment it stops being applicable. The checkbox only renders
  // while receiver KYC is on AND something is being minted; without this, ticking it
  // and then turning receiver KYC off would keep sending `true` from an invisible
  // control — silently writing an unverified compliance assertion into the datum that
  // goes live as soon as an admin later runs SetRequiresReceiverKyc.
  useEffect(() => {
    if (!(requiresReceiverKyc && willFirstMint) && seedRecipientInAllowlist) {
      setSeedRecipientInAllowlist(false);
    }
  }, [requiresReceiverKyc, willFirstMint, seedRecipientInAllowlist]);

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
  }, [rawApi, showToast]);

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

  const makeRwaRegistration = useCallback(async (validate = true): Promise<RwaTokenInitRequest> => {
    if (validate && registrationBlocked) throw new Error(mintQtyInvalid
      ? 'Initial supply must be a whole, non-negative number.'
      : capInvalid ? 'Mintable amount must be a whole number from 0 to 9,007,199,254,740,991.'
      : mintBlockedByKyc
        ? 'Receiver KYC with an initial mint requires enrolling the recipient at genesis.'
        : 'Initial supply cannot exceed the mintable amount.');
    const adminAddress = newPayerAddress;
    if (!recoveryLoaded || !adminAddress) throw new Error('Wallet recovery check has not completed');
    const { getPaymentKeyHash } = await import('@/lib/utils/address');
    const adminPkh = getPaymentKeyHash(adminAddress);
    if (!adminPkh) throw new Error('Could not determine the wallet payment key hash');
    const tokenDetails = wizardState.stepStates['token-details']?.data as {
      assetName?: string; cip68Metadata?: CIP68MetadataFormData; recipientAddress?: string;
    } | undefined;
    return {
      feePayerAddress: adminAddress,
      assetName: tokenDetails?.assetName ? Buffer.from(tokenDetails.assetName, 'utf8').toString('hex') : '',
      adminPubKeyHash: adminPkh,
      requiresReceiverKyc,
      initialMintableAmount: capValue ?? 0,
      bootstrapPowerUserPkh: adminPkh,
      bootstrapPowerUserCapabilities: PowerUserCapability.ADMIN | PowerUserCapability.MINTER |
        PowerUserCapability.BURNER | PowerUserCapability.PAUSER | PowerUserCapability.FORCE_TRANSFER,
      bootstrapPowerUserLabel: 'Bootstrap admin',
      initialTrustedEntityVkeys: trustedEntities,
      cip68Metadata: toCip68Wire(tokenDetails?.cip68Metadata),
      initialMintQuantity: trimmedMint || '0',
      recipientAddress: tokenDetails?.recipientAddress || undefined,
      seedRecipientInAllowlistAtGenesis: seedRecipientInAllowlist,
    };
  }, [newPayerAddress, recoveryLoaded, wizardState, registrationBlocked, mintQtyInvalid, capInvalid, capValue, mintBlockedByKyc,
    requiresReceiverKyc, trustedEntities, trimmedMint, seedRecipientInAllowlist]);

  const openAttestation = useCallback(async () => {
    try {
      if (restoredAttempt) throw new Error('Resolve the previous saved registration before preparing a new one');
      if (!recoveryLoaded) throw new Error('Wallet recovery check has not completed');
      const request = attestationActive ? frozenRegistration : await makeRwaRegistration(false);
      assertRegistrationRun(run.current);
      if (!request) throw new Error('Saved registration settings are unavailable');
      setSelectedSavedAttempt(undefined);
      setPanelResumeAttempt(currentAttempt);
      setFrozenRegistration(request);
      setAttestationPayer(request.feePayerAddress);
      setApproveInitialMint(true);
      setAttestationModalOpen(true);
    } catch (error) {
      showToast({ title: 'Cannot open Veridian approval',
        description: error instanceof Error ? error.message : String(error), variant: 'error' });
    }
  }, [restoredAttempt, recoveryLoaded, attestationActive, frozenRegistration,
    currentAttempt, makeRwaRegistration, showToast]);

  const completeRwaChain = useCallback(async (saved: PendingRegistrationChain) => {
    assertRegistrationRun(run.current);
    const { chain, expectedHashes, names, approvedRegistration, completeCurrentWizard, intentId } = saved;
    if (intentId) {
      await withRegistrationLock(async () => { removeInitialMintAttempt(intentId); });
      assertRegistrationRun(run.current);
      const key = `rwa-initial-mint-submitting-${intentId}`;
      for (const suffix of ['', '-signed', '-chain', '-cursor']) mintRecoveryStorage.removeItem(`${key}${suffix}`);
      setReadyAttestation(null);
      setActiveAttemptId(null);
      setCurrentAttempt(undefined);
      setPanelResumeAttempt(undefined);
      setAttestationActive(false);
      setRestoredAttempt(false);
      setFrozenRegistration(null);
      setApproveInitialMint(false);
      if (!completeCurrentWizard) { setRecoveryLoaded(false); setRecoveryCheck(value => value + 1); }
    }
    pendingChainRef.current = null;
    setPendingChain(null);
    setRegistrationNavigationLocked(false);
    if (completeCurrentWizard) onDataChange({
      globalStatePolicyId: chain.globalStatePolicyId,
      programmableTokenPolicyId: chain.programmableTokenPolicyId,
    });
    showToast({
      title: completeCurrentWizard ? 'RWA token registered' : 'Previous RWA registration completed',
      description: `All ${expectedHashes.length} transactions confirmed: ${expectedHashes.map(h => h.slice(0, 8)).join(', ')}…`,
      variant: 'default',
    });
    if (completeCurrentWizard) onComplete({
      stepId: 'kyc-config',
      data: {
        globalStatePolicyId: chain.globalStatePolicyId,
        programmableTokenPolicyId: chain.programmableTokenPolicyId,
        denylistPolicyId: chain.denylistPolicyId,
        powerUsersPolicyId: chain.powerUsersPolicyId,
        mintableAmount: approvedRegistration
          ? String(approvedRegistration.initialMintableAmount ?? 0) : mintableAmount,
        initialMintQuantity: approvedRegistration?.initialMintQuantity ?? (trimmedMint || '0'),
        requiresReceiverKyc: approvedRegistration?.requiresReceiverKyc ?? requiresReceiverKyc,
        securityInfo,
        trustedEntities: approvedRegistration?.initialTrustedEntityVkeys ?? trustedEntities,
        chainTxHashes: expectedHashes,
        chainTxHashesByName: Object.fromEntries(names.map((name, i) => [name, expectedHashes[i]])),
      },
      txHash: chain.registrationTxHash,
      completedAt: Date.now(),
    });
  }, [onDataChange, onComplete, showToast, mintableAmount, trimmedMint, requiresReceiverKyc,
    securityInfo, trustedEntities, setRegistrationNavigationLocked]);

  const submitRwaChain = useCallback(async (chain: RwaTokenChainBuildResponse,
                                        onSubmissionStart?: () => void, intentId?: string,
                                        approvedRegistration?: RwaTokenInitRequest,
                                        completeCurrentWizard = true) => {
      assertRegistrationRun(run.current);
      // ── Phase 2: single wallet popup signs all txs in the chain ──
      // The backend includes two required CIP-171 provenance transactions.
      // (it's optional but on by default). All txs go through the same
      // CIP-103 batch so Eternl signs them as a single popup — including
      // the script-cred RegCert that Eternl refuses via single-tx signTx.
      //
      // ORDER IS LOAD-BEARING: each tx spends the previous one's change output, so
      // the backend submits them in exactly this sequence. publishScripts appears
      // only on the mint path — it publishes minting_logic and the global_state
      // spend validator as reference scripts, without which the registration tx's
      // five inline validators are 16 584 bytes against a 16 384-byte limit.
      const chainTxs: { name: string; cbor: string }[] = [
        { name: 'genesis', cbor: chain.genesisCborHex },
        { name: 'addPowerUser', cbor: chain.addPowerUserCborHex },
        { name: 'cmtaProvenance', cbor: chain.cmtaProvenanceCborHex },
        { name: 'issuanceProvenance', cbor: chain.issuanceProvenanceCborHex },
        ...(chain.publishScriptsCborHex
          ? [{ name: 'publishScripts', cbor: chain.publishScriptsCborHex }]
          : []),
        { name: 'registration', cbor: chain.registrationCborHex },
        ...(chain.attestationCborHex
          ? [{ name: 'attestation', cbor: chain.attestationCborHex }]
          : []),
        ...(chain.registerTransferLogicCborHex
          ? [{ name: 'registerTransferLogic', cbor: chain.registerTransferLogicCborHex }]
          : []),
        // Registers the third-party transfer-logic reward account, which the BURN
        // withdraws 0 from. A different script from the one above, needed by a
        // different operation — without it the first burn is rejected at submit.
        ...(chain.registerThirdPartyTransferLogicCborHex
          ? [{
              name: 'registerThirdPartyTransferLogic',
              cbor: chain.registerThirdPartyTransferLogicCborHex,
            }]
          : []),
      ];
      const unsignedCbors = chainTxs.map(t => t.cbor);
      const totalTxs = unsignedCbors.length;
      const submissionKey = intentId ? `rwa-initial-mint-submitting-${intentId}` : null;
      const savedSignatures = submissionKey && mintRecoveryStorage.getItem(`${submissionKey}-signed`);
      setStatusMessage(`Phase 2/3 — please sign ${totalTxs} registration transactions (batch signing where supported)…`);
      const signedCbors = savedSignatures ? JSON.parse(savedSignatures) as string[]
        : await wallet.signTxs(unsignedCbors, true);
      assertRegistrationRun(run.current);
      if (!Array.isArray(signedCbors) || signedCbors.length !== totalTxs ||
          signedCbors.some(cbor => typeof cbor !== 'string' || !/^(?:[0-9a-fA-F]{2})+$/.test(cbor)))
        throw new Error('Saved registration signatures are invalid; inspect this attempt before resuming');
      if (submissionKey && !savedSignatures)
        mintRecoveryStorage.setItem(`${submissionKey}-signed`, JSON.stringify(signedCbors));
      // Replay the complete immutable signed chain. The backend skips only
      // transactions it can positively confirm in a block; a stored cursor is
      // useful for display, but cannot prove earlier mempool acceptance survived.
      const cursor = 0;
      const expectedHashes = [chain.genesisTxHash, chain.addPowerUserTxHash,
        chain.cmtaProvenanceTxHash, chain.issuanceProvenanceTxHash,
        ...(chain.publishScriptsTxHash ? [chain.publishScriptsTxHash] : []),
        chain.registrationTxHash,
        ...(chain.attestationTxHash ? [chain.attestationTxHash] : []),
        ...(chain.registerTransferLogicTxHash ? [chain.registerTransferLogicTxHash] : []),
        ...(chain.registerThirdPartyTransferLogicTxHash ? [chain.registerThirdPartyTransferLogicTxHash] : [])];
      if (expectedHashes.length !== totalTxs)
        throw new Error('Saved registration chain hashes do not match its transactions');

      const pendingBase: PendingRegistrationChain = {
        chain, signedCbors, expectedHashes, names: chainTxs.map(t => t.name),
        phase: 'lost', acceptedCount: 0, startedAt: Date.now(), detail: '', observations: [],
        intentId, approvedRegistration, completeCurrentWizard,
      };

      // ── Phase 3: backend submits the chain sequentially (mempool-chained) ──
      setStatusMessage(`Phase 3/3 — submitting ${totalTxs} chained transactions to the network…`);
      onSubmissionStart?.();
      assertRegistrationRun(run.current);
      let submitResp: Awaited<ReturnType<typeof submitTokenChain>>;
      try {
        submitResp = await submitTokenChain(signedCbors);
        assertRegistrationRun(run.current);
      } catch (failure) {
        assertRegistrationRun(run.current);
        const detail = parseSubmitChainFailure(failure);
        const reported = detail.txHashes;
        const matching = reported.every((hash, index) =>
          hash.toLowerCase() === expectedHashes[cursor + index]?.toLowerCase());
        if (!matching || (detail.failedTxHash && detail.failedIndex !== undefined &&
            detail.failedTxHash.toLowerCase() !== expectedHashes[detail.failedIndex]?.toLowerCase())) {
          holdPendingChain({ ...pendingBase, phase: 'conflict',
            detail: 'Submission reported hashes outside this registration chain. Do not retry delivery.' });
          return false;
        }
        if (submissionKey) {
          if (detail.failedIndex !== undefined &&
              detail.failedIndex === reported.length && reported.length < totalTxs) {
            mintRecoveryStorage.setItem(`${submissionKey}-cursor`, String(cursor + reported.length));
          }
        }
        const partial = detail.failedIndex !== undefined &&
          detail.failedIndex === reported.length && reported.length > 0 && reported.length < totalTxs;
        holdPendingChain({ ...pendingBase, phase: partial ? 'partial' : 'lost',
          acceptedCount: partial ? reported.length : 0,
          detail: detail.error || 'Submission response was unavailable; check the exact transaction hashes.' });
        return false;
      }
      if (submitResp.error || submitResp.txHashes.length !== totalTxs - cursor ||
          !submitResp.txHashes.every((hash, index) =>
            hash.toLowerCase() === expectedHashes[index]?.toLowerCase())) {
        holdPendingChain({ ...pendingBase, phase: 'conflict',
          detail: 'Submission returned unexpected transaction hashes. Do not retry delivery.' });
        return false;
      }
      if (submitResp.confirmed === true) {
        await completeRwaChain(pendingBase);
        return true;
      }
      holdPendingChain({ ...pendingBase, phase: 'accepted', acceptedCount: totalTxs,
        detail: 'The chain was accepted. Waiting for block confirmation.' });
      return false;
  }, [wallet, completeRwaChain, holdPendingChain]);

  const submitSavedAttestation = useCallback(async (completeCurrentWizard: boolean) => {
    if (!readyAttestation) throw new Error('Review the saved registration and finish Veridian approval first');
    const { attempts } = await scanWalletInitialMintAttempts(wallet, payerAddressHex);
    assertRegistrationRun(run.current);
    const payerHex = payerAddressHex(readyAttestation.registration.feePayerAddress);
    if (!attempts.some(attempt => attempt.payerHex === payerHex &&
        attempt.intentId === readyAttestation.intentId))
      throw new Error('The saved attested chain belongs to a different wallet or attempt');
    const submissionKey = `rwa-initial-mint-submitting-${readyAttestation.intentId}`;
    const completed = await submitRwaChain(readyAttestation.chain,
      () => mintRecoveryStorage.setItem(submissionKey, '1'), readyAttestation.intentId,
      readyAttestation.registration, completeCurrentWizard);
    if (!completed) return;
  }, [readyAttestation, wallet, submitRwaChain]);

  const checkPendingChain = useCallback(async () => {
    const saved = pendingChainRef.current;
    if (!saved || pendingChecking.current || pendingFinalizing.current) return;
    pendingChecking.current = true;
    try {
      const response = await getTokenChainStatus(saved.expectedHashes);
      assertRegistrationRun(run.current);
      const observations = validateChainObservations(saved.expectedHashes, response.transactions);
      if (pendingChainRef.current?.startedAt !== saved.startedAt) return;
      setPendingChain(current => current?.startedAt === saved.startedAt
        ? { ...current, observations } : current);
      if (allChainTransactionsConfirmed(observations)) {
        pendingFinalizing.current = true;
        try { await completeRwaChain(saved); }
        finally { pendingFinalizing.current = false; }
      }
    } catch (error) {
      if (currentRegistrationRun() !== run.current) return;
      setPendingChain(current => current?.startedAt === saved.startedAt
        ? { ...current, detail: `Status check unavailable: ${error instanceof Error ? error.message : String(error)}` }
        : current);
    } finally { pendingChecking.current = false; }
  }, [completeRwaChain]);

  useEffect(() => {
    if (!pendingChain) return;
    void checkPendingChain();
    const timer = window.setInterval(() => {
      if (Date.now() - pendingChain.startedAt < 300_000) void checkPendingChain();
    }, 10_000);
    return () => window.clearInterval(timer);
  }, [pendingChain?.startedAt, checkPendingChain]);

  const resumePendingChain = useCallback(async () => {
    const saved = pendingChainRef.current;
    if (!saved || isProcessing) return;
    setIsProcessing(true);
    try {
      // Recheck immediately; an old UI observation never authorizes a replay.
      const response = await getTokenChainStatus(saved.expectedHashes);
      assertRegistrationRun(run.current);
      const observations = validateChainObservations(saved.expectedHashes, response.transactions);
      if (!canResumeRegistrationChain(saved.phase, saved.acceptedCount, observations)) {
        setPendingChain(current => current?.startedAt === saved.startedAt
          ? { ...current, observations } : current);
        return;
      }
      if (pendingFinalizing.current || pendingChainRef.current?.startedAt !== saved.startedAt) return;
      let submitted: Awaited<ReturnType<typeof submitTokenChain>>;
      try {
        submitted = await submitTokenChain(saved.signedCbors);
      } catch (failure) {
        assertRegistrationRun(run.current);
        const detail = parseSubmitChainFailure(failure);
        if (!detail.txHashes.every((hash, index) =>
            hash.toLowerCase() === saved.expectedHashes[index]?.toLowerCase()) ||
            (detail.failedTxHash && detail.failedIndex !== undefined &&
              detail.failedTxHash.toLowerCase() !== saved.expectedHashes[detail.failedIndex]?.toLowerCase())) {
          setPendingChain(current => current?.startedAt === saved.startedAt
            ? { ...current, phase: 'conflict', detail: 'Retry reported hashes outside this registration chain.' } : current);
          return;
        }
        const acceptedCount = detail.failedIndex === detail.txHashes.length
          ? Math.max(saved.acceptedCount, detail.txHashes.length) : saved.acceptedCount;
        setPendingChain(current => current?.startedAt === saved.startedAt
          ? { ...current, phase: acceptedCount > 0 ? 'partial' : 'lost', acceptedCount,
              observations: [], detail: detail.error } : current);
        return;
      }
      assertRegistrationRun(run.current);
      if (submitted.txHashes.length !== saved.expectedHashes.length ||
          !submitted.txHashes.every((hash, index) =>
            hash.toLowerCase() === saved.expectedHashes[index]?.toLowerCase())) {
        setPendingChain(current => current?.startedAt === saved.startedAt
          ? { ...current, phase: 'conflict', detail: 'Retry returned hashes outside this registration chain.' } : current);
        return;
      }
      if (submitted.confirmed) await completeRwaChain(saved);
      else setPendingChain(current => current?.startedAt === saved.startedAt
        ? { ...current, phase: 'accepted', acceptedCount: saved.expectedHashes.length,
            observations: [], detail: 'The complete chain was accepted. Waiting for confirmation.' }
        : current);
    } catch (error) {
      if (currentRegistrationRun() !== run.current) return;
      setPendingChain(current => current?.startedAt === saved.startedAt
        ? { ...current, detail: error instanceof Error ? error.message : String(error) } : current);
    } finally { setIsProcessing(false); }
  }, [isProcessing, completeRwaChain]);

  const resumePreviousRegistration = useCallback(async () => {
    if (!restoredAttempt || !attestationActive || !readyAttestation || isProcessing) return;
    setRegistrationNavigationLocked(true);
    setIsProcessing(true);
    try {
      await submitSavedAttestation(false);
    } catch (error) {
      showToast({ title: 'Saved registration could not be resumed',
        description: logError('resume saved registration', error), variant: 'error' });
    } finally {
      setIsProcessing(false); setStatusMessage('');
      if (!pendingChainRef.current && currentRegistrationRun() === run.current)
        setRegistrationNavigationLocked(false);
    }
  }, [restoredAttempt, attestationActive, readyAttestation, isProcessing,
    submitSavedAttestation, showToast, setRegistrationNavigationLocked]);

  const handleContinue = useCallback(async () => {
    try {
      if (pendingChain) { await checkPendingChain(); return; }
      const action = initialRegistrationAction(attestationActive, restoredAttempt, Boolean(readyAttestation));
      if (isRwaTokenFlow && action === 'resolve-previous')
        throw new Error('Resolve the previous saved registration before creating a new token');
      if (isRwaTokenFlow && action === 'continue-approval') {
        setAttestationModalOpen(true);
        return;
      }
      if (isRwaTokenFlow && action === 'submit-current') {
        setRegistrationNavigationLocked(true);
        setIsProcessing(true);
        await submitSavedAttestation(true);
        return;
      }
      if (isRwaTokenFlow && !canBuildInitialRegistration(connected, recoveryLoaded, recoveryError, attestationActive)) {
        throw new Error('Check the saved registration attempt before starting a new one');
      }
      if (isRwaTokenFlow) setRegistrationNavigationLocked(true);
      setIsProcessing(true);

      // 1. Get admin address
      setStatusMessage('Reading wallet...');
      const addresses = isRwaTokenFlow ? [] : await wallet.getUsedAddresses();
      const adminAddress = isRwaTokenFlow ? newPayerAddress
        : addresses[0] ?? await wallet.getChangeAddress();
      assertRegistrationRun(run.current);
      if (!adminAddress) throw new Error('Connected wallet has no usable address');

      // 2. Build global state init transaction
      setStatusMessage('Building Global State transaction...');
      const { initGlobalState } = await import('@/lib/api/compliance');

      const isKycExtended = wizardState.flowId === 'kyc-extended';
      const isRwaToken = wizardState.flowId === 'rwa-token';
      const flowModuleId = isKycExtended ? 'kyc-extended' : 'kyc';

      // kyc-extended parameterises the global-state script with the BACKEND's
      // signing key PKH so the backend can autonomously sign UpdateMemberRootHash.
      //
      // rwa-token follows a different model: the on-chain
      // `admin_credential_hash` is the USER's wallet PKH, because BaFin's
      // global_state validator gates every admin action (AddPowerUser,
      // AddTrustedEntity, RotateAdmin, …) on a signature from that key.
      // Using the backend's PKH here would mean only the backend could ever
      // sign admin txs — but the user signs in their wallet, so the tx would
      // fail with `missingSignatories` pointing at the backend's PKH.
      //
      // (Autonomous MPF root sync for rwa-token is a separate problem
      // left for a follow-up: either the user signs root-hash updates manually,
      // or admin is later delegated to the backend via RotateAdmin.)
      let adminPkh: string | undefined;
      if (isKycExtended) {
        setStatusMessage('Fetching backend admin key…');
        const adminInfo = await getKycExtendedAdminPkh();
        adminPkh = adminInfo.adminPkh;
      } else if (isRwaToken) {
        const { getPaymentKeyHash } = await import('@/lib/utils/address');
        adminPkh = getPaymentKeyHash(adminAddress);
      }

      // RWA-token: chained build + single-popup sign + batched submit.
      //
      // The backend assembles the full 3-tx registration chain in one call
      // (genesis → AddPowerUser → registration), deterministically chained
      // via mempool-visible UTxOs. The wallet signs all three in one CIP-30
      // signTxs popup. The backend submits them sequentially via its own
      // submission service, so the wallet's submission backend (which would
      // typically reject mempool-chained txs) never sees the chain.
      //
      // The registration tx always inserts the prog-token policy into the
      // CIP-113 directory and registers the module's stake credentials.
      // Whether it ALSO mints the first supply is up to `initialMintQuantity`
      // below: non-zero folds a MintSecurity GlobalState spend into the same
      // transaction; zero leaves the first mint as a separate admin action.
      if (isRwaToken) {
        // Refuse before the backend writes anything. Phase 1 of the chain persists
        // a registration row and a bootstrap power-user row as a side effect of
        // BUILDING the genesis tx, so a combination that can never produce a valid
        // registration must be stopped here rather than half-way through.
        if (registrationBlocked) {
          showToast({
            title: 'Cannot register with these settings',
            description: mintQtyInvalid
              ? 'Initial supply must be a whole, non-negative number.'
              : capInvalid
                ? 'Mintable amount must be a whole number from 0 to 9,007,199,254,740,991.'
              : mintBlockedByKyc
                ? 'To mint the initial supply at registration with receiver KYC on, tick "Enroll the recipient in the allowlist at genesis". Otherwise turn off "Require receiver KYC", or go back and set the initial supply to 0 and mint later once the member root is published.'
                : 'Initial supply cannot exceed the mintable amount.',
            variant: 'error',
          });
          return;
        }

        // ── Phase 1: build the chain on the backend ──
        setStatusMessage('Phase 1/3 — authorize token creation in your wallet, then build the registration chain…');
        const creationRequest = await makeRwaRegistration();
        assertRegistrationRun(run.current);

        if (approveInitialMint && willFirstMint) {
          await openAttestation();
          return;
        }
        await withRegistrationLock(async () => {
          const { attempts } = await scanWalletInitialMintAttempts(wallet, payerAddressHex);
          assertRegistrationRun(run.current);
          if (attempts.length)
            throw new Error('Resolve the previous saved registration before creating another token policy');
          const chain = await buildRwaTokenChain(creationRequest, rawApi);
          assertRegistrationRun(run.current);
          await submitRwaChain(chain);
        });
        return;
      }

      const response = await initGlobalState(
        {
          moduleId: flowModuleId,
          adminAddress,
          adminPkh,
          initialVkeys: trustedEntities,
          initialTransfersPaused: false,
          initialMintableAmount: mintableAmount ? parseInt(mintableAmount, 10) : 0,
          initialSecurityInfo: securityInfo || undefined,
        },
        selectedVersion?.txHash
      ) as GlobalStateInitResponse;
      assertRegistrationRun(run.current);

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
      assertRegistrationRun(run.current);
      const txHash = await wallet.submitTx(signedTx);
      assertRegistrationRun(run.current);

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
      assertRegistrationRun(run.current);

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
      if (currentRegistrationRun() !== run.current) return;
      // describeError, not `error instanceof Error`: CIP-30 wallets throw plain
      // { code, info } objects, so the instanceof branch is false for every wallet
      // rejection and the node's actual reason — which lives in `info` — was being
      // replaced by the constant below.
      const described = logError('rwa global-state init', error);
      const errorMessage = described.includes('User declined') || described.includes('user declined')
        ? 'Transaction was cancelled'
        : described;
      showToast({
        title: 'Global state setup failed',
        description: errorMessage,
        variant: 'error',
      });
    } finally {
      setIsProcessing(false);
      setStatusMessage('');
      if (!pendingChainRef.current && currentRegistrationRun() === run.current)
        setRegistrationNavigationLocked(false);
    }
  }, [wallet, selectedVersion, showToast, onDataChange, onComplete, mintableAmount, securityInfo,
      trustedEntities, wizardState, approveInitialMint, willFirstMint, submitRwaChain,
      registrationBlocked, mintQtyInvalid, capInvalid, mintBlockedByKyc, isRwaTokenFlow,
      connected, recoveryLoaded, recoveryError, attestationActive, restoredAttempt, readyAttestation,
      newPayerAddress,
      makeRwaRegistration, openAttestation, rawApi, submitSavedAttestation,
      pendingChain, checkPendingChain, setRegistrationNavigationLocked]);

  const dismissAttestation = useCallback(() => {
    setAttestationModalOpen(false);
    if (!attestationActive && !readyAttestation) {
      setApproveInitialMint(false);
      setFrozenRegistration(null);
      setAttestationPayer('');
    }
    requestAnimationFrame(() => (attestationActive
      ? attestationResumeRef.current : attestationChoiceRef.current)?.focus());
  }, [attestationActive, readyAttestation]);

  const modalRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (attestationModalOpen) modalRef.current?.focus();
  }, [attestationModalOpen]);

  return (
    <div className="space-y-6">
      {isRwaTokenFlow && !recoveryLoaded &&
        <Card role="status" className="p-4 text-sm text-dark-300">Checking saved initial mint attempts…</Card>}
      {isRwaTokenFlow && recoveryError &&
        <Card role="alert" className="p-4 text-sm text-amber-300 space-y-2">
          <p>{recoveryError}. Registration is paused until the saved-attempt check succeeds.</p>
          {connected && <Button variant="outline" onClick={() => setRecoveryCheck((value) => value + 1)}>
            Retry saved-attempt check
          </Button>}
        </Card>}
      {isRwaTokenFlow && attestationActive && !restoredAttempt && <div className="flex flex-wrap items-center gap-3 text-sm">
        <span className={readyAttestation ? 'text-green-300' : 'text-amber-300'}>
          {readyAttestation ? 'Attesting with CIP-170 ✓' : 'CIP-170 approval needs your input'}
        </span>
        <Button ref={attestationResumeRef} variant="ghost" className="h-8 px-2 text-xs" onClick={() => {
          setPanelResumeAttempt(currentAttempt);
          setAttestationModalOpen(true);
        }}>
          {readyAttestation ? 'Review' : 'Open'}
        </Button>
        {!readyAttestation && <span className="text-xs text-dark-400">Registration settings are held for this attempt.</span>}
      </div>}
      {isRwaTokenFlow && attestationModalOpen && attestationPayer && typeof document !== 'undefined' && createPortal(
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4">
          <div ref={modalRef} role="dialog" aria-modal="true" aria-label="Optional CIP-170 attestation"
            tabIndex={-1} className="w-full max-w-2xl max-h-[90vh] overflow-y-auto rounded-xl bg-dark-900 shadow-2xl outline-none"
            onKeyDown={(event) => {
              if (event.key !== 'Tab') return;
              const controls = Array.from(modalRef.current?.querySelectorAll<HTMLElement>(
                'button:not([disabled]), input:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])') ?? []);
              if (!controls.length) { event.preventDefault(); return; }
              const first = controls[0]; const last = controls[controls.length - 1];
              if (event.shiftKey && (document.activeElement === first || document.activeElement === modalRef.current)) {
                event.preventDefault(); last.focus();
              } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === modalRef.current)) {
                event.preventDefault(); first.focus();
              }
            }}>
            <InitialMintAttestationPanel key={panelResumeAttempt?.intentId ?? attestationPayer}
              resumeAttempt={panelResumeAttempt} registration={frozenRegistration}
              feePayerAddress={attestationPayer}
              preparationError={!selectedSavedAttempt && !attestationActive && registrationBlocked
                ? mintQtyInvalid ? 'Enter a whole, non-negative initial supply.'
                  : capInvalid ? 'Enter a whole mintable amount from 0 to 9,007,199,254,740,991.'
                  : mintBlockedByKyc ? 'Enroll the recipient at genesis or turn off receiver KYC before preparing this mint.'
                  : 'Initial supply exceeds the mintable amount.'
                : null}
              onPrepared={(registration, intentId, sessionId) => {
                setFrozenRegistration(registration);
                setAttestationActive(true);
                if (!selectedSavedAttempt) {
                  setActiveAttemptId(intentId);
                  setCurrentAttempt({ intentId, sessionId,
                    payerAddress: registration.feePayerAddress,
                    payerHex: payerAddressHex(registration.feePayerAddress), registration });
                }
              }}
              onReady={(chain, intentId, registration) => {
                setReadyAttestation({ chain, intentId, registration });
                setFrozenRegistration(registration);
                setAttestationActive(true);
                setAttestationModalOpen(false);
                requestAnimationFrame(() => attestationResumeRef.current?.focus());
              }}
              onDismiss={dismissAttestation}
              onBack={() => {
                if (restoredAttempt) setRecoveryLoaded(false);
                setAttestationActive(false);
                setRestoredAttempt(false);
                setReadyAttestation(null);
                setSelectedSavedAttempt(undefined);
                setCurrentAttempt(undefined);
                setPanelResumeAttempt(undefined);
                setActiveAttemptId(null);
                setFrozenRegistration(null);
                setApproveInitialMint(false);
                setAttestationModalOpen(false);
                if (restoredAttempt) setRecoveryCheck((value) => value + 1);
                requestAnimationFrame(() => attestationChoiceRef.current?.focus());
              }} />
          </div>
        </div>, document.body)}
      <fieldset className="space-y-6" disabled={isRwaTokenFlow && ((attestationActive && !restoredAttempt) || Boolean(pendingChain))}>
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">Global State Configuration</h3>
        <p className="text-dark-300 text-sm">
          Choose the initial configuration for this token. Some fields can be updated later in the Admin Panel.
        </p>
      </div>

      {isRwaTokenFlow && (
        <details className="rounded border border-dark-700 p-4 text-sm">
          <summary className="cursor-pointer font-medium text-white">How registration works</summary>
          <div className="mt-3 space-y-3">
          <p className="text-sm text-dark-300">
            Setting up a BaFin-style RWA token chains multiple transactions, including two
            required CIP-171 provenance records.{' '}
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
            {willFirstMint && (
              <li>
                <span className="text-white">Publish reference scripts</span> — parks{' '}
                <span className="font-mono text-primary-400">minting_logic</span> (~7.3 KB) and{' '}
                <span className="font-mono text-primary-400">global_state</span> (~4.3 KB) in two
                outputs at your own enterprise address, so the registration can reference them
                instead of carrying them inline. Without this the registration transaction is
                16 584 bytes against the ledger&apos;s 16 384-byte limit and cannot be submitted at
                all. This locks about 55 ADA of min-UTxO. It is recoverable in principle — the
                outputs use your own payment key — but the platform has no reclaim action, and
                most wallets will not show an enterprise address, so treat it as spent.
              </li>
            )}
            <li>
              <span className="text-white">Registration</span> — inserts the policy into the CIP-113 directory
              {willFirstMint
                ? <> and mints the initial supply of <span className="text-white">{trimmedMint}</span> in
                  the same transaction (spending the Global State under <span className="font-mono text-primary-400">MintSecurity</span>,
                  which decrements <span className="font-mono text-primary-400">mintable_amount</span>).</>
                : <>. No tokens are minted — the first mint is a separate admin action once everything is on chain.</>}
            </li>
            <li>
              <span className="text-white">TransferLogic cert</span> — registers the transfer-logic stake credential (Conway RegCert) so the first transfer can issue a withdraw-0.
            </li>
          </ol>
          <p className="text-xs text-dark-400">
            Each tx feeds the next via mempool chaining — no on-chain confirmation pauses. If your wallet
            doesn&apos;t support CIP-103 batch signing, the flow falls back to one popup per transaction.
          </p>
          </div>
        </details>
      )}

      <details className="rounded border border-dark-700 p-4 text-sm">
        <summary className="cursor-pointer font-medium text-white">What the Global State contains</summary>
        <div className="mt-3">
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
          {(isKycExtendedFlow || isRwaTokenFlow) && (
            <li className="flex items-start gap-2">
              <span className="text-primary-400 font-mono text-xs mt-0.5">member_root_hash</span>
              <span>Blake2b-256 root of the Merkle Patricia Forestry allowlist. Updated automatically by the backend whenever a user completes KYC. Transfers to recipients not in the tree are rejected on-chain.</span>
            </li>
          )}
          {isRwaTokenFlow && (
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
                <span>If on, recipients must hold a fresh KYC attestation to receive tokens. Set below, and toggleable later from the admin page.</span>
              </li>
            </>
          )}
        </ul>
        </div>
      </details>

      <Card className="p-4 space-y-4">
        <h4 className="text-sm font-medium text-white">Initial Values</h4>

        <Input
          label="Mintable Amount"
          type="number"
          min="0"
          value={displayedMintableAmount}
          onChange={(e) => setMintableAmount(e.target.value)}
          disabled={isProcessing}
          helperText={`Hard cap on how many tokens can ever be minted. Defaults to the token supply (${defaultQuantity}); 0 means no tokens can be minted at all, not "no cap".`}
        />
        {isRwaTokenFlow && capInvalid && <p role="alert" className="text-xs text-red-300">
          Enter a whole number from 0 to 9,007,199,254,740,991.
        </p>}

        {isRwaTokenFlow && (
          /* Not an input. The initial supply is collected once, on the previous step
             ("Initial Supply"), and used verbatim here — a second box for the same
             number is two sources of truth for one on-chain value. */
          <div className="rounded border border-dark-700 bg-dark-800/40 p-3 text-sm space-y-1">
            <div className="flex items-baseline justify-between gap-3">
              <span className="text-dark-300">Initial supply minted at registration</span>
              <span className="font-mono text-white">{trimmedMint || '0'}</span>
            </div>
            <p className="text-xs text-dark-400">
              Taken from the <span className="text-white">Initial Supply</span> you entered on the
              previous step. It is minted by the registration transaction itself and deducted from
              the mintable amount above. Go back and set it to 0 to register the policy only and
              mint later from the admin page.
            </p>
            {mintQtyInvalid && (
              <p className="text-xs text-red-300">
                Initial supply must be a whole, non-negative number — go back and correct it.
              </p>
            )}
            {mintExceedsCap && (
              <p className="text-xs text-red-300">
                Initial supply {trimmedMint} exceeds the mintable amount ({trimmedCap}). Raise the
                mintable amount above, or go back and lower the supply.
              </p>
            )}
          </div>
        )}

        <Input
          label="Security Info (hex, optional)"
          value={securityInfo}
          onChange={(e) => setSecurityInfo(e.target.value)}
          placeholder="Leave empty for none"
          disabled={isProcessing}
          helperText="Optional hex-encoded compliance metadata."
        />
      </Card>

      {isRwaTokenFlow && (
        <Card className="p-4 space-y-3">
          <label className="flex items-start gap-3 cursor-pointer">
            <input
              type="checkbox"
              checked={displayedRequiresReceiverKyc}
              onChange={(e) => setRequiresReceiverKyc(e.target.checked)}
              disabled={isProcessing}
              className="mt-1 h-4 w-4 shrink-0 accent-primary-500 cursor-pointer disabled:cursor-not-allowed"
            />
            <span>
              <span className="block text-sm font-medium text-white">Require receiver KYC</span>
              <span className="block text-xs text-dark-400 mt-1">
                Recipients must present a fresh KYC proof to receive tokens, checked on-chain against
                the allowlist root in <span className="font-mono text-primary-400">member_root_hash</span>.
                Can be toggled later from the admin page.
              </span>
            </span>
          </label>

          {displayedRequiresReceiverKyc && willFirstMint && (
            <label className="flex items-start gap-3 cursor-pointer border-t border-dark-700 pt-3">
              <input
                type="checkbox"
                checked={displayedSeedRecipient}
                onChange={(e) => setSeedRecipientInAllowlist(e.target.checked)}
                disabled={isProcessing}
                className="mt-1 h-4 w-4 shrink-0 accent-amber-500 cursor-pointer disabled:cursor-not-allowed"
              />
              <span>
                <span className="block text-sm font-medium text-amber-300">
                  Enroll the recipient in the allowlist at genesis — no KYC check is performed
                </span>
                <span className="block text-xs text-dark-400 mt-1">
                  Writes the recipient&apos;s stake credential into the token&apos;s compliance
                  allowlist and bakes the resulting root into{' '}
                  <span className="font-mono text-primary-400">member_root_hash</span> at genesis.
                  This is the only way to combine receiver KYC with an initial supply, because a
                  root cannot be published before the Global State exists.
                </span>
                <span className="block text-xs text-amber-300/90 mt-2">
                  What this asserts: that the recipient is a verified allowlist member — on your
                  say-so alone, with no KYC process behind it. The claim is not scoped to this first
                  mint; every later transfer proves membership against the same root, and the
                  enrollment stands for one year unless you remove it. Leave it off and mint after a
                  real enrollment if that is not what you mean.
                </span>
              </span>
            </label>
          )}

          {mintBlockedByKyc && (
            <div className="rounded border border-red-700/50 bg-red-900/20 p-3 text-xs text-red-200 space-y-1">
              <p className="font-semibold">
                Receiver KYC and an initial supply need the allowlist seed above.
              </p>
              <p className="text-red-300/90">
                The mint needs a membership proof against{' '}
                <span className="font-mono">member_root_hash</span>, and genesis writes that root
                empty unless you tick the box above — no root can be published beforehand, because
                the token&apos;s policy id does not exist until this very transaction picks its
                bootstrap UTxO.
              </p>
              <p className="text-red-300/90">
                Your options: tick the box (and accept what it asserts), turn receiver KYC off here
                and switch it on afterwards from the admin page, or go back and set the initial
                supply to 0, enroll recipients properly, publish the member root, and mint
                separately.
              </p>
            </div>
          )}
        </Card>
      )}

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
          Ed25519 verification keys authorized to sign raw CMTA attestations for this token.
          Add the backend signing entity only if you want this public backend to have that authority
          and its operator has explicitly configured a signing key. You can leave it out and add
          your own trusted key instead.
          Membership proofs use the admin-signed Merkle root and do not require a trusted entity.
        </p>

        {isLoadingVkey && displayedTrustedEntities.length === 0 ? (
          <div className="flex items-center gap-2 text-xs text-dark-400">
            <div className="h-3.5 w-3.5 border border-primary-500 border-t-transparent rounded-full animate-spin" />
            <span>Loading your wallet verification key…</span>
          </div>
        ) : displayedTrustedEntities.length > 0 ? (
          <ul className="space-y-2">
            {displayedTrustedEntities.map((vkey) => (
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

      {isRwaTokenFlow && willFirstMint && (
        <Card className="p-4">
          <label className="flex items-start gap-3 cursor-pointer">
            <input ref={attestationChoiceRef} type="checkbox" checked={approveInitialMint || (attestationActive && !restoredAttempt)}
              onChange={(e) => {
                if (e.target.checked) void openAttestation();
                else setApproveInitialMint(false);
              }} disabled={isProcessing || attestationActive || Boolean(pendingChain)}
              className="mt-1 h-4 w-4 shrink-0 accent-primary-500" />
            <span>
              <span className="block text-sm font-medium text-white">Add CIP-170 attestation with Veridian (optional)</span>
              <span className="block text-xs text-dark-300 mt-1">
                A following transaction records your KERI identity&apos;s approval of this initial mint.
                Select to open the Veridian steps and inspect exactly what is signed.
              </span>
            </span>
          </label>
        </Card>
      )}

      {isProcessing && (
        <Card className="p-4">
          <div className="flex items-center gap-3">
            <div className="h-5 w-5 border-2 border-primary-500 border-t-transparent rounded-full animate-spin" />
            <p className="text-sm text-dark-300">{statusMessage}</p>
          </div>
        </Card>
      )}

      </fieldset>
      {pendingChain && <Card role="status" className="p-4 space-y-3 text-sm">
        <div>
          <p className="font-medium text-white">Registration chain — checking status</p>
          <p className="text-dark-300 mt-1">
            {pendingChain.phase === 'accepted'
              ? 'The submission service accepted the full chain. Check the exact hashes in a block before considering registration complete.'
              : 'Delivery status is uncertain. The signed chain is held for this wizard run while its exact hashes are checked.'}
          </p>
          <p className="text-xs text-dark-400 mt-1">Keep this page open. Reset or reload clears the local signed chain.</p>
          {pendingChain.detail && <p className="text-amber-300 mt-1 break-words">{pendingChain.detail}</p>}
          {pendingChain.observations.some(item => item.status === 'INVALID') &&
            <p className="text-red-300 mt-1">A transaction was recorded as invalid. Do not retry this chain.</p>}
        </div>
        <ul className="max-h-48 overflow-y-auto space-y-1 font-mono text-xs text-dark-300">
          {pendingChain.expectedHashes.map((hash, index) => {
            const observation = pendingChain.observations[index];
            return <li key={hash} className="flex flex-wrap gap-x-2">
              <span className="text-white">{pendingChain.names[index]}</span>
              <span className="break-all">{hash}</span>
              <span className={observation?.status === 'CONFIRMED' ? 'text-green-300'
                : observation?.status === 'INVALID' ? 'text-red-300' : 'text-amber-300'}>
                {observation ? `${observation.status} (${observation.reason})` : 'Checking…'}
              </span>
            </li>;
          })}
        </ul>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" onClick={() => void checkPendingChain()}>Check confirmation</Button>
          {canResumeRegistrationChain(pendingChain.phase, pendingChain.acceptedCount,
              pendingChain.observations) &&
            <Button variant="outline" onClick={() => void resumePendingChain()} disabled={isProcessing}>
              Retry delivery of saved transactions
            </Button>}
        </div>
      </Card>}
      {isRwaTokenFlow && restoredAttempt && <details className="rounded border border-amber-700/50 p-3 text-sm">
        <summary className="cursor-pointer text-amber-200">
          A previous registration needs review before you register another token
        </summary>
        <div className="mt-3 space-y-2">
          {savedAttempts.map((attempt) => <div key={attempt.intentId}
            className="flex flex-wrap items-center gap-2 border-t border-dark-700 pt-2">
            <span className="text-xs text-dark-300">
              Initial supply {String(attempt.registration.initialMintQuantity ?? '0')} · wallet {attempt.payerHex.slice(0, 12)}…
            </span>
            <Button ref={attestationPayer === attempt.payerAddress ? attestationResumeRef : undefined}
              variant="outline" className="h-8 px-3 text-xs" onClick={() => {
                if (readyAttestation?.intentId !== attempt.intentId) setReadyAttestation(null);
                setSelectedSavedAttempt(attempt);
                setPanelResumeAttempt(attempt);
                setFrozenRegistration(attempt.registration as RwaTokenInitRequest);
                setAttestationPayer(attempt.payerAddress);
                setAttestationModalOpen(true);
              }}>Review</Button>
            {readyAttestation?.intentId === attempt.intentId &&
              <Button className="h-8 px-3 text-xs" onClick={() => void resumePreviousRegistration()}
                disabled={isProcessing}>Resume registration</Button>}
          </div>)}
        </div>
      </details>}
      <div className="flex gap-3">
        {onBack && (
          <Button variant="outline" onClick={onBack} disabled={isProcessing || Boolean(pendingChain) || (attestationActive && !restoredAttempt)}>
            Back
          </Button>
        )}
        <Button
          variant="primary"
          className="flex-1"
          onClick={handleContinue}
          isLoading={isProcessing}
          disabled={isProcessing || (!pendingChain && (restoredAttempt || (!attestationActive && registrationBlocked) || (isRwaTokenFlow &&
            (!connected || !recoveryLoaded || Boolean(recoveryError)))))}
        >
          {pendingChain ? 'Check confirmation' : isRwaTokenFlow
            ? restoredAttempt ? 'Resolve saved registration first' : attestationActive
              ? readyAttestation ? 'Register RWA Token' : 'Open CIP-170 approval'
              : approveInitialMint && willFirstMint ? 'Open CIP-170 approval' : 'Register RWA Token'
            : 'Initialize Global State & Continue'}
        </Button>
      </div>
    </div>
  );
}
