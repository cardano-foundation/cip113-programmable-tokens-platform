package org.cardanofoundation.cip113.model;

/**
 * Request to mint programmable tokens.
 * The handler knows its contract names internally, so no script names are required.
 * The substandard is resolved from tokenPolicyId via the programmable token registry.
 *
 * @param feePayerAddress  The address that pays for the transaction
 * @param tokenPolicyId    The policy ID of the programmable token (used to resolve substandard)
 * @param assetName        The asset name (hex-encoded)
 * @param quantity         The quantity to mint (positive) or burn (negative)
 * @param recipientAddress The recipient address for minted tokens (defaults to feePayerAddress if null)
 * @param attestation      Optional CIP-170 attestation data (nullable)
 * @param cip68Metadata    Optional CIP-68 metadata. When present, this mint additionally mints the
 *                         {@code (100)} reference token (quantity 1) carrying the metadata as an
 *                         inline datum, to the admin's programmable-logic-base address.
 *                         <p>This exists for {@code security-token}, whose registration is
 *                         structurally mint-free and whose registration path rejects a second asset
 *                         name under the policy — so its reference token has to be minted here, in a
 *                         {@code MintBurn} transaction, alongside a user-token mint. For
 *                         {@code dummy} and {@code freeze-and-seize} the pair is minted at
 *                         registration and this field is normally null.
 *                         <p>{@code assetName} must already carry its {@code (222)}/{@code (333)}
 *                         label; the reference token's name is derived by re-labelling that base.
 */
public record MintTokenRequest(
        String feePayerAddress,
        String tokenPolicyId,
        String assetName,
        String quantity,
        String recipientAddress,
        Cip170AttestationData attestation,
        Cip68Metadata cip68Metadata) {

    /** Convenience overload for the common mint/burn with no CIP-68 reference token to create. */
    public MintTokenRequest(String feePayerAddress,
                            String tokenPolicyId,
                            String assetName,
                            String quantity,
                            String recipientAddress,
                            Cip170AttestationData attestation) {
        this(feePayerAddress, tokenPolicyId, assetName, quantity, recipientAddress, attestation, null);
    }
}
