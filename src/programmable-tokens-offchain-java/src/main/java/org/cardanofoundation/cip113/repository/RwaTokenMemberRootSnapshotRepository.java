package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RwaTokenMemberRootSnapshotRepository extends JpaRepository<RwaTokenMemberRootSnapshotEntity, Long> {
    Optional<RwaTokenMemberRootSnapshotEntity> findByProgrammableTokenPolicyIdAndRootHash(
            String policyId, String rootHash);
}
