package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.comparator.UtxoComparator;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.cardano.util.AmountUtil;
import com.easy1staking.util.Pair;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreDatums;
import org.cardanofoundation.cip113.core.CoreLayout;
import org.cardanofoundation.cip113.core.CoreRedeemers;
import org.cardanofoundation.cip113.core.CoreWithdrawal;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.BlacklistInitEntity;
import org.cardanofoundation.cip113.entity.FreezeAndSeizeTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.TransactionContext.MintingResult;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.*;
import org.cardanofoundation.cip113.repository.BlacklistInitRepository;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.*;
import org.cardanofoundation.cip113.service.substandard.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable;
import org.cardanofoundation.cip113.service.substandard.capabilities.Seizeable;
import org.cardanofoundation.cip113.util.Cip68;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import org.cardanofoundation.cip113.service.substandard.context.FreezeAndSeizeContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

/**
 * Handler for the "freeze-and-seize" programmable token substandard.
 *
 * <p>This handler supports regulated stablecoins with compliance features:</p>
 * <ul>
 *   <li><b>BasicOperations</b> - Register, mint, burn, transfer programmable tokens</li>
 *   <li><b>BlacklistManageable</b> - Freeze/unfreeze addresses via blacklist</li>
 *   <li><b>Seizeable</b> - Seize assets from blacklisted/sanctioned addresses</li>
 * </ul>
 *
 * <p>This handler requires a {@link FreezeAndSeizeContext} to be set before use,
 * as there can be multiple stablecoin deployments, each with their own configuration.</p>
 *
 * <p>Use {@link SubstandardHandlerFactory#getHandler(String, org.cardanofoundation.cip113.service.substandard.context.SubstandardContext)}
 * to get a properly configured instance.</p>
 */
@Component
@Scope("prototype") // New instance each time for context isolation
@RequiredArgsConstructor
@Slf4j
public class FreezeAndSeizeHandler implements SubstandardHandler, BasicOperations<FreezeAndSeizeRegisterRequest>, BlacklistManageable, Seizeable {

    private static final String SUBSTANDARD_ID = "freeze-and-seize";

    private final ObjectMapper objectMapper;
    private final AppConfig.Network network;
    private final BlacklistNodeParser blacklistNodeParser;
    private final RegistryNodeParser registryNodeParser;
    private final AccountService accountService;
    private final SubstandardService substandardService;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;
    private final FreezeAndSeizeScriptBuilderService fesScriptBuilder;
    private final LinkedListService linkedListService;
    private final QuickTxBuilder quickTxBuilder;

    /** Sizes the CIP-68 reference-token output against the live min-UTxO parameters. */
    private final ProtocolParamsSupplier protocolParamsSupplier;

    private final HybridUtxoSupplier hybridUtxoSupplier;

    private final FreezeAndSeizeTokenRegistrationRepository freezeAndSeizeTokenRegistrationRepository;

    private final BlacklistInitRepository blacklistInitRepository;

    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    /** See the note in DummySubstandardHandler: the ledger, not our index, decides whether a
     *  credential already exists. */
    private final ScriptRegistrationService scriptRegistrationService;

    private final UtxoProvider utxoProvider;

    private final BFBackendService bfBackendService;

