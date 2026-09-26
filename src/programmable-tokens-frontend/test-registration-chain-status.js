const assert = require('node:assert/strict');
const { validateChainObservations, allChainTransactionsConfirmed, canResumeRegistrationChain } =
  require('./.mint-recovery-build/registration-chain-status.js');

const hashes = ['11'.repeat(32), '22'.repeat(32), '33'.repeat(32), '44'.repeat(32)];
const statuses = (...values) => values.map((status, index) =>
  ({ hash: hashes[index], status, reason: 'test' }));

const pending = statuses('NOT_INDEXED', 'NOT_INDEXED', 'NOT_INDEXED', 'NOT_INDEXED');
assert.deepEqual(validateChainObservations(hashes, pending), pending);
assert.equal(allChainTransactionsConfirmed(pending), false);
assert.equal(canResumeRegistrationChain('accepted', 4, pending), false);
assert.equal(canResumeRegistrationChain('lost', 0, pending), false);
assert.equal(canResumeRegistrationChain('conflict', 0, pending), false);

const incompleteAcceptedPrefix = statuses('CONFIRMED', 'NOT_INDEXED', 'NOT_INDEXED', 'NOT_INDEXED');
assert.equal(canResumeRegistrationChain('partial', 2, incompleteAcceptedPrefix), false);
assert.equal(canResumeRegistrationChain('lost', 0, incompleteAcceptedPrefix), true);
const confirmedAcceptedPrefix = statuses('CONFIRMED', 'CONFIRMED', 'NOT_INDEXED', 'NOT_INDEXED');
assert.equal(canResumeRegistrationChain('partial', 2, confirmedAcceptedPrefix), true);
assert.equal(canResumeRegistrationChain('partial', 2,
  statuses('CONFIRMED', 'CONFIRMED', 'UNKNOWN', 'NOT_INDEXED')), false);
assert.equal(canResumeRegistrationChain('partial', 2,
  statuses('CONFIRMED', 'CONFIRMED', 'INVALID', 'NOT_INDEXED')), false);
assert.equal(allChainTransactionsConfirmed(statuses('CONFIRMED', 'CONFIRMED', 'CONFIRMED', 'CONFIRMED')), true);
assert.throws(() => validateChainObservations(hashes, statuses('CONFIRMED')), /does not match/);
assert.throws(() => validateChainObservations(hashes,
  [{ hash: 'ff'.repeat(32), status: 'CONFIRMED', reason: 'test' }, ...pending.slice(1)]), /does not match/);
console.log('OK exact confirmation and guarded registration-chain replay decisions');
