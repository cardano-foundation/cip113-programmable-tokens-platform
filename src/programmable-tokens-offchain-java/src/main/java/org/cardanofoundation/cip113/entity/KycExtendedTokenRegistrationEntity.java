package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "kyc_extended_token_registration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycExtendedTokenRegistrationEntity {

    @Id
    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "issuer_admin_pkh", nullable = false, length = 56)
    private String issuerAdminPkh;

    @Column(name = "tel_policy_id", nullable = false, length = 56)
    private String telPolicyId;

    @Column(name = "member_root_hash_onchain", length = 64)
    private String memberRootHashOnchain;

    @Column(name = "member_root_hash_local", length = 64)
    private String memberRootHashLocal;

    @Column(name = "last_root_update_tx_hash", length = 64)
    private String lastRootUpdateTxHash;

    @Column(name = "last_root_update_at")
    private Instant lastRootUpdateAt;
}
