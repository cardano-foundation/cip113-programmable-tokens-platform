-- A proposed member root must retain the exact leaves that produced it. The
-- mutable Veridian staging table is deliberately not used to serve proofs.
CREATE TABLE rwa_token_member_root_snapshot (
    id BIGSERIAL PRIMARY KEY,
    programmable_token_policy_id VARCHAR(56) NOT NULL,
    root_hash VARCHAR(64) NOT NULL,
    baseline_root_hash VARCHAR(64) NOT NULL,
    leaves_json TEXT NOT NULL,
    tx_hash VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rwa_member_root_snapshot UNIQUE (programmable_token_policy_id, root_hash)
);
CREATE INDEX ix_rwa_member_root_snapshot_policy
    ON rwa_token_member_root_snapshot (programmable_token_policy_id);
