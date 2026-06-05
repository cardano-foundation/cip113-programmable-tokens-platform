package org.cardanofoundation.cip113.model;

/** Discovery-list entry for a registered security-token. */
public record SecurityTokenSummary(
        String policyId,
        String assetName,        // hex
        String displayName,      // utf8(assetName) with <unnamed> fallback
        String description,      // optional; null in v1
        boolean requiresReceiverKyc,
        long registeredAt        // epoch ms (0 if never published)
) {}
