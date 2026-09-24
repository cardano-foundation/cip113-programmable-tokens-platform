/**
 * What a transaction says about itself, for someone deciding whether to sign it.
 *
 * A signer handed 3,489 bytes of hex has two options: trust whoever sent it, or
 * read CBOR by eye. This exists so there is a third. It is deliberately a
 * SUMMARY and says so on the page — it reports the shape of the transaction, not
 * a full semantic decode, and a field it does not understand is reported as
 * present rather than quietly omitted.
 *
 * The honesty rule here matters more than the coverage: it is worse to show a
 * confident, partial summary that omits a mint than to show nothing, because the
 * first actively reassures. So `unknownFields` carries every body key this does
 * not interpret, and the UI shows it.
 */

import { CBOR as EvoCBOR } from "@evolution-sdk/evolution";
import { transactionBodyBytes } from "./hash";

/** Conway transaction body keys this summary interprets. */
const KNOWN: Record<number, string> = {
  0: "inputs",
  1: "outputs",
  2: "fee",
  3: "ttl",
  4: "certificates",
  5: "withdrawals",
  7: "auxiliaryDataHash",
  8: "validityIntervalStart",
  9: "mint",
  11: "scriptDataHash",
  13: "collateralInputs",
  14: "requiredSigners",
  15: "networkId",
  16: "collateralReturn",
  17: "totalCollateral",
  18: "referenceInputs",
};

export interface TxSummary {
  inputCount: number;
  outputCount: number;
  /** Lovelace, as a string so a caller cannot lose precision rendering it. */
  fee: string | null;
  ttl: string | null;
  certificateCount: number;
  withdrawalCount: number;
  referenceInputCount: number;
  mints: boolean;
  /** Key hashes the transaction DECLARES it must be signed by, hex. */
  requiredSigners: string[];
  /** Body keys not interpreted here — shown so a partial summary cannot reassure. */
  unknownFields: number[];
}

function len(v: unknown): number {
  if (Array.isArray(v)) return v.length;
  if (v instanceof Map) return v.size;
  if (v instanceof Set) return v.size;
  // A tag-258 set decodes to a wrapper whose payload carries the entries.
  if (v && typeof v === "object" && "value" in v) return len((v as { value: unknown }).value);
  return 0;
}

function entriesOf(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (v instanceof Set) return [...v];
  if (v && typeof v === "object" && "value" in v) return entriesOf((v as { value: unknown }).value);
  return [];
}

function toHex(b: Uint8Array): string {
  return Array.from(b).map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function summariseTransaction(txHex: string): TxSummary {
  const body = transactionBodyBytes(txHex);
  const { item } = EvoCBOR.decodeItemWithOffset(body, 0);
  if (!(item instanceof Map)) {
    throw new Error("transaction body is not a CBOR map");
  }

  const get = (k: number): unknown => {
    for (const [key, value] of item.entries()) {
      const n = typeof key === "bigint" ? Number(key) : key;
      if (n === k) return value;
    }
    return undefined;
  };

  const unknownFields: number[] = [];
  for (const key of item.keys()) {
    const n = typeof key === "bigint" ? Number(key) : (key as number);
    if (!(n in KNOWN)) unknownFields.push(n);
  }

  const feeRaw = get(2);
  const ttlRaw = get(3);
  const signersRaw = get(14);

  return {
    inputCount: len(get(0)),
    outputCount: len(get(1)),
    fee: feeRaw === undefined ? null : String(feeRaw),
    ttl: ttlRaw === undefined ? null : String(ttlRaw),
    certificateCount: len(get(4)),
    withdrawalCount: len(get(5)),
    referenceInputCount: len(get(18)),
    // Presence, not size: a mint field that exists at all changes what the
    // transaction does, and an empty one should still be visible.
    mints: get(9) !== undefined,
    requiredSigners: entriesOf(signersRaw)
      .filter((s): s is Uint8Array => s instanceof Uint8Array)
      .map(toHex),
    unknownFields: unknownFields.sort((a, b) => a - b),
  };
}
