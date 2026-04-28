"use client";

import { useState, useRef, useCallback } from 'react';
import { useWallet } from "@/hooks/use-wallet";
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { useToast } from '@/components/ui/use-toast';
import {
  getAgentOobi,
  resolveOobi,
  presentCredential,
  cancelPresentation,
  storeCardanoAddress,
  getAvailableRoles,
  publishCredentialChain,
  issueCredential,
  type CredentialResponse,
  type AvailableRole,
} from '@/lib/api/keri';
import type { StepComponentProps } from '@/types/registration';
import { QrCode, Shield, Copy, CheckCircle } from 'lucide-react';

interface Cip170StepData {
  authBeginTxHash: string;
  sessionId: string;
}

type Cip170SubStep = 'intro' | 'oobi-share' | 'oobi-scan' | 'credential' | 'publish' | 'done';

function getSessionId(): string {
  if (typeof sessionStorage === 'undefined') return crypto.randomUUID();
  let id = sessionStorage.getItem('register-cip170-session-id');
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem('register-cip170-session-id', id);
  }
  return id;
}

export function KycCip170Step({
  onDataChange,
  onComplete,
  onBack,
}: StepComponentProps<Cip170StepData>) {
  const { wallet } = useWallet();
  const { toast: showToast } = useToast();
  const sessionIdRef = useRef(getSessionId());

  const [subStep, setSubStep] = useState<Cip170SubStep>('intro');
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // OOBI state
  const [oobiUrl, setOobiUrl] = useState<string | null>(null);
  const [oobiCopied, setOobiCopied] = useState(false);
  const [partnerOobi, setPartnerOobi] = useState('');

  // Credential state
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(null);
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const [credential, setCredential] = useState<CredentialResponse | null>(null);
  const [credentialMode, setCredentialMode] = useState<'present' | 'issue'>('present');
  const [issueFirstName, setIssueFirstName] = useState('');
  const [issueLastName, setIssueLastName] = useState('');
  const [issueEmail, setIssueEmail] = useState('');

  // Publish state
  const [authBeginTxHash, setAuthBeginTxHash] = useState<string | null>(null);

  // ── Skip (optional step) ────────────────────────────────────────────────
  const handleSkip = () => {
    onDataChange({});
    onComplete({
      stepId: 'cip170-auth-begin',
      data: {},
      completedAt: Date.now(),
    });
  };

  // ── OOBI ────────────────────────────────────────────────────────────────
  const loadOobi = useCallback(async () => {
    try {
      setIsLoading(true);
      setError(null);
      const data = await getAgentOobi(sessionIdRef.current);
      setOobiUrl(data.oobi);
      setSubStep('oobi-share');
    } catch {
      setError('Failed to load agent OOBI');
    } finally {
      setIsLoading(false);
    }
  }, []);

  const handleResolveOobi = async () => {
    if (!partnerOobi.trim()) return;
    try {
      setIsLoading(true);
      setError(null);
      await resolveOobi(sessionIdRef.current, partnerOobi.trim());
      const addresses = await wallet.getUsedAddresses();
      if (addresses?.[0]) {
        await storeCardanoAddress(sessionIdRef.current, addresses[0]);
      }
      const rolesData = await getAvailableRoles(sessionIdRef.current);
      setAvailableRoles(rolesData.availableRoles);
      setSubStep('credential');
    } catch {
      setError('Failed to resolve OOBI. Make sure the URL is correct.');
    } finally {
      setIsLoading(false);
    }
  };

  // ── Credential presentation ─────────────────────────────────────────────
  const handlePresentCredential = async () => {
    if (!selectedRole) return;
    try {
      setIsLoading(true);
      setError(null);
      const cred = await presentCredential(sessionIdRef.current, selectedRole);
      setCredential(cred);
    } catch (err: unknown) {
      if (err instanceof Error) {
        if (err.message.includes('409')) setError('Presentation was cancelled.');
        else if (err.message.includes('408')) setError('Timed out waiting for credential. Please try again.');
        else setError(err.message);
      } else {
        setError('Failed to present credential');
      }
    } finally {
      setIsLoading(false);
    }
  };

  // ── Credential issuance ─────────────────────────────────────────────────
  const handleIssueCredential = async () => {
    if (!issueFirstName.trim() || !issueLastName.trim() || !issueEmail.trim()) return;
    try {
      setIsLoading(true);
      setError(null);
      const cred = await issueCredential(sessionIdRef.current, {
        firstName: issueFirstName.trim(),
        lastName: issueLastName.trim(),
        email: issueEmail.trim(),
      });
      setCredential(cred);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Failed to issue credential');
    } finally {
      setIsLoading(false);
    }
  };

  // ── Publish AUTH_BEGIN ───────────────────────────────────────────────────
  const handlePublish = async () => {
    try {
      setIsLoading(true);
      setError(null);

      const addresses = await wallet.getUsedAddresses();
      const feePayerAddress = addresses?.[0];
      if (!feePayerAddress) throw new Error('No wallet address found');

      const unsignedCbor = await publishCredentialChain(
        sessionIdRef.current,
        feePayerAddress,
      );

      const signedTx = await wallet.signTx(unsignedCbor);
      const txHash = await wallet.submitTx(signedTx);

      setAuthBeginTxHash(txHash);
      setSubStep('done');

      showToast({
        title: 'AUTH_BEGIN Published',
        description: `Credential chain published on-chain. Tx: ${txHash.slice(0, 16)}...`,
        variant: 'success',
      });

      onDataChange({ authBeginTxHash: txHash, sessionId: sessionIdRef.current });
      onComplete({
        stepId: 'cip170-auth-begin',
        data: { authBeginTxHash: txHash, sessionId: sessionIdRef.current },
        completedAt: Date.now(),
      });
    } catch (err) {
      console.error('AUTH_BEGIN publish error:', err);
      let msg = 'Failed to publish credential chain';
      if (err instanceof Error) {
        msg = err.message.includes('User declined') ? 'Transaction was cancelled' : err.message;
      }
      setError(msg);
    } finally {
      setIsLoading(false);
    }
  };

  // ── Render ──────────────────────────────────────────────────────────────

  const stepIndicator = (
    <div className="flex gap-2 mb-4">
      {['intro', 'oobi-share', 'oobi-scan', 'credential', 'publish'].map((s, i) => {
        const steps: Cip170SubStep[] = ['intro', 'oobi-share', 'oobi-scan', 'credential', 'publish'];
        const currentIdx = steps.indexOf(subStep === 'done' ? 'publish' : subStep);
        return (
          <div
            key={s}
            className={`h-1 flex-1 rounded-full ${i <= currentIdx ? 'bg-primary-500' : 'bg-dark-700'}`}
          />
        );
      })}
    </div>
  );

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold text-white mb-2">CIP-170 Credential Chain (Optional)</h3>
        <p className="text-dark-300 text-sm">
          Optionally publish your credential chain on-chain as a CIP-170 AUTH_BEGIN transaction.
          This establishes your signing authority on the blockchain and only needs to be done once.
          If you have already published your credential chain, you can skip this step.
        </p>
      </div>

      {stepIndicator}

      {error && (
        <Card className="p-4 bg-red-500/10 border-red-500/30">
          <p className="text-sm text-red-400">{error}</p>
        </Card>
      )}

      {/* Intro */}
      {subStep === 'intro' && (
        <Card className="p-4 space-y-4">
          <h4 className="text-sm font-medium text-white">What this does</h4>
          <ul className="text-sm text-dark-300 space-y-2">
            <li className="flex items-start gap-2">
              <span className="text-primary-400 font-mono text-xs mt-0.5">1.</span>
              <span>Connect to your Veridian wallet via OOBI exchange</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-primary-400 font-mono text-xs mt-0.5">2.</span>
              <span>Present a verifiable credential from your wallet</span>
            </li>
            <li className="flex items-start gap-2">
              <span className="text-primary-400 font-mono text-xs mt-0.5">3.</span>
              <span>Publish the credential chain on-chain (AUTH_BEGIN metadata at label 170)</span>
            </li>
          </ul>
          <div className="flex gap-3">
            {onBack && (
              <Button variant="outline" onClick={onBack}>Back</Button>
            )}
            <Button variant="primary" className="flex-1" onClick={loadOobi} isLoading={isLoading}>
              Start Credential Verification
            </Button>
            <Button variant="ghost" onClick={handleSkip}>
              Skip
            </Button>
          </div>
        </Card>
      )}

      {/* Step 1: Share OOBI */}
      {subStep === 'oobi-share' && (
        <Card className="p-4 space-y-4">
          <div className="flex items-center gap-2">
            <QrCode className="h-5 w-5 text-primary-400" />
            <h4 className="text-white font-medium">Share Agent OOBI</h4>
          </div>
          <p className="text-sm text-dark-400">
            Share this OOBI URL with your Veridian wallet to establish a connection.
          </p>
          {oobiUrl && (
            <div className="space-y-3">
              <div className="px-4 py-3 bg-dark-900 rounded-lg">
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
          <Button variant="primary" className="w-full" onClick={() => setSubStep('oobi-scan')} disabled={!oobiUrl}>
            Next: Enter Wallet OOBI
          </Button>
        </Card>
      )}

      {/* Step 2: Scan/Paste Wallet OOBI */}
      {subStep === 'oobi-scan' && (
        <Card className="p-4 space-y-4">
          <h4 className="text-white font-medium">Enter Wallet OOBI</h4>
          <p className="text-sm text-dark-400">
            Paste the OOBI URL from your Veridian wallet.
          </p>
          <Input
            label="Wallet OOBI URL"
            value={partnerOobi}
            onChange={(e) => setPartnerOobi(e.target.value)}
            placeholder="http://..."
            disabled={isLoading}
          />
          <Button
            variant="primary"
            className="w-full"
            onClick={handleResolveOobi}
            isLoading={isLoading}
            disabled={!partnerOobi.trim() || isLoading}
          >
            {isLoading ? 'Resolving...' : 'Resolve & Connect'}
          </Button>
        </Card>
      )}

      {/* Step 3: Present or Issue Credential */}
      {subStep === 'credential' && (
        <Card className="p-4 space-y-4">
          <div className="flex items-center gap-2">
            <Shield className="h-5 w-5 text-primary-400" />
            <h4 className="text-white font-medium">
              {credentialMode === 'present' ? 'Present Credential' : 'Issue Credential'}
            </h4>
          </div>

          {!credential ? (
            <>
              <div className="grid grid-cols-2 gap-2 p-1 bg-dark-900 rounded-lg">
                <button
                  onClick={() => { setCredentialMode('present'); setError(null); }}
                  disabled={isLoading}
                  className={`px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                    credentialMode === 'present'
                      ? 'bg-primary-500/20 text-primary-300 border border-primary-500/40'
                      : 'text-dark-400 hover:text-dark-200'
                  }`}
                >
                  I have a credential
                </button>
                <button
                  onClick={() => { setCredentialMode('issue'); setError(null); }}
                  disabled={isLoading}
                  className={`px-3 py-2 rounded-md text-sm font-medium transition-colors ${
                    credentialMode === 'issue'
                      ? 'bg-primary-500/20 text-primary-300 border border-primary-500/40'
                      : 'text-dark-400 hover:text-dark-200'
                  }`}
                >
                  Issue a new credential
                </button>
              </div>

              {credentialMode === 'present' ? (
                <>
                  <p className="text-sm text-dark-400">
                    Select a role and present your credential from your Veridian wallet.
                  </p>

                  {availableRoles && availableRoles.length > 0 && (
                    <div className="space-y-2">
                      {availableRoles.map((r) => (
                        <button
                          key={r.role}
                          onClick={() => setSelectedRole(r.role)}
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

                  <Button
                    variant="primary"
                    className="w-full"
                    onClick={handlePresentCredential}
                    isLoading={isLoading}
                    disabled={!selectedRole || isLoading}
                  >
                    {isLoading ? 'Waiting for wallet...' : 'Request Credential Presentation'}
                  </Button>

                  {isLoading && (
                    <Button variant="ghost" className="w-full" onClick={() => cancelPresentation(sessionIdRef.current)}>
                      Cancel
                    </Button>
                  )}
                </>
              ) : (
                <>
                  <p className="text-sm text-dark-400">
                    Don&apos;t have a credential yet? Provide your details and we&apos;ll issue one to your Veridian wallet.
                    Accept the offer in the wallet when prompted.
                  </p>

                  <div className="space-y-3">
                    <Input
                      label="First name"
                      value={issueFirstName}
                      onChange={(e) => setIssueFirstName(e.target.value)}
                      placeholder="Jane"
                      disabled={isLoading}
                    />
                    <Input
                      label="Last name"
                      value={issueLastName}
                      onChange={(e) => setIssueLastName(e.target.value)}
                      placeholder="Doe"
                      disabled={isLoading}
                    />
                    <Input
                      label="Email"
                      type="email"
                      value={issueEmail}
                      onChange={(e) => setIssueEmail(e.target.value)}
                      placeholder="jane@example.com"
                      disabled={isLoading}
                    />
                  </div>

                  <Button
                    variant="primary"
                    className="w-full"
                    onClick={handleIssueCredential}
                    isLoading={isLoading}
                    disabled={
                      isLoading ||
                      !issueFirstName.trim() ||
                      !issueLastName.trim() ||
                      !issueEmail.trim()
                    }
                  >
                    {isLoading ? 'Issuing credential...' : 'Issue Credential'}
                  </Button>
                </>
              )}
            </>
          ) : (
            <>
              <div className="px-4 py-3 bg-green-500/10 border border-green-500/30 rounded-lg">
                <div className="flex items-center gap-2 mb-2">
                  <CheckCircle className="h-4 w-4 text-green-400" />
                  <span className="text-sm text-green-400 font-medium">Credential Verified</span>
                </div>
                <div className="space-y-1">
                  {Object.entries(credential.attributes).map(([key, value]) => (
                    <p key={key} className="text-xs text-dark-300">
                      <span className="text-dark-500">{key}:</span> {String(value)}
                    </p>
                  ))}
                </div>
              </div>
              <Button variant="primary" className="w-full" onClick={() => setSubStep('publish')}>
                Continue to Publish
              </Button>
            </>
          )}
        </Card>
      )}

      {/* Step 4: Publish AUTH_BEGIN */}
      {subStep === 'publish' && (
        <Card className="p-4 space-y-4">
          <h4 className="text-white font-medium">Publish Credential Chain</h4>
          <p className="text-sm text-dark-400">
            Publish your credential chain on-chain as a CIP-170 AUTH_BEGIN transaction.
            This establishes your signing authority on the blockchain.
          </p>
          {credential && (
            <div className="px-4 py-3 bg-dark-900 rounded-lg">
              <p className="text-xs text-dark-400 mb-1">Credential</p>
              <p className="text-sm text-white">{credential.label}</p>
            </div>
          )}
          <Button
            variant="primary"
            className="w-full"
            onClick={handlePublish}
            isLoading={isLoading}
            disabled={isLoading}
          >
            {isLoading ? 'Building & signing...' : 'Sign & Publish AUTH_BEGIN Transaction'}
          </Button>
        </Card>
      )}

      {/* Done */}
      {subStep === 'done' && authBeginTxHash && (
        <Card className="p-4 space-y-4">
          <div className="flex items-center gap-2">
            <CheckCircle className="h-5 w-5 text-green-400" />
            <h4 className="text-green-400 font-medium">AUTH_BEGIN Published</h4>
          </div>
          <div className="px-4 py-3 bg-dark-900 rounded-lg">
            <p className="text-xs text-dark-400 mb-1">Transaction Hash</p>
            <p className="text-xs text-primary-400 font-mono break-all">{authBeginTxHash}</p>
          </div>
          <p className="text-sm text-dark-400">
            Proceeding to token registration...
          </p>
        </Card>
      )}
    </div>
  );
}
