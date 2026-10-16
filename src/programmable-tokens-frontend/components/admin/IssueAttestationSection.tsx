"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Copy, ShieldCheck } from "lucide-react";
import { IssuerSigner } from "@/components/attestation/IssuerSigner";
import { buildCmtaAttestationPayloadHex, stakeIdentityFromBaseAddress } from "@/lib/rwa/attestation";
import { getRwaTokenGlobalState, verifyRwaAttestation } from "@/lib/api/rwa-token";
import type { AttestationCheck } from "@/types/api";

function localDateTimeValue(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * Issue a KYC attestation for ONE holder: build the 67-byte claim (the holder's stake credential,
 * this token, this network, a tier and an expiry), have a trusted issuer sign it with their CIP-30
 * wallet, let the backend confirm a transfer would accept it, and hand out a copy-paste bundle.
 *
 * Nothing is stored server-side. The bundle is bound to one holder, one token, one network and an
 * expiry, so it lets nobody else transfer — but it DOES reveal that the holder passed KYC at that
 * tier, hence "share only with the holder".
 */
export function IssueAttestationSection({ policyId }: { policyId: string }) {
  const [context, setContext] = useState<{ networkId: number; trustedVkeys: string[] } | null>(null);
  const [contextError, setContextError] = useState<string | null>(null);
  const [holder, setHolder] = useState("");
  const [tier, setTier] = useState("1");
  const [expiry, setExpiry] = useState(() => localDateTimeValue(Date.now() + 30 * 24 * 60 * 60 * 1000));
  const [bundle, setBundle] = useState<string | null>(null);
  const [check, setCheck] = useState<AttestationCheck | null>(null);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getRwaTokenGlobalState(policyId)
      .then((gs) => { if (!cancelled) setContext({ networkId: gs.networkId, trustedVkeys: gs.trustedEntityVkeys ?? [] }); })
      .catch((e) => { if (!cancelled) setContextError(e instanceof Error ? e.message : String(e)); });
    return () => { cancelled = true; };
  }, [policyId]);

  // Any change to the claim inputs invalidates a bundle built from the old claim.
  useEffect(() => { setBundle(null); setCheck(null); setCopied(false); }, [holder, tier, expiry]);

  const claim = useMemo((): { hex: string | null; error: string | null } => {
    if (!context || !holder.trim()) return { hex: null, error: null };
    try {
      return { hex: buildCmtaAttestationPayloadHex(holder.trim(), policyId, context.networkId,
        Number(tier), new Date(expiry).getTime()), error: null };
    } catch (e) {
      return { hex: null, error: e instanceof Error ? e.message : String(e) };
    }
  }, [context, holder, tier, expiry, policyId]);

  const identity = useMemo(() => {
    try { return holder.trim() ? stakeIdentityFromBaseAddress(holder.trim()) : null; } catch { return null; }
  }, [holder]);

  // The claim currently on screen; a verify that returns after the inputs changed must not show
  // a bundle for the OLD claim.
  const claimRef = useRef<string | null>(null);
  claimRef.current = claim.hex;

  const onSigned = async ({ attestation, bundle: text }: { attestation: { payloadHex: string; coseSign1Hex: string; coseKeyHex: string }; bundle: string }) => {
    setError(null); setCheck(null);
    try {
      const result = await verifyRwaAttestation(policyId, attestation);
      if (claimRef.current?.toLowerCase() !== attestation.payloadHex.toLowerCase()) return;
      setCheck(result);
      if (result.valid) setBundle(text);
      else setError(`The backend would not accept this attestation: ${result.reason}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return <Card className="p-6 space-y-4">
    <div className="flex items-center gap-2">
      <ShieldCheck className="h-5 w-5 text-primary-400" />
      <h2 className="text-lg font-semibold text-white">Issue a KYC attestation</h2>
    </div>
    <p className="text-xs text-dark-300">
      A trusted issuer signs a claim for one holder; the holder pastes the bundle into their transfer
      dialog. Nothing is stored on the server.
    </p>
    {contextError && <p className="text-xs text-red-400">{contextError}</p>}
    {context && context.trustedVkeys.length === 0 && <p className="text-xs text-red-400">
      This token has no trusted issuer in its live global state; add one before issuing attestations.
    </p>}
    <div className="space-y-3">
      <label className="block space-y-1 text-xs text-dark-300">
        <span>Holder address (a base address — the claim binds its STAKE credential)</span>
        <Input value={holder} onChange={(e) => setHolder(e.target.value)} placeholder="addr1… / addr_test1…" />
      </label>
      <div className="grid gap-3 sm:grid-cols-[7rem_1fr]">
        <label className="space-y-1 text-xs text-dark-300">
          <span className="block">KYC tier</span>
          <input type="number" min="1" max="3" step="1" value={tier} onChange={(e) => setTier(e.target.value)}
            className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 text-sm text-white focus:border-primary-500 focus:outline-none" />
          <span className="block text-dark-400">1 User · 2 Institutional · 3 vLEI</span>
        </label>
        <label className="space-y-1 text-xs text-dark-300">
          <span className="block">Valid until (local time)</span>
          <input type="datetime-local" value={expiry} onChange={(e) => setExpiry(e.target.value)}
            className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 text-sm text-white focus:border-primary-500 focus:outline-none" />
        </label>
      </div>
      {claim.error && <p className="text-xs text-red-400">{claim.error}</p>}
      {claim.hex && identity && context && <div className="rounded-lg border border-dark-700 p-3 text-xs text-dark-300 space-y-0.5">
        <p className="font-medium text-dark-200">The claim you are about to sign</p>
        <p>Stake credential: <span className="font-mono break-all">{identity.credentialHash}</span> ({identity.credentialType === 0 ? "key" : "script"})</p>
        <p>Tier {tier} · valid until {new Date(expiry).toLocaleString()} · network {context.networkId}</p>
        <p>Token: <span className="font-mono break-all">{policyId}</span></p>
      </div>}
    </div>
    {context && <IssuerSigner claimHex={claim.hex} trustedVkeys={context.trustedVkeys}
      disabled={!claim.hex || context.trustedVkeys.length === 0} onSigned={onSigned} />}
    {error && <p role="alert" className="text-xs text-red-400">{error}</p>}
    {bundle && check?.valid && <div className="space-y-2 rounded-lg border border-success-700/40 bg-success-900/10 p-3">
      <p className="text-xs text-success-300">
        Accepted by the backend: tier {check.tier}, valid until {new Date(check.validUntilMs!).toLocaleString()}.
        <strong> Share only with the holder</strong> — it shows they passed KYC.
      </p>
      <textarea readOnly value={bundle} rows={4} spellCheck={false}
        className="w-full resize-none rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 font-mono text-xs text-white" />
      <Button type="button" variant="outline" className="text-xs" onClick={async () => {
        try { await navigator.clipboard.writeText(bundle); setCopied(true); } catch { setCopied(false); }
      }}><Copy className="mr-1 h-3.5 w-3.5" />{copied ? "Copied" : "Copy bundle"}</Button>
    </div>}
  </Card>;
}
