package org.cardanofoundation.cip113.model;

/**
 * Request to publish a credential chain on-chain as a CIP-170 AUTH_BEGIN transaction.
 *
 * @param feePayerAddress The address that pays for the transaction
 */
public record CredentialChainPublishRequest(String feePayerAddress) {
}
