package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Cross-implementation alpha.4 parity: Java re-derives the hashes deployed by SDK 0.9.x.
 * The committed Preview record is chain-verified and independent of this Java factory.
 */
class PreviewDeploymentRecordDerivationTest {

    private static final String PREVIEW_DEPLOYMENT_TX =
            "8314e59f3e240ba89fb7f7037cf3094307132ede0ac3a1d2515a09a9cc333bc8";

    private static ProtocolBootstrapParams params;
    private static CoreScriptFactory scripts;

    @BeforeAll
    static void loadCommittedRecord() throws Exception {
        var stream = PreviewDeploymentRecordDerivationTest.class.getClassLoader()
                .getResourceAsStream("protocol-bootstraps-preview.json");
        assertNotNull(stream);
        List<ProtocolBootstrapParams> records =
                new ObjectMapper().readValue(stream, new TypeReference<>() {});
        params = records.stream()
                .filter(record -> PREVIEW_DEPLOYMENT_TX.equals(record.txHash()))
                .findFirst()
                .orElseThrow();
        scripts = new CoreScriptFactory(new CoreBlueprint());
    }

    private static void assertDerives(CoreValidator validator, String deployed) throws Exception {
        assertEquals(deployed, scripts.script(validator, params).getPolicyId(),
                () -> validator + " does not reproduce the SDK alpha.4 Preview deployment");
    }

    @Test
    void allDeploymentScriptsReproduce() throws Exception {
        assertDerives(CoreValidator.PROTOCOL_PARAMS, params.protocolParams().policyId());
        assertDerives(CoreValidator.PROGRAMMABLE_LOGIC_BASE, params.programmableLogicBase().scriptHash());
        assertDerives(CoreValidator.TRANSFER, params.transfer().scriptHash());
        assertDerives(CoreValidator.THIRD_PARTY, params.thirdParty().scriptHash());
        assertDerives(CoreValidator.UNFRACKING, params.unfracking().scriptHash());
        assertDerives(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL,
                params.programmableLogicGlobal().scriptHash());
        assertDerives(CoreValidator.ISSUANCE_CBOR_HEX_MINT, params.issuance().policyId());
        assertDerives(CoreValidator.REGISTRY, params.registry().scriptHash());
        assertDerives(CoreValidator.ISSUANCE_LOGIC, params.issuanceLogic().scriptHash());
        assertDerives(CoreValidator.UPGRADE_MULTISIG, params.upgradeMultisig().scriptHash());
    }

    @Test
    void issuanceMintMatchesSdkForKnownMintingLogic() throws Exception {
        var mintingLogic = scripts.alwaysFail("deadbeef");
        assertEquals("79ad1c0a3e2b019d85bf60e1182a546f8ad43eae90bc0884fb1098de",
                mintingLogic.getPolicyId());
        assertEquals("efe296b9ec8e21bf8d8b05f7a2b0a5129a43b240c49137c7e66623db",
                scripts.issuanceMint(params, mintingLogic).getPolicyId());
    }

    @Test
    void sameTypedOneShotInputsRemainDistinct() {
        assertNotEquals(params.protocolParams().txInput(), params.upgradeMultisig().txInput(),
                "a shared fixture input makes the upgrade-multisig field-selection check vacuous");
    }

    @Test
    void dispatcherDelegatesAreDistinct() {
        assertEquals(3, Set.of(params.transfer().scriptHash(), params.thirdParty().scriptHash(),
                params.unfracking().scriptHash()).size());
    }
}
