-- ============================================================================
-- Record whether a freeze-and-seize blacklist init was built for a CIP-68 token
-- ============================================================================
-- The blacklist init and the token registration are two separate transactions,
-- signed and paid for minutes apart, and they must agree on ONE thing that
-- neither can rediscover from the other: whether the token is CIP-68.
--
-- Why it matters. The init transaction is the only place the `issuer_admin`
-- reward account gets registered (buildPreRegistrationTransaction deliberately
-- errors for this substandard), and the registration transaction withdraws-0
-- from that account. `issuer_admin` is parameterized by the ASSET NAME, and a
-- CIP-68 registration uses the (333)-LABELLED name -- a different parameter, so
-- a different script, so a different reward address. If the init registered the
-- unlabelled credential and the registration withdraws from the labelled one,
-- the registration is rejected with WithdrawalsNotInRewardsCERTS, AFTER the
-- user has already signed and paid for the init. The deposit is spent and the
-- registration cannot be completed without starting over.
--
-- The label itself no longer depends on `quantity` -- freeze-and-seize caps no
-- lifetime supply, so it is always (333) -- which removed the subtler half of
-- this bug, where an optional `quantity` defaulted to 0 at init (choosing 333)
-- and was 1 at registration (choosing 222). What remains is the on/off
-- difference, and that is what this column pins so registration can refuse
-- BEFORE building rather than fail on chain afterwards.
--
-- NULL means "recorded before this column existed", which is deliberately
-- distinct from FALSE: for those rows the cross-check has no evidence and must
-- stay silent rather than reject a registration that is actually fine.
ALTER TABLE freeze_and_seize_blacklist_init
    ADD COLUMN cip68_enabled BOOLEAN;

COMMENT ON COLUMN freeze_and_seize_blacklist_init.cip68_enabled IS
    'Whether this blacklist init registered the issuer_admin credential for a CIP-68 (labelled) asset name. Cross-checked at registration, which withdraws-0 from that same credential. NULL = pre-dates this column, cross-check skipped.';
