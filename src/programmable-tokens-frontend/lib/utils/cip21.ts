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

/**
 * Which part of the transaction a violation sits in — and therefore whether it is FATAL.
 *
 * ⛔ THIS EXISTS BECAUSE CLASSIFYING BY MESSAGE PREFIX WAS A REAL DEFECT. `ceremony.ts` used to split
 * fatal from advisory with `violation.startsWith("body")`. Three violations describe body-level
 * defects whose text does not begin with "body" — the tag-258 inconsistency, the "could not walk the
 * CBOR" blind case, and "empty CBOR" — so each was downgraded to a console warning telling the
 * operator the item was OUTSIDE the body and did not block signing. Both claims were false. The blind
 * case was the worst: its own message says "treat this check as blind, not as a pass", and the caller
 * passed it.
 *
 * ⚑ SO SCOPE IS STRUCTURAL, DERIVED FROM THE PATH, AND PROSE CAN NEVER DECIDE FATALITY AGAIN. Found
 * by an adversarial pre-merge audit, 2026-10-01, which reached the warning with a body whose `inputs`
 * carried tag 258 while `reference_inputs` did not.
 */
export type Cip21Scope =
  /** Inside the body — the only part a hardware wallet reconstructs and hashes. FATAL. */
  | "body"
  /** A whole-transaction rule (tag-258 all-or-nothing) that still changes the body a device hashes. FATAL. */
  | "transaction"
  /** The checker could not read the bytes, so it has no opinion. FATAL — no finding is not a pass. */
  | "blind"
  /** Outside the body: real, but not part of what a device hashes. ADVISORY. */
  | "witnessSet"
  | "isValid"
  | "auxiliaryData";

/** A scope that makes a transaction unfit for a hardware wallet. */
export function isFatalScope(scope: Cip21Scope): boolean {
  return scope === "body" || scope === "transaction" || scope === "blind";
}

export interface Cip21Violation {
  scope: Cip21Scope;
  message: string;
}

export interface Cip21Report {
  /** Human-readable violations, each naming the field it sits in. Empty means conformant. */
  violations: string[];
  /** The same violations, each carrying the scope that decides whether it is fatal. */
  scopedViolations: Cip21Violation[];
  /** How many tag 258 wrappers the transaction carries. */
  tag258Count: number;
  /** Set-valued body fields encoded as a bare array, with no tag 258. */
  bareSetFields: string[];
  /** Set-valued body fields carrying tag 258. */
  taggedSetFields: string[];
}

