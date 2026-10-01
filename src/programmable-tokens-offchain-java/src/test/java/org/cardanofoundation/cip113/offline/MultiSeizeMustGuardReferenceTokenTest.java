package org.cardanofoundation.cip113.offline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * When multi-seize is implemented, it must refuse the CIP-68 {@code (100)} reference token.
 *
 * <p><strong>Why a structural test instead of a runtime guard.</strong> Seizing the reference
 * token destroys its metadata: the datum travels to output 1 while the token goes to the
 * destination, so the metadata ends up on an output that no longer holds the asset it describes.
 * {@code /compliance/seize} is guarded by {@code Cip68.refuseReferenceToken}, keyed on the
 * request's asset name.
 *
 * <p>{@code /compliance/seize/multi} CANNOT be guarded the same way: its request carries a
 * {@code policyId} and a list of {@code txHash#index} references with NO asset name, so the
 * label is not derivable from the request at all. The check has to happen where the handler
 * reads each referenced UTxO's assets.
 *
 * <p>⚑ AND TODAY THERE IS NOWHERE TO PUT IT, which is the point of doing it this way. Every
 * implementation of {@code buildMultiSeizeTransaction} is a stub returning
 * {@code typedError("not implemented")} — the default in {@code Seizeable} and the override in
 * {@code FreezeAndSeizeHandler}. No transaction is built, so nothing can be swept and a runtime
 * guard would be dead code on an unreachable path. I reported this as a live gap before reading
 * the implementations; it is not one.
 *
 * <p>⛔ SO THE RISK IS ENTIRELY IN THE FUTURE, and it lands on whoever implements multi-seize —
 * precisely the person who will not know that a programmable seize silently separates a datum
 * from its token, because nothing in the signature says so. This test fails the moment a stub
 * becomes real without the guard, which is the only moment the warning is useful.
 */
class MultiSeizeMustGuardReferenceTokenTest {

    private static final Path MAIN = Path.of("src/main/java/org/cardanofoundation/cip113");
    private static final String METHOD = "buildMultiSeizeTransaction";

    /** A body that only reports the operation is missing cannot lose anybody's metadata. */
    private static boolean isStub(String body) {
        String lower = body.toLowerCase();
        return lower.contains("not implemented") || lower.contains("not yet implemented");
    }

    private record Impl(String file, String body) {}

    /** Crude brace matching from the method signature — enough to isolate one method body. */
    private static List<Impl> implementations() throws IOException {
        var out = new ArrayList<Impl>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                int at = src.indexOf(METHOD);
                while (at >= 0) {
                    int open = src.indexOf('{', at);
                    // A signature ending in ';' is an abstract declaration, not an implementation.
                    int semi = src.indexOf(';', at);
                    if (open >= 0 && (semi < 0 || open < semi)) {
                        int depth = 0;
                        int i = open;
                        for (; i < src.length(); i++) {
                            if (src.charAt(i) == '{') depth++;
                            else if (src.charAt(i) == '}' && --depth == 0) break;
                        }
                        out.add(new Impl(p.getFileName().toString(), src.substring(open, Math.min(i + 1, src.length()))));
                    }
                    at = src.indexOf(METHOD, at + METHOD.length());
                }
            }
        }
        return out;
    }

    @Test
    @DisplayName("every real multi-seize implementation refuses the (100) reference token")
    void realImplementationsMustGuard() throws IOException {
        var impls = implementations();

        // ⛔ NON-VACUITY. Without this the test passes by finding nothing, which is the failure
        // mode of every source-scanning check — and this one would then stay green forever
        // precisely because the method had been renamed or moved.
        assertTrue(impls.size() >= 2,
                "expected at least the Seizeable default and the FreezeAndSeizeHandler override; "
                        + "found " + impls.size() + " implementation(s) of " + METHOD
                        + ". If it was renamed or moved, this test is now blind and must be updated.");

        var unguarded = new ArrayList<String>();
        for (var impl : impls) {
            if (isStub(impl.body())) continue;
            if (impl.body().contains("refuseReferenceToken")) continue;
            unguarded.add(impl.file());
        }

        if (!unguarded.isEmpty()) {
            fail("multi-seize is now implemented in " + String.join(", ", unguarded)
                    + " without refusing the CIP-68 (100) reference token.\n\n"
                    + "A programmable seize sends the token to the destination while its inline datum "
                    + "goes to output 1, so seizing the reference token leaves the metadata on an "
                    + "output that no longer holds the asset it describes — unrecoverable, and the "
                    + "ledger accepts it because no validator requires a datum to survive.\n\n"
                    + "The request carries only a policyId and txHash#index references, so the label "
                    + "cannot be read from it: resolve each referenced UTxO and call "
                    + "Cip68.refuseReferenceToken(\"seize\", assetName) for every asset it holds. "
                    + "/compliance/seize does the request-level version of this check.");
        }
    }

    @Test
    @DisplayName("the stub detector is not what makes this pass — it must recognise a real body")
    void theStubDetectorDiscriminates() {
        // Proves the exemption above is narrow. If isStub() returned true for everything, the
        // main test could never fail and would be theatre.
        assertTrue(isStub("{ return TransactionContext.typedError(\"Not yet implemented\"); }"));
        assertTrue(!isStub("{ var utxos = supplier.getUtxos(request.utxoReferences()); return build(utxos); }"),
                "a body that actually builds a transaction must NOT be treated as a stub");
    }
}
