const assert = require('node:assert/strict');
const { parseInitialMintCap } = require('./.mint-recovery-build/initial-mint-cap.js');

assert.equal(parseInitialMintCap('0'), 0);
assert.equal(parseInitialMintCap('00013'), 13);
assert.equal(parseInitialMintCap('9007199254740991'), Number.MAX_SAFE_INTEGER);
for (const value of ['', '-1', '10.5', '1e3', '9007199254740992']) {
  assert.equal(parseInitialMintCap(value), null, `must reject ${JSON.stringify(value)}`);
}
console.log('OK initial mint cap preserves the exact whole number across the JavaScript API');
