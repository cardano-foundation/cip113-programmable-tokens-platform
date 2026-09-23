package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "rwa_genesis_reservation")
@Getter
@Setter
public class RwaGenesisReservationEntity {
    @Id
    @Column(name = "global_state_policy_id", length = 56)
    private String globalStatePolicyId;

    @Column(name = "bootstrap_tx_hash", length = 64)
    private String bootstrapTxHash;

    @Column(name = "bootstrap_output_index")
    private Integer bootstrapOutputIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
