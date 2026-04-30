package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "kyc_extended_member_leaf",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_policy_member",
                columnNames = {"programmable_token_policy_id", "member_pkh"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycExtendedMemberLeafEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "member_pkh", nullable = false, length = 56)
    private String memberPkh;

    @Column(name = "bound_address", length = 255)
    private String boundAddress;

    @Column(name = "kyc_session_id", length = 128)
    private String kycSessionId;

    @Column(name = "valid_until_ms", nullable = false)
    private long validUntilMs;

    @Column(name = "added_at", nullable = false)
    @Builder.Default
    private Instant addedAt = Instant.now();

    /** Set once the leaf is included in an on-chain root publish. Inclusion proofs
     *  are generated only from leaves with {@code publishedAt} non-null. */
    @Column(name = "published_at")
    private Instant publishedAt;
}
