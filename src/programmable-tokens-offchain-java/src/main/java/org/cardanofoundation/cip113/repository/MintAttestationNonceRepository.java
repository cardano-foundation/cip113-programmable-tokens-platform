package org.cardanofoundation.cip113.repository;
import org.cardanofoundation.cip113.entity.MintAttestationNonceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
public interface MintAttestationNonceRepository extends JpaRepository<MintAttestationNonceEntity, String> {
    @Modifying
    @Query(value = "INSERT INTO mint_attestation_nonce (nonce, expires_at) VALUES (:nonce, :expires) ON CONFLICT DO NOTHING", nativeQuery = true)
    int consume(@Param("nonce") String nonce, @Param("expires") Instant expires);
}
