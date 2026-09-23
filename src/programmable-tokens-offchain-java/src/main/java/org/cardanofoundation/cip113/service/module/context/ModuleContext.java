package org.cardanofoundation.cip113.service.module.context;

/**
 * Base interface for module-specific context/configuration.
 * <p>
 * Some modules (like freeze-and-seize) can have multiple instances
 * (e.g., multiple stablecoins), each with their own configuration.
 * This context carries instance-specific parameters needed to build transactions.
 * <p>
 * Simple modules like "dummy" don't need context and can use {@link EmptyContext}.
 */
public interface ModuleContext {

    /**
     * Get the module ID this context is for.
     *
     * @return The module identifier (e.g., "freeze-and-seize", "dummy")
     */
    String getModuleId();

    /**
     * Empty context for modules that don't need configuration.
     */
    record EmptyContext(String moduleId) implements ModuleContext {
        @Override
        public String getModuleId() {
            return moduleId;
        }

        public static EmptyContext forModule(String moduleId) {
            return new EmptyContext(moduleId);
        }
    }
}
