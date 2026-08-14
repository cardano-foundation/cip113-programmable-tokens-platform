-- v0.4.0's RegistryNode (lib/registry_node.ak) gained two fields --
-- minting_logic_script at index 2 and unfracking_logic_script at index 5 --
-- making it a 7-field constructor. Every row indexed by the pre-v0.4.0 parser
-- is SILENTLY WRONG, not merely incomplete: that parser read a 7-field constr
-- with 5-field-shaped offsets, so transfer_logic_script actually held
-- minting_logic_script's hash, third_party_transfer_logic_script held
-- transfer_logic_script's hash, and global_state_policy_id was always empty
-- (see docs/PLATFORM-V0.4.0-PORT-PLAN.md, WP-3). A schema bump alone would
-- leave those plausible-but-wrong values in place under NOT NULL columns.
--
-- This migration clears the corrupt rows outright (an empty table that is
-- visibly "not registered" is safer than one silently serving the wrong
-- script hashes to a caller who might build a transaction against them) and
-- adds the two missing columns.
--
-- IMPORTANT: this migration does NOT by itself re-index anything. It only
-- touches this app's own table; yaci-store's sync cursor (table `cursor_`,
-- driven by the `store.cardano.*` properties in application.yaml) is
-- untouched, so RegistryEventListener will only observe NEW on-chain events
-- from here on -- historical registrations will not reappear on their own.
--
-- To recover historical registry state, an operator must additionally rewind
-- the indexer BEFORE restarting the app against this migration:
--   1. Stop the app.
--   2. Truncate yaci-store's checkpoint tables (`cursor_`, `era`) -- or set
--      store.cardano.sync-start-slot / store.cardano.sync-start-blockhash
--      back to a slot before the protocol deployment / registration
--      transactions you need replayed.
--   3. Restart. The replayed AddressUtxoEvents rebuild `registry_node` from
--      scratch via RegistryEventListener, this time with the corrected
--      7-field parser.
-- There is no automated trigger for step 2 in this codebase today (see
-- docs/PLATFORM-V0.4.0-PORT-PLAN.md, WP-4) -- it is a manual, environment-
-- specific operator action because rewinding a shared sync cursor safely
-- depends on which slot the relevant transactions live at and whether other
-- consumers share that cursor.

DELETE FROM registry_node;

-- DEFAULT '' only exists to satisfy NOT NULL while ADD COLUMN runs; the table is
-- empty at this point (see DELETE above) so no row actually gets the default
-- value. Dropped immediately after so the columns match their siblings
-- (transfer_logic_script, third_party_transfer_logic_script): NOT NULL with no
-- default, forcing every future insert (RegistryEventListener, RegistryService)
-- to supply a real value instead of silently falling back to ''.
ALTER TABLE registry_node
    ADD COLUMN minting_logic_script VARCHAR(56) NOT NULL DEFAULT '',
    ADD COLUMN unfracking_logic_script VARCHAR(56) NOT NULL DEFAULT '';

ALTER TABLE registry_node
    ALTER COLUMN minting_logic_script DROP DEFAULT,
    ALTER COLUMN unfracking_logic_script DROP DEFAULT;

COMMENT ON COLUMN registry_node.minting_logic_script IS 'Script/key hash for minting validation (RegistryNode index 2)';
COMMENT ON COLUMN registry_node.unfracking_logic_script IS 'Script/key hash whose withdraw-0 must be invoked by any UnfrackingAct touching this policy; empty = unfracking forbidden (RegistryNode index 5, new in v0.4.0)';
