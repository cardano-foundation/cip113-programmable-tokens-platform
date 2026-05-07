package org.cardanofoundation.cip113.model;

/**
 * Public summary of a registered kyc-extended token, used by the
 * {@code /verify} discovery index. No PII; safe to return without auth.
 */
public record KycExtendedTokenSummary(
        String policyId,
        String assetName,    // hex
        String displayName,  // utf8(assetName), or "<unnamed>" if decoding fails
        String description,  // optional CIP-68 description, may be null
        long registeredAt    // epoch ms; falls back to lastRootUpdateAt or 0
) {
}
