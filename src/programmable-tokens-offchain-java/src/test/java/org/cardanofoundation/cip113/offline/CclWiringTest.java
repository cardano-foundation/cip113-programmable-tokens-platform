package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.ccl.CclDeployments;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Spring wires CCL's per-deployment service without touching any deployment, and the service
 * resolves each request against the deployment the request names.
 *
 * <p>A minimal context, no database and no node: the repository has no full
 * {@code @SpringBootTest}.
 */
class CclWiringTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withBean(AppConfig.Network.class, () -> new AppConfig.Network("preview"))
            .withBean(BFBackendService.class, () -> mock(BFBackendService.class))
            .withBean(ProtocolScriptBuilderService.class,
                    () -> new ProtocolScriptBuilderService(new CoreScriptFactory(new CoreBlueprint())))
            .withBean(CclDeployments.class);

    @Test
    void theContextStartsWithoutResolvingAnyDeployment() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            ctx.getBean(CclDeployments.class);
            verifyNoInteractions(ctx.getBean(BFBackendService.class));
        });
    }

    /**
     * A handler is called with the deployment the request selected ({@code protocolTxHash}); the
     * transaction must be built against that deployment, never the default one.
     */
    @Test
    void eachDeploymentGetsItsOwnService() throws Exception {
        List<ProtocolBootstrapParams> records = new ObjectMapper().readValue(
                Path.of("src/main/resources/protocol-bootstraps-preview.json").toFile(),
                new TypeReference<>() {});
        var first = records.getFirst();
        var other = new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(first)
                .replace(first.txHash(), "00".repeat(32)), ProtocolBootstrapParams.class);

        context.run(ctx -> {
            var deployments = ctx.getBean(CclDeployments.class);
            var service = deployments.service(first);

            assertThat(deployments.service(first)).as("one service per deployment").isSameAs(service);
            assertThat(service.advanced().deployment().getBootstrapTxHash()).isEqualTo(first.txHash());

            var otherService = deployments.service(other);
            assertThat(otherService).isNotSameAs(service);
            assertThat(otherService.advanced().deployment().getBootstrapTxHash()).isEqualTo("00".repeat(32));
        });
    }
}
