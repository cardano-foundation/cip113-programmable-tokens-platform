package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaTokenAdminRequestNonceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface RwaTokenAdminRequestNonceRepository extends JpaRepository<RwaTokenAdminRequestNonceEntity, String> {
    @Modifying
    @Query(value = """
        INSERT INTO rwa_token_admin_request_nonce (nonce, token_policy_id, admin_hash, expires_at)
        VALUES (:nonce, :tokenPolicyId, :adminHash, :expiresAt)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int consume(@Param("nonce") String nonce, @Param("tokenPolicyId") String tokenPolicyId,
                @Param("adminHash") String adminHash, @Param("expiresAt") Instant expiresAt);
}