    /**
     * Context for this handler instance.
     * Must be set before performing any operations.
     */
    @Setter
    private FreezeAndSeizeContext context;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    // ========== BasicOperations Implementation ==========

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(
            FreezeAndSeizeRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // This used to hard-error with "Use DenyList Init instead", on the assumption that the
            // blacklist init is the only transaction that ever needs to register these two reward
            // accounts. That holds only when a token's init and its registration are built
            // back-to-back from the same inputs. They are not always:
            //
            //   - issuer_admin is parameterized by (admin, ASSET NAME), so registering a SECOND
            //     token against an EXISTING blacklist resolves a reward account no init ever saw;
            //   - the transfer script is parameterized by (PLB hash, blacklist policy), so it is
            //     shared by every token on that blacklist and is already registered by then.
            //
            // Either way the registration withdraws-0 from both, and a withdrawal from an
            // unregistered reward account is rejected phase-1 at submit with
            // WithdrawalsNotInRewardsCERTS — after the user has signed. Script evaluation cannot
            // catch it, because reward-account existence is a ledger rule, not a Plutus one.
            //
            // So: register whichever of the two is actually missing, and return a null CBOR when
            // both already exist. Same contract as the dummy handler's, which is what the wizard's
            // pre-registration step already knows how to consume.
            if (request.getAdminPubKeyHash() == null || request.getAdminPubKeyHash().isBlank()) {
                return TransactionContext.typedError("adminPubKeyHash is required");
            }
            if (request.getBlacklistNodePolicyId() == null || request.getBlacklistNodePolicyId().isBlank()) {
                return TransactionContext.typedError(
                        "blacklistNodePolicyId is required — initialise the blacklist first");
            }

            // The SAME label rule as registration and as the init. issuer_admin is parameterized
            // by this name, so an unlabelled name here would register a DIFFERENT credential than
            // the one the registration withdraws from — the exact failure V14's cross-check
            // exists to prevent.
            var userAssetNameHex = request.getCip68Metadata() == null
                    ? request.getAssetName()
                    : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), request.getAssetName());

            var substandardIssueContract = fesScriptBuilder.buildIssuerAdminScript(
                    Credential.fromKey(request.getAdminPubKeyHash()),
                    userAssetNameHex);
            var substandardIssueAddress = AddressProvider.getRewardAddress(
                    substandardIssueContract, network.getCardanoNetwork());

            var substandardTransferContract = fesScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    request.getBlacklistNodePolicyId());
            var substandardTransferAddress = AddressProvider.getRewardAddress(
                    substandardTransferContract, network.getCardanoNetwork());

            var requiredStakeAddresses = Stream.of(substandardIssueAddress, substandardTransferAddress)
                    .map(Address::getAddress)
                    .toList();

            var registeredStakeAddresses = requiredStakeAddresses.stream()
                    .filter(scriptRegistrationService::isStakeAddressRegistered)
                    .toList();

            var stakeAddressesToRegister = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> !registeredStakeAddresses.contains(stakeAddress))
                    .toList();
            log.info("freeze-and-seize pre-registration: required={} registered={} toRegister={}",
                    requiredStakeAddresses, registeredStakeAddresses, stakeAddressesToRegister);

            if (stakeAddressesToRegister.isEmpty()) {
                return TransactionContext.ok(null, registeredStakeAddresses);
            }

            var registerAddressTx = new Tx()
                    .from(request.getFeePayerAddress())
                    .withChangeAddress(request.getFeePayerAddress());
            // Record the attempt BEFORE handing the transaction over. This row is the evidence
            // that lets a later /script-registration/known confirm this credential if the submit
            // comes back saying it already exists; without it that endpoint would be an
            // unauthenticated write over arbitrary addresses.
            stakeAddressesToRegister.forEach(addr -> {
                scriptRegistrationService.noteRegistrationAttempted(addr, null);
                registerAddressTx.registerStakeAddress(addr);
            });

            // A bare legacy StakeRegistration, deliberately: it is still valid in Conway and
            // requires NO witness, so a script stake credential registers without attaching its
            // validator. Rewriting it to RegCert is what makes a witness mandatory.
            var transaction = quickTxBuilder.compose(registerAddressTx)
                    .feePayer(request.getFeePayerAddress())
                    .build();

            return TransactionContext.ok(transaction.serializeToHex(), registeredStakeAddresses);
        } catch (Exception e) {
            log.error("freeze-and-seize pre-registration failed", e);
            return TransactionContext.typedError("pre-registration failed: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            FreezeAndSeizeRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {
            var adminPkh = Credential.fromKey(request.getAdminPubKeyHash());
            var blacklistNodePolicyId = request.getBlacklistNodePolicyId();

            List<Utxo> feePayerUtxos;
            if (request.getChainingTransactionCborHex() != null) {
                var chainingTxBytes = HexUtil.decodeHexString(request.getChainingTransactionCborHex());
                var chainingTxHash = TransactionUtil.getTxHash(chainingTxBytes);
                log.info("Chaining Tx Hash: " + chainingTxHash);
                var chainingTx = Transaction.deserialize(chainingTxBytes);

                var chainingTxOuputs = chainingTx.getBody().getOutputs();
                Utxo inputUtxo = null;
                for (int i = 0; i < chainingTxOuputs.size(); i++) {
                    var output = chainingTxOuputs.get(i);
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

                log.info("inputUtxo: {}", inputUtxo);

                feePayerUtxos = List.of(inputUtxo);
                feePayerUtxos.forEach(hybridUtxoSupplier::add);
            } else {
                feePayerUtxos = accountService.findAdaOnlyUtxo(request.getFeePayerAddress(), 10_000_000L);
            }

            var bootstrapTxHash = protocolParams.txHash();

            var directorySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            if (protocolParamsUtxoOpt.isEmpty()) {
                TransactionContext.error("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();

            var directorySpendContractAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());
            log.info("directorySpendContractAddress: {}", directorySpendContractAddress.getAddress());

            var issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (issuanceUtxoOpt.isEmpty()) {
                TransactionContext.error("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();
            log.info("issuanceUtxo: {}", issuanceUtxo);

            // CIP-68 mints a PAIR under this one policy: the labelled user token, plus a (100)
            // reference token of quantity 1 carrying the metadata as an inline datum.
            //
            // The labelled name has to be settled HERE, before the issuer-admin script is built,
            // because that script is parameterized by the asset name and issuance_mint is in turn
            // parameterized by that script — so the label participates in the token policy id.
            // The TypeScript SDK path does the same (`labeledAssetName(333, …)` feeding
            // `deployment.assetName`); if this used the bare name the two paths would register
            // different policies for the same wizard input.
            //
            // The label is ALWAYS (333). example_transfer_logic.ak's issuer_admin_contract checks
            // only that the permitted credential signed or withdrew — it ignores its
            // `_asset_name` parameter and caps nothing — and buildMintTransaction below can mint
            // more of the same name at any time. A (222) would therefore be a non-fungibility
            // claim this substandard cannot enforce. Making the label independent of `quantity`
            // also removes a whole failure mode: buildBlacklistInitTransaction registers the
            // issuer_admin reward account that this registration withdraws-0 from, and if the two
            // derived different labels from different quantities they would derive different
            // reward addresses. They no longer can.
            var cip68Metadata = request.getCip68Metadata();
            if (cip68Metadata != null
                && (request.getQuantity() == null || request.getQuantity().isBlank())) {
                return TransactionContext.typedError(
                        "quantity is required when cip68Metadata is present — the reference token "
                        + "is minted against a stated supply.");
            }
            var mintQuantity = new BigInteger(request.getQuantity());
            if (cip68Metadata != null && mintQuantity.signum() <= 0) {
                return TransactionContext.typedError(
                        "quantity must be > 0 when cip68Metadata is present, got: " + mintQuantity);
            }
            var userAssetNameHex = cip68Metadata == null
                    ? request.getAssetName()
                    : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), request.getAssetName());
            var referenceAssetNameHex = cip68Metadata == null
                    ? null
                    : Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, request.getAssetName());

            // Cross-check against the blacklist init, BEFORE building anything. That transaction
            // registered the issuer_admin reward account this registration is about to withdraw-0
            // from, and issuer_admin is parameterized by the asset name — so if the init was built
            // without CIP-68 and this registration is built with it (or the reverse), the two
            // resolve DIFFERENT reward addresses and this transaction is rejected on chain with
            // WithdrawalsNotInRewardsCERTS, after the user has signed and paid for both. Refusing
            // here costs nothing; discovering it on chain costs the init deposit.
            //
            // A null flag means the row pre-dates the column, so there is no evidence either way
            // and the check stays silent rather than blocking a registration that may be fine.
            var initRowOpt = blacklistInitRepository.findByBlacklistNodePolicyId(blacklistNodePolicyId);
            if (initRowOpt.isPresent() && initRowOpt.get().getCip68Enabled() != null
                && initRowOpt.get().getCip68Enabled() != (cip68Metadata != null)) {
                return TransactionContext.typedError(
                        "CIP-68 mismatch: the blacklist init for policy " + blacklistNodePolicyId
                        + " was built " + (initRowOpt.get().getCip68Enabled() ? "WITH" : "WITHOUT")
                        + " CIP-68 metadata, but this registration is "
                        + (cip68Metadata != null ? "WITH" : "WITHOUT")
                        + " it. The init registered the issuer_admin reward account this "
                        + "registration withdraws-0 from, and issuer_admin is parameterized by the "
                        + "asset name — so the labelled and unlabelled forms are different "
                        + "credentials. Re-run the blacklist init with the same CIP-68 setting.");
            }

            /// Getting Substandard Contracts and parameterize
            // Issuer to be used for minting/burning/sieze
            var substandardIssueContract = fesScriptBuilder.buildIssuerAdminScript(Credential.fromKey(request.getAdminPubKeyHash()), userAssetNameHex);
            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            // Transfer contract
            var substandardTransferContract = fesScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    blacklistNodePolicyId
            );
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());
            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());


            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, substandardIssueContract);
            final var progTokenPolicyId = issuanceContract.getPolicyId();

            var registryAddress = AddressProvider.getEntAddress(directorySpendContract, network.getCardanoNetwork());

            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());
            log.info("found {}, registry entries", registryEntries.size());

            var nodeAlreadyPresent = linkedListService.nodeAlreadyPresent(progTokenPolicyId, registryEntries, utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                    .map(RegistryNode::key));

            if (nodeAlreadyPresent) {
                log.warn("registry node already present");
                TransactionContext.error("registry node already present");
            }

            var nodeToReplaceOpt = linkedListService.findNodeToReplace(progTokenPolicyId, registryEntries, utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                    .map(node -> new LinkedListNode(node.key(), node.next())));

            if (nodeToReplaceOpt.isEmpty()) {
                log.warn("could not find node to replace");
                TransactionContext.error("could not find node to replace");
            }

            var directoryUtxo = nodeToReplaceOpt.get();
            log.info("directoryUtxo: {}", directoryUtxo);
            var existingRegistryNodeDatumOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());

            if (existingRegistryNodeDatumOpt.isEmpty()) {
                TransactionContext.error("could not parse current registry node");
            }

            var existingRegistryNodeDatum = existingRegistryNodeDatumOpt.get();

            // Directory MINT - NFT, address, datum and value
            var directoryMintContract = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolParams);
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
                TransactionContext.error("could not find amount for directory mint");
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

            // substandardIssueContract is the freeze-and-seize admin script — "Issuer to
            // be used for minting/burning/sieze" (see its construction above) — so it is
            // both minting_logic_script AND the seize authority at third_party_transfer_logic_script.
            // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN — least permission
            // by default. src/substandards/freeze-and-seize/ declares no unfracking hook validator.
            var directoryMintDatum = new RegistryNode(HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    existingRegistryNodeDatum.next(),
                    Credential.fromScript(substandardIssueContract.getScriptHash()),
                    Credential.fromScript(substandardTransferContract.getScriptHash()),
                    Credential.fromScript(substandardIssueContract.getScriptHash()),
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
            // SmartTokenMintingAction { minting_logic_cred, minting_registry_proof } wrapper is
            // gone (the credential is now the validator's compile-time parameter).
            // Registry node output is at index 2 in outputs:
            // [0] PLB output (programmable token), [1] updated covering node, [2] new registry node
            // The registry node is the LAST output, and CIP-68 inserts the reference-token output
            // ahead of the two node outputs — so the index issuance_mint reads shifts 2 -> 3.
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

            log.info("request.getRecipientAddress(): {}", request.getRecipientAddress());
            var payeeAddress = new Address(request.getRecipientAddress());

            var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    payeeAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            // The (100) reference token is itself a programmable token, so core's `no_escape`
            // forces it to a PLB base address with an inline stake credential. It goes to the
            // ISSUER's, so the issuer can spend it later to rewrite the metadata.
            Asset referenceToken = null;
            Value referenceTokenValue = null;
            Address referenceTokenAddress = null;
            var referenceTokenDatum = cip68Metadata == null ? null : Cip68.buildDatum(cip68Metadata);
            if (cip68Metadata != null) {
                referenceToken = Asset.builder()
                        .name("0x" + referenceAssetNameHex)
                        .value(ONE)
                        .build();
                var issuerAddress = new Address(request.getFeePayerAddress());
                referenceTokenAddress = AddressProvider.getBaseAddress(
                        Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
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
            // issuance_mint constrains no asset names, and the freeze-and-seize issuer_admin
            // validator ignores its `_asset_name` parameter entirely.
            var mintedAssets = cip68Metadata == null
                    ? List.of(programmableToken)
                    : List.of(programmableToken, referenceToken);

            var tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    // No redeemer for substandard
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, ConstrPlutusData.of(0))
                    // Mint Token
                    .mintAsset(issuanceContract, mintedAssets, issuanceRedeemer)
                    // Redeemer is DirectoryInit (constr(0))
                    .mintAsset(directoryMintContract, directoryMintNft, directoryMintRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0));

            // Output 1, CIP-68 only. Must sit between the user token and the two node outputs so
            // `registryNodeOutputIndex` above stays correct.
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
                    .withChangeAddress(request.getFeePayerAddress());

            var firstUtxo = feePayerUtxos.getFirst();
            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh.getBytes())
                    .feePayer(request.getFeePayerAddress())
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtxo.getTxHash())
                            .index(firstUtxo.getOutputIndex())
                            .build())
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(request.getFeePayerAddress())) {
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
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            // Measure the finished CBOR before returning it, so user-supplied metadata that
            // overflows the ledger limit fails here rather than at submission. CIP-68 branch only;
            // the non-CIP-68 path is untouched.
            if (cip68Metadata != null) {
                Cip68.preflightTxSize("freeze-and-seize registration", transaction.serialize(),
                        protocolParamsSupplier.getProtocolParams());
            }

            var blacklistInitOpt = blacklistInitRepository.findByBlacklistNodePolicyId(blacklistNodePolicyId);

            if (blacklistInitOpt.isEmpty()) {
                return TransactionContext.typedError("blacklist init could not be found");
            }

            freezeAndSeizeTokenRegistrationRepository.save(FreezeAndSeizeTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(progTokenPolicyId)
                    .issuerAdminPkh(HexUtil.encodeHexString(adminPkh.getBytes()))
                    .blacklistInit(blacklistInitOpt.get())
                    .build());

            // Save to unified programmable token registry (policyId -> substandardId binding)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .substandardId(SUBSTANDARD_ID)
                    // The LABELLED name, so a later mint/transfer resolves the asset that is
                    // actually on chain rather than the unlabelled base the wizard collected.
                    .assetName(userAssetNameHex)
                    .build());

            hybridUtxoSupplier.clear();

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

        } catch (Exception e) {
            log.error("error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {

            // freeze-and-seize mints its CIP-68 pair at REGISTRATION, so metadata on a later mint
            // has nowhere to go. Refuse instead of ignoring it: silently accepting would leave the
            // caller believing the reference token had been created or updated.
            if (request.cip68Metadata() != null) {
                return TransactionContext.typedError(
                        "cip68Metadata is not accepted on a freeze-and-seize mint — the (100) "
                        + "reference token is minted at registration. To change the metadata, spend "
                        + "the existing reference-token UTxO and rewrite its datum (not supported here).");
            }

            var adminUtxos = accountService.findAdaOnlyUtxo(request.feePayerAddress(), 10_000_000L);

            var bootstrapTxHash = protocolParams.txHash();

            var issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve issuance params");
            }
            var issuanceUtxo = issuanceUtxoOpt.get();
            log.info("issuanceUtxo: {}", issuanceUtxo);

            /// Getting Substandard Contracts and parameterize
            // Issuer to be used for minting/burning/sieze
            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var substandardIssueContract = fesScriptBuilder.buildIssuerAdminScript(Credential.fromKey(context.getIssuerAdminPkh()), request.assetName());
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());


            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, substandardIssueContract);
            log.info("issuanceContract: {}", issuanceContract.getPolicyId());

            // Find the registry node for this token (must exist for subsequent mint)
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());
            final var progTokenPolicyId = issuanceContract.getPolicyId();
            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> {
                        var registryDatumOpt = registryNodeParser.parse(utxo.getInlineDatum());
                        return registryDatumOpt.map(registryDatum -> registryDatum.key().equals(progTokenPolicyId)).orElse(false);
                    })
                    .findAny();
            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }
            var progTokenRegistry = progTokenRegistryOpt.get();
            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();
            // Sole reference input, so the index is trivially 0 -- taken through CoreLayout
            // anyway, because `indexOf` returns -1 for anything it cannot find and -1 inside a
            // redeemer is an out-of-range read the validator reports as an unrelated failure.
            // CoreLayout throws instead, and adding a second reference input here later will
            // then be a change that keeps working rather than one that silently renumbers.
            var mintLayout = CoreLayout.builder().referenceInput(registryRefInput).build();
            var registryRefInputIndex = mintLayout.referenceInputIndex(registryRefInput);

            // types.MintingRegistryProof directly (no SmartTokenMintingAction wrapper in v0.4.0).
            var issuanceRedeemer = CoreRedeemers.mintProofRefInput(registryRefInputIndex);

            // Programmable Token Mint
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

            log.info("request.getRecipientAddress(): {}", request.recipientAddress());
            var payeeAddress = new Address(request.recipientAddress());

            var targetAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    payeeAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());


            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .withdraw(substandardIssueAddress.getAddress(), BigInteger.ZERO, ConstrPlutusData.of(0))
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue), ConstrPlutusData.of(0))
                    .readFrom(registryRefInput)
                    .attachRewardValidator(substandardIssueContract)
                    .withChangeAddress(request.feePayerAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh.getBytes())
                    .feePayer(request.feePayerAddress())
