/**
 * Every FES operation must be reachable through the SDK from the UI, and the toggle must
 * default to the backend.
 *
 * ⛔ WHY THIS TEST EXISTS AT ALL. Giovanni's goal is that the platform "must migrate to latest
 * version and USE THE SDK ON THE FE to ensure fes tokens work okay". Parity was built op by op
 * across several components and NOTHING asserted it held. I then mis-read it myself: I produced a
 * path map claiming register, blacklist init and freeze/unfreeze were BACKEND-ONLY, because I
 * derived it from which endpoints appear in lib/api instead of reading the handlers. All three
 * already had SDK branches. A claim that cannot be checked gets restated wrongly, and a reviewer
 * cannot tell which version is true — so the property is pinned here.
 *
 * ⚑ AND THE DEFAULT IS PART OF THE PROPERTY, not a detail. Parity means the SDK CAN build each
 * transaction, not that it becomes the route everyone takes: the SDK path stays opt-in per
 * operation until it is verified against a live deployment. A refactor that derived the default
 * from `sdkAvailable` would silently move every user onto the unverified path the moment the
 * capability was enabled — which is exactly what BlacklistSection's own comment warns about.
 *
 * Source-scanning, deliberately: "both routes exist and the default is backend" is a structural
 * property of the components. Driving it behaviourally would need a browser, a CIP-30 wallet and a
 * live backend, and would then prove the mocks agree rather than that the app offers both paths.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let ran = 0;
const read = (f) => {
  assert.ok(fs.existsSync(f), `${f} is gone — this check is now blind and must be updated`);
  return fs.readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
};

/**
 * One row per FES operation: where it lives, the SDK call that must be present, and the backend
 * call that must still be present. Both halves matter — losing the backend call would silently
 * make the SDK the only route, which is the same defect from the other side.
 */
const OPS = [
  {
    op: "register + blacklist init (chained)",
    file: "components/register/steps/freeze-and-seize/combined-build-sign-submit-step.tsx",
    sdk: /buildFESRegistration\(/,
    backend: /registerToken\(/,
    alsoBackend: /initBlacklist\(/,
  },
  {
    op: "freeze / unfreeze",
    file: "components/admin/BlacklistSection.tsx",
    sdk: /protocol\.compliance\.(freeze|unfreeze)\(/,
    backend: /(addToBlacklist|removeFromBlacklist)\(/,
  },
  {
    op: "seize",
    file: "components/admin/SeizeSection.tsx",
    sdk: /protocol\.compliance\.seize\(/,
    backend: /seizeTokens\(/,
  },
  {
    op: "transfer",
    file: "components/transfer/TransferModal.tsx",
    sdk: /protocol\.transfer\(/,
    backend: /transferToken\(/,
  },
  {
    op: "mint",
    file: "components/admin/MintSection.tsx",
    sdk: /protocol\.mint\(/,
    // Mint is SDK-ONLY in the FE by design — there is no backend mint call site to require.
    backend: null,
  },
];

for (const { op, file, sdk, backend, alsoBackend } of OPS) {
  const src = read(file);
  assert.ok(sdk.test(src),
    `${op}: no SDK call site in ${file}. Every FES operation must be reachable through the SDK ` +
      `from the UI — that is the goal this parity serves.`);
  if (backend) {
    assert.ok(backend.test(src),
      `${op}: the BACKEND call site is gone from ${file}, so the SDK is now the only route. ` +
        `The SDK path is opt-in until verified against a live deployment; removing the backend ` +
        `route moves everyone onto it by omission.`);
  }
  if (alsoBackend) {
    assert.ok(alsoBackend.test(src), `${op}: the backend blacklist-init call site is gone from ${file}`);
  }
}
console.log(`  OK   all ${OPS.length} FES operation groups offer the SDK route (and keep the backend one)`);
ran++;

// ---- the toggle defaults to backend, everywhere it exists ----
const TOGGLES = [
  { file: "components/admin/BlacklistSection.tsx", pattern: /useState<TransactionBuilder>\("backend"\)/ },
  { file: "components/admin/SeizeSection.tsx", pattern: /useState<TransactionBuilder>\("backend"\)/ },
  { file: "components/admin/BurnSection.tsx", pattern: /useState<TransactionBuilder>\("backend"\)/ },
  { file: "components/transfer/TransferModal.tsx", pattern: /useState<TransactionBuilder>\("backend"\)/ },
  // The register wizard holds a boolean rather than the shared union; false means backend.
  { file: "components/register/steps/freeze-and-seize/combined-build-sign-submit-step.tsx",
    pattern: /useState\(false\)/ },
];

for (const { file, pattern } of TOGGLES) {
  const src = read(file);
  assert.ok(pattern.test(src),
    `${file}: the transaction-builder toggle no longer defaults to the backend. Parity means the ` +
      `SDK CAN build the transaction, not that it becomes the default route — deriving the ` +
      `default from sdkAvailable would move every user onto an unverified path.`);
}
console.log(`  OK   the builder toggle defaults to backend in all ${TOGGLES.length} places`);
ran++;

// ---- non-vacuity: the SDK patterns must not match a file that has no SDK path ----
// ⛔ Without this the matchers could be wrong in a way that happens to pass. init-blacklist-step
// is the control: it is a BACKEND-ONLY component (and no longer in the FES flow, which uses the
// combined step), so a matcher that fires on it is matching something other than an SDK call.
const control = read("components/register/steps/freeze-and-seize/init-blacklist-step.tsx");
assert.ok(/initBlacklist\(/.test(control), "the control file should still call the backend initBlacklist");
assert.ok(
  !/protocol\.compliance\.|buildFESRegistration\(|protocol\.register\(/.test(control),
  "the control file now has an SDK call, so it is no longer a negative control — pick another, " +
    "or delete this legacy step (it is not referenced by freeze-and-seize-flow.tsx).",
);
console.log("  OK   the matchers do not fire on a known backend-only component");
ran++;

console.log(`\n${ran} checks passed`);
