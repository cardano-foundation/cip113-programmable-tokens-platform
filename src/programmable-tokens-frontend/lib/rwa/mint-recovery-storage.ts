const initialMintMemory = new Map<string, string>();
const isInitialMintKey = (key: string) => key.startsWith('rwa-initial-mint-');

/** Registration CIP-170 stays in memory; admin mint recovery retains its own storage. */
export const mintRecoveryStorage = {
  getItem(key: string): string | null {
    if (isInitialMintKey(key)) return initialMintMemory.get(key) ?? null;
    if (typeof window === 'undefined') return null;
    const saved = window.localStorage.getItem(key);
    if (saved !== null) return saved;
    const previous = window.sessionStorage.getItem(key);
    if (previous !== null) window.localStorage.setItem(key, previous);
    return previous;
  },
  setItem(key: string, value: string): void {
    if (isInitialMintKey(key)) { initialMintMemory.set(key, value); return; }
    if (typeof window === 'undefined') throw new Error('Mint recovery storage is only available in the browser');
    window.localStorage.setItem(key, value);
    window.sessionStorage.removeItem(key);
  },
  removeItem(key: string): void {
    if (isInitialMintKey(key)) { initialMintMemory.delete(key); return; }
    if (typeof window === 'undefined') return;
    window.localStorage.removeItem(key);
    window.sessionStorage.removeItem(key);
  },
};

/** Remove data written by older registration builds, then forget this run. */
export function clearRegistrationCip170Storage(): void {
  initialMintMemory.clear();
  if (typeof window === 'undefined') return;
  for (const area of [window.localStorage, window.sessionStorage]) {
    for (let i = area.length - 1; i >= 0; i--) {
      const key = area.key(i);
      if (key && isInitialMintKey(key)) area.removeItem(key);
    }
    area.removeItem('register-cip170-session-id');
  }
}

export function initialMintRecoveryKeys(payerHex: string) {
  const suffix = payerHex;
  return {
    session: `rwa-initial-mint-keri-session-${suffix}`,
    intent: `rwa-initial-mint-keri-intent-${suffix}`,
    registration: `rwa-initial-mint-frozen-registration-${suffix}`,
    draft: `rwa-initial-mint-next-draft-${suffix}`,
  };
}

export interface SavedInitialMintAttempt {
  intentId: string;
  sessionId: string;
  payerAddress: string;
  payerHex: string;
  registration: {
    feePayerAddress: string;
    assetName: string;
    adminPubKeyHash: string;
    requiresReceiverKyc: boolean;
    initialMintQuantity?: string;
  };
}

const ATTEMPT_PREFIX = 'rwa-initial-mint-attempt-';
const REGISTRATION_LOCK = 'rwa-registration-admission';

/** One browser origin must not admit two registration chains at once. */
export async function withRegistrationLock<T>(action: () => Promise<T>): Promise<T> {
  if (typeof navigator === 'undefined' || !navigator.locks?.request)
    throw new Error('This browser cannot coordinate registration across tabs. Use a browser with Web Locks support.');
  return navigator.locks.request(REGISTRATION_LOCK, { mode: 'exclusive' }, action);
}

export function saveInitialMintAttempt(attempt: SavedInitialMintAttempt): void {
  const key = ATTEMPT_PREFIX + attempt.intentId;
  const existing = mintRecoveryStorage.getItem(key);
  const encoded = JSON.stringify(attempt);
  if (existing !== null && existing !== encoded)
    throw new Error('A different recovery record already uses this initial-mint request ID');
  mintRecoveryStorage.setItem(key, encoded);
}

export async function admitInitialMintAttempt(wallet: {
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
}, normalize: (address: string) => string, attempt: SavedInitialMintAttempt): Promise<void> {
  await withRegistrationLock(async () => {
    const { attempts, addresses } = await scanWalletInitialMintAttempts(wallet, normalize);
    if (!addresses.some(address => normalize(address) === attempt.payerHex))
      throw new Error('The connected wallet no longer reports the registration fee payer');
    if (attempts.length)
      throw new Error('Resolve the previous saved registration before preparing a new one');
    saveInitialMintAttempt(attempt);
  });
}

export function removeInitialMintAttempt(intentId: string): void {
  const encoded = mintRecoveryStorage.getItem(ATTEMPT_PREFIX + intentId);
  if (encoded) {
    const record = JSON.parse(encoded) as SavedInitialMintAttempt;
    const keys = initialMintRecoveryKeys(record.payerHex);
    if (mintRecoveryStorage.getItem(keys.intent) === intentId) {
      mintRecoveryStorage.removeItem(keys.intent);
      mintRecoveryStorage.removeItem(keys.registration);
      if (mintRecoveryStorage.getItem(keys.session) === record.sessionId)
        mintRecoveryStorage.removeItem(keys.session);
    }
  }
  mintRecoveryStorage.removeItem(ATTEMPT_PREFIX + intentId);
}

function storedAttempts(): SavedInitialMintAttempt[] {
  const attempts: SavedInitialMintAttempt[] = [];
  for (const key of initialMintMemory.keys()) {
    if (!key?.startsWith(ATTEMPT_PREFIX)) continue;
    const encoded = mintRecoveryStorage.getItem(key);
    if (!encoded) throw new Error('Initial-mint recovery record disappeared during scan');
    let record: SavedInitialMintAttempt;
    try { record = JSON.parse(encoded) as SavedInitialMintAttempt; }
    catch { throw new Error('Saved initial-mint recovery record is malformed'); }
    if (!record || record.intentId !== key.slice(ATTEMPT_PREFIX.length) ||
        typeof record.sessionId !== 'string' || !record.sessionId ||
        typeof record.payerHex !== 'string' || !record.payerHex ||
        typeof record.payerAddress !== 'string' || !record.payerAddress ||
        !record.registration || typeof record.registration !== 'object')
      throw new Error('Saved initial-mint recovery record is incomplete');
    attempts.push(record);
  }
  return attempts;
}

