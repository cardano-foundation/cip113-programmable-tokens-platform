package org.cardanofoundation.cip113.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A component must not hard-depend on a bean that a feature flag can remove.
 *
 * <p><strong>Why this exists.</strong> {@code InitialMintAttestationStore} is
 * {@code @ConditionalOnProperty("keri.enabled")}. {@code ExpiredInitialMintCleanup} held it as a
 * {@code private final} constructor dependency while being an unconditional {@code @Component}, so
 * every deployment running without a KERI agent — which is both preview and preprod, by their own
 * profiles — failed at startup with:
 *
 * <pre>
 *   Parameter 1 of constructor in …ExpiredInitialMintCleanup required a bean of type
 *   …InitialMintAttestationStore that could not be found.
 * </pre>
 *
 * <p>⛔ AND NO TEST COULD HAVE CAUGHT IT, which is the point of doing it this way. The failure is a
 * property of the bean GRAPH, so a unit test that constructs objects never sees it, and the only
 * runtime that does is a real container with the flag off — which needs a database, so it is not a
 * cheap test to own. This reads the source instead: it is a structural check, and structural
 * checks are what catch a class rather than an instance.
 *
 * <p>⚑ TWO CORRECT SHAPES, and the distinction matters. Gate the CONSUMER too when the whole
 * component is meaningless without the feature; hold the dependency as {@code ObjectProvider<T>}
 * when only part of it is, as {@code TokenOperationsService} does for
 * {@code MintAttestationService}. Either satisfies this test; a bare {@code private final} does not.
 */
class ConditionalBeanWiringTest {

    private static final Path MAIN = Path.of("src/main/java/org/cardanofoundation/cip113");

    /** `@ConditionalOnProperty(name = "x.y", ...)` — the property a class is gated on, if any. */
    private static final Pattern GATE =
            Pattern.compile("@ConditionalOnProperty\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");

    /** `private final SomeType field;` — a HARD constructor dependency under @RequiredArgsConstructor. */
    private static final Pattern HARD_DEP =
            Pattern.compile("private\\s+final\\s+([A-Z][A-Za-z0-9_]*)\\s+[a-zA-Z_][A-Za-z0-9_]*\\s*;");

    private record Source(String simpleName, String gate, String body) {}

    private static List<Source> sources() throws IOException {
        assertTrue(Files.isDirectory(MAIN),
                "expected to run from the Gradle module root so " + MAIN + " resolves; got "
                        + Path.of(".").toAbsolutePath());
        try (Stream<Path> files = Files.walk(MAIN)) {
            var out = new ArrayList<Source>();
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                var body = Files.readString(p, StandardCharsets.UTF_8);
                var m = GATE.matcher(body);
                var name = p.getFileName().toString().replace(".java", "");
                out.add(new Source(name, m.find() ? m.group(1) : null, body));
            }
            return out;
        }
    }

    @Test
    @DisplayName("no unconditional component hard-depends on a flag-gated bean")
    void noUngatedConsumerOfAGatedBean() throws IOException {
        var all = sources();

        // Which classes a flag can remove, and which flag.
        Map<String, String> gatedBeans = new HashMap<>();
        for (var s : all) {
            if (s.gate() != null) gatedBeans.put(s.simpleName(), s.gate());
        }
        assertTrue(gatedBeans.size() >= 5,
                "expected to find several flag-gated beans; found " + gatedBeans.size()
                        + " — if the annotation style changed, this test is now blind and must be updated");

        Set<String> problems = new LinkedHashSet<>();
        for (var consumer : all) {
            Matcher deps = HARD_DEP.matcher(consumer.body());
            while (deps.find()) {
                String type = deps.group(1);
                String flag = gatedBeans.get(type);
                if (flag == null) continue;                       // not a removable bean
                if (type.equals(consumer.simpleName())) continue; // its own type
                if (flag.equals(consumer.gate())) continue;       // gated on the SAME flag: fine

                problems.add(
                        consumer.simpleName() + " hard-depends on " + type + ", which only exists when "
                                + flag + "=true"
                                + (consumer.gate() == null
                                        ? " — but the consumer is unconditional."
                                        : " — but the consumer is gated on " + consumer.gate() + " instead."));
            }
        }

        if (!problems.isEmpty()) {
            fail("A feature flag can remove a bean these components require, so the container will "
                    + "refuse to start with that flag off — a failure no unit test sees, because it is a "
                    + "property of the bean graph.\n\n"
                    + String.join("\n", problems)
                    + "\n\nFix by gating the consumer on the same property, or by holding the dependency "
                    + "as ObjectProvider<T> when the consumer must survive its absence (see "
                    + "TokenOperationsService).");
        }
    }

    @Test
    @DisplayName("the check is not vacuous — it can see the gated beans and the hard-dep shape")
    void theCheckCanSeeBothShapes() throws IOException {
        var all = sources();

        var store = all.stream().filter(s -> s.simpleName().equals("InitialMintAttestationStore")).findFirst();
        assertTrue(store.isPresent(), "InitialMintAttestationStore should exist");
        assertTrue("keri.enabled".equals(store.get().gate()),
                "it should be gated on keri.enabled; if not, this test's premise has moved");

        // ⛔ PROOF THE PATTERN MATCHES A REAL DEPENDENCY. Without this the main test could pass by
        // matching nothing at all, which is the failure mode of every regex-based check.
        var cleanup = all.stream().filter(s -> s.simpleName().equals("ExpiredInitialMintCleanup")).findFirst();
        assertTrue(cleanup.isPresent(), "ExpiredInitialMintCleanup should exist");
        assertTrue(HARD_DEP.matcher(cleanup.get().body()).find(),
                "the hard-dependency pattern must match this class's `private final` fields");
        assertTrue("keri.enabled".equals(cleanup.get().gate()),
                "and it must now be gated on keri.enabled, which is the fix this test guards");
    }
}
