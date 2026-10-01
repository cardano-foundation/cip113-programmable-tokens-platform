/**
 * A registration callback must carry the token's IDENTITY, and must never guess it.
 *
 * ⛔ THE BUG, measured on preprod 2026-10-01 through the SDK path. A CIP-68 FES token was built and
 * registered, and every later backend lookup was refused:
 *
 *     The backend's freeze-and-seize record for b6e7a4ad… does not describe that token: its admin
 *     key hash and asset name derive policy 602030fa…
 *
 * The token and the chain were both correct. The STORED ROW was wrong, because:
 *
 *   1. the combined step has TWO onComplete calls — one after build, one after submit — and the
 *      submit one omitted `adminPkh`, `blacklistInitTxInput` and `userAssetNameHex`. The wizard
 *      reducer REPLACES `result`, and the registration callback fires on the submit step, so
 *      whatever the second payload omits is gone by the time the row is written.
 *   2. the flow then fell back to `stringToHex(tokenDetails.assetName)` — the UNLABELLED name —
 *      and issuer_admin is parameterised by (adminPkh, assetName), so the row derived a different
 *      policy id than the token has.
 *
 * ⚑ IT ONLY BIT THE SDK PATH, which is why it survived. The backend branch writes its own row
 * server-side from the name IT derived, so the callback's value is redundant there; on the SDK path
 * the callback is the ONLY writer. A field redundant on one branch and load-bearing on the other is
 * exactly the kind that gets dropped from one of them.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let ran = 0;
const read = (f) => {
  assert.ok(fs.existsSync(f), `${f} is gone — this check is now blind`);
  return fs.readFileSync(f, "utf8");
};

// ---- 1. BOTH onComplete payloads carry the identity fields ----
const step = read("components/register/steps/freeze-and-seize/combined-build-sign-submit-step.tsx");
const payloads = [...step.matchAll(/onComplete\(\{[\s\S]*?\}\);/g)].map((m) => m[0]);
assert.ok(
  payloads.length >= 2,
  `expected at least 2 onComplete payloads in the combined step, found ${payloads.length}. If the ` +
    `step was refactored to one, this check must be updated — but note the bug was the two ` +
    `disagreeing.`,
);
for (const field of ["adminPkh", "userAssetNameHex", "blacklistInitTxInput"]) {
  const carrying = payloads.filter((p) => p.includes(field)).length;
  assert.strictEqual(
    carrying,
    payloads.length,
    `${carrying} of ${payloads.length} onComplete payloads carry \`${field}\`. ALL must: the last ` +
      `one to fire REPLACES the step result the registration callback reads, so a field present in ` +
      `only one payload is lost whenever the other fires last. This is the defect that wrote a row ` +
      `describing a different token.`,
  );
}
console.log(`  OK   all ${payloads.length} onComplete payloads carry the identity fields`);
ran++;

// ---- 2. the flow REFUSES the unlabelled fallback for a CIP-68 token ----
const flow = read("lib/registration/flows/freeze-and-seize-flow.tsx");
assert.ok(
  /cip68Enabled && !combinedResult\.userAssetNameHex/.test(flow),
  "freeze-and-seize-flow no longer refuses to fall back to the UNLABELLED asset name for a CIP-68 " +
    "token. issuer_admin is parameterised by (adminPkh, assetName), so the unlabelled form records " +
    "a row that derives a different policy id — and the refusal the operator then sees blames the " +
    "token, which is never at fault.",
);
// ⛔ And the fallback must SURVIVE for non-CIP-68, where the raw hex IS the minted name. Removing
// it entirely would break every unlabelled registration — the opposite over-correction.
assert.ok(
  /stringToHex\(tokenDetails\?\.assetName \|\| ''\)/.test(flow),
  "the raw-name fallback is gone entirely. It is correct when CIP-68 is OFF — there is no label, so " +
    "the raw hex is the minted name. Only the CIP-68 case must refuse.",
);
console.log("  OK   the unlabelled fallback refuses for CIP-68 and survives for plain tokens");
ran++;

// ---- 3. the sibling guard on adminPkh is still there ----
// Same class, already fixed once; if it is removed the asset-name guard above is only half the rule.
const container = read("components/register/wizard/wizard-step-container.tsx");
assert.ok(
  /did not report the admin key hash/.test(container),
  "the wizard no longer refuses a registration with no adminPkh. The token policy id is derived " +
    "from it, so guessing it from the wallet writes a row describing a different token — the same " +
    "defect as the asset name, in the other parameter.",
);
console.log("  OK   the adminPkh guard still refuses rather than guessing");
ran++;

console.log(`\n${ran} checks passed`);
