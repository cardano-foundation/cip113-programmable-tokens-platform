package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting with no deployment recorded — the window before a bootstrap.
 *
 * <p><strong>Why this exists.</strong> Refusing to start is right for a service whose job is
 * indexing a protocol instance. But it left the period BEFORE a bootstrap blind: the database,
 * the migrations and the node peer are configured independently of any protocol, and there was
 * no way to find out whether they worked until after a ceremony had spent one-shot seeds that
 * cannot be recovered.
 *
 * <p><strong>Direct instantiation, not ApplicationContextRunner.</strong> The runner silently
 * does not bind without {@code @EnableConfigurationProperties} — a config test that passes with
 * the bug restored, which this repo has been bitten by before. Setting the fields and calling
 * {@code init()} tests the actual code path with nothing between.
 */
class StartWithoutDeploymentTest {

    /** preprod's record is an empty array — the real state before a bootstrap. */
    private static ProtocolBootstrapService serviceFor(boolean allowNoDeployment) {
        var service = new ProtocolBootstrapService(new ObjectMapper(), new AppConfig.Network("preprod"));
        ReflectionTestUtils.setField(service, "defaultTxHash", "");
        ReflectionTestUtils.setField(service, "allowNoDeployment", allowNoDeployment);
        return service;
    }

    @Test
    @DisplayName("without the flag it still refuses to start — the default is unchanged")
    void refusesByDefault() {
        var failure = assertThrows(IllegalStateException.class, () -> serviceFor(false).init());

        // The message must carry the three things that were missing when this cost an afternoon.
        assertTrue(failure.getMessage().contains("NOT A DEADLOCK"),
                "the refusal must say the bootstrap page does not need this service; it previously "
                + "said 'deploy and record the current protocol' from a service that had just "
                + "refused to boot, which reads as a deadlock. Got:\n" + failure.getMessage());
        assertTrue(failure.getMessage().contains("cip113.allow-no-deployment=true"),
                "the refusal must name the way out");
        assertTrue(failure.getMessage().contains("protocol-bootstraps-preprod.json"),
                "the refusal must name the file it read");
    }

    @Test
    @DisplayName("the version in the message is READ FROM THE BLUEPRINT, not written into the code")
    void versionIsDerivedNotHardcoded() {
        var failure = assertThrows(IllegalStateException.class, () -> serviceFor(false).init());

        // ⛔ THE POINT OF THIS TEST. The literal went stale twice in three releases —
        // alpha.4 -> alpha.5 -> 0.0.1 — and each time it sent somebody looking at their config
        // for a mismatch that did not exist. Asserting the CURRENT version would just move the
        // staleness into the test, so this asserts the message agrees with the shipped artifact,
        // whatever that is.
        var shipped = new ProtocolBootstrapService(new ObjectMapper(), new AppConfig.Network("preprod"));
        ReflectionTestUtils.setField(shipped, "defaultTxHash", "");
        ReflectionTestUtils.setField(shipped, "allowNoDeployment", true);
        shipped.init();
        var version = shipped.getPlutus().preamble().version();

        assertTrue(failure.getMessage().contains(version),
                "the refusal names a version the shipped blueprint does not declare (" + version
                        + "). Got:\n" + failure.getMessage());
        assertTrue(!failure.getMessage().contains("alpha.4") && !failure.getMessage().contains("alpha.5"),
                "a superseded version is hardcoded in the refusal again");
    }

    @Test
    @DisplayName("with the flag it starts — and still refuses to answer")
    void startsButRefusesToServe() {
        var service = serviceFor(true);
        assertDoesNotThrow(service::init, "the flag must let the context come up");

        // Starting is not the same as working, and the accessor is where that is enforced.
        // Thirteen call sites reach it and none checks for null, so returning null would have
        // surfaced as an NPE several frames from the cause.
        var refusal = assertThrows(IllegalStateException.class, service::getProtocolBootstrapParams);
        assertTrue(refusal.getMessage().contains("does not make protocol operations possible"),
                "the accessor must say the flag did not make this work. Got:\n" + refusal.getMessage());

        // The blueprint IS loaded, which is what makes the started process worth having.
        assertTrue(service.getPlutus() != null, "the blueprint should still load without a deployment");
    }
}
