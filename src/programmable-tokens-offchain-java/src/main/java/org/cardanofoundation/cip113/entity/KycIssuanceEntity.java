package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "kyc_issuance")
@Getter
@Setter
public class KycIssuanceEntity {
    @Id
    @Column(name = "session_id", length = 128)
    private String sessionId;

    @Version
    @Column(name = "row_version")
    private Long rowVersion;
    @Column(name = "wallet_aid", length = 128, nullable = false)
    private String walletAid;
    @Column(name = "issuer_aid", length = 128, nullable = false)
    private String issuerAid;
    @Column(name = "schema_said", length = 128, nullable = false)
    private String schemaSaid;
    @Column(name = "schema_oobi_url", columnDefinition = "text", nullable = false)
    private String schemaOobiUrl;
    @Column(name = "attributes_json", columnDefinition = "text", nullable = false)
    private String attributesJson;
    @Column(name = "status", length = 32, nullable = false)
    private String status;
    @Column(name = "credential_said", length = 128)
    private String credentialSaid;
    @Column(name = "issue_operation_name", length = 255)
    private String issueOperationName;
    @Column(name = "acdc_json", columnDefinition = "text")
    private String acdcJson;
    @Column(name = "iss_json", columnDefinition = "text")
    private String issJson;
    @Column(name = "anc_json", columnDefinition = "text")
    private String ancJson;
    @Column(name = "grant_said", length = 128)
    private String grantSaid;
    @Column(name = "grant_raw", columnDefinition = "text")
    private String grantRaw;
    @Column(name = "grant_sigs", columnDefinition = "text")
    private String grantSigs;
    @Column(name = "grant_atc", columnDefinition = "text")
    private String grantAtc;
    @Column(name = "signing_establishment_seq", length = 64)
    private String signingEstablishmentSeq;
    @Column(name = "signing_establishment_digest", length = 128)
    private String signingEstablishmentDigest;
    @Column(name = "delivery_owner", length = 128)
    private String deliveryOwner;
    @Column(name = "delivery_lease_until")
    private Instant deliveryLeaseUntil;
}
