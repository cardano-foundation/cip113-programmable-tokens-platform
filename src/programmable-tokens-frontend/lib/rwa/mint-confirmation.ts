import { transactionHash } from '../tx/hash';

export interface SavedMintChain {
  payer: string;
  chain: { mintTxHash: string; attestationTxHash: string };
  signed: string[];
  quantity?: string;
  assetName?: string;
  confirmation?: {
    phase: 'signed' | 'submitting' | 'accepted' | 'uncertain' | 'conflict';
    submissions: number;
    lastError?: string;
  };
}

type Observation = { hash: string; status: 'CONFIRMED' | 'INVALID' | 'NOT_INDEXED' | 'UNKNOWN'; reason: string };
type Submission = { txHashes: string[]; confirmed?: boolean; error?: string };
export interface MintConfirmationPorts {
  /** Capture during discovery so another tab may clear storage before this run starts. */
  knownRecord?: SavedMintChain;
  load(): SavedMintChain | null;
  save(record: SavedMintChain): void;
  clear(record: SavedMintChain): void;
  check(hashes: string[]): Promise<{ transactions: Observation[] }>;
  submit(signed: string[]): Promise<Submission>;
  submissionFailure?(error: unknown): Submission;
  progress(message: string, hashes: string[]): void;
  signal: AbortSignal;
  pause?: (signal: AbortSignal) => Promise<void>;
}

const MAX_SUBMISSIONS = 3;
const owners = new Map<string, Promise<unknown>>();

function active(signal: AbortSignal): void {
  if (signal.aborted) throw new DOMException('Mint confirmation stopped', 'AbortError');
}

/** Stop an obsolete wallet/form attempt before the next signing or network step. */
export async function awaitCurrentMint<T>(isCurrent: () => boolean, action: () => Promise<T>): Promise<T> {
  if (!isCurrent()) throw new DOMException('Mint attempt changed', 'AbortError');
  const result = await action();
  if (!isCurrent()) throw new DOMException('Mint attempt changed', 'AbortError');
  return result;
}

export function waitForMintPoll(signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    active(signal);
    const aborted = () => { clearTimeout(timer); reject(new DOMException('Mint confirmation stopped', 'AbortError')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', aborted); resolve(); }, 5_000);
    signal.addEventListener('abort', aborted, { once: true });
  });
}

/** Check the saved bytes before they can cross a submission boundary. */
export function validateSavedMint(record: SavedMintChain): string[] {
  const hashes = [record?.chain?.mintTxHash, record?.chain?.attestationTxHash];
  if (!record?.payer || !Array.isArray(record.signed) || record.signed.length !== 2 ||
      hashes.some(hash => typeof hash !== 'string' || !/^[0-9a-f]{64}$/i.test(hash)) ||
      hashes[0].toLowerCase() === hashes[1].toLowerCase())
    throw new Error('Saved mint chain is invalid. Its recovery record has been retained.');
  for (let i = 0; i < 2; i++) {
    if (transactionHash(record.signed[i]) !== hashes[i].toLowerCase())
      throw new Error('Saved signed transaction does not match the expected mint chain. Its recovery record has been retained.');
  }
  const state = record.confirmation;
  if (state && (!['signed', 'submitting', 'accepted', 'uncertain', 'conflict'].includes(state.phase) ||
      !Number.isInteger(state.submissions) || state.submissions < 0 || state.submissions > MAX_SUBMISSIONS ||
      (state.lastError !== undefined && typeof state.lastError !== 'string')))
    throw new Error('Saved mint submission state is invalid. Its recovery record has been retained.');
  return hashes.map(hash => hash.toLowerCase());
}

/** One owner per intent, including reloads in other tabs where Web Locks is supported.
 * Keep the lock until an outstanding submission settles, even after cancellation. */
export async function confirmSavedMint(key: string, ports: MintConfirmationPorts): Promise<SavedMintChain> {
  active(ports.signal);
  const initial = ports.load() ?? ports.knownRecord;
  if (!initial) throw new Error('Saved signed mint chain is unavailable');
  validateSavedMint(initial);
  const previous = owners.get(key) ?? Promise.resolve();
  const run = previous.catch(() => {}).then(async () => {
    active(ports.signal);
    const task = () => reconcile(ports, initial);
    if (typeof navigator !== 'undefined' && navigator.locks?.request)
      return navigator.locks.request(`admin-mint-${key}`, { mode: 'exclusive', signal: ports.signal }, task);
    return task();
  });
  owners.set(key, run);
  try { return await run; }
  finally { if (owners.get(key) === run) owners.delete(key); }
}

