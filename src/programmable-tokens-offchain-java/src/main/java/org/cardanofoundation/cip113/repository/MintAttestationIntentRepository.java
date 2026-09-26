package org.cardanofoundation.cip113.repository;

import jakarta.persistence.LockModeType;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface MintAttestationIntentRepository extends JpaRepository<MintAttestationIntentEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from MintAttestationIntentEntity i where i.id = :id")
    Optional<MintAttestationIntentEntity> findLockedById(String id);
    List<MintAttestationIntentEntity> findAllByDigestOrderByIdAsc(String digest);

    @Query("select i.id from MintAttestationIntentEntity i where i.initialRegistrationJson is not null " +
            "and i.initialPlanJson is not null and i.expiresAt < :now and i.id > :after " +
            "and i.status in :statuses order by i.id asc")
    List<String> findExpiredInitialIds(@Param("now") Instant now, @Param("after") String after,
                                       @Param("statuses") List<String> statuses, Pageable page);
}
