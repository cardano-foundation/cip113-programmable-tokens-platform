/**
 * CIP-21 conformance check for a transaction a hardware wallet is about to sign.
 *
 * ⛔ WHY THIS EXISTS. A hardware wallet does not sign the bytes you hand it. CIP-21, Motivation:
 * "Transaction data are streamed into HW wallets in small chunks and they compute a rolling hash of
 * the transaction body which is signed at the end. Consequently, a HW wallet only provides witness
 * signatures, and the transaction body which was signed has to be reconstructed by the client."
 *
 * The device re-serializes CANONICALLY and signs THAT hash. So when our body is not canonical in
 * the same way, the witness is over a different hash than the body we submit, and the only thing
 * anyone sees is the wallet saying "hash mismatch" — with no indication of WHICH field diverged.
 * A software wallet signs our bytes verbatim, which is exactly why this class of bug is invisible
 * until someone plugs in a Ledger.
 *
 * ⚑ THIS REPORTS, IT DOES NOT REWRITE. Re-encoding a body changes its hash, and therefore its
 * transaction id — and the FES registration chains a second transaction onto the first one's id
 * (`chainingTransactionCborHex`). A "helpful" canonicaliser in the signing path would silently
 * repoint tx2 at a UTxO that never existed. Naming the offending field is the safe half, and it is
 * the half that was missing.
 *
 * The rules, from CIP-21 "Canonical CBOR serialization format" and "Tags in sets":
 *   - integers and lengths encoded as small as possible
 *   - map keys sorted from lowest value to highest
 *   - no indefinite-length items
 *   - tag 258 all-or-nothing: "either there are no tags 258 in sets, or there are such tags
 *     everywhere" — so a tag is NOT a defect on its own, only an inconsistent one is. Evolution
 *     adds tag 258 to inputs on serialization (measured 2026-10-01); that is conformant by itself.
 *
 * Deliberately dependency-free and byte-level: the point is to see what is actually on the wire,
 * which a decode/re-encode round trip through any CBOR library would destroy.
 */

/** Transaction body map keys, Conway. Used to name the field a violation sits in. */
const BODY_FIELD: Record<number, string> = {
  0: "inputs", 1: "outputs", 2: "fee", 3: "ttl", 4: "certificates", 5: "withdrawals",
  6: "update (FORBIDDEN by CIP-21)", 7: "auxiliary_data_hash", 8: "validity_interval_start",
  9: "mint", 11: "script_data_hash", 13: "collateral_inputs", 14: "required_signers",
  15: "network_id", 16: "collateral_return", 17: "total_collateral", 18: "reference_inputs",
  19: "voting_procedures", 20: "proposal_procedures (FORBIDDEN by CIP-21)",
  21: "current_treasury_value", 22: "donation",
};

export interface Cip21Report {
  /** Human-readable violations, each naming the field it sits in. Empty means conformant. */
  violations: string[];
  /** How many tag 258 wrappers the transaction carries. */
  tag258Count: number;
  /** Set-valued body fields encoded as a bare array, with no tag 258. */
  bareSetFields: string[];
  /** Set-valued body fields carrying tag 258. */
  taggedSetFields: string[];
}

interface Ctx {
  bytes: Uint8Array;
  violations: string[];
  tag258: number;
  /** Set-valued body fields carrying tag 258, by name. */
  taggedSetFields: string[];
  /** Set-valued body fields encoded as a bare array, by name. */
  bareSetFields: string[];
  /** Set to true for the item directly following a tag 258, so it is not counted as untagged. */
  justTagged: boolean;
  /** Empty arrays/maps found inside the BODY, by path. CIP-21 forbids them there. */
  emptyInBody: string[];
}

/** Fields whose value is a set in the Conway CDDL, i.e. where tag 258 is permitted. */
const SET_FIELDS = new Set([0, 4, 13, 14, 18]);

/**
 * Is this path inside the transaction BODY?
 *
 * Only the body is hashed, and CIP-21's empty-collection rule is scoped to "the transaction body or
 * its elements". The witness set of an UNSIGNED transaction is legitimately an empty map (`a0`), so
 * applying the rule transaction-wide would flag every unsigned transaction we ever build.
 */
