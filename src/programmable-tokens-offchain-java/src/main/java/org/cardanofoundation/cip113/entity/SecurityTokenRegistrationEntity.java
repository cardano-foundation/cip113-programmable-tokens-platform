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

    /** CIP-68 metadata captured at genesis, as JSON, or null when this token is not CIP-68.
     *  Replayed into the {@code (100)} reference token's datum by the first mint — the
     *  registration path cannot carry that token, so the metadata has to survive the gap
     *  between the wizard and the mint page. */
    @Column(name = "cip68_metadata_json", columnDefinition = "TEXT")
    private String cip68MetadataJson;

    /** True once the {@code (100)} reference token has been minted. CIP-68 allows exactly one,
     *  so this blocks a second. */
    @Column(name = "cip68_reference_minted", nullable = false)
    @Builder.Default
    private boolean cip68ReferenceMinted = false;
}
