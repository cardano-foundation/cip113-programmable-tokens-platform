package org.cardanofoundation.cip113.pin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

/**
 * The offline half of an {@code UPSTREAM_PIN.json} guard, shared by every vendored
 * upstream tree in this repository.
 *
 * <p>Two directories are verbatim copies of third-party contract sources —
 * {@code src/core-contracts} (the CIP-113 core) and {@code src/substandards/rwa-token} —
 * and each carries a manifest plus a {@code verify-upstream-pin.sh}. The checks are
 * identical in every respect except which directory they point at, so they live here
 * once rather than being copied per tree. The next vendored substandard should add a
 * five-line test, not a fourth copy of this logic.
 *
 * <p>What this class does <em>not</em> do is the ONLINE half — proving the manifest was
 * not regenerated over a tree nobody reviewed. That needs the network and lives in each
 * tree's {@code verify-upstream-pin.sh}, which re-downloads the pinned tarball and diffs
 * it. {@link #verifyTree} is what runs on every build; the script is what runs in CI.
 *
 * <p><strong>Gradle caveat.</strong> These directories sit outside the Java project, so
 * they are not implicit inputs of the {@code test} task. {@code build.gradle} declares
 * them explicitly. Without that, editing a vendored file leaves {@code test}
 * {@code UP-TO-DATE} and the guard silently never runs — which is the one failure mode a
 * drift guard cannot afford.
 */
public final class UpstreamPin {

    /** Files inside a vendored directory that are ours, not upstream's. */
    private static final Set<String> NON_UPSTREAM_FILES =
            Set.of("UPSTREAM_PIN.json", "verify-upstream-pin.sh");

    /**
     * Directories excluded from every pin, pruned at ANY depth. Must stay identical to
     * the {@code prune} set in each {@code verify-upstream-pin.sh}: the two must agree
     * or the manifest the script writes cannot be the manifest this class verifies.
     *
     * <p>{@code build} is the local aiken working directory (gitignored, not shipped
     * upstream). {@code .git} and {@code .claude} appear only when a tree was vendored
     * from a maintainer's working checkout rather than a release tarball; they are
     * machine-specific state and must never become pinned inputs.
     */
    private static final Set<String> PRUNED_DIRS = Set.of("build", ".git", ".claude");

    private final String vendoredDir;
    private final JsonNode pin;
    private final Path repoRoot;

    private UpstreamPin(String vendoredDir, Path repoRoot, JsonNode pin) {
        this.vendoredDir = vendoredDir;
        this.repoRoot = repoRoot;
        this.pin = pin;
    }

    /**
     * Load the pin for a repo-relative vendored directory, locating the repository root
     * by walking up from the working directory until the manifest is visible (the test
     * task's working directory is the Gradle project, not the repo root).
     */
    public static UpstreamPin load(String vendoredDir) throws IOException {
        String pinFile = vendoredDir + "/UPSTREAM_PIN.json";
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(pinFile))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(
                    "could not locate " + pinFile + " from " + Path.of("").toAbsolutePath());
        }
        return new UpstreamPin(vendoredDir, dir, new ObjectMapper().readTree(dir.resolve(pinFile).toFile()));
    }

    public JsonNode json() {
        return pin;
    }

    public Path repoRoot() {
        return repoRoot;
    }

    /**
     * Every vendored file's sha256 must match the manifest, with nothing added and
     * nothing missing.
     *
     * @return {@code null} when the tree is intact, otherwise a ready-to-print failure
     *         message naming every divergent file. Returning rather than throwing keeps
     *         the JUnit assertion (and its {@code fail(...)}) in the caller, where the
     *         test framework can attribute it.
     */
    public String verifyTree() throws IOException {
        Map<String, String> expected = new TreeMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = pin.get("files").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            expected.put(e.getKey(), e.getValue().asText());
        }
        if (expected.size() <= 20) {
            return "manifest looks truncated: only " + expected.size() + " files listed for " + vendoredDir;
        }

        Map<String, String> actual = hashTree(repoRoot.resolve(vendoredDir));

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

        // The per-file loop reads its expectations FROM `files`, so `files` itself is
        // unauthenticated: a regeneration over a tree nobody reviewed looks identical to a
        // clean pin. `tree_sha256` is one value a human can eyeball in a diff and carry
        // between machines, which is what makes recording it more than decoration.
        JsonNode recordedTree = pin.get("tree_sha256");
        if (problems.isEmpty() && recordedTree != null && !recordedTree.isNull()) {
            String computed = treeSha256(expected);
            if (!recordedTree.asText().equals(computed)) {
                problems.add("tree_sha256 does not match the per-file manifest"
                        + " (recorded " + recordedTree.asText() + ", computed " + computed + ")."
                        + " Every individual hash matched, so `files` was edited without"
                        + " refreshing tree_sha256, or vice versa.");
            }
        }

        if (problems.isEmpty()) {
            return null;
        }
        return "The vendored tree " + vendoredDir + " diverges from "
                + pin.get("repository").asText() + " @ " + pin.get("commit").asText()
                + ".\nThis directory is a verbatim upstream copy and must not be hand-edited.\n"
                + "To adopt a different upstream revision, re-vendor and regenerate the manifest\n"
                + "with " + vendoredDir + "/verify-upstream-pin.sh --regenerate.\n\n"
                + String.join("\n", problems);
    }

    /**
     * The backend loads its OWN copy of {@code plutus.json} from the classpath; no build
     * step syncs it from the vendored tree. A stale copy means the running service builds
     * transactions against contracts that are not the ones in the repository — and, once
     * deployed, not the ones on chain.
     *
     * @return {@code null} when in sync, otherwise a failure message.
     */
    public String verifyBackendResourceCopy() throws IOException {
        Path vendoredBlueprint = repoRoot.resolve(vendoredDir).resolve("plutus.json");
        String rel = pin.get("backend_resource_copy").asText();
        Path resourceCopy = repoRoot.resolve(rel);

        if (!Files.exists(resourceCopy)) {
            return "backend resource copy not found: " + resourceCopy;
        }
        if (!sha256(vendoredBlueprint).equals(sha256(resourceCopy))) {
            return "The backend loads " + rel + ", a separate copy that no build step updates. "
                    + "It differs from " + vendoredDir + "/plutus.json, so the running service would "
                    + "use different contracts than the ones vendored here. Copy it across "
                    + "(or run " + vendoredDir + "/verify-upstream-pin.sh --regenerate, which does it "
                    + "for you).";
        }
        return null;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static Map<String, String> hashTree(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (isUnderPrunedDir(rel) || NON_UPSTREAM_FILES.contains(rel)) continue;
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
     * lines. Mirrors the same computation in every {@code verify-upstream-pin.sh}; the two
     * must agree byte for byte, or a regeneration by one tool fails verification by the
     * other. Takes the sorted map rather than walking the filesystem, so the result cannot
     * depend on directory iteration order.
     */
    static String treeSha256(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(files).forEach((path, hash) -> sb.append(path).append('\0').append(hash).append('\n'));
        return hex(digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256(Path p) throws IOException {
        return hex(digest(Files.readAllBytes(p)));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
