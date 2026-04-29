package org.cardanofoundation.cip113.service.substandard.capabilities;

import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;

/**
 * Global state management capability for substandards that use a global state UTxO.
 * Supports initialization of the global state, management of the trusted entity list,
 * and general updates (pause transfers, mintable amount, security info).
 *
 * <p>This interface covers all global-state operations for the KYC substandard.
 * It is intentionally separate from {@link WhitelistManageable}, which is reserved
 * for substandards that maintain an on-chain address whitelist (linked-list pattern).</p>
 */
public interface GlobalStateManageable {

    // ========== Request / Result Records ==========

    /**
     * Result of a global state initialization — carries the minted policy ID.
     */
    record GlobalStateInitResult(String globalStatePolicyId) {}

    /**
     * Request to initialize the global state UTxO for a new KYC token deployment.
     */
    record GlobalStateInitRequest(
            /** Substandard ID (e.g., "kyc") — used when the token is not yet registered */
            String substandardId,
            /** Admin address that will own and update the global state */
            String adminAddress,
            /** Optional initial trusted entity vkeys (64 hex chars / 32 bytes each) */
            java.util.List<String> initialVkeys,
            /** Whether transfers start paused (default: false) */
            Boolean initialTransfersPaused,
            /** Initial mintable amount cap (default: 0 = no cap) */
            Long initialMintableAmount,
            /** Optional hex-encoded compliance metadata */
            String initialSecurityInfo
    ) {}

    /**
     * Request to add a trusted entity (verification key) to the global state.
     */
    record AddTrustedEntityRequest(
            /** Admin address performing the action */
            String adminAddress,
            /** Ed25519 verification key hex (64 chars / 32 bytes) */
            String verificationKey,
            /** Policy ID of the programmable token */
            String policyId
    ) {}

    /**
     * Request to remove a trusted entity (verification key) from the global state.
     */
    record RemoveTrustedEntityRequest(
            /** Admin address performing the action */
            String adminAddress,
            /** Ed25519 verification key hex (64 chars / 32 bytes) to remove */
            String verificationKey,
            /** Policy ID of the programmable token */
            String policyId,
            /** Optional reason for removal */
            String reason
    ) {}

    /**
     * Request to update a field of the global state UTxO.
     * Exactly one of the action-specific fields should be non-null.
     */
    record GlobalStateUpdateRequest(
            /** Admin address performing the update */
            String adminAddress,
            /** Policy ID of the programmable token */
            String policyId,
            /** Action to perform */
            GlobalStateAction action,
            /** New value for transfers_paused (for PAUSE_TRANSFERS) */
            Boolean transfersPaused,
            /** New value for mintable_amount (for UPDATE_MINTABLE_AMOUNT) */
            Long mintableAmount,
            /** New security info as hex-encoded bytes (for MODIFY_SECURITY_INFO) */
            String securityInfo,
            /** Full replacement list of trusted entity vkeys (for MODIFY_TRUSTED_ENTITIES) */
            java.util.List<String> trustedEntities
    ) {}

    enum GlobalStateAction {
        PAUSE_TRANSFERS,
        UPDATE_MINTABLE_AMOUNT,
        MODIFY_SECURITY_INFO,
        MODIFY_TRUSTED_ENTITIES
    }

    // ========== Capability Methods ==========

    /**
     * Initialize the global state UTxO for a new token deployment.
     */
    TransactionContext<GlobalStateInitResult> buildGlobalStateInitTransaction(
            GlobalStateInitRequest request,
            ProtocolBootstrapParams protocolParams);

    /**
     * Add a trusted entity (verification key) to the global state.
     */
    TransactionContext<Void> buildAddTrustedEntityTransaction(
            AddTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams);

    /**
     * Remove a trusted entity (verification key) from the global state.
     */
    TransactionContext<Void> buildRemoveTrustedEntityTransaction(
            RemoveTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams);

    /**
     * Update a field of the global state UTxO (pause, mintable amount, security info, entities).
     */
    TransactionContext<Void> buildGlobalStateUpdateTransaction(
            GlobalStateUpdateRequest request,
            ProtocolBootstrapParams protocolParams);
}
