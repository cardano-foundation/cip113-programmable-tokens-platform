ALTER TABLE kyc_session ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE kyc_issuance (
    session_id VARCHAR(128) PRIMARY KEY REFERENCES kyc_session(session_id),
    wallet_aid VARCHAR(128) NOT NULL,
    issuer_aid VARCHAR(128) NOT NULL,
    schema_said VARCHAR(128) NOT NULL,
    schema_oobi_url TEXT NOT NULL,
    attributes_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    credential_said VARCHAR(128),
    issue_operation_name VARCHAR(255),
    acdc_json TEXT,
    iss_json TEXT,
    anc_json TEXT,
    grant_said VARCHAR(128),
    grant_raw TEXT,
    grant_sigs TEXT,
    grant_atc TEXT,
    signing_establishment_seq VARCHAR(64),
    signing_establishment_digest VARCHAR(128),
    delivery_owner VARCHAR(128),
    delivery_lease_until TIMESTAMP WITH TIME ZONE,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
