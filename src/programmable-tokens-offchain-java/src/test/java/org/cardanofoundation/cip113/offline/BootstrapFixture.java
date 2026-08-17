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
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
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
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.blueprint.Validator;
import org.cardanofoundation.cip113.model.bootstrap.CoordinationParams;
import org.cardanofoundation.cip113.model.bootstrap.DirectoryMintParams;
import org.cardanofoundation.cip113.model.bootstrap.DirectorySpendParams;
import org.cardanofoundation.cip113.model.bootstrap.IssuanceParams;
import org.cardanofoundation.cip113.model.bootstrap.ProgrammableLogicBaseParams;
import org.cardanofoundation.cip113.model.bootstrap.ProgrammableLogicGlobalParams;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;
import org.cardanofoundation.cip113.model.bootstrap.UnfrackingParams;
import org.cardanofoundation.cip113.model.bootstrap.UpgradeMultisigParams;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds — and virtually submits — the CIP-113 protocol-bootstrap transaction on an
 * {@link OfflineChain}, so downstream tests can start from a protocol that actually exists.
 *
 * <p>This is the same transaction {@code standard/PreviewProtocolDeploymentMintTest#deploy}
 * builds against a live backend: same parameterisation chain, same datums, same mints, same
 * outputs in the same order. The only difference is where the two one-shot UTxOs and the
 * protocol parameters come from.
 */
@Slf4j
public final class BootstrapFixture {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Devnet-shaped network: testnet discriminator + magic 42, matching AppConfig.Network("devnet"). */
    public static final Network NETWORK = new Network(0b0000, 42);

    public static final Account ADMIN = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC);
    public static final Account REF_INPUT = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC, 10, 0);
    public static final Account ALICE = Account.createFromMnemonic(NETWORK, PreviewConstants.ADMIN_MNEMONIC, 1, 0);

    private static final String NONCE_ISSUANCE_ALWAYS_FAIL =
            "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";

    /** registry_node.sentinel_next_key — 30 bytes of 0xff, deliberately NOT 28. */
    private static final String SENTINEL_NEXT_KEY =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    private BootstrapFixture() {
    }

    /**
     * Everything a downstream transaction needs to talk about the freshly-bootstrapped protocol.
     *
     * @param transaction        the built + signed bootstrap transaction
     * @param params             the handoff record the production services consume
     * @param outputs            the bootstrap transaction's outputs, as spendable UTxOs
     * @param coordinationUtxo   output holding the ProtocolParams NFT + ProgrammableLogicGlobalParams datum
     * @param registryOriginUtxo output holding the origin registry node
     * @param issuanceCborHexUtxo output holding the issuance template, locked under always_fail
     * @param plbRefUtxo         reference-script output for programmable_logic_base
     * @param plgRefUtxo         reference-script output for programmable_logic_global
     * @param unfrackingRefUtxo  reference-script output for unfracking
     */
    public record Bootstrapped(Transaction transaction,
                               ProtocolBootstrapParams params,
                               List<Utxo> outputs,
                               Utxo coordinationUtxo,
                               Utxo registryOriginUtxo,
                               Utxo issuanceCborHexUtxo,
                               Utxo plbRefUtxo,
                               Utxo plgRefUtxo,
                               Utxo unfrackingRefUtxo,
                               PlutusScript programmableLogicBase,
                               PlutusScript programmableLogicGlobal,
                               PlutusScript unfracking,
                               PlutusScript registryMint,
                               PlutusScript registrySpend,
                               PlutusScript issuanceCborHex,
                               PlutusScript protocolParamsMint,
                               PlutusScript coordinationSpend,
                               PlutusScript alwaysFail) {
    }

    /** Load the protocol blueprint shipped in {@code src/main/resources/plutus.json}. */
    public static List<Validator> protocolValidators() throws Exception {
        var plutus = OBJECT_MAPPER.readValue(
                BootstrapFixture.class.getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        return plutus.validators();
    }

    public static String compiledCodeFor(String title, List<Validator> validators) {
        return validators.stream()
                .filter(v -> v.title().equals(title))
                .findAny()
                .orElseThrow(() -> new IllegalStateException("no validator titled " + title))
                .compiledCode();
    }

    public static PlutusScript applyParams(String compiledCode, PlutusData... params) {
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(ListPlutusData.of(params), compiledCode),
                PlutusVersion.v3);
    }

    public static ConstrPlutusData scriptCred(PlutusScript script) throws Exception {
        return ConstrPlutusData.of(1, BytesPlutusData.of(script.getScriptHash()));
    }

    /** registry_node.empty_vkey = VerificationKey(#"") — Credential index 0. */
    public static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(""));
    }

    /**
     * Build, evaluate and virtually submit the bootstrap transaction onto {@code chain}.
     *
     * <p>Seeds the two ~200 ADA admin UTxOs the one-shot mints are parameterised on, then leaves
     * the chain holding the bootstrap's outputs as the spendable set.
     */
    public static Bootstrapped bootstrap(OfflineChain chain) throws Exception {
        var validators = protocolValidators();

        var utxo1 = chain.seedAda("cip113-offline-bootstrap-utxo-1", ADMIN.baseAddress(), 0, 200);
        var utxo2 = chain.seedAda("cip113-offline-bootstrap-utxo-2", ADMIN.baseAddress(), 0, 200);
        var walletUtxos = List.of(utxo1, utxo2);

        var utxo1OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo1.getTxHash())),
                BigIntPlutusData.of(utxo1.getOutputIndex()));
        var utxo2OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo2.getTxHash())),
                BigIntPlutusData.of(utxo2.getOutputIndex()));

        // ---- 0. always_fail: the immutable lock for the IssuanceCborHex UTxO
        var alwaysFail = applyParams(compiledCodeFor("always_fail.always_fail.spend", validators),
                BytesPlutusData.of(HexUtil.decodeHexString(NONCE_ISSUANCE_ALWAYS_FAIL)));
        var alwaysFailAddress = AddressProvider.getEntAddress(alwaysFail, NETWORK);

        // ---- 1. coordination_spend, nonce-parameterised on the bootstrap UTxO
        var coordinationNonce = Blake2bUtil.blake2bHash256(
                HexUtil.decodeHexString(utxo1.getTxHash() + String.format("%02x", utxo1.getOutputIndex())));
        var coordinationSpend = applyParams(
                compiledCodeFor("coordination_spend.coordination_spend.spend", validators),
                BytesPlutusData.of(coordinationNonce));
        var coordinationAddress = AddressProvider.getEntAddress(coordinationSpend, NETWORK);

        // ---- 2. protocol_params_mint(utxo1, coordination_hash)
        var protocolParamsMint = applyParams(
                compiledCodeFor("protocol_params_mint.protocol_params_mint.mint", validators),
                utxo1OutputReference,
                BytesPlutusData.of(coordinationSpend.getScriptHash()));
        var paramsPolicy = BytesPlutusData.of(protocolParamsMint.getScriptHash());

        // ---- 3-5. Everything anchored on the params policy
        var plb = applyParams(compiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators), paramsPolicy);
        var plg = applyParams(compiledCodeFor("programmable_logic_global.programmable_logic_global.withdraw", validators), paramsPolicy);
        var unfracking = applyParams(compiledCodeFor("unfracking.unfracking.withdraw", validators), paramsPolicy);

        var plgRewardAddress = AddressProvider.getRewardAddress(plg, NETWORK);
        var unfrackingRewardAddress = AddressProvider.getRewardAddress(unfracking, NETWORK);

        // ---- 6. upgrade_multisig: 1-of-1 on the admin key
        var adminVkh = new Address(ADMIN.baseAddress()).getPaymentCredentialHash().orElseThrow();
        var upgradeMultisig = applyParams(compiledCodeFor("upgrade_multisig.upgrade_multisig.withdraw", validators),
                ListPlutusData.of(BytesPlutusData.of(adminVkh)),
                BigIntPlutusData.of(1));
        var upgradeMultisigRewardAddress = AddressProvider.getRewardAddress(upgradeMultisig, NETWORK);

        // ---- 7. issuance_cbor_hex_mint(utxo2, always_fail_hash)
        var issuanceCborHex = applyParams(
                compiledCodeFor("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint", validators),
                utxo2OutputReference,
                BytesPlutusData.of(alwaysFail.getScriptHash()));

        // ---- 8-9. registry_spend BEFORE registry_mint
        var registrySpend = applyParams(compiledCodeFor("registry_spend.registry_spend.spend", validators), paramsPolicy);
        var registrySpendAddress = AddressProvider.getEntAddress(
                Credential.fromScript(registrySpend.getScriptHash()), NETWORK);

        var registryMint = applyParams(compiledCodeFor("registry_mint.registry_mint.mint", validators),
                utxo1OutputReference,
                BytesPlutusData.of(issuanceCborHex.getScriptHash()),
                scriptCred(registrySpend));

        // ProgrammableLogicGlobalParams — field order is load-bearing.
        var coordinationDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(registryMint.getScriptHash()),
                scriptCred(plb),
                scriptCred(unfracking),
                scriptCred(plg),
                scriptCred(upgradeMultisig));

        var protocolParamNft = Asset.builder()
                .name(HexUtil.encodeHexString("ProtocolParams".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE)
                .build();
        var protocolParamsValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(protocolParamsMint.getPolicyId())
                        .assets(List.of(protocolParamNft))
                        .build()))
                .build();

        var originNodeDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),
                emptyVkey(),
                emptyVkey(),
                emptyVkey(),
                emptyVkey(),
                BytesPlutusData.of(""));

        var registryNft = Asset.builder().name("0x").value(BigInteger.ONE).build();
        var registryValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(registryMint.getPolicyId())
                        .assets(List.of(registryNft))
                        .build()))
                .build();

        // issuance_mint template: split the serialized script body on a unique dummy marker.
        var dummyPolicyId = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";
        var issuanceDummy = applyParams(compiledCodeFor("issuance_mint.issuance_mint.mint", validators),
                scriptCred(plb),
                BytesPlutusData.of(registryMint.getScriptHash()),
                ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(dummyPolicyId))),
                scriptCred(plg));
        var parts = HexUtil.encodeHexString(issuanceDummy.serializeScriptBody()).split(dummyPolicyId);
        if (parts.length != 2 || parts[1].isEmpty()) {
            throw new IllegalStateException("issuance template marker split produced " + parts.length + " parts");
        }

        var issuanceCborHexDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(parts[0])),
                BytesPlutusData.of(HexUtil.decodeHexString(parts[1])));

        var issuanceCborHexNft = Asset.builder()
                .name(HexUtil.encodeHexString("IssuanceCborHex".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE)
                .build();

        var candidate = TransactionOutput.builder()
                .address(alwaysFailAddress.getAddress())
                .value(Value.builder()
                        .coin(MinAdaCalculator.DUMMY_COIN_VAL)
                        .multiAssets(List.of(MultiAsset.builder()
                                .policyId(issuanceCborHex.getPolicyId())
                                .assets(List.of(issuanceCborHexNft))
                                .build()))
                        .build())
                .inlineDatum(issuanceCborHexDatum)
                .build();
        var issuanceCborHexCoin = new MinAdaCalculator(chain.protocolParams())
                .calculateMinAda(candidate)
                .add(BigInteger.valueOf(1_000_000))
                .add(BigInteger.valueOf(999_999))
                .divide(BigInteger.valueOf(1_000_000))
                .multiply(BigInteger.valueOf(1_000_000));

        var issuanceCborHexValue = Value.builder()
                .coin(issuanceCborHexCoin)
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(issuanceCborHex.getPolicyId())
                        .assets(List.of(issuanceCborHexNft))
                        .build()))
                .build();

        var tx = new Tx()
                .collectFrom(walletUtxos)
                .mintAsset(registryMint, registryNft, ConstrPlutusData.of(0))
                .mintAsset(protocolParamsMint, protocolParamNft, ConstrPlutusData.of(1))
                .mintAsset(issuanceCborHex, issuanceCborHexNft, ConstrPlutusData.of(2))
                .payToContract(coordinationAddress.getAddress(), ValueUtil.toAmountList(protocolParamsValue), coordinationDatum)
                .payToContract(registrySpendAddress.getAddress(), ValueUtil.toAmountList(registryValue), originNodeDatum)
                .payToContract(alwaysFailAddress.getAddress(), ValueUtil.toAmountList(issuanceCborHexValue), issuanceCborHexDatum)
                .payToAddress(REF_INPUT.baseAddress(), Amount.ada(5), plb)
                .payToAddress(REF_INPUT.baseAddress(), Amount.ada(20), plg)
                .payToAddress(REF_INPUT.baseAddress(), Amount.ada(12), unfracking)
                .payToAddress(ADMIN.baseAddress(), Amount.ada(50))
                .payToAddress(ADMIN.baseAddress(), Amount.ada(50))
                .withChangeAddress(ADMIN.baseAddress());

        for (var rewardAddress : List.of(plgRewardAddress, unfrackingRewardAddress, upgradeMultisigRewardAddress)) {
            tx.registerStakeAddress(rewardAddress.getAddress());
        }

        chain.withScripts(alwaysFail, coordinationSpend, protocolParamsMint, plb, plg, unfracking,
                upgradeMultisig, issuanceCborHex, registrySpend, registryMint);

        var quickTxBuilder = new QuickTxBuilder(chain.utxoSupplier(), chain.protocolParamsSupplier(),
                chain.transactionProcessor());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(ADMIN))
                .withTxEvaluator(chain.evaluator())
                .feePayer(ADMIN.baseAddress())
                .mergeOutputs(false)
                .buildAndSign();

        var txHash = TransactionUtil.getTxHash(transaction);
        var outputs = chain.submit(transaction);

        int plbRefIdx = refScriptOutputIndex(transaction, plb);
        int plgRefIdx = refScriptOutputIndex(transaction, plg);
        int unfrackingRefIdx = refScriptOutputIndex(transaction, unfracking);

        var coordinationUtxo = outputAt(outputs, coordinationAddress.getAddress());
        var registryOriginUtxo = outputAt(outputs, registrySpendAddress.getAddress());
        var issuanceCborHexUtxo = outputAt(outputs, alwaysFailAddress.getAddress());

        var params = new ProtocolBootstrapParams(
                new org.cardanofoundation.cip113.model.bootstrap.ProtocolParams(
                        new TxInput(utxo1.getTxHash(), utxo1.getOutputIndex()),
                        protocolParamsMint.getPolicyId(),
                        HexUtil.encodeHexString(coordinationSpend.getScriptHash())),
                new CoordinationParams(
                        HexUtil.encodeHexString(coordinationNonce),
                        HexUtil.encodeHexString(coordinationSpend.getScriptHash()),
                        coordinationAddress.getAddress()),
                new ProgrammableLogicGlobalParams(protocolParamsMint.getPolicyId(), plg.getPolicyId()),
                new ProgrammableLogicBaseParams(protocolParamsMint.getPolicyId(), plb.getPolicyId()),
                new UnfrackingParams(protocolParamsMint.getPolicyId(),
                        HexUtil.encodeHexString(unfracking.getScriptHash()),
                        unfrackingRewardAddress.getAddress()),
                new UpgradeMultisigParams(List.of(HexUtil.encodeHexString(adminVkh)), 1,
                        HexUtil.encodeHexString(upgradeMultisig.getScriptHash()),
                        upgradeMultisigRewardAddress.getAddress()),
                new IssuanceParams(new TxInput(utxo2.getTxHash(), utxo2.getOutputIndex()),
                        issuanceCborHex.getPolicyId(),
                        HexUtil.encodeHexString(alwaysFail.getScriptHash())),
                new DirectoryMintParams(new TxInput(utxo1.getTxHash(), utxo1.getOutputIndex()),
                        issuanceCborHex.getPolicyId(), registryMint.getPolicyId()),
                new DirectorySpendParams(protocolParamsMint.getPolicyId(), registrySpend.getPolicyId()),
                new TxInput(txHash, plbRefIdx),
                new TxInput(txHash, plgRefIdx),
                new TxInput(txHash, unfrackingRefIdx),
                txHash);

        log.info("bootstrap complete: txHash={} size={} bytes, paramsPolicy={} registryPolicy={} issuancePolicy={}",
                txHash, transaction.serialize().length, protocolParamsMint.getPolicyId(),
                registryMint.getPolicyId(), issuanceCborHex.getPolicyId());

        return new Bootstrapped(transaction, params, outputs,
                coordinationUtxo, registryOriginUtxo, issuanceCborHexUtxo,
                outputs.get(plbRefIdx), outputs.get(plgRefIdx), outputs.get(unfrackingRefIdx),
                plb, plg, unfracking, registryMint, registrySpend, issuanceCborHex,
                protocolParamsMint, coordinationSpend, alwaysFail);
    }

    private static Utxo outputAt(List<Utxo> outputs, String address) {
        return outputs.stream()
                .filter(u -> address.equals(u.getAddress()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no bootstrap output at " + address));
    }

    public static int refScriptOutputIndex(Transaction tx, PlutusScript script) throws Exception {
        var wanted = HexUtil.encodeHexString(script.getScriptHash());
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            var ref = outputs.get(i).getScriptRef();
            if (ref == null) {
                continue;
            }
            if (wanted.equals(HexUtil.encodeHexString(
                    ReferenceScriptUtil.deserializeScriptRef(ref).getScriptHash()))) {
                return i;
            }
        }
        throw new IllegalStateException("no output carries reference script " + wanted);
    }
}
