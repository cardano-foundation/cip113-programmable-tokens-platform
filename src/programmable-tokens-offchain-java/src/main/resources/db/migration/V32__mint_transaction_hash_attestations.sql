-- The same frozen mint transaction can be attested by several independent AIDs.
ALTER TABLE mint_attestation_intent DROP CONSTRAINT IF EXISTS mint_attestation_intent_digest_key;
CREATE INDEX IF NOT EXISTS idx_mint_attestation_intent_digest ON mint_attestation_intent (digest);

ALTER TABLE mint_attestation_intent ADD COLUMN initial_prefix_json text;
ALTER TABLE mint_attestation_intent ADD COLUMN initial_snapshot_json text;
ALTER TABLE mint_attestation_intent ADD COLUMN attestation_cbor text;
ALTER TABLE mint_attestation_intent ADD COLUMN attestation_tx_hash varchar(64);
