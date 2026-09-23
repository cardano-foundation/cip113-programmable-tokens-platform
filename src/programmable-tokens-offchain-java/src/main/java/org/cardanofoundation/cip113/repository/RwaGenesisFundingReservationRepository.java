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
        INSERT INTO rwa_genesis_funding_reservation (input_ref, global_state_policy_id)
        VALUES (:inputRef, :gsPolicy)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int claim(@Param("inputRef") String inputRef, @Param("gsPolicy") String gsPolicy);
}
