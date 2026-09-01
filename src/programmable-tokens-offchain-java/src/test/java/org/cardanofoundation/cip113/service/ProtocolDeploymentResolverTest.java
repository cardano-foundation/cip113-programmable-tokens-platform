package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish-vs-bootstrap keying rule, pinned against the committed preview records.
 *
 * <p>Offline: exercises {@link ProtocolDeploymentResolver#matchDeployment} directly, so it
 * needs neither a Spring context nor a database.
 *
 * <p>What makes this worth having is that the bug it guards was invisible for a whole
 * deployment generation. The 2026-05 preview deployment did bootstrap and reference-script
 * publish in ONE transaction, so a deployment record's key and its indexed params version's
 * key were the same string and every lookup worked whichever one the caller happened to send.
 * The SDK's 2026-08 deployment splits them, and the same call started failing with
 * "Protocol version not found" naming a transaction that is plainly on chain.
 */
class ProtocolDeploymentResolverTest {

    /** The reference-script PUBLISH transaction — how a deployment record is keyed. */
    private static final String PUBLISH_TX =
            "e466d078444a4d750ccb99f4ebc1d22957772ff49bb53a0228e0ce45f5c6e479";

    /** The BOOTSTRAP transaction, which created the params UTxO — how a VERSION is keyed. */
    private static final String BOOTSTRAP_TX =
            "35954fc8c92db95dc7d1de361da3fe7d6ad0f6711f9501ebf7d7cf4219262031";

    private static final String REGISTRY_POLICY =
            "a6fece0d87571381dafeb40103c301b86e920cc8c37ac43d3a4c2c8e";

    private static final String PROG_LOGIC =
            "5f9ee621c098be5d3a8d7bf8579267cc949c197ece726daa0e8a446f";

    private static List<ProtocolBootstrapParams> deployments;

    @BeforeAll
    static void loadCommittedRecords() throws Exception {
        var stream = ProtocolDeploymentResolverTest.class.getClassLoader()
                .getResourceAsStream("protocol-bootstraps-preview.json");
        deployments = new ObjectMapper().readValue(stream, new TypeReference<>() {});
    }

    private static ProtocolParamsEntity version(String txHash, String registryPolicy, String progLogic) {
        return ProtocolParamsEntity.builder()
                .txHash(txHash)
                .registryNodePolicyId(registryPolicy)
                .progLogicScriptHash(progLogic)
                .slot(121181106L)
                .build();
    }

    /**
     * The live bug, in one assertion: the hash the frontend sends is NOT any record's key, so
     * a resolver that only consults record keys must fail — which is what it did.
     */
    @Test
    void theBootstrapHashIsNotAnyDeploymentRecordKey() {
        assertTrue(deployments.stream().noneMatch(d -> BOOTSTRAP_TX.equals(d.txHash())),
                "precondition: no record is keyed by the bootstrap tx — if this ever fails, the "
                        + "records changed shape and the resolver's reason for existing changed with it");
        assertTrue(deployments.stream().anyMatch(d -> PUBLISH_TX.equals(d.txHash())),
                "precondition: the publish tx IS a record key");
    }

    @Test
    void anIndexedVersionResolvesToItsDeploymentRecord() {
        var match = ProtocolDeploymentResolver.matchDeployment(
                deployments, version(BOOTSTRAP_TX, REGISTRY_POLICY, PROG_LOGIC));

        assertTrue(match.isPresent(), "the live deployment's params version must resolve");
        assertEquals(PUBLISH_TX, match.get().txHash(),
                "it must resolve to the record keyed by the PUBLISH transaction");
    }

    /**
     * The identity is the PAIR. Matching on registry policy alone would be enough today (the
     * two committed deployments differ in both), so this asserts the stricter rule directly
     * rather than relying on the fixture to distinguish it.
     */
    @Test
    void aVersionMatchingOnlyOneHalfOfTheIdentityDoesNotResolve() {
        var registryOnly = ProtocolDeploymentResolver.matchDeployment(
                deployments, version(BOOTSTRAP_TX, REGISTRY_POLICY, "00".repeat(28)));
        assertTrue(registryOnly.isEmpty(),
                "registry policy alone must not resolve a deployment");

        var progLogicOnly = ProtocolDeploymentResolver.matchDeployment(
                deployments, version(BOOTSTRAP_TX, "00".repeat(28), PROG_LOGIC));
        assertTrue(progLogicOnly.isEmpty(),
                "programmable_logic_base alone must not resolve a deployment");
    }

    @Test
    void anUnknownVersionResolvesToNothing() {
        var match = ProtocolDeploymentResolver.matchDeployment(
                deployments, version("ff".repeat(32), "11".repeat(28), "22".repeat(28)));

        assertTrue(match.isEmpty());
    }

    /**
     * The superseded 2026-05 deployment must still resolve from its own identity. Old records
     * are kept as history and the frontend can still select them, so a resolver that only knew
     * about the newest deployment would break version switching.
     */
    @Test
    void theSupersededDeploymentStillResolves() {
        var legacy = deployments.stream()
                .filter(d -> "a432339cbd7318222c8c51ed4fb52ee4c68f676037622aa7361dd45d897324a4"
                        .equals(d.txHash()))
                .findFirst()
                .orElseThrow();

        var match = ProtocolDeploymentResolver.matchDeployment(deployments, version(
                "irrelevant",
                legacy.directoryMintParams().scriptHash(),
                legacy.programmableLogicBaseParams().scriptHash()));

        assertTrue(match.isPresent());
        assertEquals(legacy.txHash(), match.get().txHash());
    }
}
