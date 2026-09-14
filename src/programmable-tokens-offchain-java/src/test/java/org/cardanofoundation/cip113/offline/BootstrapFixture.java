package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.common.model.Network;
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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.PreviewConstants;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.blueprint.Validator;
import org.cardanofoundation.cip113.model.bootstrap.CredentialParams;
import org.cardanofoundation.cip113.model.bootstrap.IssuanceParams;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.RegistryParams;
import org.cardanofoundation.cip113.model.bootstrap.ScriptParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;
import org.cardanofoundation.cip113.model.bootstrap.UpgradeMultisigParams;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the alpha.4 protocol topology on an {@link OfflineChain}.
 *
 * <p>The fixture deliberately mirrors the SDK bootstrap's dependency graph instead of its
 * network-oriented transaction grouping. Three distinct one-shot inputs derive protocol params,
 * issuance CBOR and upgrade multisig. Protocol state, multisig state and the seven reference
 * scripts are then virtually submitted in dependency order. The offline chain does not model
 * phase-1 stake registration; individual tests model that state where it matters.
 */
@Slf4j
public final class BootstrapFixture {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    public static final Network NETWORK = new Network(0b0000, 42);
    public static final Account ADMIN = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC);
    public static final Account REF_INPUT = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC, 10, 0);
    public static final Account ALICE = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC, 1, 0);

    private static final String NONCE_ISSUANCE_ALWAYS_FAIL =
            "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";
    private static final String SENTINEL_NEXT_KEY =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    private BootstrapFixture() {
    }

    public record Bootstrapped(Transaction transaction,
                               ProtocolBootstrapParams params,
                               List<Utxo> outputs,
                               Utxo coordinationUtxo,
                               Utxo registryOriginUtxo,
                               Utxo issuanceCborHexUtxo,
                               Utxo plbRefUtxo,
                               Utxo plgRefUtxo,
                               Utxo transferRefUtxo,
                               Utxo thirdPartyRefUtxo,
                               Utxo unfrackingRefUtxo,
                               Utxo issuanceLogicRefUtxo,
                               Utxo upgradeMultisigRefUtxo,
                               PlutusScript programmableLogicBase,
                               PlutusScript programmableLogicGlobal,
                               PlutusScript transfer,
                               PlutusScript thirdParty,
                               PlutusScript unfracking,
                               PlutusScript issuanceLogic,
                               PlutusScript upgradeMultisig,
                               PlutusScript registryMint,
                               PlutusScript registrySpend,
                               PlutusScript issuanceCborHex,
                               PlutusScript protocolParamsMint,
                               PlutusScript coordinationSpend,
                               PlutusScript alwaysFail) {
    }

    public static List<Validator> protocolValidators() throws Exception {
        var plutus = OBJECT_MAPPER.readValue(
                BootstrapFixture.class.getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        return plutus.validators();
    }

    public static String compiledCodeFor(String title, List<Validator> validators) {
        return validators.stream().filter(v -> v.title().equals(title)).findAny()
                .orElseThrow(() -> new IllegalStateException("no validator titled " + title)).compiledCode();
    }

    public static PlutusScript applyParams(String compiledCode, PlutusData... params) {
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(ListPlutusData.of(params), compiledCode), PlutusVersion.v3);
    }

    public static ConstrPlutusData scriptCred(PlutusScript script) throws Exception {
        return ConstrPlutusData.of(1, BytesPlutusData.of(script.getScriptHash()));
    }

    public static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(""));
    }

    public static Bootstrapped bootstrap(OfflineChain chain) throws Exception {
        var validators = protocolValidators();
        var protocolSeed = chain.seedAda("cip113-alpha4-protocol-seed", ADMIN.baseAddress(), 0, 200);
        var issuanceSeed = chain.seedAda("cip113-alpha4-issuance-seed", ADMIN.baseAddress(), 0, 200);
        var upgradeSeed = chain.seedAda("cip113-alpha4-upgrade-seed", ADMIN.baseAddress(), 0, 200);

        var protocolRef = outputReference(protocolSeed);
        var issuanceRef = outputReference(issuanceSeed);
        var upgradeRef = outputReference(upgradeSeed);

        var alwaysFail = applyParams(compiledCodeFor("always_fail.always_fail.spend", validators),
                BytesPlutusData.of(HexUtil.decodeHexString(NONCE_ISSUANCE_ALWAYS_FAIL)));
        var upgradeMultisig = applyParams(
                compiledCodeFor("upgrade_multisig.upgrade_multisig.withdraw", validators), upgradeRef);
        var protocolParams = applyParams(
                compiledCodeFor("protocol_params.protocol_params.mint", validators), protocolRef);
        var paramsPolicy = BytesPlutusData.of(protocolParams.getScriptHash());
        var plb = applyParams(
                compiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators), paramsPolicy);
        var issuanceCborHex = applyParams(
                compiledCodeFor("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint", validators),
                issuanceRef, BytesPlutusData.of(alwaysFail.getScriptHash()));
        var registry = applyParams(compiledCodeFor("registry.registry.mint", validators),
                protocolRef, BytesPlutusData.of(issuanceCborHex.getScriptHash()));

        PlutusData plbCred = scriptCred(plb);
        PlutusData registryPolicy = BytesPlutusData.of(registry.getScriptHash());
        var maxInline = BigIntPlutusData.of(CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES);
        var transfer = applyParams(compiledCodeFor("transfer.transfer.withdraw", validators),
                plbCred, registryPolicy, maxInline);
        var thirdParty = applyParams(compiledCodeFor("third_party.third_party.withdraw", validators),
                plbCred, registryPolicy, maxInline);
        var unfracking = applyParams(compiledCodeFor("unfracking.unfracking.withdraw", validators),
                plbCred, registryPolicy, maxInline);
        var plg = applyParams(compiledCodeFor("programmable_logic_global.programmable_logic_global.withdraw", validators),
                BytesPlutusData.of(transfer.getScriptHash()),
                BytesPlutusData.of(thirdParty.getScriptHash()),
                BytesPlutusData.of(unfracking.getScriptHash()));
        var issuanceLogic = applyParams(compiledCodeFor("issuance_logic.issuance_logic.withdraw", validators),
                plbCred, registryPolicy, paramsPolicy, maxInline);

        chain.withScripts(alwaysFail, upgradeMultisig, protocolParams, plb, issuanceCborHex, registry,
                transfer, thirdParty, unfracking, plg, issuanceLogic);

        var quickTxBuilder = new QuickTxBuilder(chain.utxoSupplier(), chain.protocolParamsSupplier(),
                chain.transactionProcessor());

        // Mutable upgrade authority config. The datum is MultisigScript::Signature(admin payment key).
        var adminPkh = new Address(ADMIN.baseAddress()).getPaymentCredentialHash().orElseThrow();
        var multisigDatum = ConstrPlutusData.of(0, BytesPlutusData.of(adminPkh));
        var multisigNft = Asset.builder()
                .name(HexUtil.encodeHexString("UpgradeMultisig".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE).build();
        var multisigAddress = AddressProvider.getEntAddress(upgradeMultisig, NETWORK).getAddress();
        var multisigTx = new Tx().collectFrom(List.of(upgradeSeed))
                .mintAsset(upgradeMultisig, multisigNft, ConstrPlutusData.of(0))
                .payToContract(multisigAddress, List.of(Amount.ada(5),
                        Amount.asset(upgradeMultisig.getPolicyId(), multisigNft.getName(), BigInteger.ONE)), multisigDatum)
                .withChangeAddress(ADMIN.baseAddress());
        var builtMultisig = quickTxBuilder.compose(multisigTx)
                .withSigner(SignerProviders.signerFrom(ADMIN)).withTxEvaluator(chain.evaluator())
                .feePayer(ADMIN.baseAddress()).mergeOutputs(false).buildAndSign();
        var multisigHash = TransactionUtil.getTxHash(builtMultisig);
        var multisigOutputs = chain.submit(builtMultisig);
        var multisigUtxo = outputAt(multisigOutputs, multisigAddress);

        var paramsDatumModel = new CoreProtocolParamsDatum(
                Credential.fromScript(plg.getScriptHash()), Credential.fromScript(issuanceLogic.getScriptHash()),
                Credential.fromScript(transfer.getScriptHash()), Credential.fromScript(thirdParty.getScriptHash()),
                Credential.fromScript(upgradeMultisig.getScriptHash()), null);
        paramsDatumModel.validateForDeployment();

        var protocolParamNft = Asset.builder()
                .name(HexUtil.encodeHexString("ProtocolParams".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE).build();
        var registryNft = Asset.builder().name("0x").value(BigInteger.ONE).build();
        var issuanceNft = Asset.builder()
                .name(HexUtil.encodeHexString("IssuanceCborHex".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE).build();

        var originNodeDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""), BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),
                emptyVkey(), emptyVkey(), emptyVkey(), emptyVkey(), BytesPlutusData.of(""));

        var dummyPolicyId = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";
        var issuanceDummy = applyParams(compiledCodeFor("issuance_mint.issuance_mint.mint", validators),
                ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(dummyPolicyId))), paramsPolicy);
        var dummyBody = HexUtil.encodeHexString(issuanceDummy.serializeScriptBody());
        int marker = dummyBody.indexOf(dummyPolicyId);
        if (marker < 0 || marker != dummyBody.lastIndexOf(dummyPolicyId)) {
            throw new IllegalStateException("issuance template marker must occur exactly once");
        }
        var issuanceDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(dummyBody.substring(0, marker))),
                BytesPlutusData.of(HexUtil.decodeHexString(dummyBody.substring(marker + dummyPolicyId.length()))));

        var paramsAddress = AddressProvider.getEntAddress(protocolParams, NETWORK).getAddress();
        var registryAddress = AddressProvider.getEntAddress(registry, NETWORK).getAddress();
        var alwaysFailAddress = AddressProvider.getEntAddress(alwaysFail, NETWORK).getAddress();

        var issuanceCandidate = TransactionOutput.builder().address(alwaysFailAddress)
                .value(valueWithAsset(MinAdaCalculator.DUMMY_COIN_VAL, issuanceCborHex.getPolicyId(), issuanceNft))
                .inlineDatum(issuanceDatum).build();
        var issuanceCoin = new MinAdaCalculator(chain.protocolParams()).calculateMinAda(issuanceCandidate)
                .add(BigInteger.valueOf(1_999_999)).divide(BigInteger.valueOf(1_000_000))
                .multiply(BigInteger.valueOf(1_000_000));

        var stateTx = new Tx().collectFrom(List.of(protocolSeed, issuanceSeed))
                .mintAsset(protocolParams, protocolParamNft, ConstrPlutusData.of(0))
                .mintAsset(registry, registryNft, ConstrPlutusData.of(0))
                .mintAsset(issuanceCborHex, issuanceNft, ConstrPlutusData.of(0))
                .payToContract(paramsAddress,
                        ValueUtil.toAmountList(valueWithAsset(Amount.ada(5).getQuantity(), protocolParams.getPolicyId(), protocolParamNft)),
                        paramsDatumModel.toPlutusData())
                .payToContract(registryAddress,
                        ValueUtil.toAmountList(valueWithAsset(Amount.ada(5).getQuantity(), registry.getPolicyId(), registryNft)),
                        originNodeDatum)
                .payToContract(alwaysFailAddress,
                        ValueUtil.toAmountList(valueWithAsset(issuanceCoin, issuanceCborHex.getPolicyId(), issuanceNft)),
                        issuanceDatum)
                .withChangeAddress(ADMIN.baseAddress());
        var builtState = quickTxBuilder.compose(stateTx)
                .withSigner(SignerProviders.signerFrom(ADMIN)).withTxEvaluator(chain.evaluator())
                .feePayer(ADMIN.baseAddress()).mergeOutputs(false).buildAndSign();
        var stateHash = TransactionUtil.getTxHash(builtState);
        var stateOutputs = chain.submit(builtState);

        // Publish all reference scripts in the SDK's append-only order.
        var refFunding = chain.seedAda("cip113-alpha4-ref-funding", ADMIN.baseAddress(), 0, 200);
        var refTx = new Tx().from(ADMIN.baseAddress()).collectFrom(List.of(refFunding));
        for (var script : List.of(plb, plg, transfer, thirdParty, unfracking, issuanceLogic, upgradeMultisig)) {
            refTx.payToAddress(REF_INPUT.baseAddress(), Amount.ada(20), script);
        }
        refTx.withChangeAddress(ADMIN.baseAddress());
        var builtRefs = quickTxBuilder.compose(refTx)
                .withSigner(SignerProviders.signerFrom(ADMIN)).feePayer(ADMIN.baseAddress())
                .mergeOutputs(false).buildAndSign();
        var refHash = TransactionUtil.getTxHash(builtRefs);
        var refOutputs = chain.submit(builtRefs);

        int plbIdx = refScriptOutputIndex(builtRefs, plb);
        int plgIdx = refScriptOutputIndex(builtRefs, plg);
        int transferIdx = refScriptOutputIndex(builtRefs, transfer);
        int thirdPartyIdx = refScriptOutputIndex(builtRefs, thirdParty);
        int unfrackingIdx = refScriptOutputIndex(builtRefs, unfracking);
        int issuanceLogicIdx = refScriptOutputIndex(builtRefs, issuanceLogic);
        int upgradeIdx = refScriptOutputIndex(builtRefs, upgradeMultisig);

        var paramsUtxo = outputAt(stateOutputs, paramsAddress);
        var registryUtxo = outputAt(stateOutputs, registryAddress);
        var issuanceUtxo = outputAt(stateOutputs, alwaysFailAddress);
        var params = new ProtocolBootstrapParams(
                ProtocolBootstrapParams.CURRENT_SCHEMA_VERSION, stateHash,
                new org.cardanofoundation.cip113.model.bootstrap.ProtocolParams(
                        TxInput.from(protocolSeed), protocolParams.getPolicyId(), TxInput.from(paramsUtxo)),
                new ScriptParams(plb.getPolicyId()), new ScriptParams(transfer.getPolicyId()),
                new ScriptParams(thirdParty.getPolicyId()), new ScriptParams(unfracking.getPolicyId()),
                new ScriptParams(plg.getPolicyId()), CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES,
                new UpgradeMultisigParams(upgradeMultisig.getPolicyId(), TxInput.from(upgradeSeed), TxInput.from(multisigUtxo)),
                new TxInput(refHash, upgradeIdx),
                new CredentialParams("script", upgradeMultisig.getPolicyId()),
                new ScriptParams(issuanceLogic.getPolicyId()), new TxInput(refHash, issuanceLogicIdx),
                new IssuanceParams(TxInput.from(issuanceSeed), issuanceCborHex.getPolicyId(), alwaysFail.getPolicyId()),
                new RegistryParams(TxInput.from(protocolSeed), issuanceCborHex.getPolicyId(), registry.getPolicyId()),
                new TxInput(refHash, plbIdx), new TxInput(refHash, plgIdx),
                new TxInput(refHash, transferIdx), new TxInput(refHash, thirdPartyIdx),
                new TxInput(refHash, unfrackingIdx));

        log.info("alpha.4 bootstrap complete: stateTx={} multisigTx={} refTx={}", stateHash, multisigHash, refHash);
        return new Bootstrapped(builtState, params, stateOutputs, paramsUtxo, registryUtxo, issuanceUtxo,
                refOutputs.get(plbIdx), refOutputs.get(plgIdx), refOutputs.get(transferIdx),
                refOutputs.get(thirdPartyIdx), refOutputs.get(unfrackingIdx),
                refOutputs.get(issuanceLogicIdx), refOutputs.get(upgradeIdx),
                plb, plg, transfer, thirdParty, unfracking, issuanceLogic, upgradeMultisig,
                registry, registry, issuanceCborHex, protocolParams, protocolParams, alwaysFail);
    }

    private static ConstrPlutusData outputReference(Utxo utxo) {
        return ConstrPlutusData.of(0, BytesPlutusData.of(HexUtil.decodeHexString(utxo.getTxHash())),
                BigIntPlutusData.of(utxo.getOutputIndex()));
    }

    private static Value valueWithAsset(BigInteger coin, String policyId, Asset asset) {
        return Value.builder().coin(coin).multiAssets(List.of(
                MultiAsset.builder().policyId(policyId).assets(List.of(asset)).build())).build();
    }

    private static Utxo outputAt(List<Utxo> outputs, String address) {
        return outputs.stream().filter(u -> address.equals(u.getAddress())).findFirst()
                .orElseThrow(() -> new IllegalStateException("no bootstrap output at " + address));
    }

    public static int refScriptOutputIndex(Transaction tx, PlutusScript script) throws Exception {
        var wanted = HexUtil.encodeHexString(script.getScriptHash());
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            var ref = outputs.get(i).getScriptRef();
            if (ref != null && wanted.equals(HexUtil.encodeHexString(
                    ReferenceScriptUtil.deserializeScriptRef(ref).getScriptHash()))) return i;
        }
        throw new IllegalStateException("no output carries reference script " + wanted);
    }
}
