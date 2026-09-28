const assert = require("node:assert/strict");
const { computeMemberRoot } = require("./.root-build/rwa/member-root.js");

// Fixture roots are also asserted by RwaTokenMemberRootFixtureTest against the
// Java MPF library used by the backend, so either implementation drifting fails.
const policy = "ab".repeat(28);
const one = { credentialHash: "01".repeat(28), credentialType: 0, validUntilMs: 2_000_000_000_000 };
const two = { credentialHash: "02".repeat(28), credentialType: 1, validUntilMs: 2_100_000_000_000 };
assert.equal(computeMemberRoot([], policy, 0), "");
assert.equal(computeMemberRoot([one], policy, 0), "66e400435a9ab50d1ed01651d7e89afaaa7df57acfd371c87fc9e193fd4a8f5b");
assert.equal(computeMemberRoot([one, two], policy, 0), "c66882a78d90d382f9691f1b62d2140f9bf2c2a2b5472a90ce591d34bfa170a2");
assert.equal(computeMemberRoot([two, one], policy, 0), computeMemberRoot([one, two], policy, 0));
assert.notEqual(computeMemberRoot([{ ...one, validUntilMs: one.validUntilMs + 1 }], policy, 0), computeMemberRoot([one], policy, 0));
assert.notEqual(computeMemberRoot([{ ...one, credentialType: 1 }], policy, 0), computeMemberRoot([one], policy, 0));
assert.throws(() => computeMemberRoot([one, one], policy, 0), /Duplicate/);
console.log("CMTA member roots match Java MPF fixtures and reject duplicate credentials");
