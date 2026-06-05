"use client";

import {
  createContext,
  useContext,
  useState,
  useCallback,
  useMemo,
  type ReactNode,
} from "react";

// ---------------------------------------------------------------------------
// Address conversion (CIP-30 hex → bech32)
// Uses Evolution SDK via our CIP-113 SDK
// ---------------------------------------------------------------------------

import { addressHexToBech32, evoClient, preprodChain, previewChain, mainnetChain, EvoTransactionWitnessSet } from "@easy1staking/cip113-sdk-ts";
import { CBOR as EvoCBOR } from "@evolution-sdk/evolution";
import * as cbor from "cbor";

/**
 * Body-preserving variant of `assembleSignedTx`.
 *
 * Evolution SDK's `addVKeyWitnessesHex` (used by the upstream `assembleSignedTx`)
 * decodes the whole tx through a "format tree reconciliation" pass and re-encodes
 * it. In practice the reconciliation isn't byte-perfect for every tx shape — we
 * observed the body bytes changing when merging vkey witnesses into a Conway
 * genesis tx with mints + a RegCert + a publish redeemer. The wallet had signed
 * against the ORIGINAL body hash, so the submitted (re-encoded) body failed
 * verification with `InvalidWitnessesUTXOW`.
 *
 * This implementation parses the outer 4-element CBOR array
 *   `[body, witnessSet, isValid, auxiliaryData]`
 * and substitutes ONLY the witness-set entry. The body, isValid, and auxData
 * sub-byte-slices are concatenated verbatim, so the body hash the wallet signed
 * still matches what's submitted.
 *
 * Other witness-set entries (scripts, redeemers, datums) come from the unsigned
 * tx's witness set; the wallet's witness set contributes only the vkey witnesses
 * (key 0).
 */
/** Decode a CBOR map header: returns (count, bodyStartOffset). */
function decodeMapHeader(buf: Uint8Array, offset: number): { count: number; bodyStart: number } {
  const firstByte = buf[offset];
  const majorType = (firstByte >> 5) & 0x07;
  if (majorType !== 5) {
    throw new Error(`expected CBOR map (major type 5), got major type ${majorType} at offset ${offset}`);
  }
  const additionalInfo = firstByte & 0x1f;
  if (additionalInfo < 24) {
    return { count: additionalInfo, bodyStart: offset + 1 };
  }
  if (additionalInfo === 24) {
    return { count: buf[offset + 1], bodyStart: offset + 2 };
  }
  if (additionalInfo === 25) {
    return { count: (buf[offset + 1] << 8) | buf[offset + 2], bodyStart: offset + 3 };
  }
  if (additionalInfo === 26) {
    return {
      count: (buf[offset + 1] * 0x1000000) + (buf[offset + 2] << 16) + (buf[offset + 3] << 8) + buf[offset + 4],
      bodyStart: offset + 5,
    };
  }
  throw new Error(`unsupported CBOR map count encoding: additionalInfo=${additionalInfo}`);
}

/** Encode a CBOR map header for the given entry count. */
function encodeMapHeader(count: number): Uint8Array {
  const major = 5 << 5;
  if (count < 24) return Uint8Array.of(major | count);
  if (count < 256) return Uint8Array.of(major | 24, count);
  if (count < 65536) return Uint8Array.of(major | 25, (count >> 8) & 0xff, count & 0xff);
  return Uint8Array.of(major | 26,
    (count >> 24) & 0xff, (count >> 16) & 0xff, (count >> 8) & 0xff, count & 0xff);
}

