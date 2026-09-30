package org.cardanofoundation.cip113.offline;

import org.cardanofoundation.cip113.service.TokenDeploymentGuard;
import org.cardanofoundation.cip113.service.TokenNotInCurrentDeploymentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A token minted under one protocol deployment must not be built against another.
 *
 * <p><strong>The failure this guards.</strong> Measured on preprod 2026-09-30: a re-bootstrap
 * replaced the deployment record, and a freeze-and-seize transfer of a token minted under the
 * previous deployment was built without complaint and rejected by the ledger with
 * {@code {"code":3141,...}}. The token's policy id is the hash of {@code issuance_mint}
 * parameterised by its deployment's protocol params, so the token is deployment-bound and the
 * registry it lives in is the OLD one — while the credentials being derived came from the NEW
 * base and were never registered.
 *
 * <p>⛔ THE TESTS BELOW ARE ANCHORED ON THE DECISION, NOT ON THE MESSAGE TEXT. Two earlier
 * guards in this repo were written against string literals and passed against a gutted
 * implementation, because the literal they searched for had been removed too and
 * {@code indexOf} returned -1. Here every case asserts which of the three outcomes happens —
 * throw / IN_CURRENT_DEPLOYMENT / UNDETERMINED — so deleting the refusal makes
 * {@link #refusesATokenFromAnotherDeployment()} fail, and refusing unconditionally makes
 * {@link #allowsATokenInTheCurrentDeployment()} and both UNDETERMINED cases fail. The message
 * is checked only for the two hashes a human needs in order to act, and that check sits on top
 * of an assertThrows that already proves the refusal.
 */
class TokenDeploymentGuardTest {

    private static final String CURRENT = "dd6d13d13a4b65b2a5cf11d69449b2bc7714bad62ff72984de885f370b8b33e7";
    private static final String OTHER = "bf929c1ea832e93fae781fad34c448d78c81012c68d6b159e2cafa80b0393398";
    private static final String POLICY = "c20e6e20".repeat(7);
    private static final long CURRENT_ID = 1L;

    /** Records what the guard asked, so a check that never consults the directory cannot pass. */
    private static final class FakeView implements TokenDeploymentGuard.RegistryView {
        private final boolean inCurrent;
        private final Optional<String> lastSeen;
        final List<String> calls = new ArrayList<>();

        FakeView(boolean inCurrent, String lastSeen) {
            this.inCurrent = inCurrent;
            this.lastSeen = Optional.ofNullable(lastSeen);
        }

        @Override
        public boolean isInDeployment(String policyId, long protocolParamsId) {
            calls.add("inDeployment:" + policyId + ":" + protocolParamsId);
            return inCurrent;
        }

        @Override
        public Optional<String> lastSeenDeploymentTxHash(String policyId) {
            calls.add("lastSeen:" + policyId);
            return lastSeen;
        }
    }

    @Test
    @DisplayName("refuses a token whose directory node lives under a different deployment")
    void refusesATokenFromAnotherDeployment() {
        var view = new FakeView(false, OTHER);

        var e = assertThrows(TokenNotInCurrentDeploymentException.class, () ->
                TokenDeploymentGuard.requireTokenInDeployment(POLICY, CURRENT_ID, CURRENT, view));

        // Both deployments must be named: without the OTHER hash the operator cannot tell which
        // version to select, and without CURRENT they cannot tell what they are pointed at.
        assertTrue(e.getMessage().contains(OTHER),
                "the refusal must name the deployment that owns the token; got: " + e.getMessage());
        assertTrue(e.getMessage().contains(CURRENT),
                "the refusal must name the deployment in use; got: " + e.getMessage());
        assertTrue(e.getMessage().contains(POLICY),
                "the refusal must name the token; got: " + e.getMessage());
    }

    @Test
    @DisplayName("allows a token that is in the current deployment's directory")
    void allowsATokenInTheCurrentDeployment() {
        var view = new FakeView(true, CURRENT);

        var outcome = TokenDeploymentGuard.requireTokenInDeployment(POLICY, CURRENT_ID, CURRENT, view);

        assertEquals(TokenDeploymentGuard.Outcome.IN_CURRENT_DEPLOYMENT, outcome);
        // ⛔ NON-VACUITY: it must have actually asked the directory, scoped to this deployment.
        // A guard that returns early without looking would otherwise pass this test.
        assertEquals(List.of("inDeployment:" + POLICY + ":" + CURRENT_ID), view.calls);
    }

    @Test
    @DisplayName("does not refuse when the current deployment has no indexed protocol_params row")
    void passesWhenTheCurrentDeploymentIsNotIndexedYet() {
        // A fresh bootstrap: the record is served from the committed JSON before the indexer has
        // ingested the genesis tx. Refusing here would break every first transfer.
        var view = new FakeView(false, OTHER);

        var outcome = TokenDeploymentGuard.requireTokenInDeployment(POLICY, null, CURRENT, view);

        assertEquals(TokenDeploymentGuard.Outcome.UNDETERMINED, outcome);
        assertTrue(view.calls.isEmpty(),
                "with no deployment id there is nothing to compare against, so it must not query");
    }

    @Test
    @DisplayName("does not refuse a token that is in no directory at all — indistinguishable from indexer lag")
    void passesWhenTheTokenIsInNoDirectory() {
        var view = new FakeView(false, null);

        var outcome = TokenDeploymentGuard.requireTokenInDeployment(POLICY, CURRENT_ID, CURRENT, view);

        assertEquals(TokenDeploymentGuard.Outcome.UNDETERMINED, outcome);
        assertEquals(
                List.of("inDeployment:" + POLICY + ":" + CURRENT_ID, "lastSeen:" + POLICY),
                view.calls,
                "it must have checked both scopes before giving up");
    }

    @Test
    @DisplayName("a node found under the current deployment's own hash is not a mismatch")
    void doesNotRefuseWhenTheOnlyNodeFoundIsTheCurrentDeployments() {
        // Contradicts the scoped lookup, so it can only be a race against ingestion — and it must
        // never be reported as a mismatch of a deployment with itself.
        var view = new FakeView(false, CURRENT);

        var outcome = TokenDeploymentGuard.requireTokenInDeployment(POLICY, CURRENT_ID, CURRENT, view);

        assertEquals(TokenDeploymentGuard.Outcome.UNDETERMINED, outcome);
    }

    @Test
    @DisplayName("the directory lookup is a required dependency, not an optional one")
    void refusesToRunWithoutARegistryView() {
        // Keeps the guard unable to reach a database behind a test's back: there is no default.
        var e = assertThrows(IllegalArgumentException.class, () ->
                TokenDeploymentGuard.requireTokenInDeployment(POLICY, CURRENT_ID, CURRENT, null));
        assertFalse(e instanceof TokenNotInCurrentDeploymentException,
                "a missing dependency is a programming error, not a token-deployment refusal");
    }
}
