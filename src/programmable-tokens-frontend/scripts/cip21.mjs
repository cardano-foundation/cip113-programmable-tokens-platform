#!/usr/bin/env node
/**
 * Names the field a hardware wallet will disagree about, for a transaction CBOR you already have.
 *
 * ⛔ WHY A CLI AND NOT ONLY THE IN-APP CHECK. The in-app warning needs a browser, a wallet and the
 * operator at the keyboard; this needs the hex. When a Ledger says "hash mismatch" the one artefact
 * that always exists is the unsigned CBOR, and the answer is in it.
 *
 *   npm run check:cip21 -- <hex>
 *   npm run check:cip21 -- --file unsigned.hex
 *
 * Exits non-zero when the transaction is not CIP-21 conformant.
 */
import { readFileSync } from "node:fs";
import { checkCip21 } from "../.cip21-build/cip21.js";

const argv = process.argv.slice(2);
if (argv.length === 0) {
  console.error("usage: npm run check:cip21 -- <tx-cbor-hex> | --file <path>");
  process.exit(2);
}
const raw = argv[0] === "--file" ? readFileSync(argv[1], "utf8") : argv[0];
const hex = raw.replace(/[^0-9a-fA-F]/g, "");
if (!hex) {
  console.error("no hex digits found in the input");
  process.exit(2);
}

const r = checkCip21(hex);
console.log(`CIP-21 check — ${hex.length / 2} bytes`);
console.log(`  tag 258            : ×${r.tag258Count}`);
console.log(`  set fields tagged  : ${r.taggedSetFields.join(", ") || "none"}`);
console.log(`  set fields bare    : ${r.bareSetFields.join(", ") || "none"}`);
if (r.violations.length === 0) {
  console.log("\n  CONFORMANT — a HW wallet will reconstruct the same body, so a hash mismatch\n" +
              "  is NOT coming from serialization. Check the device's app version and the\n" +
              "  CIP-21 feature table instead (e.g. a Nano S cannot derive native script hashes).");
} else {
  console.log(`\n  NOT CONFORMANT — ${r.violations.length} violation(s):`);
  for (const v of r.violations) console.log(`    • ${v}`);
}
process.exit(r.violations.length ? 1 : 0);
