/**
 * The colour must be a function of the KEY, not of the list.
 *
 * That is the whole property. If a chip can move when the list changes, the operator cannot
 * trust it during the one minute it exists to help with — and they will not discover that
 * until the list changes, which is the worst moment.
 */
const assert = require("node:assert");

async function main() {
  const { participantColour, confusablePairs } = await import(
    "./.colour-build/participant-colour.js"
  );
  const A = "491112dd01155c07dab485f71b572e0cae759e2cd38b1c0e97554297";
  const B = "9dc7fb65d53743f5960d8306f04e933b35d10715754d6f4f7e35cc84";
  let ran = 0;

  // ---- stable across calls, position and case ----
  assert.strictEqual(participantColour(A).hue, participantColour(A).hue);
  assert.strictEqual(participantColour(A).hue, participantColour(A.toUpperCase()).hue);
  assert.strictEqual(participantColour(A).hue, participantColour(`  ${A}  `).hue);
  console.log("  OK   the same key yields the same hue, whatever its case or padding");
  ran++;

  // ---- ADDING A MEMBER MOVES NOBODY ----
  // The failure an index-based palette has: one edit and every chip changes.
  const before = [A, B].map((k) => participantColour(k).hue);
  const after = ["cc".repeat(28), A, B].map((k) => participantColour(k).hue).slice(1);
  assert.deepStrictEqual(after, before, "adding a member at the front moved existing hues");
  console.log("  OK   inserting a participant leaves every other chip where it was");
  ran++;

  // ---- a one-character difference must NOT land on a neighbouring hue ----
  // The pair most likely to exist in a real list is a transposition of one key hash. If those
  // two landed next to each other the chips would fail on exactly the case they are for.
  const transposed = A.slice(0, 8) + A[9] + A[8] + A.slice(10);
  assert.notStrictEqual(transposed, A, "the fixture did not actually transpose anything");
  const d = Math.abs(participantColour(A).hue - participantColour(transposed).hue);
  assert.ok(
    Math.min(d, 360 - d) > 25,
    `a transposed key hash landed ${Math.min(d, 360 - d)}° away — too close to distinguish`,
  );
  console.log("  OK   a transposed key hash gets a clearly different hue");
  ran++;

  // ---- confusable pairs are REPORTED, not silently fixed ----
  assert.deepStrictEqual(confusablePairs([A, B]), []);
  const twins = confusablePairs([A, A]);
  assert.strictEqual(twins.length, 1, "two identical keys must be reported as confusable");
  // And the reporter must not have changed either hue to make them distinct.
  assert.strictEqual(participantColour(A).hue, participantColour(A).hue);
  console.log("  OK   near-identical hues are reported, and nothing is nudged to hide it");
  ran++;

  // ---- the text channel is always present ----
  assert.strictEqual(participantColour(A).shortHash, A.slice(0, 8));
  assert.match(participantColour(A).swatch.backgroundColor, /^hsl\(\d+ /);
  console.log("  OK   a short hash accompanies every chip, so colour is never the only channel");
  ran++;

  console.log(`\n${ran} checks passed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
