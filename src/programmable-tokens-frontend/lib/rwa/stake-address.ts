import { RewardAccount } from "@evolution-sdk/evolution";
import type { CardanoNetwork } from "../utils/network";

export interface StakeMemberCredential {
  credentialHash: string;
  credentialType: 0 | 1;
}

/** Resolve a checksummed Cardano reward address to the CMTA member credential. */
export function resolveStakeMemberAddress(input: string, network: CardanoNetwork): StakeMemberCredential {
  const value = input.trim();
  if (value !== value.toLowerCase() && value !== value.toUpperCase())
    throw new Error("Stake address must not mix uppercase and lowercase letters");
  const normalized = value.toLowerCase();
  if (!/^stake(_test)?1[02-9ac-hj-np-z]+$/.test(normalized))
    throw new Error("Enter a stake1… or stake_test1… address, not a payment address or credential hash");

  let account: ReturnType<typeof RewardAccount.fromBech32>;
  try {
    account = RewardAccount.fromBech32(normalized);
  } catch {
    throw new Error("Invalid stake address or checksum");
  }
  // The SDK checks Bech32 but its decoder does not require a reward-address
  // header. A canonical round trip checks both the prefix and address type.
  if (RewardAccount.toBech32(account) !== normalized)
    throw new Error("Invalid stake address type or network prefix");

  const expectedNetworkId = network === "mainnet" ? 1 : 0;
  if (account.networkId !== expectedNetworkId)
    throw new Error(`Stake address is for the wrong network; this app is on ${network}`);
  const credential = account.stakeCredential;
  if (credential.hash.length !== 28)
    throw new Error("Stake credential must be 28 bytes");
  return {
    credentialHash: Array.from(credential.hash, (byte) => byte.toString(16).padStart(2, "0")).join(""),
    credentialType: credential._tag === "ScriptHash" ? 1 : 0,
  };
}
