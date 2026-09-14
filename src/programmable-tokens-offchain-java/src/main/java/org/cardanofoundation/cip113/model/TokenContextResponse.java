package org.cardanofoundation.cip113.model;

public record TokenContextResponse(
        String policyId,
        String substandardId,
        String assetName,
        String blacklistNodePolicyId,
        String issuerAdminPkh,
        String blacklistInitTxHash,
        Integer blacklistInitOutputIndex,
        /** RWA-token only: whether the on-chain validator requires the recipient
         *  to be in the allowlist. Null for substandards that don't carry this flag. */
        Boolean requiresReceiverKyc,
        /** RWA-token only: whether the on-chain validator requires the SENDER
         *  to be in the allowlist. Independent of {@link #requiresReceiverKyc} —
         *  transfer_logic_script.ak:123 reads requires_sender_kyc for the per-sender
         *  loop and :157 reads requires_receiver_kyc for the per-destination loop.
         *  Null for substandards that don't carry this flag. */
        Boolean requiresSenderKyc,
        /** RWA-token only: whether on-chain transfers are currently paused
         *  (set via the GlobalState {@code PauseTransfers} admin action). The FE
         *  uses this to disable the Send button + surface a notice. Null for
         *  substandards that don't carry this flag. */
        Boolean transfersPaused,

        /**
         * The token's transfer-logic script hash, as the registry node records it.
         *
         * <p>Exposed so a client can ask a CIP-171 registry what this script was built from.
         * Null — not absent, not "" — when the registry node has not been indexed: a client
         * must be able to tell "no provenance published" from "we have not seen this token",
         * and an empty string would collapse the two.
         */
        String transferLogicScript
) {}