function assembleSignedTxPreservingBody(unsignedTxHex: string, walletWitnessSetHex: string): string {
  console.log("[assembleSignedTxPreservingBody] starting — unsigned tx length:", unsignedTxHex.length / 2, "bytes");
  const txBytes = Buffer.from(unsignedTxHex, "hex");
  if (txBytes[0] !== 0x84) {
    throw new Error(
      `unsigned tx CBOR must start with 0x84 (4-element array), got 0x${txBytes[0]?.toString(16)}`
    );
  }
  // Locate byte ranges of body and witnessSet using Evolution's offset-tracking decoder.
  const afterBody = EvoCBOR.decodeItemWithOffset(txBytes, 1).newOffset;
  const bodyBytes = txBytes.subarray(1, afterBody);
  const afterWs = EvoCBOR.decodeItemWithOffset(txBytes, afterBody).newOffset;
  // rest = isValid (1 byte) + optional auxiliaryData — keep verbatim.
  const restBytes = txBytes.subarray(afterWs);

  // ── Extract wallet's key-0 value BYTES (verbatim) ──
  // The wallet witness set is a CBOR map. We want the raw bytes that encode
  // the value at key 0 — `Tag(258, [[vkey, sig], …])` — so we can splice them
  // directly into the unsigned tx's witness set without re-encoding (which
  // would change byte representation and break the body's script_integrity_hash).
  const walletWsBytes = Buffer.from(walletWitnessSetHex, "hex");
  const walletHdr = decodeMapHeader(walletWsBytes, 0);
  if (walletHdr.count === 0) {
    console.warn("[assembleSignedTxPreservingBody] wallet returned empty witness set map");
    return unsignedTxHex;
  }
  // Find key 0 in the wallet's witness set. CIP-30 signTx returns just vkeys,
  // so key 0 is typically the FIRST (and only) entry.
  let walkOff = walletHdr.bodyStart;
  let walletKey0ValueStart = -1;
  let walletKey0ValueEnd = -1;
  for (let i = 0; i < walletHdr.count; i++) {
    const { item: keyVal, newOffset: afterKey } =
      EvoCBOR.decodeItemWithOffset(walletWsBytes, walkOff);
    const { newOffset: afterValue } =
      EvoCBOR.decodeItemWithOffset(walletWsBytes, afterKey);
    if (typeof keyVal === "bigint" ? keyVal === BigInt(0) : keyVal === 0) {
      walletKey0ValueStart = afterKey;
      walletKey0ValueEnd = afterValue;
      break;
    }
    walkOff = afterValue;
  }
  if (walletKey0ValueStart < 0) {
    console.warn("[assembleSignedTxPreservingBody] wallet witness set has no key 0 (vkey_witnesses) — dumping for inspection:",
      walletWitnessSetHex);
    return unsignedTxHex;
  }
  const walletKey0ValueBytes = walletWsBytes.subarray(walletKey0ValueStart, walletKey0ValueEnd);
  console.log("[assembleSignedTxPreservingBody] extracted wallet key-0 value bytes:",
    walletKey0ValueBytes.length, "bytes");

  // ── Splice key 0 into the unsigned tx's witness set ──
  // Strategy: assume the unsigned tx's witness set does NOT already have
  // key 0 (Bloxbean doesn't pre-sign in our flow). We append a new entry
  // at the START of the map (canonical CBOR sort: 0 sorts before all other
  // unsigned integer keys), incrementing the entry count by 1. All existing
  // entries (datums at key 4, redeemers at key 5) are preserved byte-for-byte,
  // so the body's script_integrity_hash check passes.
  const oldWsHdr = decodeMapHeader(txBytes, afterBody);
  const oldWsBodyBytes = txBytes.subarray(oldWsHdr.bodyStart, afterWs);

  // Defensive: scan for an existing key 0 in the unsigned tx's witness set.
  // If found we'd need to merge values, which would require re-encoding.
  // Bloxbean's QuickTx pipeline doesn't pre-sign, so we don't expect this.
  let unsignedHasKey0 = false;
  let scanOff = oldWsHdr.bodyStart;
  for (let i = 0; i < oldWsHdr.count; i++) {
    const { item: keyVal, newOffset: afterKey } = EvoCBOR.decodeItemWithOffset(txBytes, scanOff);
    const { newOffset: afterVal } = EvoCBOR.decodeItemWithOffset(txBytes, afterKey);
    if (typeof keyVal === "bigint" ? keyVal === BigInt(0) : keyVal === 0) {
      unsignedHasKey0 = true;
      break;
    }
    scanOff = afterVal;
  }
  if (unsignedHasKey0) {
    throw new Error("unsigned tx already has vkey witnesses (key 0) — byte-level splice not supported in this case");
  }

  const newWsHeader = encodeMapHeader(oldWsHdr.count + 1);
  const keyZeroByte = Uint8Array.of(0x00);
  const newWsBytes = Buffer.concat([
    newWsHeader,
    keyZeroByte,
    walletKey0ValueBytes,
    oldWsBodyBytes,
  ]);
  console.log("[assembleSignedTxPreservingBody] witness set: oldCount=", oldWsHdr.count,
    "newCount=", oldWsHdr.count + 1, "newWsBytes len=", newWsBytes.length);

  const result = Buffer.concat([
    Buffer.from([0x84]),
    bodyBytes,
    newWsBytes,
    restBytes,
  ]).toString("hex");

  // Verify body bytes are preserved by re-parsing the result and comparing
  // the body sub-slice against the input body sub-slice.
  const resultBytes = Buffer.from(result, "hex");
  const resultBodyEnd = EvoCBOR.decodeItemWithOffset(resultBytes, 1).newOffset;
  const resultBodyBytes = resultBytes.subarray(1, resultBodyEnd);
  const bodyPreserved = Buffer.compare(bodyBytes, resultBodyBytes) === 0;
  console.log("[assembleSignedTxPreservingBody] body bytes preserved:", bodyPreserved,
    "— in body len:", bodyBytes.length, "out body len:", resultBodyBytes.length);
  if (!bodyPreserved) {
    console.error("[assembleSignedTxPreservingBody] BODY MUTATED — submission will fail with InvalidWitnesses",
      "\nin:", bodyBytes.toString("hex").slice(0, 200),
      "\nout:", resultBodyBytes.toString("hex").slice(0, 200));
  }

  return result;
}