//                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        var outputs = transaction1.getBody().getOutputs();
                        if (outputs.getFirst().getAddress().equals(request.feePayerAddress())) {
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
            log.error("error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }


    }

    @Override
    public TransactionContext<Void> buildBurnTransaction(BurnTokenRequest request, ProtocolBootstrapParams protocolParams) {

        log.info("request: {}", request);

        try {

            var assetTypeToBurn = new AssetType(request.tokenPolicyId(), request.assetName());
            log.info("assetTypeToBurn: {}", assetTypeToBurn);

            var adminUtxos = accountService.findAdaOnlyUtxo(request.feePayerAddress(), 10_000_000L);

            var utxoToBurnOpt = utxoProvider.findUtxo(request.utxoTxHash(), request.utxoOutputIndex());
            if (utxoToBurnOpt.isEmpty()) {
                return TransactionContext.error("utxo to burn could not be found");
            }

            var utxoToBurn = utxoToBurnOpt.get();
            log.info("utxoToBurn: {}", utxoToBurn);

            var utxoTokenAmount = utxoToBurn.toValue().amountOf(assetTypeToBurn.policyId(), "0x" + assetTypeToBurn.assetName());
            log.info("utxoTokenAmount: {}", utxoTokenAmount);

            // FES on-chain validator requires entire policy to be removed from output (dict.delete),
            // so always burn the full UTxO token amount regardless of request.quantity()
            var amountToBurn = utxoTokenAmount;
            log.info("amountToBurn (full UTxO amount): {}", amountToBurn);

            /// Getting Substandard Contracts and parameterize
            // Issuer to be used for minting/burning/sieze
            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var substandardIssueContract = fesScriptBuilder.buildIssuerAdminScript(Credential.fromKey(context.getIssuerAdminPkh()), assetTypeToBurn.assetName());
            log.info("substandardIssueContract: {}", substandardIssueContract.getPolicyId());

            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());


            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, substandardIssueContract);
            log.info("issuanceContract: {}", issuanceContract.getPolicyId());

            // issuanceRedeemer is built below after registryRefInputIndex is computed

            // Programmable Token Mint
            var programmableToken = Asset.builder()
                    .name("0x" + assetTypeToBurn.assetName())
                    .value(amountToBurn.abs().negate())
                    .build();
            log.info("programmableToken: {}", programmableToken);

            // Burn is an ADMINISTRATIVE action, so it goes through `third_party` -- not through
            // `transfer`. Before the coordinator was dissolved both were arms of one validator
            // and the choice was a redeemer constructor; now it is a different script, a
            // different reference script, and a different reward account to withdraw from.
            var coreThirdParty = protocolScriptBuilderService.getParameterizedThirdPartyScript(protocolParams);
            var coreThirdPartyAddress = AddressProvider.getRewardAddress(coreThirdParty, network.getCardanoNetwork());
            var coreThirdPartyCredential = Credential.fromScript(coreThirdParty.getScriptHash());

            // Directory SPEND parameterization
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            log.info("registrySpendContract: {}", HexUtil.encodeHexString(registrySpendContract.getScriptHash()));

            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            log.info("registryAddress: {}", registryAddress.getAddress());

            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> {
                        var registryDatumOpt = registryNodeParser.parse(utxo.getInlineDatum());
                        return registryDatumOpt.map(registryDatum -> registryDatum.key().equals(assetTypeToBurn.policyId())).orElse(false);
                    })
                    .findAny();

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }

            var progTokenRegistry = progTokenRegistryOpt.get();
            log.info("progTokenRegistry: {}", progTokenRegistry);

            var sortedInputUtxos = Stream.concat(adminUtxos.stream(), Stream.of(utxoToBurn))
                    .sorted(new UtxoComparator())
                    .toList();

            var bootstrapTxHash = protocolParams.txHash();

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);

            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();
            var protocolParamsRefInput = TransactionInput.builder()
                    .transactionId(protocolParamsUtxo.getTxHash())
                    .index(protocolParamsUtxo.getOutputIndex())
                    .build();

            var substandardIssueCredential = Credential.fromScript(substandardIssueContract.getScriptHash());
            var layout = CoreLayout.builder()
                    .referenceInput(protocolParamsRefInput)
                    .referenceInput(registryRefInput)
                    .withdrawal(coreThirdPartyCredential)
                    .withdrawal(substandardIssueCredential)
                    .build();
            var sortedReferenceInputs = layout.referenceInputs();
            var paramsIdx = layout.referenceInputIndex(protocolParamsRefInput);
            var registryRefInputInex = layout.referenceInputIndex(registryRefInput);

            // Burn of an already-registered policy: the registry node is a reference input.
            var issuanceRedeemer = CoreRedeemers.mintProofRefInput(registryRefInputInex);

            var seizeInputIndex = sortedInputUtxos.indexOf(utxoToBurn);
            log.info("seizeInputIndex: {}", seizeInputIndex);

            // outputs_start_idx = 0: the burned UTxO's continuation is the first output.
            var coreThirdPartyRedeemer = CoreRedeemers.thirdPartyRedeemer(paramsIdx, registryRefInputInex, 0);

            var baseSpendRedeemer = CoreRedeemers.spendViaThirdParty(
                    paramsIdx, layout.withdrawalIndex(coreThirdPartyCredential));

            var programmableLogicBase = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(protocolParams);
            log.info("programmableLogicBase policy: {}", programmableLogicBase.getPolicyId());

            // Remove the entire policy from the UTxO value (matches on-chain dict.delete behavior)
            var utxoValue = utxoToBurn.toValue();
            var filteredMultiAssets = utxoValue.getMultiAssets() == null
                    ? List.<MultiAsset>of()
                    : utxoValue.getMultiAssets().stream()
                    .filter(ma -> !ma.getPolicyId().equals(assetTypeToBurn.policyId()))
                    .collect(Collectors.toList());
            var returningValue = Value.builder()
                    .coin(utxoValue.getCoin())
                    .multiAssets(filteredMultiAssets)
                    .build();
            log.info("returningValue (policy removed): {}", returningValue);

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(utxoToBurn, baseSpendRedeemer)
                    .payToContract(utxoToBurn.getAddress(), ValueUtil.toAmountList(returningValue), CoreDatums.programmableTokenDatum())
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .readFrom(sortedReferenceInputs.toArray(new TransactionInput[0]))
                    .attachSpendingValidator(programmableLogicBase)
                    .attachRewardValidator(coreThirdParty)
                    .attachRewardValidator(substandardIssueContract)
                    .withChangeAddress(request.feePayerAddress());

            // Withdrawals added in LEDGER order, after the rest of the transaction: their
            // redeemers are matched to entries by position in the canonical map.
            layout.inWithdrawalOrder(
                            List.of(new CoreWithdrawal(substandardIssueCredential, substandardIssueAddress.getAddress(),
                                            ConstrPlutusData.of(0)),
                                    new CoreWithdrawal(coreThirdPartyCredential, coreThirdPartyAddress.getAddress(),
                                            coreThirdPartyRedeemer)),
                            CoreWithdrawal::credential)
                    .forEach(w -> tx.withdraw(w.rewardAddress(), BigInteger.ZERO, w.redeemer()));

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh.getBytes())
                    .feePayer(request.feePayerAddress())
