package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecurityTokenRegistrationRepository
        extends JpaRepository<SecurityTokenRegistrationEntity, String> {

    Optional<SecurityTokenRegistrationEntity> findByProgrammableTokenPolicyId(String policyId);

    /** Used by the /admin/tokens endpoint to surface every security-token a wallet
     *  administers. {@code issuerAdminPkh} is the on-chain admin (BaFin
     *  {@code admin_credential_hash}), set at genesis init to the user's wallet PKH. */
    List<SecurityTokenRegistrationEntity> findByIssuerAdminPkh(String issuerAdminPkh);

    /** Used by the registration flow: at register-time the frontend only knows
     *  the GS policy id (returned from genesis init), not the prog-token policy id. */
    Optional<SecurityTokenRegistrationEntity> findByGlobalStatePolicyId(String globalStatePolicyId);

    boolean existsByProgrammableTokenPolicyId(String policyId);

    /** Newest-first listing for the discovery / verify index. */
    List<SecurityTokenRegistrationEntity> findAllByOrderByLastRootUpdateAtDesc(Pageable pageable);
}
