package org.cardanofoundation.cip113.standard;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
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
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
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

    /** registry_node.empty_vkey = VerificationKey(#"") — Credential index 0. */
    private static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(""));
    }

    /** registry_node.sentinel_next_key — 30 bytes of 0xff, deliberately NOT 28. */
    private static final String SENTINEL_NEXT_KEY = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

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

        var dryRun = false;

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        Assertions.assertTrue(utxosOpt.isSuccessful(), "utxo query failed: " + utxosOpt.getResponse());
        Assertions.assertTrue(utxosOpt.getValue().size() >= 2,
                "need >=2 utxos at the admin address — run DevnetFundingTest first");

        var walletUtxos = utxosOpt.getValue().stream().limit(2).toList();
        var collectedLovelace = walletUtxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        // Explicit output total is ~158 ADA (5 coordination + 5 registry + ~11 issuanceCborHex,
        // dynamically sized below from the ledger-exact min-utxo + a small buffer + 5 PLB ref +
        // 20 PLG ref + 12 unfracking ref + 50 + 50 re-fragmentation), plus 6 ADA across three
        // stake-registration deposits, plus ~1 ADA fee ≈ 165 ADA. Assert well above that: the
        // issuanceCborHex figure is computed later from the actual template size, so this
        // hardcoded precondition needs headroom against it drifting upward.
        Assertions.assertTrue(collectedLovelace.compareTo(Amount.ada(175).getQuantity()) >= 0,
                "need >=175 ADA across the two bootstrap UTxOs (run DevnetFundingTest); got " + collectedLovelace);

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

        // ProgrammableLogicGlobalParams (validators/programmable_logic/params.ak:12-37).
        // Field order is load-bearing: PLB reads field 3 and unfracking reads fields 0-1
        // by index via builtins, without deserialising the record.
        var coordinationDatum = ConstrPlutusData.of(0,
                // 0: registry_node_cs — the registry NFT policy
                BytesPlutusData.of(registryMintContract.getScriptHash()),
                // 1: prog_logic_cred — payment credential of EVERY programmable token UTxO (frozen)
                scriptCred(programmableLogicBaseContract),
                // 2: unfracking_cred — read only by PLG's UnfrackingAct arm
                scriptCred(unfrackingContract),
                // 3: prog_logic_global_cred — read by PLB on every spend; this is what makes PLG swappable
                scriptCred(programmableLogicGlobalContract),
                // 4: upgrade_logic_cred — trampoline-2 authority, read only by coordination_spend
                scriptCred(upgradeMultisigContract));

        var protocolParamNft = Asset.builder()
                .name(HexUtil.encodeHexString("ProtocolParams".getBytes(), true))
                .value(BigInteger.ONE)
                .build();

        Value protocolParamsValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(protocolParamsContract.getPolicyId())
                        .assets(List.of(protocolParamNft))
                        .build()))
                .build();

        // RegistryNode (lib/registry_node.ak:51-...). registry_mint's RegistryInit compares
        // the parsed node against `origin_node` by whole-record equality, so every field must
        // be present and canonical. v0.4.0 added `unfracking_logic_script` at index 5.
        var originNodeDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),                                              // 0: key = origin_node_key
                BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),       // 1: next = sentinel
                emptyVkey(),                                                          // 2: minting_logic_script
                emptyVkey(),                                                          // 3: transfer_logic_script
                emptyVkey(),                                                          // 4: third_party_transfer_logic_script
                emptyVkey(),                                                          // 5: unfracking_logic_script (NEW)
                BytesPlutusData.of(""));                                              // 6: global_state_cs

        var registryNft = Asset.builder()
                .name("0x")                                       // origin_node_tn = #""
                .value(BigInteger.ONE)
                .build();

        Value registryValue = Value.builder()
                .coin(Amount.ada(5).getQuantity())
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(registryMintContract.getPolicyId())
                        .assets(List.of(registryNft))
                        .build()))
                .build();

        // issuance_mint gained a 4th param (plg_stake_cred) AFTER minting_logic_cred, so the
        // dummy marker is no longer last: the postfix is now non-empty and carries the PLG
        // credential bytes. The split still works because the marker is unique.
        var dummyPolicyId = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";
        var issuanceDummyContract = applyParams(ISSUANCE_CONTRACT,
                scriptCred(programmableLogicBaseContract),                 // programmable_logic_base
                BytesPlutusData.of(registryMintContract.getScriptHash()),  // registry_node_cs
                ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(dummyPolicyId))), // minting_logic_cred
                scriptCred(programmableLogicGlobalContract));              // plg_stake_cred (NEW)

        var encodedIssuanceDummyContract = HexUtil.encodeHexString(issuanceDummyContract.serializeScriptBody());
        var contractParts = encodedIssuanceDummyContract.split(dummyPolicyId);
        // Guard: registry_mint derives every token's policy id as
        // blake2b_224(prefix || minting_logic_hash || postfix) (lib/utils.ak,
        // is_programmable_token_id_valid). A marker that appears 0 or 2+ times silently
        // produces a template that can never validate any registration.
        Assertions.assertEquals(2, contractParts.length,
                "dummy policy marker must appear exactly once in the issuance template");
        Assertions.assertFalse(contractParts[1].isEmpty(),
                "postfix must be non-empty: plg_stake_cred is applied after minting_logic_cred");

        var issuanceCborHexDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(contractParts[0])),   // prefix_cbor_hex
                BytesPlutusData.of(HexUtil.decodeHexString(contractParts[1])));  // postfix_cbor_hex

        var issuanceCborHexNft = Asset.builder()
                .name(HexUtil.encodeHexString("IssuanceCborHex".getBytes(), true))
                .value(BigInteger.ONE)
                .build();

        // Size the IssuanceCborHex output precisely instead of reusing the flat 5 ADA used for
        // the other two datum outputs above: this inline datum embeds the ~1.7 KB serialized
        // issuance_mint script body split across the prefix/postfix byte fields, and min-UTxO is
        // (160 + serializedOutputSizeInBytes) * coinsPerUtxoByte lovelace — the inline datum
        // counts toward that size, so 5 ADA is very likely insufficient here.
        int issuanceTemplateBytes = (contractParts[0].length() + contractParts[1].length()) / 2;
        log.info("issuance template embedded in datum: {} bytes total (prefix {} + postfix {})",
                issuanceTemplateBytes, contractParts[0].length() / 2, contractParts[1].length() / 2);

        var protocolParamsResult = bfBackendService.getEpochService().getProtocolParameters();
        Assertions.assertTrue(protocolParamsResult.isSuccessful(),
                "protocol params query failed: " + protocolParamsResult.getResponse());

        // MinAdaCalculator implements the exact ledger formula (Babbage-changes.pdf p.9):
        // minAda = coinsPerUtxoByte * (serSize(txOut) + 160). Build a candidate output with the
        // real address/asset-shape/datum so serSize is exact; the coin figure used only for this
        // sizing pass doesn't matter as long as it CBOR-encodes at the same width as the real
        // one — both are well under 2^32 so both take the 4-byte uint form — which is exactly
        // what MinAdaCalculator.DUMMY_COIN_VAL is for.
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
        var minIssuanceCborHexAda = new MinAdaCalculator(protocolParamsResult.getValue())
                .calculateMinAda(candidateIssuanceCborHexOutput);

        // Small fixed buffer over the ledger-exact minimum, then round up to a whole ADA. The
        // calculation above is already exact (real protocol params, real address/asset-shape/
        // datum), so it doesn't need a large proportional margin the way a guessed figure would —
        // it only needs to absorb the few-byte CBOR-width slack between DUMMY_COIN_VAL and this
        // output's real coin (finding: min-UTxO is a creation-time check that a permanently
        // locked always_fail UTxO can never need to satisfy again, so any headroom beyond that is
        // pure waste, not safety margin).
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

        // ---- Assemble the bootstrap transaction: mint the three protocol NFTs, place them at
        // the addresses their validators demand, register the three withdraw-0 reward accounts,
        // and publish the three reference scripts needed by follow-up tests.
        var tx = new Tx()
                // both one-shot UTxOs must be consumed: utxo1 by protocol_params_mint AND
                // registry_mint, utxo2 by issuance_cbor_hex_mint
                .collectFrom(walletUtxos)
                // RegistryInit
                .mintAsset(registryMintContract, registryNft, ConstrPlutusData.of(0))
                // redeemer unused
                .mintAsset(protocolParamsContract, protocolParamNft, ConstrPlutusData.of(1))
                // redeemer unused
                .mintAsset(issuanceCborHexContract, issuanceCborHexNft, ConstrPlutusData.of(2))
                // coordination UTxO: enterprise address, ADA + NFT only, inline datum, NO ref script
                .payToContract(coordinationAddress.getAddress(), ValueUtil.toAmountList(protocolParamsValue), coordinationDatum)
                // origin registry node, locked under registry_spend
                .payToContract(registrySpendAddress.getAddress(), ValueUtil.toAmountList(registryValue), originNodeDatum)
                // issuance template, permanently locked under always_fail
                .payToContract(issuanceAlwaysFailAddress.getAddress(), ValueUtil.toAmountList(issuanceCborHexValue), issuanceCborHexDatum)
                // all three withdraw-0 validators need a registered reward account
                .registerStakeAddress(programmableLogicGlobalRewardAddress.getAddress())
                .registerStakeAddress(unfrackingRewardAddress.getAddress())
                .registerStakeAddress(upgradeMultisigRewardAddress.getAddress())
                // reference scripts (amounts cover min-UTxO for the script sizes)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(5), programmableLogicBaseContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(20), programmableLogicGlobalContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(12), unfrackingContract)
                // re-fragment the admin wallet for follow-up tests
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .withChangeAddress(adminAccount.baseAddress());

        // buildAndSign() executes AikenTransactionEvaluator locally (registry_mint,
        // protocol_params_mint, issuance_cbor_hex_mint all run against the datums above)
        // regardless of dryRun; submission itself is gated separately below so the built
        // Transaction object is always available for the ref-index/size checks that follow.
        var transaction = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(adminAccount))
                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                .feePayer(adminAccount.baseAddress())
                .mergeOutputs(false)
                .buildAndSign();

        // Resolve reference-script output indices dynamically — hardcoding them (e.g. 3 and 4)
        // breaks as soon as an output is added or reordered upstream.
        var plbRefIdx = findRefScriptOutputIndex(transaction, programmableLogicBaseContract);
        var plgRefIdx = findRefScriptOutputIndex(transaction, programmableLogicGlobalContract);
        var unfrackingRefIdx = findRefScriptOutputIndex(transaction, unfrackingContract);
        log.info("ref script indices — plb: {}, plg: {}, unfracking: {}", plbRefIdx, plgRefIdx, unfrackingRefIdx);

        // This devnet's max-tx-size is 16384 bytes; assert it explicitly rather than relying on
        // buildAndSign() having silently accepted it — a failure here should read as "the tx grew
        // too large" and not surface later as an opaque submit-time error.
        var serializedTx = transaction.serialize();
        Assertions.assertTrue(serializedTx.length < 16384,
                "transaction exceeds devnet max-tx-size (16384 bytes): " + serializedTx.length + " bytes");
        log.info("dryRun={}, fee={} lovelace, txSize={} bytes", dryRun, transaction.getBody().getFee(), serializedTx.length);
        log.info("serialized tx: {}", HexUtil.encodeHexString(serializedTx));

        // Computed locally from the built+signed tx body — deterministic and valid whether or
        // not this dry run is ever submitted, since the ref inputs below point at outputs of
        // *this* transaction and must resolve to its real hash, not a placeholder.
        var txHash = TransactionUtil.getTxHash(transaction);

        if (!dryRun) {
            var result = bfBackendService.getTransactionService().submitTransaction(transaction.serialize());
            if (result.isSuccessful()) {
                log.info("submitted: {}", result.getValue());
                Assertions.assertEquals(txHash, result.getValue(),
                        "locally computed tx hash must match the submitted tx hash");
            } else {
                log.warn("error: {}", result.getResponse());
                Assertions.fail("submission failed: " + result.getResponse());
            }
        }

        var protocolParams = new ProtocolParams(
                new TxInput(utxo1.getTxHash(), utxo1.getOutputIndex()),
                protocolParamsContract.getPolicyId(),
                HexUtil.encodeHexString(coordinationSpendScript.getScriptHash()));
        var coordinationParams = new CoordinationParams(
                NONCE_COORDINATION,
                HexUtil.encodeHexString(coordinationSpendScript.getScriptHash()),
                coordinationAddress.getAddress());
        var programmableLogicGlobalParams = new ProgrammableLogicGlobalParams(
                protocolParamsContract.getPolicyId(), programmableLogicGlobalContract.getPolicyId());
        var programmableLogicBaseParams = new ProgrammableLogicBaseParams(
                protocolParamsContract.getPolicyId(), programmableLogicBaseContract.getPolicyId());
        var unfrackingParams = new UnfrackingParams(
                protocolParamsContract.getPolicyId(),
                HexUtil.encodeHexString(unfrackingContract.getScriptHash()),
                unfrackingRewardAddress.getAddress());
        var upgradeMultisigParams = new UpgradeMultisigParams(
                List.of(HexUtil.encodeHexString(adminVkh)),
                1,
                HexUtil.encodeHexString(upgradeMultisigContract.getScriptHash()),
                upgradeMultisigRewardAddress.getAddress());
        var issuanceParams = new IssuanceParams(
                new TxInput(utxo2.getTxHash(), utxo2.getOutputIndex()),
                issuanceCborHexContract.getPolicyId(),
                HexUtil.encodeHexString(issuanceAlwaysFailScript.getScriptHash()));
        var directoryParams = new DirectoryMintParams(
                new TxInput(utxo1.getTxHash(), utxo1.getOutputIndex()),
                issuanceCborHexContract.getPolicyId(),
                registryMintContract.getPolicyId());
        var directorySpendParams = new DirectorySpendParams(
                protocolParamsContract.getPolicyId(), registrySpendContract.getPolicyId());

        var protocolBootstrapParams = new ProtocolBootstrapParams(protocolParams,
                coordinationParams,
                programmableLogicGlobalParams,
                programmableLogicBaseParams,
                unfrackingParams,
                upgradeMultisigParams,
                issuanceParams,
                directoryParams,
                directorySpendParams,
                new TxInput(txHash, plbRefIdx),
                new TxInput(txHash, plgRefIdx),
                new TxInput(txHash, unfrackingRefIdx),
                txHash);

        log.info("BootstrapParams: {}", OBJECT_MAPPER.writeValueAsString(protocolBootstrapParams));
    }

    private static int findRefScriptOutputIndex(
            com.bloxbean.cardano.client.transaction.spec.Transaction tx,
            PlutusScript script) throws Exception {
        var wanted = HexUtil.encodeHexString(script.getScriptHash());
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            var ref = outputs.get(i).getScriptRef();
            if (ref == null) {
                continue;
            }
            var deserialized = com.bloxbean.cardano.client.api.util.ReferenceScriptUtil.deserializeScriptRef(ref);
            if (wanted.equals(HexUtil.encodeHexString(deserialized.getScriptHash()))) {
                return i;
            }
        }
        throw new IllegalStateException("no output carries reference script " + wanted);
    }

}
