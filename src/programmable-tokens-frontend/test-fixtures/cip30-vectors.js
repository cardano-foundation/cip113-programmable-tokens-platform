// GENUINE CIP-30 vectors from the pinned CMTA contract (cpt-rwa-ch-cmta-reference @346f9bf,
// lib/kyc/verify.ak: cip30_unit_valid, cip30_unit_hashed_valid). Public TEST seed only — no
// operator key material. The hashed vector is independently confirmed upstream against
// Emurgo's cardano-message-signing 1.1.0.
const { blake2b } = require("@noble/hashes/blake2");

const protectedHeader = "a201276761646472657373581d6027e38d0e19e3434e33fbd001d3fe04b5b76763f88acd625e0d770b43";
const claim = "aa".repeat(28) + "01" + "0000000005f5e100" + "bb".repeat(28) + "01" + "00";
const unhashedSig = "b98e5f45c6b54500cf64d816928f8bd81ec095d85c12e642c4204325df16f069"
  + "43e6d8a1ae82796f4dd91fe71caa31e7e2cf5356b7a96d7ba1a1bd10482b2b06";
const hashedSig = "2a51077b605a1e9610a3aede92105263bb225d843a584d5a14412c234c273f30"
  + "97fecc734ee44150c688bf59fc0a35ccd4c42de2e140a12cda572475e5faf60a";
const vkey = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8";
const digest = Buffer.from(blake2b(Buffer.from(claim, "hex"), { dkLen: 28 })).toString("hex");

const bstr = (hex) => {
  const n = hex.length / 2;
  const head = n < 24 ? (0x40 | n).toString(16).padStart(2, "0")
    : n < 256 ? "58" + n.toString(16).padStart(2, "0")
    : "59" + n.toString(16).padStart(4, "0");
  return head + hex;
};

/** COSE_Sign1 = [protected, unprotected, payload, signature]; unprotected {"hashed": true} when hashed. */
const sign1 = (payloadHex, hashed, sig) => "84" + bstr(protectedHeader)
  + (hashed ? "a166686173686564f5" : "a0") + bstr(payloadHex)
  + bstr(sig ?? (hashed ? hashedSig : unhashedSig));

/** COSE_Key {1: 1 OKP, 3: -8 EdDSA, -1: 6 Ed25519, -2: x}. */
const key = "a4010103272006215820" + vkey;

module.exports.coseVectors = {
  protectedHeader, claim, digest, unhashedSig, hashedSig, vkey, key, sign1, policy: "bb".repeat(28),
};
