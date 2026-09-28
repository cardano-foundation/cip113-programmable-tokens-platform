import { Address, RewardAccount } from "@evolution-sdk/evolution";
import type { CardanoNetwork } from "../utils/network";

export interface StakeMemberCredential {
  credentialHash: string;
  credentialType: 0 | 1;
}

/** Resolve a checksummed stake or base payment address to its CMTA member credential. */
export function resolveStakeMemberAddress(input: string, network: CardanoNetwork): StakeMemberCredential {
  const value = input.trim();
  if (value !== value.toLowerCase() && value !== value.toUpperCase())
    throw new Error("Cardano address must not mix uppercase and lowercase letters");
  const normalized = value.toLowerCase();
  const expectedNetworkId = network === "mainnet" ? 1 : 0;
  let credential: ReturnType<typeof RewardAccount.fromBech32>["stakeCredential"] | undefined;
  let networkId: number;
  if (/^stake(_test)?1[02-9ac-hj-np-z]+$/.test(normalized)) {
    let account: ReturnType<typeof RewardAccount.fromBech32>;
    try { account = RewardAccount.fromBech32(normalized); }
    catch { throw new Error("Invalid stake address or checksum"); }
    // The SDK checks Bech32 but does not require a reward-address header.
    if (RewardAccount.toBech32(account) !== normalized)
      throw new Error("Invalid stake address type or network prefix");
    credential = account.stakeCredential;
    networkId = account.networkId;
  } else if (/^addr(_test)?1[02-9ac-hj-np-z]+$/.test(normalized)) {
    let address: ReturnType<typeof Address.fromBech32>;
    try { address = Address.fromBech32(normalized); }
    catch { throw new Error("Invalid payment address or checksum"); }
    if (Address.toBech32(address) !== normalized)
      throw new Error("Invalid payment address type or network prefix");
    credential = address.stakingCredential;
    if (!credential)
      throw new Error("Enterprise addresses have no stake credential. Enter a base address or a stake address.");
    networkId = address.networkId;
  } else {
    throw new Error("Enter a stake1…, stake_test1…, addr1…, or addr_test1… address");
  }
  if (networkId !== expectedNetworkId)
    throw new Error(`Address is for the wrong network; this app is on ${network}`);
  if (credential.hash.length !== 28)
    throw new Error("Stake credential must be 28 bytes");
  return {
    credentialHash: Array.from(credential.hash, (byte) => byte.toString(16).padStart(2, "0")).join(""),
    credentialType: credential._tag === "ScriptHash" ? 1 : 0,
  };
}