// ---------------------------------------------------------------------------
// CIP-30 Wallet API types
// ---------------------------------------------------------------------------

/** CIP-30 wallet API returned by `getCardano()[name].enable()` */
export interface WalletApi {
  getUsedAddresses(): Promise<string[]>;
  getUnusedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
  getBalance(): Promise<string>;
  getUtxos(): Promise<string[] | undefined>;
  signTx(tx: string, partialSign?: boolean): Promise<string>;
  submitTx(tx: string): Promise<string>;
  /** CIP-103 batch signing (not all wallets support this) */
  signTxs(txs: string[], partialSign?: boolean): Promise<string[]>;
  getLovelace(): Promise<string>;
}

/** Shape of `getCardano()[name]` before enable() */
interface CIP30WalletEntry {
  name: string;
  icon: string;
  apiVersion?: string;
  enable(): Promise<WalletApi>;
  isEnabled(): Promise<boolean>;
}

// Use module augmentation only if getCardano() isn't already declared
// eslint-disable-next-line @typescript-eslint/no-namespace
declare namespace CardanoCIP30 {
  type CardanoWindow = Record<string, CIP30WalletEntry | undefined>;
}

function getCardano(): Record<string, CIP30WalletEntry | undefined> | undefined {
  if (typeof window === "undefined") return undefined;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (window as any).cardano as CardanoCIP30.CardanoWindow | undefined;
}

// ---------------------------------------------------------------------------
// Context types
// ---------------------------------------------------------------------------

export interface WalletContextValue {
  /** Whether a wallet is currently connected */
  connected: boolean;
  /** Name of the connected wallet (e.g., "eternl", "lace") */
  name: string;
  /** The CIP-30 wallet API (always non-null — throws if not connected) */
  wallet: WalletApi;
  /** Raw CIP-30 API from entry.enable() — for Evolution SDK withCip30() */
  rawApi: unknown | null;
  /** Connect to a wallet by its CIP-30 key (e.g., "eternl") */
  connect(walletKey: string): Promise<void>;
  /** Disconnect the current wallet */
  disconnect(): void;
}

/** Stub wallet that throws on any method call. Used before connecting. */
const DISCONNECTED_WALLET: WalletApi = {
  getUsedAddresses: () => { throw new Error("Wallet not connected"); },
  getUnusedAddresses: () => { throw new Error("Wallet not connected"); },
  getChangeAddress: () => { throw new Error("Wallet not connected"); },
  getBalance: () => { throw new Error("Wallet not connected"); },
  getUtxos: () => { throw new Error("Wallet not connected"); },
  signTx: () => { throw new Error("Wallet not connected"); },
  submitTx: () => { throw new Error("Wallet not connected"); },
  signTxs: () => { throw new Error("Wallet not connected"); },
  getLovelace: () => { throw new Error("Wallet not connected"); },
};

