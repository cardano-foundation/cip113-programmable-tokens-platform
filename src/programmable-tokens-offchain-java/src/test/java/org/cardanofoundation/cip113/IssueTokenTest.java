package org.cardanofoundation.cip113;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.DirectorySetNode;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

@Slf4j
public class IssueTokenTest extends AbstractPreviewTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String SUBSTANDARD_ISSUE_CONTRACT = "585701010029800aba2aba1aab9eaab9dab9a4888896600264646644b30013370e900218031baa00289919b87375a6012008906400980418039baa0028a504014600c600e002600c004600c00260066ea801a29344d9590011";

    private static final String SUBSTANDARD_TRANSFER_CONTRACT = "585701010029800aba2aba1aab9eaab9dab9a4888896600264646644b30013370e900218031baa00289919b87375a6012008904801980418039baa0028a504014600c600e002600c004600c00260066ea801a29344d9590011";

    private String ISSUANCE_MINT, DIRECTORY_MINT_CONTRACT, DIRECTORY_SPEND_CONTRACT;

    private ProtocolBootstrapParams protocolBootstrapParams;

    @BeforeEach
    public void loadContracts() throws Exception {
        protocolBootstrapParams = OBJECT_MAPPER.readValue(this.getClass().getClassLoader().getResourceAsStream("protocol-bootstraps-preview.json"), ProtocolBootstrapParams[].class)[0];
        var plutus = OBJECT_MAPPER.readValue(this.getClass().getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        var validators = plutus.validators();
        ISSUANCE_MINT = getCompiledCodeFor("issuance_mint.issuance_mint.mint", validators);
        DIRECTORY_MINT_CONTRACT = getCompiledCodeFor("registry_mint.registry_mint.mint", validators);
        DIRECTORY_SPEND_CONTRACT = getCompiledCodeFor("registry_spend.registry_spend.spend", validators);
    }

    @Test
    public void test() throws Exception {

        var dryRun = false;

        var bootstrapTxHash = protocolBootstrapParams.txHash();

        // Protocol Params 2592ff5b2810679c30996c309080a3635071f923b43edb494a87597c1e6a5be5:0
        // Directory 2592ff5b2810679c30996c309080a3635071f923b43edb494a87597c1e6a5be5:1
        // Issuance 2592ff5b2810679c30996c309080a3635071f923b43edb494a87597c1e6a5be5:2


        var protocolParamsUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 0);
        if (!protocolParamsUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch protocol params utxo");
        }
        var protocolParamsUtxo = protocolParamsUtxoOpt.getValue();
        log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

        var issuanceUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 2);
        if (!issuanceUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch issuance utxo");
        }
        var issuanceUtxo = issuanceUtxoOpt.getValue();
        log.info("issuanceUtxo: {}", issuanceUtxo);

        var issuanceDatum = issuanceUtxo.getInlineDatum();
        var issuanceData = PlutusData.deserialize(HexUtil.decodeHexString(issuanceDatum));
        var issuance = OBJECT_MAPPER.writeValueAsString(issuanceData);
        log.info("issuance: {}", issuance);

        var programmableLogicBaseScriptHash = protocolBootstrapParams.programmableLogicBaseParams().scriptHash();

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        if (!utxosOpt.isSuccessful() || utxosOpt.getValue().isEmpty()) {
            Assertions.fail("no utxos available");
        }
        var walletUtxos = utxosOpt.getValue();

        var directoryUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 1);
        if (!directoryUtxoOpt.isSuccessful()) {
            Assertions.fail("no directory utxo found");
        }
        var directoryUtxo = directoryUtxoOpt.getValue();
        log.info("directoryUtxo: {}", directoryUtxo);
        var directorySetNode = DirectorySetNode.fromInlineDatum(directoryUtxo.getInlineDatum());
        if (directorySetNode.isEmpty()) {
            log.info("could not deserialise directorySetNode for utxo: {}", directoryUtxo);
            Assertions.fail();
        }
        log.info("directorySetNode: {}", directorySetNode);

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_ISSUE_CONTRACT, PlutusVersion.v3);
        log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
        log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

