/**
 * Extract the payment key hash from a Cardano address.
 *
 * Bech32 Shelley addresses encode the payment credential starting at byte 1:
 * - Byte 0: header (network + address type)
 * - Bytes 1-28: payment credential (key hash or script hash)
 * - Bytes 29-56: staking credential (if present)
 *
 * @param address Bech32 encoded Cardano address
 * @returns Hex-encoded payment key hash (56 characters)
 * @throws Error if payment key hash cannot be extracted
 */
/** Alias of {@link getPaymentKeyHash}. Returns the 56-hex-char (28-byte) payment credential. */
export const extractPaymentCredHashFromAddress = (address: string): string =>
  getPaymentKeyHash(address);

/** Stake (delegation) credential hash from a base address — the user's identity in
 *  the programmable-token context (prog-token outputs use it as their stake credential
 *  and the on-chain validators extract witnesses from it). */
export const extractStakeCredHashFromAddress = (address: string): string =>
  getStakeKeyHash(address);

export function getPaymentKeyHash(address: string): string {
  try {
    const decoded = bech32Decode(address);
    if (decoded.length < 29) {
      throw new Error("Address too short to contain a payment credential");
    }
    const pkh = bytesToHex(decoded.slice(1, 29));
    return pkh;
  } catch (e) {
    throw new Error(
      `Could not extract payment key hash from address: ${e instanceof Error ? e.message : String(e)}`
    );
  }
}

/** Stake credential hash from a Shelley base address. Throws on enterprise/script addresses. */
export function getStakeKeyHash(address: string): string {
  try {
    const decoded = bech32Decode(address);
    if (decoded.length < 57) {
      throw new Error(
        "Address too short to contain a stake credential — base address required"
      );
    }
    const skh = bytesToHex(decoded.slice(29, 57));
    return skh;
  } catch (e) {
    throw new Error(
      `Could not extract stake key hash from address: ${e instanceof Error ? e.message : String(e)}`
    );
  }
}

// ---------------------------------------------------------------------------
// Bech32 decoder (minimal, Shelley addresses only)
// ---------------------------------------------------------------------------

const BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";

function bech32Decode(str: string): Uint8Array {
  // Find the separator
  const sepIdx = str.lastIndexOf("1");
  if (sepIdx < 1) throw new Error("Invalid bech32: no separator");

  const data = str.slice(sepIdx + 1);

  // Convert characters to 5-bit values
  const values: number[] = [];
  for (const ch of data) {
    const val = BECH32_CHARSET.indexOf(ch);
    if (val === -1) throw new Error(`Invalid bech32 character: ${ch}`);
    values.push(val);
  }

  // Remove checksum (last 6 values)
  const payload = values.slice(0, -6);

  // Convert 5-bit groups to 8-bit bytes
  return convertBits(payload, 5, 8, false);
}

function convertBits(
  data: number[],
  fromBits: number,
  toBits: number,
  pad: boolean
): Uint8Array {
  let acc = 0;
  let bits = 0;
  const result: number[] = [];
  const maxv = (1 << toBits) - 1;

  for (const value of data) {
    acc = (acc << fromBits) | value;
    bits += fromBits;
    while (bits >= toBits) {
      bits -= toBits;
      result.push((acc >> bits) & maxv);
    }
  }

  if (pad) {
    if (bits > 0) {
      result.push((acc << (toBits - bits)) & maxv);
    }
  }

  return new Uint8Array(result);
}

function bytesToHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}
