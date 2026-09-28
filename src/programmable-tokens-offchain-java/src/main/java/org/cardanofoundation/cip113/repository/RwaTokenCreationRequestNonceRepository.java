package org.cardanofoundation.cip113.repository;

import org.cardanofoundation.cip113.entity.RwaTokenCreationRequestNonceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface RwaTokenCreationRequestNonceRepository extends JpaRepository<RwaTokenCreationRequestNonceEntity, String> {
    @Modifying
    @Query(value = """
        INSERT INTO rwa_token_creation_request_nonce (nonce, payer_hash, expires_at)
        VALUES (:nonce, :payerHash, :expiresAt)
        ON CONFLICT DO NOTHING
        """, nativeQuery = true)
    int consume(@Param("nonce") String nonce, @Param("payerHash") String payerHash,
                @Param("expiresAt") Instant expiresAt);
}
