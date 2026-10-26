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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
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
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
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
import org.cardanofoundation.cip113.service.module.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.AddTrustedEntityRequest;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateAction;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitRequest;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitResult;
import org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.RemoveTrustedEntityRequest;
import org.cardanofoundation.cip113.service.module.context.KycContext;
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
 * Handler for the "kyc" programmable token module.
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
public class KycModuleHandler implements ModuleHandler, BasicOperations<KycRegisterRequest>, GlobalStateManageable {

    private static final String MODULE_ID = "kyc";

    /** The kyc issue logic's redeemer. */
    private static final PlutusData ISSUE = ConstrPlutusData.of(0);

    /** The inline datum the platform writes on every holding ({@code Constr 0 []}). */
    private static final PlutusData HOLDING = CoreDatums.programmableTokenDatum();

    /** The name the registration and its first mint refer to each other by, within one transaction. */
    private static final String REGISTRATION = "token";

    private final ObjectMapper objectMapper;
    private final AppConfig.Network network;
    private final RegistryNodeParser registryNodeParser;
    private final AccountService accountService;
    private final ModuleService moduleService;
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

    /** CCL's programmable-token service for the deployment each request names. */
    private final CclDeployments cclDeployments;

    /** A fresh CCL-extended builder per transaction, on the platform's suppliers. */
    private final CclQuickTx cclQuickTx;

    @Setter
    private KycContext context;

