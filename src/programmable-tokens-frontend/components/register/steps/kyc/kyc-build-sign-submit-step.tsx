"use client";

import { useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { useWallet } from '@/hooks/use-wallet';
import { resolveTxHash } from '@/lib/utils/tx-hash';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { CopyButton } from '@/components/ui/copy-button';
import { useToast } from '@/components/ui/use-toast';
import { useProtocolVersion } from '@/contexts/protocol-version-context';
import { registerToken, stringToHex } from '@/lib/api';
import {
  getAgentOobi,
  resolveOobi,
  storeCardanoAddress,
  requestAttestation,
  presentCredential,
  cancelPresentation,
  getAvailableRoles,
  getSession,
  type AvailableRole,
  type CredentialResponse,
} from '@/lib/api/keri';
import { getPaymentKeyHash } from '@/lib/utils/address';
import { getExplorerTxUrl } from '@/lib/utils/format';
import type { KycRegisterRequest, Cip170AttestationData } from '@/types/api';
import type { StepComponentProps, TokenDetailsData } from '@/types/registration';
import { Shield, Copy, QrCode } from 'lucide-react';

type BuildStatus =
  | 'idle'
  | 'building'
  | 'preview'
  | 'signing'
  | 'submitting'
  | 'success'
  | 'error';

interface KycBuildResult {
  tokenPolicyId: string;
  regTxHash: string;
  globalStatePolicyId: string;
}

export function KycBuildSignSubmitStep({
  onComplete,
  onError,
  onBack,
  isProcessing,
  setProcessing,
  wizardState,
}: StepComponentProps<Record<string, unknown>, KycBuildResult>) {
  const { connected, wallet } = useWallet();
  const { toast: showToast } = useToast();
  const { selectedVersion } = useProtocolVersion();

  const [status, setStatus] = useState<BuildStatus>('idle');
  const [errorMessage, setErrorMessage] = useState('');

  const [unsignedCbor, setUnsignedCbor] = useState('');
  const [tokenPolicyId, setTokenPolicyId] = useState('');
  const [derivedTxHash, setDerivedTxHash] = useState('');
  const [regTxHash, setRegTxHash] = useState('');

  // CIP-170 attestation state
  const [enableAttestation, setEnableAttestation] = useState(false);
  const [attestationData, setAttestationData] = useState<Cip170AttestationData | null>(null);
  const [attestError, setAttestError] = useState<string | null>(null);
  const [isAttesting, setIsAttesting] = useState(false);

  const showToastRef = useRef(showToast);
  useEffect(() => { showToastRef.current = showToast; }, [showToast]);

  const tokenDetails = useMemo(() => {
    const detailsState = wizardState.stepStates['token-details'];
    return (detailsState?.data || {}) as Partial<TokenDetailsData>;
  }, [wizardState.stepStates]);

  const globalStatePolicyId = useMemo(() => {
    const kycState = wizardState.stepStates['kyc-config'];
    return (kycState?.data as { globalStatePolicyId?: string })?.globalStatePolicyId || '';
  }, [wizardState.stepStates]);

  // Get session ID from CIP-170 step if completed
  const cip170SessionId = useMemo(() => {
    const cip170State = wizardState.stepStates['cip170-auth-begin'];
    return (cip170State?.data as { sessionId?: string })?.sessionId || null;
  }, [wizardState.stepStates]);

  // OOBI connection state (for attestation when AUTH_BEGIN was skipped)
  const [attestSessionId, setAttestSessionId] = useState<string | null>(null);
  const [oobiUrl, setOobiUrl] = useState<string | null>(null);
  const [oobiCopied, setOobiCopied] = useState(false);
  const [partnerOobi, setPartnerOobi] = useState('');
  const [isConnecting, setIsConnecting] = useState(false);
  const [isOobiConnected, setIsOobiConnected] = useState(!!cip170SessionId);
  const [connectError, setConnectError] = useState<string | null>(null);

  // Credential presentation state — required before attestation can be anchored
  const [hasCredential, setHasCredential] = useState(false);
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(null);
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const [credential, setCredential] = useState<CredentialResponse | null>(null);
  const [isPresenting, setIsPresenting] = useState(false);
  const [presentError, setPresentError] = useState<string | null>(null);

  // Effective session ID: from AUTH_BEGIN or from inline OOBI connect
  const effectiveSessionId = cip170SessionId || attestSessionId;

  // If step 1 saved a session, probe it to see whether a credential is already admitted —
  // if so we can skip OOBI + presentation and go straight to attestation.
  useEffect(() => {
    if (!cip170SessionId) return;
    let cancelled = false;
    getSession(cip170SessionId)
      .then((s) => {
        if (cancelled) return;
        if (s.hasCredential) {
          setHasCredential(true);
        }
      })
      .catch(() => {
        // Best-effort: if session probe fails, leave hasCredential false and let
        // the user run the inline credential presentation below.
      });
    return () => { cancelled = true; };
  }, [cip170SessionId]);

  // ---- BUILD ----
  const handleBuild = useCallback(async () => {
    if (!connected || !wallet) {
      const msg = 'Wallet not connected';
      setStatus('error');
      setErrorMessage(msg);
      showToastRef.current({ title: 'Wallet Not Connected', description: msg, variant: 'error' });
      onError(msg);
      return;
    }
    if (!tokenDetails.assetName || !tokenDetails.quantity) {
      const msg = 'Token details are missing. Please go back and complete the Token Details step.';
      setStatus('error');
      setErrorMessage(msg);
      showToastRef.current({ title: 'Token Details Missing', description: msg, variant: 'error' });
      onError(msg);
      return;
    }
    if (!globalStatePolicyId) {
      const msg = 'Global State Policy ID is missing. Please go back and complete the KYC Configuration step.';
      setStatus('error');
      setErrorMessage(msg);
      showToastRef.current({ title: 'Global State Not Initialized', description: msg, variant: 'error' });
      onError(msg);
      return;
    }

    try {
      setProcessing(true);
      setErrorMessage('');
      setStatus('building');

      showToastRef.current({
        title: 'Building Transaction',
        description: 'Building KYC token registration...',
        variant: 'default',
      });

      const addresses = await wallet.getUsedAddresses();
      if (!addresses?.[0]) throw new Error('No wallet address found');
      const adminAddress = addresses[0];

      const adminPubKeyHash = getPaymentKeyHash(adminAddress);
      const regRequest: KycRegisterRequest = {
        substandardId: 'kyc',
        feePayerAddress: adminAddress,
        assetName: stringToHex(tokenDetails.assetName),
        quantity: tokenDetails.quantity,
        recipientAddress: tokenDetails.recipientAddress || '',
        adminPubKeyHash,
        globalStatePolicyId,
        ...(attestationData ? { attestation: attestationData } : {}),
      };

      const regResponse = await registerToken(regRequest, selectedVersion?.txHash);
      setTokenPolicyId(regResponse.policyId);
      setUnsignedCbor(regResponse.unsignedCborTx);
      setDerivedTxHash(await resolveTxHash(regResponse.unsignedCborTx));

      setStatus('preview');
      showToastRef.current({
        title: 'Transaction Built',
        description: 'Review and sign the registration transaction',
        variant: 'success',
      });
    } catch (error) {
      setStatus('error');
      const message = error instanceof Error ? error.message : 'Failed to build transaction';
      setErrorMessage(message);
      showToastRef.current({
        title: 'Build Failed',
        description: message,
        variant: 'error',
      });
      onError(message);
    } finally {
      setProcessing(false);
    }
  }, [connected, wallet, tokenDetails, globalStatePolicyId, selectedVersion, attestationData, onError, setProcessing]);

  // ---- OOBI CONNECT (for attestation without AUTH_BEGIN) ----
  const handleLoadOobi = useCallback(async () => {
    try {
      setIsConnecting(true);
      setConnectError(null);
      const newSessionId = crypto.randomUUID();
      setAttestSessionId(newSessionId);
      const data = await getAgentOobi(newSessionId);
      setOobiUrl(data.oobi);
    } catch {
      setConnectError('Failed to load agent OOBI');
    } finally {
      setIsConnecting(false);
    }
  }, []);

  const handleResolveOobi = useCallback(async () => {
    if (!partnerOobi.trim() || !attestSessionId) return;
    try {
      setIsConnecting(true);
      setConnectError(null);
      await resolveOobi(attestSessionId, partnerOobi.trim());
      const addresses = await wallet.getUsedAddresses();
      if (addresses?.[0]) {
        await storeCardanoAddress(attestSessionId, addresses[0]);
      }
      const rolesData = await getAvailableRoles(attestSessionId);
      setAvailableRoles(rolesData.availableRoles);
      setIsOobiConnected(true);
      showToastRef.current({
        title: 'Connected',
        description: 'OOBI resolved. Present a credential to continue.',
        variant: 'success',
      });
    } catch {
      setConnectError('Failed to resolve OOBI. Make sure the URL is correct.');
    } finally {
      setIsConnecting(false);
    }
  }, [partnerOobi, attestSessionId, wallet]);

  // ---- CREDENTIAL PRESENTATION (only needed when step 1 was skipped) ----
  const loadAvailableRoles = useCallback(async () => {
    if (!effectiveSessionId) return;
    try {
      const rolesData = await getAvailableRoles(effectiveSessionId);
      setAvailableRoles(rolesData.availableRoles);
    } catch (err) {
      setPresentError(err instanceof Error ? err.message : 'Failed to load roles');
    }
  }, [effectiveSessionId]);

  const handlePresentCredential = useCallback(async () => {
    if (!effectiveSessionId || !selectedRole) return;
    try {
      setIsPresenting(true);
      setPresentError(null);
      const cred = await presentCredential(effectiveSessionId, selectedRole);
      setCredential(cred);
      setHasCredential(true);
      showToastRef.current({
        title: 'Credential Verified',
        description: `${cred.label} credential admitted.`,
        variant: 'success',
      });
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to present credential';
      if (msg.includes('409')) setPresentError('Presentation was cancelled.');
      else if (msg.includes('408')) setPresentError('Timed out waiting for credential. Please try again.');
      else setPresentError(msg);
    } finally {
      setIsPresenting(false);
    }
  }, [effectiveSessionId, selectedRole]);

  const handleCancelPresent = useCallback(() => {
    if (!effectiveSessionId) return;
    cancelPresentation(effectiveSessionId).catch(() => {});
    setIsPresenting(false);
  }, [effectiveSessionId]);

  // ---- ATTESTATION ----
  const handleAttestation = useCallback(async () => {
    if (!effectiveSessionId || !tokenDetails.assetName || !tokenDetails.quantity) return;

    try {
      setIsAttesting(true);
      setAttestError(null);

      const unit = stringToHex(tokenDetails.assetName);
      const attestData = await requestAttestation(
        effectiveSessionId,
        unit,
        tokenDetails.quantity
      );

      setAttestationData(attestData);

      showToastRef.current({
        title: 'Attestation Anchored',
        description: 'Digest anchored in KEL. You can now build the registration transaction.',
        variant: 'success',
      });
    } catch (error) {
      console.error('Attestation error:', error);
      let errorMessage = 'Failed to anchor attestation';
      if (error instanceof Error) {
        if (error.message.includes('408')) {
          errorMessage = 'Wallet did not respond to anchor request in time.';
        } else if (error.message.includes('409')) {
          errorMessage = 'Attestation request was cancelled.';
        } else {
          errorMessage = error.message;
        }
      }
      setAttestError(errorMessage);
    } finally {
      setIsAttesting(false);
    }
  }, [effectiveSessionId, tokenDetails]);

  // ---- SIGN & SUBMIT ----
  const handleSignAndSubmit = useCallback(async () => {
    if (!connected || !wallet) {
      onError('Wallet not connected');
      return;
    }

    try {
      setProcessing(true);
      setErrorMessage('');
      setStatus('signing');

      showToastRef.current({
        title: 'Sign Transaction',
        description: 'Please sign the transaction in your wallet',
        variant: 'default',
      });

      const signedTx = await wallet.signTx(unsignedCbor, true);

      setStatus('submitting');
      showToastRef.current({
        title: 'Submitting',
        description: 'Submitting registration transaction...',
        variant: 'default',
      });

      const hash = await wallet.submitTx(signedTx);
      setRegTxHash(hash);

      setStatus('success');
      showToastRef.current({
        title: 'Registration Complete!',
        description: 'KYC token registered successfully',
        variant: 'success',
      });

      onComplete({
        stepId: 'kyc-build-sign',
        data: {
          tokenPolicyId,
          regTxHash: hash,
          globalStatePolicyId,
        },
        txHash: hash,
        completedAt: Date.now(),
      });
    } catch (error) {
      setStatus('error');
      const message = error instanceof Error ? error.message : 'Failed to sign or submit';
      setErrorMessage(message);

      if (message.toLowerCase().includes('user declined') ||
          message.toLowerCase().includes('user rejected')) {
        showToastRef.current({
          title: 'Transaction Cancelled',
          description: 'You cancelled the transaction',
          variant: 'default',
        });
      } else {
        showToastRef.current({
          title: 'Submission Failed',
          description: message,
          variant: 'error',
        });
        onError(message);
      }
    } finally {
      setProcessing(false);
    }
  }, [connected, wallet, unsignedCbor, tokenPolicyId, globalStatePolicyId, onComplete, onError, setProcessing]);

  const handleFullRetry = useCallback(() => {
    setStatus('idle');
    setErrorMessage('');
    setUnsignedCbor('');
    setTokenPolicyId('');
    setDerivedTxHash('');
    setRegTxHash('');
  }, []);

  const ExplorerLink = ({ txHash: hash }: { txHash: string }) => (
    <a
      href={getExplorerTxUrl(hash)}
      target="_blank"
      rel="noopener noreferrer"
      className="text-dark-400 hover:text-primary-400 transition-colors"
      title="View on cexplorer"
    >
      <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
      </svg>
    </a>
  );

  const isActive = status === 'building' || status === 'signing' || status === 'submitting';

  const getStatusMessage = () => {
    switch (status) {
      case 'building': return 'Building registration transaction...';
      case 'preview': return 'Review the transaction before signing';
      case 'signing': return 'Waiting for wallet signature...';
      case 'submitting': return 'Submitting registration...';
      case 'success': return 'Registration complete!';
      case 'error': return errorMessage || 'Operation failed';
      default: return 'Ready to build and register';
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">
          {status === 'preview' ? 'Review & Sign' : 'Build & Register'}
        </h3>
        <p className="text-dark-300 text-sm">
          {status === 'idle'
            ? 'Build the registration transaction, sign it, and submit.'
            : status === 'preview'
            ? 'Review the details below, then sign the transaction.'
            : 'Processing your registration...'}
        </p>
      </div>

      {/* Idle state */}
      {status === 'idle' && (
        <>
          {!globalStatePolicyId && (
            <Card className="p-4 bg-yellow-500/10 border-yellow-500/30">
              <div className="flex items-start gap-3">
                <svg className="w-5 h-5 text-yellow-400 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
                <div>
                  <p className="text-yellow-300 font-medium text-sm">Global State Not Initialized</p>
                  <p className="text-yellow-200/70 text-sm mt-1">
                    The KYC Configuration step has not been completed. Please go back and initialize the Global State before building the registration.
                  </p>
                </div>
              </div>
            </Card>
          )}
          <Card className="p-4 space-y-3">
            <h4 className="font-medium text-white">Registration Summary</h4>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span className="text-dark-400">Token Name</span>
                <p className="text-white font-medium">{tokenDetails.assetName || '-'}</p>
              </div>
              <div>
                <span className="text-dark-400">Initial Supply</span>
                <p className="text-white font-medium">
                  {tokenDetails.quantity ? BigInt(tokenDetails.quantity).toLocaleString() : '-'}
                </p>
              </div>
              <div>
                <span className="text-dark-400">Substandard</span>
                <p className="text-white font-medium">KYC</p>
              </div>
              <div className="col-span-2">
                <span className="text-dark-400">Global State Policy ID</span>
                <p className={`font-medium text-sm font-mono break-all ${globalStatePolicyId ? 'text-white' : 'text-yellow-400 italic'}`}>
                  {globalStatePolicyId || 'Not initialized — go back to KYC Configuration'}
                </p>
              </div>
              {tokenDetails.recipientAddress && (
                <div className="col-span-2">
                  <span className="text-dark-400">Recipient</span>
                  <p className="text-white font-medium text-sm truncate">
                    {tokenDetails.recipientAddress}
                  </p>
                </div>
              )}
            </div>
          </Card>

          <Card className="p-4 bg-blue-500/10 border-blue-500/30">
            <div className="flex items-start gap-3">
              <svg className="w-5 h-5 text-blue-400 flex-shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div>
                <p className="text-blue-300 font-medium text-sm">KYC Token Registration</p>
                <p className="text-blue-200/70 text-sm mt-1">
                  This will register a programmable token that requires KYC attestation for transfers.
                  Transfers must include a valid signature from a trusted entity in the Global State.
                </p>
              </div>
            </div>
          </Card>

          {/* CIP-170 Attestation Toggle */}
          <Card className="p-4">
            <label className="flex items-center gap-3 cursor-pointer">
              <input
                type="checkbox"
                checked={enableAttestation}
                onChange={(e) => {
                  setEnableAttestation(e.target.checked);
                  if (!e.target.checked) {
                    setAttestationData(null);
                    setAttestError(null);
                  }
                }}
                disabled={isProcessing}
                className="w-4 h-4 rounded border-dark-600 bg-dark-800 text-primary-500 focus:ring-primary-500"
              />
              <div>
                <p className="text-sm text-white font-medium">
                  Enable CIP-170 Attestation
                </p>
                <p className="text-xs text-dark-400">
                  Attest this registration with your Veridian wallet (anchors digest in
                  KEL and attaches ATTEST metadata to the registration transaction)
                </p>
              </div>
            </label>

            {enableAttestation && !attestationData && (
              <div className="mt-4 space-y-3">
                {/* Step indicator — mirrors the four phases of the CLI script so the
                    user can see exactly where they are in the KERI handshake. */}
                <div className="flex flex-col gap-1.5">
                  {[
                    ["Connect Veridian wallet (mutual OOBI)",   isOobiConnected],
                    ["Present a verifiable credential",          hasCredential],
                    ["Anchor registration digest in your KEL",   !!attestationData],
                    ["Submit the registration transaction",      false],
                  ].map(([label, done], idx) => {
                    const active = !done && (idx === 0 || (idx === 1 && isOobiConnected) || (idx === 2 && hasCredential) || (idx === 3 && !!attestationData));
                    return (
                      <div key={idx} className={`flex items-center gap-2 text-xs ${
                        done ? 'text-green-400' : active ? 'text-primary-400' : 'text-dark-500'
                      }`}>
                        <span className={`w-5 h-5 rounded-full border flex items-center justify-center ${
                          done ? 'border-green-500 bg-green-500/10'
                            : active ? 'border-primary-500 bg-primary-500/10'
                              : 'border-dark-700'
                        }`}>{done ? '✓' : (idx + 1)}</span>
                        <span>{String(label)}</span>
                      </div>
                    );
                  })}
                </div>

                {(connectError || attestError) && (
                  <div className="px-4 py-3 bg-red-500/10 border border-red-500/30 rounded-lg">
                    <p className="text-sm text-red-400">{connectError || attestError}</p>
                  </div>
                )}

                {isAttesting && hasCredential && (
                  <p className="text-xs text-dark-400">
                    👉 Open the Veridian app, look for an incoming remote-sign request, and tap
                    Approve. The backend is polling KEL — this can take up to 5 minutes.
                  </p>
                )}

                {/* Step 1: OOBI connect (only when no session from AUTH_BEGIN) */}
                {!isOobiConnected && !oobiUrl && (
                  <Button
                    variant="primary"
                    className="w-full"
                    onClick={handleLoadOobi}
                    isLoading={isConnecting}
                    disabled={isConnecting}
                  >
                    <QrCode className="h-4 w-4 mr-2" />
                    {isConnecting ? 'Loading...' : 'Connect Veridian Wallet'}
                  </Button>
                )}

                {/* Step 2: Share agent OOBI */}
                {!isOobiConnected && oobiUrl && !partnerOobi && (
                  <div className="space-y-3">
                    <p className="text-xs text-dark-400">
                      Share this OOBI URL with your Veridian wallet, then paste your wallet&apos;s OOBI below.
                    </p>
                    <div className="px-3 py-2 bg-dark-900 rounded-lg">
                      <p className="text-xs text-primary-400 font-mono break-all">{oobiUrl}</p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        navigator.clipboard.writeText(oobiUrl);
                        setOobiCopied(true);
                        setTimeout(() => setOobiCopied(false), 2000);
                      }}
                    >
                      <Copy className="h-4 w-4 mr-2" />
                      {oobiCopied ? 'Copied!' : 'Copy OOBI'}
                    </Button>
                  </div>
                )}

                {/* Step 3: Paste wallet OOBI + resolve */}
                {!isOobiConnected && oobiUrl && (
                  <div className="space-y-3">
                    <input
                      type="text"
                      value={partnerOobi}
                      onChange={(e) => setPartnerOobi(e.target.value)}
                      placeholder="Paste your Veridian wallet OOBI..."
                      className="w-full px-3 py-2 bg-dark-900 border border-dark-700 rounded-lg text-sm text-white placeholder-dark-500 focus:outline-none focus:border-primary-500"
                    />
                    <Button
                      variant="primary"
                      className="w-full"
                      onClick={handleResolveOobi}
                      isLoading={isConnecting}
                      disabled={!partnerOobi.trim() || isConnecting}
                    >
                      {isConnecting ? 'Resolving...' : 'Resolve & Connect'}
                    </Button>
                  </div>
                )}

                {/* Step 4: Present a credential if we don't already have one in the session */}
                {isOobiConnected && !hasCredential && (
                  <div className="space-y-3">
                    {presentError && (
                      <div className="px-4 py-3 bg-red-500/10 border border-red-500/30 rounded-lg">
                        <p className="text-sm text-red-400">{presentError}</p>
                      </div>
                    )}

                    <p className="text-xs text-dark-400">
                      Select a role and present the matching credential from your Veridian wallet.
                    </p>

                    {!availableRoles && (
                      <Button
                        variant="outline"
                        className="w-full text-xs h-7"
                        onClick={loadAvailableRoles}
                      >
                        Load available roles
                      </Button>
                    )}

                    {availableRoles && availableRoles.length > 0 && (
                      <div className="space-y-2">
                        {availableRoles.map((r) => (
                          <button
                            key={r.role}
                            type="button"
                            onClick={() => setSelectedRole(r.role)}
                            disabled={isPresenting}
                            className={`w-full px-4 py-3 rounded-lg border text-left transition-colors ${
                              selectedRole === r.role
                                ? 'border-primary-500 bg-primary-500/10 text-white'
                                : 'border-dark-700 bg-dark-800 text-dark-300 hover:border-dark-600'
                            }`}
                          >
                            <span className="font-medium">{r.label}</span>
                          </button>
                        ))}
                      </div>
                    )}

                    {availableRoles && availableRoles.length === 0 && (
                      <p className="text-xs text-yellow-400">
                        No issuable roles configured on the backend.
                      </p>
                    )}

                    <Button
                      variant="primary"
                      className="w-full"
                      onClick={handlePresentCredential}
                      isLoading={isPresenting}
                      disabled={!selectedRole || isPresenting}
                    >
                      {isPresenting ? 'Waiting for wallet...' : 'Request Credential Presentation'}
                    </Button>

                    {isPresenting && (
                      <Button variant="ghost" className="w-full" onClick={handleCancelPresent}>
                        Cancel
                      </Button>
                    )}
                  </div>
                )}

                {/* Step 5: Anchor attestation (session ready + credential admitted) */}
                {isOobiConnected && hasCredential && (
                  <>
                    {credential && (
                      <div className="px-4 py-3 bg-green-500/10 border border-green-500/30 rounded-lg">
                        <div className="flex items-center gap-2 mb-1">
                          <Shield className="h-4 w-4 text-green-400" />
                          <span className="text-sm text-green-400 font-medium">{credential.label} verified</span>
                        </div>
                      </div>
                    )}
                    <Button
                      variant="primary"
                      className="w-full"
                      onClick={handleAttestation}
                      isLoading={isAttesting}
                      disabled={isAttesting}
                    >
                      {isAttesting ? 'Waiting for wallet...' : 'Anchor Digest & Attest'}
                    </Button>
                  </>
                )}
              </div>
            )}

            {attestationData && (
              <div className="mt-4 px-4 py-3 bg-green-500/10 border border-green-500/30 rounded-lg">
                <div className="flex items-center gap-2 mb-2">
                  <Shield className="h-4 w-4 text-green-400" />
                  <span className="text-sm text-green-400 font-medium">Attestation Anchored</span>
                </div>
                <p className="text-xs text-dark-300">
                  Signer: {attestationData.signerAid}
                </p>
                <p className="text-xs text-dark-300">
                  Digest: {attestationData.digest}
                </p>
              </div>
            )}
          </Card>
        </>
      )}

      {/* Active spinner */}
      {isActive && (
        <Card className="p-6 text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-12 h-12 border-4 border-primary-500 border-t-transparent rounded-full animate-spin" />
          </div>
          <p className="text-dark-300 font-medium">{getStatusMessage()}</p>
        </Card>
      )}

      {/* Preview */}
      {status === 'preview' && (
        <>
          <Card className="p-4 space-y-3">
            <h4 className="font-medium text-white">Token Details</h4>
            <div className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <span className="text-dark-400">Token Name</span>
                <p className="text-white font-medium">{tokenDetails.assetName}</p>
              </div>
              <div>
                <span className="text-dark-400">Initial Supply</span>
                <p className="text-white font-medium">
                  {BigInt(tokenDetails.quantity || '0').toLocaleString()}
                </p>
              </div>
            </div>
          </Card>

          <Card className="p-4 space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="font-medium text-white">Token Policy ID</h4>
              <CopyButton value={tokenPolicyId} />
            </div>
            <p className="text-sm text-primary-400 font-mono break-all">{tokenPolicyId}</p>
          </Card>

          <Card className="p-4 space-y-2">
            <div className="flex items-center justify-between">
              <h4 className="font-medium text-white">Global State Policy ID</h4>
              <CopyButton value={globalStatePolicyId} />
            </div>
            <p className="text-sm text-cyan-400 font-mono break-all">{globalStatePolicyId}</p>
          </Card>

          {derivedTxHash && (
            <Card className="p-4 space-y-2">
              <div className="flex items-center justify-between">
                <h4 className="font-medium text-white">Registration Tx Hash</h4>
                <div className="flex items-center gap-1.5">
                  <CopyButton value={derivedTxHash} />
                  <ExplorerLink txHash={derivedTxHash} />
                </div>
              </div>
              <p className="text-sm text-primary-400 font-mono break-all">{derivedTxHash}</p>
            </Card>
          )}
        </>
      )}

      {/* Success */}
      {status === 'success' && (
        <Card className="p-6 text-center space-y-4">
          <div className="flex justify-center">
            <div className="w-12 h-12 rounded-full bg-green-500/20 flex items-center justify-center">
              <svg className="w-6 h-6 text-green-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
              </svg>
            </div>
          </div>
          <p className="text-green-400 font-medium">Registration Complete!</p>
          <div className="mt-4 space-y-3 text-left">
            <div className="p-3 bg-dark-800 rounded">
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs text-dark-400">Token Policy ID</span>
                <CopyButton value={tokenPolicyId} />
              </div>
              <p className="text-sm text-primary-400 font-mono break-all">{tokenPolicyId}</p>
            </div>
            <div className="p-3 bg-dark-800 rounded">
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs text-dark-400">Global State Policy ID</span>
                <CopyButton value={globalStatePolicyId} />
              </div>
              <p className="text-sm text-cyan-400 font-mono break-all">{globalStatePolicyId}</p>
            </div>
            {regTxHash && (
              <div className="p-3 bg-dark-800 rounded">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-xs text-dark-400">Registration Tx Hash</span>
                  <div className="flex items-center gap-1.5">
                    <CopyButton value={regTxHash} />
                    <ExplorerLink txHash={regTxHash} />
                  </div>
                </div>
                <p className="text-sm text-primary-400 font-mono break-all">{regTxHash}</p>
              </div>
            )}
            {attestationData && (
              <div className="p-3 bg-dark-800 rounded">
                <div className="flex items-center gap-2 mb-2">
                  <Shield className="h-4 w-4 text-green-400" />
                  <span className="text-xs text-green-400 font-medium">CIP-170 Attested</span>
                </div>
                <p className="text-xs text-dark-300">Signer: {attestationData.signerAid}</p>
                <p className="text-xs text-dark-300">Digest: {attestationData.digest}</p>
              </div>
            )}
          </div>
        </Card>
      )}

      {/* Error */}
      {status === 'error' && (
        <Card className="p-4 bg-red-500/10 border-red-500/30">
          <div className="flex items-start gap-3">
            <svg className="w-5 h-5 text-red-400 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
            </svg>
            <div>
              <p className="text-red-400 font-medium">Operation Failed</p>
              <p className="text-red-300 text-sm mt-1">{errorMessage}</p>
            </div>
          </div>
        </Card>
      )}

      {/* Actions */}
      <div className="flex gap-3">
        {onBack && status !== 'success' && !isActive && (
          <Button variant="outline" onClick={onBack} disabled={isProcessing}>
            Back
          </Button>
        )}

        {status === 'idle' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleBuild}
            disabled={isProcessing || !connected || !globalStatePolicyId || (enableAttestation && !attestationData)}
            title={
              !globalStatePolicyId
                ? 'Go back to complete the KYC Configuration step first'
                : enableAttestation && !attestationData
                  ? 'Complete the attestation step first'
                  : undefined
            }
          >
            Build Registration
          </Button>
        )}

        {status === 'preview' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleSignAndSubmit}
            disabled={isProcessing || !connected}
          >
            Sign & Submit
          </Button>
        )}

        {status === 'error' && (
          <Button
            variant="primary"
            className="flex-1"
            onClick={handleFullRetry}
            disabled={isProcessing}
          >
            Rebuild From Scratch
          </Button>
        )}
      </div>

      {!connected && (
        <p className="text-sm text-center text-dark-400">
          Connect your wallet to continue
        </p>
      )}
    </div>
  );
}
