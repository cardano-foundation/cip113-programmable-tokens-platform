package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
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
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.cardanofoundation.cip113.PreviewConstants;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.blueprint.Validator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Feasibility spike: can the CIP-113 protocol-bootstrap transaction be built AND phase-2
 * evaluated with zero network access?
 *
 * <p>This is a byte-for-byte port of the transaction assembled by
 * {@code standard/PreviewProtocolDeploymentMintTest#deploy} — same parameterisation chain,
 * same datums, same mints, same outputs, same output ordering — with every backend call
 * replaced by an in-memory supplier:
 *
 * <ul>
 *   <li>{@link InMemoryUtxoSupplier} fabricates the two ~200 ADA admin UTxOs that the
 *       one-shot mints are parameterised on, and resolves them again by (txHash, index)
 *       when {@link AikenTransactionEvaluator} rebuilds the UTxO set for phase-2.</li>
 *   <li>{@link #conwayProtocolParams()} returns realistic Conway params whose PlutusV3 cost
 *       model comes from {@code CostModelUtil.plutusV3Costs} — cardano-client-lib ships the
 *       post-Chang 251-entry V3 model as a compiled-in constant, so no epoch query is
 *       needed to run the evaluator.</li>
 *   <li>{@link NoOpTransactionProcessor} satisfies the QuickTxBuilder constructor and hard
 *       fails if anything ever tries to submit.</li>
 * </ul>
 *
 * <p>Nothing here writes to disk or opens a socket. The tx is never submitted.
 */
@Slf4j
public class OfflineBootstrapEvalSpike {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Devnet-shaped network: testnet address discriminator + magic 42. */
    private static final Network network = new Network(0b0000, 42);

    private static final Account adminAccount =
            Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC);

    /** Same derivation path the deployment test parks reference scripts on. */
    private static final Account refInputAccount =
            Account.createFromMnemonic(network, PreviewConstants.ADMIN_MNEMONIC, 10, 0);

    private static final String NONCE_ISSUANCE_ALWAYS_FAIL =
            "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";

    /** registry_node.sentinel_next_key — 30 bytes of 0xff, deliberately NOT 28. */
    private static final String SENTINEL_NEXT_KEY =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    /**
     * Placeholder ex-units QuickTxBuilder stamps on every redeemer before evaluation
     * (see {@code ScriptMintingIntent}/{@code ScriptCollectFromIntent}). A redeemer still
     * carrying these after buildAndSign() means the evaluator never ran.
     */
    private static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);
    private static final BigInteger PLACEHOLDER_STEPS = BigInteger.valueOf(10_000);

    private String ALWAYS_FAIL_CONTRACT;
    private String COORDINATION_SPEND_CONTRACT;
    private String PROTOCOL_PARAMS_CONTRACT;
    private String PROGRAMMABLE_LOGIC_BASE_CONTRACT;
    private String TRANSFER_CONTRACT;
    private String THIRD_PARTY_CONTRACT;
    private String UNFRACKING_CONTRACT;
    private String UPGRADE_MULTISIG_CONTRACT;
    private String ISSUANCE_CBOR_HEX_CONTRACT;
    private String ISSUANCE_CONTRACT;
    private String REGISTRY_MINT_CONTRACT;
    private String REGISTRY_SPEND_CONTRACT;

    @BeforeEach
    public void loadContracts() throws Exception {
        var plutus = OBJECT_MAPPER.readValue(
                this.getClass().getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        var validators = plutus.validators();
        ALWAYS_FAIL_CONTRACT = compiledCodeFor("always_fail.always_fail.spend", validators);
        COORDINATION_SPEND_CONTRACT = compiledCodeFor("coordination_spend.coordination_spend.spend", validators);
        PROTOCOL_PARAMS_CONTRACT = compiledCodeFor("protocol_params_mint.protocol_params_mint.mint", validators);
        PROGRAMMABLE_LOGIC_BASE_CONTRACT = compiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators);
        TRANSFER_CONTRACT = compiledCodeFor("transfer.transfer.withdraw", validators);
        THIRD_PARTY_CONTRACT = compiledCodeFor("third_party.third_party.withdraw", validators);
        UNFRACKING_CONTRACT = compiledCodeFor("unfracking.unfracking.withdraw", validators);
        UPGRADE_MULTISIG_CONTRACT = compiledCodeFor("upgrade_multisig.upgrade_multisig.withdraw", validators);
        ISSUANCE_CBOR_HEX_CONTRACT = compiledCodeFor("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint", validators);
        ISSUANCE_CONTRACT = compiledCodeFor("issuance_mint.issuance_mint.mint", validators);
        REGISTRY_MINT_CONTRACT = compiledCodeFor("registry_mint.registry_mint.mint", validators);
        REGISTRY_SPEND_CONTRACT = compiledCodeFor("registry_spend.registry_spend.spend", validators);
    }

    @Test
    public void buildAndEvaluateBootstrapTxOffline() throws Exception {

        // ---- In-memory backend: two fabricated ~200 ADA admin UTxOs, nothing else on "chain".
        var utxoSupplier = new InMemoryUtxoSupplier();
        var utxo1 = fabricateAdaUtxo("cip113-offline-spike-bootstrap-utxo-1", 0, 200);
        var utxo2 = fabricateAdaUtxo("cip113-offline-spike-bootstrap-utxo-2", 0, 200);
        utxoSupplier.add(utxo1);
        utxoSupplier.add(utxo2);

        var protocolParamsSupplier = new StaticProtocolParamsSupplier(conwayProtocolParams());
        var protocolParams = protocolParamsSupplier.getProtocolParams();
        Assertions.assertNotNull(protocolParams.getCostModels().get("PlutusV3"),
                "offline protocol params must carry a PlutusV3 cost model or phase-2 cannot run");
        log.info("offline protocol params: PlutusV3 cost model has {} entries",
                protocolParams.getCostModels().get("PlutusV3").size());

        var walletUtxos = List.of(utxo1, utxo2);
        var collectedLovelace = walletUtxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        log.info("{} has {} lovelace across {} fabricated utxos",
                adminAccount.baseAddress(), collectedLovelace, walletUtxos.size());
        Assertions.assertTrue(collectedLovelace.compareTo(Amount.ada(175).getQuantity()) >= 0,
                "need >=175 ADA across the two bootstrap UTxOs; got " + collectedLovelace);

        // Output Reference - utxo1
        var utxo1OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo1.getTxHash())),
                BigIntPlutusData.of(utxo1.getOutputIndex()));

        // Output Reference - utxo2
        var utxo2OutputReference = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo2.getTxHash())),
                BigIntPlutusData.of(utxo2.getOutputIndex()));

        // ---- 0. always_fail: the immutable lock for the IssuanceCborHex UTxO
        var issuanceAlwaysFailScript = applyParams(ALWAYS_FAIL_CONTRACT,
                BytesPlutusData.of(HexUtil.decodeHexString(NONCE_ISSUANCE_ALWAYS_FAIL)));
        var issuanceAlwaysFailAddress = AddressProvider.getEntAddress(issuanceAlwaysFailScript, network);

        // ---- 1. coordination_spend: the home of the protocol-params NFT, nonce-parameterised
        // on the bootstrap UTxO so each deployment gets its own coordination address.
        var coordinationNonce = Blake2bUtil.blake2bHash256(
                HexUtil.decodeHexString(utxo1.getTxHash() + String.format("%02x", utxo1.getOutputIndex())));
        var coordinationSpendScript = applyParams(COORDINATION_SPEND_CONTRACT,
                BytesPlutusData.of(coordinationNonce));
        var coordinationAddress = AddressProvider.getEntAddress(coordinationSpendScript, network);
        log.info("coordinationAddress: {}", coordinationAddress.getAddress());

        // ---- 2. protocol_params_mint(utxo1, coordination_hash)
        var protocolParamsContract = applyParams(PROTOCOL_PARAMS_CONTRACT,
                utxo1OutputReference,
                BytesPlutusData.of(coordinationSpendScript.getScriptHash()));
        var paramsPolicy = BytesPlutusData.of(protocolParamsContract.getScriptHash());
        log.info("protocolParams policy: {}", protocolParamsContract.getPolicyId());

        // ---- 3-5. Everything anchored on the params policy
        var programmableLogicBaseContract = applyParams(PROGRAMMABLE_LOGIC_BASE_CONTRACT, paramsPolicy);
        var transferContract = applyParams(TRANSFER_CONTRACT, paramsPolicy);
        var thirdPartyContract = applyParams(THIRD_PARTY_CONTRACT, paramsPolicy);
        var unfrackingContract = applyParams(UNFRACKING_CONTRACT, paramsPolicy);

        var transferRewardAddress = AddressProvider.getRewardAddress(transferContract, network);
        var thirdPartyRewardAddress = AddressProvider.getRewardAddress(thirdPartyContract, network);
        var unfrackingRewardAddress = AddressProvider.getRewardAddress(unfrackingContract, network);

        // ---- 6. upgrade_multisig: 1-of-1 on the admin key
        var adminVkh = new Address(adminAccount.baseAddress())
                .getPaymentCredentialHash().orElseThrow();
        var upgradeMultisigContract = applyParams(UPGRADE_MULTISIG_CONTRACT,
                ListPlutusData.of(BytesPlutusData.of(adminVkh)),
                BigIntPlutusData.of(1));
        var upgradeMultisigRewardAddress = AddressProvider.getRewardAddress(upgradeMultisigContract, network);

        // ---- 7. issuance_cbor_hex_mint(utxo2, always_fail_hash)
        var issuanceCborHexContract = applyParams(ISSUANCE_CBOR_HEX_CONTRACT,
                utxo2OutputReference,
                BytesPlutusData.of(issuanceAlwaysFailScript.getScriptHash()));

        // ---- 8-9. registry_spend BEFORE registry_mint
        var registrySpendContract = applyParams(REGISTRY_SPEND_CONTRACT, paramsPolicy);
        var registrySpendAddress = AddressProvider.getEntAddress(
                Credential.fromScript(registrySpendContract.getScriptHash()), network);
        log.info("registrySpendAddress: {}", registrySpendAddress.getAddress());

        var registryMintContract = applyParams(REGISTRY_MINT_CONTRACT,
                utxo1OutputReference,
                BytesPlutusData.of(issuanceCborHexContract.getScriptHash()),
                scriptCred(registrySpendContract));
        log.info("registryMint policy: {}", registryMintContract.getPolicyId());

        // Field order is load-bearing and owned by CoreProtocolParamsDatum, so it is not
        // spelled out here: this spike and the runtime decoder cannot disagree about it.
        var coordinationDatum = new CoreProtocolParamsDatum(
                HexUtil.encodeHexString(registryMintContract.getScriptHash()),
                Credential.fromScript(programmableLogicBaseContract.getScriptHash()),
                Credential.fromScript(transferContract.getScriptHash()),
                Credential.fromScript(thirdPartyContract.getScriptHash()),
                Credential.fromScript(unfrackingContract.getScriptHash()),
                Credential.fromScript(upgradeMultisigContract.getScriptHash()),
                CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES).toPlutusData();

        var protocolParamNft = Asset.builder()
                .name(HexUtil.encodeHexString("ProtocolParams".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE)
                .build();

        Value protocolParamsValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(protocolParamsContract.getPolicyId())
                        .assets(List.of(protocolParamNft))
                        .build()))
                .build();

        // RegistryNode — registry_mint's RegistryInit compares by whole-record equality.
        var originNodeDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),                                          // 0: key
                BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),   // 1: next
                emptyVkey(),                                                      // 2: minting_logic_script
                emptyVkey(),                                                      // 3: transfer_logic_script
                emptyVkey(),                                                      // 4: third_party_transfer_logic_script
                emptyVkey(),                                                      // 5: unfracking_logic_script
                BytesPlutusData.of(""));                                          // 6: global_state_cs

        var registryNft = Asset.builder()
                .name("0x")                                   // origin_node_tn = #""
                .value(BigInteger.ONE)
                .build();

        Value registryValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(registryMintContract.getPolicyId())
                        .assets(List.of(registryNft))
                        .build()))
                .build();

        // issuance_mint template: split the serialized script body on a unique dummy marker.
        var dummyPolicyId = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";
        var issuanceDummyContract = applyParams(ISSUANCE_CONTRACT,
                scriptCred(programmableLogicBaseContract),                 // programmable_logic_base
                BytesPlutusData.of(registryMintContract.getScriptHash()),  // registry_node_cs
                ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(dummyPolicyId))), // minting_logic_cred
                // params_policy: a BARE PolicyId, where this used to be plg_stake_cred,
                // a Credential. Same position, different encoding.
                BytesPlutusData.of(protocolParamsContract.getScriptHash()));

        var encodedIssuanceDummyContract = HexUtil.encodeHexString(issuanceDummyContract.serializeScriptBody());
        var contractParts = encodedIssuanceDummyContract.split(dummyPolicyId);
        Assertions.assertEquals(2, contractParts.length,
                "dummy policy marker must appear exactly once in the issuance template");
        Assertions.assertFalse(contractParts[1].isEmpty(),
                "postfix must be non-empty: plg_stake_cred is applied after minting_logic_cred");

        var issuanceCborHexDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(contractParts[0])),   // prefix_cbor_hex
                BytesPlutusData.of(HexUtil.decodeHexString(contractParts[1])));  // postfix_cbor_hex

        var issuanceCborHexNft = Asset.builder()
                .name(HexUtil.encodeHexString("IssuanceCborHex".getBytes(StandardCharsets.UTF_8), true))
                .value(BigInteger.ONE)
                .build();

        int issuanceTemplateBytes = (contractParts[0].length() + contractParts[1].length()) / 2;
        log.info("issuance template embedded in datum: {} bytes total (prefix {} + postfix {})",
                issuanceTemplateBytes, contractParts[0].length() / 2, contractParts[1].length() / 2);

        // Ledger-exact min-utxo for the datum-heavy IssuanceCborHex output.
        var candidateIssuanceCborHexOutput = TransactionOutput.builder()
                .address(issuanceAlwaysFailAddress.getAddress())
                .value(Value.builder()
                        .coin(MinAdaCalculator.DUMMY_COIN_VAL)
                        .multiAssets(List.of(MultiAsset.builder()
                                .policyId(issuanceCborHexContract.getPolicyId())
                                .assets(List.of(issuanceCborHexNft))
                                .build()))
                        .build())
                .inlineDatum(issuanceCborHexDatum)
                .build();
        var minIssuanceCborHexAda = new MinAdaCalculator(protocolParams)
                .calculateMinAda(candidateIssuanceCborHexOutput);

        var issuanceCborHexCoin = minIssuanceCborHexAda.add(BigInteger.valueOf(1_000_000));
        issuanceCborHexCoin = issuanceCborHexCoin
                .add(BigInteger.valueOf(999_999))
                .divide(BigInteger.valueOf(1_000_000))
                .multiply(BigInteger.valueOf(1_000_000));
        log.info("issuanceCborHex output: ledger-exact min-utxo = {} lovelace, using {} lovelace ({} ADA) after margin",
                minIssuanceCborHexAda, issuanceCborHexCoin, issuanceCborHexCoin.divide(BigInteger.valueOf(1_000_000)));

        Value issuanceCborHexValue = Value.builder()
                .coin(issuanceCborHexCoin)
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(issuanceCborHexContract.getPolicyId())
                        .assets(List.of(issuanceCborHexNft))
                        .build()))
                .build();

        // ---- Assemble the bootstrap transaction.
        var tx = new Tx()
                .collectFrom(walletUtxos)
                .mintAsset(registryMintContract, registryNft, ConstrPlutusData.of(0))          // RegistryInit
                .mintAsset(protocolParamsContract, protocolParamNft, ConstrPlutusData.of(1))   // redeemer unused
                .mintAsset(issuanceCborHexContract, issuanceCborHexNft, ConstrPlutusData.of(2))// redeemer unused
                .payToContract(coordinationAddress.getAddress(), ValueUtil.toAmountList(protocolParamsValue), coordinationDatum)
                .payToContract(registrySpendAddress.getAddress(), ValueUtil.toAmountList(registryValue), originNodeDatum)
                .payToContract(issuanceAlwaysFailAddress.getAddress(), ValueUtil.toAmountList(issuanceCborHexValue), issuanceCborHexDatum)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(5), programmableLogicBaseContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(20), transferContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(20), thirdPartyContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(12), unfrackingContract)
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .withChangeAddress(adminAccount.baseAddress());

        // Offline: nothing is registered on the fabricated "chain", so register all four --
        // the three delegates plus the upgrade authority.
        for (var rewardAddress : List.of(transferRewardAddress,
                thirdPartyRewardAddress,
                unfrackingRewardAddress,
                upgradeMultisigRewardAddress)) {
            tx.registerStakeAddress(rewardAddress.getAddress());
        }

        // Every script the evaluator could need, keyed by hash — this deployment publishes its
        // reference scripts in the same tx, so nothing is actually resolved through here, but
        // the supplier is wired in as the spike requires.
        var scriptSupplier = new InMemoryScriptSupplier(List.of(
                issuanceAlwaysFailScript, coordinationSpendScript, protocolParamsContract,
                programmableLogicBaseContract, transferContract, thirdPartyContract, unfrackingContract,
                upgradeMultisigContract, issuanceCborHexContract, registrySpendContract,
                registryMintContract));

        var quickTxBuilder = new QuickTxBuilder(utxoSupplier, protocolParamsSupplier,
                new NoOpTransactionProcessor());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withTxEvaluator(new AikenTransactionEvaluator(
                        utxoSupplier, protocolParamsSupplier, scriptSupplier))
                .feePayer(adminAccount.baseAddress())
                .mergeOutputs(false)
                .buildAndSign();

        Assertions.assertNotNull(transaction, "buildAndSign() returned null");
        Assertions.assertNotNull(transaction.getWitnessSet(), "built tx has no witness set");

        var redeemers = transaction.getWitnessSet().getRedeemers();
        Assertions.assertNotNull(redeemers, "built tx has no redeemers");
        Assertions.assertFalse(redeemers.isEmpty(), "built tx has no redeemers");

        var maxTxExMem = new BigInteger(protocolParams.getMaxTxExMem());
        var maxTxExSteps = new BigInteger(protocolParams.getMaxTxExSteps());

        int evaluated = 0;
        for (var redeemer : redeemers) {
            var exUnits = redeemer.getExUnits();
            log.info("redeemer tag={} index={} exUnits: mem={} steps={}",
                    redeemer.getTag(), redeemer.getIndex(), exUnits.getMem(), exUnits.getSteps());

            boolean stillPlaceholder = PLACEHOLDER_MEM.equals(exUnits.getMem())
                    && PLACEHOLDER_STEPS.equals(exUnits.getSteps());
            boolean underCeiling = exUnits.getMem().compareTo(maxTxExMem) < 0
                    && exUnits.getSteps().compareTo(maxTxExSteps) < 0;
            boolean nonTrivial = exUnits.getMem().compareTo(BigInteger.ZERO) > 0
                    && exUnits.getSteps().compareTo(BigInteger.ZERO) > 0;

            if (!stillPlaceholder && underCeiling && nonTrivial) {
                evaluated++;
            }
        }

        var serializedTx = transaction.serialize();
        log.info("fee={} lovelace, txSize={} bytes, txHash={}",
                transaction.getBody().getFee(), serializedTx.length,
                TransactionUtil.getTxHash(transaction));
        log.info("transaction.serialize().length = {}", serializedTx.length);

        Assertions.assertTrue(evaluated > 0,
                "no redeemer carries real ex-units — scripts did not actually run offline");
        Assertions.assertEquals(redeemers.size(), evaluated,
                "every redeemer should carry evaluated, sub-ceiling ex-units");
        Assertions.assertTrue(serializedTx.length < 16384,
                "transaction exceeds 16384-byte max-tx-size: " + serializedTx.length + " bytes");
    }

    // ------------------------------------------------------------------ helpers

    private static String compiledCodeFor(String contractTitle, List<Validator> validators) {
        return validators.stream()
                .filter(v -> v.title().equals(contractTitle))
                .findAny()
                .orElseThrow(() -> new IllegalStateException("no validator titled " + contractTitle))
                .compiledCode();
    }

    private static PlutusScript applyParams(String compiledCode, PlutusData... params) {
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(ListPlutusData.of(params), compiledCode),
                PlutusVersion.v3);
    }

    private static ConstrPlutusData scriptCred(PlutusScript script) throws Exception {
        return ConstrPlutusData.of(1, BytesPlutusData.of(script.getScriptHash()));
    }

    /** registry_node.empty_vkey = VerificationKey(#"") — Credential index 0. */
    private static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(""));
    }

    /**
     * A deterministic, well-formed 32-byte tx hash derived from a label, so the fabricated
     * bootstrap UTxOs are stable across runs (the whole parameterisation chain hangs off them).
     */
    private static Utxo fabricateAdaUtxo(String label, int outputIndex, long ada) {
        var txHash = HexUtil.encodeHexString(
                Blake2bUtil.blake2bHash256(label.getBytes(StandardCharsets.UTF_8)));
        return Utxo.builder()
                .txHash(txHash)
                .outputIndex(outputIndex)
                .address(adminAccount.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)))))
                .build();
    }

    /**
     * Realistic Conway-era protocol params. The only field that phase-2 evaluation truly
     * cannot be faked around is {@code costModels.PlutusV3}: it is fed straight into the
     * aiken/uplc machine as the operating cost table. cardano-client-lib compiles in the
     * post-Chang 251-entry V3 model as {@code CostModelUtil.plutusV3Costs}, which is what
     * makes fully-offline evaluation possible at all.
     */
    private static ProtocolParams conwayProtocolParams() {
        var costModels = new LinkedHashMap<String, LinkedHashMap<String, Long>>();
        costModels.put("PlutusV1", indexedCostModel(costModelOf(CostModelUtil.PlutusV1CostModel)));
        costModels.put("PlutusV2", indexedCostModel(costModelOf(CostModelUtil.PlutusV2CostModel)));
        costModels.put("PlutusV3", indexedCostModel(CostModelUtil.plutusV3Costs));

        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .maxBlockSize(90112)
                .maxTxSize(16384)
                .maxBlockHeaderSize(1100)
                .keyDeposit("2000000")
                .poolDeposit("500000000")
                .eMax(18)
                .nOpt(500)
                .a0(new BigDecimal("0.3"))
                .rho(new BigDecimal("0.003"))
                .tau(new BigDecimal("0.2"))
                .decentralisationParam(BigDecimal.ZERO)
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .minUtxo("4310")
                .minPoolCost("170000000")
                .costModels(costModels)
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .maxBlockExMem("62000000")
                .maxBlockExSteps("20000000000")
                .maxValSize("5000")
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .coinsPerUtxoSize("4310")
                .govActionDeposit(BigInteger.valueOf(100_000_000_000L))
                .drepDeposit(BigInteger.valueOf(500_000_000L))
                .drepActivity(20)
                .committeeMinSize(0)
                .committeeMaxTermLength(365)
                .govActionLifetime(6)
                .minFeeRefScriptCostPerByte(new BigDecimal("15"))
                .build();
    }

    private static long[] costModelOf(com.bloxbean.cardano.client.plutus.spec.CostModel costModel) {
        return costModel.getCosts();
    }

    /**
     * ProtocolParams stores each cost model as an ordered name→value map; CostModelUtil
     * re-derives the array by sorting on integer keys when they look numeric, so plain
     * indices reproduce the on-chain ordering exactly.
     */
    private static LinkedHashMap<String, Long> indexedCostModel(long[] costs) {
        var map = new LinkedHashMap<String, Long>();
        for (int i = 0; i < costs.length; i++) {
            map.put(String.valueOf(i), costs[i]);
        }
        return map;
    }

    // ------------------------------------------------------------------ in-memory suppliers

    /** UTxO set held entirely in memory; no socket is ever opened. */
    static class InMemoryUtxoSupplier implements UtxoSupplier {
        private final Map<String, List<Utxo>> byAddress = new LinkedHashMap<>();
        private final Map<String, Utxo> byOutRef = new HashMap<>();

        void add(Utxo utxo) {
            byAddress.computeIfAbsent(utxo.getAddress(), k -> new ArrayList<>()).add(utxo);
            byOutRef.put(utxo.getTxHash() + "#" + utxo.getOutputIndex(), utxo);
        }

        @Override
        public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
            var all = byAddress.getOrDefault(address, List.of());
            int size = nrOfItems == null ? UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH : nrOfItems;
            int pageIdx = page == null ? 0 : page;   // UtxoSupplier pages are 0-based
            int from = pageIdx * size;
            if (from >= all.size()) {
                return List.of();
            }
            return List.copyOf(all.subList(from, Math.min(from + size, all.size())));
        }

        @Override
        public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
            return Optional.ofNullable(byOutRef.get(txHash + "#" + outputIndex));
        }

        @Override
        public boolean isUsedAddress(Address address) {
            return byAddress.containsKey(address.toBech32());
        }

        @Override
        public void setSearchByAddressVkh(boolean flag) {
            // Not supported offline: the fabricated set is keyed by full bech32 address.
        }
    }

    record StaticProtocolParamsSupplier(ProtocolParams params) implements ProtocolParamsSupplier {
        @Override
        public ProtocolParams getProtocolParams() {
            return params;
        }
    }

    /** Resolves reference scripts by hash from a fixed set; no backend lookup. */
    static class InMemoryScriptSupplier implements ScriptSupplier {
        private final Map<String, PlutusScript> byHash = new HashMap<>();

        InMemoryScriptSupplier(List<PlutusScript> scripts) {
            for (var script : scripts) {
                try {
                    byHash.put(HexUtil.encodeHexString(script.getScriptHash()), script);
                } catch (Exception e) {
                    throw new IllegalStateException("cannot hash script", e);
                }
            }
        }

        @Override
        public Optional<PlutusScript> getScript(String scriptHash) {
            return Optional.ofNullable(byHash.get(scriptHash));
        }
    }

    /**
     * Satisfies the QuickTxBuilder constructor without ever touching the network. Evaluation
     * is routed through {@code withTxEvaluator(...)} instead, and submission is a hard failure
     * so an accidental {@code complete()} cannot silently pretend to have submitted.
     */
    static class NoOpTransactionProcessor implements TransactionProcessor {
        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
            throw new UnsupportedOperationException(
                    "offline spike: evaluation must go through withTxEvaluator(AikenTransactionEvaluator)");
        }

        @Override
        public Result<String> submitTransaction(byte[] cborBytes) {
            throw new UnsupportedOperationException("offline spike: submission is disabled");
        }
    }
}
