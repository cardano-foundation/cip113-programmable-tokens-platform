package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "rwa_genesis_funding_reservation")
@Getter
@Setter
public class RwaGenesisFundingReservationEntity {
    @Id
    @Column(name = "input_ref", length = 80)
    private String inputRef;

    @Column(name = "global_state_policy_id", nullable = false, length = 56)
    private String globalStatePolicyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
