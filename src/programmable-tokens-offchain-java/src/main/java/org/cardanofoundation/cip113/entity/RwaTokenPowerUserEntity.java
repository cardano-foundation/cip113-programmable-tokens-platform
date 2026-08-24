package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "rwa_token_power_user",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_st_power_user",
                columnNames = {"programmable_token_policy_id", "power_user_pkh"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RwaTokenPowerUserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "power_user_pkh", nullable = false, length = 56)
    private String powerUserPkh;

    /** Bitfield: see {@link RwaTokenPowerUserCapability}. */
    @Column(name = "capabilities", nullable = false)
    private int capabilities;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "added_at", nullable = false)
    @Builder.Default
    private Instant addedAt = Instant.now();
}
