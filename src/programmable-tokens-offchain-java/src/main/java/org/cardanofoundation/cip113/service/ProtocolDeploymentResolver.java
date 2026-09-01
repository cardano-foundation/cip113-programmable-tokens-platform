package org.cardanofoundation.cip113.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

/**
 * Resolves a {@code protocolTxHash} coming off the API to the deployment record it names.
 *
 * <h2>The problem this exists for</h2>
 *
 * A deployment has TWO transaction hashes, and the API hands out one while the operations
 * endpoints historically accepted only the other:
 *
 * <ul>
 *   <li>{@code protocol-bootstraps-{network}.json} keys each record by the
 *       <strong>reference-script publish</strong> transaction — {@code e466d078…} on the
 *       current preview deployment. That is the key
 *       {@link ProtocolBootstrapService#getProtocolBootstrapParamsByTxHash(String)} indexes.</li>
 *   <li>{@code /protocol-params/versions} lists INDEXED versions, each keyed by the
 *       transaction that <strong>created the params UTxO</strong> — {@code 35954fc8…}. That is
 *       the value the frontend's version selector holds and sends back as
 *       {@code ?protocolTxHash=}.</li>
 * </ul>
 *
 * <p>In the 2026-05 preview deployment both were one transaction, so passing one where the
 * other was expected worked by coincidence and nothing distinguished them. The SDK's 2026-08
 * deployment splits bootstrap from publish, and the coincidence ended: every call carrying a
 * version hash failed with {@code Protocol version not found: 35954fc8…} — a token
 * pre-registration cannot start, and the message names a transaction that plainly exists on
 * chain, which is what makes it baffling rather than merely broken.
 *
 * <p>So resolution accepts <strong>either</strong> identity. Deployment records are matched to
 * indexed versions the same way {@code /versions} marks its default: by registry-node policy
 * and {@code programmable_logic_base} hash, the pair that actually identifies a protocol and
 * that an in-place upgrade is designed never to move. Accepting both is also backwards
 * compatible — a client still sending the publish hash keeps working.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolDeploymentResolver {

    private final ProtocolBootstrapService protocolBootstrapService;

    private final ProtocolParamsService protocolParamsService;

    /**
     * @param protocolTxHash either a deployment record's publish hash, or an indexed
     *                       protocol-params version hash. Null or empty selects the configured
     *                       default deployment.
     * @throws IllegalArgumentException if it is neither, naming both things that were tried
     */
    public ProtocolBootstrapParams resolve(String protocolTxHash) {
        if (protocolTxHash == null || protocolTxHash.isEmpty()) {
            return protocolBootstrapService.getProtocolBootstrapParams();
        }

        var byRecordKey = protocolBootstrapService.getProtocolBootstrapParamsByTxHash(protocolTxHash);
        if (byRecordKey.isPresent()) {
            return byRecordKey.get();
        }

        var version = protocolParamsService.getByTxHash(protocolTxHash);
        if (version.isPresent()) {
            var deployment = matchDeployment(
                    protocolBootstrapService.getAllBootstraps().values(), version.get());
            if (deployment.isPresent()) {
                log.debug("Resolved protocol params version {} to deployment record {}",
                        protocolTxHash, deployment.get().txHash());
                return deployment.get();
            }
            // Indexed, but from a deployment this build has no record of — a real state, not a
            // typo, and worth saying so rather than repeating "not found".
            throw new IllegalArgumentException(
                    "Protocol version " + protocolTxHash + " is indexed (registryNodePolicyId="
                            + version.get().getRegistryNodePolicyId() + ", progLogic="
                            + version.get().getProgLogicScriptHash() + ") but no deployment record "
                            + "in protocol-bootstraps-<network>.json matches it. The record for that "
                            + "deployment is missing or describes a different protocol.");
        }

        throw new IllegalArgumentException(
                "Protocol version not found: " + protocolTxHash
                        + " (matched neither a deployment record in protocol-bootstraps-<network>.json "
                        + "nor an indexed protocol-params version). If it is a params transaction, it may "
                        + "not be allowlisted in protocol-params-<network>.json, or the store may not have "
                        + "synced past it yet.");
    }

    /**
     * The deployment whose registry-node policy and {@code programmable_logic_base} hash both
     * equal the indexed version's. Pure and package-visible so it can be tested without a
     * Spring context or a database.
     */
    static Optional<ProtocolBootstrapParams> matchDeployment(
            Collection<ProtocolBootstrapParams> deployments, ProtocolParamsEntity version) {
        return deployments.stream()
                .filter(d -> d.directoryMintParams() != null
                        && d.programmableLogicBaseParams() != null
                        && d.directoryMintParams().scriptHash().equals(version.getRegistryNodePolicyId())
                        && d.programmableLogicBaseParams().scriptHash().equals(version.getProgLogicScriptHash()))
                .findFirst();
    }
}
