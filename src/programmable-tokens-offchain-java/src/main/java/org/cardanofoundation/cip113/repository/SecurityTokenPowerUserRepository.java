package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.SecurityTokenPowerUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SecurityTokenPowerUserRepository
        extends JpaRepository<SecurityTokenPowerUserEntity, Long> {

    List<SecurityTokenPowerUserEntity> findByProgrammableTokenPolicyId(String policyId);

    /** Used by /admin/tokens to surface every security-token where the connected
     *  wallet is a power user (admin / minter / burner / pauser / force-transfer).
     *  The returned capabilities bitfield drives per-page filtering on the
     *  frontend (mint page → MINTER|ADMIN required, etc.). */
    List<SecurityTokenPowerUserEntity> findByPowerUserPkh(String powerUserPkh);

    Optional<SecurityTokenPowerUserEntity> findByProgrammableTokenPolicyIdAndPowerUserPkh(
            String policyId, String powerUserPkh);

    boolean existsByProgrammableTokenPolicyIdAndPowerUserPkh(String policyId, String powerUserPkh);

    void deleteByProgrammableTokenPolicyIdAndPowerUserPkh(String policyId, String powerUserPkh);
}
