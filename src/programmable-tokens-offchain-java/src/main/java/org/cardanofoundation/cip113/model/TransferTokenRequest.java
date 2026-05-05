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
                                   // KYC fields (optional, used by KYC + KYC-Extended substandards)
                                   /** Hex-encoded 37-byte KYC payload: user_pkh(28) || role(1) || valid_until(8) */
                                   String kycPayload,
                                   /** Hex-encoded 64-byte Ed25519 signature over kycPayload */
                                   String kycSignature,
                                   /** Index of the trusted entity vkey in the global state list (default 0) */
                                   Integer kycVkeyIndex,
                                   // KYC-Extended sender fast-path: Membership proof for sender (replaces Attestation)
                                   /** Hex CBOR of the sender MPF inclusion proof (Aiken mpf.Proof Plutus Data) */
                                   String senderMpfProofCborHex,
                                   /** Sender leaf valid_until (8-byte BE millis) — must match the leaf encoded value */
                                   Long senderMpfValidUntilMs,
                                   // KYC-Extended receiver-side proofs (kyc-extended only): one entry per prog-base output
                                   /** Hex CBOR of the recipient MPF inclusion proof (Aiken mpf.Proof Plutus Data) */
                                   String mpfProofCborHex,
                                   /** Recipient leaf valid_until (8-byte BE millis) — must match the leaf encoded value */
                                   Long mpfValidUntilMs) {

}
