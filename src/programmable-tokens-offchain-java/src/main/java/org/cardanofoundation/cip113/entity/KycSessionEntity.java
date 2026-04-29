package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "kyc_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycSessionEntity {

    @Id
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "aid", length = 128)
    private String aid;

    @Column(name = "oobi", columnDefinition = "text")
    private String oobi;

    @Column(name = "cardano_address", length = 255)
    private String cardanoAddress;

    @Column(name = "credential_attributes", columnDefinition = "text")
    private String credentialAttributes;

    @Column(name = "credential_role")
    private Integer credentialRole;

    @Column(name = "credential_aid")
    private String credentialAid;

    @Column(name = "credential_said")
    private String credentialSaid;

    @Column(name = "kyc_proof_payload", length = 74)
    private String kycProofPayload;

    @Column(name = "kyc_proof_signature", length = 128)
    private String kycProofSignature;

    @Column(name = "kyc_proof_entity_vkey", length = 64)
    private String kycProofEntityVkey;

    @Column(name = "kyc_proof_valid_until")
    private Long kycProofValidUntil;
}
