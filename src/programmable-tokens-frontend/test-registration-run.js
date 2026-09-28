const assert = require('node:assert/strict');
const { currentRegistrationRun, resetRegistrationRun, assertRegistrationRun } =
  require('./.mint-recovery-build/registration-run.js');

const first = currentRegistrationRun();
assert.doesNotThrow(() => assertRegistrationRun(first));
resetRegistrationRun();
assert.throws(() => assertRegistrationRun(first), /registration was reset/);
const second = currentRegistrationRun();
assert.doesNotThrow(() => assertRegistrationRun(second));
assert.notEqual(second, first);
console.log('OK reset fences callbacks from the previous registration run');
