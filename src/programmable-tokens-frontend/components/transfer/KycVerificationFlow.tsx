"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  QrCode,
  Camera,
  Shield,
  CheckCircle,
  Copy,
  ArrowRight,
  ArrowLeft,
  ChevronDown,
  ChevronUp,
} from "lucide-react";
import {
  getAgentOobi,
  resolveOobi,
  presentCredential,
  cancelPresentation,
  storeCardanoAddress,
  generateKycProof,
  getSession,
  getAvailableRoles,
  issueCredential,
  type KycProofResponse,
  type CredentialResponse,
  type AvailableRole,
} from "@/lib/api/keri";
import { setKycProof, type KycProofCookie } from "@/lib/utils/kyc-cookie";
import { getKeriSessionIdForWallet } from "@/lib/utils/keri-session";

type KycStep = 1 | 2 | 3 | 4;

interface KycVerificationFlowProps {
  policyId: string;
  senderAddress: string;
  onComplete: (proof: KycProofCookie) => void;
  onBack: () => void;
  /** When true, ignore any cached KERI session / KYC proof and start fresh at step 1.
   *  Set on entrypoints where the user explicitly came to (re-)verify (e.g. /verify page),
   *  so a stale cached proof from a prior transfer flow doesn't auto-complete the flow. */
  forceFresh?: boolean;
}

