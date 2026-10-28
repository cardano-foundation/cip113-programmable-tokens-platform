package org.cardanofoundation.cip113.devnet;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.programmabletoken.cip113.tx.DefaultCip113ProtocolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.ccl.DeploymentMapper;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.offline.BootstrapFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stands up a CIP-113 v0.0.1 protocol on a Yaci DevKit devnet and proves that the platform and
 * cardano-client-lib describe the same deployment.
 *
 * <p>Skipped unless {@code CARDANO_BACKEND_URL} is set, like every other devnet class. Run:
 * <pre>
 *   yaci-devkit up --enable-yaci-store
 *   CARDANO_BACKEND_URL=http://localhost:8080/api/v1/ \
 *     ./gradlew test --tests '*DevnetProtocolGenesisTest*'
 * </pre>
 *
 * <p>It writes {@code build/devnet/protocol-bootstraps-devnet.json} (the platform record) and
 * {@code build/devnet/cip113-deployment.json} (CCL's descriptor) for the CCL devnet suite. They go
 * under {@code build/}, not into the committed {@code src/main/resources} record: a devnet
 * deployment lives only as long as the devnet.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "CARDANO_BACKEND_URL", matches = ".+")
class DevnetProtocolGenesisTest {

    static final Path OUT = Path.of("build/devnet");
    static final Path RECORD = OUT.resolve("protocol-bootstraps-devnet.json");
    static final Path DESCRIPTOR = OUT.resolve("cip113-deployment.json");

    private static final String BACKEND_URL = System.getenv("CARDANO_BACKEND_URL");
    private static final String ADMIN_URL =
            System.getenv().getOrDefault("YACI_ADMIN_URL", "http://localhost:10000/");

    @Test
    void genesisIsDescribedIdenticallyByThePlatformAndByCcl() throws Exception {
        var backend = new BFBackendService(BACKEND_URL, "devnet");
        topUp(BootstrapFixture.ADMIN.baseAddress(), 1000);
        topUp(BootstrapFixture.ADMIN.baseAddress(), 1000);
        topUp(BootstrapFixture.ALICE.baseAddress(), 1000);
        awaitFatUtxos(backend, BootstrapFixture.ADMIN.baseAddress(), 2);

        var deployed = new DevnetProtocolGenesis().deploy(backend, BootstrapFixture.ADMIN,
                BootstrapFixture.NETWORK);
        ProtocolBootstrapParams record = deployed.record();

        // 1. The platform re-derives every core script from the record alone.
        var factory = new CoreScriptFactory(new CoreBlueprint());
        Map<CoreValidator, String> recorded = Map.of(
                CoreValidator.PROTOCOL_PARAMS, record.protocolParams().policyId(),
                CoreValidator.PROGRAMMABLE_LOGIC_BASE, record.programmableLogicBase().scriptHash(),
                CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, record.programmableLogicGlobal().scriptHash(),
                CoreValidator.TRANSFER, record.transfer().scriptHash(),
                CoreValidator.THIRD_PARTY, record.thirdParty().scriptHash(),
                CoreValidator.UNFRACKING, record.unfracking().scriptHash(),
                CoreValidator.ISSUANCE_LOGIC, record.issuanceLogic().scriptHash(),
                CoreValidator.UPGRADE_MULTISIG, record.upgradeMultisig().scriptHash(),
                CoreValidator.REGISTRY, record.registry().scriptHash(),
                CoreValidator.ISSUANCE_CBOR_HEX_MINT, record.issuance().policyId());
        for (var e : recorded.entrySet()) {
            assertThat(factory.script(e.getKey(), record).getPolicyId())
                    .as("platform derivation of %s from the devnet record", e.getKey())
                    .isEqualToIgnoringCase(e.getValue());
        }

        // 2. CCL resolves the same deployment from chain, and the live datum names exactly the
        //    credentials genesis described (no "credentials differ" adoption).
        Cip113Deployment described = deployed.deployment();
        var resolved = new DefaultCip113ProtocolService(backend, described).resolveDeployment();
        assertThat(resolved.isSuccessful()).as("CCL resolveDeployment: %s", resolved.getResponse()).isTrue();
        Cip113Deployment live = resolved.getValue();
        assertThat(live.getProgrammableLogicGlobalHash()).isEqualToIgnoringCase(described.getProgrammableLogicGlobalHash());
        assertThat(live.getIssuanceLogicHash()).isEqualToIgnoringCase(described.getIssuanceLogicHash());
        assertThat(live.getTransferScriptHash()).isEqualToIgnoringCase(described.getTransferScriptHash());
        assertThat(live.getThirdPartyScriptHash()).isEqualToIgnoringCase(described.getThirdPartyScriptHash());
        assertThat(live.getUpgradeScriptHash()).isEqualToIgnoringCase(described.getUpgradeScriptHash());
        assertThat(live.getReferenceScripts()).hasSize(7);

        // 3. The platform's mapper turns the written record into exactly the descriptor genesis
        //    observed on chain — including the issuance-template outref the record does not carry.
        assertThat(DeploymentMapper.toDeployment(record, BootstrapFixture.NETWORK))
                .usingRecursiveComparison().isEqualTo(described);

        // 4. Hand both descriptions to the CCL devnet suite.
        Files.createDirectories(OUT);
        var json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        json.writeValue(RECORD.toFile(), List.of(record));
        json.writeValue(DESCRIPTOR.toFile(), described);
        log.info("[devnet genesis] wrote {} and {} for bootstrap {}", RECORD, DESCRIPTOR, record.txHash());
    }

    static void topUp(String address, long ada) throws Exception {
        var c = (HttpURLConnection) URI.create(ADMIN_URL + "local-cluster/api/addresses/topup").toURL().openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        try (OutputStream os = c.getOutputStream()) {
            os.write(String.format("{\"address\": \"%s\", \"adaAmount\": %d}", address, ada)
                    .getBytes(StandardCharsets.UTF_8));
        }
        String body = new String((c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream())
                .readAllBytes(), StandardCharsets.UTF_8);
        log.info("[devnet genesis] topup {} ADA to {}: {}", ada, address, body);
        assertThat(body).as("devkit topup of %s", address).contains("\"status\":true");
    }

    static void awaitFatUtxos(BFBackendService backend, String address, int count) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            var utxos = backend.getUtxoService().getUtxos(address, 100, 1);
            long fat = utxos.isSuccessful() ? utxos.getValue().stream()
                    .filter(u -> u.getAmount().stream().anyMatch(a -> "lovelace".equals(a.getUnit())
                            && a.getQuantity().compareTo(BigInteger.valueOf(100_000_000L)) > 0))
                    .count() : 0;
            if (fat >= count) return;
            log.info("[devnet genesis] waiting for {} fat UTxOs at {}: query ok={} response={}",
                    count, address, utxos.isSuccessful(), utxos.isSuccessful() ? utxos.getValue().size() : utxos.getResponse());
            Thread.sleep(1000);
        }
        throw new IllegalStateException("top-ups never reached " + address);
    }
}
