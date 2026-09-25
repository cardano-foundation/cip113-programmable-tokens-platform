package org.cardanofoundation.cip113.scheduling;

import org.cardanofoundation.cip113.config.AppConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The admin signing key: absent refuses, present works, and the shipped file carries no key.
 *
 * <p><strong>Why the last test is the important one.</strong> Until 2026-09-25 this key had a
 * committed 24-word default in {@code application.yaml}, in a PUBLIC repository. Removing it is
 * not enough by itself — history keeps it, and nothing stops a later edit adding a "convenient"
 * default back. So the ABSENCE of a default is asserted against the shipped artefact, the same way
 * {@code spring-boot-config-binding-traps} §4 asserts the shape of an annotation rather than a
 * resolved value: re-adding a default leaves every behavioural test in this class passing.
 *
 * <p>Direct instantiation, not ApplicationContextRunner — the runner can silently not bind, which
 * is a config test that passes with the bug restored, and this repo has been bitten by that before.
 */
class AdminSigningKeyAbsenceTest {

    /** A valid 12-word test mnemonic. NOT the compromised one, and used on no network. */
    private static final String TEST_MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private static AdminSigningKeyProvider provider(String mnemonic, boolean kyc, boolean rwa) {
        return new AdminSigningKeyProvider(mnemonic, kyc, rwa, new AppConfig.Network("preprod"));
    }

    @Test
    @DisplayName("absent key + a consumer enabled REFUSES to start, naming both ways out")
    void absentWithConsumerEnabledRefuses() {
        for (var flags : new boolean[][]{{true, false}, {false, true}, {true, true}}) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> provider("", flags[0], flags[1]),
                    "kycExtended=" + flags[0] + " rwaToken=" + flags[1] + " must refuse");

            var m = failure.getMessage();
            assertTrue(m.contains("KERI_SIGNING_MNEMONIC"),
                    "must name the env var that supplies the key. Got:\n" + m);
            assertTrue(m.contains("KYC_EXTENDED_ENABLED=false") && m.contains("SECURITY_TOKEN_ENABLED=false"),
                    "must name the way to run WITHOUT the key, or the only action it suggests is "
                    + "supplying the compromised default. Got:\n" + m);
            assertTrue(m.contains("compromised"),
                    "must say the removed default is compromised, so nobody pastes it back in");
        }
    }

    @Test
    @DisplayName("absent key + every consumer off is allowed — signing is simply unavailable")
    void absentWithNoConsumerIsAllowed() {
        var p = assertDoesNotThrow(() -> provider("", false, false),
                "a deployment that needs no admin key must still boot");
        assertFalse(p.isAvailable());
        // And it must refuse to hand out a key rather than letting null travel downstream.
        assertThrows(IllegalStateException.class, p::getAdminPkh);
        assertThrows(IllegalStateException.class, p::getAdminAddress);
    }

    @Test
    @DisplayName("a key that IS supplied loads and derives — the refusal is not blanket")
    void presentKeyWorks() {
        var p = provider(TEST_MNEMONIC, true, true);
        assertTrue(p.isAvailable());
        assertTrue(p.getAdminPkh().matches("[0-9a-f]{56}"), "a payment key hash is 28 bytes of hex");
        assertTrue(p.getAdminAddress().startsWith("addr_test1"), "preprod derives a testnet address");
    }

    @Test
    @DisplayName("the SHIPPED application.yaml carries no key material for either property")
    void shippedFileHasNoDefaults() throws IOException {
        var yaml = new String(new ClassPathResource("application.yaml").getInputStream().readAllBytes());

        // The placeholders must survive — removing them would break env binding entirely.
        assertTrue(yaml.contains("${KERI_SIGNING_MNEMONIC:}"),
                "keri.signing-mnemonic must stay declared as ${KERI_SIGNING_MNEMONIC:} — present so the "
                + "env var binds and a profile can override it, with NO default value");
        assertTrue(yaml.contains("${KERI_BRAN:}"),
                "keri.identifier.bran must stay declared as ${KERI_BRAN:}");

        // THE POINT OF THIS TEST: no key material, whatever its value.
        var mnemonicLine = yaml.lines().filter(l -> l.contains("signing-mnemonic:")).findFirst().orElseThrow();
        assertTrue(mnemonicLine.trim().endsWith("${KERI_SIGNING_MNEMONIC:}"),
                "signing-mnemonic must end at the empty placeholder; anything after the colon is key "
                + "material committed to a PUBLIC repository. Got: " + mnemonicLine.trim());
        var branLine = yaml.lines().filter(l -> l.contains("bran:")).findFirst().orElseThrow();
        assertTrue(branLine.trim().endsWith("${KERI_BRAN:}"),
                "bran must end at the empty placeholder. Got: " + branLine.trim());
    }
}
