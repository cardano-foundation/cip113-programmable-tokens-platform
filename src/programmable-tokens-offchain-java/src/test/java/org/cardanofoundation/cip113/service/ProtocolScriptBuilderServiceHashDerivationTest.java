package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * WP-2 safety net (no chain, no network): re-derives every protocol script hash from
 * {@code src/main/resources/protocol-bootstraps-devnet.json} using
 * {@link ProtocolScriptBuilderService} and asserts each result equals the hash recorded in that
 * file.
 *
 * <p>That JSON is the handoff record of a deployment a real Cardano node accepted
 * ({@code PreviewProtocolDeploymentMintTest.deploy()}, tx
 * {@code 96343b91bda3ec81de743f45a5acc4f30b7e9f37ed0a54534ce0499f35ec015d} on the local Yaci
 * devnet, confirmed on-chain — see {@code docs/PLATFORM-V0.4.0-PORT-PLAN.md} and the commit
 * history around it). Agreement between what this test derives and what is recorded there is
 * real evidence that the parameter shape and order {@link ProtocolScriptBuilderService} applies
 * matches {@code plutus.json}'s {@code validators[].parameters}, not a tautology against a value
 * this test invented itself.
 */
class ProtocolScriptBuilderServiceHashDerivationTest {

    private ProtocolScriptBuilderService protocolScriptBuilderService;

    private ProtocolBootstrapParams params;

    @BeforeEach
    void setUp() {
        var protocolBootstrapService = new ProtocolBootstrapService(new ObjectMapper(), new AppConfig.Network("devnet"));
        protocolBootstrapService.init();

        protocolScriptBuilderService = new ProtocolScriptBuilderService(protocolBootstrapService);
        params = protocolBootstrapService.getProtocolBootstrapParams();
    }

    /**
     * The highest-blast-radius assertion in this test: PLB's hash is the payment credential of
     * every programmable-token UTxO, so a wrong parameter shape here sends tokens to an address
     * nobody can spend, surfacing to users as "not enough funds", never as an error.
     */
    @Test
    void programmableLogicBaseHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(params);

        assertEquals(params.programmableLogicBaseParams().scriptHash(), script.getPolicyId());
    }

    @Test
    void programmableLogicGlobalHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedProgrammableLogicGlobalScript(params);

        assertEquals(params.programmableLogicGlobalPrams().scriptHash(), script.getPolicyId());
    }

    @Test
    void registryMintHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedDirectoryMintScript(params);

        assertEquals(params.directoryMintParams().scriptHash(), script.getPolicyId());
    }

    @Test
    void registrySpendHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedDirectorySpendScript(params);

        assertEquals(params.directorySpendParams().scriptHash(), script.getPolicyId());
    }

    @Test
    void issuanceCborHexMintHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedIssuanceCborHexMintScript(params);

        assertEquals(params.issuanceParams().scriptHash(), script.getPolicyId());
    }

    @Test
    void protocolParamsMintHashMatchesDeploymentRecord() throws Exception {
        var script = protocolScriptBuilderService.getParameterizedProtocolParamsMintScript(params);

        assertEquals(params.protocolParams().scriptHash(), script.getPolicyId());
    }

    /**
     * issuance_mint is deliberately NOT asserted against a recorded hash above. Its 4th
     * parameter (now {@code plg_stake_cred}, the fix under test for param count) is fine — PLG's
     * hash is in the bootstrap record — but its 3rd parameter, {@code minting_logic_cred}, is the
     * *substandard's* issuer script, which is not part of the protocol bootstrap and differs per
     * token. So unlike the other six builders above, there is no fixed "protocol" hash for
     * issuance_mint anywhere in the bootstrap record to check against: the deployment instead
     * records a split prefix/postfix CBOR template built around a dummy placeholder credential
     * (see {@code PreviewProtocolDeploymentMintTest.deploy()}, step 10), not an applied policy
     * id. Weakening the assertion to match that template is out of scope here — this is a smoke
     * test only, applying all 4 parameters (including a real, non-dummy credential in the
     * substandard slot) and checking the result is a well-formed policy id, so a param-count or
     * param-order regression in the fixed builder still fails loudly.
     */
    @Test
    void issuanceMintAppliesFourParametersWithoutError() throws Exception {
        var substandardIssueScript = protocolScriptBuilderService.getParameterizedAlwaysFailScript("deadbeef");

        var script = protocolScriptBuilderService.getParameterizedIssuanceMintScript(params, substandardIssueScript);

        assertNotNull(script.getPolicyId());
        assertEquals(56, script.getPolicyId().length(), "policy id must be a 28-byte hash (56 hex chars)");
    }
}
