package org.cardanofoundation.cip113.offline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pre-registration liveness probe must NOT fire when the blacklist init is chained in the
 * same batch.
 *
 * <h2>The regression this pins</h2>
 *
 * {@code buildRegisterTransaction} asks {@code isStakeAddressRegistered(expectedIssuerAdmin)}
 * before building, because registration withdraws-0 from that credential and the ledger applies
 * withdrawals before certificates, so registration cannot register it.
 *
 * <p>⛔ BUT IN THE CHAINED FLOW THE INIT HAS NOT BEEN SUBMITTED YET. The UI builds the init, then
 * builds the registration on its outputs with {@code chainingTransactionCborHex} set, and submits
 * both — init first. The credential is therefore legitimately absent from the chain at build time,
 * the probe answered "not registered", and the registration was refused. Measured 2026-10-01: a
 * brand-new wallet on develop could not register AT ALL, because a fresh wallet never has a
 * pre-existing blacklist. The guard was mine and it broke the primary FES path.
 *
 * <h2>Why skipping it loses nothing</h2>
 *
 * The comparison against {@code issuerAdminStakeAddress} — the real protection against a wrong
 * admin or asset name — still runs, and it works in the chained case precisely because
 * {@code buildBlacklistInitTransaction} persists that row at BUILD time, before submission. The
 * probe only ever covered rows that do not exist or pre-date V33, and in a chained batch the row
 * always exists.
 *
 * <h2>Why this is a source check</h2>
 *
 * The defect is a missing CONDITION on a branch. Reaching it at runtime needs a wallet, a built
 * init transaction, a protocol deployment and a chain — and a test that heavy would be asserting
 * its own mocks. The property is structural and local: the probe is guarded by the chaining flag.
 */
class ChainedRegistrationSkipsLivenessProbeTest {

    private static final Path HANDLER = Path.of(
            "src/main/java/org/cardanofoundation/cip113/service/module/FreezeAndSeizeHandler.java");

    private static String source() throws IOException {
        assertTrue(Files.isRegularFile(HANDLER),
                "expected to run from the Gradle module root so " + HANDLER + " resolves");
        return Files.readString(HANDLER, StandardCharsets.UTF_8);
    }

    /** The probe call and everything up to the end of its condition. */
    private static String probeCondition(String src) {
        int call = src.indexOf("isStakeAddressRegistered(expectedIssuerAdmin)");
        assertTrue(call > 0,
                "the pre-registration liveness probe is gone entirely. If that was deliberate, this "
                        + "test must be updated — but note it is the only thing that catches an init "
                        + "whose certificate was silently omitted.");
        // Walk back to the `if (` that owns it.
        int open = src.lastIndexOf("if (", call);
        assertTrue(open > 0 && call - open < 400, "could not locate the enclosing if for the probe");
        return src.substring(open, call + "isStakeAddressRegistered(expectedIssuerAdmin)".length());
    }

    @Test
    @DisplayName("the liveness probe is gated on the init NOT being chained in this batch")
    void probeIsGatedOnChaining() throws IOException {
        String cond = probeCondition(source());
        assertTrue(cond.contains("initIsChainedInThisBatch"),
                "the liveness probe is no longer gated on whether the blacklist init is chained in "
                        + "the same batch, so it will refuse every FIRST registration against a fresh "
                        + "blacklist — the init that registers the credential has not been submitted "
                        + "when the registration is built. Condition found:\n  " + cond);
    }

    @Test
    @DisplayName("the chaining flag is read from the request, not inferred")
    void chainingFlagComesFromTheRequest() throws IOException {
        String src = source();
        assertTrue(src.contains("initIsChainedInThisBatch = request.getChainingTransactionCborHex() != null"),
                "initIsChainedInThisBatch must be derived from the request's "
                        + "chainingTransactionCborHex — the same field the chaining branch itself "
                        + "tests. Inferring it any other way would let the two disagree.");
    }

    @Test
    @DisplayName("the admin/asset-name comparison is NOT gated on chaining — it must still run")
    void theRealGuardStillRunsWhenChained() throws IOException {
        String src = source();
        int cmp = src.indexOf("getIssuerAdminStakeAddress() != null");
        assertTrue(cmp > 0, "the issuer_admin comparison is gone — that is the real protection "
                + "against a wrong admin or asset name, and it works in the chained case because the "
                + "init row is persisted at build time.");
        // ⛔ NON-VACUITY IN THE OTHER DIRECTION. Fixing the false refusal by disabling BOTH checks
        // when chaining would make this file pass the first two tests and remove the protection
        // that matters. The comparison must not be inside the chaining gate.
        int open = src.lastIndexOf("if (", cmp);
        String cmpCond = src.substring(open, cmp);
        assertFalse(cmpCond.contains("initIsChainedInThisBatch"),
                "the issuer_admin comparison is now also skipped when chaining. It must not be: the "
                        + "init row exists at that point (written at build time), and comparing it is "
                        + "exactly how a wrong admin or asset name is caught before the user pays.");
    }
}
