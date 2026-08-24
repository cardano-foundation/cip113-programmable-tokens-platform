package org.cardanofoundation.cip113.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * Base class for token registration requests with polymorphic JSON deserialization.
 * The {@code substandardId} field acts as the discriminator for Jackson.
 *
 * <p>Each substandard defines its own request subtype with specific fields:</p>
 * <ul>
 *   <li>{@link DummyRegisterRequest} - No additional fields</li>
 *   <li>{@link FreezeAndSeizeRegisterRequest} - adminPubKeyHash, blacklistNodePolicyId</li>
 * </ul>
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "substandardId",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = DummyRegisterRequest.class, name = "dummy"),
        @JsonSubTypes.Type(value = FreezeAndSeizeRegisterRequest.class, name = "freeze-and-seize"),
        @JsonSubTypes.Type(value = KycRegisterRequest.class, name = "kyc"),
        @JsonSubTypes.Type(value = KycExtendedRegisterRequest.class, name = "kyc-extended"),
        @JsonSubTypes.Type(value = RwaTokenRegisterRequest.class, name = "rwa-token")
})
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public abstract class RegisterTokenRequest {

    /**
     * The substandard identifier (discriminator for polymorphic deserialization).
     */
    private String substandardId;

    /**
     * The address that pays for the transaction fees.
     */
    private String feePayerAddress;

    /**
     * The asset name for the programmable token (hex-encoded).
     */
    private String assetName;

    /**
     * The quantity of tokens to mint during registration.
     */
    private String quantity;

    /**
     * The recipient address for the minted tokens.
     */
    private String recipientAddress;

    /**
     * Optional tx hash that can be used to mempool chaining tx, it's assumed to have a few ada (10 ada+)
     */
    private String chainingTransactionCborHex;

    /**
     * Optional CIP-68 metadata. When present, registration mints a CIP-68 <em>pair</em> under one
     * policy instead of a single bare asset:
     * <ul>
     *   <li>the user token, its name prefixed with the CIP-67 {@code (333)} label — always
     *       {@code (333)}, whatever the quantity, because none of the pinned substandard
     *       contracts caps <em>lifetime</em> supply and only such a cap entitles a token to the
     *       {@code (222)} non-fungibility claim (see
     *       {@link org.cardanofoundation.cip113.util.Cip68#userTokenLabel}),</li>
     *   <li>a {@code (100)} reference token of quantity 1 whose inline datum carries this
     *       metadata, sent to the <em>admin's</em> programmable-logic-base address so the issuer
     *       can spend it later to update the metadata.</li>
     * </ul>
     *
     * <p>Note this changes {@code assetName} on chain, and for substandards that bake the asset
     * name into a script parameter (freeze-and-seize, rwa-token) it therefore changes the
     * resulting token policy id.
     *
     * <p>Supported by {@code dummy}, {@code freeze-and-seize} and {@code rwa-token}. The
     * {@code kyc} and {@code kyc-extended} handlers reject a non-null value rather than silently
     * dropping it.
     */
    private Cip68Metadata cip68Metadata;

}
