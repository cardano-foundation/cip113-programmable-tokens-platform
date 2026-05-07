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
 * Service for building parameterized KYC-Extended scripts.
 *
 * Identical structure to {@link KycScriptBuilderService}, but loads from the
 * "kyc-extended" plutus.json — different validator code → different script
 * hashes — even when parameter lists are byte-for-byte the same.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KycExtendedScriptBuilderService {

    private static final String SUBSTANDARD_ID = "kyc-extended";

    /** "GlobalState" in UTF-8 hex — must match constants.global_state_asset_name in Aiken */
    public static final String GLOBAL_STATE_ASSET_NAME_HEX = "476c6f62616c5374617465";

    private final SubstandardService substandardService;

    public PlutusScript buildIssueScript(String globalStatePolicyId, Credential adminCredential) {
        var contract = getContract("kyc_extended_transfer.issue.withdraw");

        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                PlutusSerializationHelper.serialize(adminCredential)
        );

        return applyParameters(contract, params, "kyc_extended_issue");
    }

    public PlutusScript buildTransferScript(
            String progLogicBaseScriptHash,
            String globalStatePolicyId) {

        var contract = getContract("kyc_extended_transfer.transfer.withdraw");

        var progLogicCredential = Credential.fromScript(progLogicBaseScriptHash);

        var params = ListPlutusData.of(
                PlutusSerializationHelper.serialize(progLogicCredential),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId))
        );

        return applyParameters(contract, params, "kyc_extended_transfer");
    }

    // ========== Global State Scripts ==========

    private ListPlutusData buildGlobalStateParams(TransactionInput bootstrapTxInput, String adminPkh) {
        return ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(bootstrapTxInput.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(bootstrapTxInput.getIndex())),
                BytesPlutusData.of(HexUtil.decodeHexString(adminPkh))
        );
    }

    public PlutusScript buildGlobalStateMintScript(TransactionInput bootstrapTxInput, String adminPkh) {
        var contract = getContract("global_state.global_state.mint");
        return applyParameters(contract, buildGlobalStateParams(bootstrapTxInput, adminPkh), "global_state_mint");
    }

    public PlutusScript buildGlobalStateSpendScript(TransactionInput bootstrapTxInput, String adminPkh) {
        var contract = getContract("global_state.global_state.spend");
        return applyParameters(contract, buildGlobalStateParams(bootstrapTxInput, adminPkh), "global_state_spend");
    }

    public Pair<PlutusScript, PlutusScript> buildGlobalStateScripts(
            TransactionInput bootstrapTxInput,
            String adminPkh) {

        try {
            var mintScript = buildGlobalStateMintScript(bootstrapTxInput, adminPkh);
            var spendScript = buildGlobalStateSpendScript(bootstrapTxInput, adminPkh);
            return new Pair<>(mintScript, spendScript);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build kyc-extended global state scripts", e);
        }
    }

    // ========== Private Helpers ==========

    private SubstandardValidator getContract(String contractPath) {
        return substandardService.getSubstandardValidator(SUBSTANDARD_ID, contractPath)
                .orElseThrow(() -> new IllegalStateException(
                        "kyc-extended contract not found: " + contractPath
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

            log.debug("Built kyc-extended {} script", scriptName);
            return script;

        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to build kyc-extended " + scriptName + " script",
                    e
            );
        }
    }
}
