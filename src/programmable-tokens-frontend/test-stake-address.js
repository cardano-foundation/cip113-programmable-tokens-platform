const assert = require("node:assert/strict");
const { Address, RewardAccount } = require("@evolution-sdk/evolution");
const { bech32 } = require("@scure/base");
const { resolveStakeMemberAddress } = require("./.stake-build/rwa/stake-address.js");

const hash = "11".repeat(28);
const address = (header) => RewardAccount.toBech32(RewardAccount.fromHex(header + hash));
const payment = (header, stakeHash = hash) => Address.toBech32(Address.fromHex(
  header + "22".repeat(28) + (header[0] === "6" || header[0] === "7" ? "" : stakeHash)));

assert.deepEqual(resolveStakeMemberAddress(address("e0"), "preview"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(address("f0"), "preprod"),
  { credentialHash: hash, credentialType: 1 });
assert.deepEqual(resolveStakeMemberAddress(address("e1"), "mainnet"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(address("f1"), "mainnet"),
  { credentialHash: hash, credentialType: 1 });
assert.deepEqual(resolveStakeMemberAddress(payment("00"), "preview"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(payment("20"), "preprod"),
  { credentialHash: hash, credentialType: 1 });
assert.deepEqual(resolveStakeMemberAddress(payment("10"), "preview"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(payment("11"), "mainnet"),
  { credentialHash: hash, credentialType: 0 });
assert.deepEqual(resolveStakeMemberAddress(payment("31"), "mainnet"),
  { credentialHash: hash, credentialType: 1 });

assert.throws(() => resolveStakeMemberAddress(address("e0"), "mainnet"), /wrong network/);
assert.throws(() => resolveStakeMemberAddress(address("e1"), "preview"), /wrong network/);
assert.throws(() => resolveStakeMemberAddress(payment("01"), "preview"), /wrong network/);
assert.throws(() => resolveStakeMemberAddress(hash, "preview"), /stake1/);
assert.throws(() => resolveStakeMemberAddress(payment("60"), "preview"), /Enterprise addresses have no stake credential/);
assert.throws(() => resolveStakeMemberAddress(payment("71"), "mainnet"), /Enterprise addresses have no stake credential/);
const valid = address("e0");
assert.throws(() => resolveStakeMemberAddress(valid.slice(0, -1) + (valid.endsWith("q") ? "p" : "q"), "preview"),
  /checksum/);
const validPayment = payment("00");
assert.throws(() => resolveStakeMemberAddress(validPayment.slice(0, -1) +
  (validPayment.endsWith("q") ? "p" : "q"), "preview"), /checksum/);
const wrongHeader = bech32.encode("stake_test", bech32.toWords(Uint8Array.from(
  Buffer.from("60" + hash, "hex"))));
assert.throws(() => resolveStakeMemberAddress(wrongHeader, "preview"), /type/);

console.log("CMTA stake and base addresses resolve to the intended key or script credentials");
