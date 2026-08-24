package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "rwa_token_denylist_entry",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_st_denylist",
                columnNames = {"programmable_token_policy_id", "member_pkh"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RwaTokenDenylistEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "member_pkh", nullable = false, length = 56)
    private String memberPkh;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "added_by_power_user_pkh", length = 56)
    private String addedByPowerUserPkh;

    @Column(name = "added_at", nullable = false)
    @Builder.Default
    private Instant addedAt = Instant.now();
}
