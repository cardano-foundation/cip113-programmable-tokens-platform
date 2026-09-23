package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "rwa_token_admin_request_nonce")
@Getter
@Setter
public class RwaTokenAdminRequestNonceEntity {
    @Id
    @Column(name = "nonce", length = 64)
    private String nonce;

    @Column(name = "token_policy_id", nullable = false, length = 56)
    private String tokenPolicyId;

    @Column(name = "admin_hash", nullable = false, length = 56)
    private String adminHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
