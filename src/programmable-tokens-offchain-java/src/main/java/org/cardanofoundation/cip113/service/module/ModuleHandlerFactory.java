package org.cardanofoundation.cip113.service.module;

import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.service.module.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.module.capabilities.BlacklistManageable;
import org.cardanofoundation.cip113.service.module.capabilities.Seizeable;
import org.cardanofoundation.cip113.service.module.capabilities.WhitelistManageable;
import org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext;
import org.cardanofoundation.cip113.service.module.context.KycContext;
import org.cardanofoundation.cip113.service.module.context.KycExtendedContext;
import org.cardanofoundation.cip113.service.module.context.RwaTokenContext;
import org.cardanofoundation.cip113.service.module.context.ModuleContext;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Factory service for creating and managing module handlers.
 *
 * <p>This factory supports two types of handlers:</p>
 * <ul>
 *   <li><b>Simple handlers</b> (like Dummy) - Singleton, no context needed</li>
 *   <li><b>Context-aware handlers</b> (like FreezeAndSeize) - Prototype scope, require context</li>
 * </ul>
 *
 * <h2>Usage Examples:</h2>
 * <pre>{@code
 * // Simple handler (Dummy)
 * ModuleHandler handler = factory.getHandler("dummy");
 *
 * // Context-aware handler (FreezeAndSeize)
 * var context = FreezeAndSeizeContext.forExistingDeployment(...);
 * ModuleHandler handler = factory.getHandler("freeze-and-seize", context);
 *
 * // Check capabilities
 * if (handler.supportsSeize()) {
 *     handler.asSeizeable().get().buildSeizeTransaction(...);
 * }
 * }</pre>
 */
@Service
@Slf4j
public class ModuleHandlerFactory {

    private final Map<String, ModuleHandler> simpleHandlers = new HashMap<>();
    private final Set<String> contextAwareModules = new HashSet<>();
    private final ApplicationContext applicationContext;

    /**
     * Constructor that auto-registers all ModuleHandler beans.
     *
     * @param handlerList        List of all ModuleHandler beans from Spring context
     * @param applicationContext Spring application context for creating prototype beans
     */
    public ModuleHandlerFactory(List<ModuleHandler> handlerList,
                                     ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;

        for (ModuleHandler handler : handlerList) {
            String id = handler.getModuleId().toLowerCase();

            // Prototype-scoped handlers that require runtime context.
            if (handler instanceof FreezeAndSeizeHandler
                    || handler instanceof KycModuleHandler
                    || handler instanceof KycExtendedModuleHandler
                    || handler instanceof RwaTokenModuleHandler) {
                contextAwareModules.add(id);
                log.info("Registered context-aware module: {}", id);
            } else {
                simpleHandlers.put(id, handler);
                log.info("Registered simple module: {} (capabilities: {})",
                        id, describeCapabilities(handler));
            }
        }
    }

    /**
     * Get a handler for the specified module (for simple handlers).
     *
     * @param moduleId The module identifier (e.g., "dummy")
     * @return The handler for the module, or null if not found
     * @throws IllegalStateException if the module requires context
     */
    public ModuleHandler getHandler(String moduleId) {
        String normalizedId = moduleId.toLowerCase();

        if (contextAwareModules.contains(normalizedId)) {
            throw new IllegalStateException(
                    "Module '" + moduleId + "' requires context. " +
                    "Use getHandler(moduleId, context) instead.");
        }

        return simpleHandlers.get(normalizedId);
    }

    /**
     * Get a handler for the specified module with context.
     *
     * @param moduleId The module identifier (e.g., "freeze-and-seize")
     * @param context       The context for this handler instance
     * @return The configured handler for the module
     */
    public ModuleHandler getHandler(String moduleId, ModuleContext context) {
        String normalizedId = moduleId.toLowerCase();

        // Simple handlers don't need context
        if (simpleHandlers.containsKey(normalizedId)) {
            log.debug("Returning simple handler for '{}' (context ignored)", moduleId);
            return simpleHandlers.get(normalizedId);
        }

        // Context-aware handlers
        if (contextAwareModules.contains(normalizedId)) {
            return createContextAwareHandler(normalizedId, context);
        }

        log.warn("No handler found for module: {}", moduleId);
        return null;
    }

