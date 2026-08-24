-- ============================================================================
-- Rename: this substandard is the RWA token, not the "RWA token"
-- ============================================================================
-- Naming only. No column changes, no data reshaping, nothing on chain moves --
-- the substandard id is a PLATFORM label that never appears in a datum, a
-- redeemer or a script parameter, so renaming it cannot invalidate a single
-- deployed token.
--
-- Two things do have to move together, because the platform derives one from
-- the other: the substandard id is also the RESOURCE FOLDER NAME that
-- SubstandardService scans (`classpath:substandards/*/plutus.json`, folder name
-- becomes the id). The folders are renamed in the same change; if only one side
-- moved, every rwa-token lookup would resolve to nothing and every build would
-- fail with "contract not found".
--
-- Deliberately NOT renamed: `security_asset_name` / `security_info` and their
-- Java mirrors. Those are the CONTRACT's own vocabulary -- field names in
-- GlobalStateDatum and compile-time parameters in the .ak sources -- not the
-- product name. Renaming them here would desync our identifiers from the
-- upstream they mirror, which is precisely the confusion this rename is meant
-- to remove.

-- ----------------------------------------------------------------------------
-- 1. Tables
-- ----------------------------------------------------------------------------
-- ALTER ... RENAME carries indexes, constraints and their data with it, so
-- there is no window where a row is unreachable. The historical migrations
-- (V11, V13, V15, V18, V19, V20) still name the old tables and are deliberately
-- left untouched: they are a record of what happened, and Flyway has already run
-- them. Only this one renames.
ALTER TABLE security_token_registration   RENAME TO rwa_token_registration;
ALTER TABLE security_token_member_leaf    RENAME TO rwa_token_member_leaf;
ALTER TABLE security_token_power_user     RENAME TO rwa_token_power_user;
ALTER TABLE security_token_denylist_entry RENAME TO rwa_token_denylist_entry;

-- ----------------------------------------------------------------------------
-- 2. The stored substandard id
-- ----------------------------------------------------------------------------
-- `programmable_token_registry.substandard_id` holds the literal, and the
-- handler factory looks handlers up by it. A row left saying 'rwa-token'
-- would resolve to no handler at all after this rename -- the token would
-- silently disappear from the admin panel rather than fail loudly.
UPDATE programmable_token_registry
   SET substandard_id = 'rwa-token'
 WHERE substandard_id = 'rwa-token';

COMMENT ON COLUMN programmable_token_registry.substandard_id IS
    'Substandard identifier (e.g., dummy, freeze-and-seize, rwa-token). Must equal the resources/substandards/<folder> name — SubstandardService derives the id from that folder.';
