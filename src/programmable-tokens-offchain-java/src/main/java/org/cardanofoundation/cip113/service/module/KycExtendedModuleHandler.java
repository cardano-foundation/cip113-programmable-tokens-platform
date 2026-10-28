package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenCredential;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenPolicyRef;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenRegistration;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenTx;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113ProgrammableTokenService;
import com.bloxbean.cardano.client.programmabletoken.cip113.LedgerOrdering;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.cardanofoundation.cip113.ccl.CclDeployments;
import org.cardanofoundation.cip113.ccl.CclQuickTx;
import org.cardanofoundation.cip113.ccl.CclWallets;
import org.cardanofoundation.cip113.ccl.SubstandardRedeemerExtension;
import org.cardanofoundation.cip113.model.BurnTokenRequest;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import com.easy1staking.cardano.comparator.UtxoComparator;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.util.Pair;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreDatums;
import org.cardanofoundation.cip113.core.CoreLayout;
import org.cardanofoundation.cip113.core.CoreRedeemers;
import org.cardanofoundation.cip113.core.CoreWithdrawal;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.GlobalStateInitEntity;
import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.PlutusCredentialCodec;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.GlobalStateInitRepository;
import org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.*;
import org.cardanofoundation.cip113.service.module.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable;
import org.cardanofoundation.cip113.service.module.context.KycExtendedContext;
import org.cardanofoundation.conversions.CardanoConverters;
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
 * Handler for the "kyc-extended" programmable token module.
 *
 * Mirrors {@link KycModuleHandler} but:
 *   - Global state datum carries a 5th field {@code member_root_hash}.
 *   - Transfer redeemer is {@code KycExtendedTransferRedeemer = Constr 0 [global_state_idx, sender_proofs, receiver_proofs]}
 *     where each sender proof is tagged Attestation (Constr 0) or Membership (Constr 1),
 *     and each receiver proof is a {@code MembershipProof}.
 *   - Adds {@code buildUpdateMemberRootHashTransaction} (constructor index 4 of GlobalStateSpendAction).
 *
 * @see org.cardanofoundation.cip113.service.MpfTreeService
 */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class KycExtendedModuleHandler implements ModuleHandler, BasicOperations<KycExtendedRegisterRequest>, GlobalStateManageable {

    private static final String MODULE_ID = "kyc-extended";

    /** The kyc-extended issue logic's redeemer. */
    private static final PlutusData ISSUE = ConstrPlutusData.of(0);

    /** The inline datum the platform writes on every holding ({@code Constr 0 []}). */
    private static final PlutusData HOLDING = CoreDatums.programmableTokenDatum();

    /** The name the registration and its first mint refer to each other by, within one transaction. */
    private static final String REGISTRATION = "token";

    /** Hex of an empty (32-byte) MPF root: 32 zero bytes. Initial datum value at registration. */
    private static final String EMPTY_ROOT_HEX = "0000000000000000000000000000000000000000000000000000000000000000";

    private final ObjectMapper objectMapper;
    private final AppConfig.Network network;
    private final RegistryNodeParser registryNodeParser;
    private final AccountService accountService;
    private final ModuleService moduleService;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;
    private final KycExtendedScriptBuilderService kycExtendedScriptBuilder;
    private final LinkedListService linkedListService;
    private final QuickTxBuilder quickTxBuilder;
    private final HybridUtxoSupplier hybridUtxoSupplier;
    private final KycExtendedTokenRegistrationRepository kycExtendedTokenRegistrationRepository;
    private final KycExtendedMemberLeafRepository kycExtendedMemberLeafRepository;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final GlobalStateInitRepository globalStateInitRepository;
    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    private final UtxoProvider utxoProvider;
    private final CardanoConverters cardanoConverters;
    private final MpfTreeService mpfTreeService;

    /** CCL's programmable-token service for the deployment each request names. */
    private final CclDeployments cclDeployments;

    /** A fresh CCL-extended builder per transaction, on the platform's suppliers. */
    private final CclQuickTx cclQuickTx;

    /**
     * How far ahead a transfer's upper validity bound lies at most. It must stay inside the node's
     * time-translation horizon (3k/f slots): 1.5 days on public networks, 5 minutes on a default
     * Yaci devnet.
     */
    @Setter
    @org.springframework.beans.factory.annotation.Value("${cip113.kyc.transfer-validity-ms:900000}")
    private long transferValidityMs = 15 * 60 * 1000L;

    @Setter
    private KycExtendedContext context;

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    // ========== BasicOperations Implementation ==========

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(
            KycExtendedRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        // kyc-extended bundles the module stake-credential registration into
        // the GS-init tx (see buildGlobalStateInitTransaction). The wizard doesn't
        // call a separate pre-registration step for this module.
        return TransactionContext.ok(null, List.of());
    }

    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            KycExtendedRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        // CIP-68 is not implemented for this module. Refuse rather than drop it: the wizard's
        // shared token-details step collects the metadata for every flow, and accepting it here
        // would mint a token whose (222)/(333) label advertises metadata that was never written.
        if (request.getCip68Metadata() != null) {
            return TransactionContext.typedError(
                    "CIP-68 is not supported for the 'kyc-extended' module. Supported: dummy, "
                    + "freeze-and-seize, rwa-token. Register without CIP-68 metadata.");
        }

        // CCL gap #17. A registration chained onto a global-state init that is not on chain yet
        // must spend that init's change output. CCL selects the fee payer's inputs itself and
        // cannot be pointed at a pending output, so the chained flow keeps its hand-built
        // transaction.
        if (request.getChainingTransactionCborHex() != null) {
            return buildChainedRegistrationTransaction(request, protocolParams);
        }

        try {
            var adminPkh = Credential.fromKey(request.getAdminPubKeyHash());
            var globalStatePolicyId = request.getGlobalStatePolicyId();

            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var moduleIssueContract = kycExtendedScriptBuilder.buildIssueScript(globalStatePolicyId, adminPkh);
            var moduleTransferContract = kycExtendedScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBase().scriptHash(), globalStatePolicyId);
            CclDeployments.registerWitnesses(service, moduleIssueContract, moduleTransferContract);
            var progTokenPolicyId = policyIdOf(service, moduleIssueContract, protocolParams);

            var existing = service.advanced().getRegistryNode(progTokenPolicyId);
            if (existing.isSuccessful() && existing.getValue() != null) {
                return TransactionContext.typedError(String.format("Token policy %s already registered", progTokenPolicyId));
            }

            var globalState = globalStateUtxo(globalStatePolicyId);
            if (globalState == null) {
                return TransactionContext.typedError("could not find global state UTxO for global state");
            }

            // No seize authority: the third-party slot is pinned to the protocol's always-fail
            // script, and unfracking is forbidden. The registry node names the global state.
            var registration = ProgrammableTokenRegistration.builder()
                    .mintingLogicScript(scriptCredential(moduleIssueContract))
                    .transferLogicScript(scriptCredential(moduleTransferContract))
                    .thirdPartyTransferLogicScript(ProgrammableTokenCredential.from(
                            Credential.fromScript(protocolParams.issuance().alwaysFailScriptHash())))
                    .unfrackingLogicScript(new ProgrammableTokenCredential(CredentialType.Key, ""))
                    .globalStateCs(globalStatePolicyId)
                    .build();
            var recipient = request.getRecipientAddress() == null || request.getRecipientAddress().isBlank()
                    ? request.getFeePayerAddress()
                    : request.getRecipientAddress();
            var tx = new ProgrammableTokenTx()
                    .from(request.getFeePayerAddress())
                    .register(REGISTRATION, registration, ISSUE)
                    .mint(ProgrammableTokenPolicyRef.named(REGISTRATION),
                            CclWallets.smartWallet(deployment, recipient).toBech32(),
                            List.of(asset(request.getAssetName(), new BigInteger(request.getQuantity()))),
                            ISSUE, HOLDING);
            // The handler supplies the global state itself rather than leaving CCL to find it
            // through an asset index the indexer may not offer.
            tx.readFrom(globalState);
            // CIP-170 attestation, when the wizard collected one.
            MintAttestationMetadata.attach(tx, request.getAttestation());

            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(request.getFeePayerAddress())
                    .withRequiredSigners(adminPkh.getBytes())
                    .mergeOutputs(false)
                    .build();

            kycExtendedTokenRegistrationRepository.save(KycExtendedTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(progTokenPolicyId)
                    .issuerAdminPkh(HexUtil.encodeHexString(adminPkh.getBytes()))
                    .telPolicyId(globalStatePolicyId)
                    .memberRootHashOnchain(EMPTY_ROOT_HEX)
                    .memberRootHashLocal(EMPTY_ROOT_HEX)
                    .build());

            // Save to unified programmable token registry (policyId -> moduleId binding)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .moduleId(MODULE_ID)
                    .assetName(request.getAssetName())
                    .build());

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

        } catch (Exception e) {
            log.warn("kyc-extended registration error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /**
     * The chained registration, built by hand: CCL gap #17 (see buildRegistrationTransaction).
     */
    private TransactionContext<RegistrationResult> buildChainedRegistrationTransaction(
            KycExtendedRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            // CIP-68 is not implemented for this module. Refuse rather than drop it: the
            // wizard's shared token-details step collects the metadata for every flow, and
            // accepting it here would mint a token whose (222)/(333) label advertises metadata
            // that was never written — the exact failure this refusal exists to prevent.
            if (request.getCip68Metadata() != null) {
                return TransactionContext.typedError(
                        "CIP-68 is not supported for the 'kyc-extended' module. Supported: "
                        + "dummy, freeze-and-seize, rwa-token. Register without CIP-68 metadata.");
            }
            var adminPkh = Credential.fromKey(request.getAdminPubKeyHash());
            var globalStatePolicyId = request.getGlobalStatePolicyId();

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

            var directorySpendContract = protocolScriptBuilderService.getParameterizedRegistryScript(protocolParams);

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(protocolParams.protocolParams().utxo().txHash(), protocolParams.protocolParams().utxo().outputIndex());
            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }
            var protocolParamsUtxo = protocolParamsUtxoOpt.get();

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());

            var issuanceUtxoOpt = utxoProvider.findIssuanceCborHexUtxo(protocolParams);
            if (issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();

            var moduleIssueContract = kycExtendedScriptBuilder.buildIssueScript(globalStatePolicyId, Credential.fromKey(request.getAdminPubKeyHash()));
            var moduleIssueAddress = AddressProvider.getRewardAddress(moduleIssueContract, network.getCardanoNetwork());
            log.info("kyc-extended moduleIssueAddress: {}", moduleIssueAddress.getAddress());

            var moduleTransferContract = kycExtendedScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBase().scriptHash(),
                    globalStatePolicyId
            );
            var moduleTransferAddress = AddressProvider.getRewardAddress(moduleTransferContract, network.getCardanoNetwork());
            log.info("kyc-extended moduleTransferAddress: {}", moduleTransferAddress.getAddress());

            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, moduleIssueContract);
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

            var directoryMintContract = protocolScriptBuilderService.getParameterizedRegistryScript(protocolParams);
            var directoryMintPolicyId = directoryMintContract.getPolicyId();

            // types.RegistryInsert { key: ByteArray, minting_logic_script: Credential }.
            // v0.4.0: the 2nd field is a Credential, not a bare hash — Script(hash) is Constr 1 [bytes].
            var directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    ConstrPlutusData.of(1, BytesPlutusData.of(moduleIssueContract.getScriptHash()))
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

            // third_party_transfer_logic_script (index 4): the `kyc-extended` module declares
            // NO third-party/force-transfer validator.
            // src/modules/kyc-extended/validators/kyc_extended_transfer.ak has exactly two
            // validators — `issue` (issuer-admin gate on *issuance*) and `transfer` (KYC
            // attestation / MPF-allowlist gated). Reusing the issuer-admin credential here would
            // silently grant the issuer power to take tokens from any holder, which nothing in the
            // module expresses; that is a product decision, not a port. The slot cannot be
            // empty either (linked_list.is_28_byte_credential rejects a zero-length credential on
            // a non-origin node), so it is pinned to the protocol's always_fail script:
            // programmable_logic_global.validate_3rd_party requires a *withdrawal* against this
            // credential and always_fail's `else` handler fails unconditionally, so seizure is
            // structurally impossible. The field stays mutable via the registry-node update path
            // (linked_list.is_field_updated_registry_node) if a real authority is chosen later.
            // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN — least
            // permission by default, and no kyc-extended validator declares an unfracking hook.
            var directoryMintDatum = new RegistryNode(
                    HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    existingRegistryNodeDatum.next(),
                    Credential.fromScript(moduleIssueContract.getScriptHash()),
                    Credential.fromScript(moduleTransferContract.getScriptHash()),
                    Credential.fromScript(protocolParams.issuance().alwaysFailScriptHash()),
                    RegistryNode.EMPTY_VKEY,
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

            // issuance_mint's redeemer IS types.MintingRegistryProof in v0.4.0 — the old
            // SmartTokenMintingAction { minting_logic_cred, minting_registry_proof } wrapper is
            // gone (the credential is now the validator's compile-time parameter).
            // Registry node output is at index 2: [0] PLB, [1] updated covering node, [2] new node.
            var paramsRefInput = TransactionInput.builder()
                    .transactionId(protocolParamsUtxo.getTxHash()).index(protocolParamsUtxo.getOutputIndex()).build();
            var issuanceCborRefInput = TransactionInput.builder()
                    .transactionId(issuanceUtxo.getTxHash()).index(issuanceUtxo.getOutputIndex()).build();
            var issuanceLogic = protocolScriptBuilderService.getParameterizedIssuanceLogicScript(protocolParams);
            var issuanceLogicCredential = Credential.fromScript(issuanceLogic.getScriptHash());
            var issuanceLogicAddress = AddressProvider.getRewardAddress(issuanceLogic, network.getCardanoNetwork());
            var moduleIssueCredential = Credential.fromScript(moduleIssueContract.getScriptHash());
            var issuanceLayout = CoreLayout.builder()
                    .referenceInput(paramsRefInput).referenceInput(issuanceCborRefInput)
                    .withdrawal(issuanceLogicCredential).withdrawal(moduleIssueCredential).build();
            var registryProof = CoreRedeemers.mintProofOutputIndex(2);
            var issuanceRedeemer = CoreRedeemers.issuanceRedeemer(issuanceLayout.referenceInputIndex(paramsRefInput));
            var issuanceLogicRedeemer = CoreRedeemers.issuanceLogicRedeemer(List.of(
                    new CoreRedeemers.IssuanceEntry(issuanceContract.getPolicyId(), registryProof)));

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
                    Credential.fromScript(protocolParams.programmableLogicBase().scriptHash()),
                    payeeAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var issueStakeAddress = moduleIssueAddress.getAddress();
            var issueStakeRegistered = stakeRegistrationRepository.findRegistrationsByStakeAddress(issueStakeAddress)
                    .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            if (!issueStakeRegistered) {
                log.warn("kyc-extended issue script stake address {} not found in Yaci Store — it may not yet be indexed.",
                        issueStakeAddress);
            }

            var tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .collectFrom(directoryUtxo, CoreRedeemers.registrySpend())
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue), directorySpendDatum.toPlutusData())
                    .payToContract(directorySpendContractAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue), directoryMintDatum.toPlutusData())
                    .readFrom(issuanceLayout.referenceInputs().toArray(new TransactionInput[0]))
                    .attachSpendingValidator(directorySpendContract)
                    .attachRewardValidator(issuanceLogic)
                    .attachRewardValidator(moduleIssueContract)
                    .withChangeAddress(request.getFeePayerAddress());

            for (var withdrawal : issuanceLayout.inWithdrawalOrder(List.of(
                    new CoreWithdrawal(moduleIssueCredential, moduleIssueAddress.getAddress(), ConstrPlutusData.of(0)),
                    new CoreWithdrawal(issuanceLogicCredential, issuanceLogicAddress.getAddress(), issuanceLogicRedeemer)),
                    CoreWithdrawal::credential)) {
                tx.withdraw(withdrawal.rewardAddress(), BigInteger.ZERO, withdrawal.redeemer());
            }

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

            kycExtendedTokenRegistrationRepository.save(KycExtendedTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(progTokenPolicyId)
                    .issuerAdminPkh(HexUtil.encodeHexString(adminPkh.getBytes()))
                    .telPolicyId(globalStatePolicyId)
                    .memberRootHashOnchain(EMPTY_ROOT_HEX)
                    .memberRootHashLocal(EMPTY_ROOT_HEX)
                    .build());

            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .moduleId(MODULE_ID)
                    .assetName(request.getAssetName())
                    .build());

            hybridUtxoSupplier.clear();

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

        } catch (Exception e) {
            log.error("kyc-extended registration error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            // Same refusal as registration: accepting cip68Metadata here would mint a user token
            // with no (100) reference token behind it.
            if (request.cip68Metadata() != null) {
                return TransactionContext.typedError(
                        "CIP-68 is not supported for the 'kyc-extended' module. Supported: dummy, "
                        + "freeze-and-seize, rwa-token. Mint without CIP-68 metadata.");
            }

            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var globalStatePolicyId = context.getGlobalStatePolicyId();
            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var moduleIssueContract = kycExtendedScriptBuilder.buildIssueScript(globalStatePolicyId, adminPkh);
            CclDeployments.registerWitnesses(service, moduleIssueContract);
            var progTokenPolicyId = policyIdOf(service, moduleIssueContract, protocolParams);

            // The issue logic is parameterised by (global state, admin), so the context decides the
            // policy minted under. Bind it to the policy the request names.
            if (request.tokenPolicyId() != null && !progTokenPolicyId.equalsIgnoreCase(request.tokenPolicyId())) {
                return TransactionContext.typedError("issue logic for this global state and admin derives policy "
                        + progTokenPolicyId + ", but the request names " + request.tokenPolicyId());
            }
            var registryNode = service.advanced().getRegistryNode(progTokenPolicyId);
            if (!registryNode.isSuccessful() || registryNode.getValue() == null) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            var globalState = globalStateUtxo(globalStatePolicyId);
            if (globalState == null) {
                return TransactionContext.typedError("could not find global state UTxO for global state");
            }

            var recipient = request.recipientAddress() == null ? request.feePayerAddress() : request.recipientAddress();
            var tx = new ProgrammableTokenTx()
                    .from(request.feePayerAddress())
                    .mint(progTokenPolicyId, CclWallets.smartWallet(deployment, recipient).toBech32(),
                            List.of(asset(request.assetName(), new BigInteger(request.quantity()))),
                            ISSUE, HOLDING);
            tx.readFrom(globalState);
            // CIP-170 attestation, when the caller supplied an approved one.
            MintAttestationMetadata.attach(tx, request.attestation());

            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(request.feePayerAddress())
                    .withRequiredSigners(adminPkh.getBytes())
                    .mergeOutputs(false)
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("kyc-extended mint error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /**
     * Refused: CCL gap #18. A burn spends the holder's own wallet, so the kyc-extended transfer
     * logic runs and demands a KYC proof for every spent input, and a burn request carries no
     * attestation to build one from.
     */
    @Override
    public TransactionContext<Void> buildBurnTransaction(BurnTokenRequest request,
                                                         ProtocolBootstrapParams protocolParams) {
        return TransactionContext.typedError("kyc-extended burn is not supported yet (CCL gap #18): burning spends the "
                + "holder's wallet, which runs the KYC transfer check, and a burn request carries no KYC attestation "
                + "to prove it with.");
    }

    /**
     * An owner transfer whose transfer-logic redeemer is
     * {@code Constr 0 [global_state_idx, sender_proofs, receiver_proofs]}: one sender proof per
     * spent base-script input (an attestation or a membership proof) and one membership proof for
     * the recipient unless it is a self-send. {@code global_state_idx} and the number of spent
     * base-script inputs exist only in the finished transaction, so CCL gets a placeholder and
     * {@link SubstandardRedeemerExtension} writes the real redeemer after balancing.
     */
    @Override
    public TransactionContext<Void> buildTransferTransaction(
            TransferTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var senderAddress = new Address(request.senderAddress());
            var receiverAddress = new Address(request.recipientAddress());
            var globalStatePolicyId = context.getGlobalStatePolicyId();
            var policyId = AssetType.fromUnit(request.unit()).policyId();

            // The on-chain validator extracts witnesses from the prog-token output's STAKE
            // credential, so identity throughout this flow is the delegation credential hash.
            byte[] recipientPkh = receiverAddress.getDelegationCredentialHash().orElse(null);
            if (recipientPkh == null) {
                return TransactionContext.typedError("could not extract recipient stake credential hash (recipient must be a base address)");
            }
            long now = System.currentTimeMillis();
            if (!mpfTreeService.containsValid(policyId, recipientPkh, now)) {
                return TransactionContext.typedError(
                        "recipient is not in the kyc-extended member tree (or leaf has expired). " +
                        "Recipient must complete KYC for this token before receiving transfers.");
            }
            if (request.mpfProofCborHex() == null || request.mpfProofCborHex().isBlank()
                    || request.mpfValidUntilMs() == null) {
                return TransactionContext.typedError("recipient MPF proof and validUntilMs are required for kyc-extended transfer");
            }
            ListPlutusData recipientMpfProof = decodeProof(request.mpfProofCborHex());
            ConstrPlutusData recipientMembershipProof = buildMembershipProof(recipientPkh, request.mpfValidUntilMs(), recipientMpfProof);

            byte[] senderPkh = senderAddress.getDelegationCredentialHash().orElse(null);
            if (senderPkh == null) {
                return TransactionContext.typedError("could not extract sender stake credential hash (sender must be a base address)");
            }

            boolean hasMembership = request.senderMpfProofCborHex() != null
                    && !request.senderMpfProofCborHex().isBlank()
                    && request.senderMpfValidUntilMs() != null;
            boolean hasAttestation = request.kycPayload() != null && !request.kycPayload().isBlank()
                    && request.kycSignature() != null && !request.kycSignature().isBlank();
            if (!hasMembership && !hasAttestation) {
                return TransactionContext.typedError("either Attestation (kycPayload+kycSignature) or Membership (senderMpfProofCborHex+senderMpfValidUntilMs) is required for kyc-extended transfer");
            }

            var amountToTransfer = new BigInteger(request.quantity());
            var plb = protocolParams.programmableLogicBase().scriptHash();
            var moduleTransferContract = kycExtendedScriptBuilder.buildTransferScript(plb, globalStatePolicyId);

            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var registryNode = service.advanced().getRegistryNode(policyId);
            if (!registryNode.isSuccessful() || registryNode.getValue() == null) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            // Fail early if the registry-recorded transfer hash no longer matches the current
            // runtime hash — prog-logic-global would reject the transfer otherwise.
            var registeredTransferHash = HexUtil.encodeHexString(registryNode.getValue().getTransferLogicScript().getBytes());
            var runtimeTransferHash = HexUtil.encodeHexString(moduleTransferContract.getScriptHash());
            if (!registeredTransferHash.equalsIgnoreCase(runtimeTransferHash)) {
                return TransactionContext.typedError(
                        "kyc-extended-transfer script hash mismatch with registry (" + registeredTransferHash +
                        " on-chain vs " + runtimeTransferHash + " runtime). Re-register the token after rebuilding the Aiken contracts.");
            }

            var globalState = globalStateUtxo(globalStatePolicyId);
            if (globalState == null) {
                return TransactionContext.typedError("could not find global state UTxO for global state");
            }

            var senderWallet = CclWallets.smartWallet(deployment, request.senderAddress()).toBech32();
            var held = utxoProvider.findUtxos(senderWallet).stream()
                    .flatMap(utxo -> utxo.getAmount().stream())
                    .filter(amount -> amount.getUnit().equals(request.unit()))
                    .map(Amount::getQuantity)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (held.compareTo(amountToTransfer) < 0) {
                return TransactionContext.typedError("Not enough funds");
            }

            // TTL must not exceed any proof's validUntilMs — the validator enforces tx_upper_bound <= valid_until.
            long ttlMs = Math.min(now + transferValidityMs, request.mpfValidUntilMs());
            if (hasMembership) {
                ttlMs = Math.min(ttlMs, request.senderMpfValidUntilMs());
            }
            if (hasAttestation) {
                long attValid = parseValidUntilFromAttestationPayload(request.kycPayload());
                if (attValid > 0) {
                    ttlMs = Math.min(ttlMs, attValid);
                }
            }
            if (hasMembership && ttlMs > request.senderMpfValidUntilMs()) {
                return TransactionContext.typedError("sender Membership proof has already expired");
            }
            long ttlSlot = cardanoConverters.time().toSlot(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(ttlMs), ZoneOffset.UTC));

            CclDeployments.registerWitnesses(service, moduleTransferContract);

            var vkeyIdx = request.kycVkeyIndex() != null ? request.kycVkeyIndex() : 0;
            ListPlutusData senderMpfProof = hasMembership ? decodeProof(request.senderMpfProofCborHex()) : null;
            // SenderProof: Constr 0 = Attestation { kyc_proof }, Constr 1 = Membership { proof }.
            java.util.function.IntFunction<PlutusData> senderProof = globalStateIdx -> hasMembership
                    ? ConstrPlutusData.of(1, buildMembershipProof(senderPkh, request.senderMpfValidUntilMs(), senderMpfProof))
                    : ConstrPlutusData.of(0, ConstrPlutusData.of(0,
                            BigIntPlutusData.of(globalStateIdx),
                            BigIntPlutusData.of(vkeyIdx),
                            BytesPlutusData.of(HexUtil.decodeHexString(request.kycPayload())),
                            BytesPlutusData.of(HexUtil.decodeHexString(request.kycSignature()))));
            // Validator filters sender pkhs out of receiver_witnesses, so a self-send needs zero
            // receiver proofs and a normal transfer needs exactly one.
            var receiverProofs = ListPlutusData.of();
            if (!java.util.Arrays.equals(recipientPkh, senderPkh)) {
                receiverProofs.add(recipientMembershipProof);
            }

            var tx = new ProgrammableTokenTx()
                    .from(request.senderAddress())
                    .transfer(CclWallets.smartWallet(deployment, request.recipientAddress()).toBech32(),
                            Amount.asset(request.unit(), amountToTransfer),
                            ConstrPlutusData.of(0, BigIntPlutusData.of(0), ListPlutusData.of(senderProof.apply(0)), receiverProofs),
                            HOLDING);
            tx.readFrom(globalState);

            var redeemer = new SubstandardRedeemerExtension(
                    Credential.fromScript(moduleTransferContract.getScriptHash()),
                    (built, lookup) -> {
                        var globalStateIdx = LedgerOrdering.indexOfReferenceInput(
                                built, globalState.getTxHash(), globalState.getOutputIndex());
                        if (globalStateIdx < 0) {
                            throw new IllegalStateException("the global-state UTxO is not a reference input");
                        }
                        // One sender_proof entry per prog-base input — the validator pairs them positionally.
                        var senderProofs = ListPlutusData.of();
                        for (TransactionInput input : LedgerOrdering.sortedInputs(built)) {
                            var spent = lookup.apply(input).orElseThrow(() -> new IllegalStateException(
                                    "input " + input.getTransactionId() + "#" + input.getIndex() + " cannot be resolved"));
                            var isBase = new Address(spent.getAddress()).getPaymentCredentialHash()
                                    .map(HexUtil::encodeHexString).filter(plb::equalsIgnoreCase).isPresent();
                            if (isBase) {
                                senderProofs.add(senderProof.apply(globalStateIdx));
                            }
                        }
                        return ConstrPlutusData.of(0, BigIntPlutusData.of(globalStateIdx), senderProofs, receiverProofs);
                    });

            var transaction = cclQuickTx.builder(service, redeemer)
                    .compose(tx)
                    .feePayer(request.senderAddress())
                    // The stake key signs for the wallet besides the paying payment key: price it.
                    .additionalSignersCount(1)
                    .mergeOutputs(false)
                    .validTo(ttlSlot)
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("kyc-extended transfer error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    // ========== Global State Init / Entity Management ==========

    @Override
    public TransactionContext<GlobalStateInitResult> buildGlobalStateInitTransaction(
            GlobalStateInitRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            // adminPkh is baked into the script as the only authorised signer for global-state
            // updates; explicit override lets the backend's signing key autonomously publish
            // UpdateMemberRootHash without requiring the issuer wallet at sync time.
            var adminPkh = (request.adminPkh() != null && !request.adminPkh().isBlank())
                    ? request.adminPkh()
                    : HexUtil.encodeHexString(new Address(request.adminAddress()).getPaymentCredentialHash().get());

            var utilityUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);
            if (utilityUtxos.isEmpty()) {
                return TransactionContext.typedError("no UTxOs found for admin address");
            }

            var bootstrapUtxo = utilityUtxos.getFirst();
            var bootstrapTxInput = TransactionInput.builder()
                    .transactionId(bootstrapUtxo.getTxHash())
                    .index(bootstrapUtxo.getOutputIndex())
                    .build();

            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(bootstrapTxInput, adminPkh);
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());

            var trustedEntities = ListPlutusData.of();
            if (request.initialVkeys() != null) {
                for (var vkeyHex : request.initialVkeys()) {
                    if (vkeyHex != null && vkeyHex.trim().length() == 64) {
                        trustedEntities.add(BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex.trim())));
                    }
                }
            }
            var paused = Boolean.TRUE.equals(request.initialTransfersPaused());
            var mintAmount = request.initialMintableAmount() != null ? request.initialMintableAmount() : 0L;
            PlutusData secInfoData = ConstrPlutusData.of(0);
            if (request.initialSecurityInfo() != null && !request.initialSecurityInfo().isBlank()) {
                secInfoData = BytesPlutusData.of(HexUtil.decodeHexString(request.initialSecurityInfo()));
            }
            var emptyMemberRoot = BytesPlutusData.of(HexUtil.decodeHexString(EMPTY_ROOT_HEX));

            var initialDatum = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(paused ? 1 : 0),
                    BigIntPlutusData.of(BigInteger.valueOf(mintAmount)),
                    trustedEntities,
                    secInfoData,
                    emptyMemberRoot
            );

            var globalStateNft = Asset.builder()
                    .name("0x" + KycExtendedScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX)
                    .value(BigInteger.ONE)
                    .build();

            Value globalStateValue = Value.builder()
                    .coin(Amount.ada(5).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(globalStateMintScript.getPolicyId())
                                    .assets(List.of(globalStateNft))
                                    .build()
                    ))
                    .build();

            var adminCredential = Credential.fromKey(adminPkh);
            var globalStatePolicyId = globalStateMintScript.getPolicyId();
            var moduleIssueContract = kycExtendedScriptBuilder.buildIssueScript(globalStatePolicyId, adminCredential);
            var moduleIssueAddress = AddressProvider.getRewardAddress(moduleIssueContract, network.getCardanoNetwork());

            var moduleTransferContract = kycExtendedScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBase().scriptHash(), globalStatePolicyId);
            var moduleTransferAddress = AddressProvider.getRewardAddress(moduleTransferContract, network.getCardanoNetwork());

            var requiredStakeAddresses = Stream.of(moduleIssueAddress, moduleTransferAddress)
                    .map(Address::getAddress)
                    .toList();

            var registeredStakeAddresses = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> stakeRegistrationRepository.findRegistrationsByStakeAddress(stakeAddress)
                            .map(reg -> reg.getType().equals(CertificateType.STAKE_REGISTRATION)).orElse(false))
                    .toList();

            var stakeAddressesToRegister = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> !registeredStakeAddresses.contains(stakeAddress))
                    .toList();

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
                        var outputs = transaction1.getBody().getOutputs();
                        if (!outputs.isEmpty() && outputs.getFirst().getAddress().equals(request.adminAddress())) {
                            var changeOutput = outputs.removeFirst();
                            outputs.addLast(changeOutput);
                        }
                    })
                    .build();

            log.info("kyc-extended global state init tx: {}", transaction.serializeToHex());

            var policyId = globalStateMintScript.getPolicyId();
            var existing = globalStateInitRepository.findByGlobalStatePolicyId(policyId);
            if (existing.isEmpty()) {
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
            log.error("kyc-extended global state init error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildAddTrustedEntityTransaction(
            AddTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var vkeyHex = request.verificationKey().trim();
            if (vkeyHex.length() != 64) {
                return TransactionContext.typedError("Verification key must be 64 hex characters (32 bytes)");
            }

            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);
            var currentEntities = parseTrustedEntitiesFromDatum(currentDatum);

            var updatedEntities = ListPlutusData.of();
            currentEntities.getPlutusDataList().forEach(updatedEntities::add);
            updatedEntities.add(BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex)));

            var updatedDatum = buildGlobalStateDatumWithEntities(currentDatum, updatedEntities);

            // ModifyTrustedEntities = constructor index 3
            var modifyAction = ConstrPlutusData.of(3, updatedEntities);
            var spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), modifyAction);

            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);
            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr, globalStateUtxo.getAmount(), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) ->
                            restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue))
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("kyc-extended add trusted entity error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildRemoveTrustedEntityTransaction(
            RemoveTrustedEntityRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var vkeyHex = request.verificationKey().trim();

            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

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

            var modifyAction = ConstrPlutusData.of(3, updatedEntities);
            var spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), modifyAction);

            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);
            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr, globalStateUtxo.getAmount(), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) ->
                            restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue))
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("kyc-extended remove trusted entity error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildGlobalStateUpdateTransaction(
            GlobalStateUpdateRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);

            ConstrPlutusData updatedDatum;
            ConstrPlutusData spendRedeemer;

            switch (request.action()) {
                case PAUSE_TRANSFERS -> {
                    var paused = Boolean.TRUE.equals(request.transfersPaused());
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 0,
                            ConstrPlutusData.of(paused ? 1 : 0));
                    var action = ConstrPlutusData.of(1, ConstrPlutusData.of(paused ? 1 : 0));
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                case UPDATE_MINTABLE_AMOUNT -> {
                    var amount = request.mintableAmount() != null ? request.mintableAmount() : 0L;
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 1,
                            BigIntPlutusData.of(BigInteger.valueOf(amount)));
                    var action = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(amount)));
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                case MODIFY_SECURITY_INFO -> {
                    var infoData = request.securityInfo() != null && !request.securityInfo().isBlank()
                            ? BytesPlutusData.of(HexUtil.decodeHexString(request.securityInfo()))
                            : ConstrPlutusData.of(0);
                    updatedDatum = buildGlobalStateDatumWithField(currentDatum, 3, infoData);
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
                    var action = ConstrPlutusData.of(3, newEntities);
                    spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), action);
                }
                default -> {
                    return TransactionContext.typedError("Unknown global state action: " + request.action());
                }
            }

            var adminUtxos = accountService.findAdaOnlyUtxo(request.adminAddress(), 10_000_000L);
            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            final long topUpTargetLovelace = Amount.ada(5).getQuantity().longValue();
            final boolean isTopUpAction = request.action() == GlobalStateAction.MODIFY_SECURITY_INFO;
            final Value outputValueAtEval = isTopUpAction
                    ? bumpLovelace(inputValue, topUpTargetLovelace)
                    : inputValue;

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr, ValueUtil.toAmountList(outputValueAtEval), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(request.adminAddress());

            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getIssuerAdminPkh()))
                    .feePayer(request.adminAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, request.adminAddress());
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, outputValueAtEval);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) ->
                            restoreGlobalStateOutputValue(transaction1, scriptAddr, outputValueAtEval))
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("kyc-extended global state update error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /**
     * Build a transaction that rotates the on-chain {@code member_root_hash} to {@code newRootHash}.
     *
     * Uses the new {@code UpdateMemberRootHash} GlobalStateSpendAction (constructor index 4).
     * Mirrors the {@link #buildAddTrustedEntityTransaction} pattern: spend the global state UTxO,
     * preserve everything except {@code member_root_hash}, sign with the issuer admin key.
     *
     * @param policyId     The kyc-extended programmable token policy id
     * @param newRootHash  The new 32-byte root hash to publish on-chain
     * @return TransactionContext with the unsigned tx (caller signs with admin key + submits)
     */
    public TransactionContext<Void> buildUpdateMemberRootHashTransaction(
            String policyId,
            byte[] newRootHash,
            String adminAddress,
            String signerPkh,
            ProtocolBootstrapParams protocolParams) {

        try {
            if (newRootHash == null || newRootHash.length != 32) {
                return TransactionContext.typedError("newRootHash must be 32 bytes");
            }

            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(
                    context.getGlobalStateInitTxInput(), context.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found");
            }

            var currentDatum = deserializeGlobalStateDatum(globalStateUtxo);
            var newRootBytes = BytesPlutusData.of(newRootHash);
            var updatedDatum = buildGlobalStateDatumWithField(currentDatum, 4, newRootBytes);

            // UpdateMemberRootHash = constructor index 4 of GlobalStateSpendAction
            var modifyAction = ConstrPlutusData.of(4, newRootBytes);
            var spendRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.ZERO), modifyAction);

            // Blockfrost-direct fee UTxOs — YACI may still report consumed UTxOs as unspent.
            var adminUtxos = pickAdaOnlyFromBlockfrost(adminAddress, 10_000_000L);
            if (adminUtxos.isEmpty()) {
                return TransactionContext.typedError("admin address has no ADA-only UTxOs (Blockfrost) to pay update fees: " + adminAddress);
            }
            var inputValue = globalStateUtxo.toValue();
            var scriptAddr = globalStateSpendAddress.getAddress();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(globalStateUtxo, spendRedeemer)
                    .payToContract(scriptAddr, globalStateUtxo.getAmount(), updatedDatum)
                    .attachSpendingValidator(globalStateSpendScript)
                    .withChangeAddress(adminAddress);

            hybridUtxoSupplier.add(globalStateUtxo);

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(signerPkh))
                    .feePayer(adminAddress)
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        ensureGlobalStateOutputFirst(transaction1, adminAddress);
                        restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue);
                    })
                    .postBalanceTx((txBuilderContext, transaction1) ->
                            restoreGlobalStateOutputValue(transaction1, scriptAddr, inputValue))
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("kyc-extended UpdateMemberRootHash error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    // ========== Read Global State ==========

    public Optional<GlobalStateData> readGlobalState(String policyId) {
        try {
            var regOpt = kycExtendedTokenRegistrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) return Optional.empty();
            var reg = regOpt.get();

            var gsInit = globalStateInitRepository.findByGlobalStatePolicyId(reg.getTelPolicyId());
            if (gsInit.isEmpty()) return Optional.empty();
            var initEntity = gsInit.get();

            var globalStateInitTxInput = TransactionInput.builder()
                    .transactionId(initEntity.getTxHash())
                    .index(initEntity.getOutputIndex())
                    .build();

            var globalStateScripts = kycExtendedScriptBuilder.buildGlobalStateScripts(
                    globalStateInitTxInput, reg.getIssuerAdminPkh());
            var globalStateMintScript = globalStateScripts.first();
            var globalStateSpendScript = globalStateScripts.second();

            var globalStateSpendAddress = AddressProvider.getEntAddress(globalStateSpendScript, network.getCardanoNetwork());
            var globalStatePolicyId = globalStateMintScript.getPolicyId();

            var globalStateUtxo = findGlobalStateUtxo(globalStateSpendAddress.getAddress(), globalStatePolicyId);
            if (globalStateUtxo == null) return Optional.empty();

            var constr = deserializeGlobalStateDatum(globalStateUtxo);
            var fields = constr.getData().getPlutusDataList();
            if (fields.size() < 5) return Optional.empty();

            boolean transfersPaused = false;
            if (fields.get(0) instanceof ConstrPlutusData boolConstr) {
                transfersPaused = boolConstr.getAlternative() == 1;
            }
            long mintableAmount = 0;
            if (fields.get(1) instanceof BigIntPlutusData bigInt) {
                mintableAmount = bigInt.getValue().longValueExact();
            }
            var trustedEntities = new java.util.ArrayList<String>();
            if (fields.get(2) instanceof ListPlutusData list) {
                for (var item : list.getPlutusDataList()) {
                    if (item instanceof BytesPlutusData bytes) {
                        trustedEntities.add(HexUtil.encodeHexString(bytes.getValue()));
                    }
                }
            }
            String securityInfo = null;
            if (fields.get(3) instanceof BytesPlutusData bytes) {
                securityInfo = HexUtil.encodeHexString(bytes.getValue());
            }
            String memberRootHash = null;
            if (fields.get(4) instanceof BytesPlutusData bytes) {
                memberRootHash = HexUtil.encodeHexString(bytes.getValue());
            }

            return Optional.of(new GlobalStateData(
                    policyId, transfersPaused, mintableAmount, trustedEntities, securityInfo, memberRootHash));

        } catch (Exception e) {
            log.error("kyc-extended read global state error for policyId={}", policyId, e);
            return Optional.empty();
        }
    }

    public record GlobalStateData(
            String policyId,
            boolean transfersPaused,
            long mintableAmount,
            java.util.List<String> trustedEntities,
            String securityInfo,
            String memberRootHash
    ) {}

    // ========== Private Helpers ==========

    private Utxo findGlobalStateUtxo(String spendAddress, String globalStatePolicyId) {
        // Blockfrost-direct: YACI can show an already-consumed global-state UTxO as unspent.
        var utxos = utxoProvider.findUtxosFromBlockfrost(spendAddress);
        return utxos.stream()
                .filter(utxo -> utxo.getAmount().stream()
                        .anyMatch(amount -> amount.getUnit().startsWith(globalStatePolicyId)))
                .findFirst()
                .orElse(null);
    }

    /** Greedy ADA-only UTxO selection from Blockfrost (avoids YACI's lag). */
    private List<Utxo> pickAdaOnlyFromBlockfrost(String address, long minTotalLovelace) {
        var fresh = utxoProvider.findUtxosFromBlockfrost(address);
        var picked = new java.util.ArrayList<Utxo>();
        long total = 0;
        var sorted = fresh.stream()
                .filter(u -> u.getAmount().size() == 1
                        && "lovelace".equals(u.getAmount().getFirst().getUnit()))
                .sorted((a, b) -> b.getAmount().getFirst().getQuantity()
                        .compareTo(a.getAmount().getFirst().getQuantity()))
                .toList();
        for (var u : sorted) {
            picked.add(u);
            total += u.getAmount().getFirst().getQuantity().longValue();
            if (total >= minTotalLovelace) break;
        }
        return picked;
    }

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

    private ListPlutusData parseTrustedEntitiesFromDatum(ConstrPlutusData constr) {
        var fields = constr.getData().getPlutusDataList();
        if (fields.size() >= 3 && fields.get(2) instanceof ListPlutusData list) {
            return list;
        }
        return ListPlutusData.of();
    }

    private void ensureGlobalStateOutputFirst(Transaction transaction, String changeAddress) {
        var outputs = transaction.getBody().getOutputs();
        if (!outputs.isEmpty() && outputs.getFirst().getAddress().equals(changeAddress)) {
            var changeOutput = outputs.removeFirst();
            outputs.addLast(changeOutput);
        }
    }

    private Value bumpLovelace(Value source, long targetLovelace) {
        var current = source.getCoin() == null ? BigInteger.ZERO : source.getCoin();
        var target = BigInteger.valueOf(targetLovelace);
        var coin = current.compareTo(target) >= 0 ? current : target;
        return Value.builder()
                .coin(coin)
                .multiAssets(source.getMultiAssets())
                .build();
    }

    private void restoreGlobalStateOutputValue(Transaction transaction, String scriptAddress, Value inputValue) {
        var outputs = transaction.getBody().getOutputs();
        for (var output : outputs) {
            if (output.getAddress().equals(scriptAddress)) {
                output.setValue(inputValue);
                return;
            }
        }
    }

    /**
     * Replace one field in a 5-field {@code GlobalStateDatum}.
     * Indices: 0=transfers_paused, 1=mintable_amount, 2=trusted_entities, 3=security_info, 4=member_root_hash.
     */
    private ConstrPlutusData buildGlobalStateDatumWithField(ConstrPlutusData currentDatum, int fieldIndex, PlutusData newValue) {
        var fields = currentDatum.getData().getPlutusDataList();
        if (fields.size() >= 5) {
            var f0 = fieldIndex == 0 ? newValue : fields.get(0);
            var f1 = fieldIndex == 1 ? newValue : fields.get(1);
            var f2 = fieldIndex == 2 ? newValue : fields.get(2);
            var f3 = fieldIndex == 3 ? newValue : fields.get(3);
            var f4 = fieldIndex == 4 ? newValue : fields.get(4);
            return ConstrPlutusData.of(0, f0, f1, f2, f3, f4);
        }
        throw new IllegalStateException("kyc-extended global state datum has fewer than 5 fields: " + fields.size());
    }

    private ConstrPlutusData buildGlobalStateDatumWithEntities(ConstrPlutusData currentDatum, ListPlutusData newEntities) {
        var fields = currentDatum.getData().getPlutusDataList();
        if (fields.size() >= 5) {
            return ConstrPlutusData.of(0,
                    fields.get(0),
                    fields.get(1),
                    newEntities,
                    fields.get(3),
                    fields.get(4)
            );
        }
        throw new IllegalStateException("kyc-extended global state datum has fewer than 5 fields: " + fields.size());
    }

    /**
     * Build a {@code MembershipProof} ConstrPlutusData (constructor 0) from raw fields.
     * Aiken: {@code MembershipProof { pkh: ByteArray, valid_until_ms: Int, mpf_proof: Proof }}.
     */
    private ConstrPlutusData buildMembershipProof(byte[] pkh, long validUntilMs, ListPlutusData mpfProof) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(pkh),
                BigIntPlutusData.of(BigInteger.valueOf(validUntilMs)),
                mpfProof
        );
    }

    /** Decode the hex-encoded CBOR bytes of a serialized ListPlutusData (the MPF proof). */
    private ListPlutusData decodeProof(String mpfProofCborHex) {
        return decodeProofBytes(HexUtil.decodeHexString(mpfProofCborHex));
    }

    private ListPlutusData decodeProofBytes(byte[] cbor) {
        try {
            var data = PlutusData.deserialize(cbor);
            if (data instanceof ListPlutusData list) {
                return list;
            }
            throw new IllegalStateException("MPF proof CBOR is not a ListPlutusData: " + data.getClass().getSimpleName());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode MPF proof CBOR", e);
        }
    }

    /** Parse the 8-byte BE valid_until from a 37-byte attestation payload (offset 29, length 8). */
    private long parseValidUntilFromAttestationPayload(String payloadHex) {
        try {
            byte[] bytes = HexUtil.decodeHexString(payloadHex);
            if (bytes.length < 37) return -1L;
            return java.nio.ByteBuffer.wrap(bytes, 29, 8).order(java.nio.ByteOrder.BIG_ENDIAN).getLong();
        } catch (Exception e) {
            return -1L;
        }
    }

    /**
     * The global-state UTxO. global_state is one validator with mint and spend handlers over the
     * same parameters, so its policy id IS the spend script hash: the address follows from the
     * policy alone, with no assumption about which admin initialised it.
     */
    private Utxo globalStateUtxo(String globalStatePolicyId) {
        var address = AddressProvider.getEntAddress(Credential.fromScript(globalStatePolicyId),
                network.getCardanoNetwork()).getAddress();
        return utxoProvider.findUtxos(address).stream()
                .filter(utxo -> utxo.getAmount().stream().anyMatch(a -> a.getUnit().startsWith(globalStatePolicyId)))
                .findFirst()
                .orElse(null);
    }

    /** The policy the platform derives, cross-checked against CCL's own derivation. */
    private String policyIdOf(Cip113ProgrammableTokenService service, PlutusScript moduleIssueContract,
                              ProtocolBootstrapParams protocolParams) throws Exception {
        var platform = protocolScriptBuilderService
                .getParameterizedIssuanceMintScript(protocolParams, moduleIssueContract).getPolicyId();
        var ccl = service.advanced().derivePolicyId(Credential.fromScript(moduleIssueContract.getScriptHash()));
        if (!ccl.isSuccessful() || !platform.equalsIgnoreCase(ccl.getValue())) {
            throw new IllegalStateException("platform derives policy " + platform + " but CCL derives "
                    + (ccl.isSuccessful() ? ccl.getValue() : ccl.getResponse()));
        }
        return platform;
    }

    /** The upper validity bound: now plus the configured window, as a slot. */
    private long validToSlot() {
        return cardanoConverters.time().toSlot(LocalDateTime.ofInstant(
                Instant.ofEpochMilli(System.currentTimeMillis() + transferValidityMs), ZoneOffset.UTC));
    }

    private static ProgrammableTokenCredential scriptCredential(PlutusScript script) throws Exception {
        return ProgrammableTokenCredential.from(Credential.fromScript(script.getScriptHash()));
    }

    private static Asset asset(String nameHex, BigInteger quantity) {
        return Asset.builder().name("0x" + nameHex).value(quantity).build();
    }
}