    /**
     * Create a new instance of a context-aware handler with the given context.
     */
    private ModuleHandler createContextAwareHandler(String moduleId, ModuleContext context) {
        if ("freeze-and-seize".equals(moduleId)) {
            if (!(context instanceof FreezeAndSeizeContext fasContext)) {
                throw new IllegalArgumentException(
                        "freeze-and-seize handler requires FreezeAndSeizeContext, got: " +
                        (context != null ? context.getClass().getSimpleName() : "null"));
            }

            // Get a new prototype instance from Spring
            FreezeAndSeizeHandler handler = applicationContext.getBean(FreezeAndSeizeHandler.class);
            handler.setContext(fasContext);
            log.debug("Created FreezeAndSeizeHandler with context: {}", fasContext);
            return handler;
        }

        if ("kyc".equals(moduleId)) {
            if (!(context instanceof KycContext kycContext)) {
                throw new IllegalArgumentException(
                        "kyc handler requires KycContext, got: " +
                        (context != null ? context.getClass().getSimpleName() : "null"));
            }

            KycModuleHandler handler = applicationContext.getBean(KycModuleHandler.class);
            handler.setContext(kycContext);
            log.debug("Created KycModuleHandler with context: {}", kycContext);
            return handler;
        }

        if ("kyc-extended".equals(moduleId)) {
            if (!(context instanceof KycExtendedContext kycExtCtx)) {
                throw new IllegalArgumentException(
                        "kyc-extended handler requires KycExtendedContext, got: " +
                        (context != null ? context.getClass().getSimpleName() : "null"));
            }

            KycExtendedModuleHandler handler = applicationContext.getBean(KycExtendedModuleHandler.class);
            handler.setContext(kycExtCtx);
            log.debug("Created KycExtendedModuleHandler with context: {}", kycExtCtx);
            return handler;
        }

        if ("rwa-token".equals(moduleId)) {
            if (!(context instanceof RwaTokenContext stCtx)) {
                throw new IllegalArgumentException(
                        "rwa-token handler requires RwaTokenContext, got: " +
                        (context != null ? context.getClass().getSimpleName() : "null"));
            }

            RwaTokenModuleHandler handler =
                    applicationContext.getBean(RwaTokenModuleHandler.class);
            handler.setContext(stCtx);
            log.debug("Created RwaTokenModuleHandler with context: {}", stCtx);
            return handler;
        }

        throw new IllegalStateException("Unknown context-aware module: " + moduleId);
    }

    /**
     * Check if a module handler is registered.
     *
     * @param moduleId The module identifier
     * @return true if handler exists, false otherwise
     */
    public boolean hasHandler(String moduleId) {
        String normalizedId = moduleId.toLowerCase();
        return simpleHandlers.containsKey(normalizedId) ||
               contextAwareModules.contains(normalizedId);
    }

    /**
     * Check if a module requires context.
     *
     * @param moduleId The module identifier
     * @return true if context is required, false otherwise
     */
    public boolean requiresContext(String moduleId) {
        return contextAwareModules.contains(moduleId.toLowerCase());
    }

    /**
     * Get all registered module IDs.
     *
     * @return Set of registered module IDs
     */
    public Set<String> getRegisteredModules() {
        Set<String> all = new HashSet<>(simpleHandlers.keySet());
        all.addAll(contextAwareModules);
        return all;
    }

    /**
     * Get capability description for a handler.
     */
    private String describeCapabilities(ModuleHandler handler) {
        List<String> caps = new ArrayList<>();
        if (handler instanceof BasicOperations) caps.add("BasicOperations");
        if (handler instanceof BlacklistManageable) caps.add("BlacklistManageable");
        if (handler instanceof WhitelistManageable) caps.add("WhitelistManageable");
        if (handler instanceof Seizeable) caps.add("Seizeable");
        return String.join(", ", caps);
    }

    // ========== Convenience Methods for Capability Access ==========

    /**
     * Get a handler's BasicOperations capability if supported.
     */
    @SuppressWarnings("rawtypes")
    public Optional<BasicOperations> getBasicOperations(String moduleId) {
        var handler = getHandler(moduleId);
        return handler != null ? handler.asBasicOperations() : Optional.empty();
    }

    /**
     * Get a handler's BasicOperations capability with context if supported.
     */
    @SuppressWarnings("rawtypes")
    public Optional<BasicOperations> getBasicOperations(String moduleId, ModuleContext context) {
        var handler = getHandler(moduleId, context);
        return handler != null ? handler.asBasicOperations() : Optional.empty();
    }

    /**
     * Get a handler's BlacklistManageable capability if supported.
     */
    public Optional<BlacklistManageable> getBlacklistManageable(String moduleId, ModuleContext context) {
        var handler = getHandler(moduleId, context);
        return handler != null ? handler.asBlacklistManageable() : Optional.empty();
    }

    /**
     * Get a handler's Seizeable capability if supported.
     */
    public Optional<Seizeable> getSeizeable(String moduleId, ModuleContext context) {
        var handler = getHandler(moduleId, context);
        return handler != null ? handler.asSeizeable() : Optional.empty();
    }
}
