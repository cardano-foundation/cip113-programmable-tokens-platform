import { getCardanoNetwork } from "../utils/network";
import { getApiBaseUrl } from "../api/client";
import { bytesHex, hexBytes } from "./member-root";

interface SignDataWallet {
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
  signData(addressHex: string, payloadHex: string): Promise<{ signature: string; key: string }>;
}

const BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

/** Decode the exact address bytes; comparing only payment key hashes would permit
 * a different stake credential or address type to authorize a payer address. */
export function payerAddressHex(address: string): string {
  const normalized = address.toLowerCase();
  if (normalized !== address && address.toUpperCase() !== address)
    throw new Error("Invalid mixed-case payer address");
  const separator = normalized.lastIndexOf("1");
  if (separator < 1 || !/^addr(_test)?$/.test(normalized.slice(0, separator)))
    throw new Error("Invalid payer address");
  const data = normalized.slice(separator + 1);
  if (data.length < 7) throw new Error("Invalid payer address");
  const values = [...data].map((char) => BECH32_CHARSET.indexOf(char));
  if (values.some((value) => value < 0)) throw new Error("Invalid payer address");
  let accumulator = 0;
  let bits = 0;
  const result: number[] = [];
  for (const value of values.slice(0, -6)) {
    accumulator = (accumulator << 5) | value;
    bits += 5;
    if (bits >= 8) {
      bits -= 8;
      result.push((accumulator >> bits) & 255);
    }
  }
  if (bits >= 5 || ((accumulator << (8 - bits)) & 255) !== 0 || result.length < 29)
    throw new Error("Invalid payer address bytes");
  return bytesHex(new Uint8Array(result));
}

/** Authorize the exact JSON bytes sent to a creation endpoint before it can
 * reserve any funding UTxO. This is API authentication, not a KYC attestation. */
export async function signRwaCreationRequest(args: {
  rawApi: unknown;
  feePayerAddress: string;
  path: "/rwa-token/build-chain" | "/rwa-token/init";
  serializedBody: string;
}): Promise<Record<string, string>> {
  const wallet = args.rawApi as SignDataWallet | null;
  if (!wallet?.signData || !wallet.getUsedAddresses || !wallet.getChangeAddress)
    throw new Error("Connected wallet does not support CIP-30 signData");

  const payerHex = payerAddressHex(args.feePayerAddress);
  const addresses = [...await wallet.getUsedAddresses(), await wallet.getChangeAddress()];
  const addressHex = addresses.find((candidate) => {
    try { return bytesHex(hexBytes(candidate)) === payerHex; }
    catch { return false; }
  });
  if (!addressHex) throw new Error("Connected wallet has no address matching the creation fee payer");

  const nonceBytes = new Uint8Array(32);
  crypto.getRandomValues(nonceBytes);
  const nonce = bytesHex(nonceBytes);
  const issued = Date.now();
  const expires = issued + 300_000;
  const bodyBytes = new TextEncoder().encode(args.serializedBody);
  const bodyDigest = bytesHex(new Uint8Array(await crypto.subtle.digest("SHA-256", bodyBytes)));
  const network = getCardanoNetwork();
  // The configured API endpoint identifies this deployment. The backend must
  // configure RWA_TOKEN_CREATION_AUDIENCE to this exact URL including /api/v1.
  const audience = `${getApiBaseUrl().replace(/\/+$/, "")}/api/v1`;
  const payload = "CMTA creation API v1\n"
    + `audience=${audience}\n`
    + `network=${network}\n`
    + `method=POST\n`
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
