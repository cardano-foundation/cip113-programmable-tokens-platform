package org.cardanofoundation.cip113.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Registration request for the "security-token" substandard. */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityTokenRegisterRequest extends RegisterTokenRequest {

    /** PKH of the issuer admin. The backend's signing key must match so it can
     *  autonomously sign {@code UpdateMemberRootHash} transactions. */
    private String adminPubKeyHash;

    /** Policy id of the global-state NFT. Carries the MPF root hash + the
     *  {@code requires_receiver_kyc} flag on chain. */
    private String globalStatePolicyId;

    /** Policy id of the denylist linked-list root NFT. */
    private String denylistPolicyId;

    /** Policy id of the power-users linked-list root NFT. */
    private String powerUsersPolicyId;

    /** Whether transfers to a recipient that has not completed KYC are blocked at
     *  registration time. Can be toggled later via the global-state spend action
     *  {@code SetRequiresReceiverKyc}. */
    private boolean requiresReceiverKyc;

    /** Initial mintable cap for the security-token policy. Decremented on every
     *  {@code MintSecurity} spend action; once it hits zero, no more tokens can
     *  be issued. Defaults to 0 (no minting until admin updates) if not provided. */
    private Long initialMintableAmount;

    /** Optional: bootstrap power user inserted into the off-chain DB at registration.
     *  Lets the registering admin see themselves on the admin page immediately.
     *  Defaults to {@code adminPubKeyHash} with all-capabilities if not provided
     *  separately. The on-chain insertion via {@code AddPowerUser} is a separate tx
     *  the admin triggers from the admin page once the linked-list root is in place. */
    private String bootstrapPowerUserPkh;
    private Integer bootstrapPowerUserCapabilities;
    private String bootstrapPowerUserLabel;

    /** Optional CIP-170 attestation data to attach as ATTEST metadata (label 170). */
    private Cip170AttestationData attestation;

    /** Optional initial trusted-entity Ed25519 vkeys (32-byte hex each).
     *  These are baked into the GS datum's {@code trusted_entity_vkeys} at
     *  genesis so KYC proofs from these issuers verify on chain immediately —
     *  no separate {@code AddTrustedEntity} update tx needed before the first
     *  transfer. Typically includes the backend's own KERI signing-entity
     *  vkey so locally-issued KYC proofs work out of the box. */
    private List<String> initialTrustedEntityVkeys;
}
