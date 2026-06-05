package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.SubstandardValidator;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

/** Loads the ported BaFin validators from {@code security-token/plutus.json} and
 *  applies their script parameters. One method per validator.
 *
 *  Validator naming mirrors the upstream Aiken module structure verbatim; refer
 *  to {@code src/substandards/security-token/} for the on-chain code. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityTokenScriptBuilderService {

    private static final String SUBSTANDARD_ID = "security-token";

    /** Asset names from the ported {@code lib/constants.ak}. */
    public static final String CONFIG_ASSET_NAME_HEX = "436f6e666967";              // "Config"
    public static final String GLOBAL_STATE_ASSET_NAME_HEX = "476c6f62616c5374617465"; // "GlobalState"

    private final SubstandardService substandardService;

    // ── Global state ─────────────────────────────────────────────────────────

    public PlutusScript buildGlobalStateMintScript(TransactionInput bootstrapTxInput) {
        var contract = getContract("global_state.global_state_mint_validator.mint");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(bootstrapTxInput.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(bootstrapTxInput.getIndex()))
        );
        return applyParameters(contract, params, "global_state_mint");
    }

    public PlutusScript buildGlobalStateSpendScript(String securityAssetNameHex,
                                                    String issuancePolicyId,
                                                    String globalStatePolicyId) {
        var contract = getContract("global_state.global_state_spend_validator.spend");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(issuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId))
        );
        return applyParameters(contract, params, "global_state_spend");
    }

    // ── Denylist ─────────────────────────────────────────────────────────────

    public PlutusScript buildDenylistMintScript(String globalStatePolicyId,
                                                TransactionInput initInputOutRef) {
        var contract = getContract("denylist.mint.mint");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef)
        );
        return applyParameters(contract, params, "denylist_mint");
    }

    public PlutusScript buildDenylistSpendScript(String denylistLinkedListPolicyId) {
        var contract = getContract("denylist.denylist_validator.spend");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(denylistLinkedListPolicyId))
        );
        return applyParameters(contract, params, "denylist_spend");
    }

    // ── Power users ──────────────────────────────────────────────────────────

    public PlutusScript buildPowerUsersMintScript(String globalStatePolicyId,
                                                  TransactionInput initInputOutRef) {
        var contract = getContract("power_users.mint.mint");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef)
        );
        return applyParameters(contract, params, "power_users_mint");
    }

    public PlutusScript buildPowerUsersSpendScript(String globalStatePolicyId,
                                                   String powerUsersLinkedListPolicyId) {
        var contract = getContract("power_users.power_users_validator.spend");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersLinkedListPolicyId))
        );
        return applyParameters(contract, params, "power_users_spend");
    }

    // ── Minting logic (CIP-113 issuance gate) ────────────────────────────────

    public PlutusScript buildMintingLogicScript(String securityAssetNameHex,
                                                String globalStatePolicyId,
                                                String registryPolicyId,
                                                String powerUsersLinkedListPolicyId) {
        var contract = getContract("minting_logic_script.minting_logic_validator.withdraw");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(registryPolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersLinkedListPolicyId))
        );
        return applyParameters(contract, params, "minting_logic");
    }

    // ── Transfer logic (sender + optional receiver KYC + denylist) ───────────

    public PlutusScript buildTransferLogicScript(String securityAssetNameHex,
                                                 String globalStatePolicyId,
                                                 String registryPolicyId) {
        var contract = getContract("transfer_logic_script.transfer_logic_validator.withdraw");
        var params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(registryPolicyId))
        );
        return applyParameters(contract, params, "transfer_logic");
    }

    // Third-party transfer (admin seizure) is deferred from v1 — its builder is
    // intentionally absent. The ported validator code is still on disk under
    // src/substandards/security-token/validators/ so a future PR can wire it up.

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Encode an OutputReference as Aiken sees it: Constr 0 [bytes txid, int idx]. */
    private static ConstrPlutusData outputReferenceData(TransactionInput in) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(in.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(in.getIndex())));
    }

    private SubstandardValidator getContract(String contractPath) {
        return substandardService.getSubstandardValidator(SUBSTANDARD_ID, contractPath)
                .orElseThrow(() -> new IllegalStateException(
                        "security-token contract not found: " + contractPath));
    }

    private PlutusScript applyParameters(SubstandardValidator contract,
                                         ListPlutusData params,
                                         String scriptName) {
        try {
            var parameterizedCode = AikenScriptUtil.applyParamToScript(params, contract.scriptBytes());
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    parameterizedCode, PlutusVersion.v3);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build security-token " + scriptName + " script", e);
        }
    }
}
