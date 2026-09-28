-- A bootstrap input identifies a one-shot GS policy. Preserve every previous
-- registration attempt so an unsigned chain returned earlier can still land.
CREATE TABLE rwa_genesis_reservation (
    global_state_policy_id VARCHAR(56) PRIMARY KEY,
    bootstrap_tx_hash VARCHAR(64),
    bootstrap_output_index INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rwa_genesis_bootstrap UNIQUE (bootstrap_tx_hash, bootstrap_output_index)
);

-- Legacy rows may contain duplicate GS identities. Reserve each identity once;
-- selection also consults all registration rows for their known bootstrap refs.
INSERT INTO rwa_genesis_reservation
    (global_state_policy_id, bootstrap_tx_hash, bootstrap_output_index)
SELECT DISTINCT ON (global_state_policy_id)
       global_state_policy_id, bootstrap_tx_hash, bootstrap_output_index
FROM rwa_token_registration
ORDER BY global_state_policy_id, bootstrap_tx_hash NULLS LAST, bootstrap_output_index NULLS LAST
ON CONFLICT DO NOTHING;

-- A genesis chain can consume several wallet inputs. Keep every funding and
-- collateral reference unavailable to later unsigned creation attempts.
CREATE TABLE rwa_genesis_funding_reservation (
    input_ref VARCHAR(80) PRIMARY KEY,
    global_state_policy_id VARCHAR(56) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_rwa_genesis_funding_policy
    ON rwa_genesis_funding_reservation (global_state_policy_id);
INSERT INTO rwa_genesis_funding_reservation (input_ref, global_state_policy_id)
SELECT bootstrap_tx_hash || '#' || bootstrap_output_index, global_state_policy_id
FROM rwa_genesis_reservation
WHERE bootstrap_tx_hash IS NOT NULL AND bootstrap_output_index IS NOT NULL
ON CONFLICT DO NOTHING;
