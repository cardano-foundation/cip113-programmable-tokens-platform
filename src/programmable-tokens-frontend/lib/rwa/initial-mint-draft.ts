import { initialMintRecoveryKeys, mintRecoveryStorage } from './mint-recovery-storage';

/** Future-policy settings are separate from the immutable saved registration. */
export interface InitialMintDraft {
  mintableAmount: string;
  securityInfo: string;
  requiresReceiverKyc: boolean;
  seedRecipientInAllowlist: boolean;
  trustedEntities: string[];
  approveInitialMint: boolean;
}

export function readInitialMintDraft(payerHex: string): InitialMintDraft | null {
  const value = mintRecoveryStorage.getItem(initialMintRecoveryKeys(payerHex).draft);
  if (!value) return null;
  try {
    const draft = JSON.parse(value) as InitialMintDraft;
    if (typeof draft.mintableAmount !== 'string' || typeof draft.securityInfo !== 'string' ||
        typeof draft.requiresReceiverKyc !== 'boolean' ||
        typeof draft.seedRecipientInAllowlist !== 'boolean' ||
        typeof draft.approveInitialMint !== 'boolean' ||
        !Array.isArray(draft.trustedEntities) ||
        !draft.trustedEntities.every(key => typeof key === 'string' && /^[0-9a-f]{64}$/i.test(key))) {
      return null;
    }
    return draft;
  } catch { return null; }
}

export function writeInitialMintDraft(payerHex: string, draft: InitialMintDraft): void {
  mintRecoveryStorage.setItem(initialMintRecoveryKeys(payerHex).draft, JSON.stringify(draft));
}

export function canBuildInitialRegistration(
  connected: boolean, recoveryLoaded: boolean, recoveryError: string | null,
  savedAttemptActive: boolean,
): boolean {
  return connected && recoveryLoaded && !recoveryError && !savedAttemptActive;
}

/** A restored chain never becomes the new draft's Register action. */
export function initialRegistrationAction(savedAttemptActive: boolean,
                                          restoredAttempt: boolean,
                                          chainReady: boolean):
  'resolve-previous' | 'continue-approval' | 'submit-current' | 'build-new' {
  if (restoredAttempt) return 'resolve-previous';
  if (savedAttemptActive) return chainReady ? 'submit-current' : 'continue-approval';
  return 'build-new';
}

/** Clear the active pointer only after the backend confirms safe archival. */
export function finishArchivedInitialMint(payerHex: string, status: string,
                                          canStartNewPolicy: boolean, intentId: string): void {
  if (status !== 'ARCHIVED_EXPIRED' || !canStartNewPolicy) {
    throw new Error('The saved chain is not safely archived');
  }
  const keys = initialMintRecoveryKeys(payerHex);
  if (mintRecoveryStorage.getItem(keys.intent) === intentId) {
    mintRecoveryStorage.removeItem(keys.intent);
    mintRecoveryStorage.removeItem(keys.registration);
  }
}
