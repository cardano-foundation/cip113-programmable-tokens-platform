package org.cardanofoundation.cip113.substandards.securitytoken;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Drift guard for the vendored {@code src/substandards/security-token} tree.
 *
 * <p>That directory is a <em>verbatim</em> copy of the upstream source recorded in
 * {@code UPSTREAM_PIN.json} — currently {@code easy1staking-com/fn-bafin-cardano-sc},
 * the fork that fixes the three defects in {@code docs/UPSTREAM-BAFIN-DEFECTS.md}. It
 * previously held a hand-maintained fork that silently drifted from upstream while
 * carrying a comment asserting it had not — 12 of 20 Aiken files had diverged by the
 * time anyone checked. Neither this test nor {@code verify-upstream-pin.sh} hard-codes
 * the owner: both read it from the manifest, so a re-pin edits only that file.
 *
 * <p><strong>The pin has two modes</strong>, declared by {@code source_state}. In
 * {@code commit} mode the vendored bytes are a published revision and both halves of
 * the pin apply. In {@code dirty-worktree} mode they are a maintainer's uncommitted
 * working tree; {@code commit} then names only the BASE it was taken from, no tarball
 * can reproduce it, and the online half is disabled — leaving this offline test, plus
 * {@code tree_sha256}, as the whole guard. {@link #pinProvenanceIsComplete()} enforces
 * what that mode must document so it stays reversible instead of quietly permanent.
 *
 * <p>This test is the offline half of the pin (it needs no network, so it runs
 * on every build):
 *
 * <ol>
 *   <li>every vendored file's sha256 matches the manifest — any hand-edit fails
 *       here, loudly, at the file that was edited;</li>
 *   <li>no file was added to or removed from the vendored tree;</li>
 *   <li>the backend's own copy of {@code plutus.json} under {@code
 *       src/main/resources/substandards/security-token/} is byte-identical to
 *       the vendored blueprint. {@code aiken build} does <em>not</em> sync that
 *       copy, and the backend loads it — so a stale copy means the running
 *       service uses different contracts than the ones in the repo.</li>
 * </ol>
 *
 * <p>The online half — proving the manifest itself was not regenerated over a
 * divergent tree — is {@code src/substandards/security-token/verify-upstream-pin.sh},
 * which re-downloads the pinned tarball and diffs it.
 *
 * <p>The vendored directory sits outside this Gradle project, so it is not an
 * implicit input of the {@code test} task; {@code build.gradle} therefore declares
 * it explicitly ({@code inputs.dir(…/substandards/security-token)}). Without that
 * declaration, editing a vendored file left {@code test} {@code UP-TO-DATE} and
 * this guard silently never ran.
 *
 * <p>Note also that running {@code aiken build} in the vendored directory rewrites
 * {@code plutus.json}. At the current pin that rewrite happens to be a no-op —
 * upstream's blueprint was built with {@code v1.1.23+8949565}, which is also the
 * locally installed compiler, and rebuilding reproduces byte-identical
 * {@code compiledCode} for all 24 validators (that is how the pin's
 * {@code blueprint_reproduced} flag was established). Do not rely on it: under an
 * earlier pin the compilers differed ({@code v1.1.21+42babe5}) and a stray build
 * silently changed every validator hash. This test fails loudly either way, which
 * is the intent — the vendored blueprint is the audited artifact, not a local
 * rebuild of it.
 */
class SecurityTokenUpstreamPinTest {

    private static final String VENDORED_DIR = "src/substandards/security-token";
    private static final String PIN_FILE = VENDORED_DIR + "/UPSTREAM_PIN.json";

    /** Files in the vendored directory that are ours, not upstream's. */
    private static final Set<String> NON_UPSTREAM_FILES =
            Set.of("UPSTREAM_PIN.json", "verify-upstream-pin.sh");

    /** Directories excluded from the pin, pruned at ANY depth. Must stay identical to
     *  {@code verify-upstream-pin.sh}'s {@code prune} set — the two must agree or the
     *  manifest the script writes cannot be the manifest this test verifies.
     *
     *  <p>{@code build} is the local aiken working directory (gitignored, not shipped
     *  upstream). {@code .git} and {@code .claude} only appear when the tree is
     *  vendored from a maintainer's working checkout rather than a release tarball;
     *  they are machine-specific state and must never become pinned inputs. */
    private static final Set<String> PRUNED_DIRS = Set.of("build", ".git", ".claude");

    @Test
    @DisplayName("vendored security-token tree matches its pinned upstream manifest")
    void vendoredTreeMatchesPin() throws Exception {
        Path repoRoot = repoRoot();
        Path vendored = repoRoot.resolve(VENDORED_DIR);
        JsonNode pin = new ObjectMapper().readTree(repoRoot.resolve(PIN_FILE).toFile());

        assertEquals(40, pin.get("commit").asText().length(),
                "UPSTREAM_PIN.json must record a full 40-char upstream commit SHA");

        Map<String, String> expected = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = pin.get("files").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            expected.put(e.getKey(), e.getValue().asText());
        }
        assertTrue(expected.size() > 20,
                "manifest looks truncated: only " + expected.size() + " files");

