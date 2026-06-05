package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.easy1staking.cardano.comparator.TransactionInputComparator;
import org.cardanofoundation.conversions.CardanoConverters;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.SecurityTokenPowerUserCapability;
import org.cardanofoundation.cip113.entity.SecurityTokenPowerUserEntity;
import org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.BurnTokenRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.SecurityTokenRegisterRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult;
import org.cardanofoundation.cip113.model.TransferTokenRequest;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenDenylistEntryRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenPowerUserRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.HybridUtxoSupplier;
import org.cardanofoundation.cip113.service.LinkedListService;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.SecurityTokenAllowlistService;
import org.cardanofoundation.cip113.service.SecurityTokenScriptBuilderService;
import org.cardanofoundation.cip113.service.UtxoProvider;
import com.easy1staking.cardano.model.AssetType;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.cardanofoundation.cip113.model.LinkedListNode;
import org.cardanofoundation.cip113.service.substandard.capabilities.BasicOperations;
import org.cardanofoundation.cip113.service.substandard.context.SecurityTokenContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Handler for the "security-token" substandard.
 *
 *  <p>The on-chain validators are ported verbatim from
 *  {@code easy1staking-com/fn-bafin-cardano-sc} and live under
 *  {@code src/substandards/security-token/}. This handler is deliberately
 *  isolated from {@code KycExtendedSubstandardHandler} and {@code MpfTreeService}
 *  so the older KYC substandards can be removed without breaking it.
 *
 *  <p>Status: registration / mint / transfer / global-state action methods are
 *  scaffolded and return a typed error until the per-method implementation lands.
 *  Wiring through the factory + DTO + JSON discriminator is in place so the
 *  dispatcher and frontend can observe the substandard end-to-end. */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class SecurityTokenSubstandardHandler
        implements SubstandardHandler,
                   BasicOperations<SecurityTokenRegisterRequest>,
                   org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable {

    private static final String SUBSTANDARD_ID = "security-token";

    /** Hex of an empty (32-byte) MPF root: 32 zero bytes. */
    static final String EMPTY_ROOT_HEX = "0000000000000000000000000000000000000000000000000000000000000000";

    private final SecurityTokenScriptBuilderService scriptBuilder;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;
    private final SecurityTokenAllowlistService allowlistService;
    private final SecurityTokenRegistrationRepository registrationRepository;
    private final SecurityTokenDenylistEntryRepository denylistRepository;
    private final SecurityTokenPowerUserRepository powerUserRepository;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final UtxoProvider utxoProvider;
    private final AccountService accountService;
    private final QuickTxBuilder quickTxBuilder;
    private final AppConfig.Network network;
    private final RegistryNodeParser registryNodeParser;
    private final LinkedListService linkedListService;
    private final HybridUtxoSupplier hybridUtxoSupplier;
    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    private final CardanoConverters cardanoConverters;

    @Setter
    private SecurityTokenContext context;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    // ── BasicOperations ─────────────────────────────────────────────────────

    @Override
    public TransactionContext<List<String>> buildPreRegistrationTransaction(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        // security-token bundles the substandard stake-credential registration into
        // the registration tx (see buildRegistrationTransaction). The wizard
        // doesn't call a separate pre-registration step for this substandard.
        return TransactionContext.ok(null, List.of());
    }

    /** Registration tx. Mints the security-token policy under prog-logic-base, registers
     *  in the CIP-113 directory with the parameterised {@code transfer_logic_script.withdraw}
     *  hash, persists the {@link SecurityTokenRegistrationEntity} row.
     *
     *  <p>Pre-condition: the genesis tx ({@link #buildGlobalStateInitTransaction}) must
     *  have run first so the three NFT policy ids on the request are valid.
     *
     *  <p>Reference: mirrors {@code KycExtendedSubstandardHandler.buildRegistrationTransaction}
     *  but parameterises {@code transfer_logic_script} as
     *  {@code (security_asset_name_hex, global_state_policy_id, registry_policy_id)} per the
     *  ported plutus.json. */
    @Override
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        return buildRegistrationTransaction(request, protocolParams, /*chainedGsUtxoOverride=*/ null);
    }

    /** Chain-aware overload of {@link #buildRegistrationTransaction}. When
     *  {@code chainedGsUtxoOverride} is non-null, the tx ALSO spends the GS
     *  UTxO with the BaFin {@code MintSecurity} action — decrementing the
     *  on-chain {@code mintable_amount} by the quantity being minted. This
     *  enforces the supply cap for the initial mint that happens at
     *  registration time.
     *
     *  <p>Pre-condition: {@code chainedGsUtxoOverride} carries the inline GS
     *  datum (so the script sees it during evaluation). Caller must populate
     *  {@link SecurityTokenRegisterRequest#getInitialMintableAmount()} with
     *  the SAME value the genesis tx used so we can compute the decremented
     *  amount correctly without re-reading the chain. */
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams,
            Utxo chainedGsUtxoOverride) {
        try {
            // 0. Context + script construction. The genesis tx already wrote a
            // SecurityTokenRegistrationEntity row keyed on the prog-token policy
            // id and stored the (gs, pu, dl) policies + securityAssetNameHex +
            // bootstrap input. TokenOperationsService loaded all of that into
            // the SecurityTokenContext for us.
            if (context == null) {
                return TransactionContext.typedError("security-token context not set — genesis init must run first");
            }
            var gsPolicy = context.getGlobalStatePolicyId();
            var puPolicy = context.getPowerUsersPolicyId();
            var securityAssetNameHex = context.getSecurityAssetNameHex();
            var adminPkhHex = context.getIssuerAdminPkh();
            if (gsPolicy == null || puPolicy == null || securityAssetNameHex == null || adminPkhHex == null) {
                return TransactionContext.typedError(
                        "security-token context is incomplete — re-run the genesis init step (gsPolicy/puPolicy/assetName/adminPkh missing)");
            }
            var adminCredential = Credential.fromKey(adminPkhHex);

            var registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            var mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    securityAssetNameHex, gsPolicy, registryPolicyId, puPolicy);
            var transferLogicScript = scriptBuilder.buildTransferLogicScript(
                    securityAssetNameHex, gsPolicy, registryPolicyId);
            var issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(
                    protocolParams, mintingLogicScript);
            var progTokenPolicyId = issuanceContract.getPolicyId();
            var mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork());

            log.info("security-token registration script hashes — issuance={} mintingLogic={} transferLogic={} registryMint={} directoryMint={}",
                    progTokenPolicyId,
                    HexUtil.encodeHexString(mintingLogicScript.getScriptHash()),
                    HexUtil.encodeHexString(transferLogicScript.getScriptHash()),
                    registryPolicyId,
                    protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolParams).getPolicyId());

            // 1. Fee-payer UTxOs (same selection pattern as kyc-extended).
            List<Utxo> feePayerUtxos;
            if (request.getChainingTransactionCborHex() != null) {
                var chainingTxBytes = HexUtil.decodeHexString(request.getChainingTransactionCborHex());
                var chainingTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(chainingTxBytes);
                var chainingTx = Transaction.deserialize(chainingTxBytes);
                Utxo inputUtxo = null;
                var outs = chainingTx.getBody().getOutputs();
                for (int i = 0; i < outs.size(); i++) {
                    var output = outs.get(i);
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
            if (feePayerUtxos.isEmpty()) {
                return TransactionContext.typedError("no ADA-only UTxOs at fee-payer address");
            }

            // 2. CIP-113 protocol bookkeeping (mirrors kyc-extended).
            var bootstrapTxHash = protocolParams.txHash();
            var protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            var issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (protocolParamsUtxoOpt.isEmpty() || issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol or issuance params UTxOs");
            }
            var protocolParamsUtxo = protocolParamsUtxoOpt.get();
            var issuanceUtxo = issuanceUtxoOpt.get();

            var directorySpendScript = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            var directorySpendAddress = AddressProvider.getEntAddress(directorySpendScript, network.getCardanoNetwork());
            var directoryMintScript = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolParams);
            var directoryMintPolicyId = directoryMintScript.getPolicyId();

            // 3. Linked-list slot lookup for the directory insert.
            var registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
            var nodeAlreadyPresent = linkedListService.nodeAlreadyPresent(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum()).map(RegistryNode::key));
            if (nodeAlreadyPresent) {
                return TransactionContext.typedError(
                        "policy " + progTokenPolicyId + " already registered in CIP-113 directory");
            }
            var nodeToReplaceOpt = linkedListService.findNodeToReplace(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                            .map(node -> new LinkedListNode(node.key(), node.next())));
            if (nodeToReplaceOpt.isEmpty()) {
                return TransactionContext.typedError("could not find directory slot to insert into");
            }
            var directoryUtxo = nodeToReplaceOpt.get();
            var existingNodeOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());
            if (existingNodeOpt.isEmpty()) {
                return TransactionContext.typedError("could not parse existing directory node datum");
            }
            var existingNode = existingNodeOpt.get();

            // Locate the directory NFT carried by the slot UTxO so we can preserve it on the spend output.
            var directorySpendAssetOpt = directoryUtxo.getAmount().stream()
                    .filter(a -> a.getQuantity().equals(BigInteger.ONE)
                            && directoryMintPolicyId.equals(AssetType.fromUnit(a.getUnit()).policyId()))
                    .findAny();
            if (directorySpendAssetOpt.isEmpty()) {
                return TransactionContext.typedError("directory slot UTxO has no directory NFT");
            }
            var directorySpendAssetName = AssetType.fromUnit(directorySpendAssetOpt.get().getUnit()).assetName();

            // 4. Redeemers.
            //
            // Issuance redeemer (CIP-113 MintAndCreate variant): tell the issuance
            // contract this is a fresh registration, with the new directory entry
            // at output index 2 (preserved-slot=output 1, new-slot=output 2 per
            // kyc-extended convention).
            var issuanceRedeemer = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(1, BytesPlutusData.of(mintingLogicScript.getScriptHash())),
                    ConstrPlutusData.of(1, BigIntPlutusData.of(2))
            );
            // Directory mint Insert redeemer (carries issuance + substandard hashes).
            var directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    BytesPlutusData.of(mintingLogicScript.getScriptHash())
            );

            // 5. New + updated directory datums (preserved slot links to new key,
            // new node links to whatever the preserved slot used to link to).
            var directorySpendDatum = existingNode.toBuilder()
                    .next(HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                    .build();
            var directoryMintDatum = new RegistryNode(
                    HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    existingNode.next(),
                    HexUtil.encodeHexString(transferLogicScript.getScriptHash()),
                    HexUtil.encodeHexString(mintingLogicScript.getScriptHash()),
                    gsPolicy);

            var directorySpendNft = Asset.builder()
                    .name("0x" + directorySpendAssetName)
                    .value(BigInteger.ONE).build();
            var directoryMintNft = Asset.builder()
                    .name("0x" + issuanceContract.getPolicyId())
                    .value(BigInteger.ONE).build();

            Value directorySpendValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(directoryMintPolicyId)
                            .assets(List.of(directorySpendNft)).build()))
                    .build();
            Value directoryMintValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(directoryMintPolicyId)
                            .assets(List.of(directoryMintNft)).build()))
                    .build();

            // 6. Recipient = the user's wallet (where the first minted security tokens go).
            //    Wrapped under the prog-logic-base stake credential per CIP-113.
            //
            // quantity == 0 is supported: registration becomes "directory insert +
            // stake cred registration only", with the mintingLogicScript.withdraw
            // taking its rubber-stamp branch (mint=0 short-circuit added to the
            // Aiken validator). The first actual mint of security tokens is then
            // a separate MintSecurity tx run from the admin page.
            BigInteger mintQuantity;
            try {
                mintQuantity = new BigInteger(request.getQuantity());
            } catch (NumberFormatException nfe) {
                return TransactionContext.typedError("quantity must be a non-negative integer");
            }
            if (mintQuantity.signum() < 0) {
                return TransactionContext.typedError("quantity must be >= 0");
            }
            boolean willMint = mintQuantity.signum() > 0;
            var programmableToken = Asset.builder()
                    .name("0x" + request.getAssetName())
                    .value(mintQuantity).build();
            Value programmableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(progTokenPolicyId)
                            .assets(List.of(programmableToken)).build()))
                    .build();

            var recipient = (request.getRecipientAddress() == null || request.getRecipientAddress().isBlank())
                    ? request.getFeePayerAddress()
                    : request.getRecipientAddress();
            var recipientAddress = new Address(recipient);
            var targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().orElseThrow(() ->
                            new IllegalArgumentException("recipient must be a base address (need stake credential)")),
                    network.getCardanoNetwork());

            // 7. Compose the tx.
            //
            // Stake-credential registration for mintingLogic + transferLogic
            // is done in the genesis tx (first in the chain) — by the time
            // this registration tx runs, those credentials are mempool-
            // registered, so the withdraw-0 below succeeds without us needing
            // to register them again here.
            //
            // The withdraw redeemer is the BaFin MintingLogicScriptWithdrawRedeemer
            // { gs_input_index, pu_node_ref_input_index, minted_amount }. When
            // willMint=false we pass {0, 0, 0} and the validator's rubber-stamp
            // branch (mint=0 short-circuit) lets the registration succeed without
            // needing GS/PU on chain. When willMint=true we'd need the strict
            // path's inputs — that's the separate MintSecurity tx, not this one.
            var withdrawRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(willMint ? mintQuantity : BigInteger.ZERO));

            // Output order MUST match the issuance redeemer's directory output
            // index (= 2, set above) — kyc-extended pattern is:
            //   [0] prog-token mint to recipient
            //   [1] preserved directory entry (the spent slot, updated link)
            //   [2] NEW directory entry (the one being inserted)
            // The preBalanceTx hook below moves any leading fee-payer change
            // output to the end, so these indices stay stable. If we ever omit
            // the prog-token mint (willMint=false), the new directory entry
            // would shift to index 1 and the issuance redeemer would need
            // updating — but registration must mint a prog token anyway for
            // the directory mint validator to accept the registration.
            var tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    .withdraw(mintingLogicRewardAddress.getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .mintAsset(directoryMintScript, directoryMintNft, directoryMintRedeemer);

            if (willMint) {
                tx = tx
                        .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                        .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue),
                                ConstrPlutusData.of(0));   // output 0
            }

            tx = tx
                    .payToContract(directorySpendAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue),
                            directorySpendDatum.toPlutusData())                            // output 1 (when willMint)
                    .payToContract(directorySpendAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue),
                            directoryMintDatum.toPlutusData())                             // output 2 (when willMint)
                    .readFrom(TransactionInput.builder()
                                    .transactionId(protocolParamsUtxo.getTxHash())
                                    .index(protocolParamsUtxo.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(issuanceUtxo.getTxHash())
                                    .index(issuanceUtxo.getOutputIndex()).build())
                    .attachSpendingValidator(directorySpendScript)
                    .attachRewardValidator(mintingLogicScript)
                    .withChangeAddress(request.getFeePayerAddress());

            // 7b. CHAIN MODE: also spend GS with MintSecurity to enforce the
            // supply cap on the initial mint. Without this, mintable_amount
            // wouldn't be decremented for the registration-time mint (the
            // rubber-stamp branch of mintingLogic.withdraw bypasses the cap).
            // GS spend's MintSecurity branch enforces the cap independently of
            // the substandard's withdraw, so the rubber-stamp branch stays safe.
            //
            // Output added: [3] new GS UTxO with decremented mintable_amount.
            // The preBalanceTx hook still moves the leading change output to
            // the end, so indices 0..3 stay stable for redeemers.
            int gsOutputIdx = -1;
            if (chainedGsUtxoOverride != null && willMint) {
                var gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                        securityAssetNameHex, progTokenPolicyId, gsPolicy);
                var gsSpendAddress = AddressProvider.getEntAddress(
                        gsSpendScript, network.getCardanoNetwork());

                long oldMintable = request.getInitialMintableAmount() != null
                        ? request.getInitialMintableAmount() : 0L;
                long newMintable = oldMintable - mintQuantity.longValueExact();
                if (newMintable < 0) {
                    return TransactionContext.typedError(
                            "initial mint quantity (" + mintQuantity + ") exceeds GS mintable_amount ("
                            + oldMintable + ") — raise initialMintableAmount or lower quantity");
                }
                // PRESERVE the existing GS datum from genesis — only decrement
                // mintable_amount. Calling buildInitialGlobalStateDatum here
                // would WIPE trusted_entity_vkeys + member_root_hash that
                // genesis set (or that a later update wrote), forcing users to
                // re-add their trusted issuers / republish their MPF root.
                // Pattern matches buildMintTransaction's GS update.
                PlutusData currentGsForRegistration = PlutusData.deserialize(
                        HexUtil.decodeHexString(chainedGsUtxoOverride.getInlineDatum()));
                if (!(currentGsForRegistration instanceof ConstrPlutusData currentConstrReg)) {
                    return TransactionContext.typedError(
                            "GS datum is not a Constr (chainedGsUtxoOverride)");
                }
                List<PlutusData> currentRegFields = currentConstrReg.getData().getPlutusDataList();
                if (currentRegFields.size() < 9) {
                    return TransactionContext.typedError(
                            "GS datum is malformed (< 9 fields)");
                }
                PlutusData newGsDatum = ConstrPlutusData.of(0,
                        currentRegFields.get(0),                                              // transfers_paused
                        BigIntPlutusData.of(BigInteger.valueOf(newMintable)),                 // mintable_amount (decremented)
                        currentRegFields.get(2),                                              // admin_credential_hash
                        currentRegFields.get(3),                                              // power_user_linked_list_policy_id
                        currentRegFields.get(4),                                              // denylist_linked_list_policy_id
                        currentRegFields.get(5),                                              // security_info
                        currentRegFields.get(6),                                              // trusted_entity_vkeys (preserved!)
                        currentRegFields.get(7),                                              // member_root_hash (preserved!)
                        currentRegFields.get(8));                                             // requires_receiver_kyc

                // Compute the position of the issuance Mint redeemer in the
                // canonical redeemer ordering (Spend → Mint → Cert → Reward,
                // lex-sorted within each tag). With chain mode we have:
                //   Spend × 2 (directoryUtxo + gsUtxo), Mint × 2 (directory +
                //   issuance), Reward × 1 (mintingLogic). The two Mints are
                //   lex-sorted by policy id; the issuance position within Mints
                //   determines its global redeemer index.
                int issuancePri = 2 + (issuanceContract.getPolicyId()
                        .compareTo(directoryMintPolicyId) < 0 ? 0 : 1);

                // GlobalStateSpendRedeemer { config_ref_input_index,
                //   global_state_output_index, action = MintSecurity{ipri} }.
                // config_ref_input_index is unused by the MintSecurity branch.
                gsOutputIdx = 3;  // [0]=token, [1]=preservedDir, [2]=newDir, [3]=newGs
                var gsSpendRedeemer = ConstrPlutusData.of(0,
                        BigIntPlutusData.of(BigInteger.ZERO),
                        BigIntPlutusData.of(BigInteger.valueOf(gsOutputIdx)),
                        ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(issuancePri))));

                // GS output value must equal the input value verbatim (the GS
                // spend validator's `value_preserved` invariant).
                var gsLovelace = chainedGsUtxoOverride.getAmount().stream()
                        .filter(a -> "lovelace".equals(a.getUnit()))
                        .map(com.bloxbean.cardano.client.api.model.Amount::getQuantity)
                        .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
                var gsNftAmount = chainedGsUtxoOverride.getAmount().stream()
                        .filter(a -> !"lovelace".equals(a.getUnit()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
                var gsValue = Value.builder()
                        .coin(gsLovelace)
                        .multiAssets(List.of(MultiAsset.builder()
                                .policyId(gsPolicy)
                                .assets(List.of(Asset.builder()
                                        .name("0x" + com.easy1staking.cardano.model.AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                        .value(BigInteger.ONE).build()))
                                .build()))
                        .build();

                tx = tx
                        .collectFrom(chainedGsUtxoOverride, gsSpendRedeemer)
                        .attachSpendingValidator(gsSpendScript)
                        .payToContract(gsSpendAddress.getAddress(),
                                ValueUtil.toAmountList(gsValue), newGsDatum);   // output 3
            }

            var firstUtxo = feePayerUtxos.getFirst();
            var transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminCredential.getBytes())
                    .feePayer(request.getFeePayerAddress())
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtxo.getTxHash())
                            .index(firstUtxo.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Move the fee-payer change output (added by Bloxbean as
                        // the FIRST output) to the END so output indices 0..N
                        // remain stable for our redeemers.
                        var outputs = txn.getBody().getOutputs();
                        if (!outputs.isEmpty()
                                && outputs.getFirst().getAddress().equals(request.getFeePayerAddress())) {
                            var first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            // 8. Persist the ProgrammableTokenRegistryEntity so the platform's
            // generic dispatcher knows this prog-token policy belongs to the
            // security-token substandard. (The SecurityTokenRegistrationEntity
            // was already written at genesis.)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .substandardId(SUBSTANDARD_ID)
                    .assetName(request.getAssetName())
                    .build());
            hybridUtxoSupplier.clear();

            return TransactionContext.ok(transaction.serializeToHex(),
                    new RegistrationResult(progTokenPolicyId));
        } catch (Exception e) {
            log.error("security-token registration failed", e);
            return TransactionContext.typedError("registration failed: " + e.getMessage());
        }
    }

    /** Mint additional security tokens on a registered policy.
     *
     *  <h3>Tx shape (the BaFin {@code MintSecurity} flow)</h3>
     *  <pre>
     *    Inputs:
     *      [0]  GS UTxO       (spent, with GlobalStateSpendRedeemer.MintSecurity)
     *      [1]  funding UTxO  (admin's ADA, for fees + collateral)
     *
     *    Reference inputs:
     *      directory entry for our prog-token policy
     *           (mintingLogic.withdraw uses this to derive issuance_policy_id;
     *            issuance contract uses it to find the registered substandard)
     *      power-user node for the admin
     *           (mintingLogic.withdraw checks can_mint=true)
     *      protocol params UTxO  (CIP-113 reference)
     *      issuance params UTxO  (CIP-113 reference)
     *
     *    Mints:
     *      `quantity` security tokens under issuance contract
     *
     *    Outputs:
     *      [0]  minted prog tokens at recipient (under prog-logic-base stake cred)
     *      [1]  new GS UTxO at gsSpendAddress (mintable_amount decremented)
     *      [N]  change to admin (moved to end by preBalanceTx)
     *
     *    Withdrawals:
     *      withdraw-0 from mintingLogic stake credential
     *          (strict path: validates redeemer.minted_amount == self.mint,
     *           PU signature, can_mint, GS NFT presence)
     *  </pre>
     *
     *  <h3>Supply cap</h3>
     *  GS spend's {@code MintSecurity} branch checks
     *  {@code remaining_amount = mintable_amount - minted_amount >= 0} and
     *  enforces that the new GS datum carries the decremented value.
     */
    @Override
    public TransactionContext<Void> buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // ── 1. Resolve registration row ────────────────────────────────
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(request.tokenPolicyId());
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + request.tokenPolicyId());
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            // ── 2. Validate quantity ───────────────────────────────────────
            BigInteger mintQuantity;
            try {
                mintQuantity = new BigInteger(request.quantity());
            } catch (NumberFormatException nfe) {
                return TransactionContext.typedError("quantity must be a positive integer");
            }
            if (mintQuantity.signum() <= 0) {
                return TransactionContext.typedError(
                        "quantity must be > 0 (use buildBurnTransaction for negative mints)");
            }

            // ── 3. Build all the parameterised scripts we'll need ──────────
            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId, reg.getPowerUsersPolicyId());
            PlutusScript issuanceContract = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolParams, mintingLogicScript);
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    reg.getSecurityAssetNameHex(),
                    issuanceContract.getPolicyId(), reg.getGlobalStatePolicyId());
            Address gsSpendAddress = AddressProvider.getEntAddress(
                    gsSpendScript, network.getCardanoNetwork());
            Address mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork());

            log.info("security-token mint script hashes — issuance={} mintingLogic={} gsSpend={} registryMint={}",
                    HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    HexUtil.encodeHexString(mintingLogicScript.getScriptHash()),
                    HexUtil.encodeHexString(gsSpendScript.getScriptHash()),
                    registryPolicyId);

            // ── 4. Resolve all UTxOs the tx needs ──────────────────────────
            // GS UTxO (input — will be spent + recreated with decremented amount).
            // Look up by EXACT (policy, asset_name): once the token is in the
            // CIP-113 directory, multiple assets exist under this policy id and
            // policy-only lookup can return the wrong one, which manifests as
            // RequiredRedeemersMismatch at eval time.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError(
                        "GS NFT not found on chain — has the registration tx confirmed?");
            }
            if (gsUtxo.getInlineDatum() == null || gsUtxo.getInlineDatum().isBlank()) {
                return TransactionContext.typedError("GS UTxO is missing its inline datum");
            }

            // Admin power-user node (ref input — mintingLogic.withdraw checks
            // can_mint on this PU's data and the admin's signature on the tx).
            // The node NFT has asset name = "Node" ++ adminPkh — we look it up
            // by exact (policy, assetName) because the PU policy also mints a
            // root NFT with empty asset name, which the broader findUtxosByPolicy
            // would surface instead (and miss our node).
            byte[] adminKeyHash = HexUtil.decodeHexString(reg.getIssuerAdminPkh());
            byte[] adminNodeAssetName = concat(LL_NODE_KEY_PREFIX, adminKeyHash);
            String adminNodeAssetNameHex = HexUtil.encodeHexString(adminNodeAssetName);
            Utxo puNode = utxoProvider.findUtxoByAsset(
                    reg.getPowerUsersPolicyId(), adminNodeAssetNameHex).orElse(null);
            if (puNode == null) {
                return TransactionContext.typedError(
                        "admin power-user node not found on chain — AddPowerUser tx may not have confirmed yet "
                        + "(asset: " + reg.getPowerUsersPolicyId() + "/" + adminNodeAssetNameHex + ")");
            }

            // Directory entry for our prog-token policy (ref input — issuance
            // contract resolves the registered substandard here, and BaFin's
            // mintingLogic.withdraw runs derive_issuance_policy_id_from_registry_node
            // against this entry)
            PlutusScript directorySpendContract = protocolScriptBuilderService
                    .getParameterizedDirectorySpendScript(protocolParams);
            Address directorySpendAddress = AddressProvider.getEntAddress(
                    directorySpendContract, network.getCardanoNetwork());
            List<Utxo> registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
            Utxo directoryEntry = registryEntries.stream()
                    .filter(u -> registryNodeParser.parse(u.getInlineDatum())
                            .map(node -> request.tokenPolicyId().equals(node.key()))
                            .orElse(false))
                    .findAny().orElse(null);
            if (directoryEntry == null) {
                return TransactionContext.typedError(
                        "directory entry for policy " + request.tokenPolicyId() + " not found");
            }

            // CIP-113 protocol params + issuance params ref inputs
            String bootstrapTxHash = protocolParams.txHash();
            Optional<Utxo> protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            Optional<Utxo> issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (protocolParamsUtxoOpt.isEmpty() || issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol or issuance params UTxOs");
            }
            Utxo protocolParamsUtxo = protocolParamsUtxoOpt.get();
            Utxo issuanceUtxo = issuanceUtxoOpt.get();

            // Funding UTxO (fees + collateral) — must be ADA-only and confirmed
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(
                    request.feePayerAddress(), 5_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError("no funding UTxO at fee-payer address");
            }
            Utxo funding = fundingUtxos.getFirst();

            // ── 5. Parse current GS datum + compute new mintable_amount ────
            PlutusData currentGsDatum = PlutusData.deserialize(
                    HexUtil.decodeHexString(gsUtxo.getInlineDatum()));
            if (!(currentGsDatum instanceof ConstrPlutusData currentConstr)) {
                return TransactionContext.typedError("GS datum is not a Constr");
            }
            List<PlutusData> gsFields = currentConstr.getData().getPlutusDataList();
            if (gsFields.size() < 9) {
                return TransactionContext.typedError(
                        "GS datum has " + gsFields.size() + " fields, expected 9");
            }
            long currentMintable;
            if (gsFields.get(1) instanceof BigIntPlutusData bi) {
                currentMintable = bi.getValue().longValueExact();
            } else {
                return TransactionContext.typedError(
                        "GS datum field 1 (mintable_amount) is not an Int");
            }
            long newMintable = currentMintable - mintQuantity.longValueExact();
            if (newMintable < 0) {
                return TransactionContext.typedError(
                        "mint quantity (" + mintQuantity + ") exceeds remaining mintable_amount ("
                        + currentMintable + ")");
            }

            // Rebuild the GS datum with only mintable_amount changed, all other
            // fields (transfers_paused, admin_credential_hash, the two LL
            // policy ids, security_info, trusted_entity_vkeys, member_root_hash,
            // requires_receiver_kyc) preserved verbatim.
            PlutusData newGsDatum = ConstrPlutusData.of(0,
                    gsFields.get(0),                                              // transfers_paused
                    BigIntPlutusData.of(BigInteger.valueOf(newMintable)),         // mintable_amount (decremented)
                    gsFields.get(2),                                              // admin_credential_hash
                    gsFields.get(3),                                              // power_user_linked_list_policy_id
                    gsFields.get(4),                                              // denylist_linked_list_policy_id
                    gsFields.get(5),                                              // security_info
                    gsFields.get(6),                                              // trusted_entity_vkeys
                    gsFields.get(7),                                              // member_root_hash
                    gsFields.get(8));                                             // requires_receiver_kyc

            // ── 6. Compute redeemer indices ────────────────────────────────
            // Cardano lex-sorts both inputs and reference_inputs by (txHash,
            // outIdx) at evaluation time. Our off-chain redeemer indices MUST
            // match what the on-chain script sees.
            int gsInputIdx = lexIndex(List.of(gsUtxo, funding), gsUtxo);
            List<Utxo> refInputsSortable = List.of(directoryEntry, puNode,
                    protocolParamsUtxo, issuanceUtxo);
            int directoryRefIdx = lexIndex(refInputsSortable, directoryEntry);
            int puNodeRefIdx = lexIndex(refInputsSortable, puNode);

            // Redeemers are sorted by tag (Spend → Mint → Cert → Reward), and
            // within each tag by their index. Our tx has 1 Spend (GS), 1 Mint
            // (issuance), 1 Reward (mintingLogic). So issuance Mint sits at
            // global redeemer index 1 (after the single Spend).
            int issuancePri = 1;

            // ── 7. Build redeemers ─────────────────────────────────────────
            // Issuance contract — "mint against existing directory entry" variant.
            // Mirrors kyc-extended.buildMintTransaction:
            //   Constr 0 [Constr 1 [substandardHash], Constr 0 [directoryRefIdx]]
            // The inner Constr 0 (vs Constr 1) tells the issuance contract to
            // FIND the directory entry as a ref input at the given index (vs
            // CREATE it at an output index — that's the registration flow).
            PlutusData issuanceRedeemer = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(1, BytesPlutusData.of(mintingLogicScript.getScriptHash())),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx))));

            // mintingLogic.withdraw — STRICT path (registration mode not
            // triggered because no directory NFT is being minted in this tx).
            // Redeemer is MintingLogicScriptWithdrawRedeemer { gs_input_index,
            // power_user_node_ref_input_index, minted_amount }.
            PlutusData withdrawRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(gsInputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    BigIntPlutusData.of(mintQuantity));

            // GS spend — MintSecurity action. config_ref_input_index is unused
            // by the MintSecurity branch; gs_output_index = 1 (after the prog
            // token output at 0); issuance_policy_redeemer_index = position of
            // the issuance Mint redeemer in the canonical ordering.
            int gsOutputIdx = 1;
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(BigInteger.valueOf(gsOutputIdx)),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(issuancePri))));

            // ── 8. Prog-token output + GS output values ────────────────────
            Asset programmableToken = Asset.builder()
                    .name("0x" + request.assetName())
                    .value(mintQuantity).build();
            Value programmableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(issuanceContract.getPolicyId())
                            .assets(List.of(programmableToken)).build()))
                    .build();

            // Recipient → wrapped under prog-logic-base stake credential per CIP-113.
            String recipient = (request.recipientAddress() == null || request.recipientAddress().isBlank())
                    ? request.feePayerAddress()
                    : request.recipientAddress();
            Address recipientAddress = new Address(recipient);
            Address targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().orElseThrow(() ->
                            new IllegalArgumentException("recipient must be a base address (need stake credential)")),
                    network.getCardanoNetwork());

            // GS output value — preserved verbatim from input (the GS spend
            // validator's `value_preserved` invariant).
            BigInteger gsLovelace = gsUtxo.getAmount().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .map(Amount::getQuantity)
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
            Amount gsNftAmount = gsUtxo.getAmount().stream()
                    .filter(a -> !"lovelace".equals(a.getUnit()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
            Value gsValue = Value.builder()
                    .coin(gsLovelace)
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(reg.getGlobalStatePolicyId())
                            .assets(List.of(Asset.builder()
                                    .name("0x" + AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                    .value(BigInteger.ONE).build()))
                            .build()))
                    .build();

            // ── 9. Compose the tx ──────────────────────────────────────────
            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .withdraw(mintingLogicRewardAddress.getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue),
                            ConstrPlutusData.of(0))                                                 // output 0
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)                                                              // output 1
                    .readFrom(
                            TransactionInput.builder()
                                    .transactionId(directoryEntry.getTxHash())
                                    .index(directoryEntry.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(puNode.getTxHash())
                                    .index(puNode.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(protocolParamsUtxo.getTxHash())
                                    .index(protocolParamsUtxo.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(issuanceUtxo.getTxHash())
                                    .index(issuanceUtxo.getOutputIndex()).build())
                    .attachSpendingValidator(gsSpendScript)
                    .attachRewardValidator(mintingLogicScript)
                    .withChangeAddress(request.feePayerAddress());

            // ── 10. Build with admin required-signer, pinned collateral, and
            //       preBalanceTx hook to keep redeemer output indices stable ─
            String adminPkhHex = reg.getIssuerAdminPkh();
            String feePayerAddress = request.feePayerAddress();
            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(adminPkhHex))
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Move the fee-payer change to the END so the prog-token
                        // output stays at index 0 and the new GS at index 1
                        // (matching the redeemers we built above).
                        List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outs =
                                txn.getBody().getOutputs();
                        if (!outs.isEmpty() && outs.getFirst().getAddress().equals(feePayerAddress)) {
                            com.bloxbean.cardano.client.transaction.spec.TransactionOutput first =
                                    outs.removeFirst();
                            outs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token mint: policy={} qty={} (mintable_amount {} → {})",
                    request.tokenPolicyId(), mintQuantity, currentMintable, newMintable);
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token mint failed for policy={}", request.tokenPolicyId(), e);
            return TransactionContext.typedError("mint failed: " + e.getMessage());
        }
    }

    /** Transfer tx. Assembles {@code transfer_logic_script.withdraw} redeemer of shape
     *  {@code Constr 0 [global_state_ref_input_index, source_actions[], destination_actions[]]}.
     *  Each source action carries a {@code KycProof} (Attestation OR Membership, see
     *  {@code lib/types/kyc_proof.ak}: 66-byte payload, 64-byte signature, 32-byte vkey)
     *  plus the index of a denylist-covering linked-list ref-input proving the sender
     *  is not denylisted. Destination actions are identical but only required when the
     *  token's {@code requires_receiver_kyc} flag is true. */
    @Override
    public TransactionContext<Void> buildTransferTransaction(
            TransferTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // ── 1. Resolve registration row ────────────────────────────────
            AssetType progToken = AssetType.fromUnit(request.unit());
            String policyId = progToken.policyId();
            String assetNameHex = progToken.assetName();
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            // ── 2. Parse request, derive identities ────────────────────────
            BigInteger amountToTransfer = new BigInteger(request.quantity());
            if (amountToTransfer.signum() <= 0) {
                return TransactionContext.typedError("transfer quantity must be > 0");
            }
            Address senderAddress = new Address(request.senderAddress());
            Address recipientAddress = new Address(request.recipientAddress());
            byte[] senderStakeHash = senderAddress.getDelegationCredentialHash().orElse(null);
            byte[] recipientStakeHash = recipientAddress.getDelegationCredentialHash().orElse(null);
            if (senderStakeHash == null) {
                return TransactionContext.typedError(
                        "sender must be a base address (need delegation credential)");
            }
            if (recipientStakeHash == null) {
                return TransactionContext.typedError(
                        "recipient must be a base address (need delegation credential)");
            }
            boolean isSelfSend = java.util.Arrays.equals(senderStakeHash, recipientStakeHash);

            // v1: support Membership-only sender proof (Attestation requires the
            // KERI service to produce BaFin 66-byte payloads, which is a
            // separate workstream). The fields are the same ones kyc-extended
            // uses for the sender fast-path.
            if (request.senderMpfProofCborHex() == null || request.senderMpfProofCborHex().isBlank()
                    || request.senderMpfValidUntilMs() == null) {
                return TransactionContext.typedError(
                        "security-token sender Membership proof required: senderMpfProofCborHex + senderMpfValidUntilMs");
            }
            // Recipient Membership proof only required when the GS datum's
            // requires_receiver_kyc flag is true (and not a self-send).
            boolean needRecipientProof = reg.isRequiresReceiverKyc() && !isSelfSend;
            if (needRecipientProof) {
                if (request.mpfProofCborHex() == null || request.mpfProofCborHex().isBlank()
                        || request.mpfValidUntilMs() == null) {
                    return TransactionContext.typedError(
                            "recipient Membership proof required: mpfProofCborHex + mpfValidUntilMs (token has requires_receiver_kyc=true)");
                }
            }

            // ── 3. Build scripts ───────────────────────────────────────────
            // Transfer doesn't touch the issuance contract (no mint/burn), so
            // we don't build minting_logic or issuance here — just the transfer
            // logic substandard script plus the protocol-level prog-logic-base/global.
            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript transferLogicScript = scriptBuilder.buildTransferLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId);
            Address transferLogicRewardAddress = AddressProvider.getRewardAddress(
                    transferLogicScript, network.getCardanoNetwork());

            // Conway withdraw-0 requires the transferLogic stake cred to be
            // registered on chain. The dedicated /register-transfer-logic
            // endpoint handles this as a one-shot admin tx (the cert is
            // isolated from this multi-script tx so Eternl can sign it).
            String transferLogicRewardAddrBech32 = transferLogicRewardAddress.getAddress();
            boolean transferLogicCredRegistered = stakeRegistrationRepository
                    .findRegistrationsByStakeAddress(transferLogicRewardAddrBech32)
                    .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            if (!transferLogicCredRegistered) {
                return TransactionContext.typedError(
                        "transferLogic stake credential not yet registered on chain. "
                        + "Call POST /security-token/" + policyId
                        + "/register-transfer-logic first (one-time admin action), "
                        + "then retry the transfer.");
            }

            PlutusScript programmableLogicGlobal = protocolScriptBuilderService
                    .getParameterizedProgrammableLogicGlobalScript(protocolParams);
            Address programmableLogicGlobalRewardAddress = AddressProvider.getRewardAddress(
                    programmableLogicGlobal, network.getCardanoNetwork());

            // Per-stake prog-token addresses (payment = prog-logic-base script,
            // stake = the user's delegation credential).
            Address senderProgTokenAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    senderAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());
            Address recipientProgTokenAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().get(),
                    network.getCardanoNetwork());

            // ── 4. Resolve UTxOs ───────────────────────────────────────────
            // Sender's prog-token UTxOs (script address) — pick enough to cover
            // amountToTransfer.
            String unit = policyId + assetNameHex;
            List<Utxo> senderProgTokenUtxos = utxoProvider.findUtxos(senderProgTokenAddress.getAddress());
            List<Utxo> tokenInputs = new java.util.ArrayList<>();
            BigInteger accumulated = BigInteger.ZERO;
            for (Utxo u : senderProgTokenUtxos) {
                BigInteger amt = u.getAmount().stream()
                        .filter(a -> unit.equals(a.getUnit().replace("0x", "")))
                        .map(Amount::getQuantity)
                        .findFirst().orElse(BigInteger.ZERO);
                if (amt.signum() == 0) continue;
                tokenInputs.add(u);
                accumulated = accumulated.add(amt);
                if (accumulated.compareTo(amountToTransfer) >= 0) break;
            }
            if (accumulated.compareTo(amountToTransfer) < 0) {
                return TransactionContext.typedError(
                        "insufficient sender balance: have " + accumulated + " want " + amountToTransfer);
            }
            BigInteger changeAmount = accumulated.subtract(amountToTransfer);

            // Funding UTxO for fees + collateral (from sender's base wallet)
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(senderAddress.getAddress(), 10_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError(
                        "no funding UTxO at sender address (need 10+ ADA at " + senderAddress.getAddress() + ")");
            }
            Utxo funding = fundingUtxos.getFirst();

            // GS UTxO (ref input)
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("GS NFT not found on chain");
            }

            // Denylist root node (ref input) — for an empty denylist (v1 happy
            // path), the root node covers all possible sender + recipient pkhs.
            // For populated denylists we'd need to find the specific covering
            // node per pkh, but that's deferred until denylist add/remove ships
            // on-chain.
            List<Utxo> denylistUtxos = utxoProvider.findUtxosByPolicy(reg.getDenylistPolicyId());
            if (denylistUtxos.isEmpty()) {
                return TransactionContext.typedError("denylist root node not found on chain");
            }
            Utxo denylistRootUtxo = denylistUtxos.getFirst();

            // Directory entry for this prog-token (ref input). The registry
            // node's asset name doesn't follow the LL "NodeKey || pkh" pattern
            // here — node keys ARE the prog-token policy ids stored in the
            // node's datum. Match the mint/burn handlers by enumerating all
            // UTxOs at the directory-spend address and filtering by parsed
            // RegistryNode.key().
            PlutusScript directorySpendContract = protocolScriptBuilderService
                    .getParameterizedDirectorySpendScript(protocolParams);
            Address directorySpendAddress = AddressProvider.getEntAddress(
                    directorySpendContract, network.getCardanoNetwork());
            List<Utxo> registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
            Utxo directoryEntry = registryEntries.stream()
                    .filter(u -> registryNodeParser.parse(u.getInlineDatum())
                            .map(node -> policyId.equals(node.key()))
                            .orElse(false))
                    .findAny().orElse(null);
            if (directoryEntry == null) {
                return TransactionContext.typedError(
                        "directory entry not found for prog-token policy " + policyId);
            }

            String bootstrapTxHash = protocolParams.txHash();
            Optional<Utxo> protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            Optional<Utxo> issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (protocolParamsUtxoOpt.isEmpty() || issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol or issuance params UTxOs");
            }

            // ── 5. Compute ref-input indices (lex-sorted by txHash:idx) ───
            TransactionInput tiGs = txInputOf(gsUtxo);
            TransactionInput tiDirectory = txInputOf(directoryEntry);
            TransactionInput tiDenylistRoot = txInputOf(denylistRootUtxo);
            TransactionInput tiProtocolParams = txInputOf(protocolParamsUtxoOpt.get());
            TransactionInput tiIssuance = txInputOf(issuanceUtxoOpt.get());
            TransactionInput tiProgBaseRef = TransactionInput.builder()
                    .transactionId(protocolParams.programmableBaseRefInput().txHash())
                    .index(protocolParams.programmableBaseRefInput().outputIndex()).build();
            TransactionInput tiProgGlobalRef = TransactionInput.builder()
                    .transactionId(protocolParams.programmableGlobalRefInput().txHash())
                    .index(protocolParams.programmableGlobalRefInput().outputIndex()).build();
            List<TransactionInput> refInputsSorted = java.util.stream.Stream.of(
                    tiGs, tiDirectory, tiDenylistRoot, tiProtocolParams, tiIssuance,
                    tiProgBaseRef, tiProgGlobalRef
            ).sorted(new TransactionInputComparator()).toList();
            int gsRefIdx = refInputsSorted.indexOf(tiGs);
            int directoryRefIdx = refInputsSorted.indexOf(tiDirectory);
            int denylistRefIdx = refInputsSorted.indexOf(tiDenylistRoot);

            // ── 6. Build redeemers ─────────────────────────────────────────
            // Sender proof: Constr 1 = Membership { pkh, valid_until_ms, mpf_proof }
            PlutusData senderMembershipProof = ConstrPlutusData.of(1,
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(senderStakeHash),
                            BigIntPlutusData.of(BigInteger.valueOf(request.senderMpfValidUntilMs())),
                            decodeMpfProof(request.senderMpfProofCborHex())));

            // One source action per UNIQUE sender stake credential among token
            // inputs. v1: all token inputs are from the same wallet => 1 action.
            PlutusData sourceAction = ConstrPlutusData.of(0,
                    senderMembershipProof,
                    BigIntPlutusData.of(BigInteger.valueOf(denylistRefIdx)));
            ListPlutusData sourceActions = ListPlutusData.of(sourceAction);

            // One destination action per UNIQUE destination stake credential
            // among token outputs (in the validator's iteration order).
            //
            // The validator does:
            //   outputs_user_stake_cred_hash =
            //     list.unique(list.foldr(self.outputs, [], collect-stake-of-token-bearing))
            //   list.indexed_foldr(outputs_user_stake_cred_hash, True, ..., safe_list_at(actions, i))
            //
            // foldr right-folds with push (prepend), so the resulting list is
            // [stake-of-output[0], stake-of-output[1], ...] in TX OUTPUT ORDER
            // (de-duped via list.unique, keeping first occurrence).
            //
            // Our outputs in TX order:
            //   [0] recipient prog-token (always present, unless self-send)
            //   [1] sender change prog-token (only if changeAmount > 0)
            //
            // So the validator iterates destinations as:
            //   self-send:                     []
            //   no change (full transfer):     [recipient]
            //   with change (partial transfer):[recipient, sender]
            //
            // We must emit a matching action per destination — emitting fewer
            // causes safe_list_at to read past the end of actions[] and
            // head_list on the empty tail throws EmptyList.
            ListPlutusData destinationActions = ListPlutusData.of();
            if (!isSelfSend) {
                destinationActions.add(buildDestinationAction(
                        recipientStakeHash, needRecipientProof,
                        request.mpfProofCborHex(), request.mpfValidUntilMs(),
                        denylistRefIdx));
                // Sender appears as a destination too when there's change back.
                if (changeAmount.signum() > 0) {
                    // For the sender-as-destination, reuse the sender's
                    // Membership proof — same stake cred, same enrollment.
                    // requires_receiver_kyc=false sends a placeholder anyway,
                    // but having a real proof here is harmless.
                    destinationActions.add(buildDestinationAction(
                            senderStakeHash, needRecipientProof,
                            request.senderMpfProofCborHex(),
                            request.senderMpfValidUntilMs(),
                            denylistRefIdx));
                }
            }

            PlutusData transferRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdx)),
                    sourceActions,
                    destinationActions);

            // prog-logic-global redeemer (passthrough by registry-entry index)
            PlutusData programmableGlobalRedeemer = ConstrPlutusData.of(0,
                    ListPlutusData.of(ConstrPlutusData.of(0,
                            BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)))));

            // Per-input spend redeemer (prog-logic-base passthrough)
            PlutusData spendPassthrough = ConstrPlutusData.of(0);

            // ── 7. Build outputs ───────────────────────────────────────────
            Asset transferAsset = Asset.builder()
                    .name("0x" + assetNameHex)
                    .value(amountToTransfer).build();
            Value recipientTokenValue = Value.builder()
                    .coin(Amount.ada(2).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(policyId)
                            .assets(List.of(transferAsset)).build()))
                    .build();

            // ── 8. Assemble tx ─────────────────────────────────────────────
            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .payToContract(recipientProgTokenAddress.getAddress(),
                            ValueUtil.toAmountList(recipientTokenValue),
                            ConstrPlutusData.of(0));
            for (Utxo ti : tokenInputs) {
                tx = tx.collectFrom(ti, spendPassthrough);
            }
            if (changeAmount.signum() > 0) {
                Asset changeAsset = Asset.builder()
                        .name("0x" + assetNameHex).value(changeAmount).build();
                Value changeValue = Value.builder()
                        .coin(Amount.ada(2).getQuantity())
                        .multiAssets(List.of(MultiAsset.builder()
                                .policyId(policyId)
                                .assets(List.of(changeAsset)).build()))
                        .build();
                tx = tx.payToContract(senderProgTokenAddress.getAddress(),
                        ValueUtil.toAmountList(changeValue),
                        ConstrPlutusData.of(0));
            }
            tx = tx
                    .withdraw(transferLogicRewardAddress.getAddress(), BigInteger.ZERO, transferRedeemer)
                    .withdraw(programmableLogicGlobalRewardAddress.getAddress(), BigInteger.ZERO, programmableGlobalRedeemer)
                    .readFrom(refInputsSorted.toArray(new TransactionInput[0]))
                    .attachRewardValidator(transferLogicScript)
                    .withChangeAddress(senderAddress.getAddress());

            // TTL bounded by the membership-proof validity windows
            long now = System.currentTimeMillis();
            long ttlMs = Math.min(now + 15 * 60 * 1000L, request.senderMpfValidUntilMs());
            if (needRecipientProof) {
                ttlMs = Math.min(ttlMs, request.mpfValidUntilMs());
            }
            java.time.LocalDateTime ttlTime = java.time.LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(ttlMs), java.time.ZoneOffset.UTC);
            long ttlSlot = cardanoConverters.time().toSlot(ttlTime);

            String senderAddrBech32 = senderAddress.getAddress();
            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(senderStakeHash)
                    .feePayer(senderAddrBech32)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .validTo(ttlSlot)
                    .postBalanceTx((bctx, txn) -> {
                        // Conway's ledger rejected this with FeeTooSmallUTxO
                        // (short by ~4 KB lovelace) — Bloxbean's fee computation
                        // ran against a body that ended up slightly smaller
                        // than what got serialised (likely from
                        // redeemer-ExUnits or script-data-hash drift).
                        // Add a small fee buffer, compensate the change output,
                        // and re-derive the script-collateral fields (which
                        // are required to be >= fee * collateralPercentage).
                        // Overpaying is accepted by the ledger (the surplus is
                        // burned); the buffer of 10000 lovelace (0.01 ADA) is
                        // far less than the cost of a failed re-submission.
                        var body = txn.getBody();
                        BigInteger feePadding = BigInteger.valueOf(10_000L);
                        BigInteger oldFee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
                        BigInteger newFee = oldFee.add(feePadding);
                        body.setFee(newFee);
                        // Subtract from the largest fee-payer-addressed output
                        // (the change). Without this the tx would be
                        // over-balanced (in - out - fee != 0) → ValueNotConservedUTxO.
                        var outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (var o : outputs) {
                            if (!senderAddrBech32.equals(o.getAddress())) continue;
                            BigInteger coin = o.getValue().getCoin();
                            if (coin.compareTo(largestChange) > 0) {
                                largestChange = coin;
                                changeOut = o;
                            }
                        }
                        if (changeOut != null && largestChange.compareTo(feePadding) > 0) {
                            changeOut.getValue().setCoin(largestChange.subtract(feePadding));
                        }
                        // Bump total_collateral by feePadding * 1.5 (the
                        // collateral-percentage protocol param, typically 150%),
                        // rounded up to 2× for safety against rounding. Reduce
                        // collateral_return by the same amount so the
                        // invariant inputs - return = total_collateral holds.
                        if (body.getTotalCollateral() != null && body.getCollateralReturn() != null) {
                            BigInteger collateralBump = feePadding.multiply(BigInteger.valueOf(2));
                            body.setTotalCollateral(body.getTotalCollateral().add(collateralBump));
                            var ret = body.getCollateralReturn();
                            BigInteger newReturnCoin = ret.getValue().getCoin().subtract(collateralBump);
                            if (newReturnCoin.signum() > 0) {
                                ret.getValue().setCoin(newReturnCoin);
                            } else {
                                body.setCollateralReturn(null);
                            }
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token transfer: policy={} amount={} {} → {}",
                    policyId, amountToTransfer, senderAddrBech32, recipientAddress.getAddress());
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token transfer failed for unit={}", request.unit(), e);
            return TransactionContext.typedError("transfer failed: " + e.getMessage());
        }
    }

    /** Convert a Utxo into the {@link TransactionInput} reference shape. */
    private static TransactionInput txInputOf(Utxo u) {
        return TransactionInput.builder()
                .transactionId(u.getTxHash()).index(u.getOutputIndex()).build();
    }

    /** Decode an MPF proof from CBOR hex. The on-chain validator expects a
     *  {@code merkle_patricia_forestry.Proof} (an Aiken List). */
    private static PlutusData decodeMpfProof(String cborHex)
            throws com.bloxbean.cardano.client.exception.CborDeserializationException {
        return PlutusData.deserialize(HexUtil.decodeHexString(cborHex));
    }

    /** Build one TransferLogicScriptDestinationAction entry:
     *  {@code Constr 0 [destination_proof: KycProof, destination_denylist_covering_ref_input_index: Int]}.
     *  destination_proof is a Membership variant ({@code Constr 1 (Constr 0 [pkh, valid_until, mpf_proof])}).
     *  When {@code includeKycProof=false} we still emit a Membership shape with
     *  empty mpf_proof — the validator short-circuits verify_kyc_proof when
     *  requires_receiver_kyc is false, so the proof contents are ignored. */
    private static PlutusData buildDestinationAction(byte[] destStakeHash,
                                                     boolean includeKycProof,
                                                     String mpfProofCborHex,
                                                     Long mpfValidUntilMs,
                                                     int denylistCoveringRefIdx)
            throws com.bloxbean.cardano.client.exception.CborDeserializationException {
        PlutusData membership;
        if (includeKycProof && mpfProofCborHex != null && mpfValidUntilMs != null) {
            membership = ConstrPlutusData.of(1,
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(destStakeHash),
                            BigIntPlutusData.of(BigInteger.valueOf(mpfValidUntilMs)),
                            decodeMpfProof(mpfProofCborHex)));
        } else {
            membership = ConstrPlutusData.of(1,
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(destStakeHash),
                            BigIntPlutusData.of(BigInteger.ZERO),
                            ListPlutusData.of()));
        }
        return ConstrPlutusData.of(0,
                membership,
                BigIntPlutusData.of(BigInteger.valueOf(denylistCoveringRefIdx)));
    }

    /** Global-state genesis. One tx that mints all three NFTs (GlobalState, denylist
     *  root, power-users root) and pays them to their respective script addresses
     *  with initial datums. Also writes the {@link SecurityTokenRegistrationEntity}
     *  row and, when {@code bootstrapPowerUserPkh} is set on the request, seeds the
     *  off-chain {@code SecurityTokenPowerUserEntity} row so the admin sees themselves
     *  in the admin UI immediately. (The matching on-chain {@code AddPowerUser} tx is
     *  a separate operation triggered from the admin page.)
     *
     *  <p>This is a first-pass implementation that wires the genesis tx structure
     *  end-to-end. The exact linked-list root datum encoding follows the anastasia-labs
     *  v1.6 {@code linked_list.Element} shape: {@code Constr 0 [Root{Unit}, None]}.
     *  Devnet iteration is expected for any encoding edge cases. */
    public TransactionContext<RegistrationResult> buildGlobalStateInitTransaction(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            var adminAddress = request.getFeePayerAddress();
            if (adminAddress == null || adminAddress.isBlank()) {
                return TransactionContext.typedError("feePayerAddress is required");
            }
            var adminPkh = request.getAdminPubKeyHash();
            if (adminPkh == null || adminPkh.length() != 56) {
                return TransactionContext.typedError("adminPubKeyHash (28-byte hex) is required");
            }
            var securityAssetNameHex = request.getAssetName();
            if (securityAssetNameHex == null || securityAssetNameHex.isBlank()) {
                return TransactionContext.typedError("assetName (hex) is required");
            }

            // 1. Select a pure-ADA bootstrap UTxO from the admin's wallet. Its
            // OutputReference is the one-shot nonce for the GS mint + both LL mints.
            var utilityUtxos = accountService.findAdaOnlyUtxo(adminAddress, 10_000_000L);
            if (utilityUtxos.isEmpty()) {
                return TransactionContext.typedError(
                        "no ADA-only UTxOs at admin address (need ~10 ADA for fees + 3x min-utxo)");
            }
            var bootstrap = utilityUtxos.getFirst();
            var bootstrapInput = TransactionInput.builder()
                    .transactionId(bootstrap.getTxHash())
                    .index(bootstrap.getOutputIndex())
                    .build();

            // 2. Build the three mint scripts and derive their policy ids.
            var gsMintScript = scriptBuilder.buildGlobalStateMintScript(bootstrapInput);
            var globalStatePolicyId = gsMintScript.getPolicyId();

            var denylistMintScript = scriptBuilder.buildDenylistMintScript(globalStatePolicyId, bootstrapInput);
            var denylistPolicyId = denylistMintScript.getPolicyId();

            var powerUsersMintScript = scriptBuilder.buildPowerUsersMintScript(globalStatePolicyId, bootstrapInput);
            var powerUsersPolicyId = powerUsersMintScript.getPolicyId();

            // 3. Derive the prog-token policy id (= issuance_policy_id for the GS
            //    spend script) by wrapping the BaFin minting_logic_script in the
            //    CIP-113 generic issuance contract. The chain is:
            //      bootstrap → gs_policy → (pu_policy, dl_policy)
            //      → minting_logic_script(asset_name, gs_policy, registry_policy_id, pu_policy)
            //      → issuance_mint_script(protocolParams, minting_logic_script) → progTokenPolicyId
            //    All deterministic from the bootstrap UTxO + securityAssetNameHex.
            var registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            var mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    securityAssetNameHex, globalStatePolicyId, registryPolicyId, powerUsersPolicyId);
            var issuanceMintScript = protocolScriptBuilderService.getParameterizedIssuanceMintScript(
                    protocolParams, mintingLogicScript);
            var issuancePolicyId = issuanceMintScript.getPolicyId();

            // 4. Build the spend scripts and derive their addresses.
            var gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    securityAssetNameHex, issuancePolicyId, globalStatePolicyId);
            var gsSpendAddress = AddressProvider.getEntAddress(gsSpendScript, network.getCardanoNetwork());

            var denylistSpendScript = scriptBuilder.buildDenylistSpendScript(denylistPolicyId);
            var denylistSpendAddress = AddressProvider.getEntAddress(denylistSpendScript, network.getCardanoNetwork());

            var powerUsersSpendScript = scriptBuilder.buildPowerUsersSpendScript(globalStatePolicyId, powerUsersPolicyId);
            var powerUsersSpendAddress = AddressProvider.getEntAddress(powerUsersSpendScript, network.getCardanoNetwork());

            // 4. Build the three initial datums.
            var gsDatum = buildInitialGlobalStateDatum(
                    adminPkh, powerUsersPolicyId, denylistPolicyId,
                    request.getInitialMintableAmount() != null ? request.getInitialMintableAmount() : 0L,
                    request.isRequiresReceiverKyc(),
                    request.getInitialTrustedEntityVkeys());
            var linkedListRootDatum = buildLinkedListRootDatum();

            // 5. Build the values for the three NFT outputs.
            var gsNft = Asset.builder()
                    .name("0x" + SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX)
                    .value(BigInteger.ONE).build();
            var emptyAsset = Asset.builder().name("0x").value(BigInteger.ONE).build();

            var gsValue = oneNftValue(globalStatePolicyId, gsNft);
            var denylistValue = oneNftValue(denylistPolicyId, emptyAsset);
            var powerUsersValue = oneNftValue(powerUsersPolicyId, emptyAsset);

            // 6. Compose the genesis tx.
            // Mint redeemers: GS uses Data placeholder (validator accepts anything),
            // denylist + power-users use `Init { root_output_index }` = Constr 0 [Int].
            var emptyRedeemer = ConstrPlutusData.of(0);
            // Output indexes: 0 = GS, 1 = denylist root, 2 = power-users root (then change).
            var denylistInitRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(1));
            var powerUsersInitRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(2));

            // Register the mintingLogic withdraw-0 stake credential in this same
            // tx — the downstream registration tx withdraws-0 from it, so the
            // credential must be in the rewards state. transferLogic's credential
            // is intentionally NOT registered here: (a) registration tx doesn't
            // touch it, and (b) including its cert validator pushes the genesis
            // tx over the 16KB size limit. transferLogic gets registered lazily,
            // in the first transfer tx (TODO).
            var mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork()).getAddress();
            boolean mintingLogicCredAlreadyRegistered = stakeRegistrationRepository
                    .findRegistrationsByStakeAddress(mintingLogicRewardAddress)
                    .map(reg -> reg.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            log.info("security-token genesis: mintingLogic stake cred {} ({})",
                    mintingLogicCredAlreadyRegistered ? "already registered" : "will be registered in this tx",
                    mintingLogicRewardAddress);

            var tx = new Tx()
                    .collectFrom(utilityUtxos)
                    .mintAsset(gsMintScript, gsNft, emptyRedeemer)
                    .mintAsset(denylistMintScript, emptyAsset, denylistInitRedeemer)
                    .mintAsset(powerUsersMintScript, emptyAsset, powerUsersInitRedeemer)
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue), gsDatum)
                    .payToContract(denylistSpendAddress.getAddress(), ValueUtil.toAmountList(denylistValue), linkedListRootDatum)
                    .payToContract(powerUsersSpendAddress.getAddress(), ValueUtil.toAmountList(powerUsersValue), linkedListRootDatum)
                    .withChangeAddress(adminAddress);

            // Attach script + cert intent. The preBalanceTx below swaps the
            // pre-Conway StakeRegistration to Conway RegCert (script credentials
            // require a deposit and a publish redeemer in Conway).
            if (!mintingLogicCredAlreadyRegistered) {
                tx = tx
                        .attachCertificateValidator(mintingLogicScript)
                        .registerStakeAddress(mintingLogicRewardAddress);
            }

            var firstUtilityUtxo = utilityUtxos.getFirst();
            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(adminAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtilityUtxo.getTxHash())
                            .index(firstUtilityUtxo.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Conway-era cert swap (see comments in buildRegistrationTransaction
                        // for the upstream BaFin pattern). For each StakeRegistration
                        // with a SCRIPT credential, replace with RegCert + inject a
                        // publish redeemer pointing at the cert's index.
                        var certs = txn.getBody().getCerts();
                        if (certs == null) return;
                        for (int i = 0; i < certs.size(); i++) {
                            if (!(certs.get(i)
                                    instanceof com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration sr)) continue;
                            var cred = sr.getStakeCredential();
                            if (cred.getType()
                                    != com.bloxbean.cardano.client.transaction.spec.cert.StakeCredType.SCRIPTHASH) continue;
                            certs.set(i, com.bloxbean.cardano.client.transaction.spec.cert.RegCert.builder()
                                    .stakeCredential(cred)
                                    .coin(BigInteger.valueOf(2_000_000L))
                                    .build());
                            var ws = txn.getWitnessSet();
                            if (ws.getRedeemers() == null) {
                                ws.setRedeemers(new ArrayList<>());
                            }
                            var publishRedeemer = com.bloxbean.cardano.client.plutus.spec.Redeemer.builder()
                                    .tag(com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Cert)
                                    .data(com.bloxbean.cardano.client.plutus.spec.PlutusData.unit())
                                    .exUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                                            .mem(BigInteger.valueOf(1_000_000))
                                            .steps(BigInteger.valueOf(500_000_000))
                                            .build())
                                    .build();
                            publishRedeemer.setIndex(i);
                            ws.getRedeemers().add(publishRedeemer);
                        }
                    })
                    .build();

            // 7. Persist the registration row. issuancePolicyId IS the prog-token
            //    policy id (deterministic from the bootstrap UTxO + asset name +
            //    protocol params), so we use it as the row's primary key from genesis
            //    time onward. The subsequent registration tx just mints under this
            //    policy and registers it in the CIP-113 directory — no key rewrite.
            registrationRepository.save(SecurityTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(issuancePolicyId)
                    .issuerAdminPkh(adminPkh)
                    .globalStatePolicyId(globalStatePolicyId)
                    .denylistPolicyId(denylistPolicyId)
                    .powerUsersPolicyId(powerUsersPolicyId)
                    .requiresReceiverKyc(request.isRequiresReceiverKyc())
                    .securityAssetNameHex(securityAssetNameHex)
                    .bootstrapTxHash(bootstrap.getTxHash())
                    .bootstrapOutputIndex(bootstrap.getOutputIndex())
                    // Empty member root — encoded as "" (zero-length hex) to
                    // match the on-chain genesis datum (BytesPlutusData.of(new
                    // byte[0])). Using EMPTY_ROOT_HEX (32 zero bytes) here used
                    // to cause currentRoot()-for-empty-trie to falsely diverge
                    // from the recorded on-chain value, triggering phantom
                    // publishes of 0x0000…0000 into the GS datum.
                    .memberRootHashOnchain("")
                    .memberRootHashLocal("")
                    .build());

            // 8. Auto-seed the bootstrap power-user DB row so the admin sees themselves
            // immediately on the admin page. The matching on-chain AddPowerUser tx is
            // submitted as a follow-up step from the wizard via
            // {@link #buildAddPowerUserTransaction}.
            String bootstrapPkh = request.getBootstrapPowerUserPkh() != null
                    ? request.getBootstrapPowerUserPkh()
                    : adminPkh;
            int bootstrapCaps = request.getBootstrapPowerUserCapabilities() != null
                    ? request.getBootstrapPowerUserCapabilities()
                    : ALL_CAPABILITIES_BITFIELD;
            if (!powerUserRepository.existsByProgrammableTokenPolicyIdAndPowerUserPkh(
                    issuancePolicyId, bootstrapPkh)) {
                powerUserRepository.save(SecurityTokenPowerUserEntity.builder()
                        .programmableTokenPolicyId(issuancePolicyId)
                        .powerUserPkh(bootstrapPkh)
                        .capabilities(bootstrapCaps)
                        .label(request.getBootstrapPowerUserLabel() != null
                                ? request.getBootstrapPowerUserLabel()
                                : "Bootstrap admin")
                        .addedAt(Instant.now())
                        .build());
            }

            return TransactionContext.ok(transaction.serializeToHex(),
                    new RegistrationResult(issuancePolicyId));

        } catch (Exception e) {
            log.error("security-token global-state init failed", e);
            return TransactionContext.typedError("init failed: " + e.getMessage());
        }
    }

    /** Bitfield encoding all 5 power-user capabilities (matches BaFin's PowerUser);
     *  the bootstrap admin gets all by default. */
    private static final int ALL_CAPABILITIES_BITFIELD = 0b11111; // 5 bits, all set

    /** {@code "Node"} — the node key prefix used by the BaFin linked-list
     *  (from {@code lib/constants.ak}). Power-user node asset names are
     *  {@code NODE_KEY_PREFIX ++ user_pkh}. */
    private static final byte[] LL_NODE_KEY_PREFIX = "Node".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** Minimum lovelace placed on a script-locked UTxO that carries an NFT. */
    private static final long SCRIPT_UTXO_LOVELACE = 2_000_000L;

    /** Encode the initial 9-field {@code GlobalStateDatum} per the BaFin shape.
     *  Field order MUST match {@code lib/types/global_state.ak} exactly.
     *
     *  <p>NB: {@code trusted_entity_vkeys} is an Aiken {@code Pairs<...>} which
     *  encodes as a CBOR Map (not a List). Empty Map ≠ empty List at the byte
     *  level. The mint validator's {@code sanitise_initial_datum} calls
     *  {@code is_sorted_no_dup_vkeys} which iterates this value as Pairs. */
    private static PlutusData buildInitialGlobalStateDatum(
            String adminPkh,
            String powerUsersPolicyId,
            String denylistPolicyId,
            long mintableAmount,
            boolean requiresReceiverKyc,
            List<String> initialTrustedEntityVkeys) {
        // Build trusted_entity_vkeys as a sorted MapPlutusData with each
        // 32-byte vkey → unit metadata (Constr 0 []). Sorting by vkey bytes
        // matches BaFin's on-chain invariant — verify_kyc_proof + AddTrusted
        // both expect lex-ordered keys.
        var trustedMap = MapPlutusData.builder()
                .map(new LinkedHashMap<>())
                .build();
        if (initialTrustedEntityVkeys != null && !initialTrustedEntityVkeys.isEmpty()) {
            initialTrustedEntityVkeys.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .sorted(String::compareToIgnoreCase)
                    .forEach(vkeyHex -> trustedMap.put(
                            // MUST decode the hex string to raw 32 bytes.
                            // BytesPlutusData.of(String) stores the UTF-8
                            // bytes of the input verbatim (64 ASCII chars
                            // for a 32-byte vkey), which the on-chain
                            // sanitise_initial_datum check rejects with
                            // `error` because keys are expected to be 32
                            // raw bytes (Ed25519 vkey length).
                            BytesPlutusData.of(HexUtil.decodeHexString(vkeyHex)),
                            PlutusData.unit()));
        }
        return ConstrPlutusData.of(0,
                ConstrPlutusData.of(0),                                           // transfers_paused = False
                BigIntPlutusData.of(BigInteger.valueOf(mintableAmount)),          // mintable_amount
                BytesPlutusData.of(HexUtil.decodeHexString(adminPkh)),            // admin_credential_hash
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersPolicyId)),  // power_user_linked_list_policy_id
                BytesPlutusData.of(HexUtil.decodeHexString(denylistPolicyId)),    // denylist_linked_list_policy_id
                ConstrPlutusData.of(0),                                           // security_info = Unit (Data placeholder)
                trustedMap,                                                       // trusted_entity_vkeys
                BytesPlutusData.of(new byte[0]),                                  // member_root_hash = empty (matches upstream E2ETest)
                ConstrPlutusData.of(requiresReceiverKyc ? 1 : 0)                  // requires_receiver_kyc
        );
    }

    /** Encode an empty linked-list root: {@code Element { data: Root { data: Unit }, link: None }}.
     *  Matches {@code aiken_design_patterns/linked_list.Element}: outer Constr 0
     *  carries {@code ElementData} (Constr 0 = Root, Constr 1 = Node) and a
     *  {@code Link} (Option = Constr 0 [x] for Some, Constr 1 [] for None). */
    private static ConstrPlutusData buildLinkedListRootDatum() {
        return linkedListElement(ConstrPlutusData.of(0), optionNone(), /*isRoot=*/ true);
    }

    /** Wrap inner data + link as {@code linked_list.Element}. */
    private static ConstrPlutusData linkedListElement(PlutusData innerData, PlutusData link, boolean isRoot) {
        var elementData = ConstrPlutusData.of(isRoot ? 0 : 1, innerData);
        return ConstrPlutusData.of(0, elementData, link);
    }

    private static ConstrPlutusData optionSome(PlutusData inner) {
        return ConstrPlutusData.of(0, inner);
    }

    private static ConstrPlutusData optionNone() {
        return ConstrPlutusData.of(1);
    }

    /** Encode a {@code PowerUser} record per {@code lib/types/power_users.ak}:
     *  {@code Constr 0 [credential_hash, is_admin, can_mint, can_burn, can_pause, can_force_transfer]}. */
    private static ConstrPlutusData powerUserData(byte[] pkh, int capabilities) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(pkh),
                plutusBool((capabilities & SecurityTokenPowerUserCapability.ADMIN.bit()) != 0),
                plutusBool((capabilities & SecurityTokenPowerUserCapability.MINTER.bit()) != 0),
                plutusBool((capabilities & SecurityTokenPowerUserCapability.BURNER.bit()) != 0),
                plutusBool((capabilities & SecurityTokenPowerUserCapability.PAUSER.bit()) != 0),
                plutusBool((capabilities & SecurityTokenPowerUserCapability.FORCE_TRANSFER.bit()) != 0));
    }

    private static ConstrPlutusData plutusBool(boolean b) {
        return ConstrPlutusData.of(b ? 1 : 0);
    }

    private static Value oneNftValue(String policyId, Asset asset) {
        return Value.builder()
                .coin(BigInteger.valueOf(SCRIPT_UTXO_LOVELACE))
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(policyId)
                        .assets(List.of(asset))
                        .build()))
                .build();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        var out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Poll Blockfrost / YACI for the first UTxO carrying any asset under {@code policyId},
     *  retrying every 5s up to {@code timeout}. Returns {@code null} on timeout.
     *  Used for tx-chaining within a wizard session where the prior tx is still
     *  propagating through the indexer. */
    private com.bloxbean.cardano.client.api.model.Utxo pollForFirstUtxoByPolicy(
            String policyId, String label, java.time.Duration timeout) {
        var deadline = Instant.now().plus(timeout);
        var pollInterval = java.time.Duration.ofSeconds(5);
        int attempt = 0;
        while (Instant.now().isBefore(deadline)) {
            attempt++;
            try {
                var utxos = utxoProvider.findUtxosByPolicy(policyId);
                if (!utxos.isEmpty()) {
                    if (attempt > 1) {
                        log.info("{} appeared on chain after {} attempts", label, attempt);
                    }
                    return utxos.getFirst();
                }
            } catch (Exception e) {
                log.debug("pollForFirstUtxoByPolicy({}) attempt {}: {}", label, attempt, e.getMessage());
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn("{} still not on chain after {} ({} attempts)", label, timeout, attempt);
        return null;
    }

    /** Position of {@code target} after lex-sorting {@code utxos} by (txHash, outputIndex).
     *  Plutus tx execution sees inputs in that canonical order, so redeemer
     *  indices computed off-chain MUST sort the same way. */
    private static int lexIndex(List<com.bloxbean.cardano.client.api.model.Utxo> utxos,
                                com.bloxbean.cardano.client.api.model.Utxo target) {
        var sorted = new ArrayList<>(utxos);
        sorted.sort(java.util.Comparator
                .comparing(com.bloxbean.cardano.client.api.model.Utxo::getTxHash)
                .thenComparingInt(com.bloxbean.cardano.client.api.model.Utxo::getOutputIndex));
        return sorted.indexOf(target);
    }

    // ── On-chain power-user / denylist insertion ─────────────────────────────

    /** Build an {@code AddPowerUser} transaction that inserts a new node into the
     *  on-chain power-users linked list. Models the upstream BaFin E2ETest flow
     *  ({@code runAddAdminAsPowerUser}): spend the LL root as the "anchor", mint
     *  a new node NFT ({@code asset_name = "Node" ++ user_pkh}), and emit two
     *  outputs back to the LL spend address — the updated root (now pointing at
     *  the new node) and the new node itself.
     *
     *  <p>v1 limitation: only handles the very first insertion (anchor = root).
     *  Subsequent insertions would need to walk the existing chain to find the
     *  correct anchor based on lexicographic key order — TODO when more than
     *  one power user exists. */
    public TransactionContext<Void> buildAddPowerUserTransaction(
            String policyId,
            String powerUserPkhHex,
            int capabilities,
            String adminAddress) {
        return buildAddPowerUserTransaction(
                policyId, powerUserPkhHex, capabilities, adminAddress,
                /*overridePuRoot=*/ null, /*overrideGsUtxo=*/ null, /*overrideFunding=*/ null);
    }

    /** Chain-aware overload of {@link #buildAddPowerUserTransaction}. When any of
     *  the override UTxOs are non-null, they are used directly instead of the
     *  on-chain discovery (polling by policy id for the linked-list NFTs,
     *  {@link AccountService#findAdaOnlyUtxo} for the funding UTxO). The chain
     *  orchestrator {@link #buildFullRegistrationChain} extracts these from the
     *  preceding genesis tx's outputs so AddPowerUser can be built without
     *  waiting for genesis to confirm.
     *
     *  <p>Passing null preserves the legacy behaviour (poll + discover). */
    public TransactionContext<Void> buildAddPowerUserTransaction(
            String policyId,
            String powerUserPkhHex,
            int capabilities,
            String adminAddress,
            com.bloxbean.cardano.client.api.model.Utxo overridePuRoot,
            com.bloxbean.cardano.client.api.model.Utxo overrideGsUtxo,
            com.bloxbean.cardano.client.api.model.Utxo overrideFunding) {
        try {
            var regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError("security-token registration not found for policy " + policyId);
            }
            var reg = regOpt.get();
            if (reg.getBootstrapTxHash() == null) {
                return TransactionContext.typedError("registration has no bootstrap UTxO recorded — was genesis init run?");
            }

            byte[] newPowerUserKey = HexUtil.decodeHexString(powerUserPkhHex);
            byte[] newNodeAssetName = concat(LL_NODE_KEY_PREFIX, newPowerUserKey);
            String newNodeAssetNameHex = HexUtil.encodeHexString(newNodeAssetName);

            // Rebuild the parameterised scripts from the persisted registration.
            var bootstrapInput = TransactionInput.builder()
                    .transactionId(reg.getBootstrapTxHash())
                    .index(reg.getBootstrapOutputIndex())
                    .build();
            var puMintScript = scriptBuilder.buildPowerUsersMintScript(reg.getGlobalStatePolicyId(), bootstrapInput);
            var puSpendScript = scriptBuilder.buildPowerUsersSpendScript(
                    reg.getGlobalStatePolicyId(), reg.getPowerUsersPolicyId());
            var puSpendAddress = AddressProvider.getEntAddress(puSpendScript, network.getCardanoNetwork());

            // Resolve PU root, GS, and funding UTxOs. When chain-mode overrides are
            // provided we use them directly (genesis tx isn't on chain yet so the
            // poll-by-policy paths would time out). Otherwise fall back to on-chain
            // discovery — the admin-page "Sync to chain" button uses that path.
            com.bloxbean.cardano.client.api.model.Utxo puRoot = overridePuRoot;
            if (puRoot == null) {
                puRoot = pollForFirstUtxoByPolicy(reg.getPowerUsersPolicyId(),
                        "power-users linked-list root NFT", java.time.Duration.ofSeconds(90));
                if (puRoot == null) {
                    return TransactionContext.typedError(
                            "power-users linked-list root NFT not found on chain after 90s — " +
                            "genesis tx may still be propagating; try the 'Sync to chain' button on the admin page in a minute");
                }
            }

            com.bloxbean.cardano.client.api.model.Utxo gsUtxo = overrideGsUtxo;
            if (gsUtxo == null) {
                gsUtxo = pollForFirstUtxoByPolicy(reg.getGlobalStatePolicyId(),
                        "global-state NFT", java.time.Duration.ofSeconds(30));
                if (gsUtxo == null) {
                    return TransactionContext.typedError(
                            "global-state NFT not found on chain — was genesis init confirmed?");
                }
            }

            com.bloxbean.cardano.client.api.model.Utxo funding = overrideFunding;
            if (funding == null) {
                var fundingUtxos = accountService.findAdaOnlyUtxo(adminAddress, 5_000_000L);
                if (fundingUtxos.isEmpty()) {
                    return TransactionContext.typedError(
                            "no confirmed ADA-only UTxO at admin address for fees — " +
                            "if you just ran the genesis tx, wait ~30s for the change output to confirm, " +
                            "then retry 'Sync to chain' from the admin page");
                }
                funding = fundingUtxos.getFirst();
            }

            // Inputs are lex-sorted on (txHash, outputIndex) by the node — compute
            // the anchor's input position deterministically so the redeemer carries
            // the right index.
            int anchorInIdx = lexIndex(List.of(puRoot, funding), puRoot);
            int anchorOutIdx = 0;   // updated root → output 0
            int newNodeOutIdx = 1;  // new node      → output 1
            int gsRefIdx = 0;       // GS is the only reference input

            // Mint redeemer: AddPowerUser = variant 2 of MintRedeemer.
            var addPowerUserRedeemer = ConstrPlutusData.of(2,
                    BytesPlutusData.of(newPowerUserKey),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorInIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(newNodeOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdx)));

            // Spend redeemer on the root: StateTransition (Constr 0) — delegates
            // shape checks to the mint validator.
            var rootSpendRedeemer = ConstrPlutusData.of(0);

            // Updated root datum: same Root payload, link now points at new node's key.
            var updatedRootDatum = linkedListElement(
                    ConstrPlutusData.of(0),
                    optionSome(BytesPlutusData.of(newPowerUserKey)),
                    /*isRoot=*/ true);

            // New node datum: Node(PowerUser{...}), link = None (it's the tail).
            var newNodeDatum = linkedListElement(
                    powerUserData(newPowerUserKey, capabilities),
                    optionNone(),
                    /*isRoot=*/ false);

            // Asset for the new node NFT (asset name = "Node" ++ pkh).
            var newNodeNft = Asset.builder()
                    .name("0x" + newNodeAssetNameHex)
                    .value(BigInteger.ONE).build();

            var puPolicyId = reg.getPowerUsersPolicyId();

            // Outputs (must match the indices in addPowerUserRedeemer above):
            //   0: updated LL root — carries the preserved empty-name root NFT
            //      + min ADA, with link → new node.
            //   1: new node — carries the freshly minted node NFT + min ADA,
            //      with link = None, and the PowerUser datum.
            //
            // We mint the new node NFT and emit its output in a single call:
            // Tx.mintAsset(script, [asset], redeemer, receiver, outputDatum) is
            // the only variant that attaches a Plutus datum to the mint output,
            // which is required because the on-chain script reads the new node's
            // datum via `expect _parsed: PowerUser = node_data`. The earlier
            // approach (mintAsset to receiver + a separate payToContract with
            // the datum) produced TWO outputs at the LL spend address — one
            // datum-less (the mint receiver) and one with the datum — which
            // caused appended_node_output_index=1 to point at the datum-less
            // output and the script to error decoding it as a PowerUser.
            var rootOutputValue = oneNftValue(puPolicyId,
                    Asset.builder().name("0x").value(BigInteger.ONE).build());

            Tx tx = new Tx()
                    .collectFrom(puRoot, rootSpendRedeemer)
                    .collectFrom(List.of(funding))
                    .attachSpendingValidator(puSpendScript)
                    .payToContract(puSpendAddress.getAddress(),
                            ValueUtil.toAmountList(rootOutputValue), updatedRootDatum)        // output 0
                    .mintAsset(puMintScript, List.of(newNodeNft), addPowerUserRedeemer,
                            puSpendAddress.getAddress(), newNodeDatum)                        // output 1
                    .readFrom(TransactionInput.builder()
                            .transactionId(gsUtxo.getTxHash())
                            .index(gsUtxo.getOutputIndex()).build())
                    .withChangeAddress(adminAddress);

            // Explicitly pin collateral to the funding UTxO. Without this,
            // Bloxbean queries Blockfrost for any pure-ADA UTxO at the admin
            // address — and in chain mode Blockfrost still reports the bootstrap
            // UTxO (which genesis already consumed in mempool) as unspent. The
            // resulting tx fails on submit with BadInputsUTxO referencing the
            // bootstrap. Pinning to `funding` (the chained change from genesis
            // outputs, guaranteed to be in mempool) avoids this race.
            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(HexUtil.decodeHexString(reg.getIssuerAdminPkh()))
                    .feePayer(adminAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .build();

            return TransactionContext.ok(transaction.serializeToHex());

        } catch (Exception e) {
            log.error("security-token AddPowerUser failed for policy={} pkh={}",
                    policyId, powerUserPkhHex, e);
            return TransactionContext.typedError("AddPowerUser failed: " + e.getMessage());
        }
    }

    // ── BlacklistManageable ─────────────────────────────────────────────────
    //
    // The BaFin denylist mint script's MintRedeemer is structurally identical
    // to the power_users mint script's MintRedeemer (Init / Deinit / Add /
    // Remove with the same field shape). So buildAddToBlacklistTransaction
    // and buildRemoveFromBlacklistTransaction mirror the AddPowerUser /
    // RemovePowerUser logic verbatim — only the scripts, policy id, node
    // datum, and required-signer differ.
    //
    // The blacklist init is performed at genesis (see
    // buildGlobalStateInitTransaction); calling it again would mint a second
    // root NFT, so we reject that as unsupported.
    //
    // v1 limitation (same as AddPowerUser): only handles the first insertion
    // (anchor = root). Subsequent insertions need to walk the chain to find
    // the correct anchor by lex-ordered key — TODO once a populated denylist
    // motivates implementation.

    @Override
    public TransactionContext<TransactionContext.MintingResult> buildBlacklistInitTransaction(
            org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.BlacklistInitRequest request,
            ProtocolBootstrapParams protocolParams) {
        return TransactionContext.typedError(
                "security-token denylist is initialised at genesis (buildGlobalStateInitTransaction). "
                + "Calling /compliance/blacklist/init for a security-token is not supported.");
    }

    @Override
    public TransactionContext<Void> buildAddToBlacklistTransaction(
            org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.AddToBlacklistRequest request,
            ProtocolBootstrapParams protocolParams) {
        return buildDenylistMutation(request.tokenPolicyId(),
                request.targetAddress(), request.feePayerAddress(), /*isAdd=*/ true);
    }

    @Override
    public TransactionContext<Void> buildRemoveFromBlacklistTransaction(
            org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.RemoveFromBlacklistRequest request,
            ProtocolBootstrapParams protocolParams) {
        return buildDenylistMutation(request.tokenPolicyId(),
                request.targetAddress(), request.feePayerAddress(), /*isAdd=*/ false);
    }

    /** Shared implementation for AddToDenylist / RemoveFromDenylist —
     *  the on-chain MintRedeemer shapes are nearly identical (anchor IO indices
     *  + optional removed-node-input-index for removes). Modelled on
     *  {@link #buildAddPowerUserTransaction}. */
    private TransactionContext<Void> buildDenylistMutation(
            String policyId, String targetAddress, String feePayerAddress, boolean isAdd) {
        try {
            var regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            var reg = regOpt.get();
            if (reg.getBootstrapTxHash() == null) {
                return TransactionContext.typedError(
                        "registration has no bootstrap UTxO recorded — was genesis init run?");
            }

            // Identity for the denylist is the STAKE credential hash — that's
            // what BaFin transfer_logic_script aggregates from inputs/outputs
            // and looks up via verify_denylist_absence.
            Address target = new Address(targetAddress);
            byte[] targetStakeHash = target.getDelegationCredentialHash().orElse(null);
            if (targetStakeHash == null) {
                return TransactionContext.typedError(
                        "targetAddress must be a base address (need stake credential): " + targetAddress);
            }
            byte[] newNodeAssetName = concat(LL_NODE_KEY_PREFIX, targetStakeHash);
            String newNodeAssetNameHex = HexUtil.encodeHexString(newNodeAssetName);

            // Rebuild parameterised scripts from the persisted registration.
            var bootstrapInput = TransactionInput.builder()
                    .transactionId(reg.getBootstrapTxHash())
                    .index(reg.getBootstrapOutputIndex())
                    .build();
            var denylistMintScript = scriptBuilder.buildDenylistMintScript(
                    reg.getGlobalStatePolicyId(), bootstrapInput);
            var denylistSpendScript = scriptBuilder.buildDenylistSpendScript(reg.getDenylistPolicyId());
            var denylistSpendAddress = AddressProvider.getEntAddress(
                    denylistSpendScript, network.getCardanoNetwork());

            // v1: only first insertion (anchor = root).
            Utxo anchorNode = pollForFirstUtxoByPolicy(reg.getDenylistPolicyId(),
                    "denylist linked-list root NFT", java.time.Duration.ofSeconds(30));
            if (anchorNode == null) {
                return TransactionContext.typedError("denylist root NFT not found on chain");
            }
            // For Remove we additionally need the node-to-remove as an input;
            // not supported in v1 (would require walking the LL).
            if (!isAdd) {
                return TransactionContext.typedError(
                        "security-token denylist remove not yet implemented (v1 needs to walk "
                        + "the LL to find the node-to-remove + its predecessor anchor)");
            }

            // GS UTxO as ref input — the denylist mint validator reads
            // admin_credential_hash from it to gate the mutation.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("global-state NFT not found on chain");
            }

            // Funding UTxO at fee payer (admin's wallet).
            var fundingUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, 5_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError(
                        "no confirmed ADA-only UTxO at fee-payer address for fees + collateral");
            }
            Utxo funding = fundingUtxos.getFirst();

            // Lex-sorted input position of the anchor (spend inputs sorted by
            // (txHash, idx) at eval time).
            int anchorInIdx = lexIndex(List.of(anchorNode, funding), anchorNode);
            int anchorOutIdx = 0;   // updated root → output 0
            int newNodeOutIdx = 1;  // new node      → output 1
            int gsRefIdx = 0;       // GS is the only reference input

            // Mint redeemer: AddToDenylist = variant 2 of MintRedeemer (same
            // index as AddPowerUser — see types/denylist.ak vs types/power_users.ak).
            var addToDenylistRedeemer = ConstrPlutusData.of(2,
                    BytesPlutusData.of(targetStakeHash),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorInIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(newNodeOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdx)));

            // Spend redeemer on the root: StateTransition (Constr 0).
            var rootSpendRedeemer = ConstrPlutusData.of(0);

            // Updated root datum: link now points at new node's key (stake hash).
            var updatedRootDatum = linkedListElement(
                    ConstrPlutusData.of(0),                    // Root payload
                    optionSome(BytesPlutusData.of(targetStakeHash)),
                    /*isRoot=*/ true);

            // New node datum: Node(Denylist { metadata: () }), link = None.
            // BaFin's on-chain validators don't read the metadata, so unit is fine.
            PlutusData denylistData = ConstrPlutusData.of(0, ConstrPlutusData.of(0));
            var newNodeDatum = linkedListElement(
                    denylistData,
                    optionNone(),
                    /*isRoot=*/ false);

            var newNodeNft = Asset.builder()
                    .name("0x" + newNodeAssetNameHex)
                    .value(BigInteger.ONE).build();
            var rootOutputValue = oneNftValue(reg.getDenylistPolicyId(),
                    Asset.builder().name("0x").value(BigInteger.ONE).build());

            // Required signer = the on-chain admin from the GS datum. The
            // wallet doing the add is whoever's connected, but the validator
            // checks against the GS datum's admin_credential_hash.
            byte[] adminPkh = HexUtil.decodeHexString(reg.getIssuerAdminPkh());

            Tx tx = new Tx()
                    .collectFrom(anchorNode, rootSpendRedeemer)
                    .collectFrom(List.of(funding))
                    .attachSpendingValidator(denylistSpendScript)
                    .payToContract(denylistSpendAddress.getAddress(),
                            ValueUtil.toAmountList(rootOutputValue), updatedRootDatum)
                    .mintAsset(denylistMintScript, List.of(newNodeNft), addToDenylistRedeemer,
                            denylistSpendAddress.getAddress(), newNodeDatum)
                    .readFrom(TransactionInput.builder()
                            .transactionId(gsUtxo.getTxHash())
                            .index(gsUtxo.getOutputIndex()).build())
                    .withChangeAddress(feePayerAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(adminPkh)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .build();

            log.info("security-token AddToDenylist built: policy={} target_stake={}",
                    policyId, HexUtil.encodeHexString(targetStakeHash));
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token denylist {} failed for policy={} target={}",
                    isAdd ? "add" : "remove", policyId, targetAddress, e);
            return TransactionContext.typedError(
                    "denylist " + (isAdd ? "add" : "remove") + " failed: " + e.getMessage());
        }
    }

    /** Result of {@link #buildFullRegistrationChain}: three unsigned tx CBORs that
     *  the frontend signs in a single CIP-30 {@code signTxs} call and submits
     *  via {@code POST /issue-token/submit-chain} so the wallet's submission
     *  backend never sees the chain (which would reject mempool-chained txs). */
    public record ChainBuildResult(
            String genesisCborHex,
            String addPowerUserCborHex,
            String registrationCborHex,
            /** Conway RegCert for the BaFin transfer_logic_script stake
             *  credential — published in the same CIP-103 batch so the user
             *  doesn't need an interactive cert tx before their first transfer.
             *  Null when the chain orchestrator couldn't fit it (e.g. no admin
             *  change in the registration tx). */
            String registerTransferLogicCborHex,
            String globalStatePolicyId,
            String programmableTokenPolicyId,
            String denylistPolicyId,
            String powerUsersPolicyId,
            String genesisTxHash,
            String addPowerUserTxHash,
            String registrationTxHash,
            String registerTransferLogicTxHash) {}

    /** Build the full security-token registration chain in one call.
     *
     *  <h3>Chain shape</h3>
     *  Three txs, deterministically chained at build time, signed in one wallet
     *  popup and submitted via {@code POST /issue-token/submit-chain} (which
     *  submits sequentially through the backend's submission service so the
     *  wallet's own backend never sees the chain and can't reject mempool-
     *  chained txs):
     *
     *  <pre>
     *    ┌──────────────────┐      genesis change          ┌──────────────────┐
     *    │ 1. GENESIS TX    │  ─────────────────────────▶  │ 2. ADDPOWERUSER  │
     *    │   mints:         │                              │   spends:        │
     *    │     • GS NFT     │  PU root NFT + GS NFT (ref)  │     PU root      │
     *    │     • DL root    │  ───────────────────────────▶│   mints:         │
     *    │     • PU root    │                              │     new PU node  │
     *    │   pays back:     │                              │   pays back:     │
     *    │     • 3 NFTs to  │                              │     updated PU   │
     *    │       script ad- │                              │     root + new   │
     *    │       dresses    │                              │     node + chg   │
     *    │   change → admin │                              └─────────┬────────┘
     *    └──────────────────┘                                        │
     *                                                                │ addPu change
     *                                                                ▼
     *                                                       ┌──────────────────┐
     *                                                       │ 3. REGISTRATION  │
     *                                                       │   spends:        │
     *                                                       │     • dir slot   │
     *                                                       │     • GS         │
     *                                                       │   mints:         │
     *                                                       │     • dir NFT    │
     *                                                       │     • prog tokens│
     *                                                       │   withdraws-0:   │
     *                                                       │     mintingLogic │
     *                                                       │   pays back:     │
     *                                                       │     2× dir slot, │
     *                                                       │     prog tokens, │
     *                                                       │     new GS w/    │
     *                                                       │     decremented  │
     *                                                       │     mintable_amt │
     *                                                       └──────────────────┘
     *  </pre>
     *
     *  <h3>Why this shape</h3>
     *  <ul>
     *    <li>Genesis must be first: it consumes the bootstrap UTxO that
     *        parameterises the three mint scripts (one-shot nonce).</li>
     *    <li>AddPowerUser must come before registration: the registration tx's
     *        BaFin {@code minting_logic_script.withdraw} runs in registration
     *        mode (detected by the directory NFT mint) but the on-chain mint
     *        flow that follows will need the admin power-user node to exist on
     *        chain to validate subsequent {@code MintSecurity} actions.</li>
     *    <li>Registration mints the initial security-token supply AND spends GS
     *        with {@code MintSecurity}, so the supply cap is enforced on the
     *        first mint as well.</li>
     *  </ul>
     *
     *  <h3>Chaining mechanics</h3>
     *  <ul>
     *    <li>Each subsequent tx's funding input is the previous tx's change
     *        output at the admin address — discovered by walking outputs and
     *        matching by address.</li>
     *    <li>Each subsequent tx's anchor inputs (PU root, GS) are the previous
     *        tx's script-locked outputs — discovered the same way, then
     *        injected into {@link HybridUtxoSupplier} so Bloxbean's
     *        UTxO-by-(hash,index) lookups resolve them as mempool UTxOs.</li>
     *    <li>Inline datums are preserved when wrapping outputs as {@link Utxo}s
     *        — Plutus scripts that {@code expect InlineDatum(d) = …} crash with
     *        {@code EvaluationFailure} if the supplier returns null.</li>
     *  </ul>
     *
     *  <p>Caller contract: populate the request with {@code feePayerAddress},
     *  {@code adminPubKeyHash}, hex {@code assetName}, {@code requiresReceiverKyc},
     *  optional {@code initialMintableAmount}, {@code bootstrapPowerUserPkh},
     *  {@code bootstrapPowerUserCapabilities}, {@code quantity} (initial mint
     *  size; defaults to "1" if blank). Returns a typed error when any phase
     *  fails so the wizard can retry from scratch. */
    public TransactionContext<ChainBuildResult> buildFullRegistrationChain(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            String adminAddress = request.getFeePayerAddress();
            if (adminAddress == null || adminAddress.isBlank()) {
                return TransactionContext.typedError("feePayerAddress is required");
            }

            // ── PHASE 1 ─ Build the genesis tx ─────────────────────────────
            // Delegates to buildGlobalStateInitTransaction, which also persists
            // the SecurityTokenRegistrationEntity row keyed on the prog-token
            // policy id. The row is what the next phase's script construction
            // and the controller's loadSecurityTokenContext rely on.
            TransactionContext<RegistrationResult> genesisResult =
                    buildGlobalStateInitTransaction(request, protocolParams);
            if (!genesisResult.isSuccessful()) {
                return TransactionContext.typedError("chain[genesis]: " + genesisResult.error());
            }
            String genesisCbor = genesisResult.unsignedCborTx();
            Transaction genesisTx = Transaction.deserialize(HexUtil.decodeHexString(genesisCbor));
            String genesisTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(genesisTx.serialize());
            // Also hash the RAW CBOR bytes returned to the FE (no Bloxbean
            // deserialize/serialize round-trip). If these two differ, Bloxbean's
            // round-trip is re-encoding the body, which would invalidate any
            // signature Eternl produces against the FE-side bytes.
            String genesisRawTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(HexUtil.decodeHexString(genesisCbor));
            log.info("chain[genesis] tx hash (raw FE bytes)={} hash (Bloxbean round-trip)={} — these MUST match the failed submit tx hash, else the body was mutated post-signing",
                    genesisRawTxHash, genesisTxHash);
            String progTokenPolicyId = genesisResult.metadata() != null
                    ? genesisResult.metadata().policyId() : null;
            if (progTokenPolicyId == null) {
                return TransactionContext.typedError(
                        "chain[genesis]: missing prog-token policy id in metadata");
            }
            SecurityTokenRegistrationEntity reg = registrationRepository
                    .findByProgrammableTokenPolicyId(progTokenPolicyId)
                    .orElseThrow(() -> new IllegalStateException(
                            "registration row not found after genesis build for " + progTokenPolicyId));

            // The /build-chain controller instantiates this handler with an
            // empty SecurityTokenContext. buildRegistrationTransaction reads
            // context fields, so populate from the row we just persisted before
            // continuing into phase 3.
            this.context = SecurityTokenContext.builder()
                    .issuerAdminPkh(reg.getIssuerAdminPkh())
                    .globalStatePolicyId(reg.getGlobalStatePolicyId())
                    .denylistPolicyId(reg.getDenylistPolicyId())
                    .powerUsersPolicyId(reg.getPowerUsersPolicyId())
                    .securityAssetNameHex(reg.getSecurityAssetNameHex())
                    .globalStateInitTxInput(TransactionInput.builder()
                            .transactionId(reg.getBootstrapTxHash())
                            .index(reg.getBootstrapOutputIndex())
                            .build())
                    .requiresReceiverKyc(reg.isRequiresReceiverKyc())
                    .memberRootHashOnchain(reg.getMemberRootHashOnchain())
                    .memberRootHashLocal(reg.getMemberRootHashLocal())
                    .build();

            // ── PHASE 1.5 ─ Extract chained UTxOs from genesis ─────────────
            // Re-derive the script addresses we need to identify outputs by
            // address (rather than hard-coding indices — Bloxbean output
            // ordering can shift across SDK versions and preBalanceTx hooks).
            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId, reg.getPowerUsersPolicyId());
            PlutusScript issuanceForAddresses = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolParams, mintingLogicScript);
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    reg.getSecurityAssetNameHex(),
                    issuanceForAddresses.getPolicyId(), reg.getGlobalStatePolicyId());
            String gsSpendAddr = AddressProvider.getEntAddress(
                    gsSpendScript, network.getCardanoNetwork()).getAddress();
            PlutusScript puSpendScript = scriptBuilder.buildPowerUsersSpendScript(
                    reg.getGlobalStatePolicyId(), reg.getPowerUsersPolicyId());
            String puSpendAddr = AddressProvider.getEntAddress(
                    puSpendScript, network.getCardanoNetwork()).getAddress();

            Utxo chainedGsUtxo = findOutputAtAddress(genesisTx, genesisTxHash, gsSpendAddr, BigInteger.ZERO);
            Utxo chainedPuRoot = findOutputAtAddress(genesisTx, genesisTxHash, puSpendAddr, BigInteger.ZERO);
            Utxo chainedAdminChange = findOutputAtAddress(genesisTx, genesisTxHash, adminAddress,
                    BigInteger.valueOf(5_000_000L));
            if (chainedGsUtxo == null || chainedPuRoot == null || chainedAdminChange == null) {
                return TransactionContext.typedError(
                        "chain[genesis]: could not locate required outputs (gs="
                        + (chainedGsUtxo != null) + ", puRoot=" + (chainedPuRoot != null)
                        + ", change=" + (chainedAdminChange != null) + ")");
            }
            hybridUtxoSupplier.add(chainedGsUtxo);
            hybridUtxoSupplier.add(chainedPuRoot);
            hybridUtxoSupplier.add(chainedAdminChange);

            // ── PHASE 2 ─ Build the AddPowerUser tx ────────────────────────
            // Uses the 7-arg overload so the funding UTxO + PU root anchor + GS
            // ref input are taken directly from the genesis chained UTxOs (no
            // polling — those NFTs aren't on chain yet).
            int allCaps = ALL_CAPABILITIES_BITFIELD;
            String bootstrapPkh = request.getBootstrapPowerUserPkh() != null
                    ? request.getBootstrapPowerUserPkh()
                    : request.getAdminPubKeyHash();
            TransactionContext<Void> addPuResult = buildAddPowerUserTransaction(
                    progTokenPolicyId, bootstrapPkh, allCaps, adminAddress,
                    chainedPuRoot, chainedGsUtxo, chainedAdminChange);
            if (!addPuResult.isSuccessful()) {
                hybridUtxoSupplier.clear();
                return TransactionContext.typedError("chain[addPowerUser]: " + addPuResult.error());
            }
            String addPuCbor = addPuResult.unsignedCborTx();
            Transaction addPuTx = Transaction.deserialize(HexUtil.decodeHexString(addPuCbor));
            String addPuTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(addPuTx.serialize());

            // Find AddPowerUser's admin change output to fund the registration tx.
            Utxo addPuChange = findOutputAtAddress(addPuTx, addPuTxHash, adminAddress,
                    BigInteger.valueOf(5_000_000L));
            if (addPuChange == null) {
                hybridUtxoSupplier.clear();
                return TransactionContext.typedError(
                        "chain[addPowerUser]: no admin change output found to fund registration tx");
            }
            hybridUtxoSupplier.add(addPuChange);

            // ── PHASE 3 ─ Build the registration tx ────────────────────────
            // Registration MUST mint at least one prog token because the
            // CIP-113 directory mint validator requires the issuance contract
            // to mint in the same tx (consistency between the directory
            // entry's recorded substandard hash and the actual mint).
            //
            // The BaFin minting_logic_script.withdraw detects "registration
            // mode" via the directory NFT being minted and rubber-stamps the
            // withdraw — so no GS/PU on-chain refs are needed. But the supply
            // cap must still be enforced: we ALSO spend GS with MintSecurity
            // in this tx (via the 3-arg overload), which decrements
            // mintable_amount independently.
            request.setChainingTransactionCborHex(addPuCbor);
            if (request.getQuantity() == null || request.getQuantity().isBlank()
                    || "0".equals(request.getQuantity())) {
                request.setQuantity("1");
            }
            request.setGlobalStatePolicyId(reg.getGlobalStatePolicyId());
            // request.getInitialMintableAmount() already matches what genesis
            // wrote into the GS datum (same request object), so the registration
            // tx's decrement computation is correct.
            TransactionContext<RegistrationResult> regResult =
                    buildRegistrationTransaction(request, protocolParams, chainedGsUtxo);
            if (!regResult.isSuccessful()) {
                hybridUtxoSupplier.clear();
                return TransactionContext.typedError("chain[registration]: " + regResult.error());
            }
            String regCbor = regResult.unsignedCborTx();
            Transaction regTx = Transaction.deserialize(HexUtil.decodeHexString(regCbor));
            String regTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(regTx.serialize());

            // ── PHASE 4 ─ Register transferLogic stake credential ─────────
            // BaFin's transfer_logic_script needs its script-stake credential
            // registered (Conway RegCert) before any withdraw-0 can happen.
            // We dropped it from genesis to keep that tx under the 16 KB
            // ledger limit. By chaining it as a 4th tx here, the cert gets
            // signed in the same Eternl CIP-103 batch as the genesis cert
            // (which works) — avoiding the runtime-signed cert tx, which
            // Eternl refuses via the single-tx signTx path.
            String certCbor = null;
            String certTxHash = null;
            // Need at least ~5.3 ADA at the admin change for the cert tx:
            //   2 ADA RegCert deposit + 3 ADA self-pay to satisfy the deposit
            //   resolver + ~0.3 ADA tx fee. Threshold of 5.5 ADA on the input
            //   gives a small safety margin without rejecting borderline
            //   registrations. (Lower than 5 ADA can't possibly work.)
            Utxo regAdminChange = findOutputAtAddress(regTx, regTxHash, adminAddress,
                    BigInteger.valueOf(5_500_000L));
            if (regAdminChange != null) {
                long changeLovelace = regAdminChange.getAmount().stream()
                        .filter(a -> "lovelace".equals(a.getUnit()))
                        .map(Amount::getQuantity)
                        .findFirst().map(java.math.BigInteger::longValueExact).orElse(0L);
                log.info("chain[registerTransferLogic] building cert tx (admin change {} lovelace at {}:{})",
                        changeLovelace, regAdminChange.getTxHash(), regAdminChange.getOutputIndex());
                hybridUtxoSupplier.add(regAdminChange);
                TransactionContext<Void> certResult = buildRegisterTransferLogicTransaction(
                        progTokenPolicyId, adminAddress, protocolParams,
                        regAdminChange, /*skipAlreadyRegisteredCheck=*/ true);
                if (certResult.isSuccessful()) {
                    certCbor = certResult.unsignedCborTx();
                    Transaction certTx = Transaction.deserialize(HexUtil.decodeHexString(certCbor));
                    certTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                            .getTxHash(certTx.serialize());
                    log.info("chain[registerTransferLogic] built cert tx {} (chain length: 4)", certTxHash);
                } else {
                    log.warn("chain[registerTransferLogic] failed (chain will return 3 txs; "
                            + "user will need the runtime cert tx on first transfer): {}",
                            certResult.error());
                }
            } else {
                log.warn("chain[registerTransferLogic] skipped: no admin change output >= 5.5 ADA in registration tx (chain will return 3 txs)");
            }

            hybridUtxoSupplier.clear();

            return TransactionContext.ok(null, new ChainBuildResult(
                    genesisCbor, addPuCbor, regCbor, certCbor,
                    reg.getGlobalStatePolicyId(), progTokenPolicyId,
                    reg.getDenylistPolicyId(), reg.getPowerUsersPolicyId(),
                    genesisTxHash, addPuTxHash, regTxHash, certTxHash));
        } catch (Exception e) {
            log.error("security-token chain build failed", e);
            hybridUtxoSupplier.clear();
            return TransactionContext.typedError("chain build failed: " + e.getMessage());
        }
    }

    /** Walk {@code tx}'s outputs and return the first one at {@code targetAddr}
     *  whose lovelace amount is &gt; {@code minLovelace}, wrapped as a {@link Utxo}
     *  with inline datum preserved. Returns null if no match.
     *
     *  <p>Used by the chain orchestrator to extract funding + script-locked
     *  outputs from each preceding tx for the next one to consume. */
    private static Utxo findOutputAtAddress(Transaction tx, String txHash,
                                            String targetAddr, BigInteger minLovelace) {
        java.util.List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outputs =
                tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            com.bloxbean.cardano.client.transaction.spec.TransactionOutput out = outputs.get(i);
            if (!out.getAddress().equals(targetAddr)) continue;
            if (out.getValue().getCoin().compareTo(minLovelace) <= 0) continue;
            String inlineDatumHex = out.getInlineDatum() != null
                    ? out.getInlineDatum().serializeToHex() : null;
            return Utxo.builder()
                    .address(out.getAddress())
                    .txHash(txHash)
                    .outputIndex(i)
                    .amount(ValueUtil.toAmountList(out.getValue()))
                    .inlineDatum(inlineDatumHex)
                    .build();
        }
        return null;
    }

    /** Burn security tokens — the mirror image of {@link #buildMintTransaction}.
     *
     *  <h3>Tx shape</h3>
     *  <pre>
     *    Inputs:
     *      [0]  GS UTxO        (spent with GS spend redeemer, action MintSecurity)
     *      [1]  token UTxO     (the prog-token UTxO being drained — at
     *                           prog-logic-base, identified by request.utxoTxHash /
     *                           utxoOutputIndex)
     *      [2]  funding UTxO   (admin's ADA, fees + collateral)
     *
     *    Reference inputs:
     *      directory entry for the prog-token policy
     *      power-user node for the admin (mintingLogic checks can_burn)
     *      protocol params UTxO
     *      issuance params UTxO
     *
     *    Mints:
     *      -quantity security tokens under issuance contract (negative = burn)
     *
     *    Outputs:
     *      [0]  new GS UTxO at gsSpendAddress (mintable_amount INCREMENTED by
     *           quantity — burned tokens come back to the cap)
     *      [N]  change to admin (moved to end by preBalanceTx)
     *
     *    Withdrawals:
     *      withdraw-0 from mintingLogic stake credential — STRICT path with
     *      minted_amount = -quantity. The validator's
     *      {@code if minted_amount > 0 { can_mint } else { can_burn }} branch
     *      requires the power user to have {@code can_burn}.
     *  </pre>
     *
     *  <p>NB: this implementation assumes the token UTxO is spendable by the
     *  admin signing the tx (its stake credential is the admin's payment PKH
     *  per CIP-113 prog-logic-base). For tokens held by other wallets, an
     *  admin-seizure flow (third-party transfer) would be required —
     *  intentionally deferred until the upstream BaFin seizure validator is
     *  wired through.
     */
    @Override
    public TransactionContext<Void> buildBurnTransaction(
            BurnTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // ── 1. Resolve registration row ────────────────────────────────
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(request.tokenPolicyId());
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + request.tokenPolicyId());
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            // ── 2. Parse + validate burn quantity ──────────────────────────
            BigInteger burnQuantity;
            try {
                burnQuantity = new BigInteger(request.quantity());
            } catch (NumberFormatException nfe) {
                return TransactionContext.typedError("quantity must be a positive integer");
            }
            if (burnQuantity.signum() <= 0) {
                return TransactionContext.typedError("burn quantity must be > 0");
            }
            BigInteger mintFieldQuantity = burnQuantity.negate();

            // ── 3. Build scripts ───────────────────────────────────────────
            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId, reg.getPowerUsersPolicyId());
            PlutusScript transferLogicScript = scriptBuilder.buildTransferLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId);
            PlutusScript issuanceContract = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolParams, mintingLogicScript);
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    reg.getSecurityAssetNameHex(),
                    issuanceContract.getPolicyId(), reg.getGlobalStatePolicyId());
            Address gsSpendAddress = AddressProvider.getEntAddress(
                    gsSpendScript, network.getCardanoNetwork());
            Address mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork());
            Address transferLogicRewardAddress = AddressProvider.getRewardAddress(
                    transferLogicScript, network.getCardanoNetwork());

            // ── 4. Resolve UTxOs ───────────────────────────────────────────
            // Token UTxO being burned (caller-supplied)
            Optional<Utxo> tokenUtxoOpt = utxoProvider.findUtxo(
                    request.utxoTxHash(), request.utxoOutputIndex());
            if (tokenUtxoOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "token UTxO not found: " + request.utxoTxHash() + ":" + request.utxoOutputIndex());
            }
            Utxo tokenUtxo = tokenUtxoOpt.get();

            // GS UTxO — look up by EXACT (policy, asset_name); see comment on
            // the mint flow for why policy-only lookup is unreliable once the
            // CIP-113 directory has entries under this policy id.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("GS NFT not found on chain");
            }
            if (gsUtxo.getInlineDatum() == null || gsUtxo.getInlineDatum().isBlank()) {
                return TransactionContext.typedError("GS UTxO is missing its inline datum");
            }

            // Burner PU node. The BURNER is the connected wallet that signed the
            // request — not necessarily the original registrant admin. We derive
            // the burner's PKH from request.feePayerAddress() and look up their
            // power-user node. minting_logic.withdraw then enforces that this
            // PKH is a tx signer AND holds BURNER capability in the LL node.
            Address feePayer = new Address(request.feePayerAddress());
            byte[] burnerKeyHash = feePayer.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "feePayerAddress has no payment credential: " + request.feePayerAddress()));
            String burnerPkhHex = HexUtil.encodeHexString(burnerKeyHash);
            // Also need the burner's STAKE credential hash: the prog-token UTxO
            // we're spending is at prog-base + burner-stake, and the CIP-113
            // prog-logic-global validator's collect_input_assets requires the
            // input's stake VerificationKey to be in extra_signatories (via
            // has_or_fail — which crashes with EmptyList rather than failing
            // cleanly when missing).
            byte[] burnerStakeHash = feePayer.getDelegationCredentialHash().orElse(null);
            if (burnerStakeHash == null) {
                return TransactionContext.typedError(
                        "feePayerAddress must be a base address with a stake credential: "
                        + request.feePayerAddress());
            }
            byte[] burnerNodeAssetName = concat(LL_NODE_KEY_PREFIX, burnerKeyHash);
            String burnerNodeAssetNameHex = HexUtil.encodeHexString(burnerNodeAssetName);
            Utxo puNode = utxoProvider.findUtxoByAsset(
                    reg.getPowerUsersPolicyId(), burnerNodeAssetNameHex).orElse(null);
            if (puNode == null) {
                return TransactionContext.typedError(
                        "burner power-user node not found on chain — connected wallet "
                        + burnerPkhHex + " is not a registered power user (asset: "
                        + reg.getPowerUsersPolicyId() + "/" + burnerNodeAssetNameHex + ")");
            }

            // Directory entry
            PlutusScript directorySpendContract = protocolScriptBuilderService
                    .getParameterizedDirectorySpendScript(protocolParams);
            Address directorySpendAddress = AddressProvider.getEntAddress(
                    directorySpendContract, network.getCardanoNetwork());
            List<Utxo> registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
            Utxo directoryEntry = registryEntries.stream()
                    .filter(u -> registryNodeParser.parse(u.getInlineDatum())
                            .map(node -> request.tokenPolicyId().equals(node.key()))
                            .orElse(false))
                    .findAny().orElse(null);
            if (directoryEntry == null) {
                return TransactionContext.typedError(
                        "directory entry for policy " + request.tokenPolicyId() + " not found");
            }

            // Protocol params + issuance params
            String bootstrapTxHash = protocolParams.txHash();
            Optional<Utxo> protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            Optional<Utxo> issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (protocolParamsUtxoOpt.isEmpty() || issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol or issuance params UTxOs");
            }
            Utxo protocolParamsUtxo = protocolParamsUtxoOpt.get();
            Utxo issuanceUtxo = issuanceUtxoOpt.get();

            // Funding UTxO
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(
                    request.feePayerAddress(), 5_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError("no funding UTxO at fee-payer address");
            }
            Utxo funding = fundingUtxos.getFirst();

            // ── 5. Parse current GS datum + compute new mintable_amount ────
            PlutusData currentGsDatum = PlutusData.deserialize(
                    HexUtil.decodeHexString(gsUtxo.getInlineDatum()));
            if (!(currentGsDatum instanceof ConstrPlutusData currentConstr)) {
                return TransactionContext.typedError("GS datum is not a Constr");
            }
            List<PlutusData> gsFields = currentConstr.getData().getPlutusDataList();
            if (gsFields.size() < 9) {
                return TransactionContext.typedError(
                        "GS datum has " + gsFields.size() + " fields, expected 9");
            }
            long currentMintable;
            if (gsFields.get(1) instanceof BigIntPlutusData bi) {
                currentMintable = bi.getValue().longValueExact();
            } else {
                return TransactionContext.typedError(
                        "GS datum field 1 (mintable_amount) is not an Int");
            }
            // Burn increments mintable_amount: remaining_amount = old - minted_amount
            //                                                   = old - (-burnQty)
            //                                                   = old + burnQty
            long newMintable = currentMintable + burnQuantity.longValueExact();

            PlutusData newGsDatum = ConstrPlutusData.of(0,
                    gsFields.get(0),                                              // transfers_paused
                    BigIntPlutusData.of(BigInteger.valueOf(newMintable)),         // mintable_amount (incremented)
                    gsFields.get(2),                                              // admin_credential_hash
                    gsFields.get(3),                                              // power_user_linked_list_policy_id
                    gsFields.get(4),                                              // denylist_linked_list_policy_id
                    gsFields.get(5),                                              // security_info
                    gsFields.get(6),                                              // trusted_entity_vkeys
                    gsFields.get(7),                                              // member_root_hash
                    gsFields.get(8));                                             // requires_receiver_kyc

            // ── 6. Redeemer indices ────────────────────────────────────────
            // Inputs sorted lex by (txHash, outIdx): tokenUtxo, gsUtxo, funding.
            int gsInputIdx = lexIndex(List.of(gsUtxo, tokenUtxo, funding), gsUtxo);
            // Reference inputs MUST include the full set the tx actually has —
            // the on-chain validator reads ref inputs by index against the
            // lex-sorted FULL list, so omitting progBaseRef + progGlobalRef
            // here makes our directoryRefIdx + puNodeRefIdx point at the wrong
            // entries at eval time (causing EvaluationFailure in both the
            // issuance contract and mintingLogic.withdraw).
            TransactionInput progBaseRefInput = TransactionInput.builder()
                    .transactionId(protocolParams.programmableBaseRefInput().txHash())
                    .index(protocolParams.programmableBaseRefInput().outputIndex())
                    .build();
            TransactionInput progGlobalRefInput = TransactionInput.builder()
                    .transactionId(protocolParams.programmableGlobalRefInput().txHash())
                    .index(protocolParams.programmableGlobalRefInput().outputIndex())
                    .build();
            // Lex-sort by (txHash, outIdx) using Bloxbean's
            // TransactionInputComparator (matches Conway ledger ordering).
            List<TransactionInput> refInputsSorted = java.util.stream.Stream.of(
                    txInputOf(directoryEntry), txInputOf(puNode),
                    txInputOf(protocolParamsUtxo), txInputOf(issuanceUtxo),
                    progBaseRefInput, progGlobalRefInput
            ).sorted(new TransactionInputComparator()).toList();
            int directoryRefIdx = refInputsSorted.indexOf(txInputOf(directoryEntry));
            int puNodeRefIdx = refInputsSorted.indexOf(txInputOf(puNode));
            // Diagnostic: dump the resolved indices + the lex order so we can
            // verify against the actual on-chain ref-input order at eval time.
            log.info("security-token burn indices: gsInputIdx={} directoryRefIdx={} puNodeRefIdx={}",
                    gsInputIdx, directoryRefIdx, puNodeRefIdx);
            for (int i = 0; i < refInputsSorted.size(); i++) {
                TransactionInput ti = refInputsSorted.get(i);
                log.info("  refInputs[{}] = {}:{}", i, ti.getTransactionId(), ti.getIndex());
            }
            // Inspect what's actually at directoryEntry and puNode — addresses,
            // first asset, datum prefix. Verifies our lookups didn't pick the
            // wrong UTxO (which would cause the validator to parse the wrong
            // datum and fail with EmptyList during structural pattern matching).
            log.info("  directoryEntry (chain): {}:{} address={} datum(full)={}",
                    directoryEntry.getTxHash(), directoryEntry.getOutputIndex(),
                    directoryEntry.getAddress(),
                    directoryEntry.getInlineDatum());
            log.info("  directoryEntry.amount = {}", directoryEntry.getAmount());
            // Also dump the protocol params UTxO datum so we can see what
            // registry_node_cs prog-logic-global expects (this is what peek_first
            // on the directoryEntry value must match for the get_registry_node
            // expect to pass).
            try {
                var ppUtxoForLog = utxoProvider.findUtxo(bootstrapTxHash, 0).orElse(null);
                if (ppUtxoForLog != null) {
                    log.info("  protocolParamsUtxo.amount = {}", ppUtxoForLog.getAmount());
                    log.info("  protocolParamsUtxo.datum = {}", ppUtxoForLog.getInlineDatum());
                }
            } catch (Exception ignore) { /* logging best-effort */ }
            log.info("  puNode (chain): {}:{} address={} datum(full)={}",
                    puNode.getTxHash(), puNode.getOutputIndex(),
                    puNode.getAddress(),
                    puNode.getInlineDatum());
            // Also try to parse the directory datum so we can verify the
            // substandard hashes match what we computed.
            try {
                var nodeOpt = registryNodeParser.parse(directoryEntry.getInlineDatum());
                nodeOpt.ifPresent(n -> log.info(
                        "  parsed RegistryNode: key={} next={} transferLogic={} thirdParty(=mintingLogic?)={} gsPolicy={}",
                        n.key(), n.next(), n.transferLogicScript(),
                        n.thirdPartyTransferLogicScript(), n.globalStatePolicyId()));
            } catch (Exception parseEx) {
                log.warn("  failed to parse directoryEntry datum as RegistryNode: {}", parseEx.getMessage());
            }
            // Also dump the script-hashes we're computing, so we can verify
            // they match what's recorded in the directoryEntry's RegistryNode.
            log.info("  transferLogic.scriptHash={}", HexUtil.encodeHexString(transferLogicScript.getScriptHash()));
            log.info("  mintingLogic.scriptHash={}", HexUtil.encodeHexString(mintingLogicScript.getScriptHash()));
            log.info("  programmableLogicGlobal.scriptHash={}",
                    protocolParams.programmableLogicGlobalPrams().scriptHash());

            // Tx has: 2 Spends (gs + token), 1 Mint (issuance), 3 Rewards
            // (mintingLogic + transferLogic + programmableLogicGlobal). The
            // transferLogic withdraw is required by prog-logic-global for any
            // prog-token spend; for burns we rely on the mint/burn rubber-stamp
            // branch we added in transfer_logic_script.ak so the redeemer
            // contents don't matter. Redeemer ordering (Spend → Mint → Cert →
            // Reward); Mint sits at global index 2.
            int issuancePri = 2;

            // ── 7. Build redeemers ─────────────────────────────────────────
            PlutusData issuanceRedeemer = ConstrPlutusData.of(0,
                    ConstrPlutusData.of(1, BytesPlutusData.of(mintingLogicScript.getScriptHash())),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx))));

            // mintingLogic.withdraw STRICT path with negative minted_amount —
            // the validator's else-branch requires can_burn.
            PlutusData withdrawRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(gsInputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    BigIntPlutusData.of(mintFieldQuantity));

            int gsOutputIdx = 0;  // new GS at output 0 (change moved to end)
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(BigInteger.valueOf(gsOutputIdx)),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(issuancePri))));

            // Token UTxO spend redeemer — passthrough (Constr 0). The
            // prog-logic-base validator that runs against this input delegates
            // authorisation to prog-logic-global's withdraw-0 below.
            PlutusData tokenSpendRedeemer = ConstrPlutusData.of(0);

            // prog-logic-global validator: invoked via withdraw-0 to authorise
            // the prog-token spend. The script itself is supplied via reference
            // input (programmableGlobalRefInput) — we only need the reward
            // address here to attach the withdraw-0 to the tx. Its redeemer
            // points at the registry entry by reference-input index (Constr 0
            // wraps the variant; the inner single-element list mirrors
            // kyc-extended's pattern). prog-logic-base (spending validator for
            // the token UTxO) is similarly supplied via programmableBaseRefInput.
            PlutusScript programmableLogicGlobal = protocolScriptBuilderService
                    .getParameterizedProgrammableLogicGlobalScript(protocolParams);
            Address programmableLogicGlobalRewardAddress = AddressProvider.getRewardAddress(
                    programmableLogicGlobal, network.getCardanoNetwork());
            PlutusData programmableGlobalRedeemer = ConstrPlutusData.of(0,
                    ListPlutusData.of(ConstrPlutusData.of(0,
                            BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)))));

            // ── 8. GS output value (preserved verbatim from input) ─────────
            BigInteger gsLovelace = gsUtxo.getAmount().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .map(Amount::getQuantity)
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
            Amount gsNftAmount = gsUtxo.getAmount().stream()
                    .filter(a -> !"lovelace".equals(a.getUnit()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
            Value gsValue = Value.builder()
                    .coin(gsLovelace)
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(reg.getGlobalStatePolicyId())
                            .assets(List.of(Asset.builder()
                                    .name("0x" + AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                    .value(BigInteger.ONE).build()))
                            .build()))
                    .build();

            // ── 9. The burned asset (negative quantity in mint field) ──────
            Asset burnAsset = Asset.builder()
                    .name("0x" + request.assetName())
                    .value(mintFieldQuantity).build();

            // ── 10. Compose tx ─────────────────────────────────────────────
            // Script witnesses:
            //   spending → gsSpendScript        (attached — BaFin, parameterised)
            //   spending → programmableLogicBase (REFERENCED via programmableBaseRefInput
            //                                     — protocol script, published at bootstrap)
            //   minting  → issuanceContract     (attached — wrapped per-token)
            //   reward   → mintingLogic         (attached — BaFin, parameterised, can_burn)
            //   reward   → transferLogic        (attached — BaFin, parameterised, mint/burn
            //                                    rubber-stamp branch)
            //   reward   → programmableLogicGlobal (REFERENCED via programmableGlobalRefInput
            //                                       — protocol script, published at bootstrap)
            //
            // The protocol scripts must be referenced (not attached) — attaching
            // both inline pushes the tx well over the 16 KB size limit.
            //
            // progBaseRefInput + progGlobalRefInput declared above in step 6
            // (we need them for ref-input lex-sorting before computing indices).

            // (No transferLogic-stake-cred-registered precondition: burn no
            // longer uses transferLogic.withdraw — see comment block on the
            // Tx builder below for why prog-logic-global's check is satisfied
            // by mintingLogic instead.)

            // The transferLogic withdraw + script are intentionally OMITTED here.
            // prog-logic-global validates a prog-token spend via
            //   expect has_withdrawal(transfer_logic_script)
            // where transfer_logic_script is read from registry node field 3.
            // In our registry datum, field 3 stores mintingLogic's hash (the
            // BaFin substandard reuses CIP-113's "third_party_transfer_logic"
            // slot for mintingLogic — see RegistryNode.toPlutusData). Since
            // mintingLogic IS in our withdrawals, the protocol's check passes
            // without needing a separate transferLogic withdrawal. Removing it
            // also drops the ~5952-byte transferLogic script from the witness
            // set, keeping the burn tx under the 16 KB ledger limit.
            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(tokenUtxo, tokenSpendRedeemer)
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .withdraw(mintingLogicRewardAddress.getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .withdraw(programmableLogicGlobalRewardAddress.getAddress(), BigInteger.ZERO,
                            programmableGlobalRedeemer)
                    .mintAsset(issuanceContract, burnAsset, issuanceRedeemer)
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)                                                              // output 0
                    .readFrom(
                            TransactionInput.builder()
                                    .transactionId(directoryEntry.getTxHash())
                                    .index(directoryEntry.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(puNode.getTxHash())
                                    .index(puNode.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(protocolParamsUtxo.getTxHash())
                                    .index(protocolParamsUtxo.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(issuanceUtxo.getTxHash())
                                    .index(issuanceUtxo.getOutputIndex()).build(),
                            progBaseRefInput,
                            progGlobalRefInput)
                    .attachSpendingValidator(gsSpendScript)
                    .attachRewardValidator(mintingLogicScript)
                    .withChangeAddress(request.feePayerAddress());

            // ── 11. Build with burner signature + pinned collateral ────────
            // Required signers:
            //   - burnerKeyHash (PAYMENT) — needed by mintingLogic.withdraw's
            //     can_burn check (matches the PU node's credential_hash field).
            //   - burnerStakeHash (STAKE) — needed by prog-logic-global's
            //     collect_input_assets check on the prog-token UTxO's stake
            //     credential. Wallets sign for BOTH payment and stake when
            //     signing as themselves, so this just declares the requirement.
            String feePayerAddress = request.feePayerAddress();
            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(burnerKeyHash, burnerStakeHash)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Move the fee-payer change output to the END so the
                        // new GS at output 0 keeps its position.
                        List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outs =
                                txn.getBody().getOutputs();
                        if (!outs.isEmpty() && outs.getFirst().getAddress().equals(feePayerAddress)) {
                            com.bloxbean.cardano.client.transaction.spec.TransactionOutput first =
                                    outs.removeFirst();
                            outs.addLast(first);
                        }
                    })
                    .postBalanceTx((bctx, txn) -> {
                        // Same fee-too-small pattern we saw in transfer:
                        // Bloxbean's fee calc comes up short by a few KB
                        // lovelace (final body ends up slightly bigger due to
                        // redeemer-ExUnits/script-data-hash drift). Overpay by
                        // 10000 lovelace, compensate the change output, and
                        // bump total_collateral + shrink collateral_return in
                        // lockstep so the Conway invariants hold.
                        var body = txn.getBody();
                        BigInteger feePadding = BigInteger.valueOf(10_000L);
                        BigInteger oldFee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
                        body.setFee(oldFee.add(feePadding));
                        // Subtract from the largest fee-payer-addressed output (the change).
                        var outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (var o : outputs) {
                            if (!feePayerAddress.equals(o.getAddress())) continue;
                            BigInteger coin = o.getValue().getCoin();
                            if (coin.compareTo(largestChange) > 0) {
                                largestChange = coin;
                                changeOut = o;
                            }
                        }
                        if (changeOut != null && largestChange.compareTo(feePadding) > 0) {
                            changeOut.getValue().setCoin(largestChange.subtract(feePadding));
                        }
                        // Collateral keeps up with the bumped fee (Conway:
                        // total_collateral >= fee * collateralPercentage,
                        // typically 150% — 2x is safe).
                        if (body.getTotalCollateral() != null && body.getCollateralReturn() != null) {
                            BigInteger collateralBump = feePadding.multiply(BigInteger.valueOf(2));
                            body.setTotalCollateral(body.getTotalCollateral().add(collateralBump));
                            var ret = body.getCollateralReturn();
                            BigInteger newReturnCoin = ret.getValue().getCoin().subtract(collateralBump);
                            if (newReturnCoin.signum() > 0) {
                                ret.getValue().setCoin(newReturnCoin);
                            } else {
                                body.setCollateralReturn(null);
                            }
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token burn: policy={} qty={} (mintable_amount {} → {})",
                    request.tokenPolicyId(), burnQuantity, currentMintable, newMintable);
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token burn failed for policy={}", request.tokenPolicyId(), e);
            return TransactionContext.typedError("burn failed: " + e.getMessage());
        }
    }

    /** One-shot admin tx that registers {@code transfer_logic_script}'s stake
     *  credential on chain via a Conway RegCert. Must be called once after
     *  registration, before the first burn/transfer that withdraws against it.
     *
     *  <p>Structurally minimal — 1 funding input, 1 change output, 1 RegCert,
     *  1 Cert publish redeemer, 1 attached script — so wallets that struggle
     *  with the larger burn tx (e.g. Eternl's CSL-based signer choking on
     *  Conway tag-7 inside a multi-script body) handle this one cleanly.
     *
     *  <p>Idempotent: if the credential is already registered (per
     *  {@code stake_registration} table), returns a typed error so the
     *  frontend can skip silently. */
    public TransactionContext<Void> buildRegisterTransferLogicTransaction(
            String policyId, String feePayerAddress, ProtocolBootstrapParams protocolParams) {
        return buildRegisterTransferLogicTransaction(
                policyId, feePayerAddress, protocolParams, /*chainedFunding=*/ null,
                /*skipAlreadyRegisteredCheck=*/ false);
    }

    /** Chain-mode overload: when {@code chainedFunding} is non-null, the cert tx
     *  uses that UTxO as its funding input (instead of going through
     *  {@link AccountService#findAdaOnlyUtxo} which hits Blockfrost). Used by
     *  {@link #buildFullRegistrationChain} so the cert tx mempool-chains off
     *  the registration tx's admin change output. Set
     *  {@code skipAlreadyRegisteredCheck=true} to keep building during chain
     *  setup even though no on-chain cert exists yet — the chain orchestrator
     *  guarantees the cert won't collide. */
    public TransactionContext<Void> buildRegisterTransferLogicTransaction(
            String policyId, String feePayerAddress, ProtocolBootstrapParams protocolParams,
            Utxo chainedFunding, boolean skipAlreadyRegisteredCheck) {
        try {
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript transferLogicScript = scriptBuilder.buildTransferLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId);
            Address transferLogicRewardAddress = AddressProvider.getRewardAddress(
                    transferLogicScript, network.getCardanoNetwork());
            String transferLogicRewardAddrBech32 = transferLogicRewardAddress.getAddress();

            if (!skipAlreadyRegisteredCheck) {
                boolean alreadyRegistered = stakeRegistrationRepository
                        .findRegistrationsByStakeAddress(transferLogicRewardAddrBech32)
                        .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                        .orElse(false);
                if (alreadyRegistered) {
                    return TransactionContext.typedError(
                            "transferLogic stake credential already registered for policy " + policyId);
                }
            }

            // Need enough ADA in inputs to cover: tx fee (~0.2 ADA) + Conway
            // RegCert deposit (2 ADA) + min-UTxO for the change output (~1 ADA).
            List<Utxo> fundingUtxos;
            if (chainedFunding != null) {
                fundingUtxos = List.of(chainedFunding);
            } else {
                fundingUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, 5_000_000L);
            }
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError(
                        "no funding UTxO at fee-payer address (need at least 5 ADA: 2 for the cert deposit, ~0.2 for fees, the rest for min-UTxO change)");
            }
            Utxo funding = fundingUtxos.getFirst();

            Address feePayer = new Address(feePayerAddress);
            byte[] signerKeyHash = feePayer.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "feePayerAddress has no payment credential: " + feePayerAddress));

            // Tx body shape:
            //   - .from(feePayerAddress) — required: Tx.getFromAddress() only
            //     returns null silently when hasScriptIntents() is true (which
            //     requires a ScriptCollectFromIntent / ScriptMintingIntent /
            //     any intent with a redeemer). Our cert tx has none of those
            //     until preBalanceTx injects the publish redeemer, so
            //     getFromAddress() throws "No sender address" during _build
            //     unless we set .from() explicitly.
            //   - .collectFrom(fundingUtxos) — pins explicit inputs so the
            //     balancer doesn't try to re-resolve through the supplier
            //     (which would fail "Cannot resolve deposit" if the supplier's
            //     view of the wallet is empty).
            //   - .payToAddress(feePayerAddress, 3 ADA) — gives Bloxbean's
            //     deposit resolver an explicit output at the fee-payer's
            //     address to deduct the 2 ADA RegCert deposit from (the
            //     resolveAuto's findAnyOutput fallback).
            Tx tx = new Tx()
                    .from(feePayerAddress)
                    .collectFrom(fundingUtxos)
                    .payToAddress(feePayerAddress, java.util.List.of(Amount.ada(3)))
                    .attachCertificateValidator(transferLogicScript)
                    .registerStakeAddress(transferLogicRewardAddrBech32)
                    .withChangeAddress(feePayerAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(signerKeyHash)
                    .feePayer(feePayerAddress)
                    // depositPayer + collateralPayer explicitly set who pays the
                    // RegCert deposit and the script collateral. Without these
                    // Bloxbean falls back to Tx.getFromAddress() which throws
                    // "No sender address" when .from() isn't set on the Tx.
                    // Setting them lets us skip .from() (which interfered with
                    // the chain-mode collateral wiring) while still resolving
                    // both deposit and collateral.
                    .depositPayer(feePayerAddress)
                    .collateralPayer(feePayerAddress)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // (a) Conway swap: StakeRegistration (script cred) → RegCert
                        // + inject a Cert publish redeemer. Same pattern as the
                        // genesis tx, isolated here so it's the ONLY thing in
                        // the tx.
                        var certs = txn.getBody().getCerts();
                        if (certs != null) {
                            for (int i = 0; i < certs.size(); i++) {
                                if (!(certs.get(i)
                                        instanceof com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration sr)) continue;
                                var cred = sr.getStakeCredential();
                                if (cred.getType()
                                        != com.bloxbean.cardano.client.transaction.spec.cert.StakeCredType.SCRIPTHASH) continue;
                                certs.set(i, com.bloxbean.cardano.client.transaction.spec.cert.RegCert.builder()
                                        .stakeCredential(cred)
                                        .coin(BigInteger.valueOf(2_000_000L))
                                        .build());
                                var ws = txn.getWitnessSet();
                                if (ws.getRedeemers() == null) {
                                    ws.setRedeemers(new ArrayList<>());
                                }
                                var publishRedeemer = com.bloxbean.cardano.client.plutus.spec.Redeemer.builder()
                                        .tag(com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Cert)
                                        .data(com.bloxbean.cardano.client.plutus.spec.PlutusData.unit())
                                        .exUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                                                .mem(BigInteger.valueOf(1_000_000))
                                                .steps(BigInteger.valueOf(500_000_000))
                                                .build())
                                        .build();
                                publishRedeemer.setIndex(i);
                                ws.getRedeemers().add(publishRedeemer);
                            }
                        }
                        // (b) Inject the collateral INPUT only — the
                        // total_collateral + collateral_return need the final
                        // fee, which isn't computed until after balancing, so
                        // we set those in postBalanceTx below.
                        var body = txn.getBody();
                        if (body.getCollateral() == null || body.getCollateral().isEmpty()) {
                            body.setCollateral(new ArrayList<>(List.of(
                                    com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                                            .transactionId(funding.getTxHash())
                                            .index(funding.getOutputIndex())
                                            .build())));
                        }
                    })
                    .postBalanceTx((bctx, txn) -> {
                        // After Bloxbean's balancing pass we have the final fee.
                        // Bloxbean's collateral wiring is gated by
                        // containsScriptTx (false for our cert tx because the
                        // publish redeemer is injected in preBalanceTx), so it
                        // leaves total_collateral / collateral_return unset
                        // (or in a stale state from the gated path). Compute
                        // both ourselves now so they match the collateral
                        // input we added in preBalanceTx.
                        var body = txn.getBody();
                        if (body.getCollateral() == null || body.getCollateral().isEmpty()) return;
                        BigInteger fee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
                        // The collateral_return output + total_collateral field
                        // we're about to add inflate the tx body by ~70-80 CBOR
                        // bytes, which at the standard per-byte fee (~44
                        // lovelace) costs an extra ~3500 lovelace. Bloxbean
                        // computed the fee against the body WITHOUT these
                        // fields. Overpay by 10000 lovelace (0.01 ADA) — far
                        // cheaper than failing submission, and the surplus is
                        // burned which is acceptable for a one-shot setup tx.
                        BigInteger feePadding = BigInteger.valueOf(10_000L);
                        BigInteger newFee = fee.add(feePadding);
                        body.setFee(newFee);
                        // Conway requires total_collateral >= fee * collateralPercentage / 100
                        // (typically 150%). Use 200% of the (now bumped) fee
                        // to leave plenty of margin.
                        BigInteger totalCollateral = newFee.multiply(BigInteger.valueOf(2));
                        if (totalCollateral.signum() == 0) {
                            totalCollateral = BigInteger.valueOf(2_000_000L);
                        }
                        BigInteger fundingLovelace = funding.getAmount().stream()
                                .filter(a -> "lovelace".equals(a.getUnit()))
                                .map(Amount::getQuantity)
                                .findFirst().orElse(BigInteger.ZERO);
                        body.setTotalCollateral(totalCollateral);
                        // The change output also needs to drop by feePadding,
                        // otherwise the tx is over-balanced (in - out - fee != 0).
                        // Pick the largest output to the fee payer (the change),
                        // not the 3-ADA self-pay, to cover this.
                        var outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (var o : outputs) {
                            if (!feePayerAddress.equals(o.getAddress())) continue;
                            BigInteger coin = o.getValue().getCoin();
                            if (coin.compareTo(largestChange) > 0) {
                                largestChange = coin;
                                changeOut = o;
                            }
                        }
                        if (changeOut != null && largestChange.compareTo(feePadding) > 0) {
                            changeOut.getValue().setCoin(largestChange.subtract(feePadding));
                        }
                        BigInteger collateralReturnCoin = fundingLovelace.subtract(totalCollateral);
                        if (collateralReturnCoin.signum() > 0) {
                            body.setCollateralReturn(
                                    com.bloxbean.cardano.client.transaction.spec.TransactionOutput.builder()
                                            .address(feePayerAddress)
                                            .value(com.bloxbean.cardano.client.transaction.spec.Value.builder()
                                                    .coin(collateralReturnCoin)
                                                    .build())
                                            .build());
                        } else {
                            body.setCollateralReturn(null);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token register-transfer-logic: policy={} stake={}",
                    policyId, transferLogicRewardAddrBech32);
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token register-transfer-logic failed for policy={}", policyId, e);
            return TransactionContext.typedError(
                    "register-transfer-logic failed: " + e.getMessage());
        }
    }

    // ── Used by SecurityTokenRootSyncJob ─────────────────────────────────────

    /** Admin-signed {@code UpdateMemberRootHash} tx. Called by the autonomous root
     *  publisher when the local MPF root has diverged from the on-chain root.
     *
     *  <p>Spends the GS UTxO with redeemer {@code GlobalStateSpendRedeemer {
     *  config_ref_input_index, global_state_output_index, action = UpdateMemberRootHash
     *  { new_member_root_hash } }}. Action is {@code Constr 7} in the
     *  {@code GlobalStateSpendAction} enum.
     *
     *  <p>The continuing output preserves the NFT + value and only rotates
     *  {@code member_root_hash}; all other datum fields stay as they were on the
     *  input. Signed by the admin key (matched against the current
     *  {@code admin_credential_hash} field, NOT a compile-time parameter — admin
     *  is rotatable via {@code RotateAdmin}). */
    public TransactionContext<Void> buildUpdateMemberRootHashTransaction(
            String policyId,
            byte[] newRootHash,
            String adminAddress,
            String signerPkh,
            ProtocolBootstrapParams protocolParams) {
        try {
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId, reg.getPowerUsersPolicyId());
            PlutusScript issuanceContract = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolParams, mintingLogicScript);
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    reg.getSecurityAssetNameHex(),
                    issuanceContract.getPolicyId(), reg.getGlobalStatePolicyId());
            Address gsSpendAddress = AddressProvider.getEntAddress(
                    gsSpendScript, network.getCardanoNetwork());

            // Look up the GS NFT specifically by (policy, asset_name). The
            // policy-only helper picks the first UTxO under any asset of that
            // policy — but after a token gets registered in the CIP-113
            // directory, the directory node NFTs (under the gs policy id, with
            // node-key asset names) appear there too, and the lex-first one
            // might not be the GS UTxO. Asset-exact lookup avoids the wrong-
            // script attachment that causes RequiredRedeemersMismatch.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError(
                        "Global state UTxO not found on chain (asset: "
                        + reg.getGlobalStatePolicyId() + "/"
                        + SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX + ")");
            }
            if (gsUtxo.getInlineDatum() == null || gsUtxo.getInlineDatum().isBlank()) {
                return TransactionContext.typedError("GS UTxO is missing its inline datum");
            }

            // Parse current datum, replace only field 7 (member_root_hash).
            PlutusData currentDatum = PlutusData.deserialize(
                    HexUtil.decodeHexString(gsUtxo.getInlineDatum()));
            if (!(currentDatum instanceof ConstrPlutusData currentConstr)) {
                return TransactionContext.typedError("GS datum is not a Constr");
            }
            List<PlutusData> gsFields = currentConstr.getData().getPlutusDataList();
            if (gsFields.size() < 9) {
                return TransactionContext.typedError("GS datum is malformed (< 9 fields)");
            }
            PlutusData newGsDatum = ConstrPlutusData.of(0,
                    gsFields.get(0),                                              // transfers_paused
                    gsFields.get(1),                                              // mintable_amount
                    gsFields.get(2),                                              // admin_credential_hash
                    gsFields.get(3),                                              // power_user_linked_list_policy_id
                    gsFields.get(4),                                              // denylist_linked_list_policy_id
                    gsFields.get(5),                                              // security_info
                    gsFields.get(6),                                              // trusted_entity_vkeys
                    BytesPlutusData.of(newRootHash),                              // member_root_hash ← NEW
                    gsFields.get(8));                                             // requires_receiver_kyc

            // Funding UTxO from admin's wallet (backend AdminSigningKeyProvider).
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(adminAddress, 5_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError("no funding UTxO at admin address");
            }
            Utxo funding = fundingUtxos.getFirst();

            // GS spend redeemer: Constr 0 (config_ref_idx=0, gs_output_idx=0,
            // action = Constr 7 UpdateMemberRootHash(new_root_hash))
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),                          // config_ref_input_index
                    BigIntPlutusData.of(BigInteger.ZERO),                          // global_state_output_index
                    ConstrPlutusData.of(7, BytesPlutusData.of(newRootHash)));     // UpdateMemberRootHash

            // Preserve GS UTxO value verbatim (lovelace + the GS NFT).
            BigInteger gsLovelace = gsUtxo.getAmount().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .map(Amount::getQuantity)
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
            Amount gsNftAmount = gsUtxo.getAmount().stream()
                    .filter(a -> !"lovelace".equals(a.getUnit()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
            Value gsValue = Value.builder()
                    .coin(gsLovelace)
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(reg.getGlobalStatePolicyId())
                            .assets(List.of(Asset.builder()
                                    .name("0x" + AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                    .value(BigInteger.ONE).build()))
                            .build()))
                    .build();

            byte[] signerKeyHash = HexUtil.decodeHexString(signerPkh);

            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)                                                              // output 0
                    .attachSpendingValidator(gsSpendScript)
                    .withChangeAddress(adminAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(signerKeyHash)
                    .feePayer(adminAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Keep new GS at output 0 — move change to end.
                        List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outs =
                                txn.getBody().getOutputs();
                        if (!outs.isEmpty() && outs.getFirst().getAddress().equals(adminAddress)) {
                            com.bloxbean.cardano.client.transaction.spec.TransactionOutput first =
                                    outs.removeFirst();
                            outs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token UpdateMemberRootHash: policy={} new_root={}",
                    policyId, HexUtil.encodeHexString(newRootHash));
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token UpdateMemberRootHash failed for policy={}", policyId, e);
            return TransactionContext.typedError(
                    "UpdateMemberRootHash failed: " + e.getMessage());
        }
    }

    // ── Global state update chain ────────────────────────────────────────────
    //
    // Each GlobalStateSpendAction is one redeemer variant; the on-chain validator
    // for each enforces that ONLY the field(s) corresponding to that action may
    // differ between input and continuing-output datums. So changing N datum
    // fields requires N transactions, one per action, each preserving everything
    // except the field it owns.
    //
    // The orchestrator below builds those N txs as a mempool chain: tx[i+1]
    // spends tx[i]'s new GS UTxO output (output 0) instead of going back to
    // Blockfrost (which wouldn't see it yet). The frontend signs all N at once
    // via CIP-103 signTxs, then submits sequentially via /issue-token/submit-chain.

    /** One change to apply to the GS datum. Caller fills the field(s) relevant
     *  to {@code action}; the others should be null. */
    public record GsChangeSpec(
            String action,                          // "PauseTransfers" | "ModifySecurityInfo" | "AddTrustedEntity" |
                                                    // "RemoveTrustedEntity" | "UpdateTrustedEntity" |
                                                    // "SetRequiresReceiverKyc" | "UpdateMemberRootHash" | "RotateAdmin"
            Boolean transfersPaused,                // PauseTransfers
            String newSecurityInfoHex,              // ModifySecurityInfo (CBOR-encoded Data)
            String trustedVkeyHex,                  // AddTrustedEntity / RemoveTrustedEntity (32-byte hex)
            String trustedMetadataHex,              // AddTrustedEntity (CBOR-encoded Data)
            String trustedOldVkeyHex,               // UpdateTrustedEntity
            String trustedNewVkeyHex,               // UpdateTrustedEntity
            String trustedNewMetadataHex,           // UpdateTrustedEntity (CBOR-encoded Data)
            Boolean requiresReceiverKycEnabled,     // SetRequiresReceiverKyc
            String newMemberRootHashHex,            // UpdateMemberRootHash — if null, backend uses current local
            String newAdminCredentialHashHex        // RotateAdmin (28-byte hex)
    ) {}

    /** Build N chained admin-signed txs, one per change. tx[i+1] mempool-chains
     *  off tx[i]'s GS UTxO output. Returns the unsigned CBORs in order. */
    public TransactionContext<List<String>> buildGlobalStateUpdateChain(
            String policyId,
            List<GsChangeSpec> changes,
            String feePayerAddress,
            String signerPkh,
            ProtocolBootstrapParams protocolParams) {
        try {
            if (changes == null || changes.isEmpty()) {
                return new TransactionContext<>(null, List.of(), true, null);
            }
            Optional<SecurityTokenRegistrationEntity> regOpt =
                    registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();

            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getGlobalStatePolicyId(),
                    registryPolicyId, reg.getPowerUsersPolicyId());
            PlutusScript issuanceContract = protocolScriptBuilderService
                    .getParameterizedIssuanceMintScript(protocolParams, mintingLogicScript);
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    reg.getSecurityAssetNameHex(),
                    issuanceContract.getPolicyId(), reg.getGlobalStatePolicyId());
            Address gsSpendAddress = AddressProvider.getEntAddress(
                    gsSpendScript, network.getCardanoNetwork());

            // Seed from the on-chain GS UTxO + datum.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("Global state UTxO not found on chain");
            }
            if (gsUtxo.getInlineDatum() == null || gsUtxo.getInlineDatum().isBlank()) {
                return TransactionContext.typedError("GS UTxO is missing its inline datum");
            }
            PlutusData currentDatum = PlutusData.deserialize(
                    HexUtil.decodeHexString(gsUtxo.getInlineDatum()));

            // Funding from the admin's wallet — one UTxO is enough for the whole chain
            // because each tx feeds its change to the same admin address, and we pin
            // the funding chain via the prevTx's CHANGE output (output 1 after the GS
            // continuing output gets reordered to index 0 by preBalanceTx).
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, 10_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError("no funding UTxO at fee-payer address");
            }
            Utxo funding = fundingUtxos.getFirst();
            byte[] signerKeyHash = HexUtil.decodeHexString(signerPkh);

            List<String> unsignedCbors = new java.util.ArrayList<>();
            for (int i = 0; i < changes.size(); i++) {
                GsChangeSpec change = changes.get(i);
                ActionAndDatum ad = computeActionAndDatum(change, currentDatum, policyId);
                if (ad.error != null) {
                    return TransactionContext.typedError("change[" + i + "]: " + ad.error);
                }

                TransactionContext<Void> stepCtx = buildSingleGsUpdateTx(
                        reg, gsSpendScript, gsSpendAddress, gsUtxo, ad.actionRedeemer,
                        ad.newDatum, funding, feePayerAddress, signerKeyHash);
                if (!stepCtx.isSuccessful()) {
                    return TransactionContext.typedError(
                            "change[" + i + "] (" + change.action() + "): " + stepCtx.error());
                }
                unsignedCbors.add(stepCtx.unsignedCborTx());

                // Roll forward: next iteration's gsUtxo = THIS tx's output 0 (the
                // new GS) and next iteration's funding = THIS tx's change output.
                byte[] builtBytes = HexUtil.decodeHexString(stepCtx.unsignedCborTx());
                Transaction builtTx = Transaction.deserialize(builtBytes);
                String nextTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                        .getTxHash(builtBytes);
                var outs = builtTx.getBody().getOutputs();
                // After our preBalanceTx swap, GS is at output 0, change at output 1.
                gsUtxo = utxoFromOutput(nextTxHash, 0, outs.get(0));
                currentDatum = ad.newDatum;
                if (outs.size() > 1
                        && outs.get(1).getAddress().equals(feePayerAddress)) {
                    funding = utxoFromOutput(nextTxHash, 1, outs.get(1));
                }
            }
            return new TransactionContext<>(null, unsignedCbors, true, null);
        } catch (Exception e) {
            log.error("security-token GS update chain failed for policy={}", policyId, e);
            return TransactionContext.typedError("GS update chain failed: " + e.getMessage());
        }
    }

    private static final class ActionAndDatum {
        PlutusData actionRedeemer;
        PlutusData newDatum;
        String error;
    }

    /** Compute (action redeemer, new datum) for one change. The new datum mutates
     *  only the field the action owns; all other fields are preserved verbatim.
     *  Throws CborDeserializationException if a metadata/security-info hex payload
     *  fails to parse — the orchestrator catches and turns into a typed error. */
    private ActionAndDatum computeActionAndDatum(GsChangeSpec change, PlutusData currentDatum,
                                                 String policyId)
            throws com.bloxbean.cardano.client.exception.CborDeserializationException {
        ActionAndDatum out = new ActionAndDatum();
        if (!(currentDatum instanceof ConstrPlutusData currentConstr)) {
            out.error = "current GS datum is not a Constr";
            return out;
        }
        List<PlutusData> f = currentConstr.getData().getPlutusDataList();
        if (f.size() < 9) {
            out.error = "current GS datum is malformed (< 9 fields)";
            return out;
        }

        switch (change.action()) {
            case "PauseTransfers" -> {
                // Constr 1 (Bool transfers_paused, Int power_user_node_ref_input_index)
                // BaFin's PauseTransfers also references a PU node to authorise the
                // change. For admin-as-sole-PU setups, this is just the admin's PU
                // node — same lookup pattern as mint/burn. v1 simplification: pass
                // index 0 and rely on the admin signature gate (the validator also
                // checks must_be_signed_by_credential(admin_credential_hash)).
                if (change.transfersPaused() == null) {
                    out.error = "PauseTransfers requires transfersPaused"; return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(1,
                        boolToConstr(change.transfersPaused()),
                        BigIntPlutusData.of(BigInteger.ZERO));
                out.newDatum = ConstrPlutusData.of(0,
                        boolToConstr(change.transfersPaused()), f.get(1), f.get(2),
                        f.get(3), f.get(4), f.get(5), f.get(6), f.get(7), f.get(8));
            }
            case "ModifySecurityInfo" -> {
                if (change.newSecurityInfoHex() == null) {
                    out.error = "ModifySecurityInfo requires newSecurityInfoHex"; return out;
                }
                PlutusData newSecInfo = PlutusData.deserialize(
                        HexUtil.decodeHexString(change.newSecurityInfoHex()));
                out.actionRedeemer = ConstrPlutusData.of(2, newSecInfo);
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        newSecInfo, f.get(6), f.get(7), f.get(8));
            }
            case "AddTrustedEntity" -> {
                if (change.trustedVkeyHex() == null || change.trustedMetadataHex() == null) {
                    out.error = "AddTrustedEntity requires trustedVkeyHex + trustedMetadataHex"; return out;
                }
                PlutusData vkey = BytesPlutusData.of(HexUtil.decodeHexString(change.trustedVkeyHex()));
                PlutusData meta = PlutusData.deserialize(HexUtil.decodeHexString(change.trustedMetadataHex()));
                out.actionRedeemer = ConstrPlutusData.of(3, vkey, meta);
                // trusted_entity_vkeys is a Map (Pairs) — read existing, add the new
                // (vkey -> meta) pair. The validator may require sorted-by-vkey
                // order; we sort ascending.
                MapPlutusData oldMap = (MapPlutusData) f.get(6);
                MapPlutusData newMap = MapPlutusData.builder().build();
                java.util.SortedMap<String, PlutusData> sorted = new java.util.TreeMap<>();
                oldMap.getMap().forEach((k, v) ->
                        sorted.put(HexUtil.encodeHexString(((BytesPlutusData) k).getValue()), v));
                sorted.put(change.trustedVkeyHex().toLowerCase(), meta);
                sorted.forEach((kHex, v) -> newMap.put(
                        BytesPlutusData.of(HexUtil.decodeHexString(kHex)), v));
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        f.get(5), newMap, f.get(7), f.get(8));
            }
            case "RemoveTrustedEntity" -> {
                if (change.trustedVkeyHex() == null) {
                    out.error = "RemoveTrustedEntity requires trustedVkeyHex"; return out;
                }
                PlutusData vkey = BytesPlutusData.of(HexUtil.decodeHexString(change.trustedVkeyHex()));
                out.actionRedeemer = ConstrPlutusData.of(4, vkey);
                MapPlutusData oldMap = (MapPlutusData) f.get(6);
                MapPlutusData newMap = MapPlutusData.builder().build();
                oldMap.getMap().forEach((k, v) -> {
                    String kHex = HexUtil.encodeHexString(((BytesPlutusData) k).getValue());
                    if (!kHex.equalsIgnoreCase(change.trustedVkeyHex())) {
                        newMap.put(k, v);
                    }
                });
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        f.get(5), newMap, f.get(7), f.get(8));
            }
            case "UpdateTrustedEntity" -> {
                if (change.trustedOldVkeyHex() == null || change.trustedNewVkeyHex() == null
                        || change.trustedNewMetadataHex() == null) {
                    out.error = "UpdateTrustedEntity requires trustedOldVkeyHex + trustedNewVkeyHex + trustedNewMetadataHex";
                    return out;
                }
                PlutusData oldVkey = BytesPlutusData.of(HexUtil.decodeHexString(change.trustedOldVkeyHex()));
                PlutusData newVkey = BytesPlutusData.of(HexUtil.decodeHexString(change.trustedNewVkeyHex()));
                PlutusData newMeta = PlutusData.deserialize(HexUtil.decodeHexString(change.trustedNewMetadataHex()));
                out.actionRedeemer = ConstrPlutusData.of(5, oldVkey, newVkey, newMeta);
                MapPlutusData oldMap = (MapPlutusData) f.get(6);
                java.util.SortedMap<String, PlutusData> sorted = new java.util.TreeMap<>();
                oldMap.getMap().forEach((k, v) -> {
                    String kHex = HexUtil.encodeHexString(((BytesPlutusData) k).getValue());
                    if (!kHex.equalsIgnoreCase(change.trustedOldVkeyHex())) sorted.put(kHex, v);
                });
                sorted.put(change.trustedNewVkeyHex().toLowerCase(), newMeta);
                MapPlutusData newMap = MapPlutusData.builder().build();
                sorted.forEach((kHex, v) -> newMap.put(
                        BytesPlutusData.of(HexUtil.decodeHexString(kHex)), v));
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        f.get(5), newMap, f.get(7), f.get(8));
            }
            case "SetRequiresReceiverKyc" -> {
                if (change.requiresReceiverKycEnabled() == null) {
                    out.error = "SetRequiresReceiverKyc requires requiresReceiverKycEnabled"; return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(6,
                        boolToConstr(change.requiresReceiverKycEnabled()));
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        f.get(5), f.get(6), f.get(7),
                        boolToConstr(change.requiresReceiverKycEnabled()));
            }
            case "UpdateMemberRootHash" -> {
                // If newMemberRootHashHex is null, caller wants the current local root.
                byte[] root = change.newMemberRootHashHex() != null
                        ? HexUtil.decodeHexString(change.newMemberRootHashHex())
                        : allowlistService.currentRoot(policyId);
                out.actionRedeemer = ConstrPlutusData.of(7, BytesPlutusData.of(root));
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), f.get(2), f.get(3), f.get(4),
                        f.get(5), f.get(6), BytesPlutusData.of(root), f.get(8));
            }
            case "RotateAdmin" -> {
                if (change.newAdminCredentialHashHex() == null) {
                    out.error = "RotateAdmin requires newAdminCredentialHashHex"; return out;
                }
                PlutusData newAdmin = BytesPlutusData.of(
                        HexUtil.decodeHexString(change.newAdminCredentialHashHex()));
                out.actionRedeemer = ConstrPlutusData.of(8, newAdmin);
                out.newDatum = ConstrPlutusData.of(0,
                        f.get(0), f.get(1), newAdmin, f.get(3), f.get(4),
                        f.get(5), f.get(6), f.get(7), f.get(8));
            }
            default -> {
                out.error = "unknown action: " + change.action();
                return out;
            }
        }
        return out;
    }

    /** Build a single admin-signed GS spend tx. Continuing GS at output 0 by
     *  way of the preBalanceTx swap; change at output 1. */
    private TransactionContext<Void> buildSingleGsUpdateTx(
            SecurityTokenRegistrationEntity reg,
            PlutusScript gsSpendScript,
            Address gsSpendAddress,
            Utxo gsUtxo,
            PlutusData actionRedeemer,
            PlutusData newGsDatum,
            Utxo funding,
            String feePayerAddress,
            byte[] signerKeyHash) {
        try {
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),                          // config_ref_input_index
                    BigIntPlutusData.of(BigInteger.ZERO),                          // global_state_output_index
                    actionRedeemer);

            BigInteger gsLovelace = gsUtxo.getAmount().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .map(Amount::getQuantity)
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
            Amount gsNftAmount = gsUtxo.getAmount().stream()
                    .filter(a -> !"lovelace".equals(a.getUnit()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
            Value gsValue = Value.builder()
                    .coin(gsLovelace)
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(reg.getGlobalStatePolicyId())
                            .assets(List.of(Asset.builder()
                                    .name("0x" + AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                    .value(BigInteger.ONE).build()))
                            .build()))
                    .build();

            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)
                    .attachSpendingValidator(gsSpendScript)
                    .withChangeAddress(feePayerAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(signerKeyHash)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Keep new GS at output 0 — move change to end.
                        List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outs =
                                txn.getBody().getOutputs();
                        if (!outs.isEmpty() && outs.getFirst().getAddress().equals(feePayerAddress)) {
                            var first = outs.removeFirst();
                            outs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false)
                    .build();
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("buildSingleGsUpdateTx failed", e);
            return TransactionContext.typedError("build failed: " + e.getMessage());
        }
    }

    /** Synthesize a Utxo from a TransactionOutput we just built (so the NEXT tx
     *  in the chain can use it as input without going to Blockfrost). */
    private Utxo utxoFromOutput(String txHash, int idx,
                                com.bloxbean.cardano.client.transaction.spec.TransactionOutput out) {
        List<Amount> amounts = new java.util.ArrayList<>();
        amounts.add(Amount.builder().unit("lovelace")
                .quantity(out.getValue().getCoin()).build());
        if (out.getValue().getMultiAssets() != null) {
            for (MultiAsset ma : out.getValue().getMultiAssets()) {
                for (Asset a : ma.getAssets()) {
                    String hexName = a.getName().startsWith("0x")
                            ? a.getName().substring(2) : a.getName();
                    amounts.add(Amount.builder()
                            .unit(ma.getPolicyId() + hexName)
                            .quantity(a.getValue()).build());
                }
            }
        }
        Utxo.UtxoBuilder b = Utxo.builder()
                .txHash(txHash)
                .outputIndex(idx)
                .address(out.getAddress())
                .amount(amounts);
        if (out.getInlineDatum() != null) {
            try {
                b.inlineDatum(HexUtil.encodeHexString(out.getInlineDatum().serializeToBytes()));
            } catch (Exception ignore) { /* leave null */ }
        }
        return b.build();
    }

    private static PlutusData boolToConstr(boolean v) {
        return ConstrPlutusData.of(v ? 1 : 0);
    }

    /** Read the on-chain global-state datum for the given policy.
     *
     *  <p>Looks up the global-state policy id on the SecurityTokenRegistrationEntity
     *  for {@code policyId}, finds the UTxO holding the GS NFT via Blockfrost (no
     *  script-address derivation needed — the NFT is unique), and deserialises the
     *  9-field BaFin {@code GlobalStateDatum} from the inline datum.
     *
     *  <p>Used by the autonomous publisher's equality gate and by the admin UI's
     *  read-current-state view. */
    public Optional<GlobalStateData> readGlobalState(String policyId) {
        try {
            var regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) return Optional.empty();
            var globalStatePolicyId = regOpt.get().getGlobalStatePolicyId();

            // Look up by exact (policy, asset_name) — see mint/burn flow comments
            // for why the policy-only helper can return the wrong asset.
            var utxoOpt = utxoProvider.findUtxoByAsset(
                    globalStatePolicyId,
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX);
            if (utxoOpt.isEmpty()) return Optional.empty();
            var utxo = utxoOpt.get();

            var datumHex = utxo.getInlineDatum();
            if (datumHex == null || datumHex.isBlank()) return Optional.empty();

            var data = PlutusData.deserialize(HexUtil.decodeHexString(datumHex));
            if (!(data instanceof ConstrPlutusData constr)) return Optional.empty();
            var fields = constr.getData().getPlutusDataList();
            if (fields.size() < 9) return Optional.empty();

            // BaFin GlobalStateDatum field order — see
            // src/substandards/security-token/lib/types/global_state.ak.
            boolean transfersPaused = boolFromConstr(fields.get(0));
            long mintableAmount = intFrom(fields.get(1));
            String adminCredentialHash = bytesHexFrom(fields.get(2));
            // fields.get(3): power_user_linked_list_policy_id (not surfaced)
            // fields.get(4): denylist_linked_list_policy_id (not surfaced)
            String securityInfoHex = serializeHex(fields.get(5));
            List<String> trustedEntityVkeys = trustedEntitiesFrom(fields.get(6));
            String memberRootHash = bytesHexFrom(fields.get(7));
            boolean requiresReceiverKyc = boolFromConstr(fields.get(8));

            return Optional.of(new GlobalStateData(
                    policyId,
                    transfersPaused,
                    mintableAmount,
                    trustedEntityVkeys,
                    securityInfoHex,
                    memberRootHash,
                    requiresReceiverKyc,
                    adminCredentialHash));
        } catch (Exception e) {
            log.debug("readGlobalState({}) failed: {}", policyId, e.getMessage());
            return Optional.empty();
        }
    }

    // ── Datum decoding helpers ───────────────────────────────────────────────

    private static boolean boolFromConstr(PlutusData d) {
        // Aiken encodes Bool as Constr 0 (False) / Constr 1 (True).
        if (d instanceof ConstrPlutusData c) return c.getAlternative() == 1;
        return false;
    }

    private static long intFrom(PlutusData d) {
        if (d instanceof BigIntPlutusData b) return b.getValue().longValueExact();
        return 0L;
    }

    private static String bytesHexFrom(PlutusData d) {
        if (d instanceof BytesPlutusData b) return HexUtil.encodeHexString(b.getValue());
        return null;
    }

    /** {@code security_info: Data} is opaque — surface it as hex CBOR. */
    private static String serializeHex(PlutusData d) {
        try {
            return HexUtil.encodeHexString(d.serializeToBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code trusted_entity_vkeys: Pairs<ByteArray, TrustedEntityMetadata>} — surface
     *  just the vkey list (the per-entity metadata is opaque and not used by the
     *  off-chain code). The on-chain type is a sorted list of pairs; Plutus encodes
     *  {@code Pairs} as either {@code Map} or a list of {@code Constr 0 [k, v]}
     *  depending on the data producer — we handle both. */
    private static List<String> trustedEntitiesFrom(PlutusData d) {
        var out = new ArrayList<String>();
        if (d instanceof MapPlutusData m) {
            m.getMap().forEach((k, v) -> {
                if (k instanceof BytesPlutusData bk) out.add(HexUtil.encodeHexString(bk.getValue()));
            });
        } else if (d instanceof ListPlutusData l) {
            for (var entry : l.getPlutusDataList()) {
                if (entry instanceof ConstrPlutusData ec) {
                    var pair = ec.getData().getPlutusDataList();
                    if (!pair.isEmpty() && pair.get(0) instanceof BytesPlutusData bk) {
                        out.add(HexUtil.encodeHexString(bk.getValue()));
                    }
                }
            }
        }
        return out;
    }

    /** Read view of the on-chain global state datum surfaced to off-chain callers. */
    public record GlobalStateData(
            String policyId,
            boolean transfersPaused,
            long mintableAmount,
            java.util.List<String> trustedEntityVkeys,
            String securityInfoHex,
            String memberRootHash,
            boolean requiresReceiverKyc,
            String adminCredentialHash
    ) {}
}
