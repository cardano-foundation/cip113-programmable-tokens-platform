const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');

async function main() {
  const { confirmSavedMint, validateSavedMint, awaitCurrentMint } = await import('./.hash-build/mint-confirmation/rwa/mint-confirmation.js');
  const transactions = JSON.parse(readFileSync('./test-fixtures/real-preview-txs.json', 'utf8')).transactions;
  const hashes = transactions.map(tx => tx.txHash);
  const signed = transactions.map(tx => tx.cbor);
  const clone = value => JSON.parse(JSON.stringify(value));
  const base = () => ({ payer: 'wallet-a', chain: { mintTxHash: hashes[0], attestationTxHash: hashes[1] },
    signed: [...signed], confirmation: { phase: 'signed', submissions: 0 } });
  const observations = (...statuses) => ({ transactions: statuses.map((status, i) => ({ hash: hashes[i], status, reason: '' })) });
  const confirmed = () => observations('CONFIRMED', 'CONFIRMED');
  let id = 0;
  function harness(record = base(), reads = [confirmed()]) {
    let stored = clone(record);
    const controller = new AbortController();
    const events = [], submissions = [], messages = [];
    const ports = {
      signal: controller.signal,
      load: () => clone(stored),
      save: value => { stored = clone(value); events.push('save'); },
      clear: () => { events.push('clear'); stored = null; },
      check: async expected => {
        assert.deepEqual(expected, hashes);
        events.push('check');
        assert.ok(reads.length, 'unexpected extra status request');
        const next = reads.shift();
        if (next instanceof Error) throw next;
        return next;
      },
      submit: async bytes => {
        assert.equal(stored.confirmation.phase, 'submitting');
        assert.ok(stored.confirmation.submissions > 0, 'budget must be persisted before network write');
        events.push('submit'); submissions.push(bytes);
        return { txHashes: bytes.map(cbor => hashes[signed.indexOf(cbor)]), confirmed: false };
      },
      progress: message => messages.push(message),
      pause: async signal => { assert.equal(signal.aborted, false); },
    };
    return { ports, controller, events, submissions, messages, record: () => stored,
      run: key => confirmSavedMint(key ?? `test-${id++}`, ports) };
  }

  // Normal acceptance remains pending without another submission, even if no indexer sees it yet.
  const accepted = harness(base(), [observations('NOT_INDEXED', 'NOT_INDEXED'), observations('CONFIRMED', 'NOT_INDEXED'), confirmed()]);
  await accepted.run();
  assert.equal(accepted.submissions.length, 1);
  assert.deepEqual(accepted.events.slice(0, 3), ['save', 'submit', 'save']);
  assert.ok(accepted.messages.some(message => /Waiting/.test(message)));
  assert.equal(accepted.record(), null);

  const reloaded = base(); reloaded.confirmation = { phase: 'accepted', submissions: 1 };
  const recovery = harness(reloaded, [observations('UNKNOWN', 'NOT_INDEXED'), confirmed()]);
  await recovery.run();
  assert.equal(recovery.submissions.length, 0);

  // Missing token/quantity in legacy records does not stop exact-hash recovery.
  const legacy = base(); delete legacy.confirmation;
  const legacyDone = harness(legacy, [confirmed()]);
  await legacyDone.run();
  assert.deepEqual(legacyDone.events, ['check', 'clear']);
  const partial = harness(legacy, [observations('CONFIRMED', 'NOT_INDEXED'), confirmed()]);
  await partial.run();
  assert.deepEqual(partial.submissions, [[signed[1]]]);
  assert.equal(partial.events[0], 'check');
  const missing = harness(legacy, [observations('NOT_INDEXED', 'NOT_INDEXED'), confirmed()]);
  await missing.run();
  assert.deepEqual(missing.submissions, [signed]);
  const unknown = harness(legacy, [observations('UNKNOWN', 'NOT_INDEXED'), confirmed()]);
  await unknown.run(); assert.equal(unknown.submissions.length, 0);

  const invalid = harness(reloaded, [observations('CONFIRMED', 'INVALID')]);
  await assert.rejects(invalid.run(), /invalid on chain/);
  assert.ok(invalid.record()); assert.equal(invalid.submissions.length, 0);
  const mismatch = harness(reloaded, [{ transactions: [
    { hash: 'ff'.repeat(32), status: 'CONFIRMED' }, { hash: hashes[1], status: 'CONFIRMED' }] }]);
  await assert.rejects(mismatch.run(), /does not match/); assert.ok(mismatch.record());
  const corrupt = base(); corrupt.signed.reverse();
  const badBytes = harness(corrupt);
  await assert.rejects(badBytes.run(), /does not match/); assert.equal(badBytes.events.length, 0);
  assert.throws(() => validateSavedMint({ ...base(), signed: ['00', signed[1]] }));
  assert.throws(() => validateSavedMint({ ...base(), signed: [signed[0], signed[0]],
    chain: { mintTxHash: hashes[0], attestationTxHash: hashes[0].toUpperCase() } }), /invalid/);

  // A provider outage retries read-only. Success is determined by exact hashes.
  const outage = harness(reloaded, [new Error('503'), confirmed()]);
  await outage.run(); assert.equal(outage.submissions.length, 0);
  assert.ok(outage.messages.some(message => /temporarily unavailable/.test(message)));

  // Each uncertain network write consumes a persisted budget, then monitoring continues.
  const budget = harness(legacy, [observations('NOT_INDEXED', 'NOT_INDEXED'), observations('NOT_INDEXED', 'NOT_INDEXED'),
    observations('NOT_INDEXED', 'NOT_INDEXED'), confirmed()]);
  let calls = 0;
  budget.ports.submit = async () => { calls++; assert.equal(budget.record().confirmation.submissions, calls + 1); throw new Error('connection lost'); };
  await budget.run(); assert.equal(calls, 2);
  assert.ok(budget.messages.some(message => message.startsWith('Submission status is uncertain; checking the chain…')));
  assert.ok(budget.messages.some(message => /Last submission response:/.test(message)));

  // Reload after a crash between persistence and HTTP completion checks before replay.
  const crash = harness();
  crash.ports.submit = async () => { crash.controller.abort(); throw new Error('page closed'); };
  await assert.rejects(crash.run(), { name: 'AbortError' });
  assert.deepEqual(crash.record().confirmation, { phase: 'submitting', submissions: 1 });
  const afterCrash = harness(crash.record(), [observations('CONFIRMED', 'NOT_INDEXED'), confirmed()]);
  await afterCrash.run(); assert.equal(afterCrash.events[0], 'check');
  assert.deepEqual(afterCrash.submissions, [[signed[1]]]);

  // Cancellation after a wallet switch prevents a late chain read from clearing or reporting success.
  const switched = harness(reloaded);
  let releaseRead;
  switched.ports.check = () => new Promise(resolve => { releaseRead = resolve; });
  const switchedRun = switched.run();
  await new Promise(resolve => setImmediate(resolve));
  switched.controller.abort(); releaseRead(confirmed());
  await assert.rejects(switchedRun, { name: 'AbortError' });
  assert.ok(switched.record()); assert.equal(switched.events.includes('clear'), false);

  // React strict-mode cleanup/restart must not overlap an already in-flight submission.
  const first = harness(); let releaseSubmit;
  first.ports.submit = () => new Promise(resolve => { releaseSubmit = resolve; });
  const one = first.run('same-owner');
  await new Promise(resolve => setImmediate(resolve));
  first.controller.abort();
  const second = harness(first.record(), [confirmed()]);
  const two = second.run('same-owner');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(second.events, [], 'new owner must wait for outstanding network request');
  releaseSubmit({ txHashes: hashes, confirmed: false });
  await assert.rejects(one, { name: 'AbortError' });
  await two; assert.deepEqual(second.events, ['check', 'clear']);

  // A second tab captured the chain before waiting for the owner; the first tab
  // clears storage on completion, and the second still proves the exact hashes.
  const shared = harness(reloaded, [confirmed()]);
  let finishOwner;
  shared.ports.check = () => new Promise(resolve => { finishOwner = resolve; });
  const owner = shared.run('two-tabs');
  await new Promise(resolve => setImmediate(resolve));
  const observer = harness(reloaded, [confirmed()]);
  observer.ports.load = () => shared.record() && clone(shared.record());
  const observing = observer.run('two-tabs');
  finishOwner(confirmed());
  await owner;
  await observing;
  assert.deepEqual(observer.events, ['check']);
  assert.equal(observer.submissions.length, 0);

  // Discovery can precede the effect: another tab clears before its first load.
  const goneBeforeStart = harness(reloaded, [observations('UNKNOWN', 'NOT_INDEXED'), confirmed()]);
  goneBeforeStart.ports.knownRecord = clone(reloaded);
  goneBeforeStart.ports.load = () => null;
  await goneBeforeStart.run();
  assert.deepEqual(goneBeforeStart.events, ['check', 'check']);
  assert.equal(goneBeforeStart.submissions.length, 0);
  const invalidSnapshot = harness(reloaded);
  invalidSnapshot.ports.load = () => null;
  invalidSnapshot.ports.knownRecord = { ...clone(reloaded), signed: [...signed].reverse() };
  await assert.rejects(invalidSnapshot.run(), /does not match/);
  assert.equal(invalidSnapshot.events.length, 0);

  // Wallet/form changes while Veridian is waiting cannot reach the next build.
  for (const obsoleteAt of ['prepare', 'anchor']) {
    let current = true, releaseStep, builds = 0;
    const steps = async () => {
      await awaitCurrentMint(() => current, () => obsoleteAt === 'prepare'
        ? new Promise(resolve => { releaseStep = resolve; }) : Promise.resolve());
      await awaitCurrentMint(() => current, () => obsoleteAt === 'anchor'
        ? new Promise(resolve => { releaseStep = resolve; }) : Promise.resolve());
      await awaitCurrentMint(() => current, async () => { builds++; });
    };
    const attempt = steps();
    await new Promise(resolve => setImmediate(resolve));
    current = false; releaseStep();
    await assert.rejects(attempt, { name: 'AbortError' });
    assert.equal(builds, 0);
  }

  const unexpected = harness();
  unexpected.ports.submit = async () => ({ txHashes: ['ff'.repeat(32)] });
  await assert.rejects(unexpected.run(), /unexpected transaction hashes/);
  assert.equal(unexpected.record().confirmation.phase, 'conflict');
  const conflictReload = harness(unexpected.record());
  await assert.rejects(conflictReload.run(), /unexpected transaction hashes/);
  assert.equal(conflictReload.events.length, 0);
  console.log('PASS admin mint orchestrator: pending, exact confirmation, legacy/reload, partial replay, invalid, network outage, persisted budget, crash, wallet abort, ownership, and hash mismatch');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
