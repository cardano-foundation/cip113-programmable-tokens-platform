package org.cardanofoundation.cip113.model;

public record TokenContextResponse(
        String policyId,
        String moduleId,
        String assetName,
        String blacklistNodePolicyId,
        String issuerAdminPkh,
        String blacklistInitTxHash,
        Integer blacklistInitOutputIndex,
        /** RWA-token only: whether the on-chain validator requires the recipient
         *  to be in the allowlist. Null for modules that don't carry this flag. */
        Boolean requiresReceiverKyc,
        /** RWA-token only: whether the on-chain validator requires the SENDER
         *  to be in the allowlist. Independent of {@link #requiresReceiverKyc} —
         *  transfer_logic_script.ak:123 reads requires_sender_kyc for the per-sender
         *  loop and :157 reads requires_receiver_kyc for the per-destination loop.
         *  Null for modules that don't carry this flag. */
        Boolean requiresSenderKyc,
        /** RWA-token only: whether on-chain transfers are currently paused
         *  (set via the GlobalState {@code PauseTransfers} admin action). The FE
         *  uses this to disable the Send button + surface a notice. Null for
         *  modules that don't carry this flag. */
        Boolean transfersPaused,

        /**
         * The token's transfer-logic script hash, as the registry node records it.
         *
         * <p>Exposed so a client can ask a CIP-171 registry what this script was built from.
         * Null — not absent, not "" — when the registry node has not been indexed: a client
         * must be able to tell "no provenance published" from "we have not seen this token",
         * and an empty string would collapse the two.
         */
        String transferLogicScript,

        /**
         * The admin key hash recorded by the blacklist init, when there is one.
         *
         * <p>Exposed because it is the SAME value as {@code issuerAdminPkh} — both come from the
         * one admin PKH the registration built its scripts with — and rows written before
         * 2026-09-15 have the correct value HERE while {@code issuerAdminPkh} holds the wallet's
         * first used address, which on a multi-address wallet is a different key.
         *
         * <p>A client can therefore repair such a row without re-registering the token. It must
         * only ever accept this value after DERIVING the token's policy id from it and finding
         * it matches, which is something only a client holding the blueprint can do — so this
         * field is offered as a candidate, never as an answer.
         */
        String blacklistAdminPkh,

        /**
         * The token's CIP-68 metadata, read back from its reference token, or null.
         *
         * <p>Null is not "this token has no metadata" — see {@link #cip68Status}, which says which
         * of three things happened. A client that renders null as "no metadata" will tell a user
         * their token published nothing when the truth may be that this backend has not indexed
         * the reference token yet.
         */
        org.cardanofoundation.cip113.model.Cip68Metadata cip68Metadata,

        /**
         * Why {@link #cip68Metadata} is null, when it is: NOT_CIP68, REFERENCE_TOKEN_NOT_FOUND or
         * NO_READABLE_DATUM. Null when metadata was found.
         */
        String cip68Status
) {}
