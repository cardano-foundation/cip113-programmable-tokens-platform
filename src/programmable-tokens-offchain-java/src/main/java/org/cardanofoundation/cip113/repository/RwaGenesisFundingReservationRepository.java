package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaGenesisFundingReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RwaGenesisFundingReservationRepository
        extends JpaRepository<RwaGenesisFundingReservationEntity, String> {
    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO rwa_genesis_funding_reservation (input_ref, global_state_policy_id, created_at)
        VALUES (:inputRef, :gsPolicy, CURRENT_TIMESTAMP)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int claim(@Param("inputRef") String inputRef, @Param("gsPolicy") String gsPolicy);
    @Modifying
    @Query("delete from RwaGenesisFundingReservationEntity r where r.inputRef = :inputRef and r.globalStatePolicyId = :gsPolicy")
    int releaseOwned(@Param("inputRef") String inputRef, @Param("gsPolicy") String gsPolicy);
}
