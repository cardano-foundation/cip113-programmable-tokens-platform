-- ============================================================================
-- Security-Token Substandard Tables
-- ============================================================================
-- Per-token registration, MPF allowlist leaves, denylist entries, and
-- power-user records. The on-chain validators are the source of truth; these
-- tables are off-chain mirrors used for fast queries, UI rendering, and the
-- autonomous root-sync publisher.

CREATE TABLE security_token_registration (
    programmable_token_policy_id  VARCHAR(56) PRIMARY KEY,
    issuer_admin_pkh              VARCHAR(56) NOT NULL,
    global_state_policy_id        VARCHAR(56) NOT NULL,
    denylist_policy_id            VARCHAR(56) NOT NULL,
    power_users_policy_id         VARCHAR(56) NOT NULL,
    requires_receiver_kyc         BOOLEAN     NOT NULL DEFAULT TRUE,
    security_asset_name_hex       VARCHAR(64),
    bootstrap_tx_hash             VARCHAR(64),
    bootstrap_output_index        INT,
    member_root_hash_onchain      VARCHAR(64),
    member_root_hash_local        VARCHAR(64),
    last_root_update_tx_hash      VARCHAR(64),
    last_root_update_at           TIMESTAMP
);

-- MPF allowlist leaves. Mirrors kyc-extended's published_at split-state model
-- so inclusion proofs are only served from leaves the chain has already seen.
CREATE TABLE security_token_member_leaf (
    id                            BIGSERIAL PRIMARY KEY,
    programmable_token_policy_id  VARCHAR(56) NOT NULL,
    member_pkh                    VARCHAR(56) NOT NULL,
    bound_address                 VARCHAR(255),
    kyc_session_id                VARCHAR(128),
    valid_until_ms                BIGINT NOT NULL,
    added_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at                  TIMESTAMP,
    CONSTRAINT uq_st_policy_member UNIQUE (programmable_token_policy_id, member_pkh)
);

CREATE INDEX idx_st_member_leaf_policy        ON security_token_member_leaf (programmable_token_policy_id);
CREATE INDEX idx_st_member_leaf_valid_until   ON security_token_member_leaf (valid_until_ms);
CREATE INDEX idx_st_member_leaf_published_at  ON security_token_member_leaf (programmable_token_policy_id, published_at);

-- Denylist entries. Off-chain mirror of the on-chain denylist linked list.
CREATE TABLE security_token_denylist_entry (
    id                            BIGSERIAL PRIMARY KEY,
    programmable_token_policy_id  VARCHAR(56) NOT NULL,
    member_pkh                    VARCHAR(56) NOT NULL,
    reason                        VARCHAR(255),
    added_by_power_user_pkh       VARCHAR(56),
    added_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_st_denylist UNIQUE (programmable_token_policy_id, member_pkh)
);

CREATE INDEX idx_st_denylist_policy ON security_token_denylist_entry (programmable_token_policy_id);

-- Power-user records. capabilities is a bitfield:
--   bit 0 = Admin, bit 1 = Minter, bit 2 = Burner, bit 3 = Pauser,
--   bit 4 = Blacklister, bit 5 = Verifier, bit 6 = ForceTransfer (unused in v1).
CREATE TABLE security_token_power_user (
    id                            BIGSERIAL PRIMARY KEY,
    programmable_token_policy_id  VARCHAR(56) NOT NULL,
    power_user_pkh                VARCHAR(56) NOT NULL,
    capabilities                  INT         NOT NULL,
    label                         VARCHAR(255),
    added_at                      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_st_power_user UNIQUE (programmable_token_policy_id, power_user_pkh)
);

CREATE INDEX idx_st_power_user_policy ON security_token_power_user (programmable_token_policy_id);