function inBody(path: string): boolean {
  return path === "body" || path.startsWith("body.") || path.startsWith("body[") || path.startsWith("body{");
}

/**
 * Checks one output for the legacy shape CIP-21 forbids.
 *
 * "Outputs containing no multi-asset tokens must be serialized as a simple tuple, i.e.
 * `[address, coin, ?datum_hash]` instead of `[address, [coin, {}], ?datum_hash]`." The empty-map
 * rule already catches the `{}`, but a token-free output deserves the message that names the fix.
 */
function checkLegacyOutput(ctx: Ctx, i: number, path: string): void {
  const b = ctx.bytes;
  if ((b[i] >> 5) !== 4) return;               // post-Alonzo map output: different rules
  const n = b[i] & 0x1f;
  if (n < 2 || n > 3) return;
  // skip the address (a byte string)
  let p = i + 1;
  const ab = b[p];
  if ((ab >> 5) !== 2) return;
  const ai2 = ab & 0x1f;
  // An address is 29 or 57 bytes, so its length is either inline or one byte. Anything longer is
  // not an address shape this check understands — skip rather than read the wrong offset.
  if (ai2 > 24) return;
  p += ai2 < 24 ? 1 + ai2 : 2 + b[i + 2];
  // value position: a bare uint is already the simple tuple; an array is [coin, multiasset]
  if ((b[p] >> 5) !== 4) return;
  const vn = b[p] & 0x1f;
  if (vn !== 2) return;
  // walk past coin to reach the multiasset map
  const after = walk(ctx, p + 1, `${path}.coin`);
  if (b[after] === 0xa0) {
    ctx.violations.push(
      `${path}: token-free output serialized as [address, [coin, {}]] — CIP-21 requires the simple ` +
      `tuple [address, coin, ?datum_hash] when an output carries no multi-asset tokens`
    );
  }
}

function compareBytes(a: Uint8Array, b: Uint8Array): number {
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return a.length - b.length;
}

const hex = (u: Uint8Array) => Array.from(u).map((b) => b.toString(16).padStart(2, "0")).join("");

/**
 * Walks one CBOR item, recording violations, and returns the offset just past it.
 *
 * `path` is a human-readable location such as `body.withdrawals`. `isBody` marks the transaction
 * body map so its integer keys can be translated into field names.
 */
