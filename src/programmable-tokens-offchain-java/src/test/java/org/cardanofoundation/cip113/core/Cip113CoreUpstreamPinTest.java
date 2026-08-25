package org.cardanofoundation.cip113.core;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.cip113.pin.UpstreamPin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard for {@code src/core-contracts} — the vendored CIP-113 core contract tree.
 *
 * <h2>Why this directory exists at all</h2>
 *
 * Until this guard was written the backend shipped {@code src/main/resources/plutus.json}
 * with <em>no record of where it came from</em>. Recovering that took a scan of all 270
 * upstream commits, comparing every validator hash, to establish it corresponded to
 * {@code 726d757} — a commit on a feature branch that was never on {@code main}. Nobody
 * could have learned that by reading the repository. An upgrade therefore began by not
 * knowing what it was upgrading <em>from</em>, which is the expensive half of an upgrade.
 *
 * <p>The vendored tree also carries upstream's {@code documentation/}, unlike the
 * rwa-token pin which excludes its README. That is deliberate: the core documentation is
 * the specification the Java builders are written against — withdrawal-index derivation,
 * redeemer shapes, transaction composition — and it drifts from the validators on its own.
 * Pinning it turns a doc change into a reviewable diff instead of an invisible one.
 *
 * <p>This is the offline half of the pin, so it runs on every build. The online half —
 * proving the manifest was not regenerated over a tree nobody reviewed — is
 * {@code src/core-contracts/verify-upstream-pin.sh}, which re-downloads the pinned
 * tarball and diffs it.
 *
 * @see UpstreamPin the shared mechanics, also used by the rwa-token pin
 * @see CoreBlueprintSurfaceTest which checks the complementary thing: that the backend's
 *      expectations still match these bytes
 */
class Cip113CoreUpstreamPinTest {

    private static final String VENDORED_DIR = "src/core-contracts";

    @Test
    @DisplayName("vendored core tree matches its pinned upstream manifest")
    void vendoredTreeMatchesPin() throws Exception {
        UpstreamPin pin = UpstreamPin.load(VENDORED_DIR);
        assertEquals(40, pin.json().get("commit").asText().length(),
                "UPSTREAM_PIN.json must record a full 40-char upstream commit SHA");
        assertNull(pin.verifyTree());
    }

    @Test
    @DisplayName("backend resource copy of plutus.json is byte-identical to the vendored blueprint")
    void backendResourceCopyIsInSync() throws Exception {
        assertNull(UpstreamPin.load(VENDORED_DIR).verifyBackendResourceCopy());
    }

    /**
     * The core pin is vendored verbatim from a published commit, so it must claim the
     * blueprint reproduces. That claim is not decoration here: it was independently
     * corroborated, because the backend was already running a local aiken
     * {@code v1.1.23+8949565} rebuild of this exact source and it reproduced every
     * validator's {@code compiledCode} byte for byte against upstream's
     * {@code v1.1.22+39d6b04} build. Two compiler versions, identical output.
     *
     * <p>If a future re-pin ever has to vendor a blueprint that does <em>not</em>
     * reproduce — the situation the rwa-token pin is in — this assertion is where that
     * has to be confronted and documented rather than slipped in.
     */
    @Test
    @DisplayName("pin records complete provenance for a verbatim vendoring")
    void pinProvenanceIsComplete() throws Exception {
        JsonNode pin = UpstreamPin.load(VENDORED_DIR).json();
        for (String field : java.util.List.of(
                "repository", "commit", "commit_date", "commit_subject",
                "upstream_version", "aiken_compiler_of_committed_blueprint",
                "backend_resource_copy", "tree_sha256")) {
            assertTrue(pin.hasNonNull(field), "core pin must record " + field);
        }
        assertTrue(pin.get("blueprint_reproduced").asBoolean(),
                "the core tree is vendored verbatim from a published commit; if its blueprint no "
                        + "longer reproduces from its own source, say so explicitly in the pin "
                        + "(with the diagnosis) rather than leaving this flag true.");
    }
}
