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
    /** Where this token's parameterized validators were published as reference scripts.
     *  All three share one publish transaction, so the hash is stored once. NULL means
     *  they were never published and a burn cannot be built — see V15. */
    @Column(name = "ref_scripts_tx_hash", length = 64)
    private String refScriptsTxHash;

    @Column(name = "minting_logic_ref_index")
    private Integer mintingLogicRefIndex;

    @Column(name = "gs_spend_ref_index")
    private Integer gsSpendRefIndex;

    /** A DIFFERENT transaction from {@link #refScriptsTxHash}: publishing all three
     *  scripts together came to 18 273 bytes, so this one rides on the cert tx that
     *  registers its reward account. */
    @Column(name = "third_party_ref_tx_hash", length = 64)
    private String thirdPartyRefTxHash;

    @Column(name = "third_party_transfer_logic_ref_index")
    private Integer thirdPartyTransferLogicRefIndex;

    /** Whether a cert transaction registering the third_party_transfer_logic reward
     *  account has been built. The burn withdraws 0 from it, and an unregistered
     *  reward account fails phase-1 at submit — which script evaluation cannot catch. */
    @Column(name = "third_party_reward_registered", nullable = false)
    @Builder.Default
    private boolean thirdPartyRewardRegistered = false;

    @Column(name = "cip68_reference_minted", nullable = false)
    @Builder.Default
    private boolean cip68ReferenceMinted = false;
}
