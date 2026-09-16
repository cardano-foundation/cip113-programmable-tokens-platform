/**
 * Finding the two lovelace values to mine against, inside a serialised transaction body.
 *
 * ⛔ THIS IS THE PART THAT CAN CORRUPT RATHER THAN MERELY FAIL. The miner patches bytes in place,
 * so a wrong offset does not produce a transaction that fails to mine — it produces one that is
 * still valid CBOR, still hashes perfectly, and has had four bytes of something else overwritten.
 * Nothing downstream can catch that: the hash is computed over whatever the bytes now say. Only
 * refusing a bad offset can, which is why every step here verifies rather than assumes.
 *
 * ## What a transaction output actually looks like, which is not what the docs suggest
 *
 * MEASURED against Evolution's own serialiser rather than assumed. For an ada-only output it emits
 * the SHELLEY ARRAY form:
 *
 *     82                       array(2)
 *       5839 <57 bytes>        the address
 *       1a   <4 bytes>         the coin
 *
 * not the Babbage map form (`a2 00 <address> 01 <value>`) that a reading of CIP-31 or the Conway
 * CDDL would lead you to write a parser for. Both are legal and both appear in the wild — an
 * output carrying a datum or a script reference uses the map — so both are handled here. Had this
 * been written against the map form alone it would have failed on every transaction this
 * application builds, which is the argument for testing a parser against the encoder it will
 * actually meet.
 *
 * Multi-asset outputs encode the value as `[coin, assets]`; the coin is the first element and is
 * the one mined against.
 */
import { CBOR as EvoCBOR } from '@evolution-sdk/evolution';
import type { LovelaceSlot } from './mine';

/** CBOR major type 4 (array) and 5 (map), as header-byte ranges. */
const isArrayHeader = (b: number) => b >= 0x80 && b <= 0x9b;
const isMapHeader = (b: number) => b >= 0xa0 && b <= 0xbb;
const isBytesHeader = (b: number) => b >= 0x40 && b <= 0x5b;

/** CBOR 0x1a: a 5-byte unsigned integer. The width a lovelace value near 1 ADA uses. */
const CBOR_UINT32 = 0x1a;

export interface OutputSlot {
  /** Index of this output within the body's output list. */
  index: number;
  /** Address bytes, hex. */
  addressHex: string;
  /** Where the coin's CBOR header sits, and what it currently says. */
  slot: LovelaceSlot;
}

/** Read a definite-length header: returns the item count and where the contents start. */
function readHeader(body: Uint8Array, offset: number): { count: number; start: number } {
  const initial = body[offset];
  const info = initial & 0x1f;
  if (info < 24) return { count: info, start: offset + 1 };
  if (info === 24) return { count: body[offset + 1], start: offset + 2 };
  if (info === 25) return { count: (body[offset + 1] << 8) | body[offset + 2], start: offset + 3 };
  if (info === 26) {
    return {
      count:
        body[offset + 1] * 0x1000000 + body[offset + 2] * 0x10000 +
        body[offset + 3] * 0x100 + body[offset + 4],
      start: offset + 5,
    };
  }
  throw new Error(
    `indefinite-length or 64-bit CBOR header at offset ${offset} (0x${initial.toString(16)}); ` +
      `a transaction body from this application does not contain one, so this is not the body ` +
      `that was expected.`,
  );
}

/** Skip one CBOR item, using the SDK's decoder so this file owns no format knowledge it needn't. */
function skip(body: Uint8Array, offset: number): number {
  return EvoCBOR.decodeItemWithOffset(body, offset).newOffset;
}

function hexOf(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += bytes[i].toString(16).padStart(2, '0');
  return s;
}

/**
 * Every output in the body, with the byte offset of its coin.
 *
 * Refuses anything it does not fully understand rather than returning a best guess — see the
 * header note for why a guess here is worse than a failure.
 */
