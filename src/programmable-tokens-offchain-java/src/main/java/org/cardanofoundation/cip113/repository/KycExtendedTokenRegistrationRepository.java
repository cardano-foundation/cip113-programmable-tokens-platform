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

    /** Used by the /admin/tokens endpoint to surface every kyc-extended token a wallet
     *  administers. For kyc-extended the {@code issuerAdminPkh} is the BACKEND signing
     *  key (so the backend can autonomously publish UpdateMemberRootHash), so this
     *  finder returns rows only when the connected wallet IS the backend admin — rare
     *  in production but useful for dev/test runs. */
    List<KycExtendedTokenRegistrationEntity> findByIssuerAdminPkh(String issuerAdminPkh);

    /**
     * Newest-first listing for the {@code /verify} discovery index.
     * Tokens that have never published a root (lastRootUpdateAt == null)
     * sort last under JPA's default null-ordering on most providers.
     */
    List<KycExtendedTokenRegistrationEntity> findAllByOrderByLastRootUpdateAtDesc(Pageable pageable);
}
