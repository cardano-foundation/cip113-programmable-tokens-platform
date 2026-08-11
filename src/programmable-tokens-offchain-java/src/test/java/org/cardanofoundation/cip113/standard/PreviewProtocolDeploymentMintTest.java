package org.cardanofoundation.cip113.standard;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.AbstractPreviewTest;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.bootstrap.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

@Slf4j
public class PreviewProtocolDeploymentMintTest extends AbstractPreviewTest {


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String NONCE_ISSUANCE_ALWAYS_FAIL = "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";

    private static final String NONCE_COORDINATION = "9c1f0c1e3a5d47b28e6f0a91d4c7b3e50f28a6d19b4c7e035a8d2f61c093b47e";

    private String ALWAYS_FAIL_CONTRACT;
    private String COORDINATION_SPEND_CONTRACT;
    private String PROTOCOL_PARAMS_CONTRACT;
    private String PROGRAMMABLE_LOGIC_BASE_CONTRACT;
    private String PROGRAMMABLE_LOGIC_GLOBAL_CONTRACT;
    private String UNFRACKING_CONTRACT;
    private String UPGRADE_MULTISIG_CONTRACT;
    private String ISSUANCE_CBOR_HEX_CONTRACT;
    private String ISSUANCE_CONTRACT;
    private String REGISTRY_MINT_CONTRACT;
    private String REGISTRY_SPEND_CONTRACT;

    @BeforeEach
    public void loadContracts() throws Exception {
        var plutus = OBJECT_MAPPER.readValue(this.getClass().getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        var validators = plutus.validators();
        ALWAYS_FAIL_CONTRACT = getCompiledCodeFor("always_fail.always_fail.spend", validators);
        COORDINATION_SPEND_CONTRACT = getCompiledCodeFor("coordination_spend.coordination_spend.spend", validators);
        PROTOCOL_PARAMS_CONTRACT = getCompiledCodeFor("protocol_params_mint.protocol_params_mint.mint", validators);
        PROGRAMMABLE_LOGIC_BASE_CONTRACT = getCompiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators);
        PROGRAMMABLE_LOGIC_GLOBAL_CONTRACT = getCompiledCodeFor("programmable_logic_global.programmable_logic_global.withdraw", validators);
        UNFRACKING_CONTRACT = getCompiledCodeFor("unfracking.unfracking.withdraw", validators);
        UPGRADE_MULTISIG_CONTRACT = getCompiledCodeFor("upgrade_multisig.upgrade_multisig.withdraw", validators);
        ISSUANCE_CBOR_HEX_CONTRACT = getCompiledCodeFor("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint", validators);
        ISSUANCE_CONTRACT = getCompiledCodeFor("issuance_mint.issuance_mint.mint", validators);
        REGISTRY_MINT_CONTRACT = getCompiledCodeFor("registry_mint.registry_mint.mint", validators);
        REGISTRY_SPEND_CONTRACT = getCompiledCodeFor("registry_spend.registry_spend.spend", validators);
    }

