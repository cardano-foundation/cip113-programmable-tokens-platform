package org.cardanofoundation.cip113.model;

/** Exact wallet-authorized inputs for the optional mint attestation. */
public record MintAttestationRequest(String sessionId, String network, String protocolTxHash,
        String tokenPolicyId, String assetName, String quantity, String feePayerAddress,
        String recipientAddress, String programmableRecipientAddress, String requestId) {
    public MintAttestationRequest(String sessionId, String network, String protocolTxHash,
            String tokenPolicyId, String assetName, String quantity, String feePayerAddress,
            String recipientAddress, String programmableRecipientAddress) {
        this(sessionId, network, protocolTxHash, tokenPolicyId, assetName, quantity,
                feePayerAddress, recipientAddress, programmableRecipientAddress, null);
    }
}
