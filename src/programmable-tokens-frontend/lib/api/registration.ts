/**
 * Token Registration API
 */

import { RegisterTokenRequest, RegisterTokenResponse } from '@/types/api';
import { apiPost } from './client';

/**
 * Response from pre-registration endpoint
 */
export interface PreRegistrationResponse {
  /** Unsigned transaction CBOR hex, or null if all addresses are already registered */
  unsignedCborTx: string | null;
  /** List of stake addresses (already registered or to be registered) */
  metadata: string[];
  /** Whether the operation was successful */
  isSuccessful: boolean;
  /** Error message if unsuccessful */
  error: string | null;
}

/**
 * Pre-register stake addresses required for programmable token registration.
 * This registers withdraw-0 script stake addresses before the main token registration.
 *
 * @param request The registration request (same as for registerToken)
 * @param protocolTxHash Optional protocol version tx hash
 * @returns Transaction context with unsigned tx (null if all already registered) and stake address list
 */
export async function preRegisterToken(
  request: RegisterTokenRequest,
  protocolTxHash?: string
): Promise<PreRegistrationResponse> {
  const endpoint = protocolTxHash
    ? `/issue-token/pre-register?protocolTxHash=${protocolTxHash}`
    : '/issue-token/pre-register';

  return apiPost<RegisterTokenRequest, PreRegistrationResponse>(
    endpoint,
    request,
    { timeout: 60000 } // 60 seconds for pre-registration transaction
  );
}

/**
 * Register a new programmable token policy
 * Returns policy ID and unsigned transaction CBOR hex
 */
export async function registerToken(
  request: RegisterTokenRequest,
  protocolTxHash?: string
): Promise<RegisterTokenResponse> {
  const endpoint = protocolTxHash
    ? `/issue-token/register?protocolTxHash=${protocolTxHash}`
    : '/issue-token/register';

  return apiPost<RegisterTokenRequest, RegisterTokenResponse>(
    endpoint,
    request,
    { timeout: 60000 } // 60 seconds for registration transaction
  );
}

/** Tell the backend that a script stake credential is already registered on chain.
 *
 *  Called after a submit fails with ledger error 3145, whose payload names the credential. The
 *  backend cannot determine this itself: its account endpoint may be absent, and its indexed
 *  certificates only reach back to sync-start-slot, while the protocol-global validators are
 *  registered once per network — usually long before that. Recording it here is what lets the
 *  next attempt leave the credential out instead of failing identically. */
export async function noteKnownRegistration(knownCredential: string): Promise<void> {
  await apiPost('/script-registration/known', { knownCredential });
}
