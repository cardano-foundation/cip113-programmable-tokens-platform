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
        SubstandardValidator contract = getContract("global_state.global_state_mint_validator.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(bootstrapTxInput.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(bootstrapTxInput.getIndex()))
        );
        return applyParameters(contract, params, "global_state_mint");
    }

    public PlutusScript buildGlobalStateSpendScript(String securityAssetNameHex,
                                                    String issuancePolicyId,
                                                    String globalStatePolicyId) {
        SubstandardValidator contract = getContract("global_state.global_state_spend_validator.spend");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(issuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId))
        );
        return applyParameters(contract, params, "global_state_spend");
    }

    // ── Denylist ─────────────────────────────────────────────────────────────

    public PlutusScript buildDenylistMintScript(String globalStatePolicyId,
                                                TransactionInput initInputOutRef) {
        SubstandardValidator contract = getContract("denylist.mint.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef)
        );
        return applyParameters(contract, params, "denylist_mint");
    }

    public PlutusScript buildDenylistSpendScript(String denylistLinkedListPolicyId) {
        SubstandardValidator contract = getContract("denylist.denylist_validator.spend");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(denylistLinkedListPolicyId))
        );
        return applyParameters(contract, params, "denylist_spend");
    }

    // ── Power users ──────────────────────────────────────────────────────────

    public PlutusScript buildPowerUsersMintScript(String globalStatePolicyId,
                                                  TransactionInput initInputOutRef) {
        SubstandardValidator contract = getContract("power_users.mint.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef)
        );
        return applyParameters(contract, params, "power_users_mint");
    }

    public PlutusScript buildPowerUsersSpendScript(String globalStatePolicyId,
                                                   String powerUsersLinkedListPolicyId) {
        SubstandardValidator contract = getContract("power_users.power_users_validator.spend");
        ListPlutusData params = ListPlutusData.of(
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
        SubstandardValidator contract = getContract("minting_logic_script.minting_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
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
        SubstandardValidator contract = getContract("transfer_logic_script.transfer_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(registryPolicyId))
        );
        return applyParameters(contract, params, "transfer_logic");
    }

    // ── Third-party transfer logic (power-user seizure / forced transfer) ────

    /**
     * The substandard's dedicated third-party transfer validator.
     *
     * <p><b>NOT currently wired into {@code RegistryNode.third_party_transfer_logic_script}
     * (index 4) — it cannot validate there at the pinned upstream commit.</b>
     * {@code third_party_transfer_logic_script.ak:44} passes
     * {@code constants.transfer_logic_script_registry_node_index} (= 3) to
     * {@code utils.derive_issuance_policy_id_from_registry_node}, which asserts that
     * registry-node field 3 equals the withdrawing script's own hash. Field 3 must hold
     * {@code transfer_logic_script} for CIP-113's {@code validate_transfer}, so this
     * validator can never self-locate in a node that also supports regular transfers.
     * {@code constants.third_party_transfer_logic_script_registry_node_index} (= 4) is
     * declared upstream but never referenced — an upstream bug at
     * FluidTokens/fn-bafin-cardano-sc {@code 7ae4ce3}. The vendored tree is
     * verbatim-pinned (see {@code UPSTREAM_PIN.json}), so the workaround lives in
     * {@code SecurityTokenSubstandardHandler.buildRegistrationTransaction}, which puts
     * {@code mintingLogic} in slot 4 instead. Re-point slot 4 at this script once
     * upstream passes the right constant.
     *
     * <p>Parameter order mirrors {@code third_party_transfer_logic_script.ak}:
     * {@code (security_asset_name, power_users_linked_list_policy_id, global_state_policy_id,
     * registry_policy_id)} — note {@code power_users} comes <em>before</em>
     * {@code global_state} here, unlike in {@code minting_logic_script}.
     */
    public PlutusScript buildThirdPartyTransferLogicScript(String securityAssetNameHex,
                                                           String powerUsersLinkedListPolicyId,
                                                           String globalStatePolicyId,
                                                           String registryPolicyId) {
        SubstandardValidator contract =
                getContract("third_party_transfer_logic_script.third_party_transfer_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersLinkedListPolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(registryPolicyId))
        );
        return applyParameters(contract, params, "third_party_transfer_logic");
    }

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
            String parameterizedCode = AikenScriptUtil.applyParamToScript(params, contract.scriptBytes());
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    parameterizedCode, PlutusVersion.v3);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build security-token " + scriptName + " script", e);
        }
    }
}
