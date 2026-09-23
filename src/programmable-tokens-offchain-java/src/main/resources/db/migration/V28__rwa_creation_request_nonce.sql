CREATE TABLE rwa_token_creation_request_nonce (
    nonce VARCHAR(64) PRIMARY KEY,
    payer_hash VARCHAR(56) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
