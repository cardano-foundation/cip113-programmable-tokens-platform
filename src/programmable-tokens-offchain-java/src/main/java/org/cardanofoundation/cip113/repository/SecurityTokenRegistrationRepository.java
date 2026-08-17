package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Atomically claim the right to mint this token's CIP-68 {@code (100)} reference token.
     *
     * <p>A read-then-write on {@code cip68ReferenceMinted} is not enough. Two concurrent mint
     * builds both read {@code false}, both build a transaction that mints the reference token,
     * and both then write {@code true} — and CIP-68 permits exactly one reference token per
     * policy, with no way to recover from two: every consumer picks one of the pair arbitrarily.
     *
     * <p>This is the compare-and-set that makes exactly one of them win. It is issued
     * <em>after</em> the transaction is built and <em>before</em> the CBOR is handed back, so a
     * build that fails for any other reason leaves the flag alone and can be retried, while the
     * loser of a genuine race is refused without ever receiving a signable transaction.
     *
     * @return 1 if this caller claimed the mint, 0 if it was already claimed
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SecurityTokenRegistrationEntity r set r.cip68ReferenceMinted = true "
           + "where r.programmableTokenPolicyId = :policyId and r.cip68ReferenceMinted = false")
    int claimCip68ReferenceMint(@Param("policyId") String policyId);
}
