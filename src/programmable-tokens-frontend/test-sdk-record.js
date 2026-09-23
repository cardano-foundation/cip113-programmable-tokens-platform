/**
 * The page's SDK-shaped output, against the SDK's own committed record.
 *
 * ## The fixture is the encoder's output, not a description of it
 *
 * `test-fixtures/sdk-instance-alpha4-7e8a631.json` is a verbatim copy of
 * `deployments/preview/alpha4-7e8a631.json` from cip113-sdk-ts — the real file
 * `saveInstance` wrote. And it records the SAME live preview instance as this
 * repo's `protocol-bootstraps-preview.json`: both carry txHash 8314e59f….
 *
 * That coincidence is what makes the test strong. It runs the actual pipeline —
 * our committed platform record, through the page's conversion, to bytes — and
 * compares against what the SDK really has on disk. It does not reimplement
 * `saveInstance` and check the reimplementation against itself, which is the
 * shape of test that passes while both sides are wrong together.
 *
 * ## Controls
 *
 * Byte-equality is only evidence if it is losable. Four mutations are applied
 * below and each MUST break the comparison: dropping the trailing newline,
 * changing the indent, re-ordering keys, and leaving `schemaVersion` in. If any
 * of them still compared equal, the assertion above it would be measuring
 * nothing.
 */
const assert = require("node:assert");
const { readFileSync } = require("node:fs");

async function main() {
  const { toSdkInstanceRecord, serialiseSdkInstance, isSdkInstanceName, sdkInstancePath } =
    await import("./.sdkrec-build/sdk-record.js");

  const SDK_FILE = "./test-fixtures/sdk-instance-alpha4-7e8a631.json";
  const PLATFORM_FILE =
    "../programmable-tokens-offchain-java/src/main/resources/protocol-bootstraps-preview.json";

  const sdkBytes = readFileSync(SDK_FILE, "utf8");
  const platformEntries = JSON.parse(readFileSync(PLATFORM_FILE, "utf8"));
  let ran = 0;

  // The premise: both files describe the same deployment. Without this the
  // comparison below would be comparing two unrelated instances and passing or
  // failing for reasons that have nothing to do with the conversion.
  const sdkParsed = JSON.parse(sdkBytes);
  assert.strictEqual(platformEntries.length, 1, "expected one platform instance");
  assert.strictEqual(
    sdkParsed.txHash,
    platformEntries[0].txHash,
    "the two fixtures are different deployments — this test's premise is gone",
  );
  console.log(`  OK   both fixtures record the same instance (${sdkParsed.txHash.slice(0, 12)}…)`);
  ran++;

  // ---- THE ASSERTION ------------------------------------------------------
  const produced = serialiseSdkInstance(toSdkInstanceRecord(platformEntries[0]));
  assert.strictEqual(
    produced,
    sdkBytes,
    "the SDK-shaped output is not byte-equal to the SDK's own committed record",
  );
  console.log(`  OK   byte-equal to the SDK's committed record (${sdkBytes.length} bytes)`);
  ran++;

  // ---- CONTROLS: each mutation must break it ------------------------------
  const base = toSdkInstanceRecord(platformEntries[0]);

  const noNewline = JSON.stringify(base, null, 2);
  assert.notStrictEqual(noNewline, sdkBytes,
    "CONTROL FAILED: output without the trailing newline still compared equal");
  console.log("  OK   control: dropping the trailing newline breaks it");
  ran++;

  const wrongIndent = JSON.stringify(base, null, 4) + "\n";
  assert.notStrictEqual(wrongIndent, sdkBytes,
    "CONTROL FAILED: a 4-space indent still compared equal");
  console.log("  OK   control: changing the indent breaks it");
  ran++;

  const reordered = Object.fromEntries(Object.entries(base).reverse());
  assert.notStrictEqual(serialiseSdkInstance(reordered), sdkBytes,
    "CONTROL FAILED: reversed key order still compared equal");
  console.log("  OK   control: re-ordering the keys breaks it");
  ran++;

  const withSchema = serialiseSdkInstance({ schemaVersion: 3, ...base });
  assert.notStrictEqual(withSchema, sdkBytes,
    "CONTROL FAILED: leaving schemaVersion in still compared equal");
  console.log("  OK   control: leaving schemaVersion in breaks it");
  ran++;

  // ---- the bigint replacer is real, not decoration ------------------------
  // Nothing in the committed record is a bigint, so the assertion above cannot
  // exercise the replacer. Without this the replacer could be deleted and every
  // test would still pass — and it would then throw on the first bootstrap that
  // produced one, which is the case it exists for.
  const withBig = serialiseSdkInstance({ a: BigInt("9007199254740993") });
  assert.strictEqual(withBig, '{\n  "a": "9007199254740993"\n}\n',
    "a bigint must serialise as a decimal STRING, as saveInstance does");
  assert.throws(() => JSON.stringify({ a: BigInt(1) }),
    "premise gone: JSON.stringify no longer throws on bigint, so the replacer is moot");
  console.log("  OK   a bigint serialises as a string (and plain stringify still throws)");
  ran++;

  // A value that was a NUMBER stays a number — the alpha.4 record's
  // maxInlineDatumBytes is 1024 unquoted, and that is this same function.
  assert.ok(/"maxInlineDatumBytes": 1024,/.test(produced),
    "maxInlineDatumBytes must stay an unquoted number, as it is in the SDK's record");
  console.log("  OK   a number stays a number — no blanket string-for-numbers rule");
  ran++;

  // ---- instance names ------------------------------------------------------
  for (const good of ["alpha4-7e8a631", "a", "A1", "x.y_z-1"]) {
    assert.ok(isSdkInstanceName(good), `${good} should be a valid instance name`);
  }
  for (const bad of ["-leading", ".leading", "_leading", "has space", "has/slash", ""]) {
    assert.ok(!isSdkInstanceName(bad), `${bad} should be rejected as an instance name`);
  }
  assert.strictEqual(sdkInstancePath("preview", "alpha5"), "deployments/preview/alpha5.json");
  console.log("  OK   instance names match the harness rule, and the path is the harness path");
  ran++;

  // ---- the array must be refused, not silently indexed --------------------
  assert.throws(() => toSdkInstanceRecord(platformEntries), /not the array/);
  assert.throws(() => toSdkInstanceRecord({ nope: 1 }), /no txHash/);
  console.log("  OK   the whole array is refused rather than silently taking [0]");
  ran++;

  console.log(`\n${ran} checks passed`);
}

main().catch((e) => { console.error(e); process.exit(1); });
