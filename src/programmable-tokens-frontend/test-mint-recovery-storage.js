const assert = require('node:assert/strict');
const { mintRecoveryStorage, initialMintRecoveryKeys, clearRegistrationCip170Storage } = require('./.mint-recovery-build/mint-recovery-storage.js');

function area() {
  const entries = new Map();
  return {
    getItem: key => entries.has(key) ? entries.get(key) : null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key),
    key: index => [...entries.keys()][index] ?? null,
    get length() { return entries.size; },
  };
}

const localStorage = area();
const sessionStorage = area();
global.window = { localStorage, sessionStorage };

sessionStorage.setItem('rwa-initial-mint-keri-intent', 'old-intent');
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), null);

const walletA = initialMintRecoveryKeys('aa');
const walletB = initialMintRecoveryKeys('bb');
mintRecoveryStorage.setItem(walletA.intent, 'attempt-a');
mintRecoveryStorage.setItem(walletB.intent, 'attempt-b');
assert.equal(mintRecoveryStorage.getItem(walletA.intent), 'attempt-a');
assert.equal(mintRecoveryStorage.getItem(walletB.intent), 'attempt-b');
assert.equal(localStorage.getItem(walletA.intent), null);

mintRecoveryStorage.setItem('rwa-initial-mint-submitting-old-intent-signed', '["signed"]');
assert.equal(sessionStorage.getItem('rwa-initial-mint-submitting-old-intent-signed'), null);
// Registration state is volatile; admin mint recovery still persists.
global.window.sessionStorage = area();
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-submitting-old-intent-signed'), '["signed"]');
assert.equal(localStorage.getItem('rwa-initial-mint-submitting-old-intent-signed'), null);
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), null);

mintRecoveryStorage.setItem('mint-keri-test', 'admin-recovery');
localStorage.setItem(walletA.intent, 'legacy');
global.window.sessionStorage.setItem('register-cip170-session-id', 'legacy-session');
clearRegistrationCip170Storage();
assert.equal(mintRecoveryStorage.getItem(walletA.intent), null);
assert.equal(mintRecoveryStorage.getItem(walletB.intent), null);
assert.equal(localStorage.getItem(walletA.intent), null);
assert.equal(global.window.sessionStorage.getItem('register-cip170-session-id'), null);
assert.equal(mintRecoveryStorage.getItem('mint-keri-test'), 'admin-recovery');

mintRecoveryStorage.removeItem('rwa-initial-mint-submitting-old-intent-signed');
assert.equal(localStorage.getItem('rwa-initial-mint-submitting-old-intent-signed'), null);
delete global.window;
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), null);
assert.throws(() => mintRecoveryStorage.setItem('unsafe', 'value'), /only available in the browser/);
console.log('OK registration CIP-170 state is volatile and reset preserves admin mint recovery');