//                .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .mergeOutputs(false) //<-- this is important! or directory tokens will go to same address
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        try {
                            log.info("tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .build();

            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("error", e);
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
            var blacklistNodePolicyId = context.getBlacklistNodePolicyId();

            var adminUtxos = accountService.findAdaOnlyUtxo(senderAddress.getAddress(), 10_000_000L);

            var progToken = AssetType.fromUnit(request.unit());
            log.info("policy id: {}, asset name: {}", progToken.policyId(), progToken.unsafeHumanAssetName());

            // Same reason as the dummy transfer path: every output below is rebuilt with the plain
            // `ConstrPlutusData.of(0)` programmable-token datum, which would erase the CIP-68
            // metadata the (100) token exists to carry and break the pair.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(progToken.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to transfer the CIP-68 (100) reference token: this path rebuilds "
                        + "outputs with the plain programmable-token datum, which would erase the "
                        + "metadata and break the pair. Reference-token custody is not supported.");
            }

            var amountToTransfer = new BigInteger(request.quantity());

            // Directory SPEND parameterization
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            log.info("registrySpendContract: {}", HexUtil.encodeHexString(registrySpendContract.getScriptHash()));

            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            log.info("registryAddress: {}", registryAddress.getAddress());

            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> {
                        var registryDatumOpt = registryNodeParser.parse(utxo.getInlineDatum());
                        return registryDatumOpt.map(registryDatum -> registryDatum.key().equals(progToken.policyId())).orElse(false);
                    })
                    .findAny();

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }

            var progTokenRegistry = progTokenRegistryOpt.get();
            log.info("progTokenRegistry: {}", progTokenRegistry);

            var bootstrapTxHash = protocolParams.txHash();

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);

            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);


            var senderProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    senderAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var recipientProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    receiverAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            var senderProgTokensUtxos = utxoProvider.findUtxos(senderProgrammableTokenAddress.getAddress());


            // The `transfer` delegate. A transfer loads this reference script and neither of the
            // other two -- programmable_logic_base dispatches to exactly one delegate per input.
            var coreTransfer = protocolScriptBuilderService.getParameterizedTransferScript(protocolParams);
            var coreTransferAddress = AddressProvider.getRewardAddress(coreTransfer, network.getCardanoNetwork());
            var coreTransferCredential = Credential.fromScript(coreTransfer.getScriptHash());

            var programmableLogicBase = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(protocolParams);
            log.info("programmableLogicBase policy: {}", programmableLogicBase.getPolicyId());

            // FIXME:
            var parameterisedSubstandardTransferContract = fesScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    blacklistNodePolicyId
            );

            var substandardTransferAddress = AddressProvider.getRewardAddress(parameterisedSubstandardTransferContract, network.getCardanoNetwork());
            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());

            var valueToSend = Value.from(progToken.policyId(), "0x" + progToken.assetName(), amountToTransfer);

            var inputUtxos = senderProgTokensUtxos.stream()
                    .reduce(new Pair<List<Utxo>, Value>(List.of(), Value.builder().build()),
                            (listValuePair, utxo) -> {
                                if (listValuePair.second().subtract(valueToSend).isPositive()) {
                                    return listValuePair;
                                } else {
                                    // `> 0`, not `> 1`. This predicate answers "does this UTxO
                                    // carry any of the token we are moving?", and a UTxO holding
                                    // exactly one unit does. With `> 1` a freshly registered NFT —
                                    // or the last unit of any holding — was invisible to the
                                    // selector, so the transfer collected no inputs and reported
                                    // "Not enough funds" for a balance it could plainly see.
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

            var senderProgTokensValue = inputUtxos.stream()
                    .map(Utxo::toValue)
                    .filter(value -> value.amountOf(progToken.policyId(), "0x" + progToken.assetName()).compareTo(BigInteger.ZERO) > 0)
                    .reduce(Value::add)
                    .orElse(Value.builder().build());

            var returningValue = senderProgTokensValue.subtract(valueToSend);

            var tokenAsset2 = Asset.builder()
                    .name("0x" + progToken.assetName())
                    .value(amountToTransfer)
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

            var progTokenAmount = senderProgTokensValue.amountOf(progToken.policyId(), "0x" + progToken.assetName());
            log.info("progTokenAmount: {}", progTokenAmount);

            if (progTokenAmount.compareTo(amountToTransfer) < 0) {
                return TransactionContext.typedError("Not enough funds");
            }

            var parameterisedBlacklistSpendingScript = fesScriptBuilder.buildBlacklistSpendScript(blacklistNodePolicyId);
            var blacklistAddress = AddressProvider.getEntAddress(parameterisedBlacklistSpendingScript, network.getCardanoNetwork());

            var blacklistUtxos = utxoProvider.findUtxos(blacklistAddress.getAddress());

            var sortedInputUtxos = Stream.concat(adminUtxos.stream(), inputUtxos.stream())
                    .sorted(new UtxoComparator())
                    .toList();

            var proofs = new ArrayList<Pair<Utxo, Utxo>>();
            var progTokenBaseScriptHash = protocolParams.programmableLogicBaseParams().scriptHash();
            for (Utxo utxo : sortedInputUtxos) {
                var address = new Address(utxo.getAddress());
                var addressPkh = address.getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
                if (progTokenBaseScriptHash.equals(addressPkh)) {
                    var stakingPkh = address.getDelegationCredentialHash().map(HexUtil::encodeHexString).get();
                    var relevantBlacklistNodeOpt = blacklistUtxos.stream()
                            .filter(blackListUtxo -> blacklistNodeParser
                                    .parse(blackListUtxo.getInlineDatum())
                                    .map(blacklistNode -> blacklistNode.key().compareTo(stakingPkh) < 0 && blacklistNode.next().compareTo(stakingPkh) > 0)
                                    .orElse(false))
                            .findAny();
                    if (relevantBlacklistNodeOpt.isEmpty()) {
                        return TransactionContext.typedError("could not resolve blacklist exemption");
                    }
                    proofs.add(new Pair<>(utxo, relevantBlacklistNodeOpt.get()));
                }
            }

            var sortedReferenceInputs = Stream.concat(proofs.stream().map(Pair::second).map(utxo -> TransactionInput.builder()
                                    .transactionId(utxo.getTxHash())
                                    .index(utxo.getOutputIndex())
                                    .build()),
                            Stream.of(TransactionInput.builder()
                                    .transactionId(protocolParamsUtxo.getTxHash())
                                    .index(protocolParamsUtxo.getOutputIndex())
                                    .build(), TransactionInput.builder()
                                    .transactionId(progTokenRegistry.getTxHash())
                                    .index(progTokenRegistry.getOutputIndex())
                                    .build())
                    )
                    .toList();

            var protocolParamsRefInput = TransactionInput.builder()
                    .transactionId(protocolParamsUtxo.getTxHash())
                    .index(protocolParamsUtxo.getOutputIndex())
                    .build();
            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();

            var substandardTransferCredential =
                    Credential.fromScript(parameterisedSubstandardTransferContract.getScriptHash());
            var layoutBuilder = CoreLayout.builder()
                    .withdrawal(coreTransferCredential)
                    .withdrawal(substandardTransferCredential);
            sortedReferenceInputs.forEach(layoutBuilder::referenceInput);
            var layout = layoutBuilder.build();
            sortedReferenceInputs = layout.referenceInputs();
            var paramsIdx = layout.referenceInputIndex(protocolParamsRefInput);

            var proofList = proofs.stream().map(pair -> {
                log.info("first: {}, second: {}", pair.first(), pair.second());
                var index = layout.referenceInputIndex(TransactionInput.builder().transactionId(pair.second().getTxHash()).index(pair.second().getOutputIndex()).build());
                log.info("adding index: {} as a blacklist non-belonging proof", index);
                return ConstrPlutusData.of(0, BigIntPlutusData.of(index));
            }).toList();
            var freezeAndSeizeRedeemer = ListPlutusData.of();
            proofList.forEach(freezeAndSeizeRedeemer::add);

            var coreTransferRedeemer = CoreRedeemers.transferRedeemer(paramsIdx, List.of(
                    CoreRedeemers.tokenExists(layout.referenceInputIndex(registryRefInput))));

            var baseSpendRedeemer = CoreRedeemers.spendViaTransfer(
                    paramsIdx, layout.withdrawalIndex(coreTransferCredential));

            var tx = new Tx()
                    .collectFrom(adminUtxos);

            inputUtxos.forEach(utxo -> tx.collectFrom(utxo, baseSpendRedeemer));

            // Added in LEDGER order: withdrawal redeemers are matched to entries by position in
            // the canonical map, so pre-sorting removes any dependence on the serialiser
            // re-indexing them afterwards.
            layout.inWithdrawalOrder(
                            List.of(new CoreWithdrawal(substandardTransferCredential, substandardTransferAddress.getAddress(), freezeAndSeizeRedeemer),
                                    new CoreWithdrawal(coreTransferCredential, coreTransferAddress.getAddress(), coreTransferRedeemer)),
                            CoreWithdrawal::credential)
                    .forEach(w -> tx.withdraw(w.rewardAddress(), BigInteger.ZERO, w.redeemer()));

            tx.payToContract(senderProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(returningValue), CoreDatums.programmableTokenDatum())
                    .payToContract(recipientProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(tokenValue2), CoreDatums.programmableTokenDatum());

            sortedReferenceInputs.forEach(tx::readFrom);

            tx.attachRewardValidator(coreTransfer)
                    .attachRewardValidator(parameterisedSubstandardTransferContract)
                    .attachSpendingValidator(programmableLogicBase) // base
                    .withChangeAddress(senderAddress.getAddress());


            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(senderAddress.getDelegationCredentialHash().get())
                    .additionalSignersCount(1)
                    .feePayer(senderAddress.getAddress())
                    .mergeOutputs(false)
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .build();


            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }


    }

    // ========== BlacklistManageable Implementation ==========

    @Override
    public TransactionContext<MintingResult> buildBlacklistInitTransaction(BlacklistInitRequest request, ProtocolBootstrapParams protocolParams) {

        try {

            log.info("blacklistInitRequest: {}", request);

            var adminAddress = new Address(request.adminAddress());

            var utilityUtxos = accountService.findAdaOnlyUtxo(request.feePayerAddress(), 10_000_000L);
            log.info("admin utxos size: {}", utilityUtxos.size());

            var utilityAdaBalance = utilityUtxos.stream()
                    .flatMap(utxo -> utxo.getAmount().stream())
                    .map(Amount::getQuantity)
                    .reduce(BigInteger::add)
                    .orElse(ZERO);

            log.info("utility ada balance: {}", utilityAdaBalance);

            var bootstrapUtxo = utilityUtxos.getFirst();
            log.info("bootstrapUtxo: {}", bootstrapUtxo);

            var bootstrapUtxoOpt = utxoProvider.findUtxo(bootstrapUtxo.getTxHash(), bootstrapUtxo.getOutputIndex());

            if (bootstrapUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("no utxo found");
            }

            var bootstrapTxInput = TransactionInput.builder()
                    .transactionId(bootstrapUtxo.getTxHash())
                    .index(bootstrapUtxo.getOutputIndex())
                    .build();

            var adminPkhBytes = adminAddress.getPaymentCredentialHash().get();
            var adminPkh = HexUtil.encodeHexString(adminPkhBytes);

            // Build both blacklist scripts at once
            var blacklistScripts = fesScriptBuilder.buildBlacklistScripts(bootstrapTxInput, adminPkh);
            var parameterisedBlacklistMintingScript = blacklistScripts.first();
            var parameterisedBlacklistSpendingScript = blacklistScripts.second();

            var blacklistSpendAddress = AddressProvider.getEntAddress(parameterisedBlacklistSpendingScript, network.getCardanoNetwork());

            var blacklistInitDatum = BlacklistNode.builder()
                    .key("")
                    .next("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
                    .build();

            var blacklistAsset = Asset.builder().name("0x").value(ONE).build();

            var blacklistNft = Asset.builder()
                    .name("0x")
                    .value(BigInteger.ONE)
                    .build();

            Value blacklistValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(parameterisedBlacklistMintingScript.getPolicyId())
                                    .assets(List.of(blacklistNft))
                                    .build()
                    ))
                    .build();

            // Stake Address Registration
            //
            // This is the ONLY place the issuer_admin reward account gets registered
            // (buildPreRegistrationTransaction deliberately errors for this substandard), and the
            // registration transaction withdraws-0 from it. issuer_admin is parameterized by the
            // asset name, so a CIP-68 registration — which uses the LABELLED name — resolves to a
            // different reward address. Registering the unlabelled one here would leave that
            // withdrawal pointing at an unregistered account, and the registration would be
            // rejected with WithdrawalsNotInRewardsCERTS after the user had already signed and
            // paid for this transaction. So apply exactly the same label rule as registration.
            //
            // That rule is now quantity-INDEPENDENT — always (333) — which is what makes the two
            // sites impossible to desynchronise. Previously the label came from `quantity`, and
            // `quantity` is optional on this request: a null one became 0, which chose (333),
            // while a registration asking for 1 chose (222). The already-paid init had then
            // registered a reward account the registration would never withdraw from. Only the
            // PRESENCE of cip68Metadata can differ now, and that is cross-checked below.
            var initAssetNameHex = request.cip68Metadata() == null
                    ? request.assetName()
                    : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), request.assetName());
            if (request.cip68Metadata() != null
                && (request.quantity() == null || request.quantity().isBlank())) {
                return TransactionContext.typedError(
                        "quantity is required when cip68Metadata is present, and must match the "
                        + "registration that follows this blacklist init.");
            }
            var substandardIssueContract = fesScriptBuilder.buildIssuerAdminScript(Credential.fromKey(adminPkh), initAssetNameHex);
            var substandardIssueAddress = AddressProvider.getRewardAddress(substandardIssueContract, network.getCardanoNetwork());
            log.info("substandardIssueAddress: {}", substandardIssueAddress.getAddress());

            var substandardTransferContract = fesScriptBuilder.buildTransferScript(
                    protocolParams.programmableLogicBaseParams().scriptHash(),
                    parameterisedBlacklistMintingScript.getPolicyId()
            );
            var substandardTransferAddress = AddressProvider.getRewardAddress(substandardTransferContract, network.getCardanoNetwork());
            log.info("substandardTransferAddress: {}", substandardTransferAddress.getAddress());


            var requiredStakeAddresses = Stream.of(substandardIssueAddress, substandardTransferAddress)
                    .map(Address::getAddress)
                    .toList();
            log.info("requiredStakeAddresses: {}", String.join(", ", requiredStakeAddresses));

            var registeredStakeAddresses = requiredStakeAddresses.stream()
                    .filter(scriptRegistrationService::isStakeAddressRegistered)
                    .toList();
            log.info("registeredStakeAddresses: {}", String.join(", ", registeredStakeAddresses));

            var stakeAddressesToRegister = requiredStakeAddresses.stream()
                    .filter(stakeAddress -> !registeredStakeAddresses.contains(stakeAddress))
                    .toList();
            log.info("stakeAddressesToRegister: {}", String.join(", ", stakeAddressesToRegister));

            var tx = new Tx()
                    .collectFrom(utilityUtxos)
                    .mintAsset(parameterisedBlacklistMintingScript, blacklistAsset, ConstrPlutusData.of(0))
                    // Can be used to chain tx
                    .payToAddress(request.feePayerAddress(), Amount.ada(40L))
                    .payToContract(blacklistSpendAddress.getAddress(), ValueUtil.toAmountList(blacklistValue), blacklistInitDatum.toPlutusData())
                    .withChangeAddress(request.feePayerAddress());

            // Same evidence rule as the pre-registration path above: the blacklist init is the
            // other place this platform emits these certificates.
            stakeAddressesToRegister.forEach(addr -> {
                scriptRegistrationService.noteRegistrationAttempted(addr, null);
                tx.registerStakeAddress(addr);
            });

            var transaction = new QuickTxBuilder(bfBackendService).compose(tx)
                    .feePayer(request.feePayerAddress())
                    .ignoreScriptCostEvaluationError(false)
                    .mergeOutputs(false)
                    .build();

            log.info("transaction: {}", transaction.serializeToHex());
            log.info("transaction: {}", objectMapper.writeValueAsString(transaction));

            var mintBootstrap = new BlacklistMintBootstrap(TxInput.from(bootstrapUtxo), adminPkh, parameterisedBlacklistMintingScript.getPolicyId());
            var spendBootstrap = new BlacklistSpendBootstrap(parameterisedBlacklistMintingScript.getPolicyId(), parameterisedBlacklistSpendingScript.getPolicyId());
            var bootstrap = new BlacklistBootstrap(mintBootstrap, spendBootstrap);

            var stringBoostrap = objectMapper.writeValueAsString(bootstrap);
            log.info("bootstrap: {}", stringBoostrap);

            blacklistInitRepository.save(BlacklistInitEntity.builder()
                    .blacklistNodePolicyId(parameterisedBlacklistMintingScript.getPolicyId())
                    .adminPkh(adminPkh)
                    .txHash(bootstrapUtxo.getTxHash())
                    .outputIndex(bootstrapUtxo.getOutputIndex())
                    // Which issuer_admin credential this init registered — labelled or not. The
                    // registration that follows withdraws-0 from that credential, so the two must
                    // agree; recording it lets registration refuse BEFORE building rather than
                    // fail on chain after the user has paid for both transactions.
                    .cip68Enabled(request.cip68Metadata() != null)
                    .build());

            return TransactionContext.ok(transaction.serializeToHex(), new MintingResult(parameterisedBlacklistMintingScript.getPolicyId(), ""));

        } catch (Exception e) {
            return TransactionContext.typedError(String.format("could not build transaction: %s", e.getMessage()));
        }

    }

    @Override
    public TransactionContext<Void> buildAddToBlacklistTransaction(AddToBlacklistRequest request, ProtocolBootstrapParams protocolParams) {

        try {
            log.info("addToBlacklistRequest: {}", request);

            var blacklistedAddress = new Address(request.targetAddress());

            var adminUtxos = accountService.findAdaOnlyUtxoByPaymentPubKeyHash(context.getBlacklistManagerPkh(), 10_000_000L);
            log.info("admin utxos size: {}", adminUtxos.size());
            var adminAdaBalance = adminUtxos.stream()
                    .flatMap(utxo -> utxo.getAmount().stream())
                    .map(Amount::getQuantity)
                    .reduce(BigInteger::add)
                    .orElse(ZERO);
            log.info("admin ada balance: {}", adminAdaBalance);

            // Build both blacklist scripts at once
            var blacklistScripts = fesScriptBuilder.buildBlacklistScripts(
                    context.getBlacklistInitTxInput(),
                    context.getIssuerAdminPkh()
            );
            var parameterisedBlacklistMintingScript = blacklistScripts.first();
            var parameterisedBlacklistSpendingScript = blacklistScripts.second();
            log.info("parameterisedBlacklistSpendingScript: {}", parameterisedBlacklistSpendingScript.getPolicyId());

            var blacklistSpendAddress = AddressProvider.getEntAddress(parameterisedBlacklistSpendingScript, network.getCardanoNetwork());
            log.info("blacklistSpend: {}", blacklistSpendAddress.getAddress());

            var blacklistUtxos = utxoProvider.findUtxos(blacklistSpendAddress.getAddress());
            log.info("blacklistUtxos: {}", blacklistUtxos.size());
            blacklistUtxos.forEach(utxo -> log.info("bl utxo: {}", utxo));

            var aliceStakingPkh = blacklistedAddress.getDelegationCredentialHash().map(HexUtil::encodeHexString).get();
            var blocklistNodeToReplaceOpt = blacklistUtxos.stream()
                    .flatMap(utxo -> blacklistNodeParser.parse(utxo.getInlineDatum())
                            .stream()
                            .flatMap(blacklistNode -> Stream.of(new Pair<>(utxo, blacklistNode))))
                    .filter(utxoBlacklistNodePair -> {
                        var datum = utxoBlacklistNodePair.second();
                        return datum.key().compareTo(aliceStakingPkh) < 0 && aliceStakingPkh.compareTo(datum.next()) < 0;
                    })
                    .findAny();

            if (blocklistNodeToReplaceOpt.isEmpty()) {
                return TransactionContext.error("could not find blocklist node to replace");
            }

            var blocklistNodeToReplace = blocklistNodeToReplaceOpt.get();
            log.info("blocklistNodeToReplace: {}", blocklistNodeToReplace);

            var preexistingNode = blocklistNodeToReplace.second();

            var beforeNode = preexistingNode.toBuilder().next(aliceStakingPkh).build();
            var afterNode = preexistingNode.toBuilder().key(aliceStakingPkh).build();

            var mintRedeemer = ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(aliceStakingPkh)));

            // Before/Updated
            var preExistingAmount = blocklistNodeToReplace.first().getAmount();
            // Next/minted
            var mintedAmount = Value.from(parameterisedBlacklistMintingScript.getPolicyId(), "0x" + aliceStakingPkh, ONE);

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(blocklistNodeToReplace.first(), ConstrPlutusData.of(0))
                    .mintAsset(parameterisedBlacklistMintingScript, Asset.builder().name("0x" + aliceStakingPkh).value(ONE).build(), mintRedeemer)
                    // Replaced
                    .payToContract(blacklistSpendAddress.getAddress(), preExistingAmount, beforeNode.toPlutusData())
                    .payToContract(blacklistSpendAddress.getAddress(), ValueUtil.toAmountList(mintedAmount), afterNode.toPlutusData())
                    .attachSpendingValidator(parameterisedBlacklistSpendingScript)
                    .withChangeAddress(request.feePayerAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getBlacklistManagerPkh()))
                    .feePayer(request.feePayerAddress())
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .mergeOutputs(false)
                    .build();

            log.info("transaction: {}", transaction.serializeToHex());
            log.info("transaction: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            return TransactionContext.error(String.format("error: %s", e.getMessage()));

        }

    }

    @Override
    public TransactionContext<Void> buildRemoveFromBlacklistTransaction(
            RemoveFromBlacklistRequest request,
            ProtocolBootstrapParams protocolParams) {

        try {

            var targetAddress = new Address(request.targetAddress());

            var adminUtxos = accountService.findAdaOnlyUtxoByPaymentPubKeyHash(context.getBlacklistManagerPkh(), 10_000_000L);
            log.info("admin utxos size: {}", adminUtxos.size());
            var adminAdaBalance = adminUtxos.stream()
                    .flatMap(utxo -> utxo.getAmount().stream())
                    .map(Amount::getQuantity)
                    .reduce(BigInteger::add)
                    .orElse(ZERO);
            log.info("admin ada balance: {}", adminAdaBalance);

            // Build both blacklist scripts at once
            var blacklistScripts = fesScriptBuilder.buildBlacklistScripts(
                    context.getBlacklistInitTxInput(),
                    context.getIssuerAdminPkh()
            );
            var parameterisedBlacklistMintingScript = blacklistScripts.first();
            var parameterisedBlacklistSpendingScript = blacklistScripts.second();
            log.info("parameterisedBlacklistSpendingScript: {}", parameterisedBlacklistSpendingScript.getPolicyId());

            var blacklistSpendAddress = AddressProvider.getEntAddress(parameterisedBlacklistSpendingScript, network.getCardanoNetwork());
            log.info("blacklistSpend: {}", blacklistSpendAddress.getAddress());

            var blacklistUtxos = utxoProvider.findUtxos(blacklistSpendAddress.getAddress());
            log.info("blacklistUtxos: {}", blacklistUtxos.size());
            blacklistUtxos.forEach(utxo -> log.info("bl utxo: {}", utxo));

            var credentialsToRemove = targetAddress.getDelegationCredentialHash().map(HexUtil::encodeHexString).get();

            var blocklistNodeToRemoveOpt = blacklistUtxos.stream()
                    .flatMap(utxo -> blacklistNodeParser.parse(utxo.getInlineDatum())
                            .stream()
                            .flatMap(blacklistNode -> Stream.of(new Pair<>(utxo, blacklistNode))))
                    .filter(utxoBlacklistNodePair -> {
                        var datum = utxoBlacklistNodePair.second();
                        return datum.key().equals(credentialsToRemove);
                    })
                    .findAny();

            var blocklistNodeToUpdateOpt = blacklistUtxos.stream()
                    .flatMap(utxo -> blacklistNodeParser.parse(utxo.getInlineDatum())
                            .stream()
                            .flatMap(blacklistNode -> Stream.of(new Pair<>(utxo, blacklistNode))))
                    .filter(utxoBlacklistNodePair -> {
                        var datum = utxoBlacklistNodePair.second();
                        return datum.next().equals(credentialsToRemove);
                    })
                    .findAny();

            if (blocklistNodeToRemoveOpt.isEmpty() || blocklistNodeToUpdateOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve relevant blacklist nodes");
            }

            var blocklistNodeToRemove = blocklistNodeToRemoveOpt.get();
            log.info("blocklistNodeToRemove: {}", blocklistNodeToRemove);

            var blocklistNodeToUpdate = blocklistNodeToUpdateOpt.get();
            log.info("blocklistNodeToUpdate: {}", blocklistNodeToUpdate);

            var newNext = blocklistNodeToRemove.second().next();
            var updatedNode = blocklistNodeToUpdate.second().toBuilder().next(newNext).build();

            var mintRedeemer = ConstrPlutusData.of(2, BytesPlutusData.of(HexUtil.decodeHexString(credentialsToRemove)));

            // Before/Updated
            var preExistingAmount = blocklistNodeToUpdate.first().getAmount();

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(blocklistNodeToRemove.first(), ConstrPlutusData.of(0))
                    .collectFrom(blocklistNodeToUpdate.first(), ConstrPlutusData.of(0))
                    .mintAsset(parameterisedBlacklistMintingScript, Asset.builder().name("0x" + credentialsToRemove).value(ONE.negate()).build(), mintRedeemer)
                    // Replaced
                    .payToContract(blacklistSpendAddress.getAddress(), preExistingAmount, updatedNode.toPlutusData())
                    .attachSpendingValidator(parameterisedBlacklistSpendingScript)
                    .withChangeAddress(request.feePayerAddress());

            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(context.getBlacklistManagerPkh()))
                    .feePayer(request.feePayerAddress())
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .mergeOutputs(false)
                    .build();

            log.info("transaction: {}", transaction.serializeToHex());
            log.info("transaction: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            return TransactionContext.error(String.format("error: %s", e.getMessage()));

        }

    }

    /**
     * Check if an address is currently blacklisted.
     * This is a read-only query operation that checks the on-chain blacklist linked-list.
     *
     * @param address The bech32 address to check
     * @return true if the address is blacklisted (frozen), false otherwise
     */
    public boolean isAddressBlacklisted(String address) {
        try {
            log.debug("Checking blacklist status for address: {}", address);

            // 1. Extract stake credential from address (same as add/remove operations)
            var targetAddress = new Address(address);
            var credentialHashOpt = targetAddress.getDelegationCredentialHash()
                    .map(HexUtil::encodeHexString);

            if (credentialHashOpt.isEmpty()) {
                log.debug("Address {} has no stake credential", address);
                return false; // No stake credential = cannot be blacklisted
            }

            var credentialHash = credentialHashOpt.get();
            log.debug("Extracted stake credential: {}", credentialHash);

            // 2. Derive blacklist mint and spend scripts
            var blacklistScripts = fesScriptBuilder.buildBlacklistScripts(
                    context.getBlacklistInitTxInput(),
                    context.getIssuerAdminPkh()
            );
            var parameterisedBlacklistMintingScript = blacklistScripts.first();
            var parameterisedBlacklistSpendingScript = blacklistScripts.second();
            var blacklistPolicyId = parameterisedBlacklistMintingScript.getPolicyId();
            log.debug("Derived blacklist policy ID: {}", blacklistPolicyId);

            // 3. Compute blacklist spend address
            var blacklistSpendAddress = AddressProvider.getEntAddress(
                    parameterisedBlacklistSpendingScript,
                    network.getCardanoNetwork()
            );
            log.debug("Derived blacklist spend address: {}", blacklistSpendAddress.getAddress());

            // 5. Query UTxOs at blacklist address
            var blacklistUtxos = utxoProvider.findUtxos(blacklistSpendAddress.getAddress());
            log.debug("Found {} blacklist UTxOs", blacklistUtxos.size());

            // 6. Parse datums and check if credential is in the list
            boolean isBlacklisted = blacklistUtxos.stream()
                    .flatMap(utxo -> blacklistNodeParser.parse(utxo.getInlineDatum()).stream())
                    .anyMatch(blacklistNode -> blacklistNode.key().equals(credentialHash));

            log.debug("Address {} is blacklisted: {}", address, isBlacklisted);
            return isBlacklisted;

        } catch (Exception e) {
            log.error("Error checking blacklist status for address: {}", address, e);
            // Fail-safe: return false to avoid blocking legitimate users
            return false;
        }
    }

    // ========== Seizeable Implementation ==========

    @Override
    public TransactionContext<Void> buildSeizeTransaction(
            SeizeRequest request,
            ProtocolBootstrapParams protocolParams) {


        try {

            log.info("request: {}", request);

            var feePayerAddress = new Address(request.feePayerAddress());
            var feePayerPkh = feePayerAddress.getPaymentCredentialHash().map(HexUtil::encodeHexString).get();

            var adminUtxos = accountService.findAdaOnlyUtxoByPaymentPubKeyHash(feePayerPkh, 10_000_000L);
            log.info("adminUtxos: {}", adminUtxos);

            var bootstrapTxHash = protocolParams.txHash();

            var progToken = AssetType.fromUnit(request.unit());
            log.info("policy id: {}, asset name: {}", progToken.policyId(), progToken.unsafeHumanAssetName());

            // A (100) reference token cannot be seized, and the reason is structural rather than a
            // policy choice. `substandardIssueAdminContract` below is parameterized by the asset
            // name of the SELECTED unit, but registration parameterized issuer_admin by the
            // USER-token name — so seizing the (100) derives a different script, a different reward
            // credential, and a withdraw-0 from an account that was never registered. The
            // transaction cannot validate. (Even if it could, the continuing output is rebuilt
            // below and ThirdPartyAct requires address, datum and reference script unchanged.)
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(progToken.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to seize the CIP-68 (100) reference token: issuer_admin is "
                        + "parameterized by the USER token's name, so a seizure keyed on the (100) "
                        + "name resolves a different reward credential and can never validate. "
                        + "Seize the (222)/(333) user token instead.");
            }

            // Directory SPEND parameterization
            var registrySpendContract = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            log.info("registrySpendContract: {}", HexUtil.encodeHexString(registrySpendContract.getScriptHash()));

            var registryAddress = AddressProvider.getEntAddress(registrySpendContract, network.getCardanoNetwork());
            log.info("registryAddress: {}", registryAddress.getAddress());

            var registryEntries = utxoProvider.findUtxos(registryAddress.getAddress());

            var progTokenRegistryOpt = registryEntries.stream()
                    .filter(utxo -> {
                        var registryDatumOpt = registryNodeParser.parse(utxo.getInlineDatum());
                        return registryDatumOpt.map(registryDatum -> registryDatum.key().equals(progToken.policyId())).orElse(false);
                    })
                    .findAny();

            if (progTokenRegistryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }

            var progTokenRegistry = progTokenRegistryOpt.get();
            log.info("progTokenRegistry: {}", progTokenRegistry);

            var registryOpt = registryNodeParser.parse(progTokenRegistry.getInlineDatum());
            if (registryOpt.isEmpty()) {
                return TransactionContext.typedError("could not find registry entry for token");
            }

            var registry = registryOpt.get();
            log.info("registry: {}", registry);

            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);

            if (protocolParamsUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol params");
            }

            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            log.info("protocolParamsUtxo: {}", protocolParamsUtxo);

            var utxoOpt = utxoProvider.findUtxo(request.utxoTxHash(), request.utxoOutputIndex());

            if (utxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not find utxo to seize");
            }

            var utxoToSeize = utxoOpt.get();

