CREATE TABLE IF NOT EXISTS kyc_session (
    session_id       VARCHAR(128) PRIMARY KEY,
    aid              VARCHAR(128),
    oobi             TEXT,
    cardano_address  VARCHAR(255),
    credential_attributes TEXT,
    credential_role  INTEGER,
    credential_aid   VARCHAR(128),
    credential_said  VARCHAR(128),
    kyc_proof_payload      VARCHAR(74),
    kyc_proof_signature    VARCHAR(128),
    kyc_proof_entity_vkey  VARCHAR(64),
    kyc_proof_valid_until  BIGINT
);