async function reconcile(ports: MintConfirmationPorts, initial: SavedMintChain): Promise<SavedMintChain> {
  active(ports.signal);
  const current = ports.load();
  // Another tab may have confirmed and removed the record while we waited for
  // its lock. Recheck our known hashes read-only before reporting completion.
  const record = current ?? { ...initial, confirmation: { phase: 'accepted' as const, submissions: MAX_SUBMISSIONS } };
  const hashes = validateSavedMint(record);
  // Legacy records may have already submitted. Always reconcile them first and
  // count that possible submission against the retry budget.
  record.confirmation ??= { phase: 'uncertain', submissions: 1 };
  if (record.confirmation.phase === 'conflict')
    throw new Error('Submission returned unexpected transaction hashes. The saved chain needs inspection.');
  const state = record.confirmation;
  const pause = ports.pause ?? waitForMintPoll;
  const progress = () => ports.progress(state.phase === 'accepted'
    ? 'Waiting for the mint and CIP-170 attestation to confirm…'
    : state.submissions >= MAX_SUBMISSIONS
      ? `Submission status is uncertain; checking the chain…${state.lastError ? ` Last submission response: ${state.lastError}` : ''}`
      : 'Checking mint and CIP-170 confirmation…', hashes);

  const submit = async (start: number) => {
    active(ports.signal);
    state.phase = 'submitting';
    state.submissions++;
    ports.save(record); // Persist BEFORE the request: a reload must not reset its budget.
    let response: Submission;
    try { response = await ports.submit(record.signed.slice(start)); }
    catch (error) {
      active(ports.signal);
      response = ports.submissionFailure?.(error) ?? { txHashes: [], error: 'Submission status is unknown' };
    }
    active(ports.signal);
    const expected = hashes.slice(start);
    if (!Array.isArray(response.txHashes) || response.txHashes.length > expected.length ||
        response.txHashes.some((hash, index) => typeof hash !== 'string' || hash.toLowerCase() !== expected[index])) {
      state.phase = 'conflict';
      ports.save(record);
      throw new Error('Submission returned unexpected transaction hashes. The saved chain has been retained.');
    }
    state.phase = !response.error && response.txHashes.length === expected.length ? 'accepted' : 'uncertain';
    state.lastError = state.phase === 'accepted' ? undefined
      : (response.error || 'The backend did not acknowledge every expected transaction.').slice(0, 1_000);
    ports.save(record);
  };

  // Only a freshly persisted chain may submit before the first status read.
  if (state.phase === 'signed' && state.submissions === 0) await submit(0);
  for (;;) {
    active(ports.signal);
    progress();
    let observations: Observation[];
    try { observations = (await ports.check(hashes)).transactions; }
    catch {
      active(ports.signal);
      ports.progress('Confirmation check is temporarily unavailable; checking again…', hashes);
      await pause(ports.signal);
      continue;
    }
    active(ports.signal);
    if (!Array.isArray(observations) || observations.length !== 2 || observations.some((item, index) =>
      !item || typeof item.hash !== 'string' || item.hash.toLowerCase() !== hashes[index] ||
      !['CONFIRMED', 'INVALID', 'NOT_INDEXED', 'UNKNOWN'].includes(item.status)))
      throw new Error('Chain status does not match the saved mint transactions. The recovery record has been retained.');
    if (observations.some(item => item.status === 'INVALID'))
      throw new Error('The mint or CIP-170 transaction is invalid on chain. The signed chain has been retained for inspection.');
    if (observations.every(item => item.status === 'CONFIRMED')) {
      if (ports.load() !== null) ports.clear(record);
      return record;
    }
    if (state.phase !== 'accepted' && state.submissions < MAX_SUBMISSIONS) {
      if (observations.every(item => item.status === 'NOT_INDEXED')) await submit(0);
      else if (observations[0].status === 'CONFIRMED' && observations[1].status === 'NOT_INDEXED') await submit(1);
    }
    progress();
    await pause(ports.signal);
  }
}