//        var registerAddressTx = new Tx()
//                .from(adminAccount.baseAddress())
//                .registerStakeAddress(substandardIssueAddress.getAddress())
//                .withChangeAddress(adminAccount.baseAddress());
//
//        quickTxBuilder.compose(registerAddressTx)
//                .feePayer(adminAccount.baseAddress())
//                .withSigner(SignerProviders.signerFrom(adminAccount))
//                .completeAndWait();

        var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_TRANSFER_CONTRACT, PlutusVersion.v3);

        // Issuance Parameterization
        // v0.4.0 issuance_mint takes FOUR parameters — programmable_logic_base (Credential),
        // registry_node_cs (PolicyId), minting_logic_cred (Credential), plg_stake_cred
        // (Credential). Applying only the first three yields a different (wrong) policy id.
        // Mirrors ProtocolScriptBuilderService.getParameterizedIssuanceMintScript.
        var issuanceParameters = ListPlutusData.of(
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(programmableLogicBaseScriptHash))
                ),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().scriptHash())),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(substandardIssueContract.getScriptHash())
                ),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(
                                protocolBootstrapParams.programmableLogicGlobalPrams().scriptHash()))
                )
        );
        var issuanceContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(issuanceParameters, ISSUANCE_MINT), PlutusVersion.v3);
        log.info("issuanceContract: {}", issuanceContract.getPolicyId());

        // Registry node output is at index 2: [0] PLB token, [1] covering node, [2] new registry node
        // issuance_mint's redeemer IS types.MintingRegistryProof in v0.4.0 (no
        // SmartTokenMintingAction wrapper): OutputIndex { index } = Constr 1 [Int].
        var issuanceRedeemer = ConstrPlutusData.of(1, BigIntPlutusData.of(2));

        // Directory MINT parameterization
        log.info("protocolBootstrapParams.directoryMintParams(): {}", protocolBootstrapParams.directoryMintParams());
        var directoryMintParameters = ListPlutusData.of(
                ConstrPlutusData.of(0,
                        BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().txInput().txHash())),
                        BigIntPlutusData.of(protocolBootstrapParams.directoryMintParams().txInput().outputIndex())),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().issuanceScriptHash()))
        );
        var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryMintParameters, DIRECTORY_MINT_CONTRACT), PlutusVersion.v3);
        log.info("directoryMintContract: {}", directoryMintContract.getPolicyId());

        // Directory SPEND parameterization
        var directorySpendParameters = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
        );
        var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, DIRECTORY_SPEND_CONTRACT), PlutusVersion.v3);
        log.info("directorySpendContract, policy: {}", directorySpendContract.getPolicyId());
        log.info("directorySpendContract, script hash: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));
        var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network);
        log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());


        // Directory MINT - NFT, address, datum and value
        // types.RegistryInsert { key, minting_logic_script: Credential } — v0.4.0: the 2nd
        // field is a Credential, not a bare hash.
        var directoryMintRedeemer = ConstrPlutusData.of(1,
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash()))
        );

        var directoryMintNft = Asset.builder()
                .name("0x" + issuanceContract.getPolicyId())
