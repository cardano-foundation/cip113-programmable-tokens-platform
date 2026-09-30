package org.cardanofoundation.cip113.service;

import java.util.Optional;

/**
 * Refuses an operation on a token that belongs to a different protocol deployment.
 *
 * <p>ONE guard, placed once on the shared path, rather than one per substandard. The bug it
 * catches is a property of the protocol — a token's policy id is deployment-bound — so it is
 * the same bug in freeze-and-seize, kyc, kyc-extended and rwa-token. Copying a per-module
 * check into each handler would have produced four places to keep in step and would still have
 * missed the next module.
 *
 * <p>⚑ NO SPRING, NO REPOSITORY, NO DEFAULT for {@link RegistryView}: the lookup is a
 * REQUIRED parameter. That is deliberate and is what makes the decision testable without a
 * database — and, more importantly, it makes it impossible for this class to reach the network
 * or the DB behind a test's back. A default would have re-admitted exactly that.
 *
 * <h2>What it will and will not refuse</h2>
 *
 * <p>It refuses only a <strong>proven</strong> mismatch: the token is absent from the current
 * deployment's directory AND its node was last written under a different deployment. Two cases
 * deliberately pass:
 *
 * <ul>
 *   <li><strong>The current deployment has no indexed {@code protocol_params} row yet</strong>
 *       ({@code currentProtocolParamsId == null}). The deployment record is served from
 *       {@code protocol-bootstraps-<network>.json}, which a build carries before the indexer
 *       has seen the genesis transaction — so during that window there is nothing to compare
 *       against. Refusing here would break every fresh bootstrap.
 *   <li><strong>The token is in no directory at all.</strong> Indistinguishable from indexer
 *       lag: a registration that is on chain but not yet ingested looks exactly like one that
 *       never happened. A transfer of a genuinely unregistered token fails on chain anyway,
 *       whereas refusing here would block a legitimate transfer made seconds after
 *       registration.
 * </ul>
 *
 * <p>⚠ SO THIS IS NARROWER THAN "the token must be in the current registry, else refuse", and
 * the narrowing is the point: the wider rule cannot tell a deployment mismatch from indexer
 * lag, and would turn a timing window into a hard failure. Both passing cases return
 * {@link Outcome#UNDETERMINED} so the caller can log them rather than swallow them.
 */
public final class TokenDeploymentGuard {

    /** The two directory lookups the decision needs. Implemented over {@code RegistryService}. */
    public interface RegistryView {

        /** Is there a live directory node for this policy id under this deployment? */
        boolean isInDeployment(String policyId, long protocolParamsId);

        /**
         * The {@code txHash} of the deployment whose directory last wrote a live node for this
         * policy id, across all deployments; empty when no live node exists anywhere.
         */
        Optional<String> lastSeenDeploymentTxHash(String policyId);
    }

    /** What the check established. A refusal throws instead of returning. */
    public enum Outcome {
        /** The token's directory node is present under the deployment in use. */
        IN_CURRENT_DEPLOYMENT,
        /** Not provable either way — see the class comment's two passing cases. */
        UNDETERMINED
    }

    private TokenDeploymentGuard() {
    }

    /**
     * @param policyId                the programmable token's policy id (NOT a unit)
     * @param currentProtocolParamsId the in-use deployment's {@code protocol_params.id}, or
     *                                {@code null} when it has not been indexed yet
     * @param currentProtocolTxHash   the in-use deployment's genesis {@code txHash}, for the message
     * @param view                    directory lookups; required
     * @throws TokenNotInCurrentDeploymentException on a proven deployment mismatch
     */
    public static Outcome requireTokenInDeployment(
            String policyId,
            Long currentProtocolParamsId,
            String currentProtocolTxHash,
            RegistryView view) {

        if (view == null) {
            throw new IllegalArgumentException("RegistryView is required");
        }
        if (policyId == null || policyId.isBlank()) {
            // Not this guard's business to validate the unit; the caller already parsed it.
            return Outcome.UNDETERMINED;
        }
        if (currentProtocolParamsId == null) {
            return Outcome.UNDETERMINED;
        }
        if (view.isInDeployment(policyId, currentProtocolParamsId)) {
            return Outcome.IN_CURRENT_DEPLOYMENT;
        }

        Optional<String> lastSeen = view.lastSeenDeploymentTxHash(policyId);
        if (lastSeen.isEmpty() || lastSeen.get().equals(currentProtocolTxHash)) {
            // Absent everywhere, or the only node found is the current deployment's own (which
            // contradicts the check above, so it can only be a race against ingestion).
            return Outcome.UNDETERMINED;
        }

        throw new TokenNotInCurrentDeploymentException(
                "Token " + policyId + " belongs to a different protocol deployment. It has no"
                        + " directory node under the deployment in use (protocolTxHash="
                        + currentProtocolTxHash + "); its node was last written under deployment "
                        + lastSeen.get() + ". A programmable token cannot be moved between"
                        + " deployments: its policy id is the hash of issuance_mint parameterised"
                        + " by the deployment's protocol params, so the registry node and every"
                        + " credential derived for it belong to that deployment. Either operate"
                        + " against protocol version " + lastSeen.get()
                        + ", or mint a new token under " + currentProtocolTxHash + ".");
    }
}
