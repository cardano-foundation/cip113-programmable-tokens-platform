package org.cardanofoundation.cip113.model;

/**
 * Request to anchor a digest in the user's KEL for CIP-170 attestation.
 *
 * @param unit     The token unit (policyId + assetNameHex)
 * @param quantity The quantity being minted
 */
public record AttestAnchorRequest(String unit, String quantity) {
}
