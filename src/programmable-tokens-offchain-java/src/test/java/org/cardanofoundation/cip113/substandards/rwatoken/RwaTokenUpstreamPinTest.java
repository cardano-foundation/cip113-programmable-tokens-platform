package org.cardanofoundation.cip113.substandards.rwatoken;

import com.fasterxml.jackson.databind.JsonNode;
import org.cardanofoundation.cip113.pin.UpstreamPin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drift guard for the vendored {@code src/substandards/rwa-token} tree.
 *
 * <p>That directory is a <em>verbatim</em> copy of the upstream source recorded in
 * {@code UPSTREAM_PIN.json}. It previously held a hand-maintained fork that silently
 * drifted from upstream while carrying a comment asserting it had not — 12 of 20 Aiken
 * files had diverged by the time anyone checked. Neither this test nor
 * {@code verify-upstream-pin.sh} hard-codes the owner: both read it from the manifest, so
 * a re-pin edits only that file.
 *
 * <p><strong>The pin has two modes</strong>, declared by {@code source_state}. In
 * {@code commit} mode the vendored bytes are a published revision and both halves of the
 * pin apply. In {@code dirty-worktree} mode they are a maintainer's uncommitted working
 * tree; {@code commit} then names only the BASE it was taken from, no tarball can
 * reproduce it, and the online half is disabled — leaving this offline test, plus
 * {@code tree_sha256}, as the whole guard. {@link #pinProvenanceIsComplete()} enforces
 * what that mode must document so it stays reversible instead of quietly permanent.
 *
 * <p>The mechanics are shared with the core-contracts pin; see {@link UpstreamPin} for
 * what the tree check actually proves and for the Gradle input-declaration caveat.
 *
 * <p>Note that running {@code aiken build} in the vendored directory rewrites
 * {@code plutus.json}. This pin carries a {@code rebuilt-from-vendored-source} exception
 * because upstream's committed blueprint is stale for {@code global_state_spend}, so both
 * the rebuilt bytes and upstream's committed bytes are pinned by sha256 — the exception
 * self-expires the moment upstream regenerates.
 */
class RwaTokenUpstreamPinTest {

    private static final String VENDORED_DIR = "src/substandards/rwa-token";

    @Test
    @DisplayName("vendored rwa-token tree matches its pinned upstream manifest")
    void vendoredTreeMatchesPin() throws Exception {
        UpstreamPin pin = UpstreamPin.load(VENDORED_DIR);
        assertEquals(40, pin.json().get("commit").asText().length(),
                "UPSTREAM_PIN.json must record a full 40-char upstream commit SHA");
        assertNull(pin.verifyTree());
    }

    /**
     * Pin mode guard. When {@code source_state} is {@code dirty-worktree} the vendored
     * bytes are a maintainer's uncommitted working tree, so {@code commit} names only the
     * base it was taken from and the ONLINE half of the pin cannot run at all (there is
     * no fetchable revision whose tarball would match). That is a deliberate, temporary
     * state — this test pins down what it must carry so it stays legible and reversible
     * rather than quietly becoming permanent.
     */
    @Test
    @DisplayName("pin records enough provenance for the mode it declares")
    void pinProvenanceIsComplete() throws Exception {
        JsonNode pin = UpstreamPin.load(VENDORED_DIR).json();
        String state = pin.hasNonNull("source_state") ? pin.get("source_state").asText() : "commit";

        assertTrue(Set.of("commit", "dirty-worktree").contains(state),
                "unknown source_state: " + state);

        if ("dirty-worktree".equals(state)) {
            for (String field : List.of("source_path", "uncommitted_changes_summary", "tree_sha256")) {
                assertTrue(pin.hasNonNull(field),
                        "a dirty-worktree pin must record " + field + ": without it nobody can "
                                + "tell what these bytes are, and the online check is disabled.");
            }
        }
    }

    @Test
    @DisplayName("backend resource copy of plutus.json is byte-identical to the vendored blueprint")
    void backendResourceCopyIsInSync() throws Exception {
        assertNull(UpstreamPin.load(VENDORED_DIR).verifyBackendResourceCopy());
    }
}
