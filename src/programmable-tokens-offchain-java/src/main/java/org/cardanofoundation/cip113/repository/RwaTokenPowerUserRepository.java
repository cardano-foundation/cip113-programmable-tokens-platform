package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaTokenPowerUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RwaTokenPowerUserRepository
        extends JpaRepository<RwaTokenPowerUserEntity, Long> {

    List<RwaTokenPowerUserEntity> findByProgrammableTokenPolicyId(String policyId);

    /** Used by /admin/tokens to surface every rwa-token where the connected
     *  wallet is a power user (admin / minter / burner / pauser / force-transfer).
     *  The returned capabilities bitfield drives per-page filtering on the
     *  frontend (mint page → MINTER|ADMIN required, etc.). */
    List<RwaTokenPowerUserEntity> findByPowerUserPkh(String powerUserPkh);

    Optional<RwaTokenPowerUserEntity> findByProgrammableTokenPolicyIdAndPowerUserPkh(
            String policyId, String powerUserPkh);

    boolean existsByProgrammableTokenPolicyIdAndPowerUserPkh(String policyId, String powerUserPkh);

    void deleteByProgrammableTokenPolicyIdAndPowerUserPkh(String policyId, String powerUserPkh);
}
