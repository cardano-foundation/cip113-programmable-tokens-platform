const assert = require("node:assert/strict");
const { Address, RewardAccount } = require("@evolution-sdk/evolution");
const { bech32 } = require("@scure/base");
const { resolveStakeMemberAddress } = require("./.stake-build/rwa/stake-address.js");

const hash = "11".repeat(28);
const address = (header) => RewardAccount.toBech32(RewardAccount.fromHex(header + hash));

assert.deepEqual(resolveStakeMemberAddress(address("e0"), "preview"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(address("f0"), "preprod"),
  { credentialHash: hash, credentialType: 1 });
assert.deepEqual(resolveStakeMemberAddress(address("e1"), "mainnet"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(address("f1"), "mainnet"),
  { credentialHash: hash, credentialType: 1 });

assert.throws(() => resolveStakeMemberAddress(address("e0"), "mainnet"), /wrong network/);
assert.throws(() => resolveStakeMemberAddress(address("e1"), "preview"), /wrong network/);
assert.throws(() => resolveStakeMemberAddress(hash, "preview"), /stake1/);
const payment = Address.toBech32(Address.fromHex("00" + "22".repeat(28) + hash));
assert.throws(() => resolveStakeMemberAddress(payment, "preview"), /stake1/);
const valid = address("e0");
assert.throws(() => resolveStakeMemberAddress(valid.slice(0, -1) + (valid.endsWith("q") ? "p" : "q"), "preview"),
  /checksum/);
const wrongHeader = bech32.encode("stake_test", bech32.toWords(Uint8Array.from(
  Buffer.from("60" + hash, "hex"))));
assert.throws(() => resolveStakeMemberAddress(wrongHeader, "preview"), /type/);

console.log("CMTA stake addresses resolve to the intended key or script credentials");
