/**
 * Token Transfer API
 */

import { TransferTokenRequest, TransferTokenResponse } from '@/types/api';
import { apiPost } from './client';

/**
 * Transfer programmable tokens from sender to recipient
 * Returns unsigned transaction CBOR hex
 */
export async function transferToken(
  request: TransferTokenRequest,
  protocolTxHash?: string
): Promise<TransferTokenResponse> {
  const endpoint = protocolTxHash
    ? `/transfer-token/transfer?protocolTxHash=${protocolTxHash}`
    : '/transfer-token/transfer';

  return apiPost<TransferTokenRequest, TransferTokenResponse>(
    endpoint,
    request,
    { timeout: 60000 } // 60 seconds for transfer transaction
  );
}
