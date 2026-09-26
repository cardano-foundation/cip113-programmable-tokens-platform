const assert = require('node:assert/strict');
const { initialMintRecoveryKeys, mintRecoveryStorage } = require('./.mint-recovery-build/mint-recovery-storage.js');
const { readInitialMintDraft, writeInitialMintDraft, canBuildInitialRegistration,
  initialRegistrationAction, finishArchivedInitialMint } = require('./.mint-recovery-build/initial-mint-draft.js');

function area() {
  const entries = new Map();
  return {
    getItem: key => entries.get(key) ?? null,
    setItem: (key, value) => entries.set(key, value),
    removeItem: key => entries.delete(key),
  };
}
global.window = { localStorage: area(), sessionStorage: area() };

const alice = initialMintRecoveryKeys('aa');
const bob = initialMintRecoveryKeys('bb');
const savedRegistration = '{"initialMintQuantity":"11","initialMintableAmount":13}';
const savedSignedChain = '["signed-genesis","signed-registration"]';
mintRecoveryStorage.setItem(alice.intent, 'built-intent');
mintRecoveryStorage.setItem(alice.registration, savedRegistration);
mintRecoveryStorage.setItem('rwa-initial-mint-submitting-built-intent-signed', savedSignedChain);

const draft = {
  mintableAmount: '21', securityInfo: '', requiresReceiverKyc: false,
  seedRecipientInAllowlist: false, trustedEntities: ['a'.repeat(64)], approveInitialMint: false,
};
writeInitialMintDraft('aa', draft);
assert.deepEqual(readInitialMintDraft('aa'), draft);
assert.equal(readInitialMintDraft('bb'), null);
assert.equal(mintRecoveryStorage.getItem(alice.registration), savedRegistration);
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-submitting-built-intent-signed'), savedSignedChain);
assert.equal(canBuildInitialRegistration(true, true, null, true), false);
assert.equal(canBuildInitialRegistration(true, true, 'recovery failed', false), false);
assert.equal(canBuildInitialRegistration(true, false, null, false), false);
assert.equal(canBuildInitialRegistration(true, true, null, false), true);
assert.equal(initialRegistrationAction(false, false, false), 'build-new');
assert.equal(initialRegistrationAction(true, false, false), 'continue-approval');
assert.equal(initialRegistrationAction(true, false, true), 'submit-current');
assert.equal(initialRegistrationAction(true, true, false), 'resolve-previous');
assert.equal(initialRegistrationAction(true, true, true), 'resolve-previous');
assert.equal(initialRegistrationAction(false, true, true), 'resolve-previous');

writeInitialMintDraft('bb', { ...draft, mintableAmount: '100' });
assert.equal(readInitialMintDraft('aa').mintableAmount, '21');
assert.equal(readInitialMintDraft('bb').mintableAmount, '100');
assert.notEqual(alice.draft, bob.draft);

for (const status of ['UNKNOWN', 'PARTIAL', 'EXPIRED_UNSTARTED']) {
  assert.throws(() => finishArchivedInitialMint('aa', status, false, 'built-intent'));
  assert.equal(mintRecoveryStorage.getItem(alice.intent), 'built-intent');
}
assert.throws(() => finishArchivedInitialMint('aa', 'ARCHIVED_EXPIRED', false, 'built-intent'));
assert.equal(mintRecoveryStorage.getItem(alice.intent), 'built-intent');
finishArchivedInitialMint('aa', 'ARCHIVED_EXPIRED', true, 'different-intent');
assert.equal(mintRecoveryStorage.getItem(alice.intent), 'built-intent');
finishArchivedInitialMint('aa', 'ARCHIVED_EXPIRED', true, 'built-intent');
assert.equal(mintRecoveryStorage.getItem(alice.intent), null);
assert.equal(mintRecoveryStorage.getItem(alice.registration), null);
assert.deepEqual(readInitialMintDraft('aa'), draft);
assert.equal(mintRecoveryStorage.getItem('rwa-initial-mint-submitting-built-intent-signed'), savedSignedChain);
console.log('OK future draft stays wallet-scoped and cannot replace an unresolved built chain');
