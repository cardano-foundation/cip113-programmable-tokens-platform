package org.cardanofoundation.cip113.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Registration request for the "kyc" substandard.
 * Includes the global state policy ID that will be used
 * to verify KYC attestations during token transfers.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KycRegisterRequest extends RegisterTokenRequest {

    /**
     * Public key hash of the admin who will manage this token (issuer).
     * Used to parameterize the issuer admin contract.
     */
    private String adminPubKeyHash;

    /**
     * Policy ID of the global state NFT.
     * The transfer validator will check KYC attestations against entities in the global state.
     */
    private String globalStatePolicyId;

    /**
     * Optional CIP-170 attestation data to attach as ATTEST metadata (label 170).
     */
    private Cip170AttestationData attestation;
}
