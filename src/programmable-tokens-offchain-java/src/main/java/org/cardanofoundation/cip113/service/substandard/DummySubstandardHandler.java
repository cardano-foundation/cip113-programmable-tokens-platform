package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.comparator.TransactionInputComparator;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.cardano.util.UtxoUtil;
import com.easy1staking.util.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.cardanofoundation.cip113.service.substandard.capabilities.BasicOperations;
import org.cardanofoundation.cip113.util.Cip68;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.math.BigInteger.ONE;

/**
 * Handler for the "dummy" programmable token substandard.
 * This is a simple reference implementation with basic issue and transfer validators.
 *
 * <p>Capabilities: {@link BasicOperations} only (register, mint, burn, transfer)</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DummySubstandardHandler implements SubstandardHandler, BasicOperations<DummyRegisterRequest> {

    private static final String SUBSTANDARD_ID = "dummy";

    private final ObjectMapper objectMapper;

    private final AppConfig.Network network;

    private final UtxoRepository utxoRepository;

    private final RegistryNodeParser registryNodeParser;

    private final AccountService accountService;

    private final SubstandardService substandardService;

    private final ProtocolScriptBuilderService protocolScriptBuilderService;

    private final QuickTxBuilder quickTxBuilder;

    /** Sizes the CIP-68 reference-token output against the live min-UTxO parameters. */
    private final ProtocolParamsSupplier protocolParamsSupplier;

    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    /** Answers "is this credential already registered?" against the LEDGER, falling back to the
     *  indexed certificates. Querying the index directly is what produced
     *  StakeKeyAlreadyRegisteredDELEG here: these validators are protocol-global and registered
     *  once per network, typically long before this deployment's sync-start slot. */
    private final ScriptRegistrationService scriptRegistrationService;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(DummyRegisterRequest registerTokenRequest,
                                                                            ProtocolBootstrapParams protocolBootstrapParams) {

        try {

            var rigistrarUtxosOpt = utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.getFeePayerAddress(), Pageable.unpaged());
            if (rigistrarUtxosOpt.isEmpty()) {
                return TransactionContext.typedError("issuer wallet is empty");
            }

            // Handler knows its own contract names internally
            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "transfer.issue.withdraw");
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "transfer.transfer.withdraw");

            if (substandardIssuanceContractOpt.isEmpty() || substandardTransferContractOpt.isEmpty()) {
                log.warn("substandard issuance or transfer contract are empty");
                return TransactionContext.typedError("substandard issuance or transfer contract are empty");
            }

            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());
            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());

            var requiredStakeAddresses = Stream.of(substandardIssueAddress, substandardTransferAddress)
                    .map(Address::getAddress)
                    .toList();

            var registeredStakeAddresses = requiredStakeAddresses.stream()
                    .filter(scriptRegistrationService::isStakeAddressRegistered)
                    .toList();

            // Everything REQUIRED that is not yet REGISTERED.
            //
            // This used to start from `registeredStakeAddresses` and keep the entries not in
            // `requiredStakeAddresses` — but the registered list is by construction a subset of
            // the required one, so that predicate was never true and this list was ALWAYS empty.
            // The method therefore always returned a null CBOR, the wizard reported "all
            // required stake addresses are already registered", and no certificate was ever
            // built. On a protocol deployment where they genuinely were not registered, the
            // registration that followed withdrew-0 from reward accounts that did not exist and
            // was rejected with WithdrawalsNotInRewardsCERTS.
            var stakeAddressesToRegister = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> !registeredStakeAddresses.contains(stakeAddress))
                    .toList();
            log.info("dummy pre-registration: required={} registered={} toRegister={}",
                    requiredStakeAddresses, registeredStakeAddresses, stakeAddressesToRegister);

            if (stakeAddressesToRegister.isEmpty()) {
                return TransactionContext.ok(null, registeredStakeAddresses);
            } else {

                var registerAddressTx = new Tx()
                        .from(registerTokenRequest.getFeePayerAddress())
                        .withChangeAddress(registerTokenRequest.getFeePayerAddress());

                stakeAddressesToRegister.forEach(registerAddressTx::registerStakeAddress);

                var transaction = quickTxBuilder.compose(registerAddressTx)
                        .feePayer(registerTokenRequest.getFeePayerAddress())
                        .build();

                return TransactionContext.ok(transaction.serializeToHex(), registeredStakeAddresses);
            }

        } catch (Exception e) {
            return TransactionContext.typedError(e.getMessage());
        }

    }

    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(DummyRegisterRequest registerTokenRequest,
                                                                               ProtocolBootstrapParams protocolBootstrapParams) {

        try {

            var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolBootstrapParams);

            var bootstrapTxHash = protocolBootstrapParams.txHash();

            var protocolParamsUtxoOpt = utxoRepository.findById(UtxoId.builder()
                    .txHash(bootstrapTxHash)
                    .outputIndex(0)
                    .build());

            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());

            var directoryMintContract = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolBootstrapParams);
            var directoryMintPolicyId = directoryMintContract.getPolicyId();

            var issuanceUtxoOpt = utxoRepository.findById(UtxoId.builder().txHash(bootstrapTxHash).outputIndex(2).build());
            if (issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();
            log.info("issuanceUtxo: {}", issuanceUtxo);

            var rigistrarUtxosOpt = utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.getFeePayerAddress(), Pageable.unpaged());
            if (rigistrarUtxosOpt.isEmpty()) {
                return TransactionContext.typedError("issuer wallet is empty");
            }
            var registrarUtxos = rigistrarUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            // Handler knows its own contract names internally
            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "transfer.issue.withdraw");
            var substandardTransferContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "transfer.transfer.withdraw");

            // filter(Objects::nonNull) after the map, not just orElse(""): the validator
            // may be PRESENT with a null scriptHash (blueprint entry without a compiled
            // hash), which orElse alone would pass straight through to
            // Credential.fromScript(null) below.
            var thirdPartyScriptHash = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "third_party.third_party.withdraw")
                    .map(SubstandardValidator::scriptHash)
                    .filter(Objects::nonNull)
                    .orElse("");

            if (substandardIssuanceContractOpt.isEmpty() || substandardTransferContractOpt.isEmpty()) {
                log.warn("substandard issuance or transfer contract are empty");
                return TransactionContext.typedError("substandard issuance or transfer contract are empty");
            }

            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);

            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolBootstrapParams, substandardIssueContract);
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            log.info("issuanceContract: {}", progTokenPolicyId);

            var registryEntries = utxoRepository.findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());

            var registryEntryOpt = registryEntries.stream()
                    .flatMap(Collection::stream)
                    .filter(addressUtxoEntity -> registryNodeParser.parse(addressUtxoEntity.getInlineDatum())
                            .map(registryNode -> registryNode.key().equals(progTokenPolicyId))
                            .orElse(false)
                    )
                    .findAny();

            if (registryEntryOpt.isEmpty()) {

                var nodeToReplaceOpt = registryEntries.stream()
                        .flatMap(Collection::stream)
                        .filter(addressUtxoEntity -> {
                            var registryDatumOpt = registryNodeParser.parse(addressUtxoEntity.getInlineDatum());

                            if (registryDatumOpt.isEmpty()) {
                                log.warn("could not parse registry datum for: {}", addressUtxoEntity.getInlineDatum());
                                return false;
                            }

                            var registryDatum = registryDatumOpt.get();

                            var after = registryDatum.key().compareTo(progTokenPolicyId) < 0;
                            var before = progTokenPolicyId.compareTo(registryDatum.next()) < 0;
                            log.info("after:{}, before: {}", after, before);
                            return after && before;

                        })
                        .findAny();

                if (nodeToReplaceOpt.isEmpty()) {
                    return TransactionContext.typedError("could not find node to replace");
                }

                var directoryUtxo = UtxoUtil.toUtxo(nodeToReplaceOpt.get());
                log.info("directoryUtxo: {}", directoryUtxo);
                var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());

                if (existingRegistryNodeDatumOpt.isEmpty()) {
                    return TransactionContext.typedError("could not parse current registry node");
                }

                var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

                // Directory MINT - NFT, address, datum and value
                // types.RegistryInsert { key: ByteArray, minting_logic_script: Credential }.
                // v0.4.0: the 2nd field is a Credential, not a bare hash — Script(hash) is
                // Constr 1 [bytes].
                var directoryMintRedeemer = ConstrPlutusData.of(1,
                        BytesPlutusData.of(issuanceContract.getScriptHash()),
                        ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash()))
                );

                var directoryMintNft = Asset.builder()
                        .name("0x" + issuanceContract.getPolicyId())
                        .value(BigInteger.ONE)
                        .build();

                Optional<Amount> registrySpentNftOpt = directoryUtxo.getAmount()
                        .stream()
                        .filter(amount -> amount.getQuantity().equals(ONE) && directoryMintPolicyId.equals(AssetType.fromUnit(amount.getUnit()).policyId()))
                        .findAny();

                if (registrySpentNftOpt.isEmpty()) {
                    return TransactionContext.typedError("could not find amount for directory mint");
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

                // third_party_transfer_logic_script (index 4): the `dummy` substandard has NO
                // third-party/force-transfer validator — src/substandards/dummy/validators/transfer.ak
                // declares only `issue` and `transfer`, and the blueprint exposes only those two.
                // The slot cannot be left empty (linked_list.is_28_byte_credential rejects a
                // zero-length credential on a non-origin node), so it is pinned to the protocol's
                // always_fail script: programmable_logic_global.validate_3rd_party requires a
                // withdrawal against this credential, and always_fail's `else` handler fails
                // unconditionally, making seizure structurally impossible rather than
                // delegated to an authority the substandard never declared.
                // The lookup below is kept so a future dummy third_party validator is picked up
                // automatically.
                // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN. No dummy
                // validator declares an unfracking hook, so least permission is the deliberate value.
                var directoryMintDatum = new RegistryNode(HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                        existingRegistryNodeDatum.next(),
                        Credential.fromScript(substandardIssueContract.getScriptHash()),
                        Credential.fromScript(substandardTransferContract.getScriptHash()),
                        Credential.fromScript(thirdPartyScriptHash.isEmpty()
                                ? protocolBootstrapParams.issuanceParams().alwaysFailScriptHash()
                                : thirdPartyScriptHash),
                        RegistryNode.EMPTY_VKEY,
                        "");
                log.info("directoryMintDatum: {}", directoryMintDatum);

                Value directoryMintValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(directoryMintContract.getPolicyId())
                                        .assets(List.of(directoryMintNft))
                                        .build()
                        ))
                        .build();
                log.info("directoryMintValue: {}", directoryMintValue);

                Value directorySpendValue = Value.builder()
                        .coin(Amount.ada(1).getQuantity())
                        .multiAssets(List.of(
                                MultiAsset.builder()
                                        .policyId(directoryMintContract.getPolicyId())
                                        .assets(List.of(directorySpendNft))
                                        .build()
                        ))
                        .build();
                log.info("directorySpendValue: {}", directorySpendValue);


                // issuance_mint's redeemer IS types.MintingRegistryProof in v0.4.0 — the old
                // SmartTokenMintingAction { minting_logic_cred, minting_registry_proof } wrapper
                // is gone (the credential is now the validator's compile-time parameter).
                // Registry node output is at index 2 in outputs:
                // [0] PLB output (programmable token), [1] updated covering node, [2] new registry node
                // CIP-68 mints a PAIR under this one policy: the labelled user token, plus a
                // (100) reference token of quantity 1 whose inline datum carries the metadata.
                // The reference token is itself a programmable token, so core's `no_escape`
                // forces it to a PLB base address with an inline stake credential just like the
                // user token — it goes to the ISSUER's, so the issuer can spend it later to
                // update the metadata.
                var cip68Metadata = registerTokenRequest.getCip68Metadata();
                if (cip68Metadata != null
                    && (registerTokenRequest.getQuantity() == null || registerTokenRequest.getQuantity().isBlank())) {
                    return TransactionContext.typedError(
                            "quantity is required when cip68Metadata is present — the reference "
                            + "token is minted against a stated supply.");
                }
                var mintQuantity = new BigInteger(registerTokenRequest.getQuantity());
                if (cip68Metadata != null && mintQuantity.signum() <= 0) {
                    return TransactionContext.typedError(
                            "quantity must be > 0 when cip68Metadata is present, got: " + mintQuantity);
                }
                // ALWAYS (333) for dummy. Nothing in validators/transfer.ak caps lifetime supply —
                // `issue` is `redeemer == 100` and buildMintTransaction below will happily mint
                // more of the same name later — so a (222) here would be a non-fungibility claim
                // this substandard cannot keep. See Cip68.userTokenLabel for the full argument.
                var userAssetNameHex = cip68Metadata == null
                        ? registerTokenRequest.getAssetName()
                        : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), registerTokenRequest.getAssetName());
                var referenceAssetNameHex = cip68Metadata == null
                        ? null
                        : Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, registerTokenRequest.getAssetName());

                // The registry node is the LAST output, and CIP-68 inserts the reference-token
                // output ahead of the two node outputs — so the index issuance_mint is told to
                // look at shifts from 2 to 3. Getting this wrong makes issuance_mint read the
                // wrong output and trap, which is why it is derived rather than written twice.
                var registryNodeOutputIndex = cip68Metadata == null ? 2 : 3;
                var issuanceRedeemer = ConstrPlutusData.of(1, BigIntPlutusData.of(registryNodeOutputIndex)); // OutputIndex { index }

                // Programmable Token Mint
                var programmableToken = Asset.builder()
                        .name("0x" + userAssetNameHex)
                        .value(mintQuantity)
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

                var payee = registerTokenRequest.getRecipientAddress() == null || registerTokenRequest.getRecipientAddress().isBlank() ? registerTokenRequest.getFeePayerAddress() : registerTokenRequest.getRecipientAddress();
                log.info("payee: {}", payee);

                var payeeAddress = new Address(payee);

                var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                        payeeAddress.getDelegationCredential().get(),
                        network.getCardanoNetwork());

                Asset referenceToken = null;
                Value referenceTokenValue = null;
                Address referenceTokenAddress = null;
                var referenceTokenDatum = cip68Metadata == null ? null : Cip68.buildDatum(cip68Metadata);
                if (cip68Metadata != null) {
                    referenceToken = Asset.builder()
                            .name("0x" + referenceAssetNameHex)
                            .value(ONE)
                            .build();
                    var issuerAddress = new Address(registerTokenRequest.getFeePayerAddress());
                    referenceTokenAddress = AddressProvider.getBaseAddress(
                            Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                            issuerAddress.getDelegationCredential().orElseThrow(() -> new IllegalArgumentException(
                                    "CIP-68 needs the issuer's stake credential to place the reference token — "
                                    + "feePayerAddress must be a base address")),
                            network.getCardanoNetwork());
                    var sizingValue = Value.builder()
                            .coin(Amount.ada(1).getQuantity())
                            .multiAssets(List.of(MultiAsset.builder()
                                    .policyId(issuanceContract.getPolicyId())
                                    .assets(List.of(referenceToken))
                                    .build()))
                            .build();
                    referenceTokenValue = sizingValue.toBuilder()
                            .coin(Cip68.referenceOutputCoin(protocolParamsSupplier.getProtocolParams(),
                                    referenceTokenAddress.getAddress(), sizingValue, referenceTokenDatum))
                            .build();
                }

                // Both assets mint under the one issuance policy with the ONE issuance redeemer —
                // issuance_mint constrains no asset names, it only checks the registry proof and
                // that nothing escapes the PLB.
                var mintedAssets = cip68Metadata == null
                        ? List.of(programmableToken)
                        : List.of(programmableToken, referenceToken);

                var tx = new Tx()
                        .collectFrom(registrarUtxos)
                        .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                        .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                        // Mint Token
                        .mintAsset(issuanceContract, mintedAssets, issuanceRedeemer)
                        // Redeemer is DirectoryInit (constr(0))
                        .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                        .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0));

                // Output 1, CIP-68 only. Must sit between the user token and the two node
                // outputs so `registryNodeOutputIndex` above stays correct.
                if (cip68Metadata != null) {
                    tx = tx.payToContract(referenceTokenAddress.getAddress(),
                            ValueUtil.toAmountList(referenceTokenValue), referenceTokenDatum);
                }

                tx = tx
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
                        .withChangeAddress(registerTokenRequest.getFeePayerAddress());

                var transaction = quickTxBuilder.compose(tx)
