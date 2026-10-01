/**
 * An unknown network must REFUSE, and a devnet must be configured rather than guessed.
 *
 * ⛔ THE DEFECT THIS PINS. `getCardanoNetwork()` used to `console.warn` and fall back to preview
 * for any unrecognised NEXT_PUBLIC_NETWORK. Measured 2026-10-01 with `NEXT_PUBLIC_NETWORK=devnet`
 * before devnet was supported: the build resolved to PREVIEW — preview's network magic, preview's
 * slotConfig, and a chain API default on the public preview Blockfrost — while the warning went to
 * a browser console nobody reads. A build configured for one network silently addressed another.
 *
 * ⚑ Absent is NOT the same as unknown, and both halves are asserted below. CI builds this app with
 * no NEXT_PUBLIC_NETWORK at all, so absence must keep its default or every such build breaks;
 * a non-empty unknown value means somebody chose something that does not exist, and that refuses.
 */
const assert = require("node:assert");

const mod = require("./.network-build/network.js");
const { getCardanoNetwork, getDevnetChainParams, toSdkNetwork } = mod;

let ran = 0;
const ENV_KEYS = [
  "NEXT_PUBLIC_NETWORK",
  "NEXT_PUBLIC_DEVNET_NETWORK_MAGIC",
  "NEXT_PUBLIC_DEVNET_ZERO_TIME",
  "NEXT_PUBLIC_DEVNET_ZERO_SLOT",
  "NEXT_PUBLIC_DEVNET_SLOT_LENGTH",
  "NEXT_PUBLIC_DEVNET_EPOCH_LENGTH",
  "NEXT_PUBLIC_DEVNET_CHAIN_API_URL",
];
function withEnv(vars, fn) {
  const saved = {};
  for (const k of ENV_KEYS) { saved[k] = process.env[k]; delete process.env[k]; }
  Object.assign(process.env, vars);
  try { return fn(); }
  finally {
    for (const k of ENV_KEYS) {
      if (saved[k] === undefined) delete process.env[k]; else process.env[k] = saved[k];
    }
  }
}

// ---- 1. the three public networks are unchanged ----
for (const n of ["preview", "preprod", "mainnet"]) {
  assert.strictEqual(withEnv({ NEXT_PUBLIC_NETWORK: n }, getCardanoNetwork), n);
}
// Absent and empty both keep the default. CI depends on this.
assert.strictEqual(withEnv({}, getCardanoNetwork), "preview");
assert.strictEqual(withEnv({ NEXT_PUBLIC_NETWORK: "" }, getCardanoNetwork), "preview");
console.log("  OK   preview/preprod/mainnet resolve unchanged, and absent still defaults");
ran++;

// ---- 2. devnet is now a supported network ----
assert.strictEqual(withEnv({ NEXT_PUBLIC_NETWORK: "devnet" }, getCardanoNetwork), "devnet");
console.log("  OK   devnet is a supported network");
ran++;

// ---- 3. an unknown value REFUSES instead of becoming preview ----
// ⛔ The behaviour change. Asserted on the OUTCOME (a throw), not on the message text.
for (const bad of ["devnset", "mainet", "testnet", "Preview", "sanchonet"]) {
  assert.throws(
    () => withEnv({ NEXT_PUBLIC_NETWORK: bad }, getCardanoNetwork),
    (e) => e instanceof Error && e.message.includes(bad),
    `NEXT_PUBLIC_NETWORK="${bad}" must refuse, not fall back to preview`
  );
}
console.log("  OK   an unrecognised network refuses rather than silently becoming preview");
ran++;

// ---- 4. devnet params: every missing variable is NAMED ----
const FULL = {
  NEXT_PUBLIC_NETWORK: "devnet",
  NEXT_PUBLIC_DEVNET_NETWORK_MAGIC: "42",
  NEXT_PUBLIC_DEVNET_ZERO_TIME: "1790810647000",
  NEXT_PUBLIC_DEVNET_ZERO_SLOT: "0",
  NEXT_PUBLIC_DEVNET_SLOT_LENGTH: "1",
  NEXT_PUBLIC_DEVNET_EPOCH_LENGTH: "600",
  NEXT_PUBLIC_DEVNET_CHAIN_API_URL: "http://127.0.0.1:8080/api/v1",
};
for (const key of Object.keys(FULL).filter((k) => k !== "NEXT_PUBLIC_NETWORK")) {
  const partial = { ...FULL };
  delete partial[key];
  assert.throws(
    () => withEnv(partial, getDevnetChainParams),
    (e) => e instanceof Error && e.message.includes(key),
    `a missing ${key} must be named in the error — an unnamed one sends people to the wrong file`
  );
}
console.log("  OK   each missing devnet variable is named in the refusal");
ran++;

// ---- 5. a complete devnet config parses, with the right types ----
const params = withEnv(FULL, getDevnetChainParams);
assert.strictEqual(params.networkMagic, 42);
assert.strictEqual(params.zeroTime, 1790810647000);
assert.strictEqual(params.slotLength, 1);
assert.strictEqual(params.epochLength, 600);
assert.strictEqual(params.chainApiUrl, "http://127.0.0.1:8080/api/v1");
// A non-numeric value must not become NaN and flow into slot arithmetic.
assert.throws(() => withEnv({ ...FULL, NEXT_PUBLIC_DEVNET_ZERO_TIME: "soon" }, getDevnetChainParams));
console.log("  OK   a complete devnet config parses, and a non-numeric one refuses");
ran++;

// ---- 6. the SDK network mapping, including the deliberate undefined ----
assert.strictEqual(toSdkNetwork("mainnet"), "mainnet");
assert.strictEqual(toSdkNetwork("preview"), "preview");
assert.strictEqual(toSdkNetwork("preprod"), "preprod");
// ⛔ devnet is ABSENT, not substituted. "preview" here would claim a devnet is preview.
assert.strictEqual(toSdkNetwork("devnet"), undefined);
console.log("  OK   the SDK network mapping is exact, and devnet is undefined not a stand-in");
ran++;

// ---- 7. the chain selector handles every network, and there is only ONE of it ----
// A source check: getEvolutionChain needs the SDK at runtime, and what matters here is structural
// — that no network can fall through to `undefined`, which is the defect that started this.
const fs = require("node:fs");
const chainSrc = fs.readFileSync("lib/utils/chain.ts", "utf8");
for (const n of ["mainnet", "preprod", "preview", "devnet"]) {
  assert.ok(
    new RegExp(`case\\s+"${n}"`).test(chainSrc),
    `lib/utils/chain.ts has no case for "${n}" — an unhandled network returns undefined, and an ` +
      `undefined chain reaches the Evolution client as its chain`
  );
}
// The two former duplicates must now delegate rather than switch on their own. Each used to be an
// independent switch, so adding a network meant editing both and forgetting one was silent.
const ctxSrc = fs.readFileSync("contexts/cip113-context.tsx", "utf8");
const deploySrc = fs.readFileSync("lib/deployment/deploy.ts", "utf8");
assert.ok(/getEvolutionChain\(/.test(ctxSrc), "cip113-context no longer uses the shared chain selector");
assert.ok(/getEvolutionChain\(/.test(deploySrc), "deploy.ts no longer uses the shared chain selector");
assert.ok(!/case "preview": return previewChain/.test(ctxSrc),
  "cip113-context has grown its own chain switch again — there must be one selector, not three");
console.log("  OK   one chain selector, with a case for all 4 networks, used by both call sites");
ran++;

console.log(`\n${ran} checks passed`);
