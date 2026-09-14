ALTER TABLE protocol_params ADD COLUMN programmable_logic_global_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN issuance_logic_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN pending_upgrade_cred VARCHAR(56);

COMMENT ON COLUMN protocol_params.programmable_logic_global_cred IS
    'Alpha.4 protocol params field 0: dispatcher withdrawal credential';
COMMENT ON COLUMN protocol_params.issuance_logic_cred IS
    'Alpha.4 protocol params field 1: replaceable issuance withdrawal credential';
COMMENT ON COLUMN protocol_params.pending_upgrade_cred IS
    'Alpha.4 protocol params field 5: nominated authority, null when no handover is pending';
