#!/usr/bin/env node
// Generates genuine Ed25519 test vectors for the KYC-proof positive tests in
// `lib/kyc/verify.ak` and `validators/transfer_logic_script.ak`.
//
// Node >= 18, zero dependencies (uses only `node:crypto`).
//
// Re-run with:
//
//     node scripts/kyc-test-vectors.mjs
//
// The seed below is FIXED, so this always derives the same key pair and
// prints the same vectors — it is dev tooling to keep the on-chain test
// fixtures honest, not a secret. The printed public key is pasted verbatim
// into `test_issuer_vkey` in `lib/kyc/verify.ak`; the printed payload/
// signature pairs are pasted into the const fixtures that back the new
// `*_succeeds_with_a_genuine_*` tests in both files.
//
// ── Payload layout (67 bytes, big-endian where applicable) ─────────────────
// Authoritative definition: `lib/types/kyc_proof.ak`.
//
//   bytes  0..27  user_pkh            (28)
//   byte      28  user_kyc_tier       (1)
//   bytes 29..36  valid_until_ms      (8, big-endian)
//   bytes 37..64  security_policy_id  (28)
//   byte      65  network_id          (1)
//   byte      66  credential_type     (1)  0x00 VerificationKey, 0x01 Script
//
// The 64-byte signature is `crypto.sign(null, payload, key)` — Ed25519's
// "PureEdDSA" mode, matching `aiken/crypto.verify_ed25519_signature`.

import { createPrivateKey, createPublicKey, sign } from "node:crypto";

// ── Fixed 32-byte seed: bytes 0x00..0x1f. Deterministic and reproducible —
// not derived from anything secret. ─────────────────────────────────────────
const SEED = Buffer.from(Array.from({ length: 32 }, (_, i) => i));

// PKCS#8 DER wrapper for a raw Ed25519 private key seed:
//   30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20 <32-byte seed>
const PKCS8_PREFIX_HEX = "302e020100300506032b657004220420";

function loadKey(seed) {
  const der = Buffer.concat([Buffer.from(PKCS8_PREFIX_HEX, "hex"), seed]);
  return createPrivateKey({ key: der, format: "der", type: "pkcs8" });
}

function rawPublicKey(privateKey) {
  const spki = createPublicKey(privateKey).export({
    format: "der",
    type: "spki",
  });
  // SPKI DER for Ed25519 is a fixed 12-byte prefix followed by the raw
  // 32-byte public key.
  return spki.subarray(spki.length - 32);
}

// ── Payload builder ──────────────────────────────────────────────────────

function beBytes(n, width) {
  const buf = Buffer.alloc(width);
  let v = BigInt(n);
  for (let i = width - 1; i >= 0; i -= 1) {
    buf[i] = Number(v & 0xffn);
    v >>= 8n;
  }
  return buf;
}

function buildPayload({
  userPkhHex,
  tier,
  validUntilMs,
  securityPolicyIdHex,
  networkId,
  credentialType,
}) {
  const payload = Buffer.concat([
    Buffer.from(userPkhHex, "hex"),
    beBytes(tier, 1),
    beBytes(validUntilMs, 8),
    Buffer.from(securityPolicyIdHex, "hex"),
    beBytes(networkId, 1),
    beBytes(credentialType, 1),
  ]);
  if (payload.length !== 67) {
    throw new Error(`payload must be 67 bytes, got ${payload.length}`);
  }
  return payload;
}

// ── Vector inputs — every value copied verbatim from the const fixtures in
// the two test files (never guessed). ───────────────────────────────────────

// lib/kyc/verify.ak
const TEST_USER_PKH =
  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const TEST_SECURITY_POLICY_ID =
  "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
const TEST_TTL_MS = 100_000_000;
const TEST_NETWORK_ID = 1;

// validators/transfer_logic_script.ak
const T_SENDER_STAKE =
  "0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a";
const T_DEST_STAKE =
  "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b";
const T_ISSUANCE_POLICY =
  "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
// t_gs_datum's network_id (validators/transfer_logic_script.ak, t_gs_datum).
const T_NETWORK_ID = 1;
// t_transfer_tx's validity_range upper bound is PositiveInfinity, so the
// positive-control tests give the transaction a Finite upper bound of its
// own (see t_tx_upper below) and the TTL here must exceed it.
const T_TX_UPPER = 50_000_000;
const T_KYC_TTL_MS = 100_000_000;
// Both t_sender_input and t_token_utxo build the stake credential as
// `VerificationKey(stake_hash)`, so both sender and destination vectors use
// credential_type 0x00.

const VECTORS = [
  {
    name: "verify.ak — vector (a): VerificationKey form",
    userPkhHex: TEST_USER_PKH,
    tier: 0x01,
    validUntilMs: TEST_TTL_MS,
    securityPolicyIdHex: TEST_SECURITY_POLICY_ID,
    networkId: TEST_NETWORK_ID,
    credentialType: 0x00,
  },
  {
    name: "verify.ak — vector (b): Script form",
    userPkhHex: TEST_USER_PKH,
    tier: 0x01,
    validUntilMs: TEST_TTL_MS,
    securityPolicyIdHex: TEST_SECURITY_POLICY_ID,
    networkId: TEST_NETWORK_ID,
    credentialType: 0x01,
  },
  {
    name: "transfer_logic_script.ak — vector (c): sender (t_sender_stake)",
    userPkhHex: T_SENDER_STAKE,
    tier: 0x01,
    validUntilMs: T_KYC_TTL_MS,
    securityPolicyIdHex: T_ISSUANCE_POLICY,
    networkId: T_NETWORK_ID,
    credentialType: 0x00,
  },
  {
    name: "transfer_logic_script.ak — vector (d): destination (t_dest_stake)",
    userPkhHex: T_DEST_STAKE,
    tier: 0x01,
    validUntilMs: T_KYC_TTL_MS,
    securityPolicyIdHex: T_ISSUANCE_POLICY,
    networkId: T_NETWORK_ID,
    credentialType: 0x00,
  },
];

function main() {
  const key = loadKey(SEED);
  const pubKey = rawPublicKey(key);

  console.log("seed (hex):       " + SEED.toString("hex"));
  console.log("issuer_vkey (hex): " + pubKey.toString("hex"));
  console.log("");

  for (const vector of VECTORS) {
    const payload = buildPayload(vector);
    const signature = sign(null, payload, key);
    console.log(vector.name);
    console.log("  payload (67B):   " + payload.toString("hex"));
    console.log("  signature (64B): " + signature.toString("hex"));
    console.log("");
  }
}

main();