function walk(ctx: Ctx, i: number, path: string, isBody = false): number {
  const b = ctx.bytes;
  if (i >= b.length) return i;

  const ib = b[i];
  const major = ib >> 5;
  const ai = ib & 0x1f;
  const wasTagged = ctx.justTagged;
  ctx.justTagged = false;

  let len = 0;
  let hdr = 1;
  let indefinite = false;

  if (ai < 24) {
    len = ai;
  } else if (ai === 24) {
    len = b[i + 1]; hdr = 2;
    if (len < 24) ctx.violations.push(`${path}: value/length ${len} uses an extra byte but fits in the header — CIP-21 requires the shortest encoding`);
  } else if (ai === 25) {
    len = (b[i + 1] << 8) | b[i + 2]; hdr = 3;
    if (len <= 0xff) ctx.violations.push(`${path}: value/length ${len} encoded in 2 bytes but fits in 1 — not minimal`);
  } else if (ai === 26) {
    len = ((b[i + 1] << 24) | (b[i + 2] << 16) | (b[i + 3] << 8) | b[i + 4]) >>> 0; hdr = 5;
    if (len <= 0xffff) ctx.violations.push(`${path}: value/length ${len} encoded in 4 bytes but fits in 2 — not minimal`);
  } else if (ai === 27) {
    hdr = 9;
    let v = 0n;
    for (let k = 1; k <= 8; k++) v = (v << 8n) | BigInt(b[i + k]);
    len = Number(v);
    if (v <= 0xffffffffn) ctx.violations.push(`${path}: value/length ${v} encoded in 8 bytes but fits in 4 — not minimal`);
  } else if (ai === 31) {
    indefinite = true;
    ctx.violations.push(`${path}: INDEFINITE length (0x${ib.toString(16).padStart(2, "0")}) — CIP-21 requires definite-length items`);
  } else {
    ctx.violations.push(`${path}: reserved additional-info ${ai} (0x${ib.toString(16)})`);
    return i + 1;
  }

  let p = i + hdr;

  switch (major) {
    case 0:
    case 1:
      return p;

    case 2:
    case 3:
      if (indefinite) return skipIndefinite(ctx, p, path);
      return p + len;

    case 4: {
      if (indefinite) return skipIndefinite(ctx, p, path);
      if (len === 0 && inBody(path)) ctx.emptyInBody.push(`${path} (empty list)`);
      for (let k = 0; k < len; k++) p = walk(ctx, p, `${path}[${k}]`);
      return p;
    }

    case 5: {
      if (indefinite) return skipIndefinite(ctx, p, path);
      if (len === 0 && inBody(path)) ctx.emptyInBody.push(`${path} (empty map)`);
      let prev: Uint8Array | null = null;
      for (let k = 0; k < len; k++) {
        const ks = p;
        const ke = walk(ctx, p, `${path}{key}`);
        const key = b.slice(ks, ke);

        // Canonical CBOR: keys ascending by encoded bytes. This is the rule that bites CIP-113,
        // because the withdrawals map and every multiasset map inside `mint` and the outputs are
        // built in the order the builder happened to add them.
        if (prev && compareBytes(prev, key) >= 0) {
          const what = compareBytes(prev, key) === 0 ? "DUPLICATE key" : "keys out of canonical order";
          ctx.violations.push(
            `${path}: ${what} at entry ${k} — 0x${hex(prev)} then 0x${hex(key)}. CIP-21 requires map keys sorted lowest to highest; a HW wallet re-sorts them and hashes a different body.`
          );
        }
        prev = key;

        // Name the body's fields, and flag the two CIP-21 forbids outright.
        let label = `${path}{${hex(key)}}`;
        if (isBody && key.length === 1 && key[0] <= 0x17) {
          const n = key[0];
          const name = BODY_FIELD[n] ?? `unknown_field_${n}`;
          label = `body.${name}`;
          if (n === 6 || n === 20) {
            ctx.violations.push(`body: contains \`${name}\` — CIP-21 lists this entry as unsupported, it must not be included`);
          }
          if (SET_FIELDS.has(n)) classifySet(ctx, ke, label);
          if (n === 1 && (b[ke] >> 5) === 4) {
            // outputs: inspect each element for the forbidden legacy shape
            const cnt = b[ke] & 0x1f;
            let q = ke + 1;
            if (cnt < 24) for (let j = 0; j < cnt; j++) { checkLegacyOutput(ctx, q, `body.outputs[${j}]`); q = walk(ctx, q, `body.outputs[${j}]`); }
          }
          p = walk(ctx, ke, label);
          continue;
        }
        p = walk(ctx, ke, label);
      }
      return p;
    }

    case 6: {
      if (len === 258) {
        ctx.tag258++;
        ctx.justTagged = true;
      }
      return walk(ctx, p, len === 258 ? path : `${path}/tag${len}`);
    }

    case 7:
      if (ai === 25) return i + 3;
      if (ai === 26) return i + 5;
      if (ai === 27) return i + 9;
      return i + hdr;

    default:
      return p;
  }
}

/**
 * Classifies one set-valued body field as tagged or bare, for the all-or-nothing rule.
 *
 * ⚑ Deliberately looks at the VALUE HEAD rather than counting arrays during the walk. An input is
 * itself a 2-element array inside the `inputs` set, so counting arrays made every conformant
 * transaction look inconsistent — the check's own first bug, and a reminder that `untaggedSets`
 * must mean "set field encoded bare", never "array seen".
 */
function classifySet(ctx: Ctx, i: number, path: string): void {
  const b = ctx.bytes;
  if (b[i] === 0xd9 && b[i + 1] === 0x01 && b[i + 2] === 0x02) {
    ctx.taggedSetFields.push(path);
  } else if (b[i] >> 5 === 4) {
    ctx.bareSetFields.push(path);
  }
}

