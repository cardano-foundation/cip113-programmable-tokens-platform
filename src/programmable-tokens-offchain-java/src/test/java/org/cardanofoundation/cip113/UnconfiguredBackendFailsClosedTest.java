package org.cardanofoundation.cip113;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the backstop actually fires instead of merely existing.
 *
 * <p>{@link AbstractPreviewTest} no longer defaults its backend to real Blockfrost preview.
 * Every current subclass is gated on {@code CARDANO_BACKEND_URL}, so in normal use an unset
 * variable disables the test long before the field is read — which means the throw is never
 * exercised by the suite and could rot into a comment that describes behaviour nobody checks.
 *
 * <p>This runs only when the variable is ABSENT, which is the state a fresh clone is in.
 */
@DisabledIfEnvironmentVariable(named = "CARDANO_BACKEND_URL", matches = ".+")
class UnconfiguredBackendFailsClosedTest {

    @Test
    @DisplayName("touching the backend without CARDANO_BACKEND_URL throws rather than resolving to a public testnet")
    void unconfiguredBackendFailsClosed() {
        Throwable thrown = assertThrows(Throwable.class,
                () -> { @SuppressWarnings("unused") String ignored = AbstractPreviewTest.BACKEND_URL; },
                "reading BACKEND_URL with no CARDANO_BACKEND_URL set did not throw — it resolved to "
                        + "something, and the only thing it used to resolve to was real Blockfrost preview");

        // Class initialisation wraps the cause, so walk to the root.
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertNotNull(root.getMessage(), "fail-closed error carried no message");
        assertTrue(root.getMessage().contains("CARDANO_BACKEND_URL"),
                "the error should name the variable that is missing, got: " + root.getMessage());
        assertTrue(root.getMessage().contains("no default"),
                "the error should say there is no default, got: " + root.getMessage());
    }
}