export function KycVerificationFlow({
  policyId,
  senderAddress,
  onComplete,
  onBack,
  forceFresh = false,
}: KycVerificationFlowProps) {
  const [step, setStep] = useState<KycStep>(1);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Per-wallet session id: switching wallets in the same tab yields a fresh
  // session, so the backend's cached credential / KYC proof from a previous
  // wallet cannot be inherited by the new one.
  const sessionIdRef = useRef(getKeriSessionIdForWallet(senderAddress));

  // Step 1: OOBI
  const [oobiUrl, setOobiUrl] = useState<string | null>(null);
  const [oobiQrDataUrl, setOobiQrDataUrl] = useState<string | null>(null);
  const [oobiCopied, setOobiCopied] = useState(false);

  // Step 2: Partner OOBI
  const [partnerOobi, setPartnerOobi] = useState("");
  const [isScanning, setIsScanning] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const scanIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const streamRef = useRef<MediaStream | null>(null);

  // Step 3: Role selection + credential
  const [availableRoles, setAvailableRoles] = useState<AvailableRole[] | null>(
    null
  );
  const [selectedRole, setSelectedRole] = useState<string | null>(null);
  const [credential, setCredential] = useState<CredentialResponse | null>(null);
  const [showIssueForm, setShowIssueForm] = useState(false);
  const [issueForm, setIssueForm] = useState({
    firstName: "",
    lastName: "",
    email: "",
  });
  const [isIssuing, setIsIssuing] = useState(false);
  const [issueError, setIssueError] = useState<string | null>(null);

  // Restore session on mount (skipped when caller asked for a fresh flow).
  useEffect(() => {
    if (forceFresh) return;
    const sessionId = sessionIdRef.current;
    getSession(sessionId)
      .then((data) => {
        if (data.exists && data.hasCredential && data.attributes) {
          setCredential({
            role: data.credentialRoleName ?? "USER",
            roleValue: data.credentialRole ?? 0,
            label: data.credentialRoleName ?? "User",
            attributes: data.attributes,
          });
          if (data.kycProofPayload) {
            const proof: KycProofCookie = {
              payloadHex: data.kycProofPayload,
              signatureHex: data.kycProofSignature!,
              entityVkeyHex: data.kycProofEntityVkey!,
              validUntilMs: data.kycProofValidUntil ?? 0,
              role: data.credentialRole ?? 0,
              roleName: data.credentialRoleName ?? "USER",
            };
            setKycProof(policyId, senderAddress, proof);
            onComplete(proof);
          } else {
            setStep(4);
          }
        }
      })
      .catch(() => {
        // No session — start from step 1
      });
  }, [policyId, onComplete, forceFresh]);

  // Cleanup camera on unmount
  useEffect(() => {
    return () => {
      stopCamera();
    };
  }, []);

  // ── Step 1: Load agent OOBI ──────────────────────────────────────────────

  const loadOobi = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const response = await getAgentOobi(sessionIdRef.current);
      setOobiUrl(response.oobi);

      const { default: QRCode } = await import("qrcode");
      const dataUrl = await QRCode.toDataURL(response.oobi, {
        width: 256,
        margin: 2,
        color: { dark: "#ffffff", light: "#00000000" },
      });
      setOobiQrDataUrl(dataUrl);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to load agent OOBI"
      );
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (step === 1 && !oobiUrl) {
      loadOobi();
    }
  }, [step, oobiUrl, loadOobi]);

  const copyOobi = useCallback(() => {
    if (oobiUrl) {
      navigator.clipboard.writeText(oobiUrl);
      setOobiCopied(true);
      setTimeout(() => setOobiCopied(false), 2000);
    }
  }, [oobiUrl]);

  // ── Step 2: Scan / paste partner OOBI ─────────────────────────────────────

  const stopCamera = useCallback(() => {
    if (scanIntervalRef.current) {
      clearInterval(scanIntervalRef.current);
      scanIntervalRef.current = null;
    }
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    }
    setIsScanning(false);
  }, []);

  const startCamera = useCallback(async () => {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "environment" },
      });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setIsScanning(true);

      scanIntervalRef.current = setInterval(() => {
        const video = videoRef.current;
        const canvas = canvasRef.current;
        if (!video || !canvas || video.readyState !== video.HAVE_ENOUGH_DATA)
          return;

        canvas.width = video.videoWidth;
        canvas.height = video.videoHeight;
        const ctx = canvas.getContext("2d");
        if (!ctx) return;

        ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
        const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);

        import("jsqr").then(({ default: jsQR }) => {
          const code = jsQR(
            imageData.data,
            imageData.width,
            imageData.height
          );
          if (code && code.data) {
            setPartnerOobi(code.data);
            stopCamera();
          }
        });
      }, 300);
    } catch {
      setError("Camera access denied. Please paste the OOBI manually.");
    }
  }, [stopCamera]);

  const resolvePartnerOobi = useCallback(async () => {
    if (!partnerOobi.trim()) return;
    setIsLoading(true);
    setError(null);
    try {
      await resolveOobi(sessionIdRef.current, partnerOobi.trim());
      await storeCardanoAddress(sessionIdRef.current, senderAddress);
      setStep(3);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to resolve partner OOBI"
      );
    } finally {
      setIsLoading(false);
    }
  }, [partnerOobi, senderAddress]);

  // ── Step 3: Role selection + present credential ───────────────────────────

  // Load available roles when entering step 3
  useEffect(() => {
    if (step === 3 && availableRoles === null && !credential) {
      getAvailableRoles(sessionIdRef.current)
        .then((data) => {
          setAvailableRoles(data.availableRoles ?? []);
          if (data.availableRoles?.length === 1) {
            setSelectedRole(data.availableRoles[0].role);
          }
        })
        .catch((e) =>
          setError(
            e instanceof Error
              ? e.message
              : "Failed to load available roles"
          )
        );
    }
  }, [step, availableRoles, credential]);

  const requestCredential = useCallback(async () => {
    if (!selectedRole) return;
    setIsLoading(true);
    setError(null);
    try {
      const response = await presentCredential(
        sessionIdRef.current,
        selectedRole
      );
      setCredential(response);
      setStep(4);
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      if (msg.includes("cancelled") || msg.includes("409")) {
        setError("Credential presentation was cancelled.");
      } else if (msg.includes("408") || msg.includes("Timed out")) {
        setError("Timed out waiting for credential. Please try again.");
      } else {
        setError(msg);
      }
    } finally {
      setIsLoading(false);
    }
  }, [selectedRole]);

  const handleCancelPresentation = useCallback(async () => {
    try {
      await cancelPresentation(sessionIdRef.current);
    } catch {
      // ignore
    }
  }, []);

  const handleIssueCredential = useCallback(async () => {
    const { firstName, lastName, email } = issueForm;
    if (!firstName.trim() || !lastName.trim() || !email.trim()) {
      setIssueError("All fields are required.");
      return;
    }
    setIsIssuing(true);
    setIssueError(null);
    try {
      await issueCredential(sessionIdRef.current, {
        firstName: firstName.trim(),
        lastName: lastName.trim(),
        email: email.trim(),
      });
      setIssueError(null);
      setShowIssueForm(false);
    } catch (err) {
      setIssueError(
        err instanceof Error ? err.message : "Failed to issue credential"
      );
    } finally {
      setIsIssuing(false);
    }
  }, [issueForm]);

  // ── Step 4: Generate proof ────────────────────────────────────────────────

  const handleGenerateProof = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const proofResponse = await generateKycProof(sessionIdRef.current);
      const proof: KycProofCookie = {
        payloadHex: proofResponse.payloadHex,
        signatureHex: proofResponse.signatureHex,
        entityVkeyHex: proofResponse.entityVkeyHex,
        validUntilMs: proofResponse.validUntilPosixMs,
        role: proofResponse.role,
        roleName: proofResponse.roleName,
      };
      setKycProof(policyId, senderAddress, proof);
      onComplete(proof);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Failed to generate KYC proof"
      );
    } finally {
      setIsLoading(false);
    }
  }, [policyId, onComplete]);

  // ── Step indicator ────────────────────────────────────────────────────────

  const steps = [
    { num: 1, label: "Share OOBI" },
    { num: 2, label: "Scan OOBI" },
    { num: 3, label: "Credential" },
    { num: 4, label: "Proof" },
  ];

  return (
    <div className="space-y-4">
      {/* Step indicator */}
      <div className="flex items-center justify-between px-2">
        {steps.map((s, i) => (
          <div key={s.num} className="flex items-center">
            <div
              className={`flex items-center justify-center w-7 h-7 rounded-full text-xs font-bold ${
                step >= s.num
                  ? "bg-primary-500 text-white"
                  : "bg-dark-700 text-dark-400"
              }`}
            >
              {step > s.num ? (
                <CheckCircle className="h-4 w-4" />
              ) : (
                s.num
              )}
            </div>
            <span
              className={`ml-1.5 text-xs hidden sm:inline ${
                step >= s.num ? "text-white" : "text-dark-500"
              }`}
            >
              {s.label}
            </span>
            {i < steps.length - 1 && (
              <div
                className={`mx-2 h-px w-6 ${
                  step > s.num ? "bg-primary-500" : "bg-dark-700"
                }`}
              />
            )}
          </div>
        ))}
      </div>

      {/* Error */}
      {error && (
        <Card className="p-3 border-red-500/30 bg-red-500/5">
          <p className="text-sm text-red-400">{error}</p>
        </Card>
      )}

      {/* Step 1: Share OOBI */}
      {step === 1 && (
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-2">
            <QrCode className="h-5 w-5 text-primary-400" />
            <h3 className="text-sm font-semibold text-white">
              Share Your OOBI
            </h3>
          </div>
          <p className="text-xs text-dark-400">
            Scan this QR code with your Veridian wallet to establish a
            connection. Alternatively, copy the OOBI URL and paste it in your
            wallet.
          </p>

          {isLoading ? (
            <div className="flex justify-center py-8">
              <div className="h-8 w-8 border-2 border-primary-500 border-t-transparent rounded-full animate-spin" />
            </div>
          ) : oobiQrDataUrl ? (
            <div className="flex flex-col items-center gap-3">
              <div className="bg-dark-900 rounded-lg p-4">
                <img
                  src={oobiQrDataUrl}
                  alt="Agent OOBI QR Code"
                  className="w-48 h-48"
                />
              </div>
              <button
                onClick={copyOobi}
                className="flex items-center gap-1.5 text-xs text-dark-400 hover:text-primary-400 transition-colors"
              >
                <Copy className="h-3 w-3" />
                {oobiCopied ? "Copied!" : "Copy OOBI URL"}
              </button>
            </div>
          ) : null}

          <div className="flex gap-2">
            <Button variant="ghost" onClick={onBack} className="flex-1">
              <ArrowLeft className="h-4 w-4 mr-1" />
              Back
            </Button>
            <Button
              variant="primary"
              onClick={() => setStep(2)}
              disabled={!oobiUrl}
              className="flex-1"
            >
              Next
              <ArrowRight className="h-4 w-4 ml-1" />
            </Button>
          </div>
        </Card>
      )}

      {/* Step 2: Scan Partner OOBI */}
      {step === 2 && (
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Camera className="h-5 w-5 text-primary-400" />
            <h3 className="text-sm font-semibold text-white">
              Scan Wallet OOBI
            </h3>
          </div>
          <p className="text-xs text-dark-400">
            Scan the QR code from your Veridian wallet, or paste the OOBI URL
            below.
          </p>

          {isScanning ? (
            <div className="relative rounded-lg overflow-hidden bg-dark-900">
              <video
                ref={videoRef}
                className="w-full"
                playsInline
                muted
              />
              <canvas ref={canvasRef} className="hidden" />
              <Button
                variant="ghost"
                className="absolute bottom-2 right-2"
                onClick={stopCamera}
              >
                Stop Scan
              </Button>
            </div>
          ) : (
            <Button
              variant="outline"
              onClick={startCamera}
              className="w-full"
              disabled={isLoading}
            >
              <Camera className="h-4 w-4 mr-2" />
              Start Camera Scan
            </Button>
          )}

          <div className="flex gap-2">
            <Input
              label=""
              value={partnerOobi}
              onChange={(e) => setPartnerOobi(e.target.value)}
              placeholder="Paste wallet OOBI URL"
              disabled={isLoading}
              className="flex-1"
            />
          </div>

          <div className="flex gap-2">
            <Button
              variant="ghost"
              onClick={() => {
                stopCamera();
                setStep(1);
              }}
            >
              <ArrowLeft className="h-4 w-4 mr-1" />
              Back
            </Button>
            <Button
              variant="primary"
              onClick={resolvePartnerOobi}
              disabled={!partnerOobi.trim() || isLoading}
              isLoading={isLoading}
              className="flex-1"
            >
              {isLoading ? "Resolving..." : "Resolve & Continue"}
            </Button>
          </div>
        </Card>
      )}

      {/* Step 3: Select Role + Present Credential */}
      {step === 3 && (
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-2">
            <Shield className="h-5 w-5 text-primary-400" />
            <h3 className="text-sm font-semibold text-white">
              Present Credential
            </h3>
          </div>

          {credential ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <Badge variant="success" size="sm">
                  Verified
                </Badge>
                <span className="text-sm text-white">
                  {credential.label} ({credential.role})
                </span>
              </div>
              <div className="bg-dark-900 rounded-lg p-3 space-y-1">
                {Object.entries(credential.attributes).map(([key, value]) => (
                  <div key={key} className="flex justify-between text-xs">
                    <span className="text-dark-400">{key}</span>
                    <span className="text-white font-mono">
                      {String(value)}
                    </span>
                  </div>
                ))}
              </div>
              <Button
                variant="primary"
                onClick={() => setStep(4)}
                className="w-full"
              >
                Generate Proof
                <ArrowRight className="h-4 w-4 ml-1" />
              </Button>
            </div>
          ) : isLoading ? (
            <div className="flex flex-col items-center py-6 gap-3">
              <div className="h-10 w-10 border-3 border-primary-500 border-t-transparent rounded-full animate-spin" />
              <p className="text-sm text-dark-300">
                Waiting for credential from wallet...
              </p>
              <p className="text-xs text-dark-500">
                Please present your credential in the Veridian wallet
              </p>
              <Button
                variant="ghost"
                onClick={handleCancelPresentation}
                className="mt-2"
              >
                Cancel
              </Button>
            </div>
          ) : (
            <div className="space-y-4">
              <p className="text-xs text-dark-400">
                Select the type of credential you want to present, then click
                the button below. Your wallet will prompt you to share it.
              </p>

              {/* Role selection */}
              {availableRoles === null && (
                <div className="flex justify-center py-4">
                  <div className="h-6 w-6 border-2 border-primary-500 border-t-transparent rounded-full animate-spin" />
                </div>
              )}

              {availableRoles !== null && availableRoles.length === 0 && (
                <p className="text-xs text-red-400">
                  No credential types are available. The signing entity may not
                  be registered.
                </p>
              )}

              {availableRoles !== null && availableRoles.length > 0 && (
                <div className="space-y-2">
                  {availableRoles.map((r) => (
                    <button
                      key={r.role}
                      type="button"
                      onClick={() => setSelectedRole(r.role)}
                      className={`w-full flex items-center justify-between px-4 py-3 rounded-lg border transition-all ${
                        selectedRole === r.role
                          ? "border-primary-500 bg-primary-500/10"
                          : "border-dark-700 bg-dark-900 hover:border-dark-600"
                      }`}
                    >
                      <div className="text-left">
                        <p
                          className={`text-sm font-medium ${
                            selectedRole === r.role
                              ? "text-primary-400"
                              : "text-white"
                          }`}
                        >
                          {r.label}
                        </p>
                        <p className="text-xs text-dark-500">{r.role}</p>
                      </div>
                      <Badge
                        variant={
                          selectedRole === r.role ? "success" : "default"
                        }
                        size="sm"
                      >
                        {r.role}
                      </Badge>
                    </button>
                  ))}
                </div>
              )}

              <div className="flex gap-2">
                <Button variant="ghost" onClick={() => setStep(2)}>
                  <ArrowLeft className="h-4 w-4 mr-1" />
                  Back
                </Button>
                <Button
                  variant="primary"
                  onClick={requestCredential}
                  disabled={!selectedRole}
                  className="flex-1"
                >
                  Request Presentation
                </Button>
              </div>

              {/* "I don't have a credential" section */}
              <div className="relative flex items-center justify-center py-2">
                <div className="absolute inset-0 flex items-center">
                  <div className="w-full border-t border-dark-700" />
                </div>
                <span className="relative bg-dark-800 px-3 text-xs text-dark-500">
                  or
                </span>
              </div>

              <button
                type="button"
                onClick={() => setShowIssueForm((v) => !v)}
                className="flex items-center gap-2 text-sm font-medium text-dark-400 hover:text-primary-400 transition-colors w-full justify-center"
              >
                {showIssueForm ? (
                  <ChevronUp className="h-4 w-4" />
                ) : (
                  <ChevronDown className="h-4 w-4" />
                )}
                I don&apos;t have a credential
              </button>

              {showIssueForm && (
                <div className="bg-dark-900 rounded-lg p-4 space-y-3">
                  <p className="text-xs text-dark-400">
                    Provide your details below and a basic User credential will
                    be issued to your wallet.
                  </p>
                  <Input
                    label=""
                    placeholder="First name"
                    value={issueForm.firstName}
                    onChange={(e) =>
                      setIssueForm((f) => ({
                        ...f,
                        firstName: e.target.value,
                      }))
                    }
                    disabled={isIssuing}
                  />
                  <Input
                    label=""
                    placeholder="Last name"
                    value={issueForm.lastName}
                    onChange={(e) =>
                      setIssueForm((f) => ({
                        ...f,
                        lastName: e.target.value,
                      }))
                    }
                    disabled={isIssuing}
                  />
                  <Input
                    label=""
                    placeholder="Email address"
                    type="email"
                    value={issueForm.email}
                    onChange={(e) =>
                      setIssueForm((f) => ({ ...f, email: e.target.value }))
                    }
                    disabled={isIssuing}
                  />
                  {issueError && (
                    <p className="text-xs text-red-400">{issueError}</p>
                  )}
                  <Button
                    variant="primary"
                    onClick={handleIssueCredential}
                    isLoading={isIssuing}
                    disabled={isIssuing}
                    className="w-full"
                  >
                    {isIssuing
                      ? "Issuing..."
                      : "Issue User Credential"}
                  </Button>
                </div>
              )}
            </div>
          )}
        </Card>
      )}

      {/* Step 4: Generate Proof */}
      {step === 4 && (
        <Card className="p-5 space-y-4">
          <div className="flex items-center gap-2">
            <CheckCircle className="h-5 w-5 text-green-400" />
            <h3 className="text-sm font-semibold text-white">Generate Proof</h3>
          </div>
          <p className="text-xs text-dark-400">
            Your credential has been verified. Generate a signed KYC proof to
            authorize token transfers. The proof will be stored in your browser
            and is valid for 30 days.
          </p>

          {credential && (
            <div className="bg-dark-900 rounded-lg p-3">
              <div className="flex items-center gap-2 mb-2">
                <Badge variant="success" size="sm">
                  {credential.role}
                </Badge>
                <span className="text-xs text-dark-400">
                  Credential verified
                </span>
              </div>
            </div>
          )}

          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => setStep(3)}>
              <ArrowLeft className="h-4 w-4 mr-1" />
              Back
            </Button>
            <Button
              variant="primary"
              onClick={handleGenerateProof}
              isLoading={isLoading}
              disabled={isLoading}
              className="flex-1"
            >
              {isLoading ? "Generating..." : "Generate & Continue"}
            </Button>
          </div>
        </Card>
      )}
    </div>
  );
}
