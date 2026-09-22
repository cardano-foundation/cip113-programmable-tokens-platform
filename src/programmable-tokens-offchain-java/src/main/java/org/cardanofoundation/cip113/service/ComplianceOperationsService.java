package org.cardanofoundation.cip113.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.TransactionContext.MintingResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.capabilities.BlacklistManageable;
import org.cardanofoundation.cip113.service.module.capabilities.BlacklistManageable.*;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.AddTrustedEntityRequest;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitRequest;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitResult;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateUpdateRequest;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.RemoveTrustedEntityRequest;
import org.cardanofoundation.cip113.service.module.capabilities.Seizeable;
import org.cardanofoundation.cip113.service.module.capabilities.Seizeable.*;
import org.cardanofoundation.cip113.service.module.capabilities.WhitelistManageable;
import org.cardanofoundation.cip113.service.module.capabilities.WhitelistManageable.*;
import org.cardanofoundation.cip113.service.module.context.ModuleContext;
import org.springframework.stereotype.Service;

/**
 * Service orchestration layer for compliance operations.
 * This service coordinates blacklist, whitelist, and seize operations
 * between controllers and module handlers.
 *
 * <p>Supported capabilities:</p>
 * <ul>
 *   <li>{@link BlacklistManageable} - Freeze/unfreeze addresses</li>
 *   <li>{@link WhitelistManageable} - KYC/securities compliance</li>
 *   <li>{@link Seizeable} - Asset seizure from blacklisted addresses</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ComplianceOperationsService {

    private final ModuleHandlerFactory handlerFactory;
    private final ProtocolBootstrapService protocolBootstrapService;

    private final ProtocolDeploymentResolver protocolDeploymentResolver;

    // ========== Blacklist Operations ==========

    /**
     * Initialize a blacklist for a programmable token.
     *
     * @param moduleId  The module identifier (e.g., "freeze-and-seize")
     * @param request        The blacklist initialization request
     * @param protocolTxHash Optional protocol version tx hash (uses default if null)
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx and bootstrap parameters
     */
    public TransactionContext<MintingResult> initBlacklist(
            String moduleId,
            BlacklistInitRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Initializing blacklist for module: {}, admin: {}",
                moduleId, request.adminAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var blacklistMgr = getBlacklistManageable(moduleId, context);

        var txContext = blacklistMgr.buildBlacklistInitTransaction(request, protocolParams);

        log.info("Blacklist init transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Add an address to the blacklist (freeze).
     *
     * @param moduleId  The module identifier
     * @param request        The add to blacklist request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> addToBlacklist(
            String moduleId,
            AddToBlacklistRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Adding to blacklist for module: {}, target: {}",
                moduleId, request.targetAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var blacklistMgr = getBlacklistManageable(moduleId, context);

        var txContext = blacklistMgr.buildAddToBlacklistTransaction(request, protocolParams);

        log.info("Add to blacklist transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Remove an address from the blacklist (unfreeze).
     *
     * @param moduleId  The module identifier
     * @param request        The remove from blacklist request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> removeFromBlacklist(
            String moduleId,
            RemoveFromBlacklistRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Removing from blacklist for module: {}, target: {}",
                moduleId, request.targetAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var blacklistMgr = getBlacklistManageable(moduleId, context);

        var txContext = blacklistMgr.buildRemoveFromBlacklistTransaction(request, protocolParams);

        log.info("Remove from blacklist transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    // ========== Whitelist Operations ==========

    /**
     * Initialize a whitelist for a programmable token.
     *
     * @param moduleId  The module identifier
     * @param request        The whitelist initialization request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx and bootstrap parameters
     */
    public TransactionContext<WhitelistInitResult> initWhitelist(
            String moduleId,
            WhitelistInitRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Initializing whitelist for module: {}, admin: {}",
                moduleId, request.adminAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var whitelistMgr = getWhitelistManageable(moduleId, context);

        var txContext = whitelistMgr.buildWhitelistInitTransaction(request, protocolParams);

        log.info("Whitelist init transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Add an address to the whitelist (KYC approval).
     *
     * @param moduleId  The module identifier
     * @param request        The add to whitelist request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> addToWhitelist(
            String moduleId,
            AddToWhitelistRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Adding to whitelist for module: {}, target: {}",
                moduleId, request.targetCredential());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var whitelistMgr = getWhitelistManageable(moduleId, context);

        var txContext = whitelistMgr.buildAddToWhitelistTransaction(request, protocolParams);

        log.info("Add to whitelist transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Remove an address from the whitelist (revoke KYC approval).
     *
     * @param moduleId  The module identifier
     * @param request        The remove from whitelist request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> removeFromWhitelist(
            String moduleId,
            RemoveFromWhitelistRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Removing from whitelist for module: {}, target: {}",
                moduleId, request.targetCredential());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var whitelistMgr = getWhitelistManageable(moduleId, context);

        var txContext = whitelistMgr.buildRemoveFromWhitelistTransaction(request, protocolParams);

        log.info("Remove from whitelist transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    // ========== Global State Operations ==========

    /**
     * Initialize the global state UTxO for a new token deployment.
     *
     * @param moduleId  The module identifier (e.g., "kyc")
     * @param request        The initialization request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx and global-state policy ID
     */
    public TransactionContext<GlobalStateInitResult> initGlobalState(
            String moduleId,
            GlobalStateInitRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Initializing global state for module: {}, admin: {}",
                moduleId, request.adminAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var globalStateMgr = getGlobalStateManageable(moduleId, context);

        var txContext = globalStateMgr.buildGlobalStateInitTransaction(request, protocolParams);

        log.info("Global state init transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Add a trusted entity (verification key) to the global state.
     *
     * @param moduleId  The module identifier
     * @param request        The add-entity request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> addTrustedEntity(
            String moduleId,
            AddTrustedEntityRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Adding trusted entity for module: {}, target: {}",
                moduleId, request.verificationKey());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var globalStateMgr = getGlobalStateManageable(moduleId, context);

        var txContext = globalStateMgr.buildAddTrustedEntityTransaction(request, protocolParams);

        log.info("Add trusted entity transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Remove a trusted entity (verification key) from the global state.
     *
     * @param moduleId  The module identifier
     * @param request        The remove-entity request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> removeTrustedEntity(
            String moduleId,
            RemoveTrustedEntityRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Removing trusted entity for module: {}, target: {}",
                moduleId, request.verificationKey());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var globalStateMgr = getGlobalStateManageable(moduleId, context);

        var txContext = globalStateMgr.buildRemoveTrustedEntityTransaction(request, protocolParams);

        log.info("Remove trusted entity transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Update the global state UTxO (pause transfers, mintable amount, security info).
     *
     * @param moduleId  The module identifier
     * @param request        The global state update request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> updateGlobalState(
            String moduleId,
            GlobalStateUpdateRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Updating global state for module: {}, action: {}",
                moduleId, request.action());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var globalStateMgr = getGlobalStateManageable(moduleId, context);

        var txContext = globalStateMgr.buildGlobalStateUpdateTransaction(request, protocolParams);

        log.info("Global state update transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    // ========== Seize Operations ==========

    /**
     * Seize assets from a blacklisted address.
     *
     * @param moduleId  The module identifier
     * @param request        The seize request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> seize(
            String moduleId,
            SeizeRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Seizing assets for module: {}, from: {}, destination: {}",
                moduleId, request.destinationAddress(), request.destinationAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var seizeable = getSeizeable(moduleId, context);

        var txContext = seizeable.buildSeizeTransaction(request, protocolParams);

        log.info("Seize transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    /**
     * Seize assets from multiple UTxOs in a single transaction.
     *
     * @param moduleId  The module identifier
     * @param request        The multi-seize request
     * @param protocolTxHash Optional protocol version tx hash
     * @param context        Optional context for context-aware handlers
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> multiSeize(
            String moduleId,
            MultiSeizeRequest request,
            String protocolTxHash,
            ModuleContext context) {

        log.info("Multi-seizing assets for module: {}, utxo count: {}, destination: {}",
                moduleId, request.utxoReferences().size(), request.destinationAddress());

        var protocolParams = resolveProtocolParams(protocolTxHash);
        var seizeable = getSeizeable(moduleId, context);

        var txContext = seizeable.buildMultiSeizeTransaction(request, protocolParams);

        log.info("Multi-seize transaction built successfully for module: {}", moduleId);
        return txContext;
    }

    // ========== Helper Methods ==========

    /**
     * Resolve protocol bootstrap params from tx hash or use default.
     */
    private ProtocolBootstrapParams resolveProtocolParams(String protocolTxHash) {
        return protocolDeploymentResolver.resolve(protocolTxHash);
    }

    /**
     * Get BlacklistManageable capability from handler.
     */
    private BlacklistManageable getBlacklistManageable(String moduleId, ModuleContext context) {
        var handler = context != null
                ? handlerFactory.getHandler(moduleId, context)
                : handlerFactory.getHandler(moduleId);

        if (handler == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }

        return handler.asBlacklistManageable()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "Module '" + moduleId + "' does not support blacklist management"));
    }

    /**
     * Get WhitelistManageable capability from handler.
     */
    private WhitelistManageable getWhitelistManageable(String moduleId, ModuleContext context) {
        var handler = context != null
                ? handlerFactory.getHandler(moduleId, context)
                : handlerFactory.getHandler(moduleId);

        if (handler == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }

        return handler.asWhitelistManageable()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "Module '" + moduleId + "' does not support whitelist management"));
    }

    /**
     * Get GlobalStateManageable capability from handler.
     */
    private GlobalStateManageable getGlobalStateManageable(String moduleId, ModuleContext context) {
        var handler = context != null
                ? handlerFactory.getHandler(moduleId, context)
                : handlerFactory.getHandler(moduleId);

        if (handler == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }

        return handler.asGlobalStateManageable()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "Module '" + moduleId + "' does not support global state management"));
    }

    /**
     * Get Seizeable capability from handler.
     */
    private Seizeable getSeizeable(String moduleId, ModuleContext context) {
        var handler = context != null
                ? handlerFactory.getHandler(moduleId, context)
                : handlerFactory.getHandler(moduleId);

        if (handler == null) {
            throw new IllegalArgumentException("Unknown module: " + moduleId);
        }

        return handler.asSeizeable()
                .orElseThrow(() -> new UnsupportedOperationException(
                        "Module '" + moduleId + "' does not support seize operations"));
    }
}
