package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Immutable leaves for one candidate or confirmed on-chain member root. */
@Entity
@Table(name = "rwa_token_member_root_snapshot")
@Getter
@Setter
public class RwaTokenMemberRootSnapshotEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "root_hash", nullable = false, length = 64)
    private String rootHash;

    @Column(name = "baseline_root_hash", nullable = false, length = 64)
    private String baselineRootHash;

    @Column(name = "leaves_json", nullable = false, columnDefinition = "text")
    private String leavesJson;

    @Column(name = "tx_hash", length = 64)
    private String txHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
