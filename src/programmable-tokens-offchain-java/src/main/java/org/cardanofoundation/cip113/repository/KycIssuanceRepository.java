package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.KycIssuanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface KycIssuanceRepository extends JpaRepository<KycIssuanceEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KycIssuanceEntity> findLockedBySessionId(String sessionId);
}
