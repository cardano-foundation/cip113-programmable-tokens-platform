package org.cardanofoundation.cip113.ccl;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113ProgrammableTokenService;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CCL's programmable-token service for each protocol deployment a request names.
 *
 * <p>Keyed by the deployment's bootstrap transaction: a handler is always called with the
 * {@link ProtocolBootstrapParams} the request selected ({@code protocolTxHash}), and the
 * transaction must be built against that deployment's registry, coordination UTxO and issuance
 * template, never the default one. Resolved on first use, so the service can start with no
 * deployment recorded.
 */
@Slf4j
@Component
public class CclDeployments {

    private final AppConfig.Network network;
    private final BackendService backend;
    private final ProtocolScriptBuilderService protocolScripts;
    private final Map<String, Cip113ProgrammableTokenService> services = new ConcurrentHashMap<>();

    /**
     * {@code BFBackendService} rather than the interface: the context also holds a Koios backend.
     * CCL reads only the deployment's resolution inputs through it; every build reads through the
     * supplier of the builder it runs on (see {@link CclQuickTx}).
     */
    public CclDeployments(AppConfig.Network network, BFBackendService backend,
                          ProtocolScriptBuilderService protocolScripts) {
        this.network = network;
        this.backend = backend;
        this.protocolScripts = protocolScripts;
    }

    /** CCL's service for the deployment {@code params} describes, created once per deployment. */
    public Cip113ProgrammableTokenService service(ProtocolBootstrapParams params) {
        return services.computeIfAbsent(params.txHash(), txHash -> {
            var deployment = DeploymentMapper.toDeployment(params, network.getCardanoNetwork());
            log.info("CCL programmable-token service for deployment {} (params policy {})",
                    deployment.getBootstrapTxHash(), deployment.getParamsPolicy());
            var created = Cip113ProgrammableTokenService.create(backend, deployment);
            // A described deployment is not enough for CCL to witness every core script: the
            // registry (spent on every registration) is never published as a reference script,
            // so CCL needs its bytes. The platform derives them; CCL's own integration suite
            // does the same with registerAll(appliedScripts).
            created.advanced().scripts().registerAll(List.of(
                    protocolScripts.getParameterizedRegistryScript(params),
                    protocolScripts.getParameterizedProgrammableLogicBaseScript(params),
                    protocolScripts.getParameterizedProgrammableLogicGlobalScript(params),
                    protocolScripts.getParameterizedTransferScript(params),
                    protocolScripts.getParameterizedThirdPartyScript(params),
                    protocolScripts.getParameterizedUnfrackingScript(params),
                    protocolScripts.getParameterizedIssuanceLogicScript(params),
                    protocolScripts.getParameterizedUpgradeMultisigScript(params),
                    protocolScripts.getParameterizedProtocolParamsScript(params),
                    protocolScripts.getParameterizedIssuanceCborHexMintScript(params)));
            return created;
        });
    }

    /**
     * Makes a substandard's validators known to CCL as witnesses. CCL attaches the scripts a
     * registry node names from this service-wide registry; the platform compiles them per module.
     */
    public static void registerWitnesses(Cip113ProgrammableTokenService service, PlutusScript... scripts) {
        var registry = service.advanced().scripts();
        for (PlutusScript script : scripts) registry.register(script);
    }
}
