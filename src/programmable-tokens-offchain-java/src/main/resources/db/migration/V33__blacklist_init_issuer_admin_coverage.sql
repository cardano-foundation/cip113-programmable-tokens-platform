-- ============================================================================
-- Record WHICH issuer_admin credential a blacklist init actually registered
-- ============================================================================
-- `issuer_admin` is parameterised by (adminPkh, ASSET NAME), and the blacklist
-- init is the ONLY place its reward account gets registered -- the registration
-- transaction withdraws-0 from it, and the ledger applies withdrawals BEFORE
-- certificates, so registration cannot register it itself.
--
-- That makes a blacklist valid for exactly ONE (admin, asset name) pair. Two
-- ways to fall off it, both previously SILENT:
--
--   * a different ADMIN -- reusing a blacklist from another wallet. Measured on
--     preprod 2026-09-30: an init covered admin 438e0a0a…, a later registration
--     used 7eb45c3e…, and the withdraw-0 pointed at a reward account nothing had
--     ever registered. Rejected on chain as code 3141, whose message says
--     "rewards withdrawals must consume rewards in full" and names a balance
--     problem rather than a missing certificate.
--   * a different ASSET NAME -- a SECOND token registered against one blacklist.
--     Already present in that database: two token registrations sharing one
--     blacklist_node_policy_id.
--
-- V14 added `cip68_enabled` to close the LABEL dimension of this same script,
-- because that is the variant somebody hit first. The admin and asset-name
-- dimensions were left unguarded, and they are parameters of the same script.
--
-- This column records the reward address the init registered, so registration can
-- COMPARE rather than assume. `admin_pkh` alone cannot answer the question: it
-- carries one of the two parameters and not the other.
--
-- NULL means "recorded before this column existed", deliberately distinct from a
-- value: for those rows there is no evidence and the cross-check must stay silent
-- rather than reject a registration that is actually fine. Same convention as
-- V14's cip68_enabled.
ALTER TABLE freeze_and_seize_blacklist_init
    ADD COLUMN issuer_admin_stake_address VARCHAR(128);

COMMENT ON COLUMN freeze_and_seize_blacklist_init.issuer_admin_stake_address IS
    'Reward address of the issuer_admin credential this init registered, derived from (admin_pkh, asset name). Compared at registration, which withdraws-0 from that same credential. NULL = pre-dates this column, cross-check skipped.';
