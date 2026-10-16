-- PR #29 rwa-token pin: GlobalState sits behind the unparameterised global_state_proxy and is
-- governed by a rotatable global_state_logic (datum field 0), parameterised on an aggregate
-- datum byte cap. The cap has to be stored to re-derive the GlobalState mint and the v1 logic,
-- and every logic the platform builds (genesis + rotation targets) is kept with its applied
-- bytes and reference-script location, because a rotated-in logic cannot be re-derived from
-- defaults. Rows from before this pin keep NULLs: their GlobalState is not at the proxy, has
-- 14 fields and no registry entry, so every PR #29 write path refuses them (UNKNOWN LOGIC).
-- gs_spend_ref_index is kept and now holds the v1 LOGIC's output index in the publishScripts tx.
ALTER TABLE rwa_token_registration
    ADD COLUMN IF NOT EXISTS max_gs_datum_bytes INTEGER;
ALTER TABLE rwa_token_registration
    ADD COLUMN IF NOT EXISTS gs_logics TEXT;
-- Optimistic lock for the read-modify-write gs_logics list (NOT NULL DEFAULT 0, so rows written
-- before V34 load with version 0 and are not mistaken for new entities).
ALTER TABLE rwa_token_registration
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