const WalletContext = createContext<WalletContextValue | null>(null);

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export function WalletProvider({ children }: { children: ReactNode }) {
  const [walletApi, setWalletApi] = useState<WalletApi>(DISCONNECTED_WALLET);
  const [walletName, setWalletName] = useState("");
  const [rawCip30Api, setRawCip30Api] = useState<unknown | null>(null);

  const connect = useCallback(async (walletKey: string) => {
    const entry = getCardano()?.[walletKey];
    if (!entry) {
      throw new Error(`Wallet "${walletKey}" not found in getCardano()`);
    }

    const api = await entry.enable();

    // Wrap the raw CIP-30 API to:
    // 1. Convert hex addresses to bech32 (CIP-30 returns hex, Mesh returned bech32)
    // 2. Assemble signed tx (CIP-30 signTx returns witness set, not full tx)
    // 3. Normalize getLovelace
    // 4. Add signTxs fallback
    const wrappedApi: WalletApi = {
      ...api,
      async getUsedAddresses() {
        const hexAddrs = await api.getUsedAddresses();
        return hexAddrs.map(addressHexToBech32);
      },
      async getUnusedAddresses() {
        const hexAddrs = await api.getUnusedAddresses();
        return hexAddrs.map(addressHexToBech32);
      },
      async getChangeAddress() {
        const hexAddr = await api.getChangeAddress();
        return addressHexToBech32(hexAddr);
      },
      async signTx(tx: string, partialSign?: boolean) {
        // CIP-30 signTx returns the witness set CBOR, not the full signed tx.
        // We need to assemble the full signed tx for submitTx.
        const witnessSetHex = await api.signTx(tx, partialSign);
        return assembleSignedTxPreservingBody(tx, witnessSetHex);
      },
      async getLovelace() {
        // CIP-30 getBalance() returns CBOR-encoded Value:
        // either a simple integer (lovelace only) or [lovelace, multiasset_map]
        const balanceCborHex = await api.getBalance();
        try {
          const decoded = cbor.decode(Buffer.from(balanceCborHex, "hex"));
          if (typeof decoded === "bigint" || typeof decoded === "number") {
            return decoded.toString();
          }
          if (Array.isArray(decoded) && decoded.length >= 1) {
            return decoded[0].toString();
          }
          return "0";
        } catch {
          return balanceCborHex;
        }
      },
      async signTxs(txs: string[], partialSign?: boolean) {
        // Use Evolution SDK's native CIP-103 signTxs — it probes
        // api.cip103.signTxs, api.experimental.signTxs, and falls back
        // to sequential api.signTx automatically.
        const network = process.env.NEXT_PUBLIC_NETWORK || "preprod";
        const chain = network === "mainnet" ? mainnetChain
          : network === "preview" ? previewChain
          : preprodChain;
        const evoSigner = evoClient(chain).withCip30(api as any);

        console.log("[Wallet] Signing", txs.length, "txs via Evolution SDK CIP-103");
        const witnessSets = await evoSigner.signTxs(txs);

        // Assemble each: merge witness set into unsigned tx CBOR
        return txs.map((txCbor, i) => {
          const wsHex = EvoTransactionWitnessSet.toCBORHex(witnessSets[i]);
          return assembleSignedTxPreservingBody(txCbor, wsHex);
        });
      },
    };

    setWalletApi(wrappedApi);
    setWalletName(entry.name);
    setRawCip30Api(api);
  }, []);

  const disconnect = useCallback(() => {
    setWalletApi(DISCONNECTED_WALLET);
    setWalletName("");
    setRawCip30Api(null);
  }, []);

  const value = useMemo<WalletContextValue>(
    () => ({
      connected: walletApi !== DISCONNECTED_WALLET,
      name: walletName,
      wallet: walletApi,
      rawApi: rawCip30Api,
      connect,
      disconnect,
    }),
    [walletApi, walletName, rawCip30Api, connect, disconnect]
  );

  return (
    <WalletContext.Provider value={value}>{children}</WalletContext.Provider>
  );
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * Hook to access wallet connection state and CIP-30 API.
 *
 * Drop-in replacement for `useWallet()` from `@meshsdk/react`.
 */
export function useWallet(): WalletContextValue {
  const ctx = useContext(WalletContext);
  if (!ctx) {
    throw new Error("useWallet must be used within a <WalletProvider>");
  }
  return ctx;
}
