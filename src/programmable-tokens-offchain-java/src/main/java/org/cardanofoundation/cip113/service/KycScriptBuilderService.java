package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.easy1staking.util.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.SubstandardValidator;
import org.cardanofoundation.cip113.util.PlutusSerializationHelper;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

/**
 * Service for building parameterized KYC scripts.
 * <p>
 * This service centralizes script parameterization logic for all KYC contracts:
 * <ul>
 *   <li><b>Issue Contract</b> - Parameterized with admin credential</li>
 *   <li><b>Transfer Contract</b> - Parameterized with prog logic base + global state policy ID</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycScriptBuilderService {

    private static final String SUBSTANDARD_ID = "kyc";

    /** "GlobalState" in UTF-8 hex — must match constants.global_state_asset_name in Aiken */
    public static final String GLOBAL_STATE_ASSET_NAME_HEX = "476c6f62616c5374617465";

    private final SubstandardService substandardService;

    /**
     * Build Issue Contract (withdraw)
     * <p>
     * Contract: kyc_transfer.issue.withdraw
     * Parameters: [_global_state_policy_id, permitted_cred]
     * The global_state_policy_id is a phantom parameter that makes each token's issue script unique.
     *
     * @param globalStatePolicyId     Global state policy ID — makes the script hash unique per token
     * @param adminCredential Admin's credential (key or script)
     * @return Parameterized PlutusScript v3
     */
    public PlutusScript buildIssueScript(String globalStatePolicyId, Credential adminCredential) {
        var contract = getContract("kyc_transfer.issue.withdraw");

        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                PlutusSerializationHelper.serialize(adminCredential)
        );

        return applyParameters(contract, params, "kyc_issue");
    }

    /**
     * Build Transfer Contract (withdraw)
     * <p>
     * Contract: kyc_transfer.transfer.withdraw
     * Parameters: [programmable_logic_base_cred, global_state_policy_id]
     *
     * @param progLogicBaseScriptHash Protocol's programmable logic base script hash
     * @param globalStatePolicyId     Global state policy ID
     * @return Parameterized PlutusScript v3
     */
    public PlutusScript buildTransferScript(
            String progLogicBaseScriptHash,
            String globalStatePolicyId) {

        var contract = getContract("kyc_transfer.transfer.withdraw");

        var progLogicCredential = Credential.fromScript(progLogicBaseScriptHash);

        var params = ListPlutusData.of(
                PlutusSerializationHelper.serialize(progLogicCredential),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId))
        );

        return applyParameters(contract, params, "kyc_transfer");
    }

    // ========== Global State Scripts ==========

    /**
     * Build the shared parameter list for the combined global_state validator.
     * Parameters: [tx0: ByteArray, index0: Int, owner_credential_hash: ByteArray]
     */
    private ListPlutusData buildGlobalStateParams(TransactionInput bootstrapTxInput, String adminPkh) {
        return ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(bootstrapTxInput.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(bootstrapTxInput.getIndex())),
                BytesPlutusData.of(HexUtil.decodeHexString(adminPkh))
        );
    }

    /**
     * Build Global State Mint handler (one-shot minting policy).
     * <p>
     * Contract: global_state.global_state.mint
     * Combined validator: mint policy ID == spend script hash.
     *
     * @param bootstrapTxInput Bootstrap UTXO reference
     * @param adminPkh         Admin's public key hash (hex string)
     * @return Parameterized PlutusScript v3
     */
    public PlutusScript buildGlobalStateMintScript(TransactionInput bootstrapTxInput, String adminPkh) {
        var contract = getContract("global_state.global_state.mint");
        return applyParameters(contract, buildGlobalStateParams(bootstrapTxInput, adminPkh), "global_state_mint");
    }

    /**
     * Build Global State Spend handler (admin-guarded spending).
     * <p>
     * Contract: global_state.global_state.spend
     * Same validator as mint — policy ID == script hash.
     *
     * @param bootstrapTxInput Bootstrap UTXO reference
     * @param adminPkh         Admin's public key hash (hex string)
     * @return Parameterized PlutusScript v3
     */
    public PlutusScript buildGlobalStateSpendScript(TransactionInput bootstrapTxInput, String adminPkh) {
        var contract = getContract("global_state.global_state.spend");
        return applyParameters(contract, buildGlobalStateParams(bootstrapTxInput, adminPkh), "global_state_spend");
    }

    /**
     * Build both global state scripts at once (mint + spend).
     * Both are the same combined validator with identical policy ID / script hash.
     *
     * @param bootstrapTxInput Bootstrap UTXO reference
     * @param adminPkh         Admin's public key hash (hex string)
     * @return Pair of (mintScript, spendScript)
     */
    public Pair<PlutusScript, PlutusScript> buildGlobalStateScripts(
            TransactionInput bootstrapTxInput,
            String adminPkh) {

        try {
            var mintScript = buildGlobalStateMintScript(bootstrapTxInput, adminPkh);
            var spendScript = buildGlobalStateSpendScript(bootstrapTxInput, adminPkh);
            return new Pair<>(mintScript, spendScript);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build global state scripts", e);
        }
    }

    // ========== Private Helpers ==========

    private SubstandardValidator getContract(String contractPath) {
        return substandardService.getSubstandardValidator(SUBSTANDARD_ID, contractPath)
                .orElseThrow(() -> new IllegalStateException(
                        "KYC contract not found: " + contractPath
                ));
    }

    private PlutusScript applyParameters(
            SubstandardValidator contract,
            ListPlutusData params,
            String scriptName) {

        try {
            var parameterizedCode = AikenScriptUtil.applyParamToScript(
                    params,
                    contract.scriptBytes()
            );

            var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    parameterizedCode,
                    PlutusVersion.v3
            );

            log.debug("Built KYC {} script", scriptName);
            return script;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to build KYC " + scriptName + " script",
                    e
            );
        }
    }
}
