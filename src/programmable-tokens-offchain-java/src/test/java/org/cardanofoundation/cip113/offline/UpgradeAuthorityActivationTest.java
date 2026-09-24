package org.cardanofoundation.cip113.offline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * alpha.5's one new rule, with a falsifier: the genesis mint must carry a withdraw-0 from the
 * upgrade credential.
 *
 * <p><strong>Why this pair and not just the positive.</strong> "The bootstrap builds" is
 * satisfied by a protocol that enforces the rule AND by one that never checks it — a green
 * result cannot tell those apart. The refusal below is what makes the success mean something:
 * the two fixtures differ in exactly one respect, the withdrawal, so the difference in outcome
 * is attributable to it and to nothing else.
 *
 * <p><strong>Why it can run offline at all.</strong> {@link OfflineChain} performs no
 * stake-registration checks — it is a phase-2 evaluator over a simulated UTxO set, not a
 * ledger — so a withdraw-0 from an unregistered credential still runs the validator, which is
 * the part under test. This is the one piece of the alpha.5 migration that can be proven
 * without a node and without a wallet, which is why it is worth having.
 */
class UpgradeAuthorityActivationTest {

    @Test
    @DisplayName("the alpha.5 genesis, carrying the withdraw-0 from upgrade_cred, is accepted")
    void genesisWithActivationIsAccepted() {
        assertDoesNotThrow(() -> BootstrapFixture.bootstrap(new OfflineChain()),
                "the alpha.5-shaped genesis must build and evaluate");
    }

    @Test
    @DisplayName("the same genesis WITHOUT the withdraw-0 is refused by protocol_params.mint")
    void genesisWithoutActivationIsRefused() {
        var failure = assertThrows(Exception.class,
                () -> BootstrapFixture.bootstrap(new OfflineChain(), false),
                "an alpha.4-shaped genesis must NOT be accepted against the alpha.5 blueprint — "
                        + "if this stops throwing, either the blueprint regressed to alpha.4 or the "
                        + "positive test above is no longer proving anything");

        // Attribution by REDEEMER, not by message: traces are compiled out of a release build,
        // so the validator cannot name itself. What is available is which redeemer failed, and
        // it must be a Mint — protocol_params is a minting policy, and a failure anywhere else
        // would mean this fixture broke for an unrelated reason while still going red.
        var text = chainText(failure);
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains("Mint"),
                "expected a Mint redeemer failure attributable to protocol_params, got: " + text);
    }

    /** The whole cause chain as text — the useful detail is never on the outermost exception. */
    private static String chainText(Throwable t) {
        var sb = new StringBuilder();
        for (var c = t; c != null; c = c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" | ");
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }
}
