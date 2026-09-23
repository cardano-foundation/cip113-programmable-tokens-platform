CREATE TABLE rwa_token_admin_request_nonce (
    nonce VARCHAR(64) PRIMARY KEY,
    token_policy_id VARCHAR(56) NOT NULL,
    admin_hash VARCHAR(56) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
