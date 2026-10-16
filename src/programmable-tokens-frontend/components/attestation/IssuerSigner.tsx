"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { verifyCip30Claim, hexToBytes } from "@/lib/rwa/cose";
import { buildAttestationBundle } from "@/lib/rwa/attestation";
import type { CmtaAttestation } from "@/types/api";

/** The slice of a CIP-30 wallet API this component needs. */
interface SignDataApi {
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
  signData(addressHex: string, payloadHex: string): Promise<{ signature: string; key: string }>;
}
interface InjectedWallet { name?: string; icon?: string; enable(): Promise<SignDataApi> }

function injectedWallets(): [string, InjectedWallet][] {
  if (typeof window === "undefined") return [];
  const cardano = (window as unknown as { cardano?: Record<string, unknown> }).cardano ?? {};
  return Object.entries(cardano).filter((entry): entry is [string, InjectedWallet] => {
    const value = entry[1] as InjectedWallet | undefined;
    return !!value && typeof value === "object" && typeof value.enable === "function";
  });
}

/**
 * Sign a 67-byte CMTA KYC claim with an ISSUER's CIP-30 wallet.
 *
 * The issuer wallet is its OWN session: this component calls `window.cardano[name].enable()`
 * and keeps the API handle locally. It never touches the app's wallet context, so the sender's
 * (or admin's) session — which later signs the transaction — is left alone.
 *
 * The result is verified HERE before it is handed on: the COSE signature over our claim (or its
 * Blake2b-224 in Ledger's hashed mode), and that the signing key is a trusted issuer.
 */
export function IssuerSigner({ claimHex, trustedVkeys, onSigned, disabled }: {
  claimHex: string | null;
  trustedVkeys: string[];
  onSigned: (result: { attestation: CmtaAttestation; bundle: string; hashed: boolean }) => void;
  disabled?: boolean;
}) {
  const [wallets, setWallets] = useState<[string, InjectedWallet][]>([]);
  const [walletKey, setWalletKey] = useState("");
  const [api, setApi] = useState<SignDataApi | null>(null);
  const [addresses, setAddresses] = useState<string[]>([]);
  const [address, setAddress] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hashedNote, setHashedNote] = useState(false);

  useEffect(() => { setWallets(injectedWallets()); }, []);

  const connect = async (key: string) => {
    setWalletKey(key); setApi(null); setAddresses([]); setAddress(""); setError(null);
    if (!key) return;
    try {
      setBusy(true);
      const wallet = wallets.find(([k]) => k === key)?.[1];
      if (!wallet) throw new Error("Wallet not found");
      const enabled = await wallet.enable();
      const used = await enabled.getUsedAddresses();
      const change = await enabled.getChangeAddress();
      const all = Array.from(new Set([...used, change].map((a) => a.toLowerCase())));
      setApi(enabled); setAddresses(all); setAddress(all[0] ?? "");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const sign = async () => {
    if (!api || !address || !claimHex) return;
    setError(null); setHashedNote(false);
    try {
      setBusy(true);
      const { signature, key } = await api.signData(address, claimHex);
      const verified = verifyCip30Claim(hexToBytes(claimHex), signature, key);
      if (!trustedVkeys.some((k) => k.toLowerCase() === verified.issuerVkeyHex))
        throw new Error(`This address signed with key ${verified.issuerVkeyHex}, which is not a trusted issuer `
          + "of this token. Pick the address whose payment key is the registered issuer key.");
      const attestation: CmtaAttestation = { payloadHex: claimHex.toLowerCase(),
        coseSign1Hex: signature.toLowerCase(), coseKeyHex: key.toLowerCase() };
      setHashedNote(verified.hashed);
      onSigned({ attestation, bundle: buildAttestationBundle(attestation), hashed: verified.hashed });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return <div className="space-y-2">
    <div className="grid gap-2 sm:grid-cols-[1fr_1.4fr]">
      <label className="space-y-1 text-xs text-dark-300">
        <span className="block">Issuer wallet</span>
        <select value={walletKey} disabled={disabled || busy} onChange={(e) => connect(e.target.value)}
          className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 text-sm text-white focus:border-primary-500 focus:outline-none">
          <option value="">{wallets.length ? "Choose a wallet…" : "No CIP-30 wallet found"}</option>
          {wallets.map(([key, w]) => <option key={key} value={key}>{w.name ?? key}</option>)}
        </select>
      </label>
      <label className="space-y-1 text-xs text-dark-300">
        <span className="block">Signing address (its payment key must be the trusted issuer key)</span>
        <select value={address} disabled={!api || busy} onChange={(e) => setAddress(e.target.value)}
          className="w-full rounded-lg border border-dark-700 bg-dark-800 px-3 py-2 font-mono text-xs text-white focus:border-primary-500 focus:outline-none">
          {addresses.length === 0 && <option value="">—</option>}
          {addresses.map((a) => <option key={a} value={a}>{a.slice(0, 18)}…{a.slice(-10)}</option>)}
        </select>
      </label>
    </div>
    <Button type="button" variant="outline" onClick={sign}
      disabled={disabled || busy || !api || !address || !claimHex} className={cn("text-xs")}>
      {busy ? "Waiting for the wallet…" : "Sign claim with issuer wallet"}
    </Button>
    <p className="text-xs text-dark-400">
      This opens a separate wallet session for the issuer. If the issuer and the sender use the SAME
      wallet extension with different accounts, switch the extension back to the sending account before
      you sign the transfer. Keep issuer keys dedicated to issuance — any signData by a trusted key over a
      valid claim counts as an attestation.
    </p>
    {hashedNote && <p className="text-xs text-warning-300">
      The wallet signed in hashed mode (e.g. Ledger): the device showed only a hash of the claim, so confirm
      the claim details above are correct before sharing.
    </p>}
    {error && <p role="alert" className="text-xs text-red-400">{error}</p>}
  </div>;
}