    /**
     * How far ahead a transfer's upper validity bound lies. It must stay inside the node's
     * time-translation horizon (3k/f slots): 1.5 days on public networks, 5 minutes on a default
     * Yaci devnet.
     */
    @Setter
    @org.springframework.beans.factory.annotation.Value("${cip113.kyc.transfer-validity-ms:900000}")
    private long transferValidityMs = 15 * 60 * 1000L;

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    // ========== BasicOperations Implementation ==========

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(
            KycRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        // KYC bundles the module stake-credential registration into the
        // GS-init tx (see buildGlobalStateInitTransaction). The wizard doesn't
        // call a separate pre-registration step for this module.
        return TransactionContext.ok(null, List.of());
    }

    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            KycRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        // CIP-68 is not implemented for this module. Refuse rather than drop it: the wizard's
        // shared token-details step collects the metadata for every flow, and accepting it here
        // would mint a token whose (222)/(333) label advertises metadata that was never written.
        if (request.getCip68Metadata() != null) {
            return TransactionContext.typedError(
                    "CIP-68 is not supported for the 'kyc' module. Supported: dummy, "
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

            var globalStateInitEntity = globalStateInitRepository.findByGlobalStatePolicyId(globalStatePolicyId);
            if (globalStateInitEntity.isEmpty()) {
                return TransactionContext.typedError("Global state init not found for policy ID: " + globalStatePolicyId);
            }

            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var moduleIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, adminPkh);
            var moduleTransferContract = kycScriptBuilder.buildTransferScript(
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

            kycTokenRegistrationRepository.save(KycTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(progTokenPolicyId)
                    .issuerAdminPkh(HexUtil.encodeHexString(adminPkh.getBytes()))
                    .globalStateInit(globalStateInitEntity.get())
                    .build());

            // Save to unified programmable token registry (policyId -> moduleId binding)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .moduleId(MODULE_ID)
                    .assetName(request.getAssetName())
                    .build());

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

        } catch (Exception e) {
            log.warn("kyc registration error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /**
     * The chained registration, built by hand: CCL gap #17 (see buildRegistrationTransaction).
     */
    private TransactionContext<RegistrationResult> buildChainedRegistrationTransaction(
            KycRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            // CIP-68 is not implemented for this module. Refuse rather than drop it: the
            // wizard's shared token-details step collects the metadata for every flow, and
            // accepting it here would mint a token whose (222)/(333) label advertises metadata
            // that was never written — the exact failure this refusal exists to prevent.
            if (request.getCip68Metadata() != null) {
                return TransactionContext.typedError(
                        "CIP-68 is not supported for the 'kyc' module. Supported: dummy, "
                        + "freeze-and-seize, rwa-token. Register without CIP-68 metadata.");
            }
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

            // Build KYC module scripts
            var moduleIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, Credential.fromKey(request.getAdminPubKeyHash()));
            var moduleIssueAddress = AddressProvider.getRewardAddress(moduleIssueContract, network.getCardanoNetwork());
            log.info("KYC moduleIssueAddress: {}", moduleIssueAddress.getAddress());

            var moduleTransferContract = kycScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBase().scriptHash(),
                    globalStatePolicyId
            );
            var moduleTransferAddress = AddressProvider.getRewardAddress(moduleTransferContract, network.getCardanoNetwork());
            log.info("KYC moduleTransferAddress: {}", moduleTransferAddress.getAddress());

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

            // Directory MINT
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

            // third_party_transfer_logic_script (index 4): the `kyc` module declares NO
            // third-party/force-transfer validator. src/modules/kyc/validators/kyc_transfer.ak
            // has exactly two validators — `issue` ("Issuer admin contract: only the permitted
            // credential can authorize *issuance*") and `transfer` (KYC-proof gated). Reusing the
            // issuer-admin credential here would silently grant the issuer the power to take
            // tokens away from any holder, which no kyc validator or datum expresses; that is a
            // product decision, not a port. The slot also cannot be empty
            // (linked_list.is_28_byte_credential rejects a zero-length credential on a non-origin
            // node), so it is pinned to the protocol's always_fail script — since
            // programmable_logic_global.validate_3rd_party requires a *withdrawal* against this
            // credential and always_fail's `else` handler fails unconditionally, seizure is
            // structurally impossible. The field is mutable via the registry-node update path
            // (linked_list.is_field_updated_registry_node), so a real seize authority can be set
            // later once the product decision is made.
            // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN — least
            // permission by default, and no kyc validator declares an unfracking hook.
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
            // Registry node output is at index 2: [0] PLB, [1] updated covering node, [2] new registry node
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

            // Log a warning if the issue script stake address does not appear registered yet.
            // The actual enforcement happens at node submission — if not registered, the node
            // returns incompleteWithdrawals. We do not hard-block here to avoid false negatives
            // caused by Yaci Store indexing delays after a fresh Global State init.
            var issueStakeAddress = moduleIssueAddress.getAddress();
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
                    .moduleId(MODULE_ID)
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
            // Same refusal as registration: accepting cip68Metadata here would mint a user token
            // with no (100) reference token behind it.
            if (request.cip68Metadata() != null) {
                return TransactionContext.typedError(
                        "CIP-68 is not supported for the 'kyc' module. Supported: dummy, "
                        + "freeze-and-seize, rwa-token. Mint without CIP-68 metadata.");
            }

            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var globalStatePolicyId = context.getGlobalStatePolicyId();
            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var moduleIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, adminPkh);
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
            log.warn("kyc mint error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /**
     * Refused: CCL gap #18. A burn spends the holder's own wallet, so the kyc transfer
     * logic runs and demands a KYC proof for every spent input, and a burn request carries no
     * attestation to build one from.
     */
    @Override
    public TransactionContext<Void> buildBurnTransaction(BurnTokenRequest request,
                                                         ProtocolBootstrapParams protocolParams) {
        return TransactionContext.typedError("kyc burn is not supported yet (CCL gap #18): burning spends the "
                + "holder's wallet, which runs the KYC transfer check, and a burn request carries no KYC attestation "
                + "to prove it with.");
    }

    /**
     * An owner transfer with one {@code KycProof} per spent base-script input:
     * {@code Constr 0 [global_state_idx, vkey_idx, payload, signature]}. {@code global_state_idx}
     * is the global-state UTxO's position among the finished transaction's reference inputs, so
     * CCL gets a placeholder and {@link SubstandardRedeemerExtension} writes the real list after
     * balancing.
     */
    @Override
    public TransactionContext<Void> buildTransferTransaction(
            TransferTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var globalStatePolicyId = context.getGlobalStatePolicyId();

            // Validate KYC proof data is present
            if (request.kycPayload() == null || request.kycPayload().isBlank()) {
                return TransactionContext.typedError("KYC payload is required for transfer");
            }
            if (request.kycSignature() == null || request.kycSignature().isBlank()) {
                return TransactionContext.typedError("KYC signature is required for transfer");
            }

            var service = cclDeployments.service(protocolParams);
            var deployment = service.advanced().deployment();
            var registryNode = service.advanced().getRegistryNode(request.unit().substring(0, 56));
            if (!registryNode.isSuccessful() || registryNode.getValue() == null) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            var globalState = globalStateUtxo(globalStatePolicyId);
            if (globalState == null) {
                return TransactionContext.typedError("could not find global state UTxO for global state");
            }

            var amountToTransfer = new BigInteger(request.quantity());
            var senderWallet = CclWallets.smartWallet(deployment, request.senderAddress()).toBech32();
            var held = utxoProvider.findUtxos(senderWallet).stream()
                    .flatMap(utxo -> utxo.getAmount().stream())
                    .filter(amount -> amount.getUnit().equals(request.unit()))
                    .map(Amount::getQuantity)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (held.compareTo(amountToTransfer) < 0) {
                return TransactionContext.typedError("Not enough funds");
            }

            var plb = protocolParams.programmableLogicBase().scriptHash();
            var moduleTransferContract = kycScriptBuilder.buildTransferScript(plb, globalStatePolicyId);
            CclDeployments.registerWitnesses(service, moduleTransferContract);

            var vkeyIdx = request.kycVkeyIndex() != null ? request.kycVkeyIndex() : 0;
            var payload = HexUtil.decodeHexString(request.kycPayload());
            var signature = HexUtil.decodeHexString(request.kycSignature());
            var tx = new ProgrammableTokenTx()
                    .from(request.senderAddress())
                    .transfer(CclWallets.smartWallet(deployment, request.recipientAddress()).toBech32(),
                            Amount.asset(request.unit(), amountToTransfer),
                            ListPlutusData.of(kycProof(0, vkeyIdx, payload, signature)), HOLDING);
            tx.readFrom(globalState);

            var proofs = new SubstandardRedeemerExtension(
                    Credential.fromScript(moduleTransferContract.getScriptHash()),
                    (built, lookup) -> kycProofs(built, lookup, plb, globalState, vkeyIdx, payload, signature));

            // The on-chain check is tx_upper_bound <= valid_until, so any bound before the proof's
            // expiry works. A short window avoids the PastHorizon error (the node cannot translate
            // slots far in the future).
            var transaction = cclQuickTx.builder(service, proofs)
                    .compose(tx)
                    .feePayer(request.senderAddress())
                    // The stake key signs for the wallet besides the paying payment key: price it.
                    .additionalSignersCount(1)
                    .mergeOutputs(false)
                    .validTo(validToSlot())
                    .build();

            log.info("KYC transfer tx: {}", transaction.serializeToHex());
            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("KYC transfer error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    /** One KycProof per spent base-script input, in ledger input order, pointing at the global state. */
    private static PlutusData kycProofs(Transaction built,
                                        java.util.function.Function<TransactionInput, Optional<Utxo>> lookup,
                                        String plb, Utxo globalState, int vkeyIdx, byte[] payload, byte[] signature) {
        var globalStateIdx = LedgerOrdering.indexOfReferenceInput(built, globalState.getTxHash(), globalState.getOutputIndex());
        if (globalStateIdx < 0) {
            throw new IllegalStateException("the global-state UTxO is not a reference input");
        }
        var proofs = ListPlutusData.of();
        for (TransactionInput input : LedgerOrdering.sortedInputs(built)) {
            var spent = lookup.apply(input).orElseThrow(() -> new IllegalStateException(
                    "input " + input.getTransactionId() + "#" + input.getIndex() + " cannot be resolved"));
            var isBase = new Address(spent.getAddress()).getPaymentCredentialHash().map(HexUtil::encodeHexString)
                    .filter(plb::equalsIgnoreCase).isPresent();
            if (isBase) {
                proofs.add(kycProof(globalStateIdx, vkeyIdx, payload, signature));
            }
        }
        return proofs;
    }

    private static PlutusData kycProof(int globalStateIdx, int vkeyIdx, byte[] payload, byte[] signature) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(globalStateIdx), BigIntPlutusData.of(vkeyIdx),
                BytesPlutusData.of(payload), BytesPlutusData.of(signature));
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
            var globalStatePolicyId = globalStateMintScript.getPolicyId();
            var moduleIssueContract = kycScriptBuilder.buildIssueScript(globalStatePolicyId, adminCredential);
            var moduleIssueAddress = AddressProvider.getRewardAddress(moduleIssueContract, network.getCardanoNetwork());

            var moduleTransferContract = kycScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBase().scriptHash(), globalStatePolicyId);
            var moduleTransferAddress = AddressProvider.getRewardAddress(moduleTransferContract, network.getCardanoNetwork());

            // Register stake addresses for issue and transfer scripts
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
