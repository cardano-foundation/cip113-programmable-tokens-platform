package org.cardanofoundation.cip113.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KERI identifier and its credential registry are a PAIR, and both halves must be settable.
 *
 * <p><strong>Why.</strong> {@code keri.identifier.name} names an identifier on the KERI agent and
 * {@code keri.identifier.registry-name} names the credential registry that identifier owns. They
 * have moved together once already — {@code identity}/{@code kyc-registry} became
 * {@code identity2}/{@code kyc-registry2} — and until 2026-09-29 only {@code name} carried a
 * placeholder. A deployment could therefore pin half the pair and get an identifier pointed at a
 * registry it does not own, which fails at the AGENT and says nothing about configuration.
 * Reported from the cardano-production chart, which was pinning {@code identity} against an image
 * whose registry had become {@code kyc-registry2}.
 *
 * <p>⛔ AND THE CODE USED TO DISAGREE WITH THE FILE. {@code KeriService} read
 * {@code ${keri.identifier.registry-name:kyc-registry}} — an inline default naming the OLD
 * registry, while the yaml said {@code kyc-registry2}. Two sources of truth, silently divergent:
 * removing the yaml key would have pointed a current deployment at the previous registry rather
 * than failing. The inline default is gone, so there is exactly one.
 */
class KeriIdentifierPairTest {

    private static StandardEnvironment environmentWith(Map<String, Object> systemEnvironment)
            throws IOException {
        var documents = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        var environment = new StandardEnvironment();
        var sources = environment.getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.addFirst(documents.get(0));
        sources.addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, systemEnvironment));
        return environment;
    }

    @Test
    @DisplayName("both halves of the pair are settable, by the names the file declares")
    void bothHalvesAreSettable() throws IOException {
        var set = environmentWith(Map.of(
                "KERI_IDENTIFIER_NAME", "identity9",
                "KERI_REGISTRY_NAME", "kyc-registry9"));

        assertEquals("identity9", set.getProperty("keri.identifier.name"),
                "KERI_IDENTIFIER_NAME must reach keri.identifier.name");
        assertEquals("kyc-registry9", set.getProperty("keri.identifier.registry-name"),
                "KERI_REGISTRY_NAME must reach keri.identifier.registry-name — without this, a chart "
                + "can pin the identifier and not the registry, which is how they come apart");
    }

    @Test
    @DisplayName("unset leaves the image's own pair, and the two agree")
    void unsetLeavesAMatchingPair() throws IOException {
        var bare = environmentWith(Map.of());
        var name = bare.getProperty("keri.identifier.name");
        var registry = bare.getProperty("keri.identifier.registry-name");

        assertEquals("identity2", name);
        assertEquals("kyc-registry2", registry);

        // ⚑ The pairing is a CONVENTION the agent enforces, not something this file can check. The
        // most it can do is notice the two have stopped sharing a suffix, which is what happened
        // when one was pinned and the other was not.
        var nameSuffix = name.replaceAll("^[a-z-]*", "");
        var registrySuffix = registry.replaceAll("^[a-z-]*", "");
        assertEquals(nameSuffix, registrySuffix,
                "identifier '" + name + "' and registry '" + registry + "' no longer share a suffix. "
                + "If that is deliberate, update this test; if it is not, one half of the pair has "
                + "been changed without the other.");
    }

    @Test
    @DisplayName("the registry name has exactly ONE source of truth — no inline default in the code")
    void noInlineDefaultInTheCode() throws IOException {
        var source = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/org/cardanofoundation/cip113/service/KeriService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("${keri.identifier.registry-name}"),
                "KeriService should read the property with NO inline default");
        // ⛔ THE REGRESSION THIS EXISTS FOR: an inline default here silently overrides nothing and
        // decides everything the moment the yaml key goes missing — and it named the OLD registry.
        assertTrue(!source.contains("${keri.identifier.registry-name:"),
                "KeriService must not carry an inline default for the registry name: it would be a "
                + "second source of truth, and the one it had named the previous registry");
    }
}
