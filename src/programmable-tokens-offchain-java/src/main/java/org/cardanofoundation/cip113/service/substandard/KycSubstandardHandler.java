package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.easy1staking.cardano.comparator.TransactionInputComparator;
import com.easy1staking.cardano.comparator.UtxoComparator;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.util.Pair;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.KycTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.entity.GlobalStateInitEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.KycTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.repository.GlobalStateInitRepository;
import org.cardanofoundation.cip113.service.*;
import org.cardanofoundation.cip113.service.substandard.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.AddTrustedEntityRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.GlobalStateAction;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.GlobalStateInitRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.GlobalStateInitResult;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.RemoveTrustedEntityRequest;
import org.cardanofoundation.cip113.service.substandard.context.KycContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static java.math.BigInteger.ONE;

/**
 * Handler for the "kyc" programmable token substandard.
 *
 * <p>This handler supports tokens that require KYC attestation for transfers:</p>
 * <ul>
 *   <li><b>BasicOperations</b> - Register, mint, burn, transfer programmable tokens</li>
 * </ul>
 *
 * <p>During transfer, the sender must provide a valid KYC attestation (payload + signature)
 * from a trusted entity whose verification key is in the global state's Trusted Entity List.</p>
 *
 * <p>This handler requires a {@link KycContext} to be set before use for mint/transfer
 * operations, as there can be multiple KYC token deployments.</p>
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class KycSubstandardHandler implements SubstandardHandler, BasicOperations<KycRegisterRequest>, GlobalStateManageable {

    private static final String SUBSTANDARD_ID = "kyc";

    private final ObjectMapper objectMapper;
    private final AppConfig.Network network;
    private final RegistryNodeParser registryNodeParser;
    private final AccountService accountService;
    private final SubstandardService substandardService;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;
    private final KycScriptBuilderService kycScriptBuilder;
    private final LinkedListService linkedListService;
    private final QuickTxBuilder quickTxBuilder;
    private final HybridUtxoSupplier hybridUtxoSupplier;
    private final KycTokenRegistrationRepository kycTokenRegistrationRepository;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final GlobalStateInitRepository globalStateInitRepository;
    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    private final UtxoProvider utxoProvider;
    private final CardanoConverters cardanoConverters;

    @Setter
    private KycContext context;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    // ========== BasicOperations Implementation ==========

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(
            KycRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        // KYC uses a combined build flow; pre-registration not needed separately
        return TransactionContext.ok(null, List.of());
    }

    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            KycRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var adminPkh = Credential.fromKey(request.getAdminPubKeyHash());
            var globalStatePolicyId =request.getGlobalStatePolicyId();

            List<Utxo> feePayerUtxos;
            if (request.getChainingTransactionCborHex() != null) {
                var chainingTxBytes = HexUtil.decodeHexString(request.getChainingTransactionCborHex());
                var chainingTxHash = TransactionUtil.getTxHash(chainingTxBytes);
                var chainingTx = Transaction.deserialize(chainingTxBytes);

                var chainingTxOutputs = chainingTx.getBody().getOutputs();
                Utxo inputUtxo = null;
                for (int i = 0; i < chainingTxOutputs.size(); i++) {
                    var output = chainingTxOutputs.get(i);
                    if (output.getAddress().equals(request.getFeePayerAddress()) &&
                            output.getValue().getCoin().compareTo(BigInteger.valueOf(10_000_000L)) > 0) {
                        inputUtxo = Utxo.builder()
                                .address(output.getAddress())
                                .txHash(chainingTxHash)
                                .outputIndex(i)
                                .amount(ValueUtil.toAmountList(output.getValue()))
                                .build();
                    }
                }

                if (inputUtxo == null) {
                    return TransactionContext.typedError("could not chain tx");
                }

                feePayerUtxos = List.of(inputUtxo);
                feePayerUtxos.forEach(hybridUtxoSupplier::add);
            } else {
                feePayerUtxos = accountService.findAdaOnlyUtxo(request.getFeePayerAddress(), 10_000_000L);
            }

            var bootstrapTxHash = protocolParams.txHash();

            var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }
            var protocolParamsUtxo = protocolParamsUtxoOpt.get();

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());

            var issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();

            // Build KYC substandard scripts
            var substandardIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, Credential.fromKey(request.getAdminPubKeyHash()));
            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("KYC substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = kycScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    globalStatePolicyId
            );
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());
            log.info("KYC substandardTransferAddress: {}", substandardTransferAddress.getAddress());

            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, substandardIssueContract);
            final var progTokenPolicyId = issuanceContract.getPolicyId();

            var registryAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());
            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

            var nodeAlreadyPresent = linkedListService.nodeAlreadyPresent(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum()).map(RegistryNode::key));

            if (nodeAlreadyPresent) {
                return TransactionContext.typedError(String.format("Token policy %s already registered", progTokenPolicyId));
            }

            var nodeToReplaceOpt = linkedListService.findNodeToReplace(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                            .map(node -> new LinkedListNode(node.key(), node.next())));

            if (nodeToReplaceOpt.isEmpty()) {
                return TransactionContext.typedError("could not find node to replace");
            }

            var directoryUtxo = nodeToReplaceOpt.get();
            var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());
            if (existingRegistryNodeDatumOpt.isEmpty()) {
                return TransactionContext.typedError("could not parse current registry node");
            }
            var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

            // Directory MINT
            var directoryMintContract = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolParams);
            var directoryMintPolicyId = directoryMintContract.getPolicyId();

            var directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(substandardIssueContract.getScriptHash())
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

            var directoryMintDatum = new RegistryNode(
                    HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    existingRegistryNodeDatum.next(),
                    HexUtil.encodeHexString(substandardTransferContract.getScriptHash()),
                    HexUtil.encodeHexString(substandardIssueContract.getScriptHash()),
                    globalStatePolicyId);

            Value directoryMintValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintPolicyId)
                                    .assets(List.of(directoryMintNft))
                                    .build()
                    ))
                    .build();

            Value directorySpendValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(directoryMintPolicyId)
                                    .assets(List.of(directorySpendNft))
                                    .build()
                    ))
                    .build();

            // Registry node output is at index 2: [0] PLB, [1] updated covering node, [2] new registry node
            var issuanceRedeemer = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                    ConstrPlutusData.of(1, BigIntPlutusData.of(2)) // OutputIndex { index: 2 }
            );

            var programmableToken = Asset.builder()
                    .name("0x" + request.getAssetName())
                    .value(new BigInteger(request.getQuantity()))
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

            var payeeAddress = new Address(request.getRecipientAddress());
            var targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    payeeAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            // Log a warning if the issue script stake address does not appear registered yet.
            // The actual enforcement happens at node submission — if not registered, the node
            // returns incompleteWithdrawals. We do not hard-block here to avoid false negatives
            // caused by Yaci Store indexing delays after a fresh Global State init.
            var issueStakeAddress = substandardIssueAddress.getAddress();
            var issueStakeRegistered = stakeRegistrationRepository.findRegistrationsByStakeAddress(issueStakeAddress)
                    .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            if (!issueStakeRegistered) {
                log.warn("KYC issue script stake address {} not found in Yaci Store — it may not yet be indexed. " +
                        "Attempting to build the registration tx anyway; if submission fails with " +
                        "incompleteWithdrawals, wait for the Global State init tx to be confirmed and retry.",
                        issueStakeAddress);
            }

            var tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, ConstrPlutusData.of(0))
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum.toPlutusData())
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
                    .withChangeAddress(request.getFeePayerAddress());

            // Attach CIP-170 ATTEST metadata if attestation data is present
            if (request.getAttestation() != null) {
                var att = request.getAttestation();
                MetadataMap versionMap = MetadataBuilder.createMap();
                versionMap.put("v", att.cipVersion() != null ? att.cipVersion() : "1.0");

                MetadataMap cip170Map = MetadataBuilder.createMap();
                cip170Map.put("t", "ATTEST");
                cip170Map.put("i", att.signerAid());
                cip170Map.put("d", att.digest());
                cip170Map.put("s", att.seqNumber());
                cip170Map.put("v", versionMap);

                var metadata = MetadataBuilder.createMetadata();
                metadata.put(170L, cip170Map);
                tx.attachMetadata(metadata);
                log.info("CIP-170 ATTEST metadata attached to registration: signer={}, digest={}, seq={}",
                        att.signerAid(), att.digest(), att.seqNumber());
            }

            var firstUtxo = feePayerUtxos.getFirst();
            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh.getBytes())
                    .feePayer(request.getFeePayerAddress())
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtxo.getTxHash())
                            .index(firstUtxo.getOutputIndex())
                            .build())
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(request.getFeePayerAddress())) {
                            var first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.debug("KYC registration tx: {}", transaction.serializeToHex());

            // Save KYC-specific registration data
            // Look up the GlobalStateInitEntity for the global state policy ID
            var globalStateInitEntity =globalStateInitRepository.findByGlobalStatePolicyId(globalStatePolicyId)
                    .orElseThrow(() -> new RuntimeException("Global state init not found for policy ID: " + globalStatePolicyId));

            kycTokenRegistrationRepository.save(KycTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(progTokenPolicyId)
                    .issuerAdminPkh(HexUtil.encodeHexString(adminPkh.getBytes()))
                    .globalStateInit(globalStateInitEntity)
                    .build());

            // Save to unified programmable token registry
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .substandardId(SUBSTANDARD_ID)
                    .assetName(request.getAssetName())
                    .build());

            hybridUtxoSupplier.clear();

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

        } catch (Exception e) {
            log.error("KYC registration error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var adminUtxos = accountService.findAdaOnlyUtxo(request.feePayerAddress(), 10_000_000L);

            var issuanceUtxoOpt = utxoProvider.findUtxo(protocolParams.txHash(), 2);
            if (issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();

            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var substandardIssueContract = kycScriptBuilder.buildIssueScript(context.getGlobalStatePolicyId(), Credential.fromKey(context.getIssuerAdminPkh()));
            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());

            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, substandardIssueContract);

            // Subsequent mint (registry node already exists): include the registry node UTxO as a
            // reference input so issuance_mint.ak can verify the token via RefInput. The redeemer
            // is SmartTokenMintingAction { minting_logic_cred, minting_registry_proof: RefInput }.
            // Without the second field, evaluation fails with EmptyList(...) because the validator
            // tries to read `redeemer.minting_registry_proof` from a 1-element constr.
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                            .map(registryDatum -> registryDatum.key().equals(progTokenPolicyId)).orElse(false))
                    .findAny();
            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            var progTokenRegistry = progTokenRegistryOpt.get();
            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();
            var sortedReferenceInputs = Stream.of(registryRefInput)
                    .sorted(new TransactionInputComparator())
                    .toList();
            var registryRefInputIndex = sortedReferenceInputs.indexOf(registryRefInput);

            var issuanceRedeemer = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(1, BytesPlutusData.of(substandardIssueContract.getScriptHash())),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(registryRefInputIndex)) // RefInput { index }
            );

            var programmableToken = Asset.builder()
                    .name("0x" + request.assetName())
                    .value(new BigInteger(request.quantity()))
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

            var recipient = Optional.ofNullable(request.recipientAddress()).orElse(request.feePayerAddress());
            var recipientAddress = new Address(recipient);

            var targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, ConstrPlutusData.of(0))
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                    .readFrom(registryRefInput)
                    .attachRewardValidator(substandardIssueContract)
                    .withChangeAddress(request.feePayerAddress());

            // Attach CIP-170 ATTEST metadata if attestation data is present
            if (request.attestation() != null) {
                var att = request.attestation();
                MetadataMap versionMap = MetadataBuilder.createMap();
                versionMap.put("v", att.cipVersion() != null ? att.cipVersion() : "1.0");

                MetadataMap cip170Map = MetadataBuilder.createMap();
                cip170Map.put("t", "ATTEST");
                cip170Map.put("i", att.signerAid());
                cip170Map.put("d", att.digest());
                cip170Map.put("s", att.seqNumber());
                cip170Map.put("v", versionMap);

                var metadata = MetadataBuilder.createMetadata();
                metadata.put(170L, cip170Map);
                tx.attachMetadata(metadata);
                log.info("CIP-170 ATTEST metadata attached: signer={}, digest={}, seq={}",
                        att.signerAid(), att.digest(), att.seqNumber());
            }

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh.getBytes())
                    .feePayer(request.feePayerAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(request.feePayerAddress())) {
                            var first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                    })
                    .build();

            log.info("KYC mint tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("KYC mint error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildTransferTransaction(
            TransferTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var senderAddress = new Address(request.senderAddress());
            var receiverAddress = new Address(request.recipientAddress());
            var globalStatePolicyId =context.getGlobalStatePolicyId();

            // Validate KYC proof data is present
            if (request.kycPayload() == null || request.kycPayload().isBlank()) {
                return TransactionContext.typedError("KYC payload is required for transfer");
            }
            if (request.kycSignature() == null || request.kycSignature().isBlank()) {
                return TransactionContext.typedError("KYC signature is required for transfer");
            }

            var adminUtxos = accountService.findAdaOnlyUtxo(senderAddress.getAddress(), 10_000_000L);

            var progToken = AssetType.fromUnit(request.unit());
            var amountToTransfer = new BigInteger(request.quantity());

            // Registry lookup
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                            .map(registryDatum -> registryDatum.key().equals(progToken.policyId())).orElse(false))
                    .findAny();

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            var progTokenRegistry = progTokenRegistryOpt.get();

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(protocolParams.txHash(), 0);
            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }
            var protocolParamsUtxo = protocolParamsUtxoOpt.get();

            // Sender/recipient programmable token addresses
            var senderProgrammableTokenAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    senderAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var recipientProgrammableTokenAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    receiverAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var senderProgTokensUtxos = utxoProvider.findUtxos(senderProgrammableTokenAddress.getAddress());

            // Protocol scripts
            var programmableLogicGlobal = protocolScriptBuilderService.getParameterizedProgrammableLogicGlobalScript(protocolParams);
            var programmableLogicGlobalAddress = AddressProvider.getRewardAddress(programmableLogicGlobal, network.getCardanoNetwork());
            var programmableLogicBase = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(protocolParams);

            // KYC transfer script
            var parameterisedTransferContract = kycScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    globalStatePolicyId
            );
            var substandardTransferAddress = AddressProvider.getRewardAddress(parameterisedTransferContract, network.getCardanoNetwork());

            // Find global state UTxO for reference input (contains trusted entity list)
            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateSpendScript = globalStateScripts.second();
            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("could not find global state UTxO for global state");
            }

            // Select input UTxOs for the transfer
            var valueToSend = Value.from(progToken.policyId(), "0x" + progToken.assetName(), amountToTransfer);

            var inputUtxos = senderProgTokensUtxos.stream()
                    .reduce(new Pair<List<Utxo>, Value>(List.of(), Value.builder().build()),
                            (listValuePair, utxo) -> {
                                if (listValuePair.second().subtract(valueToSend).isPositive()) {
                                    return listValuePair;
                                } else {
                                    if (utxo.toValue().amountOf(progToken.policyId(), "0x" + progToken.assetName()).compareTo(BigInteger.ONE) > 0) {
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

            var senderProgTokensValue = inputUtxos.stream()
                    .map(Utxo::toValue)
                    .filter(value -> value.amountOf(progToken.policyId(), "0x" + progToken.assetName()).compareTo(BigInteger.ZERO) > 0)
                    .reduce(Value::add)
                    .orElse(Value.builder().build());

            var returningValue = senderProgTokensValue.subtract(valueToSend);

            var tokenAsset = Asset.builder()
                    .name("0x" + progToken.assetName())
                    .value(amountToTransfer)
                    .build();

            Value tokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(progToken.policyId())
                                    .assets(List.of(tokenAsset))
                                    .build()
                    ))
                    .build();

            var progTokenAmount = senderProgTokensValue.amountOf(progToken.policyId(), "0x" + progToken.assetName());
            if (progTokenAmount.compareTo(amountToTransfer) < 0) {
                return TransactionContext.typedError("Not enough funds");
            }

            // Sort inputs and build reference inputs
            var sortedInputUtxos = Stream.concat(adminUtxos.stream(), inputUtxos.stream())
                    .sorted(new UtxoComparator())
                    .toList();

            var globalStateRefInput = TransactionInput.builder()
                    .transactionId(globalStateUtxo.getTxHash())
                    .index(globalStateUtxo.getOutputIndex())
                    .build();

            var sortedReferenceInputs = Stream.of(
                            globalStateRefInput,
                            TransactionInput.builder()
                                    .transactionId(protocolParamsUtxo.getTxHash())
                                    .index(protocolParamsUtxo.getOutputIndex())
                                    .build(),
                            TransactionInput.builder()
                                    .transactionId(progTokenRegistry.getTxHash())
                                    .index(progTokenRegistry.getOutputIndex())
                                    .build())
                    .sorted(new TransactionInputComparator())
                    .toList();

            // Build KYC proof redeemer
            // One proof per programmable-base input (sender credential)
            var globalStateIdx = sortedReferenceInputs.indexOf(globalStateRefInput);
            var vkeyIdx = request.kycVkeyIndex() != null ? request.kycVkeyIndex() : 0;
            var progTokenBaseScriptHash = protocolParams.programmableLogicBaseParams().scriptHash();

            var kycProofList = ListPlutusData.of();
            for (Utxo utxo : sortedInputUtxos) {
                var address = new Address(utxo.getAddress());
                var addressPkh = address.getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
                if (progTokenBaseScriptHash.equals(addressPkh)) {
                    // KycProof: Constr 0 [global_state_idx, vkey_idx, payload, signature]
                    var proof = ConstrPlutusData.of(0,
                            BigIntPlutusData.of(globalStateIdx),
                            BigIntPlutusData.of(vkeyIdx),
                            BytesPlutusData.of(HexUtil.decodeHexString(request.kycPayload())),
                            BytesPlutusData.of(HexUtil.decodeHexString(request.kycSignature()))
                    );
                    kycProofList.add(proof);
                }
            }

            var registryIndex = sortedReferenceInputs.indexOf(TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build());

            var programmableGlobalRedeemer = ConstrPlutusData.of(0,
                    ListPlutusData.of(ConstrPlutusData.of(0, BigIntPlutusData.of(registryIndex)))
            );

            // Build the transaction
            var tx = new Tx()
                    .collectFrom(adminUtxos);

            inputUtxos.forEach(utxo -> tx.collectFrom(utxo, ConstrPlutusData.of(0)));

            tx.withdraw(substandardTransferAddress.getAddress(), BigInteger.ZERO, kycProofList)
                    .withdraw(programmableLogicGlobalAddress.getAddress(), BigInteger.ZERO, programmableGlobalRedeemer)
                    .payToContract(senderProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(returningValue), ConstrPlutusData.of(0))
                    .payToContract(recipientProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(tokenValue), ConstrPlutusData.of(0));

            sortedReferenceInputs.forEach(tx::readFrom);

            tx.attachRewardValidator(programmableLogicGlobal)
                    .attachRewardValidator(parameterisedTransferContract)
                    .attachSpendingValidator(programmableLogicBase)
                    .withChangeAddress(senderAddress.getAddress());

            // Set TTL to now + 15 minutes. The on-chain check is tx_upper_bound <= valid_until,
            // so any TTL before the proof's expiry works. We use a short window to avoid the
            // PastHorizon error (node can't compute slots far in the future).
            long ttlMs = System.currentTimeMillis() + 15 * 60 * 1000L;
            LocalDateTime ttlTime = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(ttlMs), ZoneOffset.UTC);
            long ttlSlot = cardanoConverters.time().toSlot(ttlTime);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(senderAddress.getDelegationCredentialHash().get())
                    .additionalSignersCount(1)
                    .feePayer(senderAddress.getAddress())
                    .mergeOutputs(false)
                    .validTo(ttlSlot)
                    .build();

            log.info("KYC transfer tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("KYC transfer error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    // ========== Global State Init / Entity Management ==========

    @Override
    public TransactionContext<GlobalStateInitResult> buildGlobalStateInitTransaction(
            GlobalStateInitRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            log.info("Global state init request: admin={}", request.adminAddress());

            var adminAddress = new Address(request.adminAddress());
            var adminPkhBytes = adminAddress.getPaymentCredentialHash().get();
            var adminPkh = HexUtil.encodeHexString(adminPkhBytes);

            var utilityUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);
            if (utilityUtxos.isEmpty()) {
                return TransactionContext.typedError("no UTxOs found for admin address");
            }

            var bootstrapUtxo = utilityUtxos.getFirst();
            var bootstrapTxInput = TransactionInput.builder()
                    .transactionId(bootstrapUtxo.getTxHash())
                    .index(bootstrapUtxo.getOutputIndex())
                    .build();

            // Build global state mint + spend scripts
            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(bootstrapTxInput, adminPkh);
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            log.info("Global state spend address: {}", globalStateSpendAddress.getAddress());

            // Initial datum: GlobalStateDatum {
            //   transfers_paused: False,
            //   mintable_amount: 0,
            //   trusted_entities: [initialVkey?],
            //   security_info: Constr(0, [])  (unit/void)
            // }
            var trustedEntities = ListPlutusData.of();
            if (request.initialVkeys() != null) {
                for (var vkeyHex : request.initialVkeys()) {
                    if (vkeyHex != null && vkeyHex.trim().length() == 64) {
                        trustedEntities.add(BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex.trim())));
                        log.info("Global state init adding trusted entity vkey: {}...{}", vkeyHex.substring(0, 8), vkeyHex.substring(56));
                    }
                }
            }
            // Build initial datum from request fields (with defaults)
            var paused = Boolean.TRUE.equals(request.initialTransfersPaused());
            var mintAmount = request.initialMintableAmount() != null ? request.initialMintableAmount() : 0L;
            PlutusData secInfoData = ConstrPlutusData.of(0); // default: unit
            if (request.initialSecurityInfo() != null && !request.initialSecurityInfo().isBlank()) {
                secInfoData = BytesPlutusData.of(HexUtil.decodeHexString(request.initialSecurityInfo()));
            }

            var initialDatum = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(paused ? 1 : 0),                       // transfers_paused
                    BigIntPlutusData.of(BigInteger.valueOf(mintAmount)),         // mintable_amount
                    trustedEntities,                                             // trusted_entities
                    secInfoData                                                  // security_info
            );

            // Global state NFT (token name = "GlobalState")
            var globalStateNft = Asset.builder()
                    .name("0x" + KycScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX)
                    .value(BigInteger.ONE)
                    .build();

            // Allocate generous lovelace headroom so future updates that grow the datum
            // (e.g. adding trusted entities) don't blow past the on-chain min-utxo. The
            // validator forbids changing lovelace for ModifyTrustedEntities/UpdateMintableAmount/
            // PauseTransfers (`global_state.ak:138`), so the input UTxO must already cover the
            // largest expected datum. ~5 ADA covers ~50 vkey additions.
            Value globalStateValue = Value.builder()
                    .coin(Amount.ada(5).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(globalStateMintScript.getPolicyId())
                                    .assets(List.of(globalStateNft))
                                    .build()
                    ))
                    .build();

            // Build issue and transfer scripts to register their stake addresses
            var adminCredential = Credential.fromKey(adminPkh);
            var globalStatePolicyId =globalStateMintScript.getPolicyId();
            var substandardIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, adminCredential);
            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());

            var substandardTransferContract = kycScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(), globalStatePolicyId);
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());

            // Register stake addresses for issue and transfer scripts
            var requiredStakeAddresses = Stream.of(substandardIssueAddress, substandardTransferAddress)
                    .map(Address::getAddress)
                    .toList();

            var registeredStakeAddresses = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> stakeRegistrationRepository.findRegistrationsByStakeAddress(stakeAddress)
                            .map(reg -> reg.getType().equals(CertificateType.STAKE_REGISTRATION)).orElse(false))
                    .toList();

            var stakeAddressesToRegister = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> !registeredStakeAddresses.contains(stakeAddress))
                    .toList();
            log.info("Stake addresses to register: {}", stakeAddressesToRegister);

            var tx = new Tx()
                    .collectFrom(utilityUtxos)
                    .mintAsset(globalStateMintScript, globalStateNft, ConstrPlutusData.of(0))
                    .payToContract(globalStateSpendAddress.getAddress(), ValueUtil.toAmountList(globalStateValue), initialDatum)
                    .withChangeAddress(request.adminAddress());

            stakeAddressesToRegister.forEach(tx::registerStakeAddress);

            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        // Ensure global state output is at index 0 (required by mint validator)
                        var outputs = transaction1.getBody().getOutputs();
                        if (!outputs.isEmpty() && outputs.getFirst().getAddress().equals(request.adminAddress())) {
                            var changeOutput = outputs.removeFirst();
                            outputs.addLast(changeOutput);
                        }
                    })
                    .build();

            log.info("Global state init tx: {}", transaction.serializeToHex());

            // Save Global state init entity (upsert — a previous failed attempt may have saved one already)
            var policyId = globalStateMintScript.getPolicyId();
            var existing = globalStateInitRepository.findByGlobalStatePolicyId(policyId);
            if (existing.isEmpty()) {
                // Remove any stale record with the same bootstrap UTxO (from a prior attempt
                // that produced a different policy ID, e.g. different output index selection)
                globalStateInitRepository.findByAdminPkh(adminPkh).stream()
                        .filter(e -> e.getTxHash().equals(bootstrapUtxo.getTxHash())
                                && e.getOutputIndex().equals(bootstrapUtxo.getOutputIndex()))
                        .forEach(globalStateInitRepository::delete);
                globalStateInitRepository.flush();

                globalStateInitRepository.save(GlobalStateInitEntity.builder()
                        .globalStatePolicyId(policyId)
                        .adminPkh(adminPkh)
                        .txHash(bootstrapUtxo.getTxHash())
                        .outputIndex(bootstrapUtxo.getOutputIndex())
                        .build());
            }

            return TransactionContext.ok(transaction.serializeToHex(),
                    new GlobalStateInitResult(globalStateMintScript.getPolicyId()));

        } catch (Exception e) {
            log.error("Global state init error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildAddTrustedEntityTransaction(
            AddTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            log.info("Global state add entity: policyId={}, vkey={}", request.policyId(), request.verificationKey());

            var vkeyHex = request.verificationKey().trim();
            if (vkeyHex.length() != 64) {
                return TransactionContext.typedError("Verification key must be 64 hex characters (32 bytes)");
            }

            // Rebuild global state scripts from context
            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            // Find the global state UTxO
            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            // Parse current datum to get existing trusted entity list
            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);
            var currentEntities = parseTrustedEntitiesFromDatum(currentDatum);

            // Append new vkey to list
            var updatedEntities = ListPlutusData.of();
            currentEntities.getPlutusDataList().forEach(updatedEntities::add);
            updatedEntities.add(BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex)));

            var updatedDatum = buildGlobalStateDatumWithEntities(currentDatum, updatedEntities);

            // Build typed redeemer: GlobalStateSpendRedeemer { global_state_output_index: 0, action: ModifyTrustedEntities { new_trusted_entities } }
            var modifyAction = ConstrPlutusData.of(3, updatedEntities); // ModifyTrustedEntities = constructor index 3
            var spendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),  // global_state_output_index = 0
                    modifyAction
            );

            // Find admin UTxOs for fee payment
            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);

            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr,
                            globalStateUtxo.getAmount(), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            // Register the global state UTxO in the hybrid supplier so the Aiken evaluator
            // can resolve it with full inline datum data during script cost evaluation
            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .build();

            log.info("Global state add entity tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("Global state add entity error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildRemoveTrustedEntityTransaction(
            RemoveTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            log.info("Global state remove entity: policyId={}, vkey={}", request.policyId(), request.verificationKey());

            var vkeyHex = request.verificationKey().trim();

            // Rebuild global state scripts from context
            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            // Find the global state UTxO
            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            // Parse current datum and remove the vkey
            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);
            var currentEntities = parseTrustedEntitiesFromDatum(currentDatum);

            var targetBytes = BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex));
            var updatedEntities = ListPlutusData.of();
            boolean found = false;
            for (var entity : currentEntities.getPlutusDataList()) {
                if (entity.equals(targetBytes)) {
                    found = true;
                } else {
                    updatedEntities.add(entity);
                }
            }

            if (!found) {
                return TransactionContext.typedError("Verification key not found in trusted entity list");
            }

            var updatedDatum = buildGlobalStateDatumWithEntities(currentDatum, updatedEntities);

            // Build typed redeemer: GlobalStateSpendRedeemer { global_state_output_index: 0, action: ModifyTrustedEntities { new_trusted_entities } }
            var modifyAction = ConstrPlutusData.of(3, updatedEntities); // ModifyTrustedEntities = constructor index 3
            var spendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),  // global_state_output_index = 0
                    modifyAction
            );

            // Find admin UTxOs for fee payment
            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);

            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr,
                            globalStateUtxo.getAmount(), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            // Register the global state UTxO in the hybrid supplier so the Aiken evaluator
            // can resolve it with full inline datum data during script cost evaluation
            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .build();

            log.info("Global state remove entity tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("Global state remove entity error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    // ========== GlobalStateManageable Implementation ==========

    @Override
    public TransactionContext<Void> buildGlobalStateUpdateTransaction(
            GlobalStateUpdateRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            log.info("Global state update: policyId={}, action={}", request.policyId(), request.action());

            // Rebuild global state scripts from context
            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            // Find the global state UTxO
            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);

            // Build updated datum and typed redeemer based on action
            ConstrPlutusData updatedDatum;
            ConstrPlutusData spendRedeemer;

            switch (request.action()) {
                case PAUSE_TRANSFERS -> {
                    var paused = Boolean.TRUE.equals(request.transfersPaused());
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 0,
                            ConstrPlutusData.of(paused ? 1 : 0)); // True = Constr 1, False = Constr 0
                    // PauseTransfers = constructor index 1
                    var action = ConstrPlutusData.of(1, ConstrPlutusData.of(paused ? 1 : 0));
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                case UPDATE_MINTABLE_AMOUNT -> {
                    var amount = request.mintableAmount() != null ? request.mintableAmount() : 0L;
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 1,
                            BigIntPlutusData.of(BigInteger.valueOf(amount)));
                    // UpdateMintableAmount = constructor index 0
                    var action = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(amount)));
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                case MODIFY_SECURITY_INFO -> {
                    var infoData = request.securityInfo() != null && !request.securityInfo().isBlank()
                            ? BytesPlutusData.of(HexUtil.decodeHexString(request.securityInfo()))
                            : ConstrPlutusData.of(0); // unit/void if empty
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 3, infoData);
                    // ModifySecurityInfo = constructor index 2
                    var action = ConstrPlutusData.of(2, infoData);
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                case MODIFY_TRUSTED_ENTITIES -> {
                    var newEntities = ListPlutusData.of();
                    if (request.trustedEntities() != null) {
                        for (var vkey : request.trustedEntities()) {
                            newEntities.add(BytesPlutusData.of(HexUtil.decodeHexString(vkey.trim())));
                        }
                    }
                    updatedDatum = buildGlobalStateDatumWithEntities(currentDatum, newEntities);
                    // ModifyTrustedEntities = constructor index 3
                    var action = ConstrPlutusData.of(3, newEntities);
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                default -> {
                    return TransactionContext.typedError("Unknown global state action: " + request.action());
                }
            }

            // Find admin UTxOs for fee payment
            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);

            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            // ModifySecurityInfo is the only action whose validator allows the lovelace to
            // change (`without_lovelace` check at global_state.ak:118-119). All other actions
            // require `output.value == input.value` exactly. We use ModifySecurityInfo as the
            // top-up channel: when invoked we pad the output to a generous lovelace target so
            // the global state UTxO has headroom for subsequent datum-growing updates.
            final long topUpTargetLovelace = Amount.ada(5).getQuantity().longValue();
            final boolean isTopUpAction = request.action() == GlobalStateAction.MODIFY_SECURITY_INFO;
            final Value outputValueAtEval = isTopUpAction
                    ? bumpLovelace(inputValue, topUpTargetLovelace)
                    : inputValue;

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr,
                            ValueUtil.toAmountList(outputValueAtEval), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            // Register the global state UTxO in the hybrid supplier so the Aiken evaluator
            // can resolve it with full inline datum data during script cost evaluation
            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        // Restore BEFORE script-cost eval too. The validator's value-equality
                        // check runs at eval time (before balance + before postBalanceTx in
                        // QuickTxBuilder 0.8.0-pre3), so post-only restoration is too late.
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, outputValueAtEval);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) -> {
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, outputValueAtEval);
                    })
                    .build();

            log.info("Global state update tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("Global state update error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    // ========== Read Global State ==========

    /**
     * Read the current on-chain global state for a KYC token.
     * Returns parsed datum fields (transfers_paused, mintable_amount, trusted_entities, security_info).
     */
    public Optional<GlobalStateData> readGlobalState(String policyId) {
        try {
            var kycDataOpt = kycTokenRegistrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (kycDataOpt.isEmpty()) return Optional.empty();

            var kycData = kycDataOpt.get();
            var gsInit = kycData.getGlobalStateInit();
            var globalStateInitTxInput = TransactionInput.builder()
                    .transactionId(gsInit.getTxHash())
                    .index(gsInit.getOutputIndex())
                    .build();

            var globalStateScripts = kycScriptBuilder.buildGlobalStateScripts(
                    globalStateInitTxInput, kycData.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) return Optional.empty();

            var constr = deserializeGlobalStateDatum(globalStateUtxo);
            var fields = constr.getData().getPlutusDataList();
            if (fields.size() < 4) return Optional.empty();

            // Parse transfers_paused (Bool = Constr 0 = False, Constr 1 = True)
            boolean transfersPaused = false;
            if (fields.get(0) instanceof ConstrPlutusData boolConstr) {
                transfersPaused = boolConstr.getAlternative() == 1;
            }

            // Parse mintable_amount
            long mintableAmount = 0;
            if (fields.get(1) instanceof BigIntPlutusData bigInt) {
                mintableAmount = bigInt.getValue().longValueExact();
            }

            // Parse trusted_entities
            var trustedEntities = new java.util.ArrayList<String>();
            if (fields.get(2) instanceof ListPlutusData list) {
                for (var item : list.getPlutusDataList()) {
                    if (item instanceof BytesPlutusData bytes) {
                        trustedEntities.add(HexUtil.encodeHexString(bytes.getValue()));
                    }
                }
            }

            // Parse security_info
            String securityInfo = null;
            if (fields.get(3) instanceof BytesPlutusData bytes) {
                securityInfo = HexUtil.encodeHexString(bytes.getValue());
            }
            // else Constr 0 = unit/void → null

            return Optional.of(new GlobalStateData(
                    policyId, transfersPaused, mintableAmount, trustedEntities, securityInfo));

        } catch (Exception e) {
            log.error("Error reading global state for policyId={}", policyId, e);
            return Optional.empty();
        }
    }

    /**
     * Parsed global state data from the on-chain UTxO.
     */
    public record GlobalStateData(
            String policyId,
            boolean transfersPaused,
            long mintableAmount,
            java.util.List<String> trustedEntities,
            String securityInfo
    ) {}

    // ========== Private Helpers ==========

    /**
     * Find the global state UTxO at the given address carrying the global state NFT.
     */
    private Utxo findGlobalStateUtxo(String spendAddress, String globalStatePolicyId) {
        var utxos = utxoProvider.findUtxos(spendAddress);
        return utxos.stream()
                .filter(utxo -> utxo.getAmount().stream()
                        .anyMatch(amount -> amount.getUnit().startsWith(globalStatePolicyId)))
                .findFirst()
                .orElse(null);
    }

    /**
     * Deserialize the inline datum CBOR hex string from a UTxO into a ConstrPlutusData.
     * Blockfrost returns datum as a CBOR hex string; this must be deserialized before use.
     */
    private ConstrPlutusData deserializeGlobalStateDatum(Utxo utxo) {
        var cborHex = utxo.getInlineDatum();
        if (cborHex == null || cborHex.isBlank()) {
            throw new IllegalStateException("Global state UTxO has no inline datum");
        }
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(cborHex));
            if (data instanceof ConstrPlutusData constr) {
                return constr;
            }
            throw new IllegalStateException("Global state datum is not a ConstrPlutusData: " + data.getClass().getSimpleName());
        } catch (com.bloxbean.cardano.client.exception.CborDeserializationException e) {
            throw new IllegalStateException("Failed to deserialize global state datum CBOR", e);
        }
    }

    /**
     * Parse the trusted entities list from a global state UTxO datum.
     * Datum format: Constr 0 [Bool, Int, List [ByteArray, ...], Data]
     * (transfers_paused, mintable_amount, trusted_entities, security_info)
     */
    private ListPlutusData parseTrustedEntitiesFromDatum(ConstrPlutusData constr) {
        var fields = constr.getData().getPlutusDataList();
        // trusted_entities is the 3rd field (index 2)
        if (fields.size() >= 3 && fields.get(2) instanceof ListPlutusData list) {
            return list;
        }
        return ListPlutusData.of();
    }

    /**
     * Ensure the global state output is at index 0 in the transaction.
     * The redeemer references global_state_output_index = 0, so if the change
     * output ended up at index 0 (before the contract output), we swap them.
     */
    private void ensureGlobalStateOutputFirst(Transaction transaction, String changeAddress) {
        var outputs = transaction.getBody().getOutputs();
        if (!outputs.isEmpty() && outputs.getFirst().getAddress().equals(changeAddress)) {
            var changeOutput = outputs.removeFirst();
            outputs.addLast(changeOutput);
        }
    }

    /**
     * Return a Value with at least {@code targetLovelace} lovelace, preserving multi-assets.
     * Used by ModifySecurityInfo to top up the global state UTxO when the existing lovelace
     * is below the headroom needed for future datum-growing updates.
     */
    private Value bumpLovelace(Value source, long targetLovelace) {
        var current = source.getCoin() == null ? BigInteger.ZERO : source.getCoin();
        var target = BigInteger.valueOf(targetLovelace);
        var coin = current.compareTo(target) >= 0 ? current : target;
        return Value.builder()
                .coin(coin)
                .multiAssets(source.getMultiAssets())
                .build();
    }

    /**
     * Restore the global state output's value to exactly match the desired value.
     * The on-chain validator requires own_input.output.value == global_state_output.value (exact match)
     * for most actions; ModifySecurityInfo allows lovelace differences but requires multi-assets to match.
     * QuickTxBuilder may adjust lovelace during balancing to meet min-UTxO requirements,
     * which would cause the validator to reject the tx. This method enforces the desired value.
     */
    private void restoreGlobalStateOutputValue(Transaction transaction, String scriptAddress, Value inputValue) {
        var outputs = transaction.getBody().getOutputs();
        for (var output : outputs) {
            if (output.getAddress().equals(scriptAddress)) {
                log.debug("Restoring global state output value: coin={} -> {}",
                        output.getValue().getCoin(), inputValue.getCoin());
                output.setValue(inputValue);
                return;
            }
        }
        log.warn("Could not find global state output at address {} to restore value", scriptAddress);
    }

    /**
     * Build a new GlobalStateDatum with a single field replaced at the given index.
     * Datum format: Constr 0 [Bool, Int, List [ByteArray, ...], Data]
     * Indices: 0=transfers_paused, 1=mintable_amount, 2=trusted_entities, 3=security_info
     */
    private ConstrPlutusData buildGlobalStateDatumWithField(ConstrPlutusData currentDatum, int fieldIndex, PlutusData newValue) {
        var fields = currentDatum.getData().getPlutusDataList();
        if (fields.size() >= 4) {
            var f0 = fieldIndex == 0 ? newValue : fields.get(0);
            var f1 = fieldIndex == 1 ? newValue : fields.get(1);
            var f2 = fieldIndex == 2 ? newValue : fields.get(2);
            var f3 = fieldIndex == 3 ? newValue : fields.get(3);
            return ConstrPlutusData.of(0, f0, f1, f2, f3);
        }
        throw new IllegalStateException("Global state datum has fewer than 4 fields: " + fields.size());
    }

    /**
     * Build a new GlobalStateDatum with updated trusted entities, preserving all other fields.
     * Datum format: Constr 0 [Bool, Int, List [ByteArray, ...], Data]
     */
    private ConstrPlutusData buildGlobalStateDatumWithEntities(ConstrPlutusData currentDatum, ListPlutusData newEntities) {
        var fields = currentDatum.getData().getPlutusDataList();
        if (fields.size() >= 4) {
            return ConstrPlutusData.of(0,
                    fields.get(0),  // transfers_paused
                    fields.get(1),  // mintable_amount
                    newEntities,    // updated trusted_entities
                    fields.get(3)   // security_info
            );
        }
        throw new IllegalStateException("Global state datum has fewer than 4 fields: " + fields.size());
    }
}
