"use client";

import { useEffect, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { useWallet } from '@/hooks/use-wallet';
import { useToast } from '@/components/ui/use-toast';
import { getAgentOobi, resolveOobi, storeCardanoAddress, getAvailableRoles,
  presentCredential, getSession, type AvailableRole } from '@/lib/api/keri';
import { payerAddressHex } from '@/lib/rwa/creation-auth';
import { mintRecoveryStorage, admitInitialMintAttempt,
  removeInitialMintAttempt, scanWalletInitialMintAttempts, withRegistrationLock,
  type SavedInitialMintAttempt } from '@/lib/rwa/mint-recovery-storage';
import { finishArchivedInitialMint } from '@/lib/rwa/initial-mint-draft';
import { parseSubmitChainFailure, type RwaTokenInitRequest, type RwaTokenChainBuildResponse } from '@/lib/api/rwa-token';
import { ApiException } from '@/types/api';
import { getInitialMintAttestationConfig, getInitialMintAttestation,
  prepareInitialMintAttestation, approveAndBuildInitialMintAttestation,
  cancelInitialMintAttestation, getInitialMintRecovery, archiveExpiredInitialMint,
  type InitialMintAttestationView, type InitialMintRecovery } from '@/lib/api/rwa-initial-attestation';
import type { MintAttestationConfig } from '@/lib/api/mint-attestation';
import { currentRegistrationRun, assertRegistrationRun } from '@/lib/rwa/registration-run';

interface Props {
  registration: RwaTokenInitRequest | null;
  feePayerAddress: string;
  onReady: (chain: RwaTokenChainBuildResponse, intentId: string,
    registration: RwaTokenInitRequest) => void;
  onBack: () => void;
  onDismiss: () => void;
  onPrepared: (registration: RwaTokenInitRequest, intentId: string, sessionId: string) => void;
  preparationError: string | null;
  resumeAttempt?: SavedInitialMintAttempt;
}

/** Keeps the approved creation attempt frozen while the wallet changes signing surfaces. */
export function InitialMintAttestationPanel({ registration, feePayerAddress, onReady, onBack,
  onDismiss, onPrepared, preparationError, resumeAttempt }: Props) {
  const { rawApi, wallet } = useWallet();
  const { toast } = useToast();
  const session = useRef<string | null>(null);
  const run = useRef(currentRegistrationRun());
  const [attemptId, setAttemptId] = useState(resumeAttempt?.intentId ?? null);
  const mounted = useRef(true);
  const [config, setConfig] = useState<MintAttestationConfig | null>(null);
  const [intent, setIntent] = useState<InitialMintAttestationView | null>(null);
  const restoredRegistration = resumeAttempt?.registration as RwaTokenInitRequest | undefined;
  const [oobi, setOobi] = useState<string | null>(null);
  const [walletOobi, setWalletOobi] = useState('');
  const [connected, setConnected] = useState(false);
  const [credential, setCredential] = useState(false);
  const [blockedWallet, setBlockedWallet] = useState(false);
  const [roles, setRoles] = useState<AvailableRole[] | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recovery, setRecovery] = useState<InitialMintRecovery | null>(null);

  useEffect(() => {
    mounted.current = true;
    let alive = true;
    session.current = resumeAttempt?.sessionId ?? crypto.randomUUID();
    const saved = resumeAttempt?.intentId;
    getInitialMintAttestationConfig().then((c) => { if (alive) setConfig(c); })
      .catch((e) => { if (alive) setError(e instanceof Error ? e.message : 'Could not load mint approval configuration'); });
    if (saved && resumeAttempt && payerAddressHex(resumeAttempt.registration.feePayerAddress) !== payerAddressHex(feePayerAddress)) {
      setBlockedWallet(true);
      setError('A prepared initial mint belongs to another wallet. Reconnect that wallet to resume or cancel it.');
      return () => { alive = false; mounted.current = false; };
    }
    if (saved) getInitialMintAttestation(saved, session.current).then((view) => {
      if (!alive) return;
      if (payerAddressHex(view.registration.feePayerAddress) !== payerAddressHex(feePayerAddress)) {
        setBlockedWallet(true);
        setError('Reconnect the wallet that signed this registration chain to resume it.');
      } else setIntent(view);
    }).catch((e) => { if (alive) setError(e instanceof Error ? e.message : 'Could not load the saved creation attempt'); });
    getSession(session.current).then(async (s) => {
      if (!alive) return;
      const samePayer = s.cardanoAddress?.toLowerCase() === payerAddressHex(feePayerAddress);
      setConnected(Boolean(s.exists && samePayer));
      setCredential(Boolean(s.hasCredential && samePayer));
    }).catch((e) => { if (alive && saved) setError(e instanceof Error ? e.message : 'Veridian session is unavailable; a saved signed chain can still be resumed'); });
    return () => { alive = false; mounted.current = false; };
  }, [feePayerAddress, resumeAttempt]);

  useEffect(() => {
    if (intent?.status !== 'BUILT' || !session.current) return;
    let active = true;
    getInitialMintRecovery(intent.intentId, session.current)
      .then((result) => { if (active) setRecovery(result); })
      .catch(() => { /* Manual recovery check remains available. */ });
    return () => { active = false; };
  }, [intent?.intentId, intent?.status]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !busy) onDismiss();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [busy, onDismiss]);

  const loadOobi = async () => {
    setBusy(true); setError(null);
    try { setOobi((await getAgentOobi(session.current!)).oobi); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not load backend OOBI'); }
    finally { setBusy(false); }
  };

  const connect = async () => {
    setBusy(true); setError(null);
    try {
      await resolveOobi(session.current!, walletOobi.trim());
      await storeCardanoAddress(session.current!, payerAddressHex(feePayerAddress));
      setRoles((await getAvailableRoles(session.current!)).availableRoles);
      setConnected(true);
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not connect Veridian'); }
    finally { setBusy(false); }
  };

  const present = async () => {
    if (!role) return;
    setBusy(true); setError(null);
    try { await presentCredential(session.current!, role); setCredential(true); }
    catch (e) { setError(e instanceof Error ? e.message : 'Credential presentation failed'); }
    finally { setBusy(false); }
  };

  const refreshIntent = async () => {
    const savedId = intent?.intentId ?? attemptId;
    if (!savedId || !session.current) return;
    setBusy(true); setError(null);
    try { setIntent(await getInitialMintAttestation(savedId, session.current)); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not check the saved attempt'); }
    finally { setBusy(false); }
  };

  const checkRecovery = async () => {
    const savedId = intent?.intentId ?? attemptId;
    if (!savedId || !session.current) return;
    setBusy(true); setError(null);
    try { setRecovery(await getInitialMintRecovery(savedId, session.current)); }
    catch (e) { setError(e instanceof Error ? e.message : 'Could not check the old chain'); }
    finally { setBusy(false); }
  };

  const startNewPolicy = async () => {
    if (!intent || !session.current || !config || !recovery?.canStartNewPolicy) return;
    setBusy(true); setError(null);
    try {
      const result = recovery.status === 'ARCHIVED_EXPIRED' ? recovery
        : await archiveExpiredInitialMint(rawApi, intent.intentId,
          session.current, feePayerAddress, config);
      // Keep signed CBOR and the backend intent as recovery evidence. Disconnect
      // this wallet's active pointer only after the authenticated archive succeeds.
      await withRegistrationLock(async () => {
        finishArchivedInitialMint(payerAddressHex(feePayerAddress), result.status,
          result.canStartNewPolicy, intent.intentId);
        removeInitialMintAttempt(intent.intentId);
      });
      onBack();
    } catch (e) { setError(e instanceof Error ? e.message : 'Could not start a new policy'); }
    finally { setBusy(false); }
  };

  const approveAndBuild = async () => {
    if (!config || blockedWallet) return;
    assertRegistrationRun(run.current);
    setBusy(true); setError(null);
    let preparingId: string | null = null;
    let admitted = false;
    let approvingId: string | null = null;
    try {
      const frozen = intent?.registration ?? registration ?? restoredRegistration;
      if (!frozen) throw new Error('Registration settings are unavailable; return to the wizard');
      if (payerAddressHex(frozen.feePayerAddress) !== payerAddressHex(feePayerAddress))
        throw new Error('The connected wallet differs from the frozen registration fee payer');
      let requestId = intent?.intentId ?? attemptId;
      if (!requestId) {
        requestId = crypto.randomUUID();
      }
      preparingId = intent ? null : requestId;
      const prepared = intent ?? await prepareInitialMintAttestation(rawApi, requestId, session.current!, frozen, config,
        async () => {
          assertRegistrationRun(run.current);
          if (attemptId) {
            await withRegistrationLock(async () => {
              const { attempts } = await scanWalletInitialMintAttempts(wallet, payerAddressHex);
              if (!attempts.some(saved => saved.intentId === requestId &&
                  saved.sessionId === session.current &&
                  saved.payerHex === payerAddressHex(feePayerAddress)))
                throw new Error('The saved initial-mint attempt is unavailable; inspect recovery before retrying');
            });
          } else {
            await admitInitialMintAttempt(wallet, payerAddressHex,
              { intentId: requestId, sessionId: session.current!,
                payerHex: payerAddressHex(feePayerAddress), payerAddress: feePayerAddress,
                registration: frozen });
          }
          if (currentRegistrationRun() !== run.current) {
            removeInitialMintAttempt(requestId);
            assertRegistrationRun(run.current);
          }
          admitted = true;
          setAttemptId(requestId);
          onPrepared(frozen, requestId, session.current!);
        });
      preparingId = null;
      assertRegistrationRun(run.current);
      if (!mounted.current) return;
      setIntent(prepared);
      if (!intent) return; // Let the issuer inspect the derived policy and exact mint hash first.
      const submissionKey = `rwa-initial-mint-submitting-${prepared.intentId}`;
      approvingId = prepared.intentId;
      // A saved signed chain is immutable. Reuse its saved unsigned chain on
      // recovery so the issuer does not have to authorize the same build again.
      const savedChain = mintRecoveryStorage.getItem(`${submissionKey}-chain`);
      const chain = savedChain ? JSON.parse(savedChain) as RwaTokenChainBuildResponse
        : await approveAndBuildInitialMintAttestation(rawApi, prepared.intentId,
          session.current!, feePayerAddress, config);
      assertRegistrationRun(run.current);
      if (!savedChain) mintRecoveryStorage.setItem(`${submissionKey}-chain`, JSON.stringify(chain));
      approvingId = null;
      if (!mounted.current) return;
      const builtView = await getInitialMintAttestation(prepared.intentId, session.current!);
      assertRegistrationRun(run.current);
      setIntent(builtView);
      if (!mounted.current) return;
      if (builtView.status !== 'BUILT')
        throw new Error(`The saved chain is ${builtView.status}; check its status before Cardano signing`);
      onReady(chain, prepared.intentId, frozen);
      toast({ title: 'Veridian approval ready', description: 'The attested registration chain is ready for Cardano signing.', variant: 'success' });
    } catch (e) {
      if (currentRegistrationRun() !== run.current) return;
      let recoveredPrepare = false;
      if (preparingId && admitted) {
        // The response can be lost after preparation commits. Read the saved
        // attempt first, so the next click does not ask for the same signature.
        try {
          const saved = await getInitialMintAttestation(preparingId, session.current!);
          if (mounted.current) { setIntent(saved); recoveredPrepare = true; }
        } catch (lookupError) {
          if (e instanceof ApiException && e.status && e.status >= 400 && e.status < 500
              && e.status !== 408 && lookupError instanceof ApiException && lookupError.status === 404) {
            await withRegistrationLock(async () => { removeInitialMintAttempt(preparingId!); });
            setAttemptId(null);
          }
        }
      }
      if (approvingId && mounted.current) {
        try { setIntent(await getInitialMintAttestation(approvingId, session.current!)); }
        catch { /* Keep the saved attempt ID when status cannot be read. */ }
      }
      const detail = parseSubmitChainFailure(e);
      setError(recoveredPrepare
        ? 'The preparation completed despite the lost response. Review the mint intent below before approving it.'
        : approvingId && e instanceof ApiException && e.status === 409
        ? 'This approval or build may still be processing. The saved attempt is retained; check its status before retrying.'
        : preparingId && attemptId
        ? `${detail.error}. Check the saved attempt status before signing again.`
        : detail.txHashes.length
        ? `${detail.error}. Accepted transaction hashes: ${detail.txHashes.join(', ')}. Reconcile before retrying.`
        : detail.error);
    }
    finally { setBusy(false); }
  };

  const goBack = async () => {
    if (busy || blockedWallet) return;
    const savedId = intent?.intentId ?? attemptId;
    if (savedId) {
      if (!config) {
        setError('Could not load mint approval configuration. Retry loading the page before cancelling this saved attempt.');
        return;
      }
      setBusy(true); setError(null);
      try {
        const saved = intent ?? await getInitialMintAttestation(savedId, session.current!);
        if (saved.status === 'ARCHIVED_EXPIRED') {
          await withRegistrationLock(async () => {
            removeInitialMintAttempt(savedId);
          });
          onBack();
          return;
        }
        if (saved.status === 'BUILT') {
          setIntent(saved);
          setError('This approved transaction chain has already been built. Resume it here; changing settings would require a new token policy.');
          setBusy(false);
          return;
        }
        await cancelInitialMintAttestation(rawApi, savedId, session.current!, feePayerAddress, config);
      } catch (e) {
        // A 404 is still ambiguous while preparation may be in flight. Keep
        // the request ID and let the issuer repeat the idempotent prepare.
        const message = e instanceof Error ? e.message : 'Could not cancel the frozen attempt';
        setError(`${message}. Retry preparation, then cancel the saved attempt.`);
        setBusy(false);
        return;
      }
      setBusy(false);
    }
    if (savedId) await withRegistrationLock(async () => {
      removeInitialMintAttempt(savedId);
    });
    onBack();
  };

  const shown = intent?.registration ?? registration ?? restoredRegistration;
  const built = intent?.status === 'BUILT';
  return <Card className="p-5 space-y-4">
    <div>
      <h4 className="text-lg font-semibold text-white">
        {built ? 'Saved registration ready to resume' : 'Optional Veridian approval'}
      </h4>
      <p className="text-sm text-dark-300 mt-1">
        {built
          ? 'This registration and its policy ID are frozen. Resume this saved chain, or check whether it has expired before starting a different policy.'
          : 'Veridian records your KERI identity’s approval of this exact initial mint. Your Cardano wallet still signs the transactions.'}
      </p>
    </div>
    {built && <div className="rounded border border-dark-700 bg-dark-900 p-3 text-sm text-dark-200 space-y-1">
      <p>Policy <span className="font-mono break-all text-white">{intent.fields.tokenPolicyId}</span></p>
      <p>Initial mint {intent.fields.quantity} · Cap {shown?.initialMintableAmount ?? '0'} · Receiver KYC {shown?.requiresReceiverKyc ? 'on' : 'off'}</p>
    </div>}
    {intent?.targetTxHash && <div className="rounded border border-primary-700/50 bg-primary-900/10 p-3 text-sm space-y-2" aria-label="Veridian signing payload">
      <p className="font-medium text-white">What Veridian approves</p>
      <p className="text-dark-300">This is the exact self-addressing payload Veridian approves. The digest in <span className="font-mono">d</span> is derived from the mint transaction hash.</p>
      <p className="font-mono break-all text-white">{`{"d":"${intent.digest}","txHash":"${intent.targetTxHash}"}`}</p>
      <p className="text-dark-300">Payload digest (SAID): <span className="font-mono break-all text-white">{intent.digest}</span></p>
    </div>}
    <details className="rounded border border-dark-700 p-3 text-xs text-dark-200">
      <summary className="cursor-pointer text-sm font-medium text-white">Show saved settings and transaction details</summary>
      <div className="mt-3 space-y-1">
      <p>Policy: <span className="font-mono break-all">{intent?.fields.tokenPolicyId ?? 'Derived when the attempt is prepared'}</span></p>
      <p>Fee payer: <span className="font-mono break-all">{shown?.feePayerAddress ?? feePayerAddress}</span></p>
      <p>Admin key hash: <span className="font-mono break-all">{shown?.adminPubKeyHash ?? 'Unavailable'}</span></p>
      <p>Initial quantity: {intent?.fields.quantity ?? shown?.initialMintQuantity}</p>
      <p>Asset name (hex): <span className="font-mono break-all">{intent?.fields.assetName ?? shown?.assetName}</span></p>
      <p>Recipient: <span className="font-mono break-all">{intent?.fields.recipientAddress ?? shown?.recipientAddress ?? feePayerAddress}</span></p>
      <p>Mintable cap: {shown?.initialMintableAmount ?? '0'} · Receiver KYC: {shown?.requiresReceiverKyc ? 'on' : 'off'}</p>
      <p>Genesis allowlist seed: {shown?.seedRecipientInAllowlistAtGenesis ? 'on' : 'off'} · CIP-68 metadata: {shown?.cip68Metadata ? 'included' : 'none'}</p>
      <p>Trusted entities: {shown?.initialTrustedEntityVkeys?.length ?? 0}</p>
      {shown?.initialTrustedEntityVkeys?.map((key) =>
        <p key={key} className="font-mono break-all pl-3">{key}</p>)}
      <p>Network: {config?.network ?? 'Loading'} · KERI signer: {intent?.signerAid ?? 'After connection'}</p>
      {intent?.targetTxHash && <p>Mint transaction hash: <span className="font-mono break-all">{intent.targetTxHash}</span></p>}
      {intent?.status === 'BUILT' && <div>Saved transaction hashes:
        {Object.entries(intent.transactionHashes ?? {}).map(([name, hash]) =>
          <p key={name} className="font-mono break-all">{name}: {hash}</p>)}
      </div>}
      </div>
    </details>
    {!built && <p className="text-xs text-dark-300">This adds a Veridian request and a Cardano metadata transaction fee. The attestation records approval; it does not perform KYC.</p>}
    {!intent && preparationError && <p role="alert" className="text-sm text-amber-300">
      Finish the registration settings before preparing: {preparationError}
    </p>}
    {intent && ['EXPIRED', 'RELEASED', 'CANCELED'].includes(intent.status) &&
      <p role="alert" className="text-sm text-amber-300">This prepared attempt has expired or was cancelled. Return to settings and prepare a new one.</p>}
    {error && <p role="alert" className="text-sm text-red-300">{error}</p>}
    {intent && ['ANCHORING', 'BUILDING'].includes(intent.status) &&
      <p className="text-sm text-amber-300">Approval or chain building is still in progress. Check status before retrying.</p>}
    {(intent || attemptId) &&
      <Button variant="outline" onClick={refreshIntent} disabled={busy}>Check approval status</Button>}
    {['BUILT', 'ARCHIVED_EXPIRED'].includes(intent?.status ?? '') && <div className="space-y-2">
      <Button variant="outline" onClick={checkRecovery} disabled={busy}>Check whether a new policy is possible</Button>
      {recovery && <p role="status" className="text-xs text-dark-300">{recovery.reason}</p>}
      {recovery?.canStartNewPolicy && ['EXPIRED_UNSTARTED', 'ARCHIVED_EXPIRED'].includes(recovery.status) &&
        <Button onClick={startNewPolicy} disabled={busy || !config}>Archive old attempt and use new settings</Button>}
    </div>}
    {!built && !blockedWallet && !connected && !oobi && <Button onClick={loadOobi} disabled={busy}>Connect Veridian wallet</Button>}
    {!built && !blockedWallet && !connected && oobi && <div className="space-y-2">
      <p className="text-xs text-dark-300">Add the backend OOBI in Veridian, then paste your Veridian OOBI.</p>
      <p className="text-xs font-mono break-all text-primary-400">{oobi}</p>
      <Button variant="outline" onClick={() => navigator.clipboard.writeText(oobi)}>Copy backend OOBI</Button>
      <Input label="Veridian OOBI" value={walletOobi} onChange={(e) => setWalletOobi(e.target.value)} />
      <Button onClick={connect} disabled={busy || !walletOobi.trim()}>Connect</Button>
    </div>}
    {!built && !blockedWallet && connected && !credential && <div className="space-y-2">
      <p className="text-sm text-dark-300">Present a credential from the Veridian profile you will use to approve this mint.</p>
      {!roles && <Button variant="outline" onClick={async () => {
        try { setRoles((await getAvailableRoles(session.current!)).availableRoles); }
        catch (e) { setError(e instanceof Error ? e.message : 'Could not load roles'); }
      }}>Load available roles</Button>}
      {roles?.map((r) => <button key={r.role} type="button" onClick={() => setRole(r.role)}
        className={`block w-full rounded border p-2 text-left text-sm ${role === r.role ? 'border-primary-500 text-white' : 'border-dark-700 text-dark-300'}`}>{r.label}</button>)}
      <Button onClick={present} disabled={busy || !role}>Present credential</Button>
    </div>}
    {!blockedWallet && ((connected && credential) || built) && <Button onClick={approveAndBuild} disabled={busy || !config || (!intent && Boolean(preparationError)) || Boolean(intent && ['EXPIRED', 'RELEASED', 'CANCELED', 'ARCHIVED_EXPIRED'].includes(intent.status))}>
      {busy ? 'Waiting…' : intent?.status === 'BUILT' ? 'Resume registration chain' : intent ? 'Approve in Veridian & register' : 'Prepare and review mint intent'}
    </Button>}
    {!built && !blockedWallet && <Button variant="outline" onClick={goBack} disabled={busy}>Cancel approval and edit settings</Button>}
    <Button variant="ghost" onClick={onDismiss} disabled={busy}>Close</Button>
  </Card>;
}