export async function scanWalletInitialMintAttempts(wallet: {
  getUsedAddresses(): Promise<string[]>;
  getChangeAddress(): Promise<string>;
}, normalize: (address: string) => string) {
  const [used, change] = await Promise.all([wallet.getUsedAddresses(), wallet.getChangeAddress()]);
  return scanInitialMintAttempts([...used, change], normalize);
}

/** Scan every address the wallet reports before allowing another policy build. */
export function scanInitialMintAttempts(addresses: string[], normalize: (address: string) => string):
  { payerAddress: string; addresses: string[]; attempts: SavedInitialMintAttempt[] } {
  const candidates = new Map<string, string>();
  for (const address of addresses) {
    const hex = normalize(address);
    if (!candidates.has(hex)) candidates.set(hex, address);
  }
  const payerAddress = candidates.values().next().value;
  if (!payerAddress) throw new Error('Connected wallet has no usable address');

  const legacyId = mintRecoveryStorage.getItem('rwa-initial-mint-keri-intent');
  const legacyRegistration = mintRecoveryStorage.getItem('rwa-initial-mint-frozen-registration');
  const legacySession = mintRecoveryStorage.getItem('rwa-initial-mint-keri-session');
  if (legacyId || legacyRegistration) {
    if (!legacyId || !legacyRegistration)
      throw new Error('Incomplete legacy initial-mint recovery record; inspect it before creating another policy');
    const legacy = parseRegistration(legacyRegistration);
    const legacyPayer = normalize(legacy.feePayerAddress);
    if (candidates.has(legacyPayer)) {
      const keys = initialMintRecoveryKeys(legacyPayer);
      const scopedId = mintRecoveryStorage.getItem(keys.intent);
      const scopedRegistration = mintRecoveryStorage.getItem(keys.registration);
      const scopedSession = mintRecoveryStorage.getItem(keys.session);
      if (legacySession !== null && scopedSession !== null && scopedSession !== legacySession)
        throw new Error('Legacy and wallet-scoped Veridian sessions conflict; preserve both for recovery');
      if (scopedId !== null || scopedRegistration !== null) {
        if (scopedId !== legacyId || scopedRegistration !== legacyRegistration)
          throw new Error('Legacy and wallet-scoped initial-mint attempts conflict; preserve both for recovery');
      } else {
        mintRecoveryStorage.setItem(keys.registration, legacyRegistration);
        mintRecoveryStorage.setItem(keys.intent, legacyId);
      }
      if (legacySession !== null && scopedSession === null)
        mintRecoveryStorage.setItem(keys.session, legacySession);
      mintRecoveryStorage.removeItem('rwa-initial-mint-keri-intent');
      mintRecoveryStorage.removeItem('rwa-initial-mint-frozen-registration');
      mintRecoveryStorage.removeItem('rwa-initial-mint-keri-session');
    }
  }

  const attempts = storedAttempts();
  for (const [payerHex, address] of candidates) {
    const keys = initialMintRecoveryKeys(payerHex);
    const intentId = mintRecoveryStorage.getItem(keys.intent);
    const saved = mintRecoveryStorage.getItem(keys.registration);
    if (!intentId && !saved) continue;
    if (!intentId || !saved)
      throw new Error('Incomplete saved initial-mint attempt; inspect it before creating another policy');
    const registration = parseRegistration(saved);
    if (normalize(registration.feePayerAddress) !== payerHex)
      throw new Error('Saved initial-mint payer does not match its wallet recovery key');
    if (!attempts.some(attempt => attempt.intentId === intentId)) {
      const sessionId = mintRecoveryStorage.getItem(keys.session);
      if (!sessionId) throw new Error('Saved initial-mint session is unavailable');
      const attempt = { intentId, sessionId, payerAddress: registration.feePayerAddress || address,
        payerHex, registration };
      saveInitialMintAttempt(attempt);
      attempts.push(attempt);
    }
  }
  for (const attempt of attempts) {
    if (!candidates.has(attempt.payerHex)) continue;
    if (normalize(attempt.registration.feePayerAddress) !== attempt.payerHex)
      throw new Error('Saved initial-mint payer does not match its wallet recovery key');
  }
  const owned = attempts.filter(attempt => candidates.has(attempt.payerHex));
  if (new Set(owned.map(attempt => attempt.intentId)).size !== owned.length)
    throw new Error('The same initial-mint intent is saved under multiple wallet addresses');
  return { payerAddress, addresses: [...candidates.values()], attempts: owned };
}

function parseRegistration(saved: string): SavedInitialMintAttempt['registration'] {
  let value: unknown;
  try { value = JSON.parse(saved); }
  catch { throw new Error('Saved initial-mint registration is malformed'); }
  if (!value || typeof value !== 'object')
    throw new Error('Saved initial-mint registration is malformed');
  const request = value as Record<string, unknown>;
  if (typeof request.feePayerAddress !== 'string' || !request.feePayerAddress ||
      typeof request.assetName !== 'string' ||
      typeof request.adminPubKeyHash !== 'string' ||
      typeof request.requiresReceiverKyc !== 'boolean')
    throw new Error('Saved initial-mint registration is incomplete');
  return value as SavedInitialMintAttempt['registration'];
}
