package org.cardanofoundation.cip113.substandards.dummy;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
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
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.easy1staking.cardano.model.AssetType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.AbstractPreviewTest;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.LinkedListNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.service.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static java.math.BigInteger.ONE;

/**
 * End-to-end CIP-113 registration + subsequent mint for the {@code dummy} substandard.
 *
 * <p>Network-agnostic: with no environment set it targets preview (as before); with
 * {@code CARDANO_BACKEND_URL} / {@code CARDANO_NETWORK_MAGIC} set (see
 * {@link AbstractPreviewTest}) it targets the local Yaci devnet. The bootstrap file and the
 * protocol tx hash follow from {@code CIP113_NETWORK} / {@code CIP113_PROTOCOL_TXHASH}.
 *
 * <p>Wire shapes exercised here are the v0.4.0 ones:
 * <ul>
 *   <li>{@code registry_mint} redeemer {@code RegistryInsert { key, minting_logic_script:
 *       Credential }} — the 2nd field is a {@code Credential}, not a bare hash.</li>
 *   <li>{@code issuance_mint} redeemer IS {@code MintingRegistryProof} — {@code RefInput(i)}
 *       = Constr 0 [Int], {@code OutputIndex(i)} = Constr 1 [Int]. The old
 *       {@code SmartTokenMintingAction} wrapper is gone.</li>
 *   <li>7-field {@code RegistryNode} with real {@code Credential} values.</li>
 * </ul>
 */