//                .name("0x01" + HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                .value(BigInteger.ONE)
                .build();

        var directorySpendNft = Asset.builder()
                .name("0x")
                .value(BigInteger.ONE)
                .build();

        // v0.4.0 RegistryNode is SEVEN fields (lib/registry_node.ak):
        //   0 key  1 next  2 minting_logic  3 transfer_logic
        //   4 third_party_transfer_logic  5 unfracking_logic  6 global_state_cs
        // Credentials are tagged: VerificationKey = Constr 0 [bytes], Script = Constr 1 [bytes].
        //
        // Covering (origin) node re-emitted with `next` pointing at the inserted key. All
        // four credential slots stay empty_vkey = VerificationKey(#"") — registry_mint's
        // is_updated_directory_node compares the whole record, so every other field must
        // round-trip byte-for-byte AND tag-for-tag.
        var directorySpendDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        // Inserted node. is_inserted_directory_node requires 28-byte credentials in slots
        // 2, 3 AND 4 (empty is only legal on the origin node), slot 2 to equal the
        // RegistryInsert redeemer's minting_logic_script, and slot 5 to be either
        // empty_vkey (unfracking forbidden — what we use) or a 28-byte credential.
        var directoryMintDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                BytesPlutusData.of(HexUtil.decodeHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardTransferContract.getScriptHash())),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        Value directoryMintValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintContract.getPolicyId())
                                .assets(List.of(directoryMintNft))
                                .build()
                ))
                .build();

        Value directorySpendValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintContract.getPolicyId())
                                .assets(List.of(directorySpendNft))
                                .build()
                ))
                .build();

        // Programmable Token Mint
        var pintToken = Asset.builder()
                .name(HexUtil.encodeHexString("PINT".getBytes(), true))
                .value(BigInteger.valueOf(1_000_000_000L))
                .build();

        Value pintTokenValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(issuanceContract.getPolicyId())
                                .assets(List.of(pintToken))
                                .build()
                ))
                .build();

        var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                aliceAccount.getBaseAddress().getDelegationCredential().get(),
                network);

        var tx = new ScriptTx()
                .collectFrom(walletUtxos)
                .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                // Redeemer is DirectoryInit (constr(0))
                .mintAsset(issuanceContract, pintToken, issuanceRedeemer)
                .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(pintTokenValue), ConstrPlutusData.of(0))
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum)
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum)
                .payToAddress(adminAccount.baseAddress(), Amount.ada(5))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(5))
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
                .withChangeAddress(adminAccount.baseAddress());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .feePayer(adminAccount.baseAddress())
                .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                .preBalanceTx((txBuilderContext, transaction1) -> {
                    var outputs = transaction1.getBody().getOutputs();
                    if (outputs.getFirst().getAddress().equals(adminAccount.baseAddress())) {
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
            var result = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
            if (result.isSuccessful()) {
                log.info("submitted: {}", result.getValue());
            } else {
                log.warn("error: {}", result.getResponse());
            }
        }

    }


    @Test
    public void mintOnly() throws Exception {

        var dryRun = true;

        var bootstrapTxHash = protocolBootstrapParams.txHash();

        var protocolParamsUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 0);
        if (!protocolParamsUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch protocol params utxo");
        }
        var protocolParamsUtxo = protocolParamsUtxoOpt.getValue();
        log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

        var issuanceUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 2);
        if (!issuanceUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch issuance utxo");
        }
        var issuanceUtxo = issuanceUtxoOpt.getValue();
        log.info("issuanceUtxo: {}", issuanceUtxo);

        var issuanceDatum = issuanceUtxo.getInlineDatum();
        var issuanceData = PlutusData.deserialize(HexUtil.decodeHexString(issuanceDatum));
        var issuance = OBJECT_MAPPER.writeValueAsString(issuanceData);
        log.info("issuance: {}", issuance);

        var programmableLogicBaseScriptHash = protocolBootstrapParams.programmableLogicBaseParams().scriptHash();

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        if (!utxosOpt.isSuccessful() || utxosOpt.getValue().isEmpty()) {
            Assertions.fail("no utxos available");
        }
        var walletUtxos = utxosOpt.getValue();

        var directoryUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 1);
        if (!directoryUtxoOpt.isSuccessful()) {
            Assertions.fail("no directory utxo found");
        }
        var directoryUtxo = directoryUtxoOpt.getValue();
        log.info("directoryUtxo: {}", directoryUtxo);
        var directorySetNode = DirectorySetNode.fromInlineDatum(directoryUtxo.getInlineDatum());
        if (directorySetNode.isEmpty()) {
            log.info("could not deserialise directorySetNode for utxo: {}", directoryUtxo);
            Assertions.fail();
        }
        log.info("directorySetNode: {}", directorySetNode);

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_ISSUE_CONTRACT, PlutusVersion.v3);
        log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
        log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());


        // Issuance Parameterization
        // v0.4.0 issuance_mint takes FOUR parameters — programmable_logic_base (Credential),
        // registry_node_cs (PolicyId), minting_logic_cred (Credential), plg_stake_cred
        // (Credential). Applying only the first three yields a different (wrong) policy id.
        // Mirrors ProtocolScriptBuilderService.getParameterizedIssuanceMintScript.
        var issuanceParameters = ListPlutusData.of(
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(programmableLogicBaseScriptHash))
                ),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().scriptHash())),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(substandardIssueContract.getScriptHash())
                ),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(
                                protocolBootstrapParams.programmableLogicGlobalPrams().scriptHash()))
                )
        );
        var issuanceContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(issuanceParameters, ISSUANCE_MINT), PlutusVersion.v3);
        log.info("issuanceContract: {}", issuanceContract.getPolicyId());

        // Directory MINT parameterization
        log.info("protocolBootstrapParams.directoryMintParams(): {}", protocolBootstrapParams.directoryMintParams());
        var directoryMintParameters = ListPlutusData.of(
                ConstrPlutusData.of(0,
                        BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().txInput().txHash())),
                        BigIntPlutusData.of(protocolBootstrapParams.directoryMintParams().txInput().outputIndex())),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().issuanceScriptHash()))
        );
        var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryMintParameters, DIRECTORY_MINT_CONTRACT), PlutusVersion.v3);
        log.info("directoryMintContract: {}", directoryMintContract.getPolicyId());

        // Directory SPEND parameterization
        var directorySpendParameters = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
        );
        var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, DIRECTORY_SPEND_CONTRACT), PlutusVersion.v3);
        log.info("directorySpendContract, policy: {}", directorySpendContract.getPolicyId());
        log.info("directorySpendContract, script hash: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));
        var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network);
        log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());

        // This tx mints against an ALREADY-REGISTERED policy — it emits no directory
        // outputs at all, so OutputIndex { index } (Constr 1) has nothing to point at.
        // The right proof is RefInput { index } = Constr 0 [Int], resolved on-chain via
        // `list.at(self.reference_inputs, index)`, so the registry node has to be a real
        // reference input on the tx. Modelled on dummy/PreviewRegisterTest.mint().
        var registryUtxosOpt = bfBackendService.getUtxoService()
                .getUtxos(directorySpendContractAddress.getAddress(), 100, 1);
        if (!registryUtxosOpt.isSuccessful()) {
            Assertions.fail("could not fetch registry utxos");
        }
        var registryNodeParser = new RegistryNodeParser(OBJECT_MAPPER);
        final var progTokenPolicyId = issuanceContract.getPolicyId();
        var progTokenRegistry = registryUtxosOpt.getValue().stream()
                .filter(utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                        .map(node -> node.key().equals(progTokenPolicyId))
                        .orElse(false))
                .findAny()
                .orElseThrow(() -> new AssertionError("no registry node for "
                        + progTokenPolicyId + " — run test()/registerToken() first"));
        var registryRefInput = TransactionInput.builder()
                .transactionId(progTokenRegistry.getTxHash())
                .index(progTokenRegistry.getOutputIndex())
                .build();
        // Sole reference input ⇒ index 0.
        var issuanceRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(0));


        // Programmable Token Mint
        var pintToken = Asset.builder()
                .name(HexUtil.encodeHexString("PINT".getBytes(), true))
                .value(BigInteger.valueOf(1_000_000_000L))
                .build();

        Value pintTokenValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(issuanceContract.getPolicyId())
                                .assets(List.of(pintToken))
                                .build()
                ))
                .build();

        var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                aliceAccount.getBaseAddress().getDelegationCredential().get(),
                network);

        var tx = new ScriptTx()
                .collectFrom(walletUtxos)
                .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                .mintAsset(issuanceContract, pintToken, issuanceRedeemer)
                .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(pintTokenValue), ConstrPlutusData.of(0))
                .readFrom(registryRefInput)
                .attachRewardValidator(substandardIssueContract)
                .withChangeAddress(adminAccount.baseAddress());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .feePayer(adminAccount.baseAddress())
                .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                .preBalanceTx((txBuilderContext, transaction1) -> {
                    var outputs = transaction1.getBody().getOutputs();
                    if (outputs.getFirst().getAddress().equals(adminAccount.baseAddress())) {
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
            var result = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
            if (result.isSuccessful()) {
                log.info("submitted: {}", result.getValue());
            } else {
                log.warn("error: {}", result.getResponse());
            }
        }

    }


    @Test
    public void registerIssuanceScript() throws Exception {

        var latestIssueContract = "5857010100323232323225333002323232323253330073370e900218041baa0011323370e6eb400d20c801300a300937540022940c024c02800cc020008c01c008c01c004c010dd50008a4c26cacae6955ceaab9e5742ae881";

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(latestIssueContract, PlutusVersion.v3);
        log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
        log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

        var registerAddressTx = new Tx()
                .from(adminAccount.baseAddress())
                .registerStakeAddress(substandardIssueAddress.getAddress())
                .withChangeAddress(adminAccount.baseAddress());

        quickTxBuilder.compose(registerAddressTx)
                .feePayer(adminAccount.baseAddress())
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .completeAndWait();


    }


    @Test
    public void registerToken() throws Exception {

        var dryRun = true;

        var bootstrapTxHash = protocolBootstrapParams.txHash();

        var protocolParamsUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 0);
        if (!protocolParamsUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch protocol params utxo");
        }
        var protocolParamsUtxo = protocolParamsUtxoOpt.getValue();
        log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

        var issuanceUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 2);
        if (!issuanceUtxoOpt.isSuccessful()) {
            Assertions.fail("could not fetch issuance utxo");
        }
        var issuanceUtxo = issuanceUtxoOpt.getValue();
        log.info("issuanceUtxo: {}", issuanceUtxo);

        var issuanceDatum = issuanceUtxo.getInlineDatum();
        var issuanceData = PlutusData.deserialize(HexUtil.decodeHexString(issuanceDatum));
        var issuance = OBJECT_MAPPER.writeValueAsString(issuanceData);
        log.info("issuance: {}", issuance);

        var programmableLogicBaseScriptHash = protocolBootstrapParams.programmableLogicBaseParams().scriptHash();

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        if (!utxosOpt.isSuccessful() || utxosOpt.getValue().isEmpty()) {
            Assertions.fail("no utxos available");
        }
        var walletUtxos = utxosOpt.getValue();

        var directoryUtxoOpt = bfBackendService.getUtxoService().getTxOutput(bootstrapTxHash, 1);
        if (!directoryUtxoOpt.isSuccessful()) {
            Assertions.fail("no directory utxo found");
        }
        var directoryUtxo = directoryUtxoOpt.getValue();
        log.info("directoryUtxo: {}", directoryUtxo);
        var directorySetNode = DirectorySetNode.fromInlineDatum(directoryUtxo.getInlineDatum());
        if (directorySetNode.isEmpty()) {
            log.info("could not deserialise directorySetNode for utxo: {}", directoryUtxo);
            Assertions.fail();
        }
        log.info("directorySetNode: {}", directorySetNode);

        var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_ISSUE_CONTRACT, PlutusVersion.v3);
        log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

        var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network);
        log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

