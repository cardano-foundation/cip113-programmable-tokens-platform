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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Re-derives every parameterised core script hash of the LIVE preview deployment from the
 * committed {@code protocol-bootstraps-preview.json} and asserts each equals the hash that
 * record carries. Offline: no chain, no network, no database.
 *
 * <p>This is the independence {@link ProtocolScriptBuilderServiceHashDerivationTest} gave up
 * and documented wanting back. That test derives against a record {@code BootstrapFixture}
 * generates from the same codebase, so it can only prove self-consistency: if the factory's
 * parameter shape were wrong, the fixture would be built wrong the same way and the assertion
 * would still pass. The record checked here was produced by a different implementation (the
 * TypeScript SDK's deployment run) and verified on chain, so agreement is a genuine
 * cross-implementation check — the one thing that catches a parameterisation this backend and
 * the deployer disagree about.
 *
 * <p>It is also the transcription check. A deployment record is roughly sixty hex fields
 * copied by hand from a deployment report; {@code AikenScriptUtil.applyParamToScript}
 * validates neither arity nor type, so a single wrong nibble yields a perfectly well-formed
 * script under a hash nothing on chain will match, and the first symptom is a registry lookup
 * that finds nothing.
 *
 * <p><strong>Scope.</strong> Only the scripts whose parameters live in the bootstrap record
 * are covered. {@code always_fail} takes a caller-chosen nonce that the record does not store
 * (it keeps only the resulting hash), and {@code issuance_mint} is parameterised per
 * substandard rather than per deployment — the latter is covered by
 * {@code ProtocolScriptBuilderServiceHashDerivationTest#issuanceMintAppliesFourParametersInOrder}.
 */
class PreviewDeploymentRecordDerivationTest {

    /**
     * The reference-script publish transaction, which is what a deployment record is keyed by.
     * Pinned as a literal so that appending a future deployment to the file does not silently
     * move this test onto it: a new deployment should get its own pin, deliberately.
     */
    private static final String PREVIEW_DEPLOYMENT_TX =
            "e466d078444a4d750ccb99f4ebc1d22957772ff49bb53a0228e0ce45f5c6e479";

    private static ProtocolBootstrapParams params;

    private static CoreScriptFactory scriptFactory;

    @BeforeAll
    static void loadCommittedRecord() throws Exception {
        var stream = PreviewDeploymentRecordDerivationTest.class.getClassLoader()
                .getResourceAsStream("protocol-bootstraps-preview.json");
        assertNotNull(stream, "protocol-bootstraps-preview.json is not on the test classpath");

        List<ProtocolBootstrapParams> records =
                new ObjectMapper().readValue(stream, new TypeReference<>() {});

        params = records.stream()
                .filter(record -> PREVIEW_DEPLOYMENT_TX.equals(record.txHash()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "protocol-bootstraps-preview.json carries no deployment with txHash="
                                + PREVIEW_DEPLOYMENT_TX));

        scriptFactory = new CoreScriptFactory(new CoreBlueprint());
    }

    private void assertDerives(CoreValidator validator, String recordedHash) throws Exception {
        assertEquals(recordedHash, scriptFactory.script(validator, params).getPolicyId(),
                () -> validator + " derived from the committed preview record does not match the "
                        + "hash that record carries — the parameters, their order, or their "
                        + "wrapping disagree with the deployment.");
    }

    @Test
    void programmableLogicBase() throws Exception {
        assertDerives(CoreValidator.PROGRAMMABLE_LOGIC_BASE,
                params.programmableLogicBaseParams().scriptHash());
    }

    @Test
    void transferDelegate() throws Exception {
        assertDerives(CoreValidator.TRANSFER, params.transferParams().scriptHash());
    }

    @Test
    void thirdPartyDelegate() throws Exception {
        assertDerives(CoreValidator.THIRD_PARTY, params.thirdPartyParams().scriptHash());
    }

    @Test
    void unfrackingDelegate() throws Exception {
        assertDerives(CoreValidator.UNFRACKING, params.unfrackingParams().scriptHash());
    }

    @Test
    void protocolParamsMint() throws Exception {
        assertDerives(CoreValidator.PROTOCOL_PARAMS_MINT, params.protocolParams().scriptHash());
    }

    /**
     * Also pins the second parameter's meaning. {@code protocol_params_mint} takes the
     * coordination hash, which the record still stores under the stale key
     * {@code alwaysFailScriptHash}; wiring the real {@code always_fail} hash there instead is
     * the obvious reading of that key and yields a different, wrong policy id.
     */
    @Test
    void coordinationSpend() throws Exception {
        assertDerives(CoreValidator.COORDINATION_SPEND, params.coordinationParams().scriptHash());
        assertEquals(params.coordinationParams().scriptHash(),
                params.protocolParams().alwaysFailScriptHash(),
                "protocolParams.alwaysFailScriptHash is the COORDINATION hash under a stale key; "
                        + "it must equal coordinationParams.scriptHash");
    }

    /**
     * The delegates all take the same parameter, so PLB's dispatch is only meaningful while
     * their hashes are pairwise distinct — a property neither {@code protocol_params_mint} nor
     * {@code coordination_spend} enforces on chain.
     */
    @Test
    void delegateHashesArePairwiseDistinct() {
        var transfer = params.transferParams().scriptHash();
        var thirdParty = params.thirdPartyParams().scriptHash();
        var unfracking = params.unfrackingParams().scriptHash();

        assertEquals(3, java.util.Set.of(transfer, thirdParty, unfracking).size(),
                "transfer, third_party and unfracking must be three distinct scripts — a "
                        + "deployment that wired one script into two fields collapses two "
                        + "dispatch arms into one, silently");
    }

    @Test
    void upgradeMultisig() throws Exception {
        assertDerives(CoreValidator.UPGRADE_MULTISIG, params.upgradeMultisigParams().scriptHash());
    }

    @Test
    void issuanceCborHexMint() throws Exception {
        assertDerives(CoreValidator.ISSUANCE_CBOR_HEX_MINT, params.issuanceParams().scriptHash());
    }

    @Test
    void registryMint() throws Exception {
        assertDerives(CoreValidator.REGISTRY_MINT, params.directoryMintParams().scriptHash());
    }

    @Test
    void registrySpend() throws Exception {
        assertDerives(CoreValidator.REGISTRY_SPEND, params.directorySpendParams().scriptHash());
    }
}
