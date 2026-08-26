-- The protocol_params table recorded two of the seven fields of the CIP-113 protocol-params
-- datum: the registry-node policy and PLB's credential. Everything it dropped is the live
-- wiring -- the three delegate credentials programmable_logic_base dispatches to, the upgrade
-- authority, and the inline-datum bound -- so the indexed view could not answer "who is the
-- current delegate?", which is the one question the protocol's in-place upgrade mechanism
-- makes worth asking. The deployment record in protocol-bootstraps-*.json cannot answer it
-- either: an upgrade rewrites the datum on chain and never touches that file.
--
-- Nullable rather than NOT NULL: rows written before this migration were decoded by a parser
-- that never read these fields, so their values are genuinely unknown. Defaulting them to ''
-- would assert a credential no UTxO ever carried; NULL says "this row predates the decoder
-- that could read them", which is true and is what a reader needs to know. New rows always
-- populate all five.

ALTER TABLE protocol_params ADD COLUMN transfer_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN third_party_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN unfracking_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN upgrade_cred VARCHAR(56);
ALTER TABLE protocol_params ADD COLUMN max_inline_datum_bytes BIGINT;

COMMENT ON COLUMN protocol_params.transfer_cred IS
  'Datum field 2: the transfer withdraw-0 credential. programmable_logic_base requires it on a '
  'SpendViaTransfer dispatch. Swappable in place, which is why it is indexed rather than assumed '
  'from the deployment record.';
COMMENT ON COLUMN protocol_params.third_party_cred IS
  'Datum field 3: the third_party (seize / clawback / freeze) withdraw-0 credential.';
COMMENT ON COLUMN protocol_params.unfracking_cred IS
  'Datum field 4: the unfracking withdraw-0 credential.';
COMMENT ON COLUMN protocol_params.upgrade_cred IS
  'Datum field 5: the authority permitted to rewrite this datum.';
COMMENT ON COLUMN protocol_params.max_inline_datum_bytes IS
  'Datum field 6: the maximum serialised inline datum a HOLDER-created programmable output may '
  'carry. Does not bound the mint path, nor a seizure''s paired continuation output.';
