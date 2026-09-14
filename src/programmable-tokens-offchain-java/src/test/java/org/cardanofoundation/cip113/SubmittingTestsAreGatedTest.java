package org.cardanofoundation.cip113;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Standing guard: no test class may be able to submit a transaction without an environment gate.
 *
 * <h2>Why this is a source scan and not a runtime check</h2>
 *
 * The hazard cannot be demonstrated by running it — running it IS the hazard. A test that
 * submits does so against whatever {@link AbstractPreviewTest} resolved, which defaults to
 * real Blockfrost preview, signed by a real mnemonic. So the only safe way to assert "this
 * cannot fire by accident" is to read the sources and check the gate is present.
 *
 * <h2>Two submission routes, not one</h2>
 *
 * {@code submitTransaction} is the obvious one. {@code completeAndWait} is the other: it
 * submits through {@code QuickTxBuilder} and appears in classes that contain no
 * {@code submitTransaction} at all. Grepping only the first under-counts the hazard —
 * a negative result inherits the blind spots of its pattern, so this checks both.
 *
 * <p>Build-only calls are deliberately NOT treated as hazards: a class that composes and signs
 * but never submits (DirectoryMintTest, ProtocolParamsMintTest) reads from the backend but puts
 * nothing on chain.
 */
class SubmittingTestsAreGatedTest {

    private static final Path TEST_SOURCES = Paths.get("src/test/java");

    /** Anything that puts bytes on a chain. Keep in sync when a new submission API appears. */
    private static final List<String> SUBMISSION_ROUTES = List.of("submitTransaction", "completeAndWait");

    private static final String GATE = "@EnabledIfEnvironmentVariable";

    @Test
    @DisplayName("every test class that can submit a transaction carries an environment gate")
    void everySubmittingTestIsGated() throws IOException {
        assertTrue(Files.isDirectory(TEST_SOURCES),
                "cannot find " + TEST_SOURCES.toAbsolutePath() + " — this guard reads sources, so "
                        + "it must run with the module directory as the working directory");

        List<String> ungated = new ArrayList<>();
        List<String> gated = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(TEST_SOURCES)) {
            for (Path p : paths.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("Test.java")).toList()) {
                // The guard names these literals itself; without this it would appear in its
                // own results and count as satisfied, which is a check grading its own homework.
                if (p.getFileName().toString().equals("SubmittingTestsAreGatedTest.java")) continue;
                String body = Files.readString(p);
                boolean submits = SUBMISSION_ROUTES.stream().anyMatch(body::contains);
                if (!submits) continue;
                String name = TEST_SOURCES.relativize(p).toString();
                if (body.contains(GATE)) gated.add(name); else ungated.add(name);
            }
        }

        assertTrue(gated.size() + ungated.size() > 0, "found no submitting test classes at all — "
                + "the scan is broken, which would make this guard vacuously green");

        if (!ungated.isEmpty()) {
            ungated.sort(String::compareTo);
            fail("These test classes can submit a real, signed transaction but carry no "
                    + GATE + " gate.\n"
                    + "AbstractPreviewTest defaults BACKEND_URL to real Blockfrost preview, so on any\n"
                    + "machine with a preview key in the environment `./gradlew test` (and `./gradlew\n"
                    + "build`, which runs check -> test) submits them for real.\n"
                    + "Gate each one as DevnetRwaTokenPathsTest does.\n\n"
                    + "ungated (" + ungated.size() + "):\n  " + String.join("\n  ", ungated)
                    + "\n\nalready gated (" + gated.size() + "):\n  " + String.join("\n  ", gated));
        }
    }
}
