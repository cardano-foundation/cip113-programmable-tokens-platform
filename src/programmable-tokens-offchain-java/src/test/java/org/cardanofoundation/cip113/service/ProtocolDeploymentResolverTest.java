package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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

import static org.junit.jupiter.api.Assertions.*;

/** Resolution rules for the sole supported alpha.4 deployment. */
class ProtocolDeploymentResolverTest {

    private static final String DEPLOYMENT_TX =
            "8314e59f3e240ba89fb7f7037cf3094307132ede0ac3a1d2515a09a9cc333bc8";
    private static ProtocolBootstrapParams deployment;

    /**
     * ⚑ READS src/test/resources/protocol-bootstraps-preview.json, WHICH SHADOWS THE SHIPPED
     * ONE. The shipped records are empty arrays: alpha.5 abandoned the alpha.4 instance and no
     * replacement is deployed yet, so every test that needs a real recorded generation broke
     * silently when they were emptied — this class among them, and nobody noticed because the
     * offline set was being run by name.
     *
     * The test resource is that alpha.4 record, preserved. The resolver under test is
     * version-agnostic — it decides WHICH recorded generation answers a query — so a real
     * generation is exactly the right input, and pinning it here means this stops breaking
     * every time the deployed protocol changes.
     */
    @BeforeAll
    static void loadCommittedRecord() throws Exception {
        var stream = ProtocolDeploymentResolverTest.class.getClassLoader()
                .getResourceAsStream("protocol-bootstraps-preview.json");
        List<ProtocolBootstrapParams> records = laxMapper().readValue(stream, new TypeReference<>() {});
        assertEquals(1, records.size(), "latest-only configuration must expose one contract generation");
        deployment = records.getFirst();
        assertEquals(DEPLOYMENT_TX, deployment.txHash());
    }

    /**
     * ⛔ MATCHES SPRING'S MAPPER, not Jackson's default. Spring Boot disables
     * FAIL_ON_UNKNOWN_PROPERTIES; a bare `new ObjectMapper()` does not. A record carrying a
     * field this build's model does not know — `unfrackingParameter`, added when the dispatcher
     * began recording what it was compiled against — parses fine in the running application and
     * threw here. A test stricter than production fails on inputs production accepts, which is
     * a false alarm rather than a finding.
     */
    private static ObjectMapper laxMapper() {
        return new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private static ProtocolParamsEntity version(String txHash, String registryPolicy, String plb) {
        return ProtocolParamsEntity.builder().txHash(txHash)
                .registryNodePolicyId(registryPolicy).progLogicScriptHash(plb).slot(121181106L).build();
    }

    @Test
    void currentVersionIdentityMatchesCurrentDeployment() {
        var indexed = version(DEPLOYMENT_TX, deployment.registry().scriptHash(),
                deployment.programmableLogicBase().scriptHash());
        assertEquals(deployment, ProtocolDeploymentResolver.matchDeployment(List.of(deployment), indexed).orElseThrow());
    }

    @Test
    void anInPlaceUpgradeVersionResolvesByImmutableIdentity() throws Exception {
        String upgradeTx = "ab".repeat(32);
        var resolver = resolverOver(List.of(version(upgradeTx, deployment.registry().scriptHash(),
                deployment.programmableLogicBase().scriptHash())));
        assertEquals(DEPLOYMENT_TX, resolver.resolve(upgradeTx).txHash());
    }

    @Test
    void partialOrUnknownIdentityDoesNotMatch() {
        assertTrue(ProtocolDeploymentResolver.matchDeployment(List.of(deployment),
                version("x", deployment.registry().scriptHash(), "00".repeat(28))).isEmpty());
        assertTrue(ProtocolDeploymentResolver.matchDeployment(List.of(deployment),
                version("x", "00".repeat(28), deployment.programmableLogicBase().scriptHash())).isEmpty());
    }

    private static ProtocolDeploymentResolver resolverOver(List<ProtocolParamsEntity> indexed) throws Exception {
        var bootstrapService = new ProtocolBootstrapService(laxMapper(), new AppConfig.Network("preview"));
        bootstrapService.init();
        var repo = Mockito.mock(ProtocolParamsRepository.class);
        Mockito.when(repo.findAllByOrderBySlotAsc()).thenReturn(indexed);
        var paramsService = new ProtocolParamsService(repo);
        paramsService.init();
        return new ProtocolDeploymentResolver(bootstrapService, paramsService);
    }

    @Test
    void deploymentHashResolvesDirectly() throws Exception {
        assertEquals(DEPLOYMENT_TX, resolverOver(List.of()).resolve(DEPLOYMENT_TX).txHash());
    }

    @Test
    void unknownHashIsATypedBadRequest() throws Exception {
        var thrown = assertThrows(UnknownProtocolVersionException.class,
                () -> resolverOver(List.of()).resolve("cd".repeat(32)));
        assertTrue(thrown.getMessage().contains("cd".repeat(32)));
        assertInstanceOf(IllegalArgumentException.class, thrown);
        var status = UnknownProtocolVersionException.class.getAnnotation(ResponseStatus.class);
        assertNotNull(status);
        assertEquals(HttpStatus.BAD_REQUEST, status.value());
    }

    @Test
    void indexedVersionWithoutADeploymentRecordIsNamed() throws Exception {
        String orphan = "ef".repeat(32);
        var thrown = assertThrows(UnknownProtocolVersionException.class,
                () -> resolverOver(List.of(version(orphan, "11".repeat(28), "22".repeat(28)))).resolve(orphan));
        assertTrue(thrown.getMessage().contains("is indexed"));
    }
}
