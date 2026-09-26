package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Immutable public mint document and fenced signing/build progress. */
@Entity
@Table(name = "mint_attestation_intent")
@Getter
@Setter
public class MintAttestationIntentEntity {
    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;
    @Version
    @Column(name = "row_version", nullable = false)
    private Long rowVersion;
    @Column(name = "session_id", length = 128, nullable = false)
    private String sessionId;
    @Column(name = "wallet_aid", length = 128, nullable = false)
    private String walletAid;
    @Column(name = "issuer_aid", length = 128, nullable = false)
    private String issuerAid;
    @Column(name = "credential_said", length = 128, nullable = false)
    private String credentialSaid;
    @Column(name = "fields_json", columnDefinition = "text", nullable = false)
    private String fieldsJson;
    @Column(name = "digest", length = 128, nullable = false)
    private String digest;
    @Column(name = "document_json", columnDefinition = "text", nullable = false)
    private String documentJson;
    @Column(name = "preimage", columnDefinition = "text", nullable = false)
    private String preimage;
    @Column(name = "status", length = 32, nullable = false)
    private String status;
    @Column(name = "request_said", length = 128, nullable = true)
    private String requestSaid;
    @Column(name = "request_json", columnDefinition = "text", nullable = true)
    private String requestJson;
    @Column(name = "request_sigs", columnDefinition = "text", nullable = true)
    private String requestSigs;
    @Column(name = "request_atc", columnDefinition = "text", nullable = true)
    private String requestAtc;
    @Column(name = "wallet_kel_floor", length = 32, nullable = false)
    private String walletKelFloor;
    @Column(name = "dispatch_started", nullable = false)
    private boolean dispatchStarted;
    @Column(name = "sequence_number", length = 64, nullable = true)
    private String sequenceNumber;
    @Column(name = "kel_event_json", columnDefinition = "text", nullable = true)
    private String kelEventJson;
    @Column(name = "claim_owner", length = 64, nullable = true)
    private String claimOwner;
    @Column(name = "lease_until", nullable = true)
    private Instant leaseUntil;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "unsigned_cbor", columnDefinition = "text", nullable = true)
    private String unsignedCbor;
    /** Non-null only for a prepared initial CMTA mint. Never supplied to the admin mint path. */
    @Column(name = "initial_registration_json", columnDefinition = "text")
    private String initialRegistrationJson;
    @Column(name = "initial_plan_json", columnDefinition = "text")
    private String initialPlanJson;
    @Column(name = "initial_chain_json", columnDefinition = "text")
    private String initialChainJson;
    /** Private, byte-frozen chain through the target registration transaction. */
    @Column(name = "initial_prefix_json", columnDefinition = "text")
    private String initialPrefixJson;
    /** Detached database writes produced by the rollback-only preview build. */
    @Column(name = "initial_snapshot_json", columnDefinition = "text")
    private String initialSnapshotJson;
    @Column(name = "transaction_hash", length = 64, nullable = true)
    private String transactionHash;
    @Column(name = "attestation_cbor", columnDefinition = "text")
    private String attestationCbor;
    @Column(name = "attestation_tx_hash", length = 64)
    private String attestationTxHash;
}
