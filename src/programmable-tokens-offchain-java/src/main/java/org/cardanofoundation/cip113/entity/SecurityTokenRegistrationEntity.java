package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "security_token_registration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityTokenRegistrationEntity {

    @Id
    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    @Column(name = "issuer_admin_pkh", nullable = false, length = 56)
    private String issuerAdminPkh;

    @Column(name = "global_state_policy_id", nullable = false, length = 56)
    private String globalStatePolicyId;

    @Column(name = "denylist_policy_id", nullable = false, length = 56)
    private String denylistPolicyId;

    @Column(name = "power_users_policy_id", nullable = false, length = 56)
    private String powerUsersPolicyId;

    @Column(name = "requires_receiver_kyc", nullable = false)
    @Builder.Default
    private boolean requiresReceiverKyc = true;

    /** Hex asset name of the security token (mirror of programmable_token_registry.asset_name,
     *  copied here so script rebuilds don't need a join). */
    @Column(name = "security_asset_name_hex", length = 64)
    private String securityAssetNameHex;

    /** Bootstrap UTxO consumed by the genesis tx — used as {@code (tx0, index0)}
     *  for the GS mint validator and as {@code init_input_out_ref} for the
     *  denylist + power-users mint validators. Stored so the spend scripts can be
     *  rebuilt verbatim at later spend time. */
    @Column(name = "bootstrap_tx_hash", length = 64)
    private String bootstrapTxHash;

    @Column(name = "bootstrap_output_index")
    private Integer bootstrapOutputIndex;

    @Column(name = "member_root_hash_onchain", length = 64)
    private String memberRootHashOnchain;

    @Column(name = "member_root_hash_local", length = 64)
    private String memberRootHashLocal;

    @Column(name = "last_root_update_tx_hash", length = 64)
    private String lastRootUpdateTxHash;

    @Column(name = "last_root_update_at")
    private Instant lastRootUpdateAt;
}
