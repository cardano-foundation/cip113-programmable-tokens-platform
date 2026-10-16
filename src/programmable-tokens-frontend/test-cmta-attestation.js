const assert = require("node:assert/strict");
const { Address } = require("@evolution-sdk/evolution");
const { buildCmtaAttestationPayloadHex, parseCmtaAttestationJson, prepareCip30Signature,
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
// ── PR #29: CIP-30 signData results (named test edit, 2026-10-15) ─────────────────────────
// The retired raw-Ed25519 rules (prepareCmtaSignature) and the {signatureHex, issuerVkeyHex}
// bundle are replaced by their CIP-30 equivalents. GENUINE signed vectors from the pinned contract
// (cpt-rwa-ch-cmta-reference @346f9bf, lib/kyc/verify.ak cip30_unit_valid / cip30_unit_hashed_valid,
// public TEST seed): claim subject aa…, tier 1, expiry 100 000 000 ms, policy bb…, network 1.
const { coseVectors } = require("./test-fixtures/cip30-vectors.js");
const V = coseVectors;
const vAddress = address("00", "aa".repeat(28));

// A signData result pasted from another wallet signs the COPIED, unchanged claim.
const fromWallet = JSON.stringify({ signature: V.sign1(V.claim, false), key: V.key });
const prepared = prepareCip30Signature(fromWallet, V.claim, V.claim, [V.vkey]);
assert.deepEqual(prepared, { payloadHex: V.claim, coseSign1Hex: V.sign1(V.claim, false), coseKeyHex: V.key });
assert.throws(() => prepareCip30Signature(fromWallet, "", V.claim, [V.vkey]), /Copy the claim/);
assert.throws(() => prepareCip30Signature(fromWallet, V.claim, V.claim.slice(0, -2) + "01", [V.vkey]), /claim changed/);
assert.throws(() => prepareCip30Signature(fromWallet, V.claim, V.claim, []), /no trusted entity/);
assert.throws(() => prepareCip30Signature(fromWallet, V.claim, V.claim, ["cc".repeat(32)]), /not a trusted issuer/);
assert.throws(() => prepareCip30Signature("dd".repeat(64), V.claim, V.claim, [V.vkey]), /signData result/);
// CIP-8 hashed mode (Ledger): the COSE payload is blake2b_224(claim).
const hashedFromWallet = JSON.stringify({ signature: V.sign1(V.digest, true, V.hashedSig), key: V.key });
assert.equal(prepareCip30Signature(hashedFromWallet, V.claim, V.claim, [V.vkey]).payloadHex, V.claim);
// A forged signature is rejected in the browser too.
assert.throws(() => prepareCip30Signature(JSON.stringify({ signature: V.sign1(V.claim, false, "00".repeat(64)), key: V.key }),
  V.claim, V.claim, [V.vkey]), /does not verify/);

// The v1 bundle: its own claim, checked against this party, token and network.
const bundle = { v: 1, payloadHex: V.claim, coseSign1Hex: V.sign1(V.claim, false), coseKeyHex: V.key };
const parse = (value, addr = vAddress, net = 1, keys = [V.vkey]) => parseCmtaAttestationJson(
  JSON.stringify(value), addr, V.policy, net, keys, 0);
assert.equal(parse(bundle).validUntilMs, 100_000_000);
assert.equal(parse(bundle).tier, 1);
assert.throws(() => parse({ ...bundle, v: 2 }), /version/);
assert.throws(() => parse(bundle, address("20", "aa".repeat(28))), /credential type/);
assert.throws(() => parse(bundle, keyAddress), /different stake credential/);
assert.throws(() => parse(bundle, vAddress, 0), /different network/);
assert.throws(() => parseCmtaAttestationJson(JSON.stringify(bundle), vAddress, "cd".repeat(28), 1, [V.vkey], 0), /different token/);
assert.throws(() => parseCmtaAttestationJson(JSON.stringify(bundle), vAddress, V.policy, 1, [V.vkey], 100_000_000), /expiry/);
assert.throws(() => parse(bundle, vAddress, 1, ["ff".repeat(32)]), /not trusted/);
assert.throws(() => parse({ ...bundle, payloadHex: "00".repeat(37) }), /67 bytes/);
console.log("CMTA claims bind to the selected party; CIP-30 signData results and v1 bundles verify against genuine contract vectors");
