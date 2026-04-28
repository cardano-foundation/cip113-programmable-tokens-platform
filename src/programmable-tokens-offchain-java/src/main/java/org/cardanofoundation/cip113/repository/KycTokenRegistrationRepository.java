package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.KycTokenRegistrationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for KYC token registration data.
 */
@Repository
public interface KycTokenRegistrationRepository extends JpaRepository<KycTokenRegistrationEntity, String> {

    /**
     * Find token registration by programmable token policy ID.
     */
    Optional<KycTokenRegistrationEntity> findByProgrammableTokenPolicyId(String programmableTokenPolicyId);

    /**
     * Find all token registrations by issuer admin public key hash.
     */
    List<KycTokenRegistrationEntity> findByIssuerAdminPkh(String issuerAdminPkh);

    /**
     * Find all token registrations linked to a specific global state.
     */
    List<KycTokenRegistrationEntity> findByGlobalStateInit_GlobalStatePolicyId(String globalStatePolicyId);

    /**
     * Check if a token registration exists for the given policy ID.
     */
    boolean existsByProgrammableTokenPolicyId(String programmableTokenPolicyId);
}