//                    .withSigner(SignerProviders.signerFrom(adminAccount))
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                        .feePayer(registerTokenRequest.getFeePayerAddress())
                        .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                        .preBalanceTx((txBuilderContext, transaction1) -> {
                            var outputs = transaction1.getBody().getOutputs();
                            if (outputs.getFirst().getAddress().equals(registerTokenRequest.getFeePayerAddress())) {
                                log.info("found dummy input, moving it...");
                                var first = outputs.removeFirst();
                                outputs.addLast(first);
                            }
                            try {
                                log.info("pre tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .postBalanceTx((txBuilderContext, transaction1) -> {
                            try {
                                log.info("post tx: {}", objectMapper.writeValueAsString(transaction1));
                            } catch (JsonProcessingException e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .build();

                log.info("tx: {}", transaction.serializeToHex());
                log.info("tx: {}", objectMapper.writeValueAsString(transaction));

                // The metadata is user-supplied text and the reference output is real bytes on a
                // transaction that was already sized for the registry nodes. Measure the finished
                // CBOR before handing it back, so an oversized metadata fails HERE rather than at
                // submission after the user has signed. Only on the CIP-68 branch — the
                // non-CIP-68 path is byte-for-byte what it was.
                if (cip68Metadata != null) {
                    Cip68.preflightTxSize("dummy registration", transaction.serialize(),
                            protocolParamsSupplier.getProtocolParams());
                }

                // Save to unified programmable token registry (policyId -> substandardId binding)
                programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                        .policyId(progTokenPolicyId)
                        .substandardId(SUBSTANDARD_ID)
                        // The LABELLED name, so a later mint/transfer resolves the asset that is
                        // actually on chain rather than the unlabelled base the wizard collected.
                        .assetName(userAssetNameHex)
                        .build());

                return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));
            } else {

                return TransactionContext.typedError(String.format("Token policy %s already registered", progTokenPolicyId));
            }


        } catch (Exception e) {
            return TransactionContext.typedError(e.getMessage());
        }

    }

    @Override
    public TransactionContext<Void> buildMintTransaction(MintTokenRequest mintTokenRequest,
                                                         ProtocolBootstrapParams protocolBootstrapParams) {


        try {

            // dummy mints its CIP-68 pair at REGISTRATION, so metadata on a later mint has
            // nowhere to go. Refuse instead of ignoring it: silently accepting would leave the
            // caller believing the reference token had been created or updated.
            if (mintTokenRequest.cip68Metadata() != null) {
                return TransactionContext.typedError(
                        "cip68Metadata is not accepted on a dummy mint — the (100) reference token "
                        + "is minted at registration. To change the metadata, spend the existing "
                        + "reference-token UTxO and rewrite its datum (not supported here).");
            }

            // The asset this mint emits comes straight off the request, and `issue` in
            // validators/transfer.ak is `redeemer == 100` — it constrains no asset name at all.
            // So without this check a caller who registered `(333)Foo` can mint bare `Foo`, or
            // `(444)Foo`, or `(100)Foo` with a datum of their choosing, all under the registered
            // policy id. Every one of those is indistinguishable from the real token to a wallet
            // that trusts the policy. Bind the mint to the name the registry recorded.
            var registryRowOpt = programmableTokenRegistryRepository.findByPolicyId(mintTokenRequest.tokenPolicyId());
            if (registryRowOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "no registry row for policy " + mintTokenRequest.tokenPolicyId()
                        + " — register the token before minting.");
            }
            var registeredAssetName = registryRowOpt.get().getAssetName();
            if (registeredAssetName == null || !registeredAssetName.equalsIgnoreCase(mintTokenRequest.assetName())) {
                return TransactionContext.typedError(
                        "assetName '" + mintTokenRequest.assetName() + "' does not match the registered "
                        + "asset name '" + registeredAssetName + "' for policy "
                        + mintTokenRequest.tokenPolicyId()
                        + (Cip68.hasLabel(registeredAssetName)
                           ? " — this is a CIP-68 token, so the on-chain name carries a CIP-67 label; "
                             + "send the labelled name."
                           : "."));
            }
            // Belt and braces: even if a (100) name were somehow the registered one, this endpoint
            // must never mint it. A second reference token breaks the CIP-68 pair irrecoverably —
            // two (100)s of quantity 1 under one policy leave every consumer picking one at random.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(mintTokenRequest.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to mint a (100) reference token from the ordinary mint endpoint. "
                        + "CIP-68 allows exactly one per token and it is created at registration.");
            }

            var feePayerUtxosOpt = utxoRepository.findUnspentByOwnerAddr(mintTokenRequest.feePayerAddress(), Pageable.unpaged());
            if (feePayerUtxosOpt.isEmpty()) {
                return TransactionContext.error("fee payer wallet is empty");
            }
            var feePayerUtxos = feePayerUtxosOpt.get().stream().map(UtxoUtil::toUtxo).toList();

            // Handler knows its own contract names internally. The dummy blueprint declares
            // `issue` and `transfer` inside validators/transfer.ak, so the path is
            // "transfer.issue.withdraw" — same name the registration path above uses.
            // ("issue.issue.withdraw" does not exist and made every dummy mint fail with a
            // bare NoSuchElementException from Optional.get().)
            var substandardIssuanceContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "transfer.issue.withdraw");
            if (substandardIssuanceContractOpt.isEmpty()) {
                return TransactionContext.error("substandard issuance contract not found for " + SUBSTANDARD_ID);
            }

            var substandardIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolBootstrapParams, substandardIssueContract);
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            log.info("issuanceContract: {}", progTokenPolicyId);

            // The registry row above was found by the policy id the REQUEST claimed. This policy
            // id is the one the transaction will actually mint under, and it is derived from
            // `protocolBootstrapParams` — which the caller chooses, via `protocolTxHash`
            // (TokenOperationsService.resolveProtocolParams). Those are two different things.
            //
            // Without this check, naming policy A / asset A while selecting bootstrap B passes the
            // canonical-name check against A's registry row and then mints asset A under policy B:
            // a token that is registered nowhere, wearing a registered token's name, under a
            // policy the caller picked. Bind the check to what will actually be minted.
            if (!progTokenPolicyId.equalsIgnoreCase(mintTokenRequest.tokenPolicyId())) {
                return TransactionContext.typedError(
                        "the selected protocol deployment derives issuance policy " + progTokenPolicyId
                        + ", but this request names policy " + mintTokenRequest.tokenPolicyId()
                        + ". Minting would put the registered asset name under a DIFFERENT policy "
                        + "than the one it is registered against. Use the protocol deployment this "
                        + "token was registered on, or omit protocolTxHash to use the default.");
            }

            // Find the registry node for this token (must exist for subsequent mint)
            var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolBootstrapParams);
            var registryEntries = utxoRepository.findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());
            var progTokenRegistryOpt = registryEntries.stream()
                    .flatMap(Collection::stream)
                    .filter(addressUtxoEntity -> registryNodeParser.parse(addressUtxoEntity.getInlineDatum())
                            .map(registryNode -> registryNode.key().equals(progTokenPolicyId))
                            .orElse(false))
                    .findAny();

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.error("could not find registry entry for token — is this a first mint?");
            }

            var progTokenRegistry = UtxoUtil.toUtxo(progTokenRegistryOpt.get());
            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();

            // Sort reference inputs to compute the registry node index
            var sortedReferenceInputs = Stream.of(registryRefInput)
                    .sorted(new TransactionInputComparator())
                    .toList();
            var registryRefInputIndex = sortedReferenceInputs.indexOf(registryRefInput);

            // types.MintingRegistryProof directly (no SmartTokenMintingAction wrapper in v0.4.0).
            var issuanceRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(registryRefInputIndex)); // RefInput { index }

            // Programmable Token Mint
            var programmableToken = Asset.builder()
                    .name("0x" + mintTokenRequest.assetName())
                    .value(new BigInteger(mintTokenRequest.quantity()))
                    .build();

            Value progammableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(issuanceContract.getPolicyId())
                                    .assets(List.of(programmableToken))
                                    .build()
                    ))
                    .build();

            var recipient = Optional.ofNullable(mintTokenRequest.recipientAddress())
                    .orElse(mintTokenRequest.feePayerAddress());

            var recipientAddress = new Address(recipient);

            var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(100))
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(progammableTokenValue), ConstrPlutusData.of(0))
                    .readFrom(registryRefInput)
                    .attachRewardValidator(substandardIssueContract)
                    .withChangeAddress(mintTokenRequest.feePayerAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(mintTokenRequest.feePayerAddress())
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(mintTokenRequest.feePayerAddress())) {
                            log.info("found dummy input, moving it...");
                            var first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                        try {
                            log.info("pre tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        try {
                            log.info("post tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .build();

            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("error", e);
            return TransactionContext.error(e.getMessage());
        }

    }

    @Override
    public TransactionContext<Void> buildTransferTransaction(TransferTokenRequest transferTokenRequest,
                                                             ProtocolBootstrapParams protocolBootstrapParams) {

        try {

            var bootstrapTxHash = protocolBootstrapParams.txHash();

            var progToken = AssetType.fromUnit(transferTokenRequest.unit());
            log.info("policy id: {}, asset name: {}", progToken.policyId(), progToken.unsafeHumanAssetName());

            // A (100) reference token cannot go through this path. Every output below is rebuilt
            // with `ConstrPlutusData.of(0)` as its datum — the ordinary programmable-token datum —
            // which would DESTROY the metadata the reference token exists to carry. The token
            // would survive; the CIP-68 pair would not, and the (222)/(333) user token would be
            // left advertising metadata that no longer resolves. Preserving the datum instead is
            // a metadata-custody feature (who may move it, and does moving it imply a rewrite),
            // not a transfer detail, so it is refused here rather than half-implemented.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(progToken.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to transfer the CIP-68 (100) reference token: this path rebuilds "
                        + "outputs with the plain programmable-token datum, which would erase the "
                        + "metadata and break the pair. Reference-token custody is not supported.");
            }

            // Directory SPEND parameterization
            var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolBootstrapParams);
            log.info("directorySpendContract: {}", HexUtil.encodeHexString(directorySpendContract.getScriptHash()));

            var registryEntries = utxoRepository.findUnspentByOwnerPaymentCredential(directorySpendContract.getPolicyId(), Pageable.unpaged());

            var progTokenRegistryOpt = registryEntries.stream()
                    .flatMap(Collection::stream)
                    .filter(addressUtxoEntity -> {
                        var registryDatumOpt = registryNodeParser.parse(addressUtxoEntity.getInlineDatum());
                        return registryDatumOpt.map(registryDatum -> registryDatum.key().equals(progToken.policyId())).orElse(false);
                    })
                    .findAny()
                    .map(UtxoUtil::toUtxo);

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.error("could not find registry entry for token");
            }

            var progTokenRegistry = progTokenRegistryOpt.get();

            var protocolParamsUtxoOpt = utxoRepository.findById(UtxoId.builder()
                    .txHash(bootstrapTxHash)
                    .outputIndex(0)
                    .build());

            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.error("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

            var senderAddress = new Address(transferTokenRequest.senderAddress());
            var senderProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                    senderAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var recipientAddress = new Address(transferTokenRequest.recipientAddress());
            var recipientProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var senderProgTokenAddressesOpt = utxoRepository.findUnspentByOwnerAddr(senderProgrammableTokenAddress.getAddress(), Pageable.unpaged());
            var senderProgTokensUtxos = senderProgTokenAddressesOpt.stream()
                    .flatMap(Collection::stream)
                    .map(UtxoUtil::toUtxo)
                    .toList();

            var senderProgTokensValue = senderProgTokensUtxos.stream()
                    .map(Utxo::toValue)
                    .filter(value -> value.amountOf(progToken.policyId(), "0x" + progToken.assetName()).compareTo(BigInteger.ZERO) > 0)
                    .reduce(Value::add)
                    .orElse(Value.builder().build());

            var progTokenAmount = senderProgTokensValue.amountOf(progToken.policyId(), "0x" + progToken.assetName());

            if (progTokenAmount.compareTo(new BigInteger(transferTokenRequest.quantity())) < 0) {
                return TransactionContext.error("Not enough funds");
            }

            var senderUtxos = accountService.findAdaOnlyUtxo(senderAddress.getAddress(), 10_000_000L);

            // Programmable Logic Global parameterization
            var programmableLogicGlobal = protocolScriptBuilderService.getParameterizedProgrammableLogicGlobalScript(protocolBootstrapParams);
            var programmableLogicGlobalAddress = AddressProvider.getRewardAddress(programmableLogicGlobal, network.getCardanoNetwork());
            log.info("programmableLogicGlobalAddress policy: {}", programmableLogicGlobalAddress.getAddress());
            log.info("protocolBootstrapParams.programmableLogicGlobalPrams().scriptHash(): {}", protocolBootstrapParams.programmableLogicGlobalPrams().scriptHash());

//            // Programmable Logic Base parameterization
            var programmableLogicBase = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(protocolBootstrapParams);
            log.info("programmableLogicBase policy: {}", programmableLogicBase.getPolicyId());

            // Programmable Token Mint
            var valueToSend = Value.from(progToken.policyId(), "0x" + progToken.assetName(), new BigInteger(transferTokenRequest.quantity()));
            var returningValue = senderProgTokensValue.subtract(valueToSend);

            var tokenAsset2 = Asset.builder()
                    .name("0x" + progToken.assetName())
                    .value(new BigInteger(transferTokenRequest.quantity()))
                    .build();

            Value tokenValue2 = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(progToken.policyId())
                                    .assets(List.of(tokenAsset2))
                                    .build()
                    ))
                    .build();

            var protocolParamsRefInput = TransactionInput.builder()
                    .transactionId(protocolParamsUtxo.getTxHash())
                    .index(protocolParamsUtxo.getOutputIndex())
                    .build();

            var progTokenRegistryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();

            var sortedReferenceInputs = Stream.of(protocolParamsRefInput, progTokenRegistryRefInput)
                    .sorted(new TransactionInputComparator())
                    .toList();

            var registryIndex = sortedReferenceInputs.indexOf(progTokenRegistryRefInput);

            var programmableGlobalRedeemer = ConstrPlutusData.of(0,
                    // only one prop and it's a list
                    ListPlutusData.of(ConstrPlutusData.of(0, BigIntPlutusData.of(registryIndex)))
            );

            // FIXME:
            var substandardTransferContractOpt = substandardService.getSubstandardValidator("dummy", "transfer.transfer.withdraw");
            if (substandardTransferContractOpt.isEmpty()) {
                log.warn("could not resolve transfer contract");
                return TransactionContext.error("could not resolve transfer contract");
            }
            var substandardTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(substandardTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());
            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());

            var inputUtxos = senderProgTokensUtxos.stream()
                    .reduce(new Pair<List<Utxo>, Value>(List.of(), Value.builder().build()),
                            (listValuePair, utxo) -> {
                                if (listValuePair.second().subtract(valueToSend).isPositive()) {
                                    return listValuePair;
                                } else {
                                    if (utxo.toValue().amountOf(progToken.policyId(), "0x" + progToken.assetName()).compareTo(BigInteger.ZERO) > 0) {
                                        var newUtxos = Stream.concat(Stream.of(utxo), listValuePair.first().stream());
                                        return new Pair<>(newUtxos.toList(), listValuePair.second().add(utxo.toValue()));
                                    } else {
                                        return listValuePair;
                                    }
                                }
                            }, (listValuePair, listValuePair2) -> {
                                var newUtxos = Stream.concat(listValuePair.first().stream(), listValuePair.first().stream());
                                return new Pair<>(newUtxos.toList(), listValuePair.second().add(listValuePair2.second()));
                            })
                    .first();

            var tx = new Tx()
                    .collectFrom(senderUtxos);

            inputUtxos.forEach(utxo -> {
                tx.collectFrom(utxo, ConstrPlutusData.of(0));
            });

            // must be first Provide proofs
            tx.withdraw(substandardTransferAddress.getAddress(), BigInteger.ZERO, BigIntPlutusData.of(200))
                    .withdraw(programmableLogicGlobalAddress.getAddress(), BigInteger.ZERO, programmableGlobalRedeemer)
                    .payToContract(senderProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(returningValue), ConstrPlutusData.of(0))
                    .payToContract(recipientProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(tokenValue2), ConstrPlutusData.of(0))
                    .readFrom(TransactionInput.builder()
                            .transactionId(protocolParamsUtxo.getTxHash())
                            .index(protocolParamsUtxo.getOutputIndex())
                            .build(), TransactionInput.builder()
                            .transactionId(progTokenRegistry.getTxHash())
                            .index(progTokenRegistry.getOutputIndex())
                            .build())
                    .attachRewardValidator(programmableLogicGlobal) // global
                    .attachRewardValidator(substandardTransferContract)
                    .attachSpendingValidator(programmableLogicBase) // base
                    .withChangeAddress(senderAddress.getAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(senderAddress.getDelegationCredentialHash().get())
                    .feePayer(senderAddress.getAddress())
                    .mergeOutputs(false)
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        var fees = transaction1.getBody().getFee();
                        var newFees = fees.add(BigInteger.valueOf(200_000L));
                        transaction1.getBody().setFee(newFees);

                        transaction1.getBody()
                                .getOutputs()
                                .stream()
                                .filter(transactionOutput -> senderAddress.getAddress().equals(transactionOutput.getAddress()) && transactionOutput.getValue().getCoin().compareTo(BigInteger.valueOf(2_000_000)) > 0)
                                .findAny()
                                .ifPresent(transactionOutput -> {
                                    transactionOutput.setValue(transactionOutput.getValue().substractCoin(BigInteger.valueOf(200_000L)));
                                });

                        transaction1.getBody().setTotalCollateral(transaction1.getBody().getTotalCollateral().add(BigInteger.valueOf(500_000L)));
                        var collateralReturn = transaction1.getBody().getCollateralReturn();
                        collateralReturn.setValue(collateralReturn.getValue().substractCoin(BigInteger.valueOf(500_000L)));
                    })
                    .build();


            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            return TransactionContext.error(e.getMessage());
        }

    }

}
