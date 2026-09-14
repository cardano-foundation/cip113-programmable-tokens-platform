package org.cardanofoundation.cip113.cip171;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the uplc.link URL, because getting it wrong is invisible.
 *
 * <p>The first version of {@link UplcLinkClient} was written against
 * {@code preview.uplc.link/api/registry?action=byHash}. That host is the SITE — a Next.js app
 * whose {@code /api/registry} is an internal proxy — and it answers 404 for that path. A 404
 * is exactly what "this script has no published record" looks like, so every lookup failed
 * while appearing to work: the resolver simply never matched anything.
 *
 * <p>There is no assertion a running system could have made to catch that, which is why the
 * URL is pinned here instead. Measured hosts: mainnet {@code api.uplc.link}, preview
 * {@code preview-api.uplc.link}, preprod {@code preprod-api.uplc.link}.
 */
class UplcLinkUrlTest {

    @Test
    @DisplayName("lookups use the API route, not the site's internal proxy")
    void byHashPathIsTheApiRoute() {
        var hash = "278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d";
        assertEquals("/api/v1/scripts/by-hash/" + hash, UplcLinkClient.byHashPath(hash));
    }

    @Test
    @DisplayName("every network profile points at an API host, never at the site host")
    void profilesUseApiHosts() throws Exception {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            assertNotNull(in, "application.yaml is not on the classpath");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(yaml.contains("https://preview-api.uplc.link"),
                "preview must use the API host preview-api.uplc.link");
        assertTrue(yaml.contains("https://preprod-api.uplc.link"),
                "preprod must use the API host preprod-api.uplc.link");
        assertTrue(yaml.contains("https://api.uplc.link"),
                "mainnet must use the API host api.uplc.link");

        // The site hosts answer 404 for the API route and would look like an empty registry.
        assertFalse(yaml.contains("https://preview.uplc.link"),
                "preview.uplc.link is the SITE, not the API — lookups against it always 404");
        assertFalse(yaml.contains("https://preprod.uplc.link"),
                "preprod.uplc.link is the SITE, not the API");
        // mainnet-api.uplc.link does not resolve; mainnet's API host is api.uplc.link.
        assertFalse(yaml.contains("mainnet-api.uplc.link"),
                "mainnet-api.uplc.link does not resolve — mainnet's API host is api.uplc.link");
    }
}
