const assert = require('node:assert/strict');
const { initialMintRecoveryKeys, mintRecoveryStorage, scanInitialMintAttempts,
  scanWalletInitialMintAttempts, admitInitialMintAttempt, removeInitialMintAttempt,
  saveInitialMintAttempt } = require('./.mint-recovery-build/mint-recovery-storage.js');
const { clearRegistrationCip170Storage } = require('./.mint-recovery-build/mint-recovery-storage.js');

function area() {
  const values = new Map();
  return {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
    key: index => [...values.keys()][index] ?? null,
    get length() { return values.size; },
  };
}
function reset() { clearRegistrationCip170Storage(); global.window = { localStorage: area(), sessionStorage: area() }; }
function webLocks() {
  let tail = Promise.resolve();
  Object.defineProperty(global, 'navigator', { configurable: true, value: { locks: { request: (_name, _options, callback) => {
    const next = tail.then(callback);
    tail = next.catch(() => undefined);
    return next;
  } } } });
}
function registration(payer) {
  return JSON.stringify({ feePayerAddress: payer, assetName: '', adminPubKeyHash: 'abc',
    requiresReceiverKyc: false, initialMintQuantity: '7' });
}
function save(payer, id) {
  const keys = initialMintRecoveryKeys(payer);
  mintRecoveryStorage.setItem(keys.intent, id);
  mintRecoveryStorage.setItem(keys.registration, registration(payer));
  mintRecoveryStorage.setItem(keys.session, `session-${id}`);
}
const normalize = address => address;

(async () => {
  reset();
  save('aa', 'old');
  const reordered = await scanWalletInitialMintAttempts({
    getUsedAddresses: async () => ['bb', 'aa'], getChangeAddress: async () => 'cc',
  }, normalize);
  assert.equal(reordered.payerAddress, 'bb');
  assert.deepEqual(reordered.attempts.map(a => a.intentId), ['old']);

  reset();
  save('cc', 'change');
  assert.deepEqual(scanInitialMintAttempts(['aa', 'aa', 'cc', 'cc'], normalize)
    .attempts.map(a => a.intentId), ['change']);

  reset();
  save('aa', 'first'); save('bb', 'second');
  assert.equal(scanInitialMintAttempts(['bb', 'aa'], normalize).attempts.length, 2);
  mintRecoveryStorage.removeItem(initialMintRecoveryKeys('bb').intent);
  mintRecoveryStorage.removeItem(initialMintRecoveryKeys('bb').registration);
  assert.deepEqual(scanInitialMintAttempts(['bb', 'aa'], normalize).attempts.map(a => a.intentId), ['second', 'first']);
  removeInitialMintAttempt('second');
  assert.deepEqual(scanInitialMintAttempts(['bb', 'aa'], normalize).attempts.map(a => a.intentId), ['first']);

  reset();
  mintRecoveryStorage.setItem(initialMintRecoveryKeys('aa').intent, 'incomplete');
  assert.throws(() => scanInitialMintAttempts(['aa'], normalize), /Incomplete saved/);

  reset();
  save('aa', 'scoped');
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-intent', 'legacy');
  mintRecoveryStorage.setItem('rwa-initial-mint-frozen-registration', registration('aa'));
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-session', 'session-legacy');
  assert.throws(() => scanInitialMintAttempts(['aa'], normalize), /conflict/);
  assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), 'legacy');
  assert.equal(mintRecoveryStorage.getItem(initialMintRecoveryKeys('aa').intent), 'scoped');

  reset();
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-intent', 'legacy');
  mintRecoveryStorage.setItem('rwa-initial-mint-frozen-registration', registration('aa'));
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-session', 'session-legacy');
  mintRecoveryStorage.setItem(initialMintRecoveryKeys('aa').registration, registration('aa'));
  assert.throws(() => scanInitialMintAttempts(['aa'], normalize), /conflict/);
  assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), 'legacy');

  reset();
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-intent', 'legacy');
  mintRecoveryStorage.setItem('rwa-initial-mint-frozen-registration', registration('aa'));
  mintRecoveryStorage.setItem('rwa-initial-mint-keri-session', 'session-legacy');
  assert.equal(scanInitialMintAttempts(['aa'], normalize).attempts[0].intentId, 'legacy');
  assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent'), null);

  reset();
  await assert.rejects(scanWalletInitialMintAttempts({
    getUsedAddresses: async () => { throw Error('wallet unavailable'); },
    getChangeAddress: async () => 'aa',
  }, normalize), /wallet unavailable/);
  await assert.rejects(scanWalletInitialMintAttempts({
    getUsedAddresses: async () => ['aa'],
    getChangeAddress: async () => { throw Error('change unavailable'); },
  }, normalize), /change unavailable/);
  reset(); webLocks();
  const wallet = { getUsedAddresses: async () => ['aa'], getChangeAddress: async () => 'bb' };
  const record = id => ({ intentId: id, sessionId: `session-${id}`, payerHex: 'aa',
    payerAddress: 'aa', registration: JSON.parse(registration('aa')) });
  const results = await Promise.allSettled([
    admitInitialMintAttempt(wallet, normalize, record('one')),
    admitInitialMintAttempt(wallet, normalize, record('two')),
  ]);
  assert.deepEqual(results.map(result => result.status), ['fulfilled', 'rejected']);
  assert.deepEqual(scanInitialMintAttempts(['aa', 'bb'], normalize).attempts.map(a => a.intentId), ['one']);
  removeInitialMintAttempt('one');
  assert.equal(scanInitialMintAttempts(['aa'], normalize).attempts.length, 0);
  saveInitialMintAttempt(record('one'));
  saveInitialMintAttempt(record('two'));
  mintRecoveryStorage.setItem(initialMintRecoveryKeys('aa').intent, 'two');
  mintRecoveryStorage.setItem(initialMintRecoveryKeys('aa').registration, registration('aa'));
  mintRecoveryStorage.setItem(initialMintRecoveryKeys('aa').session, 'session-two');
  removeInitialMintAttempt('one');
  assert.equal(mintRecoveryStorage.getItem(initialMintRecoveryKeys('aa').intent), 'two');
  assert.deepEqual(scanInitialMintAttempts(['aa'], normalize).attempts.map(a => a.intentId), ['two']);
  removeInitialMintAttempt('two');
  reset(); webLocks();
  Object.defineProperty(global.window.localStorage, 'setItem', { value: () => { throw Error('storage unavailable'); } });
  await admitInitialMintAttempt(wallet, normalize, record('no-write'));
  assert.deepEqual(scanInitialMintAttempts(['aa'], normalize).attempts.map(a => a.intentId), ['no-write']);
  delete global.navigator;
  reset();
  await assert.rejects(admitInitialMintAttempt(wallet, normalize, record('unsupported')), /Web Locks/);
  assert.equal(scanInitialMintAttempts(['aa'], normalize).attempts.length, 0);
  console.log('OK recovery scans all wallet addresses and preserves conflicting attempts');
})().catch(error => { console.error(error); process.exitCode = 1; });
