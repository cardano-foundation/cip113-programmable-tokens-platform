package org.cardanofoundation.cip113.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
@Entity
@Table(name = "mint_attestation_nonce")
@Getter @Setter
public class MintAttestationNonceEntity {
    @Id @Column(length = 64) private String nonce;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
}
