package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.repository.ProtocolParamsRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /** The superseded 2026-05 deployment. */
    private static final String LEGACY_TX =
            "a432339cbd7318222c8c51ed4fb52ee4c68f676037622aa7361dd45d897324a4";

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
                .filter(d -> LEGACY_TX.equals(d.txHash()))
                .findFirst()
                .orElseThrow();

        var match = ProtocolDeploymentResolver.matchDeployment(deployments, version(
                "irrelevant",
                legacy.directoryMintParams().scriptHash(),
                legacy.programmableLogicBaseParams().scriptHash()));

        assertTrue(match.isPresent());
        assertEquals(legacy.txHash(), match.get().txHash());
    }

    // ── resolve() end to end, offline ────────────────────────────────────────
    //
    // Built with the real ProtocolBootstrapService (it loads the committed records off the
    // classpath, no network) and a ProtocolParamsService over a mocked repository, so the
    // indexed-version side can be posed exactly.

    private static ProtocolDeploymentResolver resolverOver(List<ProtocolParamsEntity> indexed)
            throws Exception {
        var bootstrapService = new ProtocolBootstrapService(new ObjectMapper(),
                new AppConfig.Network("preview"));
        bootstrapService.init();

        var repo = Mockito.mock(ProtocolParamsRepository.class);
        Mockito.when(repo.findAllByOrderBySlotAsc()).thenReturn(indexed);
        var paramsService = new ProtocolParamsService(repo);
        paramsService.init();

        return new ProtocolDeploymentResolver(bootstrapService, paramsService);
    }

    /**
     * The reported defect: a bad hash answered 500. It must be a typed client error, because
     * only the type distinguishes "the caller sent nonsense" from "the server broke" — and on a
     * monitored deployment a 500 is what pages someone.
     */
    @Test
    void anUnresolvableHashThrowsATypedClientError() throws Exception {
        var resolver = resolverOver(List.of());

        var thrown = assertThrows(UnknownProtocolVersionException.class,
                () -> resolver.resolve("ab".repeat(32)));

        assertTrue(thrown.getMessage().contains("ab".repeat(32)),
                "the message must name the hash that failed");
        assertInstanceOf(IllegalArgumentException.class, thrown,
                "must stay an IllegalArgumentException: controllers that already catch that type "
                        + "answer 400 today, and narrowing the hierarchy would silently regress them");
    }

    /** The 400 has to come from somewhere; on the propagating path it is this annotation. */
    @Test
    void theTypedErrorCarriesBadRequest() {
        var status = UnknownProtocolVersionException.class.getAnnotation(ResponseStatus.class);

        assertNotNull(status, "UnknownProtocolVersionException must carry @ResponseStatus");
        assertEquals(HttpStatus.BAD_REQUEST, status.value());
    }

    @Test
    void aKnownPublishHashResolvesWithoutTouchingTheIndex() throws Exception {
        var resolver = resolverOver(List.of());

        assertEquals(PUBLISH_TX, resolver.resolve(PUBLISH_TX).txHash());
    }

    @Test
    void anIndexedVersionHashResolvesThroughTheIndex() throws Exception {
        var resolver = resolverOver(List.of(version(BOOTSTRAP_TX, REGISTRY_POLICY, PROG_LOGIC)));

        assertEquals(PUBLISH_TX, resolver.resolve(BOOTSTRAP_TX).txHash());
    }

    /**
     * The steward's control from the cluster verification, adopted here: it separates "resolves
     * correctly" from "accepts anything and quietly serves the default". A hash belonging to the
     * SUPERSEDED deployment must resolve to that deployment — not to the live one, and not to
     * whatever happens to be first.
     */
    @Test
    void asupersededHashResolvesToItsOwnDeploymentNotTheLiveOne() throws Exception {
        var legacy = deployments.stream()
                .filter(d -> LEGACY_TX.equals(d.txHash()))
                .findFirst()
                .orElseThrow();
        var resolver = resolverOver(List.of(version("legacyVersionTx",
                legacy.directoryMintParams().scriptHash(),
                legacy.programmableLogicBaseParams().scriptHash())));

        var resolved = resolver.resolve("legacyVersionTx");

        assertEquals(LEGACY_TX, resolved.txHash());
        assertTrue(!PUBLISH_TX.equals(resolved.txHash()),
                "resolving a superseded version must NOT hand back the live deployment — that is "
                        + "the difference between resolving and merely accepting");
    }

    /** Indexed, but from a protocol with no committed record: a distinct, nameable state. */
    @Test
    void anIndexedVersionWithNoMatchingRecordSaysSo() throws Exception {
        var resolver = resolverOver(List.of(version("orphanTx", "11".repeat(28), "22".repeat(28))));

        var thrown = assertThrows(UnknownProtocolVersionException.class,
                () -> resolver.resolve("orphanTx"));

        assertTrue(thrown.getMessage().contains("is indexed"),
                "an indexed-but-unmatched version must not be reported as simply not found");
    }
}
