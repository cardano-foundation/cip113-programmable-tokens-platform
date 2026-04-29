package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.GlobalStateInitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for KYC global state initialization data.
 */
@Repository
public interface GlobalStateInitRepository extends JpaRepository<GlobalStateInitEntity, String> {

    /**
     * Find global state init by policy ID.
     */
    Optional<GlobalStateInitEntity> findByGlobalStatePolicyId(String globalStatePolicyId);

    /**
     * Find all global state inits by admin public key hash.
     */
    List<GlobalStateInitEntity> findByAdminPkh(String adminPkh);
}
