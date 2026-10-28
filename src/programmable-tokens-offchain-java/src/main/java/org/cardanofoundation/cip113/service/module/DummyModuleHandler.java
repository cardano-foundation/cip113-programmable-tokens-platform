package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.programmabletoken.BurnAuthorization;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenCredential;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenPolicyRef;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenRegistration;
import com.bloxbean.cardano.client.programmabletoken.ProgrammableTokenTx;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113ProgrammableTokenService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.model.AssetType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.ccl.CclDeployments;
import org.cardanofoundation.cip113.ccl.CclQuickTx;
import org.cardanofoundation.cip113.ccl.CclWallets;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.core.CoreDatums;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.ModuleService;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.cardanofoundation.cip113.service.UtxoProvider;
import org.cardanofoundation.cip113.service.module.capabilities.BasicOperations;
import org.cardanofoundation.cip113.util.Cip68;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static java.math.BigInteger.ONE;

/**
 * Handler for the "dummy" programmable token module.
 * This is a simple reference implementation with basic issue and transfer validators.
 *
 * <p>Capabilities: {@link BasicOperations} only (register, mint, burn, transfer)</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DummyModuleHandler implements ModuleHandler, BasicOperations<DummyRegisterRequest> {

    private static final String MODULE_ID = "dummy";

    /** dummy's {@code issue} validator accepts exactly {@code redeemer == 100}. */
    private static final PlutusData ISSUE = BigIntPlutusData.of(100);

    /** dummy's {@code transfer} validator accepts exactly {@code redeemer == 200}. */
    private static final PlutusData TRANSFER = BigIntPlutusData.of(200);

    /** The name the registration and its first mint refer to each other by, within one transaction. */
    private static final String REGISTRATION = "token";

    /** The inline datum the platform writes on every holding ({@code Constr 0 []}). */
    private static final PlutusData HOLDING = CoreDatums.programmableTokenDatum();

    private final ObjectMapper objectMapper;

    private final AppConfig.Network network;

    private final UtxoRepository utxoRepository;

    private final UtxoProvider utxoProvider;

    private final RegistryNodeParser registryNodeParser;

    private final AccountService accountService;

    private final ModuleService moduleService;

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

    /** CCL's programmable-token service for the deployment each request names. */
    private final CclDeployments cclDeployments;

    /** A fresh CCL-extended builder per transaction, on the platform's suppliers. */
    private final CclQuickTx cclQuickTx;

    @Override
    public String getModuleId() {
        return MODULE_ID;
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
            var moduleIssuanceContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.issue.withdraw");
            var moduleTransferContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.transfer.withdraw");

            if (moduleIssuanceContractOpt.isEmpty() || moduleTransferContractOpt.isEmpty()) {
                log.warn("module issuance or transfer contract are empty");
                return TransactionContext.typedError("module issuance or transfer contract are empty");
            }

            var moduleIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            log.info("moduleIssueContract: {}", moduleIssueContract.getPolicyId());

            var moduleIssueAddress = AddressProvider.getRewardAddress(moduleIssueContract, network.getCardanoNetwork());
            log.info("moduleIssueAddress: {}", moduleIssueAddress.getAddress());

            var moduleTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);
            var moduleTransferAddress = AddressProvider.getRewardAddress(moduleTransferContract, network.getCardanoNetwork());
            log.info("moduleTransferAddress: {}", moduleTransferAddress.getAddress());

            var requiredStakeAddresses = Stream.of(moduleIssueAddress, moduleTransferAddress)
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

                // Record the attempt BEFORE handing the transaction over. This row is the evidence
            // that lets a later /script-registration/known confirm this credential if the submit
            // comes back saying it already exists; without it that endpoint would be an
            // unauthenticated write over arbitrary addresses.
            stakeAddressesToRegister.forEach(addr -> {
                scriptRegistrationService.noteRegistrationAttempted(addr, null);
                registerAddressTx.registerStakeAddress(addr);
            });

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

            if (utxoRepository.findUnspentByOwnerAddr(registerTokenRequest.getFeePayerAddress(), Pageable.unpaged()).isEmpty()) {
                return TransactionContext.typedError("issuer wallet is empty");
            }

            // Handler knows its own contract names internally
            var moduleIssuanceContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.issue.withdraw");
            var moduleTransferContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.transfer.withdraw");
            if (moduleIssuanceContractOpt.isEmpty() || moduleTransferContractOpt.isEmpty()) {
                log.warn("module issuance or transfer contract are empty");
                return TransactionContext.typedError("module issuance or transfer contract are empty");
            }
            var moduleIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            var moduleTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);

            // dummy has no third_party validator of its own. The registry slot is pinned to the
            // protocol's always-fail script so no third-party action can ever succeed.
            var thirdPartyHash = moduleService.getModuleValidator(MODULE_ID, "third_party.third_party.withdraw")
                    .map(ModuleValidator::scriptHash)
                    .filter(Objects::nonNull)
                    .orElse(protocolBootstrapParams.issuance().alwaysFailScriptHash());

            var service = cclDeployments.service(protocolBootstrapParams);
            var deployment = service.advanced().deployment();
            CclDeployments.registerWitnesses(service, moduleIssueContract, moduleTransferContract);

            final var progTokenPolicyId = policyIdOf(service, moduleIssueContract, protocolBootstrapParams);
            log.info("issuanceContract: {}", progTokenPolicyId);

            if (isRegistered(protocolBootstrapParams, progTokenPolicyId)) {
                // Not a transient condition, and not something a different asset name would fix.
                //
                // dummy's issuer and transfer validators take NO per-token parameters, so its
                // issuance policy is a function of the protocol deployment alone — every dummy
                // token on this network resolves to the very same policy id. CIP-113 keys registry
                // nodes by policy id, so the directory has room for exactly one dummy entry, and it
                // is already taken.
                //
                // Say so, because "already registered" invites the reasonable-but-wrong conclusion
                // that picking another token name will help.
                return TransactionContext.typedError(String.format(
                        "Token policy %s is already registered in the CIP-113 directory. The dummy "
                        + "module's validators take no per-token parameters, so every dummy "
                        + "token on this protocol deployment derives the SAME policy id — one dummy "
                        + "token per deployment is all the directory can hold, and choosing a "
                        + "different asset name will produce this same policy again. Mint more of "
                        + "the existing token instead, or use a module whose issuance policy is "
                        + "parameterised per token (freeze-and-seize, rwa-token).",
                        progTokenPolicyId));
            }

            // CIP-68 mints a PAIR under this one policy: the labelled user token, plus a (100)
            // reference token of quantity 1 whose inline datum carries the metadata. The reference
            // token is itself a programmable token, so it goes to the ISSUER's smart wallet, so the
            // issuer can spend it later to update the metadata.
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
            // `issue` is `redeemer == 100` and a later mint will happily emit more of the same
            // name — so a (222) here would be a non-fungibility claim the contract cannot keep.
            var userAssetNameHex = cip68Metadata == null
                    ? registerTokenRequest.getAssetName()
                    : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), registerTokenRequest.getAssetName());
            var payee = registerTokenRequest.getRecipientAddress() == null || registerTokenRequest.getRecipientAddress().isBlank()
                    ? registerTokenRequest.getFeePayerAddress()
                    : registerTokenRequest.getRecipientAddress();

            var registration = ProgrammableTokenRegistration.builder()
                    .mintingLogicScript(scriptCredential(moduleIssueContract.getScriptHash()))
                    .transferLogicScript(scriptCredential(moduleTransferContract.getScriptHash()))
                    .thirdPartyTransferLogicScript(scriptCredential(HexUtil.decodeHexString(thirdPartyHash)))
                    // empty_vkey: unfracking is forbidden for dummy tokens.
                    .unfrackingLogicScript(new ProgrammableTokenCredential(CredentialType.Key, ""))
                    .build();

            var tx = new ProgrammableTokenTx()
                    .from(registerTokenRequest.getFeePayerAddress())
                    .register(REGISTRATION, registration, ISSUE)
                    .mint(ProgrammableTokenPolicyRef.named(REGISTRATION),
                            CclWallets.smartWallet(deployment, payee).toBech32(),
                            List.of(asset(userAssetNameHex, mintQuantity)), ISSUE, HOLDING);
            if (cip68Metadata != null) {
                tx.mint(ProgrammableTokenPolicyRef.named(REGISTRATION),
                        CclWallets.smartWallet(deployment, registerTokenRequest.getFeePayerAddress()).toBech32(),
                        List.of(asset(Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, registerTokenRequest.getAssetName()),
                                ONE)),
                        ISSUE, Cip68.buildDatum(cip68Metadata));
            }

            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(registerTokenRequest.getFeePayerAddress())
                    .mergeOutputs(false)
                    .build();

            // The metadata is user-supplied text and the reference output is real bytes. Measure
            // the finished CBOR before handing it back, so an oversized metadata fails HERE rather
            // than at submission after the user has signed.
            if (cip68Metadata != null) {
                Cip68.preflightTxSize("dummy registration", transaction.serialize(),
                        protocolParamsSupplier.getProtocolParams());
            }

            // Save to unified programmable token registry (policyId -> moduleId binding)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .moduleId(MODULE_ID)
                    // The LABELLED name, so a later mint/transfer resolves the asset that is
                    // actually on chain rather than the unlabelled base the wizard collected.
                    .assetName(userAssetNameHex)
                    .build());

            return TransactionContext.ok(transaction.serializeToHex(), new RegistrationResult(progTokenPolicyId));

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
            // policy id. Bind the mint to the name the registry recorded.
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
            // must never mint it. A second reference token breaks the CIP-68 pair irrecoverably.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(mintTokenRequest.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to mint a (100) reference token from the ordinary mint endpoint. "
                        + "CIP-68 allows exactly one per token and it is created at registration.");
            }

            if (utxoRepository.findUnspentByOwnerAddr(mintTokenRequest.feePayerAddress(), Pageable.unpaged()).isEmpty()) {
                return TransactionContext.error("fee payer wallet is empty");
            }

            var moduleIssuanceContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.issue.withdraw");
            if (moduleIssuanceContractOpt.isEmpty()) {
                return TransactionContext.error("module issuance contract not found for " + MODULE_ID);
            }
            var moduleIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);

            var service = cclDeployments.service(protocolBootstrapParams);
            var deployment = service.advanced().deployment();
            CclDeployments.registerWitnesses(service, moduleIssueContract);
            var progTokenPolicyId = policyIdOf(service, moduleIssueContract, protocolBootstrapParams);

            // The registry row above was found by the policy id the REQUEST claimed. This policy
            // id is the one the transaction will actually mint under, and it is derived from
            // `protocolBootstrapParams` — which the caller chooses, via `protocolTxHash`. Without
            // this check, naming policy A / asset A while selecting bootstrap B would mint asset A
            // under policy B: a token registered nowhere, wearing a registered token's name.
            if (!progTokenPolicyId.equalsIgnoreCase(mintTokenRequest.tokenPolicyId())) {
                return TransactionContext.typedError(
                        "the selected protocol deployment derives issuance policy " + progTokenPolicyId
                        + ", but this request names policy " + mintTokenRequest.tokenPolicyId()
                        + ". Minting would put the registered asset name under a DIFFERENT policy "
                        + "than the one it is registered against. Use the protocol deployment this "
                        + "token was registered on, or omit protocolTxHash to use the default.");
            }

            var recipient = mintTokenRequest.recipientAddress() == null
                    ? mintTokenRequest.feePayerAddress()
                    : mintTokenRequest.recipientAddress();
            var tx = new ProgrammableTokenTx()
                    .from(mintTokenRequest.feePayerAddress())
                    .mint(progTokenPolicyId, CclWallets.smartWallet(deployment, recipient).toBech32(),
                            List.of(asset(mintTokenRequest.assetName(), new BigInteger(mintTokenRequest.quantity()))),
                            ISSUE, HOLDING);
            // CIP-170 attestation, when the caller supplied an approved one.
            MintAttestationMetadata.attach(tx, mintTokenRequest.attestation());

            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(mintTokenRequest.feePayerAddress())
                    .mergeOutputs(false)
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("error", e);
            return TransactionContext.error(e.getMessage());
        }
    }

    /**
     * Burns from the fee payer's own smart wallet.
     *
     * <p>An owner burn spends the holder's wallet, so the transfer logic runs alongside the issue
     * logic. Burning someone else's holding is a third-party burn, which CCL does not build yet
     * (CCL gap #12), so a designated UTxO outside the fee payer's wallet is refused. CCL picks the
     * wallet's UTxOs itself; the designated one only has to belong to the burner (CCL gap #9).
     */
    @Override
    public TransactionContext<Void> buildBurnTransaction(BurnTokenRequest burnTokenRequest,
                                                         ProtocolBootstrapParams protocolBootstrapParams) {
        try {
            // The same name binding as the mint: only the registered (labelled) name, and never
            // the (100) reference token — burning it would break the CIP-68 pair for good.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(burnTokenRequest.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to burn the CIP-68 (100) reference token: it carries the token's "
                        + "metadata, and CIP-68 allows exactly one per token.");
            }
            var registryRowOpt = programmableTokenRegistryRepository.findByPolicyId(burnTokenRequest.tokenPolicyId());
            if (registryRowOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "no registry row for policy " + burnTokenRequest.tokenPolicyId() + ".");
            }
            var registeredAssetName = registryRowOpt.get().getAssetName();
            if (registeredAssetName == null || !registeredAssetName.equalsIgnoreCase(burnTokenRequest.assetName())) {
                return TransactionContext.typedError(
                        "assetName '" + burnTokenRequest.assetName() + "' does not match the registered "
                        + "asset name '" + registeredAssetName + "' for policy " + burnTokenRequest.tokenPolicyId() + ".");
            }

            var moduleIssuanceContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.issue.withdraw");
            var moduleTransferContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.transfer.withdraw");
            if (moduleIssuanceContractOpt.isEmpty() || moduleTransferContractOpt.isEmpty()) {
                return TransactionContext.error("module issuance or transfer contract are empty");
            }
            var moduleIssueContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleIssuanceContractOpt.get().scriptBytes(), PlutusVersion.v3);
            var moduleTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);

            var service = cclDeployments.service(protocolBootstrapParams);
            CclDeployments.registerWitnesses(service, moduleIssueContract, moduleTransferContract);
            var progTokenPolicyId = policyIdOf(service, moduleIssueContract, protocolBootstrapParams);
            if (!progTokenPolicyId.equalsIgnoreCase(burnTokenRequest.tokenPolicyId())) {
                return TransactionContext.typedError("policy " + burnTokenRequest.tokenPolicyId()
                        + " is not this deployment's dummy policy " + progTokenPolicyId);
            }

            var holder = burnTokenRequest.feePayerAddress();
            var refusal = CclWallets.refuseForeignBurn(service.advanced().deployment(), cclQuickTx.utxoSupplier(),
                    holder, burnTokenRequest.utxoTxHash(), burnTokenRequest.utxoOutputIndex());
            if (refusal != null) {
                return TransactionContext.typedError(refusal);
            }

            var tx = new ProgrammableTokenTx()
                    .from(holder)
                    .burn(progTokenPolicyId,
                            List.of(asset(burnTokenRequest.assetName(), new BigInteger(burnTokenRequest.quantity()))),
                            BurnAuthorization.of(TRANSFER, ISSUE));
            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(holder)
                    // The stake key signs for the wallet besides the paying payment key: price it.
                    .additionalSignersCount(1)
                    .mergeOutputs(false)
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("error", e);
            return TransactionContext.error(e.getMessage());
        }
    }

    @Override
    public TransactionContext<Void> buildTransferTransaction(TransferTokenRequest transferTokenRequest,
                                                             ProtocolBootstrapParams protocolBootstrapParams) {

        try {

            var progToken = AssetType.fromUnit(transferTokenRequest.unit());
            log.info("policy id: {}, asset name: {}", progToken.policyId(), progToken.unsafeHumanAssetName());

            // A (100) reference token cannot go through this path. Every output is written with
            // the ordinary programmable-token datum, which would DESTROY the metadata the
            // reference token exists to carry, and break the CIP-68 pair. Preserving the datum
            // is a metadata-custody feature, not a transfer detail, so it is refused here rather
            // than half-implemented.
            if (Integer.valueOf(Cip68.LABEL_REFERENCE).equals(Cip68.readLabel(progToken.assetName()))) {
                return TransactionContext.typedError(
                        "refusing to transfer the CIP-68 (100) reference token: this path rebuilds "
                        + "outputs with the plain programmable-token datum, which would erase the "
                        + "metadata and break the pair. Reference-token custody is not supported.");
            }

            var moduleTransferContractOpt = moduleService.getModuleValidator(MODULE_ID, "transfer.transfer.withdraw");
            if (moduleTransferContractOpt.isEmpty()) {
                return TransactionContext.error("could not resolve transfer contract");
            }
            var moduleTransferContract = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(moduleTransferContractOpt.get().scriptBytes(), PlutusVersion.v3);

            var service = cclDeployments.service(protocolBootstrapParams);
            var deployment = service.advanced().deployment();
            CclDeployments.registerWitnesses(service, moduleTransferContract);

            var tx = new ProgrammableTokenTx()
                    .from(transferTokenRequest.senderAddress())
                    .transfer(CclWallets.smartWallet(deployment, transferTokenRequest.recipientAddress()).toBech32(),
                            Amount.asset(transferTokenRequest.unit(), new BigInteger(transferTokenRequest.quantity())),
                            TRANSFER, HOLDING);
            var transaction = cclQuickTx.builder(service)
                    .compose(tx)
                    .feePayer(transferTokenRequest.senderAddress())
                    // The stake key signs for the wallet besides the paying payment key: price it.
                    .additionalSignersCount(1)
                    .mergeOutputs(false)
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("error", e);
            return TransactionContext.error(e.getMessage());
        }

    }

    /** The policy the platform derives, cross-checked against CCL's own derivation. */
    private String policyIdOf(Cip113ProgrammableTokenService service, PlutusScript moduleIssueContract,
                              ProtocolBootstrapParams protocolBootstrapParams) throws Exception {
        var platform = protocolScriptBuilderService
                .getParameterizedIssuanceMintScript(protocolBootstrapParams, moduleIssueContract).getPolicyId();
        var ccl = service.advanced().derivePolicyId(Credential.fromScript(moduleIssueContract.getScriptHash()));
        if (!ccl.isSuccessful() || !platform.equalsIgnoreCase(ccl.getValue())) {
            throw new IllegalStateException("platform derives dummy policy " + platform
                    + " but CCL derives " + (ccl.isSuccessful() ? ccl.getValue() : ccl.getResponse()));
        }
        return platform;
    }

    /** Whether the CIP-113 directory already holds a node keyed by {@code policyId}. */
    private boolean isRegistered(ProtocolBootstrapParams protocolBootstrapParams, String policyId) throws Exception {
        var registrySpend = protocolScriptBuilderService.getParameterizedRegistryScript(protocolBootstrapParams);
        return utxoRepository.findUnspentByOwnerPaymentCredential(registrySpend.getPolicyId(), Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .anyMatch(entry -> registryNodeParser.parse(entry.getInlineDatum())
                        .map(node -> node.key().equals(policyId))
                        .orElse(false));
    }

    private static ProgrammableTokenCredential scriptCredential(byte[] hash) {
        return ProgrammableTokenCredential.from(Credential.fromScript(hash));
    }

    private static Asset asset(String nameHex, BigInteger quantity) {
        return Asset.builder().name("0x" + nameHex).value(quantity).build();
    }

}
