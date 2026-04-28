-- ============================================================================
-- KYC TEL (Trusted Entity List) Global State Initialization
-- ============================================================================

-- Create table to store TEL global state init data.
-- Each KYC token deployment has one global state UTxO holding the trusted entity list.
-- This table stores the one-shot minting policy ID and bootstrap parameters needed
-- to rebuild the global state scripts for updates.
CREATE TABLE kyc_tel_init (
    tel_node_policy_id VARCHAR(56) PRIMARY KEY,
    admin_pkh VARCHAR(56) NOT NULL,
    tx_hash VARCHAR(64) NOT NULL,
    output_index INTEGER NOT NULL,
    CONSTRAINT uk_tel_admin_tx_output UNIQUE (admin_pkh, tx_hash, output_index)
);

COMMENT ON TABLE kyc_tel_init IS 'Stores KYC TEL global state initialization data for rebuilding scripts';
COMMENT ON COLUMN kyc_tel_init.tel_node_policy_id IS 'Policy ID of the global state NFT (one-shot minting policy)';
COMMENT ON COLUMN kyc_tel_init.admin_pkh IS 'Public key hash of the admin who manages this TEL';
COMMENT ON COLUMN kyc_tel_init.tx_hash IS 'Transaction hash of the bootstrap UTxO';
COMMENT ON COLUMN kyc_tel_init.output_index IS 'Output index of the bootstrap UTxO';
