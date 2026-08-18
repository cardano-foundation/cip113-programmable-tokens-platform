package org.cardanofoundation.cip113.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/** Registration request for the "security-token" substandard. */
@Data
@EqualsAndHashCode(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityTokenRegisterRequest extends RegisterTokenRequest {

    /** PKH of the issuer admin. The backend's signing key must match so it can
     *  autonomously sign {@code UpdateMemberRootHash} transactions. */
    private String adminPubKeyHash;

    /** Policy id of the global-state NFT. Carries the MPF root hash + the
     *  {@code requires_receiver_kyc} flag on chain. */
    private String globalStatePolicyId;

    /** Policy id of the denylist linked-list root NFT. */
    private String denylistPolicyId;

    /** Policy id of the power-users linked-list root NFT. */
    private String powerUsersPolicyId;

    /** Whether transfers to a recipient that has not completed KYC are blocked at
     *  registration time. Can be toggled later via the global-state spend action
     *  {@code SetRequiresReceiverKyc}.
     *
     *  <p>NOTE (upstream fn-bafin-cardano-sc @7ae4ce3): this single flag gates
     *  BOTH the sender-side and the receiver-side KYC check —
     *  {@code transfer_logic_script.ak} reads {@code requires_receiver_kyc} in
     *  the per-sender loop as well. See {@link #requiresSenderKyc}. */
    private boolean requiresReceiverKyc;

    /** Whether senders must present a valid KYC proof. Written into the
     *  {@code GlobalStateDatum} at genesis and toggleable via the
     *  {@code SetRequiresSenderKyc} spend action.
     *
     *  <p>WARNING: at the pinned upstream revision (7ae4ce3) no validator reads
     *  this field — {@code transfer_logic_script.ak:109} gates the *sender* KYC
     *  check on {@code requires_receiver_kyc}. Setting this has no on-chain
     *  effect today; it is carried so the datum shape matches upstream exactly.
     *  Reported upstream; revisit when the pin moves. */
    private boolean requiresSenderKyc;

    /** Initial mintable cap for the security-token policy. Decremented on every
     *  {@code MintSecurity} spend action; once it hits zero, no more tokens can
     *  be issued <em>until some are burned</em>. Defaults to 0 (no minting until
     *  admin updates) if not provided.
     *
     *  <p>This is a cap on the amount OUTSTANDING, not on lifetime issuance.
     *  {@code global_state.ak:229-245} computes
     *  {@code remaining = mintable_amount - minted_amount} with a signed
     *  {@code minted_amount}, so a burn passes a negative and restores the allowance.
     *  Consequently a cap of 1 does <strong>not</strong> make the token non-fungible
     *  and never selects a CIP-67 {@code (222)} label — see
     *  {@link org.cardanofoundation.cip113.util.Cip68#userTokenLabel}. */
    private Long initialMintableAmount;

    /** Optional initial supply minted BY the registration transaction itself, as a
     *  decimal string. Defaults to {@code "0"} — a structural registration that only
     *  inserts the CIP-113 directory entry and mints no security tokens.
     *
     *  <p>When set above zero, {@code buildFullRegistrationChain}'s registration tx
     *  additionally SPENDS the GlobalState UTxO under {@code MintSecurity} (so
     *  {@code initialMintableAmount} is decremented by this quantity), references the
     *  power-user node created by the preceding {@code AddPowerUser} tx, and carries a
     *  destination action for the recipient. Must not exceed
     *  {@link #initialMintableAmount}: the supply cap is enforced on chain by
     *  {@code global_state.ak}, which rejects a negative remainder.
     *
     *  <p>This — not {@code quantity} — is the field the chained registration honours.
     *  {@code buildFullRegistrationChain} overwrites {@code quantity} with this value
     *  before delegating, so anything a client sends in {@code quantity} is discarded.
     *
     *  <p>Two further preconditions are checked up front, before the chain writes any
     *  database row: {@link #bootstrapPowerUserPkh} must equal the fee payer's payment
     *  credential (the registration tx names the power user in {@code required_signers},
     *  and only the fee payer can witness it), and {@link #requiresReceiverKyc} must be
     *  off unless the recipient's STAKE credential IS that power user — genesis writes
     *  {@code member_root_hash} empty and no root can be published beforehand, so any
     *  other recipient has no provable membership. */
    private String initialMintQuantity;

    /** OPT-IN: seed the compliance allowlist at genesis with the mint recipient's stake
     *  credential, and write the resulting MPF root into the GlobalState datum's
     *  {@code member_root_hash}.
     *
     *  <p>Why it exists. {@code requires_receiver_kyc = true} plus an empty
     *  {@code member_root_hash} makes a registration-with-mint impossible for any
     *  ordinary recipient: {@code verify_mint_destinations} needs a membership proof and
     *  there is nothing to prove against, while the contract's self-mint exemption
     *  compares the recipient's STAKE credential against the power-user node key — which
     *  this chain sets to the wallet's PAYMENT key hash, so for a normal base address it
     *  can never fire. No root can be published beforehand either: publishing one is a
     *  GlobalState spend, and the GlobalState UTxO does not exist until genesis.
     *
     *  <p><b>Compliance meaning.</b> Setting this enrolls the recipient in the token's
     *  KYC allowlist on the issuer's say-so, with no KYC process behind it. The
     *  resulting on-chain {@code member_root_hash} asserts to every later validator run
     *  — transfers included, not just this mint — that the recipient is a verified
     *  member. It is off by default and the UI labels it as such; nothing in the
     *  platform infers it.
     *
     *  <p><b>Ignored unless it is actually needed</b> — i.e. unless
     *  {@link #requiresReceiverKyc} is on AND {@link #initialMintQuantity} is above
     *  zero. Honouring it otherwise would write a live compliance assertion into the
     *  datum for a token that never needed one, and one the UI would not be showing a
     *  control for. */
    private boolean seedRecipientInAllowlistAtGenesis;

    /** Optional: bootstrap power user inserted into the off-chain DB at registration.
     *  Lets the registering admin see themselves on the admin page immediately.
     *  Defaults to {@code adminPubKeyHash} with all-capabilities if not provided
     *  separately. The on-chain insertion via {@code AddPowerUser} is a separate tx
     *  the admin triggers from the admin page once the linked-list root is in place. */
    private String bootstrapPowerUserPkh;
    private Integer bootstrapPowerUserCapabilities;
    private String bootstrapPowerUserLabel;

    /** Optional CIP-170 attestation data to attach as ATTEST metadata (label 170). */
    private Cip170AttestationData attestation;

    /** Optional initial trusted-entity Ed25519 vkeys (32-byte hex each).
     *  These are baked into the GS datum's {@code trusted_entity_vkeys} at
     *  genesis so KYC proofs from these issuers verify on chain immediately —
     *  no separate {@code AddTrustedEntity} update tx needed before the first
     *  transfer. Typically includes the backend's own KERI signing-entity
     *  vkey so locally-issued KYC proofs work out of the box. */
    private List<String> initialTrustedEntityVkeys;
}
