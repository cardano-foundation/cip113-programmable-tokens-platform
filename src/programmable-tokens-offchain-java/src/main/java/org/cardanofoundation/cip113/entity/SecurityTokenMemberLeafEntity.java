package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "security_token_member_leaf",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_st_policy_member",
                columnNames = {"programmable_token_policy_id", "member_pkh", "credential_type"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityTokenMemberLeafEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "member_pkh", nullable = false, length = 56)
    private String memberPkh;

    /** Which credential constructor this member's hash belongs to: {@code 0} =
     *  {@code VerificationKey}, {@code 1} = {@code Script}. It is the FIRST BYTE of the
     *  MPF leaf key, so it is part of the member's identity, not a description of it —
     *  the same hash under both forms is two different members with two different
     *  leaves, and the CIP-113 base layer authorises them differently (a key credential
     *  by signature, a script credential by withdraw-0). Stored rather than assumed
     *  because guessing builds a leaf the holder can never prove against. */
    @Column(name = "credential_type", nullable = false)
    @Builder.Default
    private short credentialType = 0;

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
