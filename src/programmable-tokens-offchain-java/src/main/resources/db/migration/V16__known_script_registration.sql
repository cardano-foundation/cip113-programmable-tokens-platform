-- ============================================================================
-- Script stake credentials this deployment knows to be registered
-- ============================================================================
-- Deciding whether a script stake credential is already registered has no
-- reliable oracle, and being wrong is fatal in both directions: registering an
-- existing credential is rejected with StakeKeyAlreadyRegisteredDELEG ("Trying
-- to re-register some already known credentials"), and skipping a missing one is
-- rejected with WithdrawalsNotInRewardsCERTS. Both happen at submit, after the
-- user has signed, and neither is catchable by script evaluation -- reward
-- account existence is a ledger rule, not a Plutus one.
--
-- Two sources exist and both can be blind:
--
--   * The account endpoint. Not implemented by every backend -- a devkit devnet
--     answers 404 for /accounts and even /blocks/latest -- so "not successful"
--     cannot be read as "not registered".
--
--   * Our own indexed certificates. The stake_registration table is populated
--     from sync-start-slot and starts empty again after any database reset, so
--     it holds a WINDOW of history, not all of it. Measured on the devnet that
--     produced this migration: earliest indexed certificate at slot 119969749
--     against a chain tip of 120386215.
--
-- That window is exactly the wrong shape for the credentials that matter most.
-- dummy's issue and transfer validators are protocol-GLOBAL and unparameterized:
-- they are registered ONCE per network, on the first registration anyone ever
-- performs, which is almost always older than the window. So the index reports
-- "no certificate" forever, the handler registers again, and every dummy
-- registration on that network fails.
--
-- This table is the third source: things this deployment has been TOLD are
-- registered. It is written when the platform builds a registration for a
-- credential, and when a submit comes back naming one as already known -- the
-- 3145 error carries the credential hash, which makes the failure self-correcting
-- instead of a dead end. It is deliberately a cache of observations rather than a
-- derived view: nothing here can be recomputed from chain data this deployment is
-- able to see, which is the entire reason it exists.
--
-- Keyed by reward address rather than by credential hash because that is what
-- every caller has in hand, and what the ledger's own error is translated into.
CREATE TABLE known_script_registration (
    stake_address VARCHAR(255) PRIMARY KEY,
    credential    VARCHAR(56),
    source        VARCHAR(32) NOT NULL,
    noted_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE known_script_registration IS
    'Script stake credentials this deployment knows to be registered, for the cases neither the account endpoint nor the indexed certificate window can answer.';
COMMENT ON COLUMN known_script_registration.source IS
    'How we learned it: BUILT (this platform built the registration) or LEDGER_REJECT (a submit named it as already known).';
