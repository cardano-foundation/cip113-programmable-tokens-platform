-- ============================================================================
-- KYC Substandard Tables
-- ============================================================================

-- Create table to store KYC token registration data
-- Links programmable tokens to their Trusted Entity List (TEL)
CREATE TABLE kyc_token_registration (
    programmable_token_policy_id VARCHAR(56) PRIMARY KEY,
    issuer_admin_pkh VARCHAR(56) NOT NULL,
    tel_policy_id VARCHAR(56) NOT NULL
);

COMMENT ON TABLE kyc_token_registration IS 'Stores KYC substandard token registration data linking tokens to their Trusted Entity List';
COMMENT ON COLUMN kyc_token_registration.programmable_token_policy_id IS 'Policy ID of the programmable token (primary key)';
COMMENT ON COLUMN kyc_token_registration.issuer_admin_pkh IS 'Public key hash of the issuer admin';
COMMENT ON COLUMN kyc_token_registration.tel_policy_id IS 'Policy ID of the Trusted Entity List (TEL) linked list node NFTs';
