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
 * <p>That directory is a <em>verbatim</em> copy of the upstream repository at the
 * SHA recorded in {@code UPSTREAM_PIN.json} — currently
 * {@code easy1staking-com/fn-bafin-cardano-sc}, the fork whose
 * {@code fix/cmta-requirements-fixes} branch merges {@code FluidTokens:main} and
 * fixes the three defects in {@code docs/UPSTREAM-BAFIN-DEFECTS.md}. It previously
 * held a hand-maintained fork that silently drifted from upstream while carrying a
 * comment asserting it had not — 12 of 20 Aiken files had diverged by the time
 * anyone checked. Neither this test nor {@code verify-upstream-pin.sh} hard-codes
 * the owner: both read it from the manifest, so a re-pin edits only that file.
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
 * upstream's committed blueprint was built with {@code v1.1.23+8949565}, which is
 * also the locally installed compiler, and rebuilding reproduces byte-identical
 * {@code compiledCode} for all 21 validators (that is how the pin's
 * {@code blueprint_reproduced} flag was established). Do not rely on it: under the
 * previous pin the compilers differed ({@code v1.1.21+42babe5}) and a stray build
 * silently changed every validator hash. This test fails loudly either way, which
 * is the intent — the committed blueprint is the audited artifact, not a local
 * rebuild of it.
 */
class SecurityTokenUpstreamPinTest {

    private static final String VENDORED_DIR = "src/substandards/security-token";
    private static final String PIN_FILE = VENDORED_DIR + "/UPSTREAM_PIN.json";

    /** Files in the vendored directory that are ours, not upstream's. */
    private static final Set<String> NON_UPSTREAM_FILES =
            Set.of("UPSTREAM_PIN.json", "verify-upstream-pin.sh");

    /** Local aiken working directory; upstream does not ship it and it is gitignored.
     *  Pruned at ANY depth, matching {@code verify-upstream-pin.sh}'s
     *  {@code dirs[:] = [d for d in dirs if d != "build"]} — the two must agree or
     *  the manifest the script writes cannot be the manifest this test verifies. */
    private static final String BUILD_DIR = "build";

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
                if (isUnderBuildDir(rel)) continue;
                if (NON_UPSTREAM_FILES.contains(rel)) continue;
                out.put(rel, sha256(p));
            }
        }
        return out;
    }

    /** True when any path segment of {@code rel} is the aiken {@code build} directory. */
    private static boolean isUnderBuildDir(String rel) {
        for (String segment : rel.split("/")) {
            if (BUILD_DIR.equals(segment)) return true;
        }
        return false;
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
