package org.cardanofoundation.cip113.model;

public record TokenContextResponse(
        String policyId,
        String substandardId,
        String assetName,
        String blacklistNodePolicyId,
        String issuerAdminPkh,
        String blacklistInitTxHash,
        Integer blacklistInitOutputIndex,
        /** Security-token only: whether the on-chain validator requires the recipient
         *  to be in the allowlist. Null for substandards that don't carry this flag. */
        Boolean requiresReceiverKyc
) {}
