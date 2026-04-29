package org.cardanofoundation.cip113.model;

/**
 * Request to transfer programmable tokens.
 * The substandard is resolved from the unit (policyId) via the programmable token registry.
 *
 * @param senderAddress    The sender's wallet address
 * @param unit             The token unit (policyId + assetNameHex) - used to resolve substandard
 * @param quantity         The quantity to transfer
 * @param recipientAddress The recipient's wallet address
 */
public record TransferTokenRequest(String senderAddress,
                                   String unit,
                                   String quantity,
                                   String recipientAddress,
                                   // KYC fields (optional, used by KYC substandard)
                                   /** Hex-encoded 37-byte KYC payload: user_pkh(28) || role(1) || valid_until(8) */
                                   String kycPayload,
                                   /** Hex-encoded 64-byte Ed25519 signature over kycPayload */
                                   String kycSignature,
                                   /** Index of the trusted entity vkey in the global state list (default 0) */
                                   Integer kycVkeyIndex) {

}