interface Ctx {
  bytes: Uint8Array;
  violations: Cip21Violation[];
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

/**
 * The scope a violation at this path belongs to.
 *
 * Paths are rooted at the transaction element they describe — "body…", "witnessSet…", "isValid",
 * "auxiliaryData…" — so the root IS the scope. Anything unrecognised is treated as body, i.e. FATAL:
 * a path shape this function does not know must not quietly become advisory.
 */
function scopeOfPath(path: string): Cip21Scope {
  if (path.startsWith("witnessSet")) return "witnessSet";
  if (path.startsWith("auxiliaryData")) return "auxiliaryData";
  if (path.startsWith("isValid")) return "isValid";
  return "body";
}

/** Record a violation, taking its scope from the path rather than from its wording. */
function pushAt(ctx: Ctx, path: string, message: string): void {
  ctx.violations.push({ scope: scopeOfPath(path), message });
}

/**
 * The element count of an array/map header, and where its first element starts.
 *
 * ⛔ READING `b[i] & 0x1f` AS THE COUNT IS ONLY RIGHT BELOW 24. At 24 or more the low bits hold the
 * additional-info code and the real count follows in 1, 2, 4 or 8 bytes. The legacy-output check used
 * the raw value behind `if (cnt < 24)`, so it silently stopped firing at exactly 24 outputs — probed
 * by the 2026-10-01 audit: 23 outputs produced 23 findings, 24 produced none.
 */
function headerCount(b: Uint8Array, i: number): { count: number; first: number } | null {
  const ai = b[i] & 0x1f;
  if (ai < 24) return { count: ai, first: i + 1 };
  if (ai === 24) return { count: b[i + 1], first: i + 2 };
  if (ai === 25) return { count: (b[i + 1] << 8) | b[i + 2], first: i + 3 };
  // 4- and 8-byte counts mean more elements than CIP-21 permits anyway (UINT16_MAX), and an
  // indefinite length (31) is reported by the walker itself. Decline rather than guess an offset.
  return null;
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
    pushAt(ctx, path,
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
    if (len < 24) pushAt(ctx, path, `${path}: value/length ${len} uses an extra byte but fits in the header — CIP-21 requires the shortest encoding`);
  } else if (ai === 25) {
    len = (b[i + 1] << 8) | b[i + 2]; hdr = 3;
    if (len <= 0xff) pushAt(ctx, path, `${path}: value/length ${len} encoded in 2 bytes but fits in 1 — not minimal`);
  } else if (ai === 26) {
    len = ((b[i + 1] << 24) | (b[i + 2] << 16) | (b[i + 3] << 8) | b[i + 4]) >>> 0; hdr = 5;
    if (len <= 0xffff) pushAt(ctx, path, `${path}: value/length ${len} encoded in 4 bytes but fits in 2 — not minimal`);
  } else if (ai === 27) {
    hdr = 9;
    let v = 0n;
    for (let k = 1; k <= 8; k++) v = (v << 8n) | BigInt(b[i + k]);
    len = Number(v);
    if (v <= 0xffffffffn) pushAt(ctx, path, `${path}: value/length ${v} encoded in 8 bytes but fits in 4 — not minimal`);
  } else if (ai === 31) {
    indefinite = true;
    pushAt(ctx, path, `${path}: INDEFINITE length (0x${ib.toString(16).padStart(2, "0")}) — CIP-21 requires definite-length items`);
  } else {
    pushAt(ctx, path, `${path}: reserved additional-info ${ai} (0x${ib.toString(16)})`);
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
          pushAt(ctx, path,
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
            pushAt(ctx, "body", `body: contains \`${name}\` — CIP-21 lists this entry as unsupported, it must not be included`);
          }
          if (SET_FIELDS.has(n)) classifySet(ctx, ke, label);
          if (n === 1 && (b[ke] >> 5) === 4) {
            // outputs: inspect each element for the forbidden legacy shape
            const head = headerCount(b, ke);
            if (head) {
              let q = head.first;
              for (let j = 0; j < head.count; j++) {
                checkLegacyOutput(ctx, q, `body.outputs[${j}]`);
                // ⛔ THIS PASS ONLY NEEDS THE OFFSET. The generic walker runs over the same outputs
                // again below, so letting this inner walk REPORT duplicated every violation inside an
                // output. The first fix for that deduped all violations by message text, which merged
                // two genuinely DISTINCT defects whenever their messages coincided — two non-minimal
                // withdrawal keys of the same length became one finding. A checker that hides a defect
                // to avoid printing it twice is worse than one that repeats itself, so the duplication
                // is removed at its source: walk for the offset, discard what it says.
                // ⚑ BOTH SINKS MUST BE TRUNCATED. `walk` reports into `ctx.violations` AND accumulates
                // into `ctx.emptyInBody`, which is turned into violations later, after the walk is
                // over. Rewinding only the first left the empty-map finding duplicated — caught by the
                // no-duplicates test, which is the whole reason that test exists rather than a comment.
                const reportedBefore = ctx.violations.length;
                const emptiesBefore = ctx.emptyInBody.length;
                q = walk(ctx, q, `body.outputs[${j}]`);
                ctx.violations.length = reportedBefore;
                ctx.emptyInBody.length = emptiesBefore;
              }
            }
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
    // "blind", not advisory: a checker handed nothing has no opinion, and no opinion is not a pass.
    const empty: Cip21Violation = { scope: "blind", message: "empty CBOR — nothing to check" };
    return {
      violations: [empty.message], scopedViolations: [empty],
      tag258Count: 0, bareSetFields: [], taggedSetFields: [],
    };
  }

  try {
    const ib = bytes[0];
    if (ib >> 5 === 4) {
      /**
       * ⛔ THE ELEMENT COUNT AND THE HEADER WIDTH BOTH HAVE TO BE READ, and reading neither was a
       * fatality INVERSION, not a cosmetic slip. This used `bytes[0] & 0x1f` as the count and a
       * hardcoded `p = 1`. For a non-minimal outer header — `98 04`, four elements written in two
       * bytes, valid CBOR and itself a CIP-21 minimality violation — every element shifted by one:
       * offset 1 held the count byte, so THE TRANSACTION BODY WAS WALKED AS `witnessSet`. Measured on
       * the real ceremony body a Ledger refused, identical bytes with only the header rewritten:
       *
       *   header 84   -> 1 fatal:    body.mint: keys out of canonical order at entry 2
       *   header 9804 -> 0 fatal, 1 advisory: witnessSet{09}: keys out of canonical order at entry 2
       *
       * Two failures at once: the defect stops being fatal, and the diagnosis names the wrong field —
       * and naming the right field is the whole purpose of this module. Found by the 2026-10-01
       * pre-merge audit, which also noted that the only thing containing it was a DIFFERENT guard
       * (`canonicaliseBodyOnly`'s replay post-condition, which throws first) — and that guard was
       * itself undefended one round earlier. Safety borrowed from a neighbour breaks silently when the
       * neighbour is refactored, so this reads its own header.
       */
      const head = headerCount(bytes, 0);
      if (!head) {
        // An indefinite or 4/8-byte-counted outer array: not a shape this understands. Say so loudly
        // rather than guess an offset, because a guessed offset is what mislabels the body.
        ctx.violations.push({
          scope: "blind",
          message:
            `outer array header 0x${ib.toString(16).padStart(2, "0")} is indefinite or oversized — ` +
            `cannot locate the transaction body, so treat this check as blind, not as a pass`,
        });
      } else {
        /**
         * ⚑ THE OUTER HEADER'S OWN MINIMALITY, which nothing checked. `walk` enforces minimal
         * encoding for every item it visits — but the transaction array's header is consumed HERE, by
         * the dispatch, so it was never visited and `9804` passed silently once the misroute above was
         * fixed. CIP-21: "The expression of lengths in major types 2 through 5 must be as short as
         * possible."
         *
         * Scope "transaction", so it is fatal. The outer header is not part of the body bytes, so a
         * device would in fact hash the same body — but a transaction whose framing we cannot
         * faithfully reproduce is one we refuse to re-serialize at all (`canonicaliseBodyOnly` throws
         * on it), and the conservative classification is the one that matches that refusal.
         */
        const minimalWidth = head.count < 24 ? 1 : head.count < 256 ? 2 : head.count < 65536 ? 3 : 5;
        if (head.first !== minimalWidth) {
          ctx.violations.push({
            scope: "transaction",
            message:
              `transaction: outer array header for ${head.count} element(s) uses ${head.first} bytes ` +
              `but fits in ${minimalWidth} — CIP-21 requires the shortest length encoding`,
          });
        }

        let p = head.first;
        for (let k = 0; k < head.count; k++) {
          p = k === 0 ? walk(ctx, p, "body", true) : walk(ctx, p, k === 1 ? "witnessSet" : k === 2 ? "isValid" : "auxiliaryData");
        }
      }
    } else {
      walk(ctx, 0, "body", true);
    }
  } catch (e) {
    // ⛔ SCOPE "blind", WHICH IS FATAL. This message has always said "treat this check as blind, not
    // as a pass" — and until 2026-10-01 the caller classified it by prefix, found no leading "body",
    // and passed it. The wording was right and powerless; the scope is what the caller acts on.
    ctx.violations.push({
      scope: "blind",
      message: `could not walk the CBOR (${(e as Error)?.message ?? e}) — treat this check as blind, not as a pass`,
    });
  }

  // "Unless mentioned otherwise in this CIP, optional empty lists and maps must not be included as
  // part of the transaction body or its elements." HW wallets enforce this in many cases.
  for (const e of ctx.emptyInBody) {
    pushAt(ctx, e,
      `${e} — CIP-21 forbids optional empty lists and maps in the transaction body; omit the field ` +
      `entirely instead of including it empty`
    );
  }

  // The all-or-nothing rule. Either state alone is fine; the mixture is what CIP-21 forbids.
  if (ctx.taggedSetFields.length > 0 && ctx.bareSetFields.length > 0) {
    // ⛔ SCOPE "transaction", WHICH IS FATAL. CIP-21 states the rule across the whole transaction, but
    // the consequence lands on the body: "A HW wallet normalises this and hashes a different body."
    // Classified by prefix this read as non-body and became an advisory warning that told the operator
    // the opposite of the truth on both counts.
    ctx.violations.push({
      scope: "transaction",
      message:
      `tag 258 is INCONSISTENT across the transaction: tagged [${ctx.taggedSetFields.join(", ")}] ` +
      `but bare [${ctx.bareSetFields.join(", ")}]. CIP-21: "either there are no tags 258 in sets, or ` +
      `there are such tags everywhere". A HW wallet normalises this and hashes a different body.`,
    });
  }

  // ⚑ NOT DEDUPED. Two distinct defects can legitimately produce the same message — two non-minimal
  // withdrawal keys of equal length, for instance — and collapsing them loses one. The duplication
  // that motivated deduping is fixed where it was caused, in the outputs pre-pass above.
  const scopedViolations = ctx.violations;

  return {
    violations: scopedViolations.map((v) => v.message),
    scopedViolations,
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
