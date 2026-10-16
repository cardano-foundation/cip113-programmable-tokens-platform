package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "rwa_token_registration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RwaTokenRegistrationEntity {

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

    /** Hex asset name of the RWA token (mirror of programmable_token_registry.asset_name,
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

    /** Output index of the minting AUTHORITY reference script (not the proxy — the
     *  proxy is only 1 128 bytes and rides inline). See V18. */
    @Column(name = "minting_authority_ref_index")
    private Integer mintingAuthorityRefIndex;

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

    /** Whether a cert transaction registering the {@code minting_authority} reward
     *  account has been built. Every mint, burn and registration withdraws 0 from it
     *  in addition to the proxy's account, and an unregistered reward account fails
     *  phase-1 at submit — which script evaluation cannot catch. */
    @Column(name = "minting_authority_reward_registered", nullable = false)
    @Builder.Default
    private boolean mintingAuthorityRewardRegistered = false;

    /** The minting authority this token was DEPLOYED with, written once at genesis.
     *
     *  <p><b>Provenance only — deliberately not kept in sync.</b> The live value is
     *  {@code GlobalStateDatum.minting_script_credential_hash}: the proxy reads it from
     *  the datum, every builder reads it from the fetched GS UTxO, and the admin UI reads
     *  it from {@code /global-state}. {@code RotateMintingScript} does not touch this
     *  column, so after a rotation it records the ORIGINAL authority while the datum
     *  records the current one — which is the point, since it answers "what was this
     *  token issued under" and the chain no longer can.
     *
     *  <p>Never read it to decide anything about a transaction: anything needing the
     *  CURRENT authority must read the datum, or it builds against a script the proxy no
     *  longer delegates to. */
    @Column(name = "minting_authority_hash", length = 56)
    private String mintingAuthorityHash;

    /** The GENESIS {@code max_global_state_datum_bytes}, applied to the GlobalState mint and
     *  the v1 logic. Needed to re-derive both; NULL only on rows from before V34, whose
     *  GlobalState the PR #29 builders cannot spend anyway. See V34. */
    @Column(name = "max_gs_datum_bytes")
    private Integer maxGsDatumBytes;

    /** JSON list of {@link org.cardanofoundation.cip113.model.GsLogicEntry}: every GlobalState
     *  logic this platform built for the token (genesis v1, then each rotation target), with
     *  its applied bytes, cap and reference-script location. Every GlobalState write looks up
     *  datum field 0 here and refuses when it is absent. Read and write it only through
     *  {@code GsLogicEntry}. See V34. */
    @Column(name = "gs_logics", columnDefinition = "TEXT")
    private String gsLogics;

    /** Optimistic lock (plan Addendum I13). {@code gs_logics} is a read-modify-write JSON list:
     *  a lost update could drop the entry GlobalState's field 0 names, after which every
     *  GlobalState write refuses (UNKNOWN LOGIC). Null on a builder-made row means "new"; rows
     *  from before V34 load the column default 0. */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
