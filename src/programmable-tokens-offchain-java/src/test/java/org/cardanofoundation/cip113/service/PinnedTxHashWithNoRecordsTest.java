package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A PINNED default txHash plus an EMPTY record file — the state that made allow-no-deployment
 * useless on preview.
 *
 * <p><strong>What went wrong.</strong> {@code allow-no-deployment} was added so the service could
 * start before a bootstrap existed, and {@link StartWithoutDeploymentTest} proves it does. But the
 * flag only guarded the "no usable record" refusal. Four lines further down, {@code init()} resolves
 * {@code programmable.token.default.txHash} against the records it just loaded and threw if the pin
 * matched nothing — outside the guard.
 *
 * <p>With an empty file a pin can never match, so on any profile that pins a hash the flag skipped
 * the first refusal and then hit the second regardless. The preview profile pins one while
 * {@code protocol-bootstraps-preview.json} is {@code []} in the shipped jar, so preview could not start either way.
 * The observed failure named the pin, which reads as a configuration error rather than as the flag
 * not covering this path.
 *
 * <p>⛔ The boundary is whether there was anything to match, NOT whether the pin resolved. Records
 * present and the pin matching none is a real misconfiguration and stays fatal; no records at all
 * is the very state the flag is for.
 */
class PinnedTxHashWithNoRecordsTest {

    /** The hash the preview profile actually pins. */
    private static final String PINNED =
            "8314e59f3e240ba89fb7f7037cf3094307132ede0ac3a1d2515a09a9cc333bc8";

    private static ProtocolBootstrapService serviceFor(String defaultTxHash, boolean allowNoDeployment) {
        // ⚑ preprod, NOT preview, and the reason matters: src/test/resources holds a SHADOW
        // protocol-bootstraps-preview.json carrying one record, so on the test classpath preview
        // is not empty and cannot reproduce this at all. preprod has no shadow, so it resolves to
        // the shipped [] — the same empty-file state preview has in production.
        var service = new ProtocolBootstrapService(new ObjectMapper(), new AppConfig.Network("preprod"));
        ReflectionTestUtils.setField(service, "defaultTxHash", defaultTxHash);
        ReflectionTestUtils.setField(service, "allowNoDeployment", allowNoDeployment);
        return service;
    }

    @Test
    @DisplayName("a pinned txHash with NO records starts when the flag is on — the reported failure")
    void pinnedHashWithNoRecordsStartsUnderTheFlag() {
        var service = serviceFor(PINNED, true);

        assertDoesNotThrow(service::init,
                "the flag exists precisely for a file that records no deployment. A pinned hash "
                + "cannot match an empty file, so it must not turn into a refusal that the flag "
                + "cannot switch off.");

        // Still refuses to serve, so nothing gets a wrong answer — the flag moves the failure,
        // it does not make operations possible.
        var refusal = assertThrows(IllegalStateException.class, service::getProtocolBootstrapParams);
        assertTrue(refusal.getMessage().contains("allow-no-deployment"),
                "the accessor must still refuse and name the flag. Got:\n" + refusal.getMessage());
    }

    @Test
    @DisplayName("without the flag, a pinned txHash and no records still refuses to start")
    void pinnedHashWithNoRecordsStillRefusesWithoutTheFlag() {
        var failure = assertThrows(IllegalStateException.class, () -> serviceFor(PINNED, false).init(),
                "the default must not change: no deployment means no start");

        // It should fail on the NO-DEPLOYMENT refusal, which explains the situation, rather than on
        // the pin, which reads as a typo in configuration.
        assertTrue(failure.getMessage().contains("NOT A DEADLOCK"),
                "the refusal a reader sees first must be the one that explains the state, not the "
                + "one about the pin. Got:\n" + failure.getMessage());
    }

    @Test
    @DisplayName("no pin + no records + flag on still starts — the original path is unchanged")
    void noPinStillWorks() {
        assertDoesNotThrow(() -> serviceFor("", true).init());
        assertNotNull(serviceFor("", true), "sanity");
    }
}
