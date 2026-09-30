/**
 * The collapse must not be conditional rendering, and the page must not scroll itself.
 *
 * ⛔ WHY THIS IS A TEST AND NOT A CODE REVIEW. `{!collapsed && children}` and
 * `<div hidden={collapsed}>` look equivalent and are not: the first unmounts, taking every input
 * value, fetched result and message with it. That failure already happened on this page — the note
 * recording which funding strategy built the genesis lived inside a block gated on `!genesisStep`,
 * so it vanished at the instant the build succeeded, destroying the answer exactly when it became
 * one. Nothing about that was visible in a diff; it looked like tidy conditional rendering.
 *
 * A source-scanning check, deliberately: the property is structural, and asserting it here is
 * cheaper than a browser and catches the class rather than the instance.
 */
const assert = require("node:assert");
const fs = require("node:fs");

let ran = 0;
const step = fs.readFileSync("components/deployment/ceremony-step.tsx", "utf8");
const code = step.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");

// ---- 1. children are hidden, never unmounted ----
assert.ok(
  /hidden=\{collapsed\}/.test(code),
  "CeremonyStep no longer hides its children with the `hidden` attribute",
);
assert.ok(
  !/\{\s*!collapsed\s*&&/.test(code) && !/collapsed\s*\?\s*null/.test(code),
  "CeremonyStep now renders children conditionally. A collapsed step must stay MOUNTED: " +
    "unmounting throws away input values, fetched results and messages — which is exactly how " +
    "the genesis funding note was destroyed at the moment it became an answer.",
);
console.log("  OK   a collapsed step hides its children rather than unmounting them");
ran++;

// ---- 2. no auto-scroll, anywhere in the ceremony surface ----
for (const f of [
  "components/deployment/ceremony-step.tsx",
  "app/ops/bootstrap-protocol/page.tsx",
]) {
  const src = fs.readFileSync(f, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
  assert.ok(
    !/scrollIntoView|window\.scrollTo|scrollBy/.test(src),
    `${f} now scrolls the page for the operator. That was declined: it fights a reader who has ` +
      "deliberately opened a finished step, and collapsing already removes the distance.",
  );
}
console.log("  OK   nothing scrolls the operator's page for them");
ran++;

// ---- 3. the page actually uses the component, and every step can fold ----
const page = fs.readFileSync("app/ops/bootstrap-protocol/page.tsx", "utf8");
const opens = [...page.matchAll(/<CeremonyStep\b/g)].length;
const closes = [...page.matchAll(/<\/CeremonyStep>/g)].length;
assert.strictEqual(opens, closes, `${opens} CeremonyStep opens vs ${closes} closes`);
assert.ok(opens >= 6, `expected at least 6 steps, found ${opens} — is the page still stepped?`);
// A step with no `done` prop can never fold, which silently defeats the whole feature.
const withDone = [...page.matchAll(/<CeremonyStep[\s\S]{0,400}?done=\{/g)].length;
assert.strictEqual(withDone, opens, `${opens} steps but only ${withDone} carry a \`done\` prop`);
console.log(`  OK   the page is ${opens} steps and every one of them can fold`);
ran++;

// ---- 4. the gate note stays OUTSIDE the block whose end destroyed it ----
// The original defect, guarded directly: gateNote must not sit inside `!genesisStep`.
const gateBlock = page.slice(
  page.indexOf("{phaseOneDone && !genesisStep && ("),
  page.indexOf("{genesisStep && multisig && ("),
);
const noteAt = gateBlock.indexOf("{gateNote && (");
const gateEnds = gateBlock.indexOf("</div>\n            )}");
assert.ok(
  noteAt === -1 || (gateEnds !== -1 && noteAt > gateEnds),
  "gateNote is back inside the `!genesisStep` block, so it will unmount the moment the genesis " +
    "builds — destroying the record of WHICH funding strategy worked at the instant it matters.",
);
console.log("  OK   the genesis funding note still outlives the gate that produced it");
ran++;

// ---- 5. NOTHING FOLDS BY ITSELF ----
// The regression this exists for: folding on `done` hid the verification result and the
// cannotAuthorise acknowledgement, and that acknowledgement GATES the phase-one submit button —
// so the operator faced a disabled button whose reason had just been hidden. A step's own
// progress is not consent to stop showing it.
assert.ok(
  /const collapsed = closedByHand;/.test(code),
  "CeremonyStep's collapsed state is no longer the operator's choice alone. It must not derive " +
    "from `done`: folding a step because it succeeded hid the cannotAuthorise acknowledgement " +
    "that gates phase one, leaving a disabled button with no visible reason.",
);
assert.ok(
  !/collapsed\s*=\s*done|done\s*&&\s*!/.test(code),
  "`collapsed` is being computed from `done` again — see above",
);
console.log("  OK   a step folds only when the operator folds it");
ran++;

// ---- 6. and the gate that blocks phase one is never inside a foldable-by-default region ----
// Belt and braces on the same defect, from the page's side: the acknowledgement must sit in the
// step whose own submit button it gates, so the two can never be separated.
const stepFive = page.slice(page.indexOf('label="5"'), page.indexOf('label="Phase one"'));
assert.ok(
  stepFive.includes("cannotAuthorise") && stepFive.includes("acceptedNoAuthority"),
  "the cannotAuthorise acknowledgement has moved out of the build-and-verify step; it must stay " +
    "with the plan it qualifies, or it can be hidden while still gating the submit",
);
console.log("  OK   the upgrade-authority acknowledgement sits with the plan it qualifies");
ran++;

// ---- 7. reference scripts NEVER go to a spendable address ----
// The SDK's own warning, measured on preview: "a wallet holding them alongside ordinary funds had
// two of four consumed by a routine retry, and NOTHING ERRORED." Seven outputs carrying the scripts
// every programmable transaction reads must sit where nothing can spend them. `issuanceCborHex` is
// always_fail's address; every other address in the plan is spendable by its own validator.
const refAddr = /referenceScriptAddress:\s*([^,\n]+)/.exec(page);
assert.ok(refAddr, "could not find referenceScriptAddress — this check is now blind");
assert.ok(
  /addresses\.issuanceCborHex/.test(refAddr[1]),
  `reference scripts are being paid to \`${refAddr[1].trim()}\`. They must go to always_fail's ` +
    "address (plan.addresses.issuanceCborHex). A wallet address lets coin selection eat the " +
    "protocol's own infrastructure with no error; protocolParams, registry and upgradeMultisig " +
    "are each spendable by their own validator.",
);
assert.ok(
  !/referenceScriptAddress:\s*[^,\n]*changeAddress/.test(page),
  "referenceScriptAddress is the deployer's change address again — the exact defect this guards",
);
console.log("  OK   reference scripts are paid to an address nothing can spend from");
ran++;

console.log(`\n${ran} checks passed`);