        Map<String, String> actual = hashTree(vendored);

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String got = actual.get(e.getKey());
            if (got == null) {
                problems.add("MISSING  " + e.getKey());
            } else if (!got.equals(e.getValue())) {
                problems.add("MODIFIED " + e.getKey()
                        + " (expected sha256 " + e.getValue() + ", got " + got + ")");
            }
        }
        actual.keySet().stream()
                .filter(k -> !expected.containsKey(k))
                .forEach(k -> problems.add("UNEXPECTED " + k));

        if (!problems.isEmpty()) {
            fail("The vendored security-token tree diverges from "
                    + pin.get("repository").asText() + " @ " + pin.get("commit").asText()
                    + ".\nThis directory is a verbatim upstream copy and must not be hand-edited.\n"
                    + "To adopt a different upstream revision, re-vendor and regenerate the manifest\n"
                    + "with " + VENDORED_DIR + "/verify-upstream-pin.sh --regenerate.\n\n"
                    + String.join("\n", problems));
        }

        // The per-file loop above proves each listed file is intact, but it reads its
        // expectations FROM `files` — so `files` itself is unauthenticated, and a
        // regeneration over a tree nobody reviewed looks identical to a clean pin.
        // `tree_sha256` is one value a human can eyeball in a diff and carry between
        // machines; checking it here is what makes recording it more than decoration.
        JsonNode recordedTree = pin.get("tree_sha256");
        if (recordedTree != null && !recordedTree.isNull()) {
            assertEquals(recordedTree.asText(), treeSha256(expected),
                    "tree_sha256 does not match the per-file manifest. Every individual "
                            + "hash matched, so `files` was edited without refreshing "
                            + "tree_sha256 (or vice versa) — re-run "
                            + VENDORED_DIR + "/verify-upstream-pin.sh --regenerate.");
        }
    }

    /**
     * Pin mode guard. When {@code source_state} is {@code dirty-worktree} the vendored
     * bytes are a maintainer's uncommitted working tree, so {@code commit} names only
     * the base it was taken from and the ONLINE half of the pin cannot run at all
     * (there is no fetchable revision whose tarball would match). That is a deliberate,
     * temporary state — this test pins down what it must carry so it stays legible and
     * reversible rather than quietly becoming permanent.
     */
    @Test
    @DisplayName("pin records enough provenance for the mode it declares")
    void pinProvenanceIsComplete() throws Exception {
        JsonNode pin = new ObjectMapper().readTree(repoRoot().resolve(PIN_FILE).toFile());
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
        Path repoRoot = repoRoot();
        JsonNode pin = new ObjectMapper().readTree(repoRoot.resolve(PIN_FILE).toFile());

        Path vendoredBlueprint = repoRoot.resolve(VENDORED_DIR).resolve("plutus.json");
        Path resourceCopy = repoRoot.resolve(pin.get("backend_resource_copy").asText());

        assertTrue(Files.exists(resourceCopy), "backend resource copy not found: " + resourceCopy);
        assertEquals(sha256(vendoredBlueprint), sha256(resourceCopy),
                "The backend loads " + pin.get("backend_resource_copy").asText()
                        + ", which is a separate copy that `aiken build` does NOT update. "
                        + "It differs from " + VENDORED_DIR + "/plutus.json, so the running service "
                        + "would use different contracts than the ones vendored here. Copy it across.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Walk up from the working directory until the pin file is visible. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve(PIN_FILE))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "could not locate " + PIN_FILE + " from " + Path.of("").toAbsolutePath());
    }

    private static Map<String, String> hashTree(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (isUnderPrunedDir(rel)) continue;
                if (NON_UPSTREAM_FILES.contains(rel)) continue;
                out.put(rel, sha256(p));
            }
        }
        return out;
    }

    /** True when any path segment of {@code rel} names a pruned directory. */
    private static boolean isUnderPrunedDir(String rel) {
        for (String segment : rel.split("/")) {
            if (PRUNED_DIRS.contains(segment)) return true;
        }
        return false;
    }

    /**
     * One identity for the whole manifest: sha256 over the sorted {@code path\0sha\n}
     * lines. Mirrors the same computation in {@code verify-upstream-pin.sh}; the two
     * must agree byte for byte or a regeneration by one tool fails verification by the
     * other. Takes the sorted map rather than walking the filesystem, so the result
     * cannot depend on directory iteration order.
     */
    private static String treeSha256(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(files).forEach((path, hash) -> sb.append(path).append('\0').append(hash).append('\n'));
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(Files.readAllBytes(p));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
