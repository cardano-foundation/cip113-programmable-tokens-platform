package org.cardanofoundation.cip113.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The blueprint served by {@code GET /protocol/blueprint} must still know which protocol it is.
 *
 * <h2>The defect this exists for</h2>
 *
 * {@code Plutus} was {@code record Plutus(List<Validator> validators)}. With
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} the preamble was discarded on read and
 * absent on write, so the endpoint served correct validators with no version on them. Nothing
 * failed at the boundary — the frontend's type declares {@code preamble} optional and filled in
 * {@code {title: "unknown", version: "0.0.0"}} — and the loss only surfaced once the SDK began
 * asserting the version, as <em>Blueprint "unknown v0.0.0" targets an EARLIER CIP-113 protocol
 * version</em> alongside "every required validator title IS present".
 *
 * <p>A round trip is what is asserted, not merely that the record has a field: the failure was
 * that the preamble did not survive being read and re-serialized, which is exactly what the API
 * does to it.
 */
class BlueprintPreambleSurvivesApiBoundaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("the core blueprint's preamble survives deserialize -> serialize, with its compiler")
    void preambleSurvivesRoundTrip() throws Exception {
        Plutus plutus;
        try (InputStream in = getClass().getResourceAsStream("/plutus.json")) {
            assertNotNull(in, "/plutus.json is not on the classpath");
            plutus = MAPPER.readValue(in, Plutus.class);
        }

        assertNotNull(plutus.preamble(), "preamble was dropped when reading plutus.json");
        assertEquals("cardano-foundation/cip113-programmable-tokens", plutus.preamble().title());
        assertEquals("0.5.0-alpha.4", plutus.preamble().version());
        assertNotNull(plutus.preamble().compiler(), "compiler dropped — the SDK's provenance gate reads it");
        assertEquals("Aiken", plutus.preamble().compiler().name());
        assertTrue(plutus.preamble().compiler().version().startsWith("v1.1.23"),
                "unexpected compiler: " + plutus.preamble().compiler().version());

        // What the controller actually hands back.
        JsonNode served = MAPPER.readTree(MAPPER.writeValueAsString(plutus));
        assertTrue(served.hasNonNull("preamble"),
                "the SERVED blueprint has no preamble — consumers cannot tell which protocol "
                        + "version these validators belong to, and an SDK version gate will read "
                        + "\"unknown v0.0.0\"");
        assertEquals("0.5.0-alpha.4", served.path("preamble").path("version").asText());
        assertEquals("cardano-foundation/cip113-programmable-tokens",
                served.path("preamble").path("title").asText());
    }

    @Test
    @DisplayName("every served validator carries its hash, not just title and compiledCode")
    void validatorsCarryTheirHash() throws Exception {
        Plutus plutus;
        try (InputStream in = getClass().getResourceAsStream("/plutus.json")) {
            plutus = MAPPER.readValue(in, Plutus.class);
        }
        assertTrue(plutus.validators().size() > 0, "no validators parsed");
        for (var v : plutus.validators()) {
            assertNotNull(v.hash(), v.title() + " was served with a null hash");
            assertEquals(56, v.hash().length(), v.title() + " hash is not a 28-byte script hash");
        }
    }
}
