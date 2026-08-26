package org.cardanofoundation.cip113.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Provenance guard for the compiled contract blueprints this backend ships.
 *
 * <h2>What replaced what</h2>
 *
 * The Aiken source for both contract sets was vendored into this repository and checked by a
 * pair of tests that hashed every file in each tree. The source is gone — the backend only
 * ever consumed {@code plutus.json}, and the trees duplicated what upstream already owns.
 *
 * <p>Removing them costs the ability to rebuild or audit the blueprints from inside this
 * repository; that trade was made deliberately and both remain reproducible outside it (see
 * {@code docs/CONTRACTS.md}). What must NOT be lost with them is the answer to "which
 * upstream revision is this?" — before any of it existed, recovering that took scanning 270
 * upstream commits comparing validator hashes.
 *
 * <p>So this keeps the cheap half of the old guard: every shipped blueprint is pinned by
 * sha256 against {@code contracts-pin.json}, which also records the repository, commit and
 * compiler behind it. An accidental edit still fails here, at the file that was edited.
 *
 * <p>What it can no longer prove is that those bytes correspond to the source at that
 * commit. That check is now a manual step, documented rather than automated — which is the
 * honest consequence of dropping the trees, and is why the pin file records the compiler
 * version and, for a blueprint that is not verbatim, upstream's own hash too.
 *
 * @see CoreBlueprintSurfaceTest which checks the complementary thing — that the backend's
 *      expectations still match the bytes
 */
class ContractBlueprintPinTest {

    private static final String PIN_RESOURCE = "/contracts-pin.json";

    @Test
    @DisplayName("every shipped blueprint matches its pinned sha256")
    void blueprintsMatchTheirPins() throws Exception {
        JsonNode pin = readJson(PIN_RESOURCE);
        JsonNode blueprints = pin.get("blueprints");
        assertNotNull(blueprints, "contracts-pin.json has no `blueprints` array");
        assertTrue(blueprints.size() >= 2,
                "expected a pin for the core and the rwa-token blueprint, found " + blueprints.size());

        List<String> problems = new ArrayList<>();
        for (JsonNode bp : blueprints) {
            String name = bp.get("name").asText();
            String resource = "/" + bp.get("resource").asText();
            String expected = bp.get("sha256").asText();

            byte[] bytes = readBytes(resource);
            if (bytes == null) {
                problems.add(name + ": resource " + resource + " is not on the classpath");
                continue;
            }
            String actual = sha256(bytes);
            if (!expected.equals(actual)) {
                problems.add(name + " (" + resource + "):\n    pinned " + expected
                        + "\n    actual " + actual);
            }
        }

        if (!problems.isEmpty()) {
            fail("A shipped contract blueprint does not match its pin.\n"
                    + "These files are compiled artifacts copied from upstream — they are not edited here.\n"
                    + "If you are deliberately adopting a new upstream revision, update contracts-pin.json\n"
                    + "(commit, sha256, compiler) and work through CoreBlueprintSurfaceTest's diff.\n"
                    + "See docs/CONTRACTS.md.\n\n"
                    + String.join("\n", problems));
        }
    }

    /**
     * The pin is only useful if it says where the bytes came from. A sha256 alone would make
     * the file self-consistent and still leave "which upstream revision is this?" unanswerable
     * — the exact gap that made the last upgrade expensive.
     */
    @Test
    @DisplayName("every pin records enough provenance to find the source again")
    void pinsRecordProvenance() throws Exception {
        for (JsonNode bp : readJson(PIN_RESOURCE).get("blueprints")) {
            String name = bp.get("name").asText();
            for (String field : List.of("repository", "commit", "aiken_compiler", "resource", "sha256")) {
                assertTrue(bp.hasNonNull(field), name + " pin is missing " + field);
            }
            assertEquals(40, bp.get("commit").asText().length(),
                    name + " must pin a full 40-character upstream commit SHA");

            // A blueprint that is NOT upstream's own bytes has to say so and record upstream's
            // hash, or nobody can tell later whether upstream has fixed the discrepancy.
            if (!bp.path("verbatim_from_upstream").asBoolean(true)) {
                assertTrue(bp.hasNonNull("upstream_committed_sha256"),
                        name + " is not verbatim from upstream, so it must record "
                                + "upstream_committed_sha256 — otherwise the divergence becomes "
                                + "permanent and invisible");
                assertTrue(bp.hasNonNull("note"),
                        name + " is not verbatim from upstream and must explain why");
            }
        }
    }

    private static JsonNode readJson(String resource) throws Exception {
        try (InputStream in = ContractBlueprintPinTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " is not on the classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    private static byte[] readBytes(String resource) throws Exception {
        try (InputStream in = ContractBlueprintPinTest.class.getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
