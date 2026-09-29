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

console.log(`\n${ran} checks passed`);
