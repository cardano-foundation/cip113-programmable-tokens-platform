package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KycExtendedTokenRegistrationRepository
        extends JpaRepository<KycExtendedTokenRegistrationEntity, String> {

    Optional<KycExtendedTokenRegistrationEntity> findByProgrammableTokenPolicyId(String policyId);

    boolean existsByProgrammableTokenPolicyId(String policyId);

    /**
     * Newest-first listing for the {@code /verify} discovery index.
     * Tokens that have never published a root (lastRootUpdateAt == null)
     * sort last under JPA's default null-ordering on most providers.
     */
    List<KycExtendedTokenRegistrationEntity> findAllByOrderByLastRootUpdateAtDesc(Pageable pageable);
}