export function readOutputs(body: Uint8Array): OutputSlot[] {
  if (!isMapHeader(body[0])) {
    throw new Error(
      `a transaction body must be a CBOR map, found 0x${body[0]?.toString(16) ?? '??'}. If this is ` +
        `a whole transaction rather than its body, the body is the first element of the outer array.`,
    );
  }

  const { count, start } = readHeader(body, 0);
  let offset = start;
  let outputsAt = -1;

  for (let i = 0; i < count; i++) {
    const keyStart = offset;
    const key = body[keyStart];
    offset = skip(body, keyStart);
    const valueStart = offset;
    offset = skip(body, valueStart);
    // Body key 1 is the output list. Keys are small unsigned integers, so the header byte IS the
    // key for everything below 24, which covers every field a body uses.
    if (key === 0x01) { outputsAt = valueStart; break; }
  }

  if (outputsAt < 0) {
    throw new Error('the transaction body has no output list (map key 1)');
  }

  // The output list may be a plain array or a tagged set; step past a tag if one is present.
  let listAt = outputsAt;
  if (body[listAt] === 0xd9) listAt += 3;
  if (!isArrayHeader(body[listAt])) {
    throw new Error(`the output list is not a CBOR array (0x${body[listAt]?.toString(16) ?? '??'})`);
  }

  const list = readHeader(body, listAt);
  const outputs: OutputSlot[] = [];
  let cursor = list.start;

  for (let i = 0; i < list.count; i++) {
    const outputStart = cursor;
    const header = body[outputStart];
    let addressAt: number;
    let valueAt: number;

    if (isArrayHeader(header)) {
      // Shelley form: [address, value]. What Evolution emits for an ada-only output.
      const arr = readHeader(body, outputStart);
      if (arr.count < 2) throw new Error(`output ${i} is an array of ${arr.count}, expected at least 2`);
      addressAt = arr.start;
      valueAt = skip(body, addressAt);
    } else if (isMapHeader(header)) {
      // Babbage form: {0: address, 1: value, ...}. Used when a datum or script ref is present.
      const map = readHeader(body, outputStart);
      let p = map.start;
      addressAt = -1;
      valueAt = -1;
      for (let k = 0; k < map.count; k++) {
        const key = body[p];
        const vStart = skip(body, p);
        const vEnd = skip(body, vStart);
        if (key === 0x00) addressAt = vStart;
        if (key === 0x01) valueAt = vStart;
        p = vEnd;
      }
      if (addressAt < 0 || valueAt < 0) {
        throw new Error(`output ${i} is a map without both an address (key 0) and a value (key 1)`);
      }
    } else {
      throw new Error(
        `output ${i} is neither an array nor a map (0x${header?.toString(16) ?? '??'})`,
      );
    }

    if (!isBytesHeader(body[addressAt])) {
      throw new Error(`output ${i}'s address is not a CBOR byte string`);
    }
    const addrHeader = readHeader(body, addressAt);
    const addressHex = hexOf(body.subarray(addrHeader.start, addrHeader.start + addrHeader.count));

    // A multi-asset value is [coin, assets]; the coin is the first element.
    let coinAt = valueAt;
    if (isArrayHeader(body[coinAt])) coinAt = readHeader(body, coinAt).start;

    if (body[coinAt] !== CBOR_UINT32) {
      throw new Error(
        `output ${i}'s coin is encoded as 0x${body[coinAt]?.toString(16) ?? '??'}, not the 5-byte ` +
          `form (0x1a) mining requires. Below about 0.065 ADA a coin uses a shorter encoding, and ` +
          `incrementing it could change the body's length — which would change the fee and move ` +
          `the hash the search is chasing.`,
      );
    }

    outputs.push({
      index: i,
      addressHex,
      slot: {
        offset: coinAt,
        value:
          body[coinAt + 1] * 0x1000000 + body[coinAt + 2] * 0x10000 +
          body[coinAt + 3] * 0x100 + body[coinAt + 4],
      },
    });

    cursor = skip(body, outputStart);
  }

  return outputs;
}

export interface MiningSlots {
  /** The self-output that gains a lovelace per attempt. */
  gains: LovelaceSlot;
  /** The change output that loses one. */
  loses: LovelaceSlot;
  gainsIndex: number;
  losesIndex: number;
}

/**
 * Pick the two outputs to mine between, by address and by value.
 *
 * ⛔ VERIFIED AFTER LOCATING, NOT TRUSTED FROM THE WALK. Both slots are re-read and their values
 * confirmed against what the caller expected before a single byte is patched. It costs one pass
 * and it converts a whole class of silent corruption — a walk that drifted by a few bytes and
 * found a plausible integer somewhere else — into an error before anything happens.
 *
 * @param selfAddressHex  the deployer's own address; both outputs are usually at it, so the two
 *                        are told apart by their expected values rather than by address alone
 */
export function locateMiningSlots(
  body: Uint8Array,
  params: {
    selfAddressHex: string;
    expectedGainsLovelace: number;
    expectedLosesLovelace: number;
  },
): MiningSlots {
  const outputs = readOutputs(body);
  const own = outputs.filter((o) => o.addressHex.toLowerCase() === params.selfAddressHex.toLowerCase());

  if (own.length < 2) {
    throw new Error(
      `mining needs two outputs at the deployer's own address — one to increment and one to take ` +
        `the lovelace from — but this body has ${own.length} of ${outputs.length}. The ~1 ADA ` +
        `self-output has to be added to the transaction BEFORE it is built; it cannot be ` +
        `introduced afterwards without changing the body, which is the hash being mined.`,
    );
  }

  const gains = own.find((o) => o.slot.value === params.expectedGainsLovelace);
  const loses = own.find(
    (o) => o.slot.value === params.expectedLosesLovelace && o.index !== gains?.index,
  );

  if (!gains || !loses) {
    throw new Error(
      `could not identify the two outputs to mine between. Expected one holding ` +
        `${params.expectedGainsLovelace} lovelace and another holding ${params.expectedLosesLovelace}; ` +
        `the outputs at this address hold ${own.map((o) => o.slot.value).join(', ')}. Refusing to ` +
        `guess: patching the wrong output would produce a transaction that still hashes and still ` +
        `submits.`,
    );
  }

  return {
    gains: { ...gains.slot },
    loses: { ...loses.slot },
    gainsIndex: gains.index,
    losesIndex: loses.index,
  };
}

/**
 * Will the change output still clear min-ADA after the search?
 *
 * ⛔ CHECKED BEFORE STARTING, against the expected attempt count — not at submission. Evolution
 * does not rescue an under-funded output: the shortfall survives to submission and is reported as
 * "insufficient Ada", which sends the reader to their wallet balance rather than to the search
 * that drained it one lovelace at a time.
 */
export function changeHasHeadroom(
  changeLovelace: number,
  minUtxoLovelace: number,
  expectedAttempts: number,
): { ok: boolean; shortfall: number } {
  const remaining = changeLovelace - expectedAttempts;
  return { ok: remaining >= minUtxoLovelace, shortfall: Math.max(0, minUtxoLovelace - remaining) };
}
