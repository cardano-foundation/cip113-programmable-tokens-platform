package org.cardanofoundation.cip113.core;

/**
 * Every CIP-113 <em>core</em> validator the backend resolves out of the blueprint.
 *
 * <p>This enum exists so that the blueprint title of a core validator appears in the
 * codebase exactly once. Keeping that surface in one table makes upstream validator
 * additions and renames fail visibly instead of leaving string literals scattered through
 * transaction builders.
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

    /** Dispatcher invoked on every programmable transaction before the selected delegate. */
    PROGRAMMABLE_LOGIC_GLOBAL("programmable_logic_global", Purpose.WITHDRAW),

    /** Withdraw-0 validator carrying the ordinary transfer invariants. The alpha.4
     *  dispatcher selects this delegate for the transfer path. */
    TRANSFER("transfer", Purpose.WITHDRAW),

    /** Withdraw-0 validator for administrative actions such as seizure and clawback. The
     *  alpha.4 dispatcher selects it for the third-party path. */
    THIRD_PARTY("third_party", Purpose.WITHDRAW),

    /** Withdraw-0 validator for holder-driven same-owner PLB restructuring (Finding 17).
     *  Deployed and named in the protocol-params datum so PLB can dispatch to it, but no
     *  builder in this backend invokes it yet. */
    UNFRACKING("unfracking", Purpose.WITHDRAW),

    /** Replaceable issuance checks invoked by every mint and burn. */
    ISSUANCE_LOGIC("issuance_logic", Purpose.WITHDRAW),

    /** Minting policy for programmable tokens. Parameterised per substandard, so its
     *  policy id IS the token's identity. */
    ISSUANCE_MINT("issuance_mint", Purpose.MINT),

    /** One-shot policy minting the {@code IssuanceCborHex} reference NFT, whose datum
     *  carries the {@code issuance_mint} template bytes that {@code registry_mint} checks
     *  a registration against. */
    ISSUANCE_CBOR_HEX_MINT("issuance_cbor_hex_mint", Purpose.MINT),

    /** Merged registry mint/spend validator; one hash is both NFT policy and address. */
    REGISTRY("registry", Purpose.MINT),

    /** Merged protocol-params mint/spend validator; one hash is both NFT policy and address. */
    PROTOCOL_PARAMS("protocol_params", Purpose.MINT),

    /** Reference upgrade authority: an M-of-N multisig, the initial target of the params
     *  datum's upgrade credential. */
    UPGRADE_MULTISIG("upgrade_multisig", Purpose.WITHDRAW),

    /** Nonce-parameterised unspendable script used for per-deployment dead addresses. */
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
