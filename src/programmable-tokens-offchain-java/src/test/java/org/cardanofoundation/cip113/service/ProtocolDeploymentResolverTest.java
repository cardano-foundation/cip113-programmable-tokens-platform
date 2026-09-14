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

import static org.junit.jupiter.api.Assertions.*;

/** Resolution rules for the sole supported alpha.4 deployment. */
class ProtocolDeploymentResolverTest {

    private static final String DEPLOYMENT_TX =
            "8314e59f3e240ba89fb7f7037cf3094307132ede0ac3a1d2515a09a9cc333bc8";
    private static ProtocolBootstrapParams deployment;

    @BeforeAll
    static void loadCommittedRecord() throws Exception {
        var stream = ProtocolDeploymentResolverTest.class.getClassLoader()
                .getResourceAsStream("protocol-bootstraps-preview.json");
        List<ProtocolBootstrapParams> records = new ObjectMapper().readValue(stream, new TypeReference<>() {});
        assertEquals(1, records.size(), "latest-only configuration must expose one contract generation");
        deployment = records.getFirst();
        assertEquals(DEPLOYMENT_TX, deployment.txHash());
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
        var bootstrapService = new ProtocolBootstrapService(new ObjectMapper(), new AppConfig.Network("preview"));
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
