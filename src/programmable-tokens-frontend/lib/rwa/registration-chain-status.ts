export type RegistrationChainStatus = 'CONFIRMED' | 'INVALID' | 'NOT_INDEXED' | 'UNKNOWN';
export type SubmissionPhase = 'accepted' | 'partial' | 'lost' | 'conflict';

export interface RegistrationChainObservation {
  hash: string;
  status: RegistrationChainStatus;
  reason: string;
}

/** Refuse a provider response for any other chain or with missing evidence. */
export function validateChainObservations(
  expectedHashes: string[], observations: RegistrationChainObservation[],
): RegistrationChainObservation[] {
  if (observations.length !== expectedHashes.length || observations.some((item, index) =>
    !item || item.hash.toLowerCase() !== expectedHashes[index].toLowerCase() ||
    !['CONFIRMED', 'INVALID', 'NOT_INDEXED', 'UNKNOWN'].includes(item.status)))
    throw new Error('Chain status response does not match the saved registration transactions');
  return observations;
}

export function allChainTransactionsConfirmed(observations: RegistrationChainObservation[]): boolean {
  return observations.length > 0 && observations.every(item => item.status === 'CONFIRMED');
}

/** Resend only the same signed bytes, after every previously accepted hash is proven. */
export function canResumeRegistrationChain(
  phase: SubmissionPhase, acceptedCount: number,
  observations: RegistrationChainObservation[],
): boolean {
  if (!observations.length || acceptedCount < 0 || acceptedCount >= observations.length ||
      phase === 'accepted' || phase === 'conflict') return false;
  if (phase === 'lost') {
    const confirmedPrefix = observations.findIndex(item => item.status !== 'CONFIRMED');
    const prefixLength = confirmedPrefix < 0 ? observations.length : confirmedPrefix;
    return prefixLength > 0 && prefixLength < observations.length &&
      observations.slice(prefixLength).every(item => item.status === 'NOT_INDEXED');
  }
  return observations.slice(0, acceptedCount).every(item => item.status === 'CONFIRMED') &&
    observations.slice(acceptedCount).every(item => item.status === 'NOT_INDEXED');
}
