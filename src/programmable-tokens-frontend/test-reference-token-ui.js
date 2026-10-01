/**
 * A (100) reference token must never be offered for transfer, at THREE layers.
 *
 * ⛔ WHY THREE AND NOT ONE. Moving a reference token erases its metadata: the transfer rebuilds
 * outputs with a Void datum (d87980), the ledger accepts it because no validator requires a datum
 * to survive, and the canonical reference NFT ends up at an address the issuer no longer controls.
 * Measured twice on devnet through the SDK path before 0.15.0 (txs df4db48f…, 7ee37fd2…).
 *
 * The layers, and each one's blind spot:
 *   1. THIS TEST — the asset list hides reference tokens, so the UI cannot offer one.
 *      Blind to anything that builds a transfer without going through the picker.
 *   2. The SDK (0.15.0) refuses on the CIP-67 label in transfer and seize. Blind if the
 *      operator selects the backend builder, which is TransferModal's DEFAULT.
 *   3. The Java backend (Cip68.refuseReferenceToken) refuses the same way. Blind if the
 *      operator selects the SDK builder.
 *
 * ⚑ Because the transfer builder is an operator TOGGLE defaulting to "backend", neither 2 nor 3
 * covers both paths on its own — which is exactly why the UI filter is worth a test of its own
 * rather than being treated as cosmetic.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let ran = 0;

// ---- 1. the label predicate itself, behaviourally ----
// Compiled copy of lib/utils/cip68.ts; see the test:reftoken script.
const { isReferenceToken, labelAssetNameHex } = require("./.reftoken-build/cip68.js");

const BASE = "434950313133"; // "CIP113"
assert.strictEqual(isReferenceToken(labelAssetNameHex(100, BASE)), true,
  "a label-100 name must be recognised as a reference token");
console.log("  OK   a (100) name is recognised as a reference token");
ran++;

for (const label of [222, 333]) {
  assert.strictEqual(isReferenceToken(labelAssetNameHex(label, BASE)), false,
    `label ${label} is a USER token and must not be treated as a reference token`);
}
// ⛔ The half that catches an over-broad predicate. If isReferenceToken returned true for every
// labelled name, the asset list would hide the tokens people actually hold and the first check
// above would still pass.
console.log("  OK   (222) and (333) user tokens are not mistaken for reference tokens");
ran++;

assert.strictEqual(isReferenceToken(BASE), false, "an unlabelled name is not a reference token");
assert.strictEqual(isReferenceToken(""), false, "an empty name must not throw or match");
console.log("  OK   unlabelled and empty names are handled without matching");
ran++;

// ---- 2. the asset list actually applies it ----
// A source check, deliberately: the property is "the filter is wired into the list the picker
// reads", which is structural. A behavioural test would need the whole balance pipeline mocked,
// and would then prove the mock filters rather than that the app does.
const balance = fs.readFileSync("lib/api/balance.ts", "utf8");
const code = balance.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");

assert.ok(
  /isReferenceToken/.test(code),
  "lib/api/balance.ts no longer references isReferenceToken, so the wallet asset list will " +
    "include (100) reference tokens and the transfer picker will offer one.",
);
assert.ok(
  /\.filter\(\s*\(?\s*\w+\s*\)?\s*=>\s*!\s*isReferenceToken\(/.test(code),
  "the reference-token filter is no longer applied as a negated filter over the asset list. " +
    "It must REMOVE label-100 assets: a transfer of one erases the metadata it carries, and the " +
    "ledger accepts that silently.",
);
console.log("  OK   the wallet asset list filters reference tokens out");
ran++;

console.log(`\n${ran} checks passed`);
