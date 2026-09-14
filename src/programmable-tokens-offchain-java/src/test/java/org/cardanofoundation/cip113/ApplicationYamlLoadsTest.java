package org.cardanofoundation.cip113;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * application.yaml must parse the way Spring parses it — duplicates forbidden.
 *
 * <h2>Why a test and not "it obviously works"</h2>
 *
 * A duplicate top-level key inside ONE YAML document is not an override. Spring Boot loads
 * with {@code allowDuplicateKeys=false}, so it is a {@code DuplicateKeyException} that kills
 * the application at startup — after the image is built, after CI is green, with nothing in
 * any unit test to hint at it.
 *
 * <p>This file is multi-document and the FIRST document is the unprofiled one, which also
 * carries {@code network: mainnet}. That makes "add a block next to the network key" a
 * plausible-looking edit that lands in the base document rather than in a profile — which is
 * exactly how a second {@code uplc-link:} got in, forty lines from the first.
 *
 * <p>The parser below is configured identically to Spring's, so this fails for the same
 * reason and with the same message, at build time instead of at boot.
 */
class ApplicationYamlLoadsTest {

    @Test
    @DisplayName("application.yaml parses with duplicate keys forbidden, as Spring loads it")
    void parsesWithoutDuplicateKeys() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        List<Object> documents = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            assertNotNull(in, "application.yaml is not on the classpath");
            try {
                yaml.loadAll(in).forEach(documents::add);
            } catch (Exception e) {
                fail("application.yaml does not load the way Spring loads it, so the "
                        + "application would fail at startup: " + e.getMessage(), e);
            }
        }

        assertTrue(documents.size() >= 2,
                "expected several profile documents, found " + documents.size());
    }

    @Test
    @DisplayName("every profile document declares at most one uplc-link block")
    void uplcLinkDeclaredOncePerDocument() throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));

        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            for (Object document : yaml.loadAll(in)) {
                if (!(document instanceof Map<?, ?> map)) continue;
                // Reaching here at all means no duplicates: the loader above would have thrown.
                // What this adds is the reason the key matters -- a devnet or profile document
                // that silently inherits mainnet's registry host is a wrong answer, not a crash.
                Object uplcLink = map.get("uplc-link");
                if (uplcLink != null) {
                    assertTrue(uplcLink instanceof Map,
                            "uplc-link must be a mapping, got " + uplcLink.getClass().getSimpleName());
                }
            }
        }
    }
}
