import { getCardanoNetwork } from "../utils/network";
import { bytesHex, hexBytes, type MemberLeaf } from "./member-root";
import { getPaymentKeyHash } from "../utils/address";

interface SignDataWallet {
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
  signData(addressHex: string, payloadHex: string): Promise<{ signature: string; key: string }>;
}

export async function findAdminWalletAddress(wallet: Pick<SignDataWallet, "getUsedAddresses" | "getChangeAddress">,
                                             adminHash: string): Promise<string> {
  const addresses = [...await wallet.getUsedAddresses(), await wallet.getChangeAddress()];
  const match = addresses.find((address) => {
    try { return getPaymentKeyHash(address).toLowerCase() === adminHash.toLowerCase(); }
    catch { return false; }
  });
  if (!match) throw new Error("Connected wallet has no address for the live GS admin key");
  return match;
}

function tuple(leaf: MemberLeaf): string {
  if (!/^[0-9a-f]{56}$/i.test(leaf.credentialHash)
      || (leaf.credentialType !== 0 && leaf.credentialType !== 1)
      || !Number.isSafeInteger(leaf.validUntilMs) || leaf.validUntilMs < 0)
    throw new Error("Invalid member in signed admin request");
  return `${leaf.credentialType}:${leaf.credentialHash.toLowerCase()}:${leaf.validUntilMs}`;
}

export function canonicalMemberBody(payer: string, manual: MemberLeaf | undefined,
                                    pending: MemberLeaf[]): string {
  const selected = pending.map(tuple).sort();
  if (new Set(selected.map((item) => item.slice(0, item.lastIndexOf(":")))).size !== selected.length)
    throw new Error("Duplicate pending member in admin request");
  return `payer=${payer.toLowerCase()}\nmanual=${manual ? tuple(manual) : "-"}\n`
    + `pending=${selected.length ? selected.join(",") : "-"}\n`;
}

/** CIP-30 signData authenticates an off-chain API call; it is not a CMTA attestation. */
export async function signRwaAdminRequest(args: {
  rawApi: unknown;
  policyId: string;
  gsPolicyId: string;
  adminHash: string;
  method: "GET" | "POST";
  path: string;
  canonicalBody: string;
}): Promise<Record<string, string>> {
  const wallet = args.rawApi as SignDataWallet | null;
  if (!wallet?.signData) throw new Error("Connected wallet does not support CIP-30 signData");
  const addressHex = [...await wallet.getUsedAddresses(), await wallet.getChangeAddress()]
    .find((candidate) => {
      try { return bytesHex(hexBytes(candidate).slice(1, 29)) === args.adminHash.toLowerCase(); }
      catch { return false; }
    });
  if (!addressHex) throw new Error("Connected wallet has no address for the live GS admin key");
  const address = hexBytes(addressHex);
  if (address.length < 29 || bytesHex(address.slice(1, 29)) !== args.adminHash.toLowerCase())
    throw new Error("Connected wallet is not the live GS admin");
  const nonceBytes = new Uint8Array(32);
  crypto.getRandomValues(nonceBytes);
  const nonce = bytesHex(nonceBytes);
  const issued = Date.now();
  const expires = issued + 300_000;
  const encodedBody = new TextEncoder().encode(args.canonicalBody);
  const bodyDigest = bytesHex(new Uint8Array(await crypto.subtle.digest("SHA-256", encodedBody)));
  const network = getCardanoNetwork();
  const payload = "CMTA admin API v1\n"
    + `audience=${network}:${args.gsPolicyId.toLowerCase()}\n`
    + `method=${args.method}\n`
    + `path=${args.path}\n`
    + `body-sha256=${bodyDigest}\n`
    + `nonce=${nonce}\n`
    + `issued=${issued}\n`
    + `expires=${expires}\n`;
  const signed = await wallet.signData(addressHex, bytesHex(new TextEncoder().encode(payload)));
  return {
    "X-CMTA-Address": addressHex.toLowerCase(),
    "X-CMTA-Nonce": nonce,
    "X-CMTA-Issued": String(issued),
    "X-CMTA-Expires": String(expires),
    "X-CMTA-Signature": signed.signature,
    "X-CMTA-Key": signed.key,
  };
}
