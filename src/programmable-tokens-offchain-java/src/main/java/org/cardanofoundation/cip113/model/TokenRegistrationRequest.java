package org.cardanofoundation.cip113.model;

/**
 * Request to register a token in the backend DB after SDK-built on-chain registration.
 * This is a DB-only callback — no transaction building.
 */
public record TokenRegistrationRequest(
        /** Policy ID of the programmable token */
        String policyId,
        /** Substandard identifier (e.g., "dummy", "freeze-and-seize") */
        String substandardId,
        /** Hex-encoded asset name */
        String assetName,
        /** Issuer admin PKH (FES only, nullable) */
        String issuerAdminPkh,
        /** Blacklist node policy ID (FES only, nullable) */
        String blacklistNodePolicyId,
        /** Blacklist admin PKH — for blacklist init insertion (FES only, nullable) */
        String blacklistAdminPkh,
        /** Bootstrap UTxO tx hash consumed by blacklist one-shot mint (FES only, nullable) */
        String blacklistInitTxHash,
        /** Bootstrap UTxO output index (FES only, nullable) */
        Integer blacklistInitOutputIndex,
        /**
         * Whether the blacklist init this callback inserts was built for a CIP-68 (CIP-67-labelled)
         * asset name (FES only, nullable).
         *
         * <p>The init transaction is the only place {@code issuer_admin}'s reward account is
         * registered, and {@code issuer_admin} is parameterized by the asset name — so a CIP-68
         * init and a non-CIP-68 registration resolve different reward addresses and the
         * registration is rejected on chain with {@code WithdrawalsNotInRewardsCERTS}, after the
         * init is already paid for. Recording what the init actually did lets registration refuse
         * before building.
         *
         * <p>{@code null} means the caller did not say. That is deliberately distinct from
         * {@code false}: with no evidence the cross-check stays silent rather than rejecting a
         * registration that may well be correct.
         */
        Boolean cip68Enabled
) {}
