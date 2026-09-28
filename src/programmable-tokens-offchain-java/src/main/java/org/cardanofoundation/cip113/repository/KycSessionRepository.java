package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public interface KycSessionRepository extends JpaRepository<KycSessionEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<KycSessionEntity> findLockedBySessionId(String sessionId);
}
