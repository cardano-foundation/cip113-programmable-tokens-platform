package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.KycExtendedMemberLeafEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface KycExtendedMemberLeafRepository
        extends JpaRepository<KycExtendedMemberLeafEntity, Long> {

    List<KycExtendedMemberLeafEntity> findByProgrammableTokenPolicyId(String policyId);

    /** Only the leaves that have been included in a successful on-chain root publish. */
    @Query("SELECT e FROM KycExtendedMemberLeafEntity e " +
           "WHERE e.programmableTokenPolicyId = :policyId AND e.publishedAt IS NOT NULL")
    List<KycExtendedMemberLeafEntity> findPublishedByProgrammableTokenPolicyId(
            @Param("policyId") String policyId);

    Optional<KycExtendedMemberLeafEntity> findByProgrammableTokenPolicyIdAndMemberPkh(
            String policyId, String memberPkh);

    /** Mark every leaf added at-or-before {@code attemptStartedAt} as published. */
    @Modifying
    @Query("UPDATE KycExtendedMemberLeafEntity e SET e.publishedAt = :publishedAt " +
           "WHERE e.programmableTokenPolicyId = :policyId AND e.addedAt <= :attemptStartedAt")
    int markLeavesPublished(
            @Param("policyId") String policyId,
            @Param("attemptStartedAt") Instant attemptStartedAt,
            @Param("publishedAt") Instant publishedAt);

    @Query("SELECT e FROM KycExtendedMemberLeafEntity e " +
           "WHERE e.programmableTokenPolicyId = :policyId AND e.validUntilMs < :cutoffMs")
    List<KycExtendedMemberLeafEntity> findExpired(
            @Param("policyId") String policyId, @Param("cutoffMs") long cutoffMs);

    /** Atomic upsert keyed on (programmable_token_policy_id, member_pkh) — avoids the
     *  find-then-insert race under concurrent inclusion requests. */
    @Modifying
    @Query(value = """
        INSERT INTO kyc_extended_member_leaf
            (programmable_token_policy_id, member_pkh, valid_until_ms, bound_address, kyc_session_id, added_at)
        VALUES
            (:policyId, :memberPkh, :validUntilMs, :boundAddress, :kycSessionId, :addedAt)
        ON CONFLICT (programmable_token_policy_id, member_pkh) DO UPDATE SET
            valid_until_ms  = EXCLUDED.valid_until_ms,
            bound_address   = EXCLUDED.bound_address,
            kyc_session_id  = EXCLUDED.kyc_session_id,
            added_at        = EXCLUDED.added_at
        """, nativeQuery = true)
    void upsertMember(
            @Param("policyId") String policyId,
            @Param("memberPkh") String memberPkh,
            @Param("validUntilMs") long validUntilMs,
            @Param("boundAddress") String boundAddress,
            @Param("kycSessionId") String kycSessionId,
            @Param("addedAt") Instant addedAt);
}