@Slf4j
public class PreviewRegisterTest extends AbstractPreviewTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Devnet override: unset means preview, so the original preview run is unchanged. */
    private static final String BOOTSTRAP_NETWORK =
            System.getenv().getOrDefault("CIP113_NETWORK",
                    System.getenv("CARDANO_NETWORK_MAGIC") == null ? "preview" : "devnet");

    private static final String DEFAULT_PROTOCOL =
            System.getenv().getOrDefault("CIP113_PROTOCOL_TXHASH",
                    "114adc8ee212b5ded1f895ab53c7741e5521feff735d05aeef2a92dcf05c9ae2");

    private static final String SUBSTANDARD_NAME = "dummy";

    private static final String TOKEN_NAME = System.getenv().getOrDefault("CIP113_TOKEN_NAME", "dummy-1");

    private final ProtocolBootstrapService protocolBootstrapService =
            new ProtocolBootstrapService(OBJECT_MAPPER, new AppConfig.Network(BOOTSTRAP_NETWORK));

    private final ProtocolScriptBuilderService protocolScriptBuilderService = new ProtocolScriptBuilderService(protocolBootstrapService);

    private final UtxoProvider utxoProvider = new UtxoProvider(bfBackendService, null);

    private final AccountService accountService = new AccountService(utxoProvider);

    private final LinkedListService linkedListService = new LinkedListService(utxoProvider);

    private SubstandardService substandardService;

    private final RegistryNodeParser registryNodeParser = new RegistryNodeParser(OBJECT_MAPPER);

    @BeforeEach
    public void init() {
        substandardService = new SubstandardService(OBJECT_MAPPER);
        substandardService.init();

        protocolBootstrapService.init();
    }

    @Test
    public void test() throws Exception {

        var tokenAmount = 10_000_000L;

        var dryRun = false;

        var issuerAccount = adminAccount;

        var adminUtxos = accountService.findAdaOnlyUtxo(issuerAccount.baseAddress(), 10_000_000L);

        var protocolBootstrapParamsOpt = protocolBootstrapService.getProtocolBootstrapParamsByTxHash(DEFAULT_PROTOCOL);
        var protocolBootstrapParams = protocolBootstrapParamsOpt.orElseThrow(() -> new AssertionError(
                "no bootstrap entry for txHash " + DEFAULT_PROTOCOL + " in protocol-bootstraps-" + BOOTSTRAP_NETWORK + ".json"));
        log.info("protocolBootstrapParams: {}", protocolBootstrapParams);

        var bootstrapTxHash = protocolBootstrapParams.txHash();

        var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolBootstrapParams);

        var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
        if (protocolParamsUtxoOpt.isEmpty()) {
            Assertions.fail("could not resolve protocol params");
        }

        var protocolParamsUtxo = protocolParamsUtxoOpt.get();

        var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network);
        log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());

        var issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
        if (issuanceUtxoOpt.isEmpty()) {
            Assertions.fail("could not resolve issuance params");
        }
        var issuanceUtxo = issuanceUtxoOpt.get();
        log.info("issuanceUtxo: {}", issuanceUtxo);

        /// Getting Substandard Contracts and parameterize
        // Issuer to be used for minting/burning
        var issuerContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_NAME, "transfer.issue.withdraw");
        var issuerContract = issuerContractOpt.get();

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(issuerContract.scriptBytes(), PlutusVersion.v3);

        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
        log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

        var transferContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_NAME, "transfer.transfer.withdraw");
        var transferContract = transferContractOpt.get();

        var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(transferContract.scriptBytes(), PlutusVersion.v3);
        var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network);
        log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());

        var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolBootstrapParams, substandardIssueContract);
        final var progTokenPolicyId = issuanceContract.getPolicyId();
        log.info("progTokenPolicyId: {}", progTokenPolicyId);

        var registryAddress = AddressProvider.getEntAddress(directorySpendContract, network);

        var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());
        log.info("found {}, registry entries", registryEntries.size());

        var nodeAlreadyPresent = linkedListService.nodeAlreadyPresent(progTokenPolicyId, registryEntries, utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                .map(RegistryNode::key));

        if (nodeAlreadyPresent) {
            Assertions.fail("registry node already present");
        }

        var nodeToReplaceOpt = linkedListService.findNodeToReplace(progTokenPolicyId, registryEntries, utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                .map(node -> new LinkedListNode(node.key(), node.next())));

        if (nodeToReplaceOpt.isEmpty()) {
            Assertions.fail("could not find node to replace");
        }

        var directoryUtxo = nodeToReplaceOpt.get();
        log.info("directoryUtxo: {}", directoryUtxo);
        var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());

        if (existingRegistryNodeDatumOpt.isEmpty()) {
            Assertions.fail("could not parse current registry node");
        }

        var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

        // Directory MINT - NFT, address, datum and value
        var directoryMintContract = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolBootstrapParams);
        var directoryMintPolicyId = directoryMintContract.getPolicyId();

        // types.RegistryInsert { key: ByteArray, minting_logic_script: Credential }.
        // v0.4.0: the 2nd field is a Credential, not a bare hash — Script(hash) is Constr 1 [bytes].
        var directoryMintRedeemer = ConstrPlutusData.of(1,
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash()))
        );

        var directoryMintNft = Asset.builder()
                .name("0x" + issuanceContract.getPolicyId())
                .value(ONE)
                .build();

        Optional<Amount> registrySpentNftOpt = directoryUtxo.getAmount()
                .stream()
                .filter(amount -> amount.getQuantity().equals(ONE) && directoryMintPolicyId.equals(AssetType.fromUnit(amount.getUnit()).policyId()))
                .findAny();

        if (registrySpentNftOpt.isEmpty()) {
            Assertions.fail("could not find amount for directory mint");
        }

        var registrySpentNft = AssetType.fromUnit(registrySpentNftOpt.get().getUnit());

        var directorySpendNft = Asset.builder()
                .name("0x" + registrySpentNft.assetName())
                .value(ONE)
                .build();

        var directorySpendDatum = existingRegistryNodeDatum.toBuilder()
                .next(HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                .build();
        log.info("directorySpendDatum: {}", directorySpendDatum);

        // third_party_transfer_logic_script (index 4): `dummy` declares no third-party /
        // force-transfer validator (src/substandards/dummy/validators/transfer.ak has only
        // `issue` and `transfer`), and the slot cannot be empty
        // (linked_list.is_28_byte_credential). Pinned to the protocol's always_fail script, so
        // programmable_logic_global.validate_3rd_party can never find a satisfiable withdrawal:
        // seizure is structurally impossible rather than delegated to an invented authority.
        // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN (least permission).
        var directoryMintDatum = new RegistryNode(HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                existingRegistryNodeDatum.next(),
                Credential.fromScript(substandardIssueContract.getScriptHash()),
                Credential.fromScript(substandardTransferContract.getScriptHash()),
                Credential.fromScript(protocolBootstrapParams.issuanceParams().alwaysFailScriptHash()),
                RegistryNode.EMPTY_VKEY,
                "");
        log.info("directoryMintDatum: {}", directoryMintDatum);

        Value directoryMintValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintPolicyId)
                                .assets(List.of(directoryMintNft))
                                .build()
                ))
                .build();
        log.info("directoryMintValue: {}", directoryMintValue);

        Value directorySpendValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintPolicyId)
                                .assets(List.of(directorySpendNft))
                                .build()
                ))
                .build();
        log.info("directorySpendValue: {}", directorySpendValue);

        // issuance_mint's redeemer IS types.MintingRegistryProof in v0.4.0 — the old
        // SmartTokenMintingAction wrapper is gone. Registry node output is at index 2:
        // [0] PLB output, [1] updated covering node, [2] new registry node.
        var issuanceRedeemer = ConstrPlutusData.of(1, BigIntPlutusData.of(2)); // OutputIndex { index: 2 }

        // Programmable Token Mint
        var programmableToken = Asset.builder()
                .name("0x" + HexUtil.encodeHexString(TOKEN_NAME.getBytes()))
                .value(BigInteger.valueOf(tokenAmount))
                .build();

        Value programmableTokenValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(issuanceContract.getPolicyId())
                                .assets(List.of(programmableToken))
                                .build()
                ))
                .build();

        var payee = issuerAccount.getBaseAddress().getAddress();
        log.info("payee: {}", payee);

        var payeeAddress = new Address(payee);

        var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                payeeAddress.getDelegationCredential().get(),
                network);

        // The substandard's withdraw-0 reward accounts must exist before registry_mint's
        // "substandard authorises this registration" check can be satisfied. Idempotent:
        // registering an already-registered stake address makes the node reject the tx, so
        // only register the ones the chain does not know yet.
        registerStakeAddressIfNeeded(issuerAccount, substandardIssueAddress.getAddress(), substandardTransferAddress.getAddress());

        var tx = new ScriptTx()
                .collectFrom(adminUtxos)
                .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                // dummy `issue` withdraw-0: redeemer must equal 100
                .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                // Mint Token
                .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                // Redeemer is RegistryInsert (constr(1))
                .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum.toPlutusData())
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum.toPlutusData())
                .readFrom(TransactionInput.builder()
                                .transactionId(protocolParamsUtxo.getTxHash())
                                .index(protocolParamsUtxo.getOutputIndex())
                                .build(),
                        TransactionInput.builder()
                                .transactionId(issuanceUtxo.getTxHash())
                                .index(issuanceUtxo.getOutputIndex())
                                .build())
                .attachSpendingValidator(directorySpendContract)
                .attachRewardValidator(substandardIssueContract)
                .withChangeAddress(issuerAccount.baseAddress());

        var transaction = quickTxBuilder.compose(tx)
                .withRequiredSigners(issuerAccount.getBaseAddress())
                .withSigner(SignerProviders.signerFrom(issuerAccount))
                .feePayer(issuerAccount.baseAddress())
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                .preBalanceTx((txBuilderContext, transaction1) -> {
                    var outputs = transaction1.getBody().getOutputs();
                    if (outputs.getFirst().getAddress().equals(issuerAccount.baseAddress())) {
                        log.info("found dummy input, moving it...");
                        var first = outputs.removeFirst();
                        outputs.addLast(first);
                    }
                    try {
                        log.info("pre tx: {}", OBJECT_MAPPER.writeValueAsString(transaction1));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .postBalanceTx((txBuilderContext, transaction1) -> {
                    try {
                        log.info("post tx: {}", OBJECT_MAPPER.writeValueAsString(transaction1));
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }
                })
                .buildAndSign();

        log.info("tx: {}", transaction.serializeToHex());
        log.info("tx: {}", OBJECT_MAPPER.writeValueAsString(transaction));

        if (!dryRun) {
            var txHash = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
            log.info("submit result: {}", txHash);
            Assertions.assertTrue(txHash.isSuccessful(), "registration submission failed: " + txHash.getResponse());
            log.info("REGISTERED policy {} in tx {}", progTokenPolicyId, txHash.getValue());
        }

    }

    /**
     * Subsequent mint against an already-registered policy — the {@code RefInput} branch of
     * {@code MintingRegistryProof}. Run after {@link #test()} has been confirmed on chain.
     */
    @Test
    public void mint() throws Exception {

        var tokenAmount = 1_000_000L;
        var issuerAccount = adminAccount;

        var protocolBootstrapParams = protocolBootstrapService.getProtocolBootstrapParamsByTxHash(DEFAULT_PROTOCOL)
                .orElseThrow(() -> new AssertionError("no bootstrap entry for txHash " + DEFAULT_PROTOCOL));

        var adminUtxos = accountService.findAdaOnlyUtxo(issuerAccount.baseAddress(), 10_000_000L);

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                substandardService.getSubstandardValidator(SUBSTANDARD_NAME, "transfer.issue.withdraw").orElseThrow().scriptBytes(),
                PlutusVersion.v3);
        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);

        var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolBootstrapParams, substandardIssueContract);
        final var progTokenPolicyId = issuanceContract.getPolicyId();

        var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolBootstrapParams);
        var registryAddress = AddressProvider.getEntAddress(directorySpendContract, network);
        var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

        var progTokenRegistry = registryEntries.stream()
                .filter(utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                        .map(node -> node.key().equals(progTokenPolicyId))
                        .orElse(false))
                .findAny()
                .orElseThrow(() -> new AssertionError("no registry node for " + progTokenPolicyId + " — run test() first"));

        var registryRefInput = TransactionInput.builder()
                .transactionId(progTokenRegistry.getTxHash())
                .index(progTokenRegistry.getOutputIndex())
                .build();

        // Sole reference input ⇒ index 0. types.MintingRegistryProof RefInput { index }.
        var issuanceRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(0));

        var programmableToken = Asset.builder()
                .name("0x" + HexUtil.encodeHexString(TOKEN_NAME.getBytes()))
                .value(BigInteger.valueOf(tokenAmount))
                .build();

        Value programmableTokenValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(issuanceContract.getPolicyId())
                                .assets(List.of(programmableToken))
                                .build()
                ))
                .build();

        var payeeAddress = new Address(issuerAccount.baseAddress());
        var targetAddress = AddressProvider.getBaseAddress(
                Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                payeeAddress.getDelegationCredential().get(),
                network);

        var tx = new ScriptTx()
                .collectFrom(adminUtxos)
                .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                .readFrom(registryRefInput)
                .attachRewardValidator(substandardIssueContract)
                .withChangeAddress(issuerAccount.baseAddress());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(issuerAccount))
                .feePayer(issuerAccount.baseAddress())
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .mergeOutputs(false)
                .buildAndSign();

        var result = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
        log.info("mint submit result: {}", result);
        Assertions.assertTrue(result.isSuccessful(), "mint submission failed: " + result.getResponse());
        log.info("MINTED {} of {} in tx {}", tokenAmount, progTokenPolicyId, result.getValue());
    }

    /** Registers the given script reward accounts, skipping any the chain already knows. */
    private void registerStakeAddressIfNeeded(com.bloxbean.cardano.client.account.Account payer,
                                              String... rewardAddresses) throws Exception {
        var toRegister = new java.util.ArrayList<String>();
        for (var rewardAddress : rewardAddresses) {
            var accountInfo = bfBackendService.getAccountService().getAccountInformation(rewardAddress);
            var registered = accountInfo.isSuccessful()
                    && accountInfo.getValue() != null
                    && Boolean.TRUE.equals(accountInfo.getValue().getActive());
            log.info("reward account {} registered={}", rewardAddress, registered);
            if (!registered) {
                toRegister.add(rewardAddress);
            }
        }
        if (toRegister.isEmpty()) {
            return;
        }
        var tx = new Tx().from(payer.baseAddress()).withChangeAddress(payer.baseAddress());
        toRegister.forEach(tx::registerStakeAddress);
        var result = quickTxBuilder.compose(tx)
                .feePayer(payer.baseAddress())
                .withSigner(SignerProviders.signerFrom(payer))
                .completeAndWait();
        log.info("stake registration result: {}", result);
        // yaci-store's Blockfrost-compatible /accounts endpoint reports script reward accounts
        // as inactive even when the ledger has them registered, so the probe above is only a
        // hint. StakeKeyRegisteredDELEG from the node is the authoritative "already there".
        if (!result.isSuccessful() && String.valueOf(result.getResponse()).contains("StakeKeyRegisteredDELEG")) {
            log.info("reward accounts already registered on chain — continuing");
            return;
        }
        Assertions.assertTrue(result.isSuccessful(), "stake registration failed: " + result.getResponse());
    }

}
