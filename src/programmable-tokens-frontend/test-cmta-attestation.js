const assert = require("node:assert/strict");
const { Address } = require("@evolution-sdk/evolution");
const { buildCmtaAttestationPayloadHex, parseCmtaAttestationJson, prepareCmtaSignature,
  sameStakeIdentity, stakeIdentityFromBaseAddress } =
  require("./.attestation-build/lib/rwa/attestation.js");

const stakeHash = "11".repeat(28);
const policy = "ab".repeat(28);
const vkey = "cc".repeat(32);
const address = (header, hash = stakeHash) => Address.toBech32(Address.fromHex(
  header + "22".repeat(28) + hash));
const keyAddress = address("00");
const scriptAddress = address("20");
assert.deepEqual(stakeIdentityFromBaseAddress(keyAddress),
  { credentialHash: stakeHash, credentialType: 0 });
assert.deepEqual(stakeIdentityFromBaseAddress(scriptAddress),
  { credentialHash: stakeHash, credentialType: 1 });
assert.equal(sameStakeIdentity(stakeIdentityFromBaseAddress(keyAddress),
  stakeIdentityFromBaseAddress(scriptAddress)), false);

const now = 1_700_000_000_000;
const expiry = now + 600_000;
const generated = buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 1, now, now - 3_600_000);
assert.equal(generated, stakeHash + "01" + "0000018bcfe56800" + policy + "0200");
assert.equal(generated.length, 134);
assert.equal(buildCmtaAttestationPayloadHex(scriptAddress, policy, 2, 1, now, now - 3_600_000),
  generated.slice(0, -2) + "01");
assert.throws(() => buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 0, now, now - 3_600_000), /tier/);
assert.throws(() => buildCmtaAttestationPayloadHex(keyAddress, policy, 256, 1, now, now - 3_600_000), /network/);
assert.throws(() => buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 1, now, now - 119_999), /expiry/);
assert.throws(() => buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 1, Number.MAX_SAFE_INTEGER + 1, now), /expiry/);
assert.throws(() => buildCmtaAttestationPayloadHex(keyAddress, "abc", 2, 1, now, now - 3_600_000), /policy/);
const signatureOnly = prepareCmtaSignature("0x" + "DD".repeat(64), generated, generated, [vkey]);
assert.deepEqual(signatureOnly, { payloadHex: generated, signatureHex: "dd".repeat(64) });
assert.throws(() => prepareCmtaSignature("dd".repeat(64), "", generated, [vkey]), /Copy the payload/);
assert.throws(() => prepareCmtaSignature("dd".repeat(64), generated, generated.slice(0, -2) + "01", [vkey]), /claim changed/);
const tierTwo = buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 2, now, now - 3_600_000);
assert.throws(() => prepareCmtaSignature("dd".repeat(64), generated, tierTwo, [vkey]), /claim changed/);
const laterExpiry = buildCmtaAttestationPayloadHex(keyAddress, policy, 2, 1, now + 60_000, now - 3_600_000);
assert.throws(() => prepareCmtaSignature("dd".repeat(64), generated, laterExpiry, [vkey]), /claim changed/);
const otherAddress = address("00", "33".repeat(28));
const otherRecipient = buildCmtaAttestationPayloadHex(otherAddress, policy, 2, 1, now, now - 3_600_000);
assert.throws(() => prepareCmtaSignature("dd".repeat(64), generated, otherRecipient, [vkey]), /claim changed/);
assert.throws(() => prepareCmtaSignature("dd".repeat(63), generated, generated, [vkey]), /64-byte/);
assert.throws(() => prepareCmtaSignature("dd".repeat(64), generated, generated, []), /no trusted entity/);
const payload = Buffer.alloc(67);
Buffer.from(stakeHash, "hex").copy(payload, 0);
payload[28] = 1;
payload.writeBigUInt64BE(BigInt(expiry), 29);
Buffer.from(policy, "hex").copy(payload, 37);
payload[65] = 0;
payload[66] = 0;
const bundle = { payloadHex: payload.toString("hex"), signatureHex: "dd".repeat(64), issuerVkeyHex: vkey };
const parse = (value, addr = keyAddress) => parseCmtaAttestationJson(
  JSON.stringify(value), addr, policy, 0, [vkey], now);
assert.equal(parse(bundle).validUntilMs, expiry);
const bad = (mutate) => { const value = Buffer.from(payload); mutate(value); return { ...bundle, payloadHex: value.toString("hex") }; };
assert.throws(() => parse(bundle, scriptAddress), /credential type/);
assert.throws(() => parse(bad((p) => p[0] = 0)), /different stake credential/);
assert.throws(() => parse(bad((p) => p[28] = 0)), /invalid KYC tier/);
assert.throws(() => parse(bad((p) => p[65] = 1)), /different network/);
assert.throws(() => parse(bad((p) => p[66] = 1)), /credential type/);
assert.throws(() => parse({ ...bundle, issuerVkeyHex: "ff".repeat(32) }), /not trusted/);
assert.throws(() => parse({ ...bundle, payloadHex: "00".repeat(37) }), /67 bytes/);
assert.throws(() => parse(bad((p) => p.writeBigUInt64BE(BigInt(now - 1), 29))), /expiry/);
console.log("CMTA payloads and signature-only requests bind to the selected claim; legacy bundles remain valid");