//        var registerAddressTx = new Tx()
//                .from(adminAccount.baseAddress())
//                .registerStakeAddress(substandardIssueAddress.getAddress())
//                .withChangeAddress(adminAccount.baseAddress());
//
//        quickTxBuilder.compose(registerAddressTx)
//                .feePayer(adminAccount.baseAddress())
//                .withSigner(SignerProviders.signerFrom(adminAccount))
//                .completeAndWait();

        var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(SUBSTANDARD_TRANSFER_CONTRACT, PlutusVersion.v3);

        // Issuance Parameterization
        // v0.4.0 issuance_mint takes FOUR parameters — programmable_logic_base (Credential),
        // registry_node_cs (PolicyId), minting_logic_cred (Credential), plg_stake_cred
        // (Credential). Applying only the first three yields a different (wrong) policy id.
        // Mirrors ProtocolScriptBuilderService.getParameterizedIssuanceMintScript.
        var issuanceParameters = ListPlutusData.of(
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(programmableLogicBaseScriptHash))
                ),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().scriptHash())),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(substandardIssueContract.getScriptHash())
                ),
                ConstrPlutusData.of(1,
                        BytesPlutusData.of(HexUtil.decodeHexString(
                                protocolBootstrapParams.programmableLogicGlobalPrams().scriptHash()))
                )
        );
        var issuanceContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(issuanceParameters, ISSUANCE_MINT), PlutusVersion.v3);
        log.info("issuanceContract: {}", issuanceContract.getPolicyId());

        // Registry node output is at index 2: [0] PLB token, [1] covering node, [2] new registry node
        // issuance_mint's redeemer IS types.MintingRegistryProof in v0.4.0 (no
        // SmartTokenMintingAction wrapper): OutputIndex { index } = Constr 1 [Int].
        var issuanceRedeemer = ConstrPlutusData.of(1, BigIntPlutusData.of(2));

        // Directory MINT parameterization
        log.info("protocolBootstrapParams.directoryMintParams(): {}", protocolBootstrapParams.directoryMintParams());
        var directoryMintParameters = ListPlutusData.of(
                ConstrPlutusData.of(0,
                        BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().txInput().txHash())),
                        BigIntPlutusData.of(protocolBootstrapParams.directoryMintParams().txInput().outputIndex())),
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.directoryMintParams().issuanceScriptHash()))
        );
        var directoryMintContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directoryMintParameters, DIRECTORY_MINT_CONTRACT), PlutusVersion.v3);
        log.info("directoryMintContract: {}", directoryMintContract.getPolicyId());

        // Directory SPEND parameterization
        var directorySpendParameters = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(protocolBootstrapParams.protocolParams().scriptHash()))
        );
        var directorySpendContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(AikenScriptUtil.applyParamToScript(directorySpendParameters, DIRECTORY_SPEND_CONTRACT), PlutusVersion.v3);
        log.info("directorySpendContract, policy: {}", directorySpendContract.getPolicyId());
        log.info("directorySpendContract, script hash: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));
        var directorySpendContractAddress = AddressProvider.getEntAddress(Credential.fromScript(directorySpendContract.getScriptHash()), network);
        log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());


        // Directory MINT - NFT, address, datum and value
        // types.RegistryInsert { key, minting_logic_script: Credential } — v0.4.0: the 2nd
        // field is a Credential, not a bare hash.
        var directoryMintRedeemer = ConstrPlutusData.of(1,
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash()))
        );

        var directoryMintNft = Asset.builder()
                .name("0x" + issuanceContract.getPolicyId())
