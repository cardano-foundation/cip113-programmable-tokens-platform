const assert = require("node:assert/strict");
const {
  parseMintableAmountInput,
  setMintableAmountChange,
} = require("./.mintable-build/mintable-amount.js");

let checks = 0;
const check = (fn) => { fn(); checks++; };

// Accepted: zero, ordinary values, surrounding whitespace, the largest exact JS integer.
check(() => assert.deepEqual(parseMintableAmountInput("0"), { ok: true, value: 0 }));
check(() => assert.deepEqual(parseMintableAmountInput(" 1000000 "), { ok: true, value: 1_000_000 }));
check(() => assert.deepEqual(parseMintableAmountInput(String(Number.MAX_SAFE_INTEGER)),
  { ok: true, value: Number.MAX_SAFE_INTEGER }));

// Rejected: the on-chain `>= 0` rule, and anything that is not a plain whole number.
for (const bad of ["", "  ", "-1", "-0", "1.5", "1e6", "1_000", "1,000", "+5", "0x10", "abc"]) {
  check(() => assert.equal(parseMintableAmountInput(bad).ok, false, `accepted ${JSON.stringify(bad)}`));
}

// Rejected rather than rounded: one past MAX_SAFE_INTEGER would reach the backend
// as a different number than the admin typed.
check(() => assert.match(
  parseMintableAmountInput((BigInt(Number.MAX_SAFE_INTEGER) + 1n).toString()).error,
  /at most/));

// Change staging: only a valid, different value produces a SetMintableAmount spec.
check(() => assert.deepEqual(setMintableAmountChange("500", 1000), {
  spec: { action: "SetMintableAmount", newMintableAmount: 500 },
  label: "Set mintable amount to 500",
}));
check(() => assert.deepEqual(setMintableAmountChange("0", 7).spec,
  { action: "SetMintableAmount", newMintableAmount: 0 }));
check(() => assert.equal(setMintableAmountChange("1000", 1000), null));
check(() => assert.equal(setMintableAmountChange(" 1000 ", 1000), null));
check(() => assert.equal(setMintableAmountChange("-5", 1000), null));

console.log(`${checks} checks passed — SetMintableAmount input is a whole number in [0, MAX_SAFE_INTEGER]`);
