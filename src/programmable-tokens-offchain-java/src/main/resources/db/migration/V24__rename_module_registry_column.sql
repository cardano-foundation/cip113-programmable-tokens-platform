-- The module name replaces the old platform label; token IDs and on-chain data are unchanged.
ALTER TABLE programmable_token_registry RENAME COLUMN substandard_id TO module_id;

COMMENT ON TABLE programmable_token_registry IS
    'Unified registry mapping programmable token policy IDs to their module';
COMMENT ON COLUMN programmable_token_registry.module_id IS
    'Module identifier (e.g., dummy, freeze-and-seize, rwa-token). Matches the resources/modules/<folder> name.';
