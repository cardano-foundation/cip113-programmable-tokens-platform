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
        Boolean requiresReceiverKyc,
        /** Security-token only: whether on-chain transfers are currently paused
         *  (set via the GlobalState {@code PauseTransfers} admin action). The FE
         *  uses this to disable the Send button + surface a notice. Null for
         *  substandards that don't carry this flag. */
        Boolean transfersPaused
) {}
