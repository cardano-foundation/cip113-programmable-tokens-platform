package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployments;
import com.bloxbean.cardano.client.programmabletoken.cip113.SmartWalletKey;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.ccl.DeploymentMapper;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform's committed deployment records describe, field for field, the deployments
 * cardano-client-lib ships for the same networks.
 *
 * <p>Two independent sources: the records come from each network's bootstrap ceremony, CCL's
 * constants were re-derived from the blueprint and read back from chain in CCL's own tests. A
 * mismatch means one side would build transactions for a different protocol instance.
 */
class DeploymentMapperTest {

    static Stream<Arguments> networks() {
        return Stream.of(
                Arguments.of("preview", Networks.preview(), Cip113Deployments.PREVIEW),
                Arguments.of("preprod", Networks.preprod(), Cip113Deployments.PREPROD),
                Arguments.of("mainnet", Networks.mainnet(), Cip113Deployments.MAINNET));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("networks")
    void theCommittedRecordMapsToCclsBuiltInDeployment(String name, Network network,
                                                         Cip113Deployment builtIn) throws Exception {
        // From disk, not the classpath: src/test/resources carries an older Preview record that
        // shadows the committed one on the test classpath.
        List<ProtocolBootstrapParams> records = new ObjectMapper().readValue(
                Path.of("src/main/resources/protocol-bootstraps-" + name + ".json").toFile(),
                new TypeReference<>() {});
        assertThat(records).as("one committed %s deployment", name).hasSize(1);

        Cip113Deployment mapped = DeploymentMapper.toDeployment(records.get(0), network);

        // Same protocol instance; only the application's wallet convention differs from CCL's
        // default (the platform keys wallets by the stake credential).
        assertThat(mapped).usingRecursiveComparison().ignoringFields("smartWalletKey").isEqualTo(builtIn);
        assertThat(mapped.getSmartWalletKey()).isEqualTo(SmartWalletKey.STAKE);
        assertThat(mapped.isUnfrackingEnabled())
                .as("the %s dispatcher was compiled against the disabled-unfracking sentinel", name)
                .isFalse();
    }
}
