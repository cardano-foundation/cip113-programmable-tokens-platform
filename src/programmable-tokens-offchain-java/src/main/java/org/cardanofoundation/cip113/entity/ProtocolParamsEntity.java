package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "protocol_params", indexes = {
    @Index(name = "idx_tx_hash", columnList = "txHash"),
    @Index(name = "idx_slot", columnList = "slot")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProtocolParamsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 56)
    private String registryNodePolicyId;

    @Column(nullable = false, length = 56)
    private String progLogicScriptHash;

    /**
     * Fields 2-5 of the protocol-params datum: the delegate credentials, plus field 6's bound.
     *
     * <p>Nullable because rows written before migration V22 came from a decoder that read only
     * fields 0 and 1 and discarded the rest. NULL means "not known for this row", which is
     * different from — and must not be conflated with — a credential that was genuinely
     * absent. Every row written since populates all of them.
     *
     * <p>These are the values an in-place upgrade changes, so they are the only way to answer
     * "which validator is authorising spends right now?" from the indexed view: the deployment
     * record in {@code protocol-bootstraps-*.json} records what was written at deploy time and
     * is never updated by an upgrade.
     */
    @Column(length = 56)
    private String transferCred;

    @Column(length = 56)
    private String thirdPartyCred;

    @Column(length = 56)
    private String unfrackingCred;

    @Column(length = 56)
    private String upgradeCred;

    /** Field 6: the bound on inline datums of holder-created programmable outputs. */
    @Column
    private Long maxInlineDatumBytes;

    @Column(nullable = false, unique = true, length = 64)
    private String txHash;

    @Column(nullable = false)
    private Long slot;

    @Column(nullable = false)
    private Long blockHeight;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
