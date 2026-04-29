/**
 * Compliance API Types
 * Types for blacklist management and token seizure operations
 */

// ============================================================================
// Blacklist Initialization
// ============================================================================

export interface BlacklistInitRequest {
  substandardId: string;         // Substandard ID (e.g., 'freeze-and-seize')
  adminAddress: string;          // Admin address that will manage this blacklist
  feePayerAddress: string;       // Address that pays for the transaction
  assetName: string;             // Hex-encoded asset name of the programmable token
}

export interface BlacklistInitResponse {
  policyId: string;              // Policy ID of the blacklist node (from backend)
  unsignedCborTx: string;        // Unsigned transaction CBOR hex
}

// ============================================================================
// Blacklist Add/Remove
// ============================================================================

export interface AddToBlacklistRequest {
  tokenPolicyId: string;         // Policy ID of the token
  assetName: string;             // Hex-encoded asset name of the programmable token
  targetAddress: string;         // Address to blacklist
  feePayerAddress: string;       // Address that pays for the transaction
}

export interface RemoveFromBlacklistRequest {
  tokenPolicyId: string;         // Policy ID of the token
  assetName: string;             // Hex-encoded asset name of the programmable token
  targetAddress: string;         // Address to un-blacklist
  feePayerAddress: string;       // Address that pays for the transaction
}

// Backend returns TransactionContext as JSON
export interface TransactionContextResponse<T = unknown> {
  unsignedCborTx: string;
  metadata: T | null;
  isSuccessful: boolean;
  error: string | null;
}

export type BlacklistOperationResponse = TransactionContextResponse<void>;

// ============================================================================
// Token Seizure
// ============================================================================

export interface SeizeTokensRequest {
  feePayerAddress: string;       // Address that pays for the transaction
  unit: string;                  // Policy ID + asset name (format: policyId.assetName)
  utxoTxHash: string;            // Transaction hash containing the UTxO
  utxoOutputIndex: number;       // Output index within the transaction
  destinationAddress: string;    // Address to receive seized tokens
}

export type SeizeTokensResponse = TransactionContextResponse<void>;

// ============================================================================
// Whitelist (Global State) Management
// ============================================================================

export interface WhitelistInitRequest {
  tokenPolicyId?: string;        // Policy ID of the programmable token (optional if substandardId provided)
  substandardId?: string;        // Substandard ID (e.g., "kyc") - for pre-registration init
  adminAddress: string;          // Admin address that will manage this whitelist
  bootstrapTxHash: string;       // Bootstrap UTxO transaction hash
  bootstrapOutputIndex: number;  // Bootstrap UTxO output index
  initialVkeys?: string[];        // Optional initial trusted entity vkeys (64 hex chars each)
  initialTransfersPaused?: boolean;  // Optional: start with transfers paused (default: false)
  initialMintableAmount?: number;    // Optional: initial mintable amount (default: 0)
  initialSecurityInfo?: string;      // Optional: security info as hex bytes
}

export type WhitelistInitResponse = TransactionContextResponse<{ bootstrapParameters: string }>;

export interface AddToWhitelistRequest {
  adminAddress: string;          // Admin address performing the action
  targetCredential: string;      // Verification key hex (64 chars)
  policyId: string;              // Policy ID of the programmable token
  kycReference?: string;         // Optional KYC verification reference
}

export interface RemoveFromWhitelistRequest {
  adminAddress: string;          // Admin address performing the action
  targetCredential: string;      // Verification key hex (64 chars)
  policyId: string;              // Policy ID of the programmable token
  reason?: string;               // Optional reason for removal
}

export type WhitelistOperationResponse = TransactionContextResponse<void>;

// ============================================================================
// Global State Init / Entity Management (KYC substandard)
// Separate from Whitelist — mirrors GlobalStateManageable on the backend.
// ============================================================================

export interface GlobalStateInitRequest {
  substandardId: string;              // e.g. "kyc"
  adminAddress: string;               // admin that owns the global state
  initialVkeys?: string[];            // initial trusted entity vkeys (64 hex chars each)
  initialTransfersPaused?: boolean;   // start with transfers paused (default: false)
  initialMintableAmount?: number;     // initial mintable amount cap (default: 0)
  initialSecurityInfo?: string;       // optional hex-encoded compliance metadata
}

export type GlobalStateInitResponse = TransactionContextResponse<{ globalStatePolicyId: string }>;

export interface AddTrustedEntityRequest {
  adminAddress: string;     // admin performing the action
  verificationKey: string;  // Ed25519 vkey hex (64 chars / 32 bytes)
  policyId: string;         // programmable token policy ID
}

export interface RemoveTrustedEntityRequest {
  adminAddress: string;     // admin performing the action
  verificationKey: string;  // Ed25519 vkey hex (64 chars / 32 bytes) to remove
  policyId: string;         // programmable token policy ID
  reason?: string;          // optional reason for removal
}

export type TrustedEntityOperationResponse = TransactionContextResponse<void>;

// ============================================================================
// Global State Management
// ============================================================================

export type GlobalStateAction = 'PAUSE_TRANSFERS' | 'UPDATE_MINTABLE_AMOUNT' | 'MODIFY_SECURITY_INFO' | 'MODIFY_TRUSTED_ENTITIES';

export interface GlobalStateUpdateRequest {
  adminAddress: string;
  policyId: string;
  action: GlobalStateAction;
  transfersPaused?: boolean;       // for PAUSE_TRANSFERS
  mintableAmount?: number;         // for UPDATE_MINTABLE_AMOUNT
  securityInfo?: string;           // for MODIFY_SECURITY_INFO (hex bytes)
  trustedEntities?: string[];      // for MODIFY_TRUSTED_ENTITIES (full replacement list of 64-char hex vkeys)
}

export type GlobalStateUpdateResponse = TransactionContextResponse<void>;

// ============================================================================
// Global State Read
// ============================================================================

export interface GlobalStateData {
  policyId: string;
  transfersPaused: boolean;
  mintableAmount: number;
  trustedEntities: string[];       // hex vkeys (64 chars each)
  securityInfo: string | null;     // hex or null
}

// ============================================================================
// Shared Types
// ============================================================================

export interface ComplianceOperationResult {
  success: boolean;
  txHash?: string;
  error?: string;
}
