package org.cardanofoundation.cip113.standard;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
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
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.AbstractPreviewTest;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.bootstrap.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class PreviewProtocolDeploymentMintTest extends AbstractPreviewTest {


    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String NONCE_ISSUANCE_ALWAYS_FAIL = "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";

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
        var plutus = OBJECT_MAPPER.readValue(this.getClass().getClassLoader().getResourceAsStream("plutus.json"), Plutus.class);
        var validators = plutus.validators();
        ALWAYS_FAIL_CONTRACT = getCompiledCodeFor("always_fail.always_fail.spend", validators);
        COORDINATION_SPEND_CONTRACT = getCompiledCodeFor("coordination_spend.coordination_spend.spend", validators);
        PROTOCOL_PARAMS_CONTRACT = getCompiledCodeFor("protocol_params_mint.protocol_params_mint.mint", validators);
        PROGRAMMABLE_LOGIC_BASE_CONTRACT = getCompiledCodeFor("programmable_logic_base.programmable_logic_base.spend", validators);
        TRANSFER_CONTRACT = getCompiledCodeFor("transfer.transfer.withdraw", validators);
        THIRD_PARTY_CONTRACT = getCompiledCodeFor("third_party.third_party.withdraw", validators);
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

        private static BigInteger lovelaceOf(Utxo utxo) {
                return utxo.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        }

    @Test
    public void deploy() throws Exception {

        var dryRun = false;

        var utxosOpt = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        Assertions.assertTrue(utxosOpt.isSuccessful(), "utxo query failed: " + utxosOpt.getResponse());
        Assertions.assertTrue(utxosOpt.getValue().size() >= 2,
                "need >=2 utxos at the admin address — run DevnetFundingTest first");

        var walletUtxos = utxosOpt.getValue().stream().filter(a -> lovelaceOf(a).compareTo(BigInteger.valueOf(100000000)) > 0).limit(2).toList();
        var collectedLovelace = walletUtxos.stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        System.out.println(adminAccount.baseAddress() + " has " + collectedLovelace + " lovelace across " + walletUtxos.size() + " utxos");
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
        // Nonce-parameterised on purpose — depending on the params policy would be circular
        // (protocol_params_mint is itself parameterised by THIS validator's address).
        // Derived from the bootstrap UTxO rather than a constant, so each deployment gets its
        // own coordination address; a fixed nonce parks every deployment's coordination UTxO at
        // one address, which is confusing to debug after a redeploy. The value is carried in
        // the handoff JSON so the address can be re-derived later.
        var coordinationNonce = Blake2bUtil.blake2bHash256(
                HexUtil.decodeHexString(utxo1.getTxHash() + String.format("%02x", utxo1.getOutputIndex())));
        var coordinationSpendScript = applyParams(COORDINATION_SPEND_CONTRACT,
                BytesPlutusData.of(coordinationNonce));
        var coordinationAddress = AddressProvider.getEntAddress(coordinationSpendScript, network);
        log.info("coordinationAddress: {}", coordinationAddress.getAddress());

        // ---- 2. protocol_params_mint(utxo1, coordination_hash)
        // protocol_params_mint.ak requires the NFT output at address.from_script(that hash).
        var protocolParamsContract = applyParams(PROTOCOL_PARAMS_CONTRACT,
                utxo1OutputReference,
                BytesPlutusData.of(coordinationSpendScript.getScriptHash()));
        var paramsPolicy = BytesPlutusData.of(protocolParamsContract.getScriptHash());
        log.info("protocolParams policy: {}", protocolParamsContract.getPolicyId());

        // ---- 3-5. Everything anchored on the params policy (no PLG->PLB chain any more)
        var programmableLogicBaseContract = applyParams(PROGRAMMABLE_LOGIC_BASE_CONTRACT, paramsPolicy);
        // Three delegates. programmable_logic_base dispatches to exactly one of them per spend,
        // naming it by a field of the params datum, so all three must be deployed, published as
        // reference scripts, and stake-registered -- even though any one transaction loads only
        // one of them.
        var transferContract = applyParams(TRANSFER_CONTRACT, paramsPolicy);
        var thirdPartyContract = applyParams(THIRD_PARTY_CONTRACT, paramsPolicy);
        var unfrackingContract = applyParams(UNFRACKING_CONTRACT, paramsPolicy);

        var transferRewardAddress = AddressProvider.getRewardAddress(transferContract, network);
        var thirdPartyRewardAddress = AddressProvider.getRewardAddress(thirdPartyContract, network);
        var unfrackingRewardAddress = AddressProvider.getRewardAddress(unfrackingContract, network);

        // ---- 6. upgrade_multisig: the trampoline-2 authority named by upgrade_logic_cred.
        // Preview deployment: 1-of-1 on the admin key. Swappable later without redeploying
        // coordination_spend, since the authority lives in the datum.
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

        // The live wiring. Field order is load-bearing and is NOT spelled out here any more:
        // CoreProtocolParamsDatum owns it, so the deployment and the runtime decoder cannot
        // drift apart, and the field reorder in the last upstream revision was a change in one
        // place rather than in every builder that had written the record by hand.
        var coordinationParamsDatum = new CoreProtocolParamsDatum(
                HexUtil.encodeHexString(registryMintContract.getScriptHash()),
                Credential.fromScript(programmableLogicBaseContract.getScriptHash()),
                Credential.fromScript(transferContract.getScriptHash()),
                Credential.fromScript(thirdPartyContract.getScriptHash()),
                Credential.fromScript(unfrackingContract.getScriptHash()),
                Credential.fromScript(upgradeMultisigContract.getScriptHash()),
                CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES);
        // protocol_params_mint only shape-checks this datum, so nothing on chain would stop a
        // deployment that bricks the protocol -- a wrong-length credential nothing can satisfy,
        // or two delegates sharing a credential, which collapses PLB's dispatch. Checked here
        // because here is the only place it CAN be checked.
        coordinationParamsDatum.validateForDeployment();
        var coordinationDatum = coordinationParamsDatum.toPlutusData();

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
                // params_policy: a BARE PolicyId. This parameter used to be plg_stake_cred, a
                // Credential -- same position, same arity, different encoding. Passing the old
                // shape would still produce a template, and every token registered against it
                // would derive a policy id that registry_mint refuses.
                BytesPlutusData.of(protocolParamsContract.getScriptHash()));

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
                // reference scripts (amounts cover min-UTxO for the script sizes)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(5), programmableLogicBaseContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(20), transferContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(20), thirdPartyContract)
                .payToAddress(refInputAccount.baseAddress(), Amount.ada(12), unfrackingContract)
                // re-fragment the admin wallet for follow-up tests
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(50))
                .withChangeAddress(adminAccount.baseAddress());

        // Every withdraw-0 validator needs a registered reward account, but they do not all
        // get a deployment-unique hash. transfer, third_party and unfracking are parameterized
        // by the params policy, which derives from utxo1, so each redeployment mints fresh
        // credentials for them. upgrade_multisig is parameterized by (signers, threshold) ONLY,
        // so redeploying with the same admin key produces the identical script hash and the
        // identical reward address every time -- and the node rejects the WHOLE transaction
        // with StakeKeyRegisteredDELEG over that one repeated certificate. Register only what
        // is not already on chain.
        for (var rewardAddress : List.of(transferRewardAddress,
                thirdPartyRewardAddress,
                unfrackingRewardAddress,
                upgradeMultisigRewardAddress)) {
            if (isStakeAddressRegistered(rewardAddress.getAddress())) {
                log.info("reward account already registered on chain, skipping its certificate "
                        + "(and its 2 ADA deposit): {}", rewardAddress.getAddress());
            } else {
                tx.registerStakeAddress(rewardAddress.getAddress());
            }
        }

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
        var transferRefIdx = findRefScriptOutputIndex(transaction, transferContract);
        var thirdPartyRefIdx = findRefScriptOutputIndex(transaction, thirdPartyContract);
        var unfrackingRefIdx = findRefScriptOutputIndex(transaction, unfrackingContract);
        log.info("ref script indices - plb: {}, transfer: {}, third_party: {}, unfracking: {}",
                plbRefIdx, transferRefIdx, thirdPartyRefIdx, unfrackingRefIdx);

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
                HexUtil.encodeHexString(coordinationNonce),
                HexUtil.encodeHexString(coordinationSpendScript.getScriptHash()),
                coordinationAddress.getAddress());
        var transferParams = new DelegateParams(
                protocolParamsContract.getPolicyId(),
                HexUtil.encodeHexString(transferContract.getScriptHash()),
                transferRewardAddress.getAddress());
        var thirdPartyParams = new DelegateParams(
                protocolParamsContract.getPolicyId(),
                HexUtil.encodeHexString(thirdPartyContract.getScriptHash()),
                thirdPartyRewardAddress.getAddress());
        var programmableLogicBaseParams = new ProgrammableLogicBaseParams(
                protocolParamsContract.getPolicyId(), programmableLogicBaseContract.getPolicyId());
        var unfrackingParams = new DelegateParams(
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

        var protocolBootstrapParams = new ProtocolBootstrapParams(
                ProtocolBootstrapParams.CURRENT_SCHEMA_VERSION,
                protocolParams,
                coordinationParams,
                transferParams,
                thirdPartyParams,
                unfrackingParams,
                programmableLogicBaseParams,
                upgradeMultisigParams,
                issuanceParams,
                directoryParams,
                directorySpendParams,
                CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES,
                new TxInput(txHash, plbRefIdx),
                new TxInput(txHash, transferRefIdx),
                new TxInput(txHash, thirdPartyRefIdx),
                new TxInput(txHash, unfrackingRefIdx),
                txHash);

        log.info("BootstrapParams: {}", OBJECT_MAPPER.writeValueAsString(protocolBootstrapParams));

        // Write the handoff to disk. Gradle swallows log.info by default, and this record is the
        // deployment's only durable output — a protocol-bootstraps-<network>.json IS this file.
        //
        // Point BOOTSTRAP_OUT straight at the resource to have a deployment register itself:
        //   BOOTSTRAP_OUT=src/main/resources/protocol-bootstraps-devnet.json ./gradlew test --tests '*deploy'
        //
        // ProtocolBootstrapService reads an ARRAY keyed by txHash and picks the active entry via
        // programmable.token.default.txHash, so deployments accumulate rather than replace: an
        // existing file is merged into, keeping older deployments addressable.
        writeBootstrapHandoff(protocolBootstrapParams);
    }

    private static void writeBootstrapHandoff(ProtocolBootstrapParams params) throws Exception {
        var handoffPath = Path.of(
                System.getenv().getOrDefault("BOOTSTRAP_OUT", "build/bootstrap-params.json"));
        if (handoffPath.getParent() != null) {
            Files.createDirectories(handoffPath.getParent());
        }

        var deployments = new ArrayList<ProtocolBootstrapParams>();
        if (Files.exists(handoffPath) && Files.size(handoffPath) > 0) {
            deployments.addAll(OBJECT_MAPPER.readValue(handoffPath.toFile(),
                    new TypeReference<List<ProtocolBootstrapParams>>() {}));
            // Re-running against the same bootstrap UTxOs reproduces the same txHash; replace
            // rather than duplicate, since ProtocolBootstrapService keys the map on it.
            deployments.removeIf(existing -> params.txHash().equals(existing.txHash()));
        }
        deployments.add(params);

        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(handoffPath.toFile(), deployments);
        log.info("BootstrapParams written to {} ({} deployment(s) in file)",
                handoffPath.toAbsolutePath(), deployments.size());
        log.info("Set programmable.token.default.txHash={} to make this deployment active", params.txHash());
    }

    /**
     * True when {@code rewardAddress} currently has a registration certificate in effect.
     *
     * <p>The two wrong answers are not equally bad, so every uncertain case — network error,
     * unexpected status, unparseable body — answers {@code false}. Answering "registered"
     * when it is not silently drops the certificate, and that delegate's withdraw-0 then
     * fails at spend time, long after deployment and far from the cause. Answering "not
     * registered" when it is costs one rejected transaction that says
     * {@code StakeKeyRegisteredDELEG} and names the script hash.
     *
     * <p>Two backends, because this deployment runs against both. yaci-store (devnet) exposes
     * the certificate list at {@code /stake/registrations}. Blockfrost (preview) exposes
     * {@code /accounts/{addr}/registrations}, an ordered certificate history whose newest
     * entry is the account's current state — a deregistered account's newest action reads
     * {@code "deregistered"}, so this distinguishes that from a live registration.
     *
     * <p>Do <b>not</b> substitute the {@code active} flag on {@code /accounts/{addr}} here.
     * It means "delegated to a pool", not "registered": these reward accounts exist only to
     * satisfy withdraw-0 and never delegate, so a registered one reports
     * {@code active: false}. The library's {@code AccountInformation} model exposes only
     * that flag and not {@code registered}, which is why this goes over raw HTTP.
     */
    private static boolean isStakeAddressRegistered(String rewardAddress) {
        var yaciStoreUrl = System.getenv("CARDANO_BACKEND_URL");
        return yaciStoreUrl == null || yaciStoreUrl.isBlank()
                ? blockfrostSaysRegistered(rewardAddress)
                : yaciStoreSaysRegistered(yaciStoreUrl, rewardAddress);
    }

    private static boolean blockfrostSaysRegistered(String rewardAddress) {
        var url = withTrailingSlash(BACKEND_URL)
                + "accounts/" + rewardAddress + "/registrations?order=desc&count=1";
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("project_id", BACKEND_KEY)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // 404 is the answer for an account the chain has never seen, not a failure.
            if (response.statusCode() == 404) {
                return false;
            }
            if (response.statusCode() != 200) {
                log.warn("registration check for {} returned HTTP {} ({}) - assuming NOT registered",
                        rewardAddress, response.statusCode(), response.body());
                return false;
            }
            var history = OBJECT_MAPPER.readTree(response.body());
            if (!history.isArray() || history.isEmpty()) {
                return false;
            }
            return "registered".equals(history.get(0).path("action").asText());
        } catch (Exception e) {
            log.warn("could not check stake registration for {}: {} - assuming NOT registered",
                    rewardAddress, e.getMessage());
            return false;
        }
    }

    private static boolean yaciStoreSaysRegistered(String backendUrl, String rewardAddress) {
        var url = withTrailingSlash(backendUrl) + "stake/registrations";
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"" + rewardAddress + "\"");
        } catch (Exception e) {
            log.warn("could not check stake registration for {}: {} - assuming NOT registered",
                    rewardAddress, e.getMessage());
            return false;
        }
    }

    private static String withTrailingSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    private static int findRefScriptOutputIndex(
            Transaction tx,
            PlutusScript script) throws Exception {
        var wanted = HexUtil.encodeHexString(script.getScriptHash());
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            var ref = outputs.get(i).getScriptRef();
            if (ref == null) {
                continue;
            }
            var deserialized = ReferenceScriptUtil.deserializeScriptRef(ref);
            if (wanted.equals(HexUtil.encodeHexString(deserialized.getScriptHash()))) {
                return i;
            }
        }
        throw new IllegalStateException("no output carries reference script " + wanted);
    }

}
