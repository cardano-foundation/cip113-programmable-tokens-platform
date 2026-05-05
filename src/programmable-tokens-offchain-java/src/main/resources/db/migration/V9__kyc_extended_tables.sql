-- ============================================================================
-- KYC Extended Substandard Tables
-- ============================================================================

-- Token registration for kyc-extended substandard.
-- Tracks per-token MPF root hashes (local and on-chain).
CREATE TABLE kyc_extended_token_registration (
    programmable_token_policy_id VARCHAR(56) PRIMARY KEY,
    issuer_admin_pkh             VARCHAR(56) NOT NULL,
    tel_policy_id                VARCHAR(56) NOT NULL,
    member_root_hash_onchain     VARCHAR(64),
    member_root_hash_local       VARCHAR(64),
    last_root_update_tx_hash     VARCHAR(64),
    last_root_update_at          TIMESTAMP
);

-- Member leaf audit table.
-- Each row is one allowed receiver (pkh) for a given policy.
CREATE TABLE kyc_extended_member_leaf (
    id                           BIGSERIAL PRIMARY KEY,
    programmable_token_policy_id VARCHAR(56) NOT NULL,
    member_pkh                   VARCHAR(56) NOT NULL,
    bound_address                VARCHAR(255),
    kyc_session_id               VARCHAR(128),
    valid_until_ms               BIGINT NOT NULL,
    added_at                     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_policy_member UNIQUE (programmable_token_policy_id, member_pkh)
);

CREATE INDEX idx_member_leaf_policy      ON kyc_extended_member_leaf (programmable_token_policy_id);
CREATE INDEX idx_member_leaf_valid_until ON kyc_extended_member_leaf (valid_until_ms);

-- Per-session binding to a kyc-extended policy — drives the KERI auto-upsert hook.
ALTER TABLE kyc_session ADD COLUMN IF NOT EXISTS bound_token_policy_id VARCHAR(56);
