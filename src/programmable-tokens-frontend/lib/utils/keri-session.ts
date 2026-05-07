/** Per-wallet KERI session id. The backend's KERI agent caches credentials and
 *  signed KYC proofs per session id, so binding it to the wallet prevents one
 *  wallet from inheriting another's credential. */

import { extractStakeCredHashFromAddress } from "@/lib/utils/address";

const KEY_PREFIX = "keri-session-id";

/** Stable short identifier for a wallet, used as a storage-key suffix.
 *  Falls back to the bech32 address when the wallet has no stake credential. */
export function walletKeyForAddress(walletAddress: string): string {
  try {
    return extractStakeCredHashFromAddress(walletAddress);
  } catch {
    return walletAddress;
  }
}

export function getKeriSessionIdForWallet(walletAddress: string): string {
  if (typeof sessionStorage === "undefined") return crypto.randomUUID();
  const key = `${KEY_PREFIX}:${walletKeyForAddress(walletAddress)}`;
  let id = sessionStorage.getItem(key);
  if (!id) {
    id = crypto.randomUUID();
    sessionStorage.setItem(key, id);
  }
  return id;
}