//            var seizedAddress = aliceAccount.getBaseAddress();
//            var seizedProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolBootstrapParams.programmableLogicBaseParams().scriptHash()),
//                    seizedAddress.getDelegationCredential().get(),
//                    network);

            var recipientAddress = new Address(request.destinationAddress());
            var recipientProgrammableTokenAddress = AddressProvider.getBaseAddress(Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());


            // Seizure is an ADMINISTRATIVE action: it goes through `third_party`, which since the
            // coordinator was dissolved is a separate validator with its own reference script and
            // its own reward account -- not a redeemer constructor on the transfer script.
            var coreThirdParty = protocolScriptBuilderService.getParameterizedThirdPartyScript(protocolParams);
            var coreThirdPartyAddress = AddressProvider.getRewardAddress(coreThirdParty, network.getCardanoNetwork());
            var coreThirdPartyCredential = Credential.fromScript(coreThirdParty.getScriptHash());

            var programmableLogicBase = protocolScriptBuilderService.getParameterizedProgrammableLogicBaseScript(protocolParams);
            log.info("programmableLogicBase policy: {}", programmableLogicBase.getPolicyId());

            // Issuer to be used for minting/burning/sieze
            log.info("context.getIssuerAdminPkh(): {}", context.getIssuerAdminPkh());
            var adminPkh = Credential.fromKey(context.getIssuerAdminPkh());
            var substandardIssueAdminContract = fesScriptBuilder.buildIssuerAdminScript(Credential.fromKey(context.getIssuerAdminPkh()), progToken.assetName());
            log.info("substandardIssueAdminContract: {}", substandardIssueAdminContract.getPolicyId());

            var substandardIssueAdminAddress = AddressProvider.getRewardAddress(substandardIssueAdminContract, network.getCardanoNetwork());

            var substandardTransferContractOpt = substandardService.getSubstandardValidator(SUBSTANDARD_ID, "example_transfer_logic.transfer.withdraw");
            if (substandardTransferContractOpt.isEmpty()) {
                log.warn("could not resolve transfer contract");
                return TransactionContext.typedError("could not resolve transfer contract");
            }


            // ThirdPartyAct requires the CONTINUING output to carry the same address, datum and
            // reference script as the input it continues — only the seized value may move. So the
            // datum has to be the input's own bytes, not a freshly minted ConstrPlutusData.of(0):
            // those coincide for an ordinary programmable-token UTxO and diverge for anything
            // else, and "coincides today" is not a property worth relying on.
            if (utxoToSeize.getReferenceScriptHash() != null && !utxoToSeize.getReferenceScriptHash().isBlank()) {
                return TransactionContext.typedError(
                        "refusing to seize a UTxO carrying a reference script: ThirdPartyAct "
                        + "requires the continuing output to preserve it, and this builder cannot "
                        + "re-attach it. utxo=" + utxoToSeize.getTxHash() + "#" + utxoToSeize.getOutputIndex());
            }
            if (utxoToSeize.getInlineDatum() == null || utxoToSeize.getInlineDatum().isBlank()) {
                return TransactionContext.typedError(
                        "refusing to seize a UTxO with no inline datum: ThirdPartyAct requires the "
                        + "continuing output's datum to match the input's, and there is nothing to "
                        + "carry forward. utxo=" + utxoToSeize.getTxHash() + "#" + utxoToSeize.getOutputIndex());
            }
            PlutusData continuingDatum;
            try {
                continuingDatum = PlutusData.deserialize(HexUtil.decodeHexString(utxoToSeize.getInlineDatum()));
            } catch (Exception e) {
                return TransactionContext.typedError(
                        "could not decode the seized UTxO's inline datum, so it cannot be preserved "
                        + "on the continuing output: " + e.getMessage());
            }

            var valueToSeize = utxoToSeize.toValue().amountOf(progToken.policyId(), "0x" + progToken.assetName());
            log.info("amount to seize: {}", valueToSeize);

            var tokenAssetToSeize = Value.from(progToken.policyId(), "0x" + progToken.assetName(), valueToSeize);

            var sortedInputs = Stream.concat(adminUtxos.stream(), Stream.of(utxoToSeize))
                    .sorted(new UtxoComparator())
                    .toList();

            var seizeInputIndex = sortedInputs.indexOf(utxoToSeize);
            log.info("seizeInputIndex: {}", seizeInputIndex);

            var registryRefInput = TransactionInput.builder()
                    .transactionId(progTokenRegistry.getTxHash())
                    .index(progTokenRegistry.getOutputIndex())
                    .build();
            var protocolParamsRefInput = TransactionInput.builder()
                    .transactionId(protocolParamsUtxo.getTxHash())
                    .index(protocolParamsUtxo.getOutputIndex())
                    .build();

            var substandardIssueAdminCredential =
                    Credential.fromScript(substandardIssueAdminContract.getScriptHash());
            var layout = CoreLayout.builder()
                    .referenceInput(protocolParamsRefInput)
                    .referenceInput(registryRefInput)
                    .withdrawal(coreThirdPartyCredential)
                    .withdrawal(substandardIssueAdminCredential)
                    .build();
            var sortedReferenceInputs = layout.referenceInputs();
            var paramsIdx = layout.referenceInputIndex(protocolParamsRefInput);
            var registryRefInputInex = layout.referenceInputIndex(registryRefInput);

            // outputs_start_idx = 1: output 0 is the seized value's destination, output 1 is the
            // CONTINUING output paired with the seized input. third_party pairs every acted-on
            // PLB input positionally from this offset, and each pair must agree byte-for-byte on
            // address, datum and reference script -- a seizure moves tokens, not ownership.
            var coreThirdPartyRedeemer = CoreRedeemers.thirdPartyRedeemer(paramsIdx, registryRefInputInex, 1);

            var baseSpendRedeemer = CoreRedeemers.spendViaThirdParty(
                    paramsIdx, layout.withdrawalIndex(coreThirdPartyCredential));

            var tx = new Tx()
                    .collectFrom(adminUtxos)
                    .collectFrom(utxoToSeize, baseSpendRedeemer)
                    .payToContract(recipientProgrammableTokenAddress.getAddress(), ValueUtil.toAmountList(tokenAssetToSeize), CoreDatums.programmableTokenDatum())
                    // The CONTINUING output: same address, same datum as the input it continues.
                    .payToContract(utxoToSeize.getAddress(), ValueUtil.toAmountList(utxoToSeize.toValue().subtract(tokenAssetToSeize)), continuingDatum)
                    .readFrom(sortedReferenceInputs.toArray(new TransactionInput[0]))
                    .attachRewardValidator(coreThirdParty)
                    .attachRewardValidator(substandardIssueAdminContract)
                    .attachSpendingValidator(programmableLogicBase)
                    .withChangeAddress(feePayerAddress.getAddress());

            // Withdrawals in LEDGER order: their redeemers are matched to entries by position in
            // the canonical map.
            layout.inWithdrawalOrder(
                            List.of(new CoreWithdrawal(substandardIssueAdminCredential,
                                            substandardIssueAdminAddress.getAddress(), ConstrPlutusData.of(0)),
                                    new CoreWithdrawal(coreThirdPartyCredential,
                                            coreThirdPartyAddress.getAddress(), coreThirdPartyRedeemer)),
                            CoreWithdrawal::credential)
                    .forEach(w -> tx.withdraw(w.rewardAddress(), BigInteger.ZERO, w.redeemer()));


            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(feePayerAddress.getAddress())
                    .mergeOutputs(false)
                    .preBalanceTx((txBuilderContext, transaction1) -> {
                        try {
                            log.info("pre balance tx: {}", objectMapper.writeValueAsString(transaction1));
                        } catch (JsonProcessingException e) {
                            throw new RuntimeException(e);
                        }
                    })
//                    .withTxEvaluator(new AikenTransactionEvaluator(bfBackendService))
                    .withRequiredSigners(adminPkh.getBytes())
                    .build();


            log.info("tx: {}", transaction.serializeToHex());
            log.info("tx: {}", objectMapper.writeValueAsString(transaction));

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.warn("error", e);
            return TransactionContext.typedError("error: " + e.getMessage());
        }


    }

    @Override
    public TransactionContext<Void> buildMultiSeizeTransaction(
            MultiSeizeRequest request,
            ProtocolBootstrapParams protocolParams) {
        // TODO: Implement multi-seize for efficiency
        // Similar to single seize but processes multiple UTxOs
        log.warn("buildMultiSeizeTransaction not yet implemented");
        return TransactionContext.typedError("Not yet implemented");
    }

}
