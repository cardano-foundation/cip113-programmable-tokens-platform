package org.cardanofoundation.cip113.offline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the CI test allowlist in {@code build.gradle}: what it may match, and when its
 * quarantine expires.
 *
 * <p><strong>Why the allowlist exists at all.</strong> A bare {@code ./gradlew test} runs classes
 * that BUILD AND SUBMIT REAL TRANSACTIONS — {@code Preview*}, {@code Preprod*}, {@code Devnet*},
 * {@code IssueToken*}, {@code Transfer*}, {@code DirectoryMint*}, {@code ProtocolParamsMint*} —
 * against whatever {@code CARDANO_BACKEND_URL} points at, from a mnemonic committed in the test
 * sources, gated by no annotation. The workflow therefore passed {@code -x test} and ran no
 * backend tests whatsoever. {@code ciTest} names what runs instead of what does not, because an
 * exclusion list needs to be wrong only once before CI spends real funds.
 *
 * <p>⛔ THE FIRST TEST IS THE ONE THAT MATTERS: it converts every allowlist pattern to a regex
 * and asserts none of them can match a submitting class. A pattern as innocent as
 * {@code '*Transfer*'} would quietly enrol {@code PreviewTransferTest}.
 *
 * <p>⚑ AND THE INVENTORY PIN IS WHY THIS FILE EXISTS RATHER THAN A COMMENT. Wiring the suites
 * into CI immediately exposed six tests in {@code OfflineCip68EvalTest} that had been failing for
 * days — broken by a pre-registration guard added upstream of them — because that class sat in no
 * documented offline set and CI ran nothing. A test class that nobody runs is indistinguishable
 * from one that passes. Pinning the count makes adding a class a decision: put it in the
 * allowlist, or say in {@link #NOT_IN_CI_REASON} why it stays out.
 */
class CiTestInventoryTest {

    private static final Path BUILD_FILE = Path.of("build.gradle");
    private static final Path TEST_ROOT = Path.of("src/test/java");

    /** Every test class in the tree, as of 2026-10-07 (counted, not guessed). Bump deliberately, never reflexively.
     *  100 → 102 (main): offline PowerUserInsertionTest and RwaAdminAuthorityTest.
     *  → 104: offline TxAttestationSealTest and MintAttestationMetadataTest (CIP-170 ATTEST_TX).
     *  → 105: offline Cip170AuthBeginTest (AUTH_BEGIN announces label 170). */
    private static final int EXPECTED_TEST_CLASS_COUNT = 105;

    /** Why the rest are out: they submit transactions, need a database, or need a live backend. */
    private static final String NOT_IN_CI_REASON =
            "they submit real transactions (Preview*/Preprod*/Devnet*/IssueToken*/Transfer*/"
                    + "DirectoryMint*/ProtocolParamsMint*), need a Postgres instance (the "
                    + "@DataJpaTest slices), or need a live backend";

    /** Name fragments that mark a class as transaction-submitting. */
    private static final List<String> SUBMITTING_MARKERS =
            List.of("Preview", "Preprod", "Devnet", "IssueToken", "DirectoryMint", "ProtocolParamsMint");

    private static String buildFile() throws IOException {
        assertTrue(Files.isRegularFile(BUILD_FILE),
                "expected to run from the Gradle module root so build.gradle resolves; got "
                        + Path.of(".").toAbsolutePath());
        return Files.readString(BUILD_FILE, StandardCharsets.UTF_8);
    }

    /** The quoted entries of a Groovy list literal `ext.<name> = [ '...', '...' ]`. */
    private static List<String> groovyList(String src, String name) {
        Matcher m = Pattern.compile("ext\\." + name + "\\s*=\\s*\\[(.*?)]", Pattern.DOTALL).matcher(src);
        assertTrue(m.find(), "could not find ext." + name + " in build.gradle — the CI allowlist "
                + "has been renamed or removed, and this test is now blind");
        var out = new ArrayList<String>();
        Matcher e = Pattern.compile("'([^']+)'").matcher(m.group(1));
        while (e.find()) out.add(e.group(1));
        return out;
    }

    private static List<String> testClassNames() throws IOException {
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            return files.filter(f -> f.toString().endsWith("Test.java"))
                    .map(f -> f.getFileName().toString().replace(".java", ""))
                    .sorted()
                    .toList();
        }
    }

    @Test
    @DisplayName("no CI allowlist pattern can match a transaction-submitting test class")
    void allowlistCannotReachASubmittingClass() throws IOException {
        var patterns = groovyList(buildFile(), "ciTestPatterns");
        assertFalse(patterns.isEmpty(), "the allowlist is empty — ciTest would run nothing");

        var submitting = testClassNames().stream()
                .filter(n -> SUBMITTING_MARKERS.stream().anyMatch(n::contains)
                        || n.startsWith("Transfer"))
                .toList();
        // ⛔ NON-VACUITY: if this finds nothing, the check below proves nothing.
        assertTrue(submitting.size() >= 5,
                "expected to find several submitting test classes to check against; found "
                        + submitting + ". If they were renamed, update SUBMITTING_MARKERS.");

        var violations = new ArrayList<String>();
        for (String pattern : patterns) {
            // Gradle's includeTestsMatching: '*' is the only wildcard, matched against the FQN.
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            Pattern p = Pattern.compile("^" + regex + "$");
            for (String cls : submitting) {
                if (p.matcher(cls).matches() || p.matcher("org.cardanofoundation.cip113.foo." + cls).matches()) {
                    violations.add(pattern + " matches " + cls);
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("A ciTest allowlist pattern matches a class that SUBMITS REAL TRANSACTIONS from "
                    + "the mnemonic in PreviewConstants. CI would spend funds on every push.\n\n"
                    + String.join("\n", violations)
                    + "\n\nNarrow the pattern. The allowlist names what runs precisely so this "
                    + "cannot happen by omission.");
        }
    }

    @Test
    @DisplayName("the backend test quarantine has not silently outlived its expiry")
    void quarantineHasNotExpired() throws IOException {
        String src = buildFile();
        Matcher m = Pattern.compile("ext\\.ciQuarantineExpiry\\s*=\\s*'(\\d{4}-\\d{2}-\\d{2})'").matcher(src);
        assertTrue(m.find(), "ext.ciQuarantineExpiry is gone from build.gradle. A quarantine "
                + "without an expiry is a deletion with extra steps — restore the date.");

        LocalDate expiry = LocalDate.parse(m.group(1));
        var quarantined = groovyList(src, "ciQuarantinedTests");

        assertFalse(LocalDate.now().isAfter(expiry),
                "The backend test quarantine expired on " + expiry + ". These classes were "
                        + "excluded from CI as a temporary measure: " + quarantined + ". Either fix "
                        + "them, delete them, or make a fresh dated decision and move the expiry — "
                        + "but do not let the exclusion drift on indefinitely, which is what this "
                        + "assertion exists to prevent.");
    }

    @Test
    @DisplayName("a newly added test class must be classified, not silently left unrun")
    void everyTestClassIsAccountedFor() throws IOException {
        var all = testClassNames();
        assertEquals(EXPECTED_TEST_CLASS_COUNT, all.size(),
                "The number of test classes changed (" + EXPECTED_TEST_CLASS_COUNT + " -> " + all.size()
                        + "). That is not a failure, it is a decision: either add the new class to "
                        + "ext.ciTestPatterns in build.gradle so CI runs it, or leave it out because "
                        + NOT_IN_CI_REASON + " — then bump EXPECTED_TEST_CLASS_COUNT here.\n\n"
                        + "This assertion exists because an unrun test class is indistinguishable "
                        + "from a passing one: six tests in OfflineCip68EvalTest were broken for "
                        + "days while looking green, purely because nothing ran them.");
    }
}
