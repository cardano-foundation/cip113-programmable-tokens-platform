package org.cardanofoundation.cip113.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Registration request for the "kyc-extended" substandard.
 *
 * Mirrors {@link KycRegisterRequest} structurally; declared as a separate type so
 * Jackson can discriminate between "kyc" and "kyc-extended" via the
 * {@code substandardId} property.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class KycExtendedRegisterRequest extends RegisterTokenRequest {

    /** PKH of the admin who manages this token (issuer). */
    private String adminPubKeyHash;

    /** Policy ID of the global state NFT (carries the MPF root hash on-chain). */
    private String globalStatePolicyId;

    /** Optional CIP-170 attestation data to attach as ATTEST metadata (label 170). */
    private Cip170AttestationData attestation;
}
