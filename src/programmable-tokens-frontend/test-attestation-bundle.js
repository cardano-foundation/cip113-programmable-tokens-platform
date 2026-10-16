// Holder-side data flow for a pasted attestation (plan "Holder-side data flow", PR #29).
const assert = require("node:assert/strict");
const { Address } = require("@evolution-sdk/evolution");
const { resolvePastedAttestation, buildAttestationBundle, buildCmtaAttestationPayloadHex } =
  require("./.attestation-build/lib/rwa/attestation.js");
const { coseVectors: V } = require("./test-fixtures/cip30-vectors.js");

const address = (stakeHex) => Address.toBech32(Address.fromHex("00" + "22".repeat(28) + stakeHex));
const holder = address("aa".repeat(28));            // the genuine vector's subject
const otherParty = address("33".repeat(28));
const bundleText = buildAttestationBundle({ payloadHex: V.claim, coseSign1Hex: V.sign1(V.claim, false), coseKeyHex: V.key });
const ctx = (over = {}) => ({ address: holder, policyId: V.policy, networkId: 1, trustedVkeys: [V.vkey],
  signedPayloadHex: "", currentPayloadHex: "", nowMs: 0, ...over });

// 1. A bundle pasted with NO copied payload is accepted: it carries its own claim.
const fromBundle = resolvePastedAttestation(bundleText, ctx());
assert.equal(fromBundle.kind, "bundle");
assert.equal(fromBundle.attestation.payloadHex, V.claim);

// 2. A bundle whose tier/expiry differ from the modal's defaults is sent UNCHANGED — the modal's
//    own claim (built from its inputs) plays no part.
const modalDefault = buildCmtaAttestationPayloadHex(holder, V.policy, 1, 2, 10_000_000_000_000, 0);
assert.notEqual(modalDefault, V.claim);
const unchanged = resolvePastedAttestation(bundleText, ctx({ currentPayloadHex: modalDefault, signedPayloadHex: modalDefault }));
assert.equal(unchanged.attestation.payloadHex, V.claim);
assert.equal(unchanged.tier, 1);
assert.equal(unchanged.validUntilMs, 100_000_000);

// 3. A raw signData {signature, key} still needs the copied, unchanged claim.
const rawText = JSON.stringify({ signature: V.sign1(V.claim, false), key: V.key });
assert.throws(() => resolvePastedAttestation(rawText, ctx()), /Copy the claim/);
assert.equal(resolvePastedAttestation(rawText, ctx({ signedPayloadHex: V.claim, currentPayloadHex: V.claim })).kind, "signData");
assert.throws(() => resolvePastedAttestation(rawText, ctx({ signedPayloadHex: V.claim, currentPayloadHex: modalDefault })),
  /claim changed/);

// 4. The recipient's bundle pasted into the SENDER field is rejected (different stake credential).
assert.throws(() => resolvePastedAttestation(bundleText, ctx({ address: otherParty })), /different stake credential/);
// …and a bundle for another token is rejected by name.
assert.throws(() => resolvePastedAttestation(bundleText, ctx({ policyId: "cd".repeat(28) })), /different token/);

assert.equal(JSON.parse(bundleText).v, 1);
console.log("pasted bundles use their own claim; raw signData needs the copied claim; wrong party/token refused");