    private static PlutusScript applyParams(String compiledCode, PlutusData... params) {
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(ListPlutusData.of(params), compiledCode),
                PlutusVersion.v3);
    }

    private static ConstrPlutusData scriptCred(PlutusScript script) throws Exception {
        return ConstrPlutusData.of(1, BytesPlutusData.of(script.getScriptHash()));
    }

    @Test
    public void deploy() throws Exception {

        var dryRun = true;

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        if (!utxosOpt.isSuccessful() || utxosOpt.getValue().size() < 3) {
            log.warn("not enough utxos, splitting wallet");

            var splitTx = new Tx()
                    .from(adminAccount.baseAddress())
                    .payToAddress(adminAccount.baseAddress(), Amount.ada(5))
                    .payToAddress(adminAccount.baseAddress(), Amount.ada(5))
                    .payToAddress(adminAccount.baseAddress(), Amount.ada(5))
                    .withChangeAddress(adminAccount.baseAddress());

            var response = quickTxBuilder.compose(splitTx)
                    .withSigner(SignerProviders.signerFrom(adminAccount))
                    .mergeOutputs(false)
                    .completeAndWait();

            log.info("Completed: {}", response);

            Thread.sleep(30000L);

            utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        }
        var walletUtxos = utxosOpt.getValue().stream().limit(2).toList();

        var utxo1 = walletUtxos.getFirst();
        var utxo2 = walletUtxos.getLast();

        Assertions.assertNotEquals(utxo1, utxo2);

        // Output Reference - utxo1
        var utxo1OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo1.getTxHash())),
                BigIntPlutusData.of(utxo1.getOutputIndex()));

        // Output Reference - utxo2
        var utxo2OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo2.getTxHash())),
                BigIntPlutusData.of(utxo2.getOutputIndex()));

        // ---- 0. always_fail: still the immutable lock for the IssuanceCborHex UTxO
        var issuanceAlwaysFailScript = applyParams(ALWAYS_FAIL_CONTRACT,
                BytesPlutusData.of(HexUtil.decodeHexString(NONCE_ISSUANCE_ALWAYS_FAIL)));
        var issuanceAlwaysFailAddress = AddressProvider.getEntAddress(issuanceAlwaysFailScript, network);

        // ---- 1. coordination_spend: the (now spendable) home of the protocol-params NFT.
        // Nonce-parameterised on purpose — depending on the params policy would be circular.
        var coordinationSpendScript = applyParams(COORDINATION_SPEND_CONTRACT,
                BytesPlutusData.of(HexUtil.decodeHexString(NONCE_COORDINATION)));
        var coordinationAddress = AddressProvider.getEntAddress(coordinationSpendScript, network);
        log.info("coordinationAddress: {}", coordinationAddress.getAddress());

        // ---- 2. protocol_params_mint(utxo1, coordination_hash)
        // The 2nd param is *named* always_fail_hash but must be the coordination hash:
        // protocol_params_mint.ak requires the NFT output at address.from_script(that hash).
        var protocolParamsContract = applyParams(PROTOCOL_PARAMS_CONTRACT,
                utxo1OutputReference,
                BytesPlutusData.of(coordinationSpendScript.getScriptHash()));
        var paramsPolicy = BytesPlutusData.of(protocolParamsContract.getScriptHash());
        log.info("protocolParams policy: {}", protocolParamsContract.getPolicyId());

        // ---- 3-5. Everything anchored on the params policy (no PLG->PLB chain any more)
        var programmableLogicBaseContract = applyParams(PROGRAMMABLE_LOGIC_BASE_CONTRACT, paramsPolicy);
        var programmableLogicGlobalContract = applyParams(PROGRAMMABLE_LOGIC_GLOBAL_CONTRACT, paramsPolicy);
        var unfrackingContract = applyParams(UNFRACKING_CONTRACT, paramsPolicy);

        var programmableLogicGlobalRewardAddress = AddressProvider.getRewardAddress(programmableLogicGlobalContract, network);
        var unfrackingRewardAddress = AddressProvider.getRewardAddress(unfrackingContract, network);

        // ---- 6. upgrade_multisig: the trampoline-2 authority named by upgrade_logic_cred.
        // Preview deployment: 1-of-1 on the admin key. Swappable later without redeploying
        // coordination_spend, since the authority lives in the datum.
        var adminVkh = new com.bloxbean.cardano.client.address.Address(adminAccount.baseAddress())
                .getPaymentCredentialHash().orElseThrow();
        var upgradeMultisigContract = applyParams(UPGRADE_MULTISIG_CONTRACT,
                ListPlutusData.of(BytesPlutusData.of(adminVkh)),
                BigIntPlutusData.of(1));
        var upgradeMultisigRewardAddress = AddressProvider.getRewardAddress(upgradeMultisigContract, network);

        // ---- 7. issuance_cbor_hex_mint(utxo2, always_fail_hash)
        var issuanceCborHexContract = applyParams(ISSUANCE_CBOR_HEX_CONTRACT,
                utxo2OutputReference,
                BytesPlutusData.of(issuanceAlwaysFailScript.getScriptHash()));

        // ---- 8-9. registry_spend BEFORE registry_mint: registry_mint gained a
        // registry_spend_cred param, and RegistryInit binds the origin node's address to it.
        var registrySpendContract = applyParams(REGISTRY_SPEND_CONTRACT, paramsPolicy);
        var registrySpendAddress = AddressProvider.getEntAddress(
                Credential.fromScript(registrySpendContract.getScriptHash()), network);
        log.info("registrySpendAddress: {}", registrySpendAddress.getAddress());

        var registryMintContract = applyParams(REGISTRY_MINT_CONTRACT,
                utxo1OutputReference,
                BytesPlutusData.of(issuanceCborHexContract.getScriptHash()),
                scriptCred(registrySpendContract));
        log.info("registryMint policy: {}", registryMintContract.getPolicyId());

        if (true) return;
    }

    @Test
    public void registerAddress() throws Exception {
        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);

        var allWalletUtxos = utxosOpt.getValue();

        var stakeRegistrationTx = new Tx()
                .from(adminAccount.baseAddress())
                .collectFrom(List.of(allWalletUtxos.get(2)))
                .registerStakeAddress("stake_test17qma20wkn4dwuweaudjqelpra78m2x5qyqd3psmwfa7lj4g5qmpkq")
                .withChangeAddress(adminAccount.baseAddress());

        new QuickTxBuilder(bfBackendService).compose(stakeRegistrationTx)
                .feePayer(adminAccount.baseAddress())
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .completeAndWait();
    }


}