//                .name("0x01" + HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                .value(BigInteger.ONE)
                .build();

        var directorySpendNft = Asset.builder()
                .name("0x")
                .value(BigInteger.ONE)
                .build();

        // v0.4.0 RegistryNode is SEVEN fields (lib/registry_node.ak):
        //   0 key  1 next  2 minting_logic  3 transfer_logic
        //   4 third_party_transfer_logic  5 unfracking_logic  6 global_state_cs
        // Credentials are tagged: VerificationKey = Constr 0 [bytes], Script = Constr 1 [bytes].
        //
        // Covering (origin) node re-emitted with `next` pointing at the inserted key. All
        // four credential slots stay empty_vkey = VerificationKey(#"") — registry_mint's
        // is_updated_directory_node compares the whole record, so every other field must
        // round-trip byte-for-byte AND tag-for-tag.
        var directorySpendDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        // Inserted node. is_inserted_directory_node requires 28-byte credentials in slots
        // 2, 3 AND 4 (empty is only legal on the origin node), slot 2 to equal the
        // RegistryInsert redeemer's minting_logic_script, and slot 5 to be either
        // empty_vkey (unfracking forbidden — what we use) or a 28-byte credential.
        var directoryMintDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(issuanceContract.getScriptHash()),
                BytesPlutusData.of(HexUtil.decodeHexString("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardTransferContract.getScriptHash())),
                ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        Value directoryMintValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintContract.getPolicyId())
                                .assets(List.of(directoryMintNft))
                                .build()
                ))
                .build();

        Value directorySpendValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(directoryMintContract.getPolicyId())
                                .assets(List.of(directorySpendNft))
                                .build()
                ))
                .build();

        // Programmable Token Mint
        var pintToken = Asset.builder()
                .name(HexUtil.encodeHexString("PINT".getBytes(), true))
                .value(BigInteger.valueOf(1_000_000_000L))
                .build();

        Value pintTokenValue = Value.builder()
                .coin(Amount.ada(1).getQuantity())
                .multiAssets(List.of(
                        MultiAsset.builder()
                                .policyId(issuanceContract.getPolicyId())
                                .assets(List.of(pintToken))
                                .build()
                ))
                .build();

        var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                aliceAccount.getBaseAddress().getDelegationCredential().get(),
                network);

        var tx = new ScriptTx()
                .collectFrom(walletUtxos)
                .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum)
                // Directory Params
                .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum)
                .readFrom(TransactionInput.builder()
                                .transactionId(protocolParamsUtxo.getTxHash())
                                .index(protocolParamsUtxo.getOutputIndex())
                                .build(),
                        TransactionInput.builder()
                                .transactionId(issuanceUtxo.getTxHash())
                                .index(issuanceUtxo.getOutputIndex())
                                .build())
                .attachSpendingValidator(directorySpendContract)
                .withChangeAddress(adminAccount.baseAddress());

        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .feePayer(adminAccount.baseAddress())
                .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                .preBalanceTx((txBuilderContext, transaction1) -> {
                    var outputs = transaction1.getBody().getOutputs();
                    if (outputs.getFirst().getAddress().equals(adminAccount.baseAddress())) {
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
            var result = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
            if (result.isSuccessful()) {
                log.info("submitted: {}", result.getValue());
            } else {
                log.warn("error: {}", result.getResponse());
            }
        }

    }


}