function skipIndefinite(ctx: Ctx, i: number, path: string): number {
  let p = i;
  while (p < ctx.bytes.length && ctx.bytes[p] !== 0xff) p = walk(ctx, p, path);
  return p + 1;
}

export function hexToBytes(h: string): Uint8Array {
  const clean = h.replace(/[^0-9a-fA-F]/g, "");
  const out = new Uint8Array(clean.length >> 1);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.substr(i * 2, 2), 16);
  return out;
}

/**
 * Checks a full transaction (`[body, witnessSet, isValid, auxiliaryData]`) or a bare body.
 *
 * Only the BODY is hashed, so body violations are the ones that produce a mismatch — but the
 * tag-258 consistency rule is stated by CIP-21 "across the transaction", so the whole thing is
 * walked and the tag counts are transaction-wide.
 */
export function checkCip21(txCborHex: string): Cip21Report {
  const bytes = hexToBytes(txCborHex);
  const ctx: Ctx = { bytes, violations: [], tag258: 0, taggedSetFields: [], bareSetFields: [], justTagged: false, emptyInBody: [] };

  if (bytes.length === 0) {
    return { violations: ["empty CBOR"], tag258Count: 0, bareSetFields: [], taggedSetFields: [] };
  }

  try {
    const ib = bytes[0];
    if (ib >> 5 === 4) {
      // A transaction: array whose first element is the body.
      const n = ib & 0x1f;
      let p = 1;
      for (let k = 0; k < n; k++) {
        p = k === 0 ? walk(ctx, p, "body", true) : walk(ctx, p, k === 1 ? "witnessSet" : k === 2 ? "isValid" : "auxiliaryData");
      }
    } else {
      walk(ctx, 0, "body", true);
    }
  } catch (e) {
    ctx.violations.push(`could not walk the CBOR (${(e as Error)?.message ?? e}) — treat this check as blind, not as a pass`);
  }

  // "Unless mentioned otherwise in this CIP, optional empty lists and maps must not be included as
  // part of the transaction body or its elements." HW wallets enforce this in many cases.
  for (const e of ctx.emptyInBody) {
    ctx.violations.push(
      `${e} — CIP-21 forbids optional empty lists and maps in the transaction body; omit the field ` +
      `entirely instead of including it empty`
    );
  }

  // The all-or-nothing rule. Either state alone is fine; the mixture is what CIP-21 forbids.
  if (ctx.taggedSetFields.length > 0 && ctx.bareSetFields.length > 0) {
    ctx.violations.push(
      `tag 258 is INCONSISTENT across the transaction: tagged [${ctx.taggedSetFields.join(", ")}] ` +
      `but bare [${ctx.bareSetFields.join(", ")}]. CIP-21: "either there are no tags 258 in sets, or ` +
      `there are such tags everywhere". A HW wallet normalises this and hashes a different body.`
    );
  }

  return {
    violations: ctx.violations,
    tag258Count: ctx.tag258,
    bareSetFields: ctx.bareSetFields,
    taggedSetFields: ctx.taggedSetFields,
  };
}

/**
 * Logs a CIP-21 report for a transaction about to go to a wallet.
 *
 * Non-blocking on purpose: a false positive here must not be able to stop a ceremony that would
 * otherwise succeed with a software wallet. The failure it diagnoses already stops itself.
 */
export function warnIfNotCip21Conformant(txCborHex: string, label: string): Cip21Report {
  const report = checkCip21(txCborHex);
  if (report.violations.length > 0) {
    console.warn(
      `[CIP-21] ${label} is NOT canonical — a hardware wallet will reconstruct a DIFFERENT body and ` +
      `report a hash mismatch. Offending field(s):\n` +
      report.violations.map((v) => `  • ${v}`).join("\n") +
      `\n  (tag 258 ×${report.tag258Count}; set fields tagged: [${report.taggedSetFields.join(", ") || "none"}], ` +
      `bare: [${report.bareSetFields.join(", ") || "none"}])`
    );
  } else {
    console.log(`[CIP-21] ${label}: canonical (tag 258 ×${report.tag258Count}) — no HW-wallet hash mismatch from serialization`);
  }
  return report;
}
