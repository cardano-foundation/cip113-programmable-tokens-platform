package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaGenesisReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface RwaGenesisReservationRepository extends JpaRepository<RwaGenesisReservationEntity, String> {
    boolean existsByBootstrapTxHashAndBootstrapOutputIndex(String txHash, int outputIndex);

    @Modifying
    @Transactional
    @Query(value = """
        INSERT INTO rwa_genesis_reservation
            (global_state_policy_id, bootstrap_tx_hash, bootstrap_output_index)
        VALUES (:gsPolicy, :txHash, :outputIndex)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int claim(@Param("gsPolicy") String gsPolicy,
              @Param("txHash") String txHash,
              @Param("outputIndex") int outputIndex);
}
