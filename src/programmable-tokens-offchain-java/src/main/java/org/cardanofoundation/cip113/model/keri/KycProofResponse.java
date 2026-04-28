package org.cardanofoundation.cip113.model.keri;

public record KycProofResponse(
        String payloadHex,
        String signatureHex,
        String entityVkeyHex,
        long validUntilPosixMs,
        int role,
        String roleName
) {}
