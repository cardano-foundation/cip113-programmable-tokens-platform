package org.cardanofoundation.cip113.core;

/**
 * Every CIP-113 <em>core</em> validator the backend resolves out of the blueprint.
 *
 * <p>This enum exists so that the blueprint title of a core validator appears in the
 * codebase exactly once. Before it, eight title literals were spread through
 * {@code ProtocolScriptBuilderService} and re-typed at each call site — which is why
 * renaming one (upstream dissolves {@code programmable_logic_global} into
 * {@code transfer} plus {@code third_party}) meant hunting string literals rather than
 * editing a table.
 *
 * <p>Titles are <em>derived</em>, not spelled out: aiken emits
 * {@code <module>.<validator>.<purpose>}, and in this contract repository the module and
 * validator names always coincide. That is an assumption about upstream's file layout
 * rather than a rule of the format, so {@link CoreBlueprint} verifies each derived title
 * actually resolves and fails loudly if one does not — the alternative being a validator
 * silently resolving to {@code null} and surfacing as a confusing NPE deep in a builder.
 *
 * <p>{@code publish} and {@code else} entries are deliberately absent. They share the
 * compiled code and hash of their family's primary purpose, so the backend never needs to
 * look them up separately; {@code CoreBlueprintSurfaceTest} is what asserts they are still
 * present in the blueprint.
 */
public enum CoreValidator {

    /** Spend validator locking every programmable-token UTxO. Its hash is the payment
     *  credential of every programmable address, which is why it is the one hash an
     *  upgrade must avoid moving if existing tokens are to survive. */
    PROGRAMMABLE_LOGIC_BASE("programmable_logic_base", Purpose.SPEND),

    /** Withdraw-0 validator carrying the transfer invariants — the hot path. Every ordinary
     *  programmable-token transfer references it, so every byte of it is a fee paid on every
     *  transfer forever, which is why it now carries nothing else.
     *
     *  <p>This was {@code programmable_logic_global}, a coordinator that dispatched
     *  transfer / third-party / unfracking itself. Upstream dissolved it: the transfer arm
     *  kept the logic and took this name, and the other two became the standalone validators
     *  below. Renaming it here was the entire off-chain cost of that rename, because nothing
     *  else in the backend names the title. */
    TRANSFER("transfer", Purpose.WITHDRAW),

    /** Withdraw-0 validator for the administrative path: seizure, clawback, freeze
     *  enforcement, burn. Split out of the coordinator so its (heavy, rarely used) bytes are
     *  not carried by every transfer's reference script.
     *
     *  <p>PLB reaches it only through a {@code SpendViaThirdParty} redeemer, naming it by the
     *  {@code third_party_cred} field of the live protocol-params datum. */
    THIRD_PARTY("third_party", Purpose.WITHDRAW),

    /** Withdraw-0 validator for holder-driven same-owner PLB restructuring (Finding 17).
     *  Deployed and named in the protocol-params datum so PLB can dispatch to it, but no
     *  builder in this backend invokes it yet. */
    UNFRACKING("unfracking", Purpose.WITHDRAW),

    /** Minting policy for programmable tokens. Parameterised per substandard, so its
     *  policy id IS the token's identity. */
    ISSUANCE_MINT("issuance_mint", Purpose.MINT),

    /** One-shot policy minting the {@code IssuanceCborHex} reference NFT, whose datum
     *  carries the {@code issuance_mint} template bytes that {@code registry_mint} checks
     *  a registration against. */
    ISSUANCE_CBOR_HEX_MINT("issuance_cbor_hex_mint", Purpose.MINT),

    /** Minting policy for registry-node NFTs (the linked-list registry of policies). */
    REGISTRY_MINT("registry_mint", Purpose.MINT),

    /** Spend validator for registry nodes: insertions and in-place field updates. */
    REGISTRY_SPEND("registry_spend", Purpose.SPEND),

    /** One-shot policy minting the protocol-params NFT, whose inline datum is the live
     *  wiring every other validator reads. */
    PROTOCOL_PARAMS_MINT("protocol_params_mint", Purpose.MINT),

    /** Spend validator guarding the coordination UTxO — the home of the protocol-params
     *  NFT and therefore the only place the live wiring can be rewritten. */
    COORDINATION_SPEND("coordination_spend", Purpose.SPEND),

    /** Reference upgrade authority: an M-of-N multisig, the initial target of the params
     *  datum's upgrade credential. Swappable, because the datum names it rather than
     *  {@code coordination_spend} being parameterised by it. */
    UPGRADE_MULTISIG("upgrade_multisig", Purpose.WITHDRAW),

    /** Unspendable script. No longer the coordination-UTxO lock target (that is
     *  {@code coordination_spend} since the upgradability work), but still parameterised
     *  by a nonce and used to derive per-deployment dead addresses. */
    ALWAYS_FAIL("always_fail", Purpose.SPEND);

    /** The blueprint purposes the backend resolves validators by. */
    public enum Purpose {
        SPEND("spend"),
        MINT("mint"),
        WITHDRAW("withdraw");

        private final String blueprintName;

        Purpose(String blueprintName) {
            this.blueprintName = blueprintName;
        }

        public String blueprintName() {
            return blueprintName;
        }
    }

    private final String module;
    private final Purpose purpose;

    CoreValidator(String module, Purpose purpose) {
        this.module = module;
        this.purpose = purpose;
    }

    /** The aiken module (and validator) name, e.g. {@code programmable_logic_base}. */
    public String module() {
        return module;
    }

    public Purpose purpose() {
        return purpose;
    }

    /** The blueprint title, e.g. {@code programmable_logic_base.programmable_logic_base.spend}. */
    public String title() {
        return module + "." + module + "." + purpose.blueprintName();
    }
}
