package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-2 safety net (no chain, no network): re-derives every protocol script hash from
 * {@code src/main/resources/protocol-bootstraps-devnet.json} using
 * {@link ProtocolScriptBuilderService} and asserts each result equals the hash recorded in that
 * file.
 *
 * <p>That JSON is the handoff record of tx
 * {@code 96343b91bda3ec81de743f45a5acc4f30b7e9f37ed0a54534ce0499f35ec015d}, produced by
 * {@code PreviewProtocolDeploymentMintTest.deploy()} against the local Yaci devnet and supplied
 * as ground truth for this port (a later, independent re-run of the same deployment tracked in
 * {@code docs/PLATFORM-V0.4.0-PORT-PLAN.md}/{@code docs/deployments/devnet-v0.4.0.json}, which
 * describe an earlier tx and different hashes — the two are not the same deployment, so do not
 * cross-check hashes between them). Agreement between what this test derives and what is
 * recorded here is evidence that the parameter shape and order
 * {@link ProtocolScriptBuilderService} applies matches {@code plutus.json}'s
 * {@code validators[].parameters}, not a tautology against a value this test invented itself —
 * though for {@code programmable_logic_base} specifically, note that a bootstrap tx being
 * accepted on-chain does not itself validate PLB's parameterization: PLB is only ever published
 * as a reference script at bootstrap time, never evaluated by that transaction.
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
     * issuance_mint is deliberately NOT asserted against a recorded hash the way the other six
     * builders are. Its 3rd parameter, {@code minting_logic_cred}, is the *substandard's* issuer
     * script — not part of the protocol bootstrap, and different per token — so unlike the other
     * builders there is no fixed "protocol" policy id for issuance_mint anywhere in the bootstrap
     * record to compare against: the deployment instead records a split prefix/postfix CBOR
     * template built around a dummy placeholder credential (see
     * {@code PreviewProtocolDeploymentMintTest.deploy()}, step 10), not an applied policy id.
     *
     * <p>{@code AikenScriptUtil.applyParamToScript} performs no arity check, so merely asserting
     * the output is *some* well-formed 28-byte hash would pass just as well with 3 parameters
     * applied as with the fixed 4 — it would not have caught the {@code plg_stake_cred} bug this
     * builder was fixed for. Instead this test follows {@code deploy()}'s own technique
     * (step 10, splitting the serialized template on the dummy marker): serialize the applied
     * script body, split it on the *substandard* script's hash (the 3rd parameter, which is
     * unambiguous and known here), and assert the PLG credential bytes (the 4th parameter) landed
     * in the postfix, i.e. were applied *after* the substandard credential. Dropping or misplacing
     * {@code plg_stake_cred} makes this fail.
     */
    @Test
    void issuanceMintAppliesFourParametersInOrder() throws Exception {
        var substandardIssueScript = protocolScriptBuilderService.getParameterizedAlwaysFailScript("deadbeef");

        var script = protocolScriptBuilderService.getParameterizedIssuanceMintScript(params, substandardIssueScript);

        var serializedBody = HexUtil.encodeHexString(script.serializeScriptBody());
        var substandardHashHex = HexUtil.encodeHexString(substandardIssueScript.getScriptHash());
        var parts = serializedBody.split(substandardHashHex);

        assertEquals(2, parts.length,
                "substandard credential must appear exactly once in the applied issuance_mint body");
        assertTrue(parts[1].contains(params.programmableLogicGlobalPrams().scriptHash()),
                "plg_stake_cred (PLG's hash) must be applied after minting_logic_cred — "
                        + "its bytes should appear in the postfix following the substandard credential");
    }
}
