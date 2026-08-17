-- ============================================================================
-- CIP-68 metadata for security-token registrations
-- ============================================================================
-- security-token is the one substandard whose CIP-68 reference token cannot be
-- minted at registration. `verify_token_registration` enumerates every asset
-- name minted under the issuance policy and rejects more than one
-- (minting_logic_script.ak:198-204), so the (100) token has to ride along with
-- a later MintBurn transaction instead -- and a MintBurn happens minutes or
-- days after the wizard collected the metadata, from a different page.
--
-- Without somewhere to keep it, the admin would have to retype the name,
-- ticker, decimals, url and logo at mint time and get them byte-identical, or
-- the token would keep its (222)/(333) label while advertising metadata that
-- was never written -- precisely the failure CIP-68 support exists to fix.
-- So genesis records the metadata here and the first mint reads it back.
--
-- Stored as JSON text rather than six columns: it is opaque to SQL (never
-- filtered or joined on), it is written once and read once, and CIP-68's
-- metadata map is open-ended -- a future field should not need a migration.
--
-- NULL means "this token is not CIP-68", which is the correct default for
-- every row that already exists.
ALTER TABLE security_token_registration
    ADD COLUMN cip68_metadata_json TEXT;

COMMENT ON COLUMN security_token_registration.cip68_metadata_json IS
    'CIP-68 metadata captured at genesis, as JSON, replayed into the (100) reference token datum on the first mint. NULL = not a CIP-68 token.';

-- Whether the (100) reference token has actually been minted. The mint path
-- also checks the chain (a UTxO holding the reference unit at the admin''s PLB
-- address), but that check depends on the indexer being caught up; this flag
-- is the local, immediate record. Either signal being set blocks a second
-- reference token, which CIP-68 forbids -- quantity must be exactly 1.
ALTER TABLE security_token_registration
    ADD COLUMN cip68_reference_minted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN security_token_registration.cip68_reference_minted IS
    'True once the (100) reference token has been minted for this policy. Guards against minting a second one.';
