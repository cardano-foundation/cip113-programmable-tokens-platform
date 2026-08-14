package org.cardanofoundation.cip113.service.substandard;

import com.bloxbean.cardano.client.exception.CborDeserializationException;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.easy1staking.cardano.comparator.TransactionInputComparator;
import org.cardanofoundation.conversions.CardanoConverters;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
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
import org.cardanofoundation.cip113.service.HybridScriptSupplier;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** Handler for the "security-token" substandard.
 *
 *  <p>The on-chain validators live under {@code src/substandards/security-token/}
 *  and are a <em>verbatim, pinned</em> copy of
 *  {@code easy1staking-com/fn-bafin-cardano-sc} @ {@code e69c66a} — see
 *  {@code UPSTREAM_PIN.json} there and {@code SecurityTokenUpstreamPinTest}. That
 *  directory previously held a hand-maintained fork; nothing in it may be edited.
 *  This handler is deliberately isolated from {@code KycExtendedSubstandardHandler}
 *  and {@code MpfTreeService} so the older KYC substandards can be removed
 *  without breaking it.
 *
 *  <p><b>ABI at the current pin.</b> The pin moved off
 *  {@code FluidTokens/fn-bafin-cardano-sc} @ {@code 7ae4ce3} to pick up fixes for the
 *  three defects in {@code docs/UPSTREAM-BAFIN-DEFECTS.md}. What that changed here:
 *  <ul>
 *    <li>{@code GlobalStateDatum} is <b>12</b> fields: {@code deactivated} was
 *        inserted at index <b>1</b>, shifting {@code mintable_amount} to 2 and every
 *        later field up by one. All indices are named constants
 *        ({@code GS_IDX_*}) precisely because that shift is silent. A new
 *        {@code DeactivateContract} spend action sits at constructor 10.</li>
 *    <li>{@code MintingLogicScriptWithdrawRedeemer} is a <b>sum type</b>:
 *        {@code MintBurn} (constructor 0) for steady-state mint/burn, resolving the
 *        registry node from a REFERENCE input; {@code RegisterToken} (constructor 1)
 *        for CIP-113 registration, resolving it from an OUTPUT. The latter is what
 *        makes registration possible at all — it used to be rejected outright.</li>
 *    <li>All three withdraw redeemers gained a leading
 *        {@code registry_node_ref_input_index}. The node is now addressed by index
 *        instead of being searched for by policy, so every one of those indices must
 *        be computed against the LEX-SORTED reference-input list ({@code lexIndex}).</li>
 *    <li>Mint destinations are now vetted like transfer destinations: each unique
 *        destination stake credential needs a {@code MintingLogicDestinationAction}
 *        carrying a denylist-absence covering-node reference index. Mints therefore
 *        carry a denylist reference input they did not need before.</li>
 *    <li>Registry-node slot 4 holds the substandard's real
 *        {@code third_party_transfer_logic_validator} again. Burns consequently need
 *        a withdrawal from it (CIP-113's {@code ThirdPartyAct} demands one keyed on
 *        slot 4), which also means the burning power user needs
 *        {@code can_force_transfer} on top of {@code can_burn}.</li>
 *    <li>The sender-side KYC check in {@code transfer_logic_script.ak} is gated
 *        on {@code gs_datum.requires_receiver_kyc}, so a token with
 *        {@code requires_receiver_kyc = False} needs no sender Membership proof.</li>
 *  </ul> */
@Component
@Scope("prototype")
@RequiredArgsConstructor
@Slf4j
public class SecurityTokenSubstandardHandler
        implements SubstandardHandler,
                   BasicOperations<SecurityTokenRegisterRequest>,
                   org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable {

    private static final String SUBSTANDARD_ID = "security-token";

    /** Minimum remaining validity window for a transaction whose TTL is clamped to
     *  an allowlist membership expiry. Below this the transaction would expire
     *  before it could realistically be signed and submitted. Applies to both the
     *  mint and the burn — {@code verify_membership_proof} demands a Finite upper
     *  bound {@code <= proof.valid_until_ms} on either path. */
    private static final long MIN_KYC_TTL_MS = 120_000L;

    /** Default validity window for mint/burn transactions. */
    private static final long DEFAULT_TTL_MS = 15 * 60 * 1000L;

    /** Hex of an empty (32-byte) MPF root: 32 zero bytes. */
    static final String EMPTY_ROOT_HEX = "0000000000000000000000000000000000000000000000000000000000000000";

    /** Validity window granted to a member enrolled by the opt-in genesis allowlist
     *  seed (one year).
     *
     *  <p>It is deliberately finite. Every membership proof clamps its transaction's
     *  TTL to this expiry, so an unbounded grant would be an unbounded compliance
     *  claim; when it lapses the issuer has to re-enroll the member through the normal
     *  allowlist path, which is where a real KYC check belongs. */
    private static final long GENESIS_SEEDED_MEMBERSHIP_MS = 365L * 24 * 60 * 60 * 1000L;

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
    /** Twin of {@link #hybridUtxoSupplier} for reference SCRIPTS. A reference script
     *  lives in a transaction output and is resolved by hash through a
     *  {@code ScriptSupplier}; the backend cannot answer for the unsubmitted outputs
     *  this chain publishes, so the chain registers them here. */
    private final HybridScriptSupplier hybridScriptSupplier;
    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    private final CardanoConverters cardanoConverters;
    /** Used to size reference-script outputs with the ledger-exact min-UTxO formula
     *  rather than a guessed constant (see {@link #buildPublishScriptsTransaction}). */
    private final com.bloxbean.cardano.client.api.ProtocolParamsSupplier protocolParamsSupplier;

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

    /** Registration tx. Mints the security-token policy under prog-logic-base,
     *  registers it in the CIP-113 directory with the parameterised
     *  {@code transfer_logic_script.withdraw} hash, and persists the
     *  {@link SecurityTokenRegistrationEntity} row.
     *
     *  <p>This used to reject every request up front: at
     *  FluidTokens/fn-bafin-cardano-sc @7ae4ce3 the minting-logic withdraw
     *  resolved its registry node only from REFERENCE inputs, and a registration
     *  creates that node as an OUTPUT, so the withdraw could never succeed
     *  (docs/UPSTREAM-BAFIN-DEFECTS.md, defect C). The pin now names
     *  easy1staking-com/fn-bafin-cardano-sc @e69c66a, whose
     *  {@code MintingLogicScriptWithdrawRedeemer} is a sum type with a
     *  {@code RegisterToken} constructor that resolves the node from
     *  {@code self.outputs}. The guard is therefore gone and this path is live.
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
        return buildRegistrationTransaction(request, protocolParams, RegistrationChainInputs.NONE);
    }

    /** The UTxOs a chained registration cannot discover by querying the chain,
     *  because the transactions that create them are still unsubmitted when the
     *  registration tx is built by {@link #buildFullRegistrationChain}.
     *
     *  <p>{@code globalState} comes from the genesis tx; {@code powerUserNode} from
     *  the AddPowerUser tx; {@code denylistCoveringNode} (the denylist linked-list
     *  ROOT, which covers every key while the list is empty) from the genesis tx.
     *  Any field may be null, in which case the builder falls back to on-chain
     *  discovery — which is what the standalone (non-chained) registration path
     *  uses. */
    public record RegistrationChainInputs(Utxo globalState,
                                          Utxo powerUserNode,
                                          Utxo denylistCoveringNode,
                                          /** The {@code minting_logic} + {@code global_state}
                                           *  spend scripts published as reference scripts by
                                           *  the preceding publish tx. Null falls back to
                                           *  attaching both inline — which only fits when the
                                           *  registration does NOT mint. */
                                          PublishedRefScripts refScripts) {
        public static final RegistrationChainInputs NONE =
                new RegistrationChainInputs(null, null, null, null);
    }

    /** Chain-aware overload of {@link #buildRegistrationTransaction}.
     *
     *  <p>When {@code request.getQuantity()} is greater than zero the tx carries the
     *  token's FIRST MINT, which changes its shape substantially: GlobalState is
     *  SPENT under {@code MintSecurity} (so {@code mintable_amount} is decremented
     *  and the supply cap enforced) rather than merely referenced, a {@code can_mint}
     *  power-user node joins the reference inputs and must sign, and every
     *  token-bearing output needs a destination action carrying a denylist-absence
     *  covering node. See {@code verify_token_registration}'s
     *  {@code minted_amount > 0} branch in
     *  {@code validators/minting_logic_script.ak}, which defers to the same
     *  {@code verify_mint_or_burn} the steady-state mint path uses.
     *
     *  <p>With quantity 0 the tx is a STRUCTURAL registration: GlobalState is only
     *  referenced and none of the mint machinery runs. That is the default and the
     *  long-proven path.
     *
     *  <p>Pre-condition for the chained call: every supplied {@link
     *  RegistrationChainInputs} UTxO carries its inline datum, so the evaluator can
     *  see it. */
    public TransactionContext<RegistrationResult> buildRegistrationTransaction(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams,
            RegistrationChainInputs chained) {
        Utxo chainedGsUtxoOverride = chained != null ? chained.globalState() : null;
        try {
            // 0. Context + script construction. The genesis tx already wrote a
            // SecurityTokenRegistrationEntity row keyed on the prog-token policy
            // id and stored the (gs, pu, dl) policies + securityAssetNameHex +
            // bootstrap input. TokenOperationsService loaded all of that into
            // the SecurityTokenContext for us.
            if (context == null) {
                return TransactionContext.typedError("security-token context not set — genesis init must run first");
            }
            String gsPolicy = context.getGlobalStatePolicyId();
            String puPolicy = context.getPowerUsersPolicyId();
            String securityAssetNameHex = context.getSecurityAssetNameHex();
            String adminPkhHex = context.getIssuerAdminPkh();
            if (gsPolicy == null || puPolicy == null || securityAssetNameHex == null || adminPkhHex == null) {
                return TransactionContext.typedError(
                        "security-token context is incomplete — re-run the genesis init step (gsPolicy/puPolicy/assetName/adminPkh missing)");
            }
            Credential adminCredential = Credential.fromKey(adminPkhHex);

            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    securityAssetNameHex, gsPolicy, registryPolicyId, puPolicy);
            PlutusScript transferLogicScript = scriptBuilder.buildTransferLogicScript(
                    securityAssetNameHex, gsPolicy, registryPolicyId);
            PlutusScript issuanceContract = protocolScriptBuilderService.getParameterizedIssuanceMintScript(
                    protocolParams, mintingLogicScript);
            String progTokenPolicyId = issuanceContract.getPolicyId();
            Address mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork());

            // 1. Fee-payer UTxOs (same selection pattern as kyc-extended).
            List<Utxo> feePayerUtxos;
            if (request.getChainingTransactionCborHex() != null) {
                byte[] chainingTxBytes = HexUtil.decodeHexString(request.getChainingTransactionCborHex());
                String chainingTxHash = TransactionUtil.getTxHash(chainingTxBytes);
                Transaction chainingTx = Transaction.deserialize(chainingTxBytes);
                Utxo inputUtxo = null;
                List<TransactionOutput> outs = chainingTx.getBody().getOutputs();
                for (int i = 0; i < outs.size(); i++) {
                    TransactionOutput output = outs.get(i);
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
            String bootstrapTxHash = protocolParams.txHash();
            Optional<Utxo> protocolParamsUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 0);
            Optional<Utxo> issuanceUtxoOpt = utxoProvider.findUtxo(bootstrapTxHash, 2);
            if (protocolParamsUtxoOpt.isEmpty() || issuanceUtxoOpt.isEmpty()) {
                return TransactionContext.typedError("could not resolve protocol or issuance params UTxOs");
            }
            Utxo protocolParamsUtxo = protocolParamsUtxoOpt.get();
            Utxo issuanceUtxo = issuanceUtxoOpt.get();

            PlutusScript directorySpendScript = protocolScriptBuilderService.getParameterizedDirectorySpendScript(protocolParams);
            Address directorySpendAddress = AddressProvider.getEntAddress(directorySpendScript, network.getCardanoNetwork());
            PlutusScript directoryMintScript = protocolScriptBuilderService.getParameterizedDirectoryMintScript(protocolParams);
            String directoryMintPolicyId = directoryMintScript.getPolicyId();

            // 3. Linked-list slot lookup for the directory insert.
            List<Utxo> registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
            boolean nodeAlreadyPresent = linkedListService.nodeAlreadyPresent(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum()).map(RegistryNode::key));
            if (nodeAlreadyPresent) {
                return TransactionContext.typedError(
                        "policy " + progTokenPolicyId + " already registered in CIP-113 directory");
            }
            Optional<Utxo> nodeToReplaceOpt = linkedListService.findNodeToReplace(progTokenPolicyId, registryEntries,
                    utxo -> registryNodeParser.parse(utxo.getInlineDatum())
                            .map(node -> new LinkedListNode(node.key(), node.next())));
            if (nodeToReplaceOpt.isEmpty()) {
                return TransactionContext.typedError("could not find directory slot to insert into");
            }
            Utxo directoryUtxo = nodeToReplaceOpt.get();
            Optional<RegistryNode> existingNodeOpt = registryNodeParser.parse(directoryUtxo.getInlineDatum());
            if (existingNodeOpt.isEmpty()) {
                return TransactionContext.typedError("could not parse existing directory node datum");
            }
            RegistryNode existingNode = existingNodeOpt.get();

            // Locate the directory NFT carried by the slot UTxO so we can preserve it on the spend output.
            Optional<Amount> directorySpendAssetOpt = directoryUtxo.getAmount().stream()
                    .filter(a -> a.getQuantity().equals(BigInteger.ONE)
                            && directoryMintPolicyId.equals(AssetType.fromUnit(a.getUnit()).policyId()))
                    .findAny();
            if (directorySpendAssetOpt.isEmpty()) {
                return TransactionContext.typedError("directory slot UTxO has no directory NFT");
            }
            String directorySpendAssetName = AssetType.fromUnit(directorySpendAssetOpt.get().getUnit()).assetName();

            // 4. Redeemers.
            //
            // Issuance redeemer: in v0.4.0 issuance_mint's redeemer IS
            // types.MintingRegistryProof — the old SmartTokenMintingAction
            // { minting_logic_cred, minting_registry_proof } wrapper is gone (the credential
            // is the validator's compile-time parameter now). OutputIndex { index } =
            // Constr 1 [Int]: this is a fresh registration, with the new directory entry at
            // output index 2 (preserved-slot=output 1, new-slot=output 2 per kyc-extended
            // convention).
            ConstrPlutusData issuanceRedeemer = ConstrPlutusData.of(1, BigIntPlutusData.of(2));
            // types.RegistryInsert { key: ByteArray, minting_logic_script: Credential }.
            // v0.4.0: the 2nd field is a Credential, not a bare hash — Script(hash) is
            // Constr 1 [bytes].
            ConstrPlutusData directoryMintRedeemer = ConstrPlutusData.of(1,
                    BytesPlutusData.of(issuanceContract.getScriptHash()),
                    ConstrPlutusData.of(1, BytesPlutusData.of(mintingLogicScript.getScriptHash()))
            );

            // 5. New + updated directory datums (preserved slot links to new key,
            // new node links to whatever the preserved slot used to link to).
            RegistryNode directorySpendDatum = existingNode.toBuilder()
                    .next(HexUtil.encodeHexString(issuanceContract.getScriptHash()))
                    .build();
            // third_party_transfer_logic_script (index 4) = the substandard's OWN
            // third_party_transfer_logic_validator, as it should be.
            //
            // Commit 0ec401a put mintingLogic here instead, because at
            // FluidTokens/fn-bafin-cardano-sc @7ae4ce3 the third-party validator passed
            // `transfer_logic_script_registry_node_index` (= 3) to
            // derive_issuance_policy_id_from_registry_node and so could only ever
            // self-locate in a node whose field 3 was the third-party script — mutually
            // exclusive with working transfers, making the validator unusable
            // (docs/UPSTREAM-BAFIN-DEFECTS.md, defect A). At the current pin
            // (easy1staking-com @e69c66a) third_party_transfer_logic_script.ak:65 passes
            // `third_party_transfer_logic_script_registry_node_index` (= 4), so the
            // validator self-locates correctly and belongs in its own slot.
            //
            // Consequence for burns: CIP-113's ThirdPartyAct branch requires a withdrawal
            // keyed on whatever slot 4 names, so buildBurnTransaction must now withdraw
            // from thirdPartyTransferLogic (and that reward account must be registered) —
            // it can no longer free-ride on the mintingLogic withdrawal it needed anyway.
            //
            // unfrackingLogicScript (index 5): empty_vkey = unfracking FORBIDDEN — least
            // permission by default; security-token declares no unfracking hook validator.
            // The minting-logic RegisterToken branch ASSERTS this exact value
            // (minting_logic_script.ak:176-181), so it is not merely a default.
            PlutusScript thirdPartyTransferLogicScript = scriptBuilder.buildThirdPartyTransferLogicScript(
                    securityAssetNameHex, puPolicy, gsPolicy, registryPolicyId);
            RegistryNode directoryMintDatum = new RegistryNode(
                    HexUtil.encodeHexString(issuanceContract.getScriptHash()),
                    existingNode.next(),
                    Credential.fromScript(mintingLogicScript.getScriptHash()),
                    Credential.fromScript(transferLogicScript.getScriptHash()),
                    Credential.fromScript(thirdPartyTransferLogicScript.getScriptHash()),
                    RegistryNode.EMPTY_VKEY,
                    gsPolicy);

            Asset directorySpendNft = Asset.builder()
                    .name("0x" + directorySpendAssetName)
                    .value(BigInteger.ONE).build();
            Asset directoryMintNft = Asset.builder()
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
            // quantity == 0 makes registration "directory insert + stake cred
            // registration only"; the first actual mint is then a separate
            // MintSecurity tx run from the admin page. That is now the SIMPLE path:
            // minting_logic_script.ak's RegisterToken branch, when nothing is minted,
            // reads GlobalState from a REFERENCE input and requires neither a power
            // user nor destination actions nor a GS spend (validators/
            // minting_logic_script.ak:212-231).
            BigInteger mintQuantity;
            try {
                mintQuantity = new BigInteger(request.getQuantity());
            } catch (NumberFormatException nfe) {
                return TransactionContext.typedError("quantity must be a non-negative integer");
            }
            if (mintQuantity.signum() < 0) {
                return TransactionContext.typedError("quantity must be >= 0");
            }
            // REGISTRATION + FIRST MINT.
            //
            // `RegisterToken` with minted_amount > 0 layers the ENTIRE MintBurn gate on
            // top of the registration (minting_logic_script.ak:232-266 →
            // verify_mint_or_burn): GlobalState must be SPENT under MintSecurity, a
            // `can_mint` power-user node must be a reference input AND sign, and every
            // token-bearing output needs a destination action carrying a
            // denylist-absence covering node.
            //
            // This used to be refused up front on the grounds that "no power user exists
            // on chain yet at registration time". That reasoning was stale: in
            // buildFullRegistrationChain the AddPowerUser tx runs BEFORE the registration
            // tx, so the node exists — as an output of an unsubmitted transaction,
            // exactly like the chained GlobalState UTxO. Both are threaded in through
            // RegistrationChainInputs.
            boolean willMint = mintQuantity.signum() > 0;

            // The asset name is used TWICE and the two uses come from different places:
            // `security_asset_name` parameterises minting_logic_script from the
            // PERSISTED context, while the minted Asset below is named from the
            // REQUEST. verify_token_registration (minting_logic_script.ak:197-208)
            // hard-`expect`s the single asset name minted under the issuance policy to
            // equal its `security_asset_name` parameter — a raw `expect`, so a mismatch
            // is an untyped script trap with nothing for the operator to act on.
            //
            // On the chained path both values come from one request and cannot differ.
            // The public two-arg overload (dispatched from TokenOperationsService) is
            // the exposure: a client-supplied asset name meets a DB-loaded context, and
            // that path can now mint. Refuse off chain instead.
            if (willMint) {
                String requestedAssetNameHex = request.getAssetName() == null
                        ? "" : request.getAssetName().trim();
                if (!securityAssetNameHex.equalsIgnoreCase(requestedAssetNameHex)) {
                    return TransactionContext.typedError(
                            "asset name mismatch: this policy's minting logic is parameterised with "
                            + "security_asset_name=" + securityAssetNameHex
                            + " but the request asks to mint '" + requestedAssetNameHex
                            + "'. minting_logic_script.ak requires them to be equal, so the "
                            + "transaction would trap on chain. Mint "
                            + securityAssetNameHex + ", or register a separate token for '"
                            + requestedAssetNameHex + "'.");
                }
            }

            Asset programmableToken = Asset.builder()
                    .name("0x" + request.getAssetName())
                    .value(mintQuantity).build();
            Value programmableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(progTokenPolicyId)
                            .assets(List.of(programmableToken)).build()))
                    .build();

            String recipient = (request.getRecipientAddress() == null || request.getRecipientAddress().isBlank())
                    ? request.getFeePayerAddress()
                    : request.getRecipientAddress();
            Address recipientAddress = new Address(recipient);
            Address targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().orElseThrow(() ->
                            new IllegalArgumentException("recipient must be a base address (need stake credential)")),
                    network.getCardanoNetwork());
            // The destination identity the minting logic vets. Under the CIP-113 address
            // model the payment credential is the shared prog-logic-base script, so the
            // inline STAKE credential is the only owner identity there is
            // (minting_logic_script.ak:394-399).
            byte[] mintRecipientStakeHash = recipientAddress.getDelegationCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "recipient must be a base address with a stake credential: " + recipient));

            // 7. Compose the tx.
            //
            // Stake-credential registration for mintingLogic + transferLogic
            // is done in the genesis tx (first in the chain) — by the time
            // this registration tx runs, those credentials are mempool-
            // registered, so the withdraw-0 below succeeds without us needing
            // to register them again here.
            //
            // Output order. The issuance redeemer built above is OutputIndex{2},
            // so when we mint the layout must be:
            //   [0] prog-token mint to recipient
            //   [1] preserved directory entry (the spent slot, updated link)
            //   [2] NEW directory entry (the one being inserted)
            // Without a first mint output 0 disappears and everything shifts down:
            //   [0] preserved directory entry
            //   [1] NEW directory entry
            // The preBalanceTx hook below moves any leading fee-payer change output
            // to the end, so these indices stay stable.
            //
            // registry_node_output_index must name the NEW node, never the re-emitted
            // covering node — an insert emits both, and the validator's identity gate
            // (minting_logic_script.ak:163-169) traps if we point at the wrong one.
            int registryNodeOutputIdx = willMint ? 2 : 1;

            // GlobalState. Structural registration REFERENCES it; a registration
            // carrying a first mint must SPEND it (so MintSecurity decrements the cap).
            // A UTxO cannot be both, which is exactly why RegisterToken carries two
            // separate index fields.
            // Prefer a caller-supplied UTxO over a chain lookup, in BOTH branches. Inside
            // buildFullRegistrationChain the GlobalState NFT is minted by the genesis tx,
            // which is still unsubmitted when this tx is built — so it cannot be found by
            // querying the chain, whether we go on to spend it or merely reference it.
            // Gating the override on willMint made a chained structural registration
            // (quantity 0) fall through to the lookup and fail with "run the genesis init
            // step first" even though the orchestrator had passed the UTxO in.
            // AddPowerUser sits between genesis and registration but only readFrom()s the
            // GS UTxO, so the genesis output is still the live one here.
            Utxo gsUtxoForRegistration = chainedGsUtxoOverride != null
                    ? chainedGsUtxoOverride
                    : utxoProvider.findUtxoByAsset(gsPolicy,
                            SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX).orElse(null);
            if (gsUtxoForRegistration == null) {
                return TransactionContext.typedError(willMint
                        ? "registration with a first mint must spend the GlobalState UTxO — "
                          + "no chained GS UTxO was supplied (use buildFullRegistrationChain)"
                        : "GlobalState NFT not found on chain for policy " + gsPolicy
                          + " — run the genesis init step first");
            }

            // 6b. First-mint prerequisites. Everything below is inert on the structural
            // path; the fields it feeds are the ones RegisterToken ignores when
            // minted_amount == 0.
            Utxo mintPuNode = null;                 // can_mint power user, reference input + signer
            byte[] mintPuNodeKey = null;            // its linked-list node key (= what must sign)
            Utxo mintDenylistNode = null;           // denylist-absence covering element for the recipient
            PlutusData mintNewGsDatum = null;       // GS datum with mintable_amount decremented
            PlutusScript mintGsSpendScript = null;
            Long mintCurrentMintable = null;
            boolean mintNeedsRecipientProof = false;
            String mintMpfProofCborHex = null;
            Long mintMpfValidUntilMs = null;
            if (willMint) {
                // The genesis tx persisted this row; it carries the denylist / power-users
                // policy ids the fallback lookups need.
                SecurityTokenRegistrationEntity reg = registrationRepository
                        .findByProgrammableTokenPolicyId(progTokenPolicyId)
                        .orElse(null);

                // Power user. verify_mint_or_burn resolves the node from the reference
                // inputs, requires can_mint, and requires the NODE'S OWN KEY to sign
                // (minting_logic_script.ak:363-370) — so it has to be the caller's node,
                // never the genesis admin's by assumption (D6).
                byte[] callerPkh = callerPaymentCredential(request.getFeePayerAddress());
                mintPuNode = chained != null && chained.powerUserNode() != null
                        ? chained.powerUserNode()
                        : (reg != null ? findPuNode(reg, callerPkh, "caller") : null);
                if (mintPuNode == null) {
                    return TransactionContext.typedError(
                            "registration with a first mint needs a can_mint power-user node as a "
                            + "reference input, and none was supplied or found on chain for "
                            + HexUtil.encodeHexString(callerPkh)
                            + " — use buildFullRegistrationChain, which builds AddPowerUser "
                            + "before the registration tx");
                }
                mintPuNodeKey = linkedListNodeKey(mintPuNode, puPolicy);
                PowerUserCaps caps = parsePowerUser(mintPuNode);
                if (!caps.canMint()) {
                    return TransactionContext.typedError(
                            "power user " + HexUtil.encodeHexString(mintPuNodeKey)
                            + " lacks the can_mint capability that the on-chain minting logic "
                            + "requires (minting_logic_script.ak: power_user_data.can_mint). "
                            + "Register structurally (quantity 0) and mint from a wallet whose "
                            + "power-user node holds can_mint.");
                }

                // Denylist-absence covering element. With an empty list the ROOT covers
                // every key (lib/denylist/absence.ak: covers_key(None, None, _)), and at
                // registration time the list is always empty — but resolve it properly so
                // a standalone registration against a populated denylist still works.
                mintDenylistNode = chained != null && chained.denylistCoveringNode() != null
                        ? chained.denylistCoveringNode()
                        : (reg != null ? findDenylistCoveringNode(reg, mintRecipientStakeHash) : null);
                if (mintDenylistNode == null) {
                    return TransactionContext.typedError(
                            "registration with a first mint needs a denylist covering node as a "
                            + "reference input, and none was supplied or found on chain");
                }

                // GS: spent under MintSecurity, so mintable_amount is decremented and the
                // supply cap enforced by global_state.ak rather than by us.
                List<PlutusData> gsFields = parseGsFields(gsUtxoForRegistration);
                mintCurrentMintable = ((BigIntPlutusData) gsFields.get(GS_IDX_MINTABLE_AMOUNT))
                        .getValue().longValueExact();
                mintNewGsDatum = applyMintableDelta(gsFields, -mintQuantity.longValueExact());
                mintGsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                        securityAssetNameHex, progTokenPolicyId, gsPolicy);

                // Receiver-KYC gate, identical to the steady-state mint (D1): the proof is
                // only consulted when GS.requires_receiver_kyc AND the receiver is not the
                // authorising power user itself.
                boolean requiresReceiverKyc = boolFromConstr(gsFields.get(GS_IDX_REQUIRES_RECEIVER_KYC));
                boolean selfMintExempt = Arrays.equals(mintRecipientStakeHash, mintPuNodeKey);
                mintNeedsRecipientProof = requiresReceiverKyc && !selfMintExempt;
                if (mintNeedsRecipientProof) {
                    ResolvedMembership m = resolveMembershipProof(
                            progTokenPolicyId, mintRecipientStakeHash, gsFields,
                            "recipient", "registering with a first mint");
                    mintMpfProofCborHex = m.proofCborHex();
                    mintMpfValidUntilMs = m.validUntilMs();
                }
            }

            // 6c. Reference scripts. A registration that mints attaches five validators;
            // inline they are 16 584 bytes against a 16 384-byte max-tx-size, so the two
            // biggest MUST come from reference inputs or the transaction cannot exist.
            // buildFullRegistrationChain publishes them in the phase before this one.
            //
            // Only the scripts this transaction actually RUNS are referenced: minting_logic
            // is the reward validator on both paths, but the global_state spend exists only
            // on the mint path (a structural registration references GlobalState rather than
            // spending it). Referencing an unused script would buy a reference input and a
            // tiered ref-script fee for nothing.
            //
            // When nothing was published (the standalone, non-chained call) both lists stay
            // empty and every validator is attached inline exactly as before — which still
            // fits, because a standalone registration is structural.
            PublishedRefScripts publishedRefScripts = chained != null && chained.refScripts() != null
                    ? chained.refScripts()
                    : new PublishedRefScripts(null, null, null, null, null, null);
            List<Utxo> refScriptUtxosInUse = new ArrayList<>();
            List<PlutusScript> refScriptsInUse = new ArrayList<>();
            if (publishedRefScripts.mintingLogicRefUtxo() != null) {
                // Guard: the published script must BE the one this builder derived. Both are
                // parameterised by (asset name, GS policy, registry policy, PU policy) and
                // both derivations read from the same registration row, so a mismatch means
                // the two halves of the chain disagree about the token — a reference input
                // to the wrong script fails on chain as "script not found", with nothing in
                // the message pointing at the cause.
                if (!Arrays.equals(publishedRefScripts.mintingLogicScript().getScriptHash(),
                        mintingLogicScript.getScriptHash())) {
                    return TransactionContext.typedError(
                            "published minting_logic reference script "
                            + HexUtil.encodeHexString(publishedRefScripts.mintingLogicScript().getScriptHash())
                            + " does not match the one this registration needs ("
                            + HexUtil.encodeHexString(mintingLogicScript.getScriptHash()) + ")");
                }
                refScriptUtxosInUse.add(publishedRefScripts.mintingLogicRefUtxo());
                refScriptsInUse.add(publishedRefScripts.mintingLogicScript());
            }
            if (willMint && publishedRefScripts.globalStateSpendRefUtxo() != null) {
                if (!Arrays.equals(publishedRefScripts.globalStateSpendScript().getScriptHash(),
                        mintGsSpendScript.getScriptHash())) {
                    return TransactionContext.typedError(
                            "published global_state spend reference script "
                            + HexUtil.encodeHexString(publishedRefScripts.globalStateSpendScript().getScriptHash())
                            + " does not match the one this registration needs ("
                            + HexUtil.encodeHexString(mintGsSpendScript.getScriptHash()) + ")");
                }
                refScriptUtxosInUse.add(publishedRefScripts.globalStateSpendRefUtxo());
                refScriptsInUse.add(publishedRefScripts.globalStateSpendScript());
            }

            Tx tx = new Tx()
                    .collectFrom(feePayerUtxos)
                    .collectFrom(directoryUtxo, ConstrPlutusData.of(0))
                    .mintAsset(directoryMintScript, directoryMintNft, directoryMintRedeemer);

            // Redeemer indices for the mint half. Cardano lex-sorts inputs and
            // reference_inputs by (txHash, outputIndex) before any script sees them, so
            // off-chain indices MUST sort the same way.
            int gsInputIdx = 0;
            int puNodeRefIdx = 0;
            int denylistRefIdx = 0;
            int issuanceRedeemerIdx = 0;
            if (willMint) {
                List<Utxo> regInputs = new ArrayList<>(feePayerUtxos);
                regInputs.add(directoryUtxo);
                regInputs.add(gsUtxoForRegistration);
                gsInputIdx = lexIndex(regInputs, gsUtxoForRegistration);

                // self.redeemers is ordered by (tag, index) with Spend(0) < Mint(1) <
                // Cert(2) < Reward(3). This tx has exactly two script spends (the
                // directory slot and GS) — extra fee-payer inputs are pubkey inputs and
                // carry no redeemer — so the two Mint redeemers sit at 2 and 3, ordered by
                // policy id the way the ledger sorts the mint field.
                issuanceRedeemerIdx = 2
                        + (progTokenPolicyId.compareTo(directoryMintPolicyId) < 0 ? 0 : 1);

                tx = tx
                        .collectFrom(gsUtxoForRegistration, ConstrPlutusData.of(0,
                                BigIntPlutusData.of(BigInteger.ZERO),               // config_ref_input_index (unused)
                                BigIntPlutusData.of(BigInteger.valueOf(3)),         // global_state_output_index
                                ConstrPlutusData.of(0,                              // MintSecurity
                                        BigIntPlutusData.of(BigInteger.valueOf(issuanceRedeemerIdx)))))
                        .mintAsset(issuanceContract, programmableToken, issuanceRedeemer)
                        .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue),
                                ConstrPlutusData.of(0));   // output 0
            }

            tx = tx
                    .payToContract(directorySpendAddress.getAddress(), ValueUtil.toAmountList(directorySpendValue),
                            directorySpendDatum.toPlutusData())                            // preserved slot
                    .payToContract(directorySpendAddress.getAddress(), ValueUtil.toAmountList(directoryMintValue),
                            directoryMintDatum.toPlutusData());                            // NEW node

            if (willMint) {
                // Output 3: the re-emitted GS UTxO. Address and value must be preserved
                // byte-for-byte (global_state.ak's address_preserved / value_preserved),
                // and the datum must equal the input's with only mintable_amount changed.
                tx = tx.payToContract(gsUtxoForRegistration.getAddress(),
                        ValueUtil.toAmountList(buildPreservedGsValue(gsUtxoForRegistration, gsPolicy)),
                        mintNewGsDatum);
            }

            // Reference inputs. On the structural path GlobalState joins them; on the
            // mint path it is SPENT instead, and the power-user node plus the denylist
            // covering element take its place. The RegisterToken redeemer must name each
            // position in the LEX-SORTED list — the ledger sorts reference inputs by
            // output reference before the script ever sees them, so an unsorted off-chain
            // index points at the wrong UTxO.
            List<TransactionInput> regRefInputs = new ArrayList<>(List.of(
                    txInputOf(protocolParamsUtxo), txInputOf(issuanceUtxo)));
            if (willMint) {
                regRefInputs.add(txInputOf(mintPuNode));
                regRefInputs.add(txInputOf(mintDenylistNode));
            } else {
                regRefInputs.add(txInputOf(gsUtxoForRegistration));
            }
            // Reference-script inputs, when the chain published them. They carry no datum
            // and are never named by a redeemer, but they DO join the same lex-sorted list,
            // so they shift every index the redeemer computes below. That is why they are
            // added here, before the sort, rather than tacked on at readFrom() time.
            for (Utxo refScriptUtxo : refScriptUtxosInUse) {
                regRefInputs.add(txInputOf(refScriptUtxo));
            }
            List<TransactionInput> regRefInputsSorted = regRefInputs.stream()
                    .sorted(new TransactionInputComparator()).toList();
            int gsRefIdxForRegistration = willMint
                    ? 0
                    : regRefInputsSorted.indexOf(txInputOf(gsUtxoForRegistration));
            if (willMint) {
                puNodeRefIdx = regRefInputsSorted.indexOf(txInputOf(mintPuNode));
                denylistRefIdx = regRefInputsSorted.indexOf(txInputOf(mintDenylistNode));
            }

            // MintingLogicScriptWithdrawRedeemer::RegisterToken (constructor 1) —
            // the registration mode added at @e69c66a. It resolves this token's registry
            // node from self.OUTPUTS, which is the whole point: at registration the node
            // is being created, so the steady-state reference-input lookup cannot see it.
            //
            // On the structural path (minted_amount == 0) the validator reads ONLY
            // registry_node_output_index and global_state_ref_input_index; the remaining
            // three fields are never dereferenced, so 0 is safe and matches upstream's
            // own tests. Destination actions are ignored and stay empty. When a first
            // mint is carried the mirror image holds: global_state_ref_input_index is the
            // ignored one and the other three plus destination_actions are live.
            ListPlutusData destinationActions = ListPlutusData.of();
            if (willMint) {
                destinationActions = ListPlutusData.of(buildDestinationAction(
                        mintRecipientStakeHash, mintNeedsRecipientProof,
                        mintMpfProofCborHex, mintMpfValidUntilMs, denylistRefIdx));
            }
            ConstrPlutusData withdrawRedeemer = ConstrPlutusData.of(1,
                    BigIntPlutusData.of(BigInteger.valueOf(registryNodeOutputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdxForRegistration)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsInputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    BigIntPlutusData.of(willMint ? mintQuantity : BigInteger.ZERO),
                    destinationActions);

            tx = tx
                    .withdraw(mintingLogicRewardAddress.getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .readFrom(regRefInputsSorted.toArray(new TransactionInput[0]))
                    .attachSpendingValidator(directorySpendScript)
                    .attachRewardValidator(mintingLogicScript)
                    .withChangeAddress(request.getFeePayerAddress());
            if (willMint) {
                tx = tx.attachSpendingValidator(mintGsSpendScript);
            }

            // 7b. Signers. The registration itself is authorised by the admin credential
            // read off the GS datum; the mint half is additionally authorised by the
            // power-user node's OWN key (must_be_signed_by_credential against
            // power_user_node_key). Usually the same wallet, so dedupe.
            List<byte[]> requiredSigners = new ArrayList<>();
            requiredSigners.add(adminCredential.getBytes());
            if (willMint && !Arrays.equals(mintPuNodeKey, adminCredential.getBytes())) {
                requiredSigners.add(mintPuNodeKey);
            }

            Utxo firstUtxo = feePayerUtxos.getFirst();
            var txContext = quickTxBuilder.compose(tx)
                    .withRequiredSigners(requiredSigners.toArray(new byte[0][]))
                    .feePayer(request.getFeePayerAddress())
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtxo.getTxHash())
                            .index(firstUtxo.getOutputIndex()).build())
                    .preBalanceTx((bctx, txn) -> {
                        // Move the fee-payer change output (added by Bloxbean as
                        // the FIRST output) to the END so output indices 0..N
                        // remain stable for our redeemers.
                        List<TransactionOutput> outputs = txn.getBody().getOutputs();
                        if (!outputs.isEmpty()
                                && outputs.getFirst().getAddress().equals(request.getFeePayerAddress())) {
                            TransactionOutput first = outputs.removeFirst();
                            outputs.addLast(first);
                        }
                    })
                    .ignoreScriptCostEvaluationError(false);
            if (!refScriptsInUse.isEmpty()) {
                // The validators are still ATTACHED above, because attaching is how
                // cardano-client binds a redeemer to a script. This pair then moves them
                // out of the witness set.
                //
                // withReferenceScripts declares what the reference inputs carry, so the
                // Conway tiered ref-script fee is charged and the cost-model languages are
                // right, WITHOUT ReferenceScriptResolver having to fetch them — it could
                // not, the publishing transaction is unsubmitted.
                // removeDuplicateScriptWitnesses then drops exactly those hashes from the
                // witness set. It defaults to FALSE, so omitting it would leave both copies
                // in the transaction: over max-tx-size, and rejected by the ledger as
                // ExtraneousScriptWitnessesUTXOW.
                txContext = txContext
                        .withReferenceScripts(refScriptsInUse.toArray(new PlutusScript[0]))
                        .removeDuplicateScriptWitnesses(true);
            }
            if (willMint) {
                // verify_membership_proof (lib/kyc/verify.ak:137-142) needs a Finite upper
                // bound <= proof.valid_until_ms; an unbounded tx fails that clause no
                // matter how good the proof is. Harmless when no proof is verified.
                txContext = txContext.validTo(kycClampedTtlSlot(
                        mintNeedsRecipientProof ? mintMpfValidUntilMs : null,
                        mintRecipientStakeHash, "recipient", "registration mint"));
            }
            Transaction transaction = txContext.build();

            if (willMint) {
                String indexMismatch = verifyRegistrationMintIndices(
                        transaction, gsUtxoForRegistration, mintPuNode, mintDenylistNode,
                        gsInputIdx, puNodeRefIdx, denylistRefIdx, registryNodeOutputIdx,
                        progTokenPolicyId, issuanceRedeemerIdx, issuanceRedeemer);
                if (indexMismatch != null) {
                    return TransactionContext.typedError(
                            "registration-with-mint aborted: " + indexMismatch
                            + ". The balancer reshaped the transaction after the redeemer "
                            + "indices were computed, so submitting it would trap on chain.");
                }
                log.info("security-token registration carries a first mint: policy={} qty={} "
                         + "(mintable_amount {} → {})",
                        progTokenPolicyId, mintQuantity, mintCurrentMintable,
                        mintCurrentMintable - mintQuantity.longValueExact());
            }

            // 8. Persist the ProgrammableTokenRegistryEntity so the platform's
            // generic dispatcher knows this prog-token policy belongs to the
            // security-token substandard. (The SecurityTokenRegistrationEntity
            // was already written at genesis.)
            programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                    .policyId(progTokenPolicyId)
                    .substandardId(SUBSTANDARD_ID)
                    .assetName(request.getAssetName())
                    .build());

            return TransactionContext.ok(transaction.serializeToHex(),
                    new RegistrationResult(progTokenPolicyId));
        } catch (Exception e) {
            log.error("security-token registration failed", e);
            return TransactionContext.typedError("registration failed: " + e.getMessage());
        } finally {
            // The chaining branch above seeds the supplier with the fee-payer UTxO it
            // synthesised from the predecessor tx. That used to be released only on the
            // success path, so every early return (and the catch) leaked entries into a
            // process-wide scratchpad; the mint path added five more such returns.
            hybridUtxoSupplier.clear();
        }
    }

    /** Mint additional security tokens on a registered policy.
     *
     *  <h3>Tx shape (the BaFin {@code MintSecurity} flow)</h3>
     *  <pre>
     *    Inputs:
     *      [0]  GS UTxO       (spent, with GlobalStateSpendRedeemer.MintSecurity)
     *      [1]  funding UTxO  (the CALLER's ADA, for fees + collateral)
     *
     *    Reference inputs:
     *      directory entry for our prog-token policy
     *           (mintingLogic.withdraw uses this to derive issuance_policy_id;
     *            issuance contract uses it to find the registered substandard)
     *      power-user node for the CALLER
     *           (mintingLogic.withdraw checks can_mint=true AND that this
     *            node's own key signed — hence caller-keyed, not admin-keyed)
     *      protocol params UTxO  (CIP-113 reference)
     *      issuance params UTxO  (CIP-113 reference)
     *      denylist covering node for the recipient's stake credential
     *
     *    Mints:
     *      `quantity` security tokens under issuance contract
     *
     *    Outputs:
     *      [0]  minted prog tokens at recipient (under prog-logic-base stake cred)
     *      [1]  new GS UTxO at gsSpendAddress (mintable_amount decremented)
     *      [N]  change to the caller (moved to end by preBalanceTx)
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
     *
     *  <h3>Receiver KYC — supported proof variants</h3>
     *  When {@code requires_receiver_kyc} is set and the self-mint exemption does
     *  not apply, this builder emits the {@code Membership} variant of
     *  {@code KycProof} only, i.e. an MPF inclusion proof against the GS datum's
     *  {@code member_root_hash}. {@code verify_kyc_proof} also accepts
     *  {@code Attestation} (a TEL-signed 66-byte payload), but {@link MintTokenRequest}
     *  carries no field for one, so a deployment that gates receiver KYC on
     *  attestations rather than on the allowlist is not served by this path — it
     *  will be refused up front with the "member_root_hash is empty" precondition.
     *  Deliberate scope narrowing, matching the transfer path.
     */
    @Override
    public TransactionContext<Void> buildMintTransaction(
            MintTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // ── 1. Resolve registration row + validate quantity ────────────
            SecurityTokenRegistrationEntity reg = registrationRepository
                    .findByProgrammableTokenPolicyId(request.tokenPolicyId())
                    .orElseThrow(() -> new BuildPreconditionException(
                            "security-token registration not found for policy " + request.tokenPolicyId()));

            BigInteger mintQuantity;
            try {
                mintQuantity = new BigInteger(request.quantity());
            } catch (NumberFormatException nfe) {
                throw new BuildPreconditionException("quantity must be a positive integer");
            }
            if (mintQuantity.signum() <= 0) {
                throw new BuildPreconditionException(
                        "quantity must be > 0 (use buildBurnTransaction for negative mints)");
            }

            // ── 2. Resolve scripts + on-chain inputs via shared helpers ────
            //
            // D6: the power-user node MUST be the CALLER's, not the registration
            // row's genesis admin. On chain, minting_logic_script.ak:364 does
            // `must_be_signed_by_credential(self, power_user_node_key)` against the
            // node passed by reference index, so referencing a node whose key never
            // signs is unsatisfiable — which is exactly what happens after a
            // RotateAdmin (the DB row still holds the old PKH) and for any non-admin
            // power user who legitimately holds can_mint.
            MintLikeScripts s = buildMintLikeScripts(reg, protocolParams);
            byte[] callerPkh = callerPaymentCredential(request.feePayerAddress());
            Utxo gsUtxo = findGsUtxo(reg);
            Utxo puNode = findPuNode(reg, callerPkh, "caller");
            PowerUserCaps callerCaps = parsePowerUser(puNode);
            if (!callerCaps.canMint()) {
                throw new BuildPreconditionException(
                        "caller " + HexUtil.encodeHexString(callerPkh) + " has a power-user node but "
                        + "not the can_mint capability, which the on-chain minting logic requires "
                        + "(minting_logic_script.ak: power_user_data.can_mint). Granting it means "
                        + "the power-users validator's ModifyPowerUser action, which this platform "
                        + "does not yet build — the capability has to be set when the node is "
                        + "created. Mint from a wallet whose node already holds can_mint.");
            }
            Utxo directoryEntry = findDirectoryEntry(request.tokenPolicyId(), protocolParams);
            List<Utxo> protoIssue = findProtocolAndIssuanceUtxos(protocolParams);
            Utxo protocolParamsUtxo = protoIssue.get(0);
            Utxo issuanceUtxo = protoIssue.get(1);
            Utxo funding = findFunding(request.feePayerAddress(), 5_000_000L);

            // ── 3. Parse GS datum + apply mintable_amount delta ────────────
            List<PlutusData> gsFields = parseGsFields(gsUtxo);
            long currentMintable = ((BigIntPlutusData) gsFields.get(GS_IDX_MINTABLE_AMOUNT)).getValue().longValueExact();
            PlutusData newGsDatum = applyMintableDelta(gsFields, -mintQuantity.longValueExact());
            long newMintable = currentMintable - mintQuantity.longValueExact();

            // ── 3b. Resolve the recipient up front ─────────────────────────
            // Its STAKE credential is the destination identity the minting logic vets
            // (the payment credential is the shared prog-logic-base script, so the
            // inline stake credential is the only identity there is), and we need it
            // before step 4 to pick the denylist covering node.
            String recipient = (request.recipientAddress() == null || request.recipientAddress().isBlank())
                    ? request.feePayerAddress()
                    : request.recipientAddress();
            Address recipientAddress = new Address(recipient);
            byte[] mintRecipientStakeHash = recipientAddress.getDelegationCredentialHash()
                    .orElseThrow(() -> new BuildPreconditionException(
                            "recipient must be a base address with a stake credential: " + recipient));
            Address targetAddress = AddressProvider.getBaseAddress(
                    Credential.fromScript(protocolParams.programmableLogicBaseParams().scriptHash()),
                    recipientAddress.getDelegationCredential().orElseThrow(() ->
                            new IllegalArgumentException("recipient must be a base address (need stake credential)")),
                    network.getCardanoNetwork());

            // ── 3c. Receiver-KYC gate (D1) ─────────────────────────────────
            // minting_logic_script.ak:415-432, per unique destination stake credential:
            //
            //   if gs_datum.requires_receiver_kyc {
            //     or { dest_pkh == power_user_node_key,
            //          verify_kyc_proof(action.destination_proof, dest_pkh, …) }
            //   } else { True }
            //
            // dest_pkh is the destination's STAKE credential; power_user_node_key is
            // the linked-list node key of the power user authorising this mint (the
            // caller, resolved in step 2). Under the CIP-113 address model the two
            // are the same kind of thing — an owner identity — so the exemption is
            // reachable exactly when the caller mints to a prog-token address whose
            // stake credential IS their own power-user node key. For a power-user
            // node registered under a wallet's PAYMENT credential (which is what
            // buildAddPowerUserTransaction does) and an ordinary HD recipient, they
            // differ, so the KYC branch is the one that must work — hence the real
            // MPF proof below rather than the old hardcoded placeholder.
            boolean requiresReceiverKyc = boolFromConstr(gsFields.get(GS_IDX_REQUIRES_RECEIVER_KYC));
            boolean selfMintExempt = Arrays.equals(mintRecipientStakeHash, callerPkh);
            boolean needRecipientProof = requiresReceiverKyc && !selfMintExempt;

            String mintMpfProofCborHex = null;
            Long mintMpfValidUntilMs = null;
            if (needRecipientProof) {
                ResolvedMembership m = resolveMembershipProof(
                        request.tokenPolicyId(), mintRecipientStakeHash, gsFields,
                        "recipient", "minting");
                mintMpfProofCborHex = m.proofCborHex();
                mintMpfValidUntilMs = m.validUntilMs();
            }

            // ── 4. Compute redeemer indices ────────────────────────────────
            // Cardano lex-sorts inputs and reference_inputs by (txHash, outIdx)
            // at eval time; off-chain indices in our redeemers MUST match.
            int gsInputIdx = lexIndex(List.of(gsUtxo, funding), gsUtxo);
            // The denylist covering node proves each mint destination is NOT denylisted.
            // Added at @e69c66a: verify_mint_destinations now runs the same per-destination
            // gate the transfer path always had (minting_logic_script.ak:380-468), so a
            // mint that omits this reference input traps. With an empty denylist the ROOT
            // element covers every key (lib/denylist/absence.ak: covers_key(None, None, _)).
            Utxo denylistCoveringNode = findDenylistCoveringNode(reg, mintRecipientStakeHash);
            List<Utxo> refInputsSortable = List.of(directoryEntry, puNode,
                    protocolParamsUtxo, issuanceUtxo, denylistCoveringNode);
            int directoryRefIdx = lexIndex(refInputsSortable, directoryEntry);
            int puNodeRefIdx = lexIndex(refInputsSortable, puNode);
            int denylistRefIdx = lexIndex(refInputsSortable, denylistCoveringNode);
            // Tx has 1 Spend (GS), 1 Mint (issuance), 1 Reward (mintingLogic).
            // Redeemers sorted by tag (Spend → Mint → Cert → Reward), so the
            // issuance Mint sits at global redeemer index 1.
            int issuancePri = 1;

            // ── 5. Build redeemers ─────────────────────────────────────────
            // Issuance — "mint against existing directory entry" variant. In v0.4.0 the
            // redeemer IS types.MintingRegistryProof: Constr 0 [directoryRefIdx] =
            // RefInput { index }, telling the issuance contract to FIND the directory entry
            // as a ref input (vs Constr 1 = OutputIndex, which is the registration flow).
            PlutusData issuanceRedeemer =
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)));
            // mintingLogic.withdraw, MintBurn (constructor 0):
            //   { registry_node_ref_input_index, global_state_input_index,
            //     power_user_node_ref_input_index, minted_amount, destination_actions }
            //
            // Both the leading registry index and the trailing destination actions are
            // new at @e69c66a. One action per UNIQUE destination stake credential among
            // token-bearing outputs, in first-appearance order — this tx has exactly one
            // such output (the recipient), so exactly one action. The KYC proof inside it
            // is only evaluated when GS.requires_receiver_kyc is true AND the recipient
            // is not the authorising power user; `needRecipientProof` (step 3c) is
            // exactly that condition, and carries a real MPF membership proof when set.
            PlutusData mintDestinationAction = buildDestinationAction(
                    mintRecipientStakeHash, needRecipientProof,
                    mintMpfProofCborHex, mintMpfValidUntilMs, denylistRefIdx);
            PlutusData withdrawRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsInputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    BigIntPlutusData.of(mintQuantity),
                    ListPlutusData.of(mintDestinationAction));
            // GS spend MintSecurity action. config_ref_input_index unused here;
            // gs_output_index = 1 (after the prog-token output at 0);
            // issuance_policy_redeemer_index = position of issuance Mint redeemer.
            int gsOutputIdx = 1;
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(BigInteger.valueOf(gsOutputIdx)),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(issuancePri))));

            // ── 6. Outputs ─────────────────────────────────────────────────
            Asset programmableToken = Asset.builder()
                    .name("0x" + request.assetName())
                    .value(mintQuantity).build();
            Value programmableTokenValue = Value.builder()
                    .coin(Amount.ada(1).getQuantity())
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId(s.issuanceContract().getPolicyId())
                            .assets(List.of(programmableToken)).build()))
                    .build();

            // Recipient/targetAddress were resolved in step 3b (the denylist covering
            // node lookup needs the stake hash before the redeemer indices are computed).
            Value gsValue = buildPreservedGsValue(gsUtxo, reg.getGlobalStatePolicyId());

            // ── 7. Compose tx ──────────────────────────────────────────────
            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .withdraw(s.mintingLogicRewardAddress().getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .mintAsset(s.issuanceContract(), programmableToken, issuanceRedeemer)
                    .payToContract(targetAddress.getAddress(), ValueUtil.toAmountList(programmableTokenValue),
                            ConstrPlutusData.of(0))                                                 // output 0
                    .payToContract(s.gsSpendAddress().getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)                                                              // output 1
                    .readFrom(
                            txInputOf(directoryEntry),
                            txInputOf(puNode),
                            txInputOf(protocolParamsUtxo),
                            txInputOf(issuanceUtxo),
                            txInputOf(denylistCoveringNode))
                    .attachSpendingValidator(s.gsSpend())
                    .attachRewardValidator(s.mintingLogic())
                    .withChangeAddress(request.feePayerAddress());

            // Finite validity upper bound (D1). verify_membership_proof
            // (lib/kyc/verify.ak:137-142) returns False unless
            // validity_range.upper_bound is Finite AND <= proof.valid_until_ms — an
            // unbounded tx fails that clause no matter how good the proof is. Set a
            // TTL on every mint (harmless when no proof is verified) and clamp it to
            // the membership's expiry when one is.
            long ttlSlot = kycClampedTtlSlot(
                    needRecipientProof ? mintMpfValidUntilMs : null,
                    mintRecipientStakeHash, "recipient", "mint");

            String feePayerAddress = request.feePayerAddress();
            Transaction transaction = quickTxBuilder.compose(tx)
                    // D6: the signature the minting logic checks is the power-user
                    // NODE key, i.e. the caller's — not the registration row's
                    // genesis admin.
                    .withRequiredSigners(callerPkh)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .validTo(ttlSlot)
                    .preBalanceTx(moveLeadingChangeOutputToEnd(feePayerAddress))
                    .ignoreScriptCostEvaluationError(false)
                    .build();

            log.info("security-token mint: policy={} qty={} (mintable_amount {} → {})",
                    request.tokenPolicyId(), mintQuantity, currentMintable, newMintable);
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (BuildPreconditionException bpe) {
            return TransactionContext.typedError(bpe.getMessage());
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
     *  is not denylisted. Destination actions are identical. The KYC proof itself is
     *  only verified — on the sender side as well as the destination side — when the
     *  live GS datum's {@code requires_receiver_kyc} flag is true; the denylist-absence
     *  check is unconditional on both sides. */
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
            boolean isSelfSend = Arrays.equals(senderStakeHash, recipientStakeHash);

            // BOTH the sender and the receiver proof requirements are decided by
            // the LIVE GS datum's requires_receiver_kyc field, not by
            // SecurityTokenRegistrationEntity (the DB cache is set at registration
            // time and isn't refreshed when the admin runs SetRequiresReceiverKyc
            // on-chain). Presence checks for senderMpfProofCborHex/mpfProofCborHex
            // are therefore deferred until after the GS UTxO is fetched and its
            // datum parsed (further below).

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
            List<Utxo> tokenInputs = new ArrayList<>();
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

            // GS UTxO (ref input) — also the source of the live
            // requires_receiver_kyc flag (DB cache may be stale if the admin
            // has run SetRequiresReceiverKyc since registration).
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("GS NFT not found on chain");
            }
            boolean liveRequiresReceiverKyc;
            boolean liveRequiresSenderKyc;
            try {
                PlutusData gsDatum = PlutusData.deserialize(
                        HexUtil.decodeHexString(gsUtxo.getInlineDatum()));
                if (!(gsDatum instanceof ConstrPlutusData gsConstr)) {
                    return TransactionContext.typedError("GS datum is not a Constr");
                }
                List<PlutusData> gsFields = gsConstr.getData().getPlutusDataList();
                if (gsFields.size() != GS_DATUM_FIELD_COUNT) {
                    return TransactionContext.typedError("GS datum has " + gsFields.size()
                            + " fields, expected " + GS_DATUM_FIELD_COUNT
                            + " (pre-@7ae4ce3 global state — must be re-bootstrapped)");
                }
                // `requires_receiver_kyc: Bool` (Constr 0 = False, Constr 1 = True)
                // sits at index 9 in the upstream datum — index 8 is
                // requires_sender_kyc, so reading 8 here would silently gate on
                // the wrong flag.
                liveRequiresReceiverKyc = boolFromConstr(gsFields.get(GS_IDX_REQUIRES_RECEIVER_KYC));
                liveRequiresSenderKyc = boolFromConstr(gsFields.get(GS_IDX_REQUIRES_SENDER_KYC));
            } catch (Exception e) {
                return TransactionContext.typedError(
                        "could not parse GS datum to read requires_receiver_kyc: " + e.getMessage());
            }
            boolean needRecipientProof = liveRequiresReceiverKyc && !isSelfSend;
            if (needRecipientProof
                    && (request.mpfProofCborHex() == null || request.mpfProofCborHex().isBlank()
                        || request.mpfValidUntilMs() == null)) {
                return TransactionContext.typedError(
                        "recipient Membership proof required: mpfProofCborHex + mpfValidUntilMs "
                        + "(token currently has requires_receiver_kyc=true on chain)");
            }

            // Sender proof requirement. The two gates are INDEPENDENT in the pinned
            // contract: transfer_logic_script.ak:123 reads requires_sender_kyc for the
            // per-sender loop, and :157 reads requires_receiver_kyc for the per-destination
            // loop. Gating the sender on the receiver flag (as this did) is the F-20 bug the
            // re-pin to @e69c66a fixed on chain — off-chain it made a token with
            // sender-KYC off but receiver-KYC on demand a sender proof the chain never asks
            // for, which no sender can produce when the member root is empty.
            //
            // Only the Membership shape is supported in v1; Attestation-style sender
            // proofs would need the KERI service to produce BaFin 66-byte payloads,
            // which is a separate workstream.
            boolean needSenderProof = liveRequiresSenderKyc;
            if (needSenderProof
                    && (request.senderMpfProofCborHex() == null || request.senderMpfProofCborHex().isBlank()
                        || request.senderMpfValidUntilMs() == null)) {
                return TransactionContext.typedError(
                        "security-token sender Membership proof required: senderMpfProofCborHex "
                        + "+ senderMpfValidUntilMs (token currently has requires_sender_kyc=true on chain)");
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
            // Sender proof: Constr 1 = Membership { pkh, valid_until_ms, mpf_proof }.
            // When requires_receiver_kyc is false the validator never inspects it,
            // so emit the same placeholder shape the destination side uses.
            PlutusData senderMembershipProof = buildMembershipProof(
                    senderStakeHash, needSenderProof,
                    request.senderMpfProofCborHex(), request.senderMpfValidUntilMs());

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

            // TransferLogicScriptWithdrawRedeemer { registry_node_ref_input_index,
            //   global_state_ref_input_index, actions_for_each_input, destination_actions }.
            //
            // registry_node_ref_input_index was ADDED at @e69c66a (defect B). The
            // validator no longer searches reference inputs for "some UTxO holding a
            // registry NFT" — every programmable token's node carries the same registry
            // policy, so that search returned whichever node sorted first and then
            // trapped on the identity assertion. It now addresses the node BY INDEX, so
            // this must be our directory entry's position in the LEX-SORTED reference
            // input list (which is what directoryRefIdx already is).
            PlutusData transferRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)),
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

            // TTL bounded by whichever membership-proof validity windows actually
            // apply. When requires_receiver_kyc is false neither proof is verified
            // and neither may even exist (empty member_root_hash, no enrolled
            // members), so the plain 15-minute window stands — reading
            // senderMpfValidUntilMs unconditionally here would NPE on exactly the
            // transfers the relaxed precondition above now allows through.
            long now = System.currentTimeMillis();
            long ttlMs = now + 15 * 60 * 1000L;
            if (needSenderProof && request.senderMpfValidUntilMs() != null) {
                ttlMs = Math.min(ttlMs, request.senderMpfValidUntilMs());
            }
            if (needRecipientProof && request.mpfValidUntilMs() != null) {
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
                        TransactionBody body = txn.getBody();
                        BigInteger feePadding = BigInteger.valueOf(10_000L);
                        BigInteger oldFee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
                        BigInteger newFee = oldFee.add(feePadding);
                        body.setFee(newFee);
                        // Subtract from the largest fee-payer-addressed output
                        // (the change). Without this the tx would be
                        // over-balanced (in - out - fee != 0) → ValueNotConservedUTxO.
                        List<TransactionOutput> outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (TransactionOutput o : outputs) {
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
                            TransactionOutput ret = body.getCollateralReturn();
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
            throws CborDeserializationException {
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
            throws CborDeserializationException {
        return ConstrPlutusData.of(0,
                buildMembershipProof(destStakeHash, includeKycProof, mpfProofCborHex, mpfValidUntilMs),
                BigIntPlutusData.of(BigInteger.valueOf(denylistCoveringRefIdx)));
    }

    /** Build a {@code KycProof.Membership} — {@code Constr 1 (Constr 0 [pkh,
     *  valid_until_ms, mpf_proof])} per {@code lib/types/kyc_proof.ak}.
     *
     *  <p>When {@code includeKycProof} is false (or the caller supplied no proof)
     *  a placeholder with {@code valid_until_ms = 0} and an empty proof list is
     *  emitted. Upstream's transfer validator short-circuits
     *  {@code verify_kyc_proof} on BOTH the sender and the destination loop when
     *  {@code gs_datum.requires_receiver_kyc} is false, so the contents are never
     *  inspected — but the Membership <em>shape</em> still has to be there for the
     *  redeemer to deserialise. */
    private static PlutusData buildMembershipProof(byte[] stakeHash,
                                                   boolean includeKycProof,
                                                   String mpfProofCborHex,
                                                   Long mpfValidUntilMs)
            throws CborDeserializationException {
        if (includeKycProof && mpfProofCborHex != null && !mpfProofCborHex.isBlank()
                && mpfValidUntilMs != null) {
            return ConstrPlutusData.of(1,
                    ConstrPlutusData.of(0,
                            BytesPlutusData.of(stakeHash),
                            BigIntPlutusData.of(BigInteger.valueOf(mpfValidUntilMs)),
                            decodeMpfProof(mpfProofCborHex)));
        }
        return ConstrPlutusData.of(1,
                ConstrPlutusData.of(0,
                        BytesPlutusData.of(stakeHash),
                        BigIntPlutusData.of(BigInteger.ZERO),
                        ListPlutusData.of()));
    }

    /** A resolved MPF inclusion proof: the CBOR the redeemer carries plus the
     *  membership expiry the transaction's TTL must respect. */
    private record ResolvedMembership(String proofCborHex, long validUntilMs) {}

    /** Resolve a real MPF inclusion proof for {@code subjectStakeHash} against the
     *  GS datum's {@code member_root_hash}, or refuse up front with a message that
     *  names the operator action which fixes it.
     *
     *  <p>Shared by the mint ({@code minting_logic_script}'s
     *  {@code verify_mint_destinations}) and the burn ({@code
     *  third_party_transfer_logic_script}'s per-destination loop). Both call the
     *  same {@code verify_kyc_proof} → {@code verify_membership_proof} pair, so
     *  both need the same three preconditions to hold: a non-empty on-chain root,
     *  a live leaf for the subject, and local/on-chain root agreement. Failing
     *  here rather than inside the evaluator is the whole point — a trapped
     *  script gives the operator nothing to act on.
     *
     *  @param subjectLabel  what the subject is to the caller ("recipient" / "burn destination")
     *  @param verb          gerund naming the operation, for the error copy ("minting" / "burning") */
    private ResolvedMembership resolveMembershipProof(String policyId, byte[] subjectStakeHash,
                                                      List<PlutusData> gsFields,
                                                      String subjectLabel, String verb) {
        byte[] onchainRoot = gsFields.get(GS_IDX_MEMBER_ROOT_HASH) instanceof BytesPlutusData rootBytes
                ? rootBytes.getValue() : new byte[0];
        if (onchainRoot.length == 0) {
            throw new BuildPreconditionException(
                    "token has requires_receiver_kyc=true but its on-chain member_root_hash is empty, "
                    + "so no membership proof can verify. Enroll the " + subjectLabel
                    + " in the allowlist and publish the member root (UpdateMemberRootHash) before "
                    + verb + ".");
        }
        long now = System.currentTimeMillis();
        SecurityTokenAllowlistService.MpfLeafView leaf = allowlistService
                .inclusionProof(policyId, subjectStakeHash, now)
                .orElseThrow(() -> new BuildPreconditionException(
                        subjectLabel + " " + HexUtil.encodeHexString(subjectStakeHash)
                        + " is not an allowlisted member of " + policyId
                        + " (or their membership has expired) — add them and publish the "
                        + "member root first"));
        if (!Arrays.equals(leaf.rootHashLocal(), onchainRoot)) {
            throw new BuildPreconditionException(
                    "allowlist root drift: the published-leaf trie root "
                    + HexUtil.encodeHexString(leaf.rootHashLocal())
                    + " does not match the GS datum's member_root_hash "
                    + HexUtil.encodeHexString(onchainRoot)
                    + " — re-publish the member root (UpdateMemberRootHash) before " + verb);
        }
        if (leaf.proofCbor() == null || leaf.proofCbor().length == 0) {
            throw new BuildPreconditionException(
                    "could not serialise the MPF inclusion proof for " + subjectLabel + " "
                    + HexUtil.encodeHexString(subjectStakeHash));
        }
        return new ResolvedMembership(HexUtil.encodeHexString(leaf.proofCbor()), leaf.validUntilMs());
    }

    /** Transaction TTL slot, clamped to an allowlist membership's expiry whenever a
     *  Membership KYC proof is being emitted.
     *
     *  <p>{@code verify_membership_proof} (lib/kyc/verify.ak:137-142) returns False
     *  unless {@code validity_range.upper_bound} is {@code Finite} AND
     *  {@code <= proof.valid_until_ms}, so an unbounded transaction fails that
     *  clause no matter how good the proof is. We therefore always set a bound.
     *
     *  <p>{@code inclusionProof} only rejects memberships that have ALREADY expired,
     *  so a leaf expiring seconds from now would clamp the TTL to (almost) the
     *  current slot. {@code ttl} is invalid-hereafter, so the ledger would reject
     *  such a transaction with {@code OutsideValidityIntervalUTxO} — and any window
     *  under a minute expires while the user is still in the wallet dialog. Refuse
     *  with the expiry timestamp instead of emitting a doomed transaction.
     *
     *  @param membershipValidUntilMs the proof's expiry, or null when no proof is
     *                                being verified (bound is then the default window) */
    private long kycClampedTtlSlot(Long membershipValidUntilMs, byte[] subjectStakeHash,
                                   String subjectLabel, String operation) {
        long now = System.currentTimeMillis();
        long ttlMs = now + DEFAULT_TTL_MS;
        if (membershipValidUntilMs != null) {
            ttlMs = Math.min(ttlMs, membershipValidUntilMs);
            if (ttlMs - now < MIN_KYC_TTL_MS) {
                throw new BuildPreconditionException(
                        subjectLabel + " " + HexUtil.encodeHexString(subjectStakeHash)
                        + "'s allowlist membership expires at "
                        + Instant.ofEpochMilli(membershipValidUntilMs)
                        + ", too soon to build a " + operation + " against (needs at least "
                        + (MIN_KYC_TTL_MS / 1000) + "s). Renew their membership and "
                        + "re-publish the member root.");
            }
        }
        return cardanoConverters.time().toSlot(
                java.time.LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(ttlMs), java.time.ZoneOffset.UTC));
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
            String adminAddress = request.getFeePayerAddress();
            if (adminAddress == null || adminAddress.isBlank()) {
                return TransactionContext.typedError("feePayerAddress is required");
            }
            String adminPkh = request.getAdminPubKeyHash();
            if (adminPkh == null || adminPkh.length() != 56) {
                return TransactionContext.typedError("adminPubKeyHash (28-byte hex) is required");
            }
            String securityAssetNameHex = request.getAssetName();
            if (securityAssetNameHex == null || securityAssetNameHex.isBlank()) {
                return TransactionContext.typedError("assetName (hex) is required");
            }

            // 1. Select a pure-ADA bootstrap UTxO from the admin's wallet. Its
            // OutputReference is the one-shot nonce for the GS mint + both LL mints.
            List<Utxo> utilityUtxos = accountService.findAdaOnlyUtxo(adminAddress, 10_000_000L);
            if (utilityUtxos.isEmpty()) {
                return TransactionContext.typedError(
                        "no ADA-only UTxOs at admin address (need ~10 ADA for fees + 3x min-utxo)");
            }
            Utxo bootstrap = utilityUtxos.getFirst();
            TransactionInput bootstrapInput = TransactionInput.builder()
                    .transactionId(bootstrap.getTxHash())
                    .index(bootstrap.getOutputIndex())
                    .build();

            // 2. Build the three mint scripts and derive their policy ids.
            PlutusScript gsMintScript = scriptBuilder.buildGlobalStateMintScript(bootstrapInput);
            String globalStatePolicyId = gsMintScript.getPolicyId();

            PlutusScript denylistMintScript = scriptBuilder.buildDenylistMintScript(globalStatePolicyId, bootstrapInput);
            String denylistPolicyId = denylistMintScript.getPolicyId();

            PlutusScript powerUsersMintScript = scriptBuilder.buildPowerUsersMintScript(globalStatePolicyId, bootstrapInput);
            String powerUsersPolicyId = powerUsersMintScript.getPolicyId();

            // 3. Derive the prog-token policy id (= issuance_policy_id for the GS
            //    spend script) by wrapping the BaFin minting_logic_script in the
            //    CIP-113 generic issuance contract. The chain is:
            //      bootstrap → gs_policy → (pu_policy, dl_policy)
            //      → minting_logic_script(asset_name, gs_policy, registry_policy_id, pu_policy)
            //      → issuance_mint_script(protocolParams, minting_logic_script) → progTokenPolicyId
            //    All deterministic from the bootstrap UTxO + securityAssetNameHex.
            String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
            PlutusScript mintingLogicScript = scriptBuilder.buildMintingLogicScript(
                    securityAssetNameHex, globalStatePolicyId, registryPolicyId, powerUsersPolicyId);
            PlutusScript issuanceMintScript = protocolScriptBuilderService.getParameterizedIssuanceMintScript(
                    protocolParams, mintingLogicScript);
            String issuancePolicyId = issuanceMintScript.getPolicyId();

            // 4. Build the spend scripts and derive their addresses.
            PlutusScript gsSpendScript = scriptBuilder.buildGlobalStateSpendScript(
                    securityAssetNameHex, issuancePolicyId, globalStatePolicyId);
            Address gsSpendAddress = AddressProvider.getEntAddress(gsSpendScript, network.getCardanoNetwork());

            PlutusScript denylistSpendScript = scriptBuilder.buildDenylistSpendScript(denylistPolicyId);
            Address denylistSpendAddress = AddressProvider.getEntAddress(denylistSpendScript, network.getCardanoNetwork());

            PlutusScript powerUsersSpendScript = scriptBuilder.buildPowerUsersSpendScript(globalStatePolicyId, powerUsersPolicyId);
            Address powerUsersSpendAddress = AddressProvider.getEntAddress(powerUsersSpendScript, network.getCardanoNetwork());

            // 4b. OPT-IN genesis allowlist seed. Off by default; when off, member_root_hash
            // stays empty exactly as before and nothing below runs.
            //
            // With requires_receiver_kyc on, an empty root makes a first mint unbuildable
            // for any ordinary recipient (verify_mint_destinations wants a membership proof
            // and there is no root to prove against), and the self-mint exemption cannot
            // rescue it: that exemption compares the recipient's STAKE credential against
            // the power-user node key, which this chain sets from the wallet's PAYMENT key
            // hash. Publishing a root beforehand is impossible — UpdateMemberRootHash is a
            // GlobalState spend and the GlobalState UTxO is created by THIS transaction.
            // Seeding the root here is the only ordering that works.
            //
            // COMPLIANCE: this enrolls the recipient in the KYC allowlist on the issuer's
            // say-so, with no KYC process behind it, and the resulting root is what every
            // later transfer proves membership against — not just this mint.
            byte[] seededMemberPkh = null;
            long seededMemberValidUntilMs = 0L;
            byte[] genesisMemberRootHash = new byte[0];
            if (request.isSeedRecipientInAllowlistAtGenesis()) {
                String seedRecipient = (request.getRecipientAddress() == null
                        || request.getRecipientAddress().isBlank())
                        ? adminAddress : request.getRecipientAddress();
                seededMemberPkh = new Address(seedRecipient).getDelegationCredentialHash()
                        .orElseThrow(() -> new BuildPreconditionException(
                                "seedRecipientInAllowlistAtGenesis needs a base address with a stake "
                                + "credential (the allowlist is keyed on the STAKE credential under "
                                + "the CIP-113 address model), got " + seedRecipient));
                seededMemberValidUntilMs = System.currentTimeMillis() + GENESIS_SEEDED_MEMBERSHIP_MS;
                genesisMemberRootHash = allowlistService
                        .rootForSingleMember(seededMemberPkh, seededMemberValidUntilMs);
                log.warn("security-token genesis: OPT-IN allowlist seed enabled — enrolling stake "
                         + "credential {} with NO KYC verification, member_root_hash={}",
                        HexUtil.encodeHexString(seededMemberPkh),
                        HexUtil.encodeHexString(genesisMemberRootHash));
            }

            // 4. Build the initial datums.
            PlutusData gsDatum = buildInitialGlobalStateDatum(
                    adminPkh, powerUsersPolicyId, denylistPolicyId,
                    request.getInitialMintableAmount() != null ? request.getInitialMintableAmount() : 0L,
                    request.isRequiresSenderKyc(),
                    request.isRequiresReceiverKyc(),
                    kycNetworkId(),
                    request.getInitialTrustedEntityVkeys(),
                    genesisMemberRootHash);
            PlutusData linkedListRootDatum = buildLinkedListRootDatum();

            // 5. Build the values for the three NFT outputs.
            Asset gsNft = Asset.builder()
                    .name("0x" + SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX)
                    .value(BigInteger.ONE).build();
            Asset emptyAsset = Asset.builder().name("0x").value(BigInteger.ONE).build();

            Value gsValue = oneNftValue(globalStatePolicyId, gsNft);
            Value denylistValue = oneNftValue(denylistPolicyId, emptyAsset);
            Value powerUsersValue = oneNftValue(powerUsersPolicyId, emptyAsset);

            // 6. Compose the genesis tx.
            // Mint redeemers: GS uses Data placeholder (validator accepts anything),
            // denylist + power-users use `Init { root_output_index }` = Constr 0 [Int].
            ConstrPlutusData emptyRedeemer = ConstrPlutusData.of(0);
            // Output indexes: 0 = GS, 1 = denylist root, 2 = power-users root (then change).
            ConstrPlutusData denylistInitRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(1));
            ConstrPlutusData powerUsersInitRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(2));

            // Register the mintingLogic withdraw-0 stake credential in this same
            // tx — the downstream registration tx withdraws-0 from it, so the
            // credential must be in the rewards state. transferLogic's credential
            // is intentionally NOT registered here: (a) registration tx doesn't
            // touch it, and (b) including its cert validator pushes the genesis
            // tx over the 16KB size limit. transferLogic gets registered lazily,
            // in the first transfer tx (TODO).
            String mintingLogicRewardAddress = AddressProvider.getRewardAddress(
                    mintingLogicScript, network.getCardanoNetwork()).getAddress();
            boolean mintingLogicCredAlreadyRegistered = stakeRegistrationRepository
                    .findRegistrationsByStakeAddress(mintingLogicRewardAddress)
                    .map(reg -> reg.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            log.info("security-token genesis: mintingLogic stake cred {} ({})",
                    mintingLogicCredAlreadyRegistered ? "already registered" : "will be registered in this tx",
                    mintingLogicRewardAddress);

            Tx tx = new Tx()
                    .collectFrom(utilityUtxos)
                    .mintAsset(gsMintScript, gsNft, emptyRedeemer)
                    .mintAsset(denylistMintScript, emptyAsset, denylistInitRedeemer)
                    .mintAsset(powerUsersMintScript, emptyAsset, powerUsersInitRedeemer)
                    .payToContract(gsSpendAddress.getAddress(), ValueUtil.toAmountList(gsValue), gsDatum)
                    .payToContract(denylistSpendAddress.getAddress(), ValueUtil.toAmountList(denylistValue), linkedListRootDatum)
                    .payToContract(powerUsersSpendAddress.getAddress(), ValueUtil.toAmountList(powerUsersValue), linkedListRootDatum)
                    .withChangeAddress(adminAddress);

            // Registering a script stake credential needs the deposit, but NOT the script:
            // Conway's RegCert carries no witness requirement, so the publish handler never
            // runs. Attaching minting_logic here cost 7211 bytes inline and pushed this tx
            // to 18027 / 16384. Verified empirically by the protocol deployment, which
            // registers three script credentials with a bare registerStakeAddress and no
            // validator attached — including upgrade_multisig, which has no publish handler
            // at all (`else(_) fail`) and could not have registered if a witness were
            // required. The preBalanceTx below still rewrites the cert to RegCert for the
            // deposit; it no longer injects a Cert redeemer.
            if (!mintingLogicCredAlreadyRegistered) {
                tx = tx.registerStakeAddress(mintingLogicRewardAddress);
            }

            Utxo firstUtilityUtxo = utilityUtxos.getFirst();
            Transaction transaction = quickTxBuilder.compose(tx)
                    .feePayer(adminAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(firstUtilityUtxo.getTxHash())
                            .index(firstUtilityUtxo.getOutputIndex()).build())
                    // No cert rewrite. The legacy StakeRegistration that Bloxbean emits is
                    // still valid in Conway and requires NO witness, so the stake credential
                    // registers without attaching the 7211-byte minting_logic script.
                    //
                    // Rewriting it to RegCert is what forces a witness: RegCert IS
                    // witness-required for a script credential, which is why doing the swap
                    // without the attached script fails with MissingScriptWitnessesUTXOW.
                    // Both halves have to move together — either legacy cert + no script, or
                    // RegCert + attached script + Cert redeemer. We take the former; it is
                    // what the protocol deployment does, and every script-credential
                    // registration on preview is a STAKE_REGISTRATION, none a RegCert.
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
                    .memberRootHashOnchain(HexUtil.encodeHexString(genesisMemberRootHash))
                    .memberRootHashLocal(HexUtil.encodeHexString(genesisMemberRootHash))
                    .build());

            // 7b. The opt-in genesis allowlist seed, now that the row its leaves hang off
            // exists. seedPublishedMember also marks the leaf PUBLISHED — inclusionProof
            // proves against published leaves only (that is the trie whose root matches the
            // chain), and this member's publishing transaction IS the genesis transaction,
            // so no UpdateMemberRootHash will ever come along to mark it.
            if (seededMemberPkh != null) {
                allowlistService.seedPublishedMember(
                        issuancePolicyId, seededMemberPkh, seededMemberValidUntilMs);
                byte[] persistedRoot = allowlistService.currentRoot(issuancePolicyId);
                if (!Arrays.equals(persistedRoot, genesisMemberRootHash)) {
                    // The datum is already sealed into the built transaction at this point,
                    // so a divergence here means every later membership proof would be
                    // rejected as root drift. Fail the build rather than emit a token whose
                    // allowlist can never verify.
                    return TransactionContext.typedError(
                            "genesis allowlist seed produced root "
                            + HexUtil.encodeHexString(persistedRoot)
                            + " in the database but " + HexUtil.encodeHexString(genesisMemberRootHash)
                            + " was written into the global-state datum — refusing to register a "
                            + "token whose membership proofs could never verify. The usual cause is "
                            + "leftover allowlist leaves for policy " + issuancePolicyId
                            + " from an earlier aborted registration: the datum root is computed over "
                            + "the single seeded member, the database root over every leaf. Clear "
                            + "them and retry.");
                }
            }

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

    /** Encode the initial {@value #GS_DATUM_FIELD_COUNT}-field {@code GlobalStateDatum}
     *  per the upstream BaFin shape. Field order MUST match
     *  {@code lib/types/global_state.ak} exactly.
     *
     *  <p>NB: {@code trusted_entity_vkeys} is an Aiken {@code Pairs<...>} which
     *  encodes as a CBOR Map (not a List). Empty Map ≠ empty List at the byte
     *  level. The mint validator's {@code sanitise_initial_datum} calls
     *  {@code is_sorted_no_dup_vkeys} which iterates this value as Pairs.
     *
     *  <p>{@code network_id} is set once here and is IMMUTABLE — every
     *  {@code GlobalStateSpendAction} carries it forward unchanged, and it is
     *  bound into every KYC proof payload (byte 65) to prevent cross-network
     *  replay. */
    private static PlutusData buildInitialGlobalStateDatum(
            String adminPkh,
            String powerUsersPolicyId,
            String denylistPolicyId,
            long mintableAmount,
            boolean requiresSenderKyc,
            boolean requiresReceiverKyc,
            int networkId,
            List<String> initialTrustedEntityVkeys,
            byte[] memberRootHash) {
        // Build trusted_entity_vkeys as a sorted MapPlutusData with each
        // 32-byte vkey → unit metadata (Constr 0 []). Sorting by vkey bytes
        // matches BaFin's on-chain invariant — verify_kyc_proof + AddTrusted
        // both expect lex-ordered keys.
        MapPlutusData trustedMap = MapPlutusData.builder()
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
                ConstrPlutusData.of(0),                                           // [0] transfers_paused = False
                ConstrPlutusData.of(0),                                           // [1] deactivated = False (added @e69c66a)
                BigIntPlutusData.of(BigInteger.valueOf(mintableAmount)),          // [2] mintable_amount
                BytesPlutusData.of(HexUtil.decodeHexString(adminPkh)),            // admin_credential_hash
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersPolicyId)),  // power_user_linked_list_policy_id
                BytesPlutusData.of(HexUtil.decodeHexString(denylistPolicyId)),    // denylist_linked_list_policy_id
                ConstrPlutusData.of(0),                                           // security_info = Unit (Data placeholder)
                trustedMap,                                                       // trusted_entity_vkeys
                // member_root_hash. Empty by default (matches upstream E2ETest and the
                // long-proven path); non-empty only when the caller opted into the genesis
                // allowlist seed, in which case it is the MPF root over that one member.
                BytesPlutusData.of(memberRootHash == null ? new byte[0] : memberRootHash),
                ConstrPlutusData.of(requiresSenderKyc ? 1 : 0),                   // requires_sender_kyc
                ConstrPlutusData.of(requiresReceiverKyc ? 1 : 0),                 // requires_receiver_kyc
                BigIntPlutusData.of(BigInteger.valueOf(networkId))                // network_id
        );
    }

    /** Number of fields in the upstream {@code GlobalStateDatum}
     *  ({@code lib/types/global_state.ak}).
     *
     *  <p>History: 9 in the original hand-maintained fork; 11 at
     *  FluidTokens/fn-bafin-cardano-sc @7ae4ce3 ({@code requires_sender_kyc}
     *  inserted at 8, {@code network_id} appended at 10); <b>12 at
     *  easy1staking-com/fn-bafin-cardano-sc @e69c66a</b>, which inserted
     *  {@code deactivated} at index <b>1</b> and so shifted
     *  {@code mintable_amount} and EVERY field after it up by one. That shift is
     *  silent and dangerous — reading {@code mintable_amount} at the old index 1
     *  now yields the {@code deactivated} Bool — so every index below is named and
     *  no raw literal is used at a call site. */
    private static final int GS_DATUM_FIELD_COUNT = 12;

    private static final int GS_IDX_TRANSFERS_PAUSED = 0;
    /** Added at @e69c66a; {@code DeactivateContract} (spend action 10) sets it. */
    private static final int GS_IDX_DEACTIVATED = 1;
    private static final int GS_IDX_MINTABLE_AMOUNT = 2;
    private static final int GS_IDX_ADMIN_CREDENTIAL_HASH = 3;
    private static final int GS_IDX_POWER_USER_LL_POLICY = 4;
    private static final int GS_IDX_DENYLIST_LL_POLICY = 5;
    private static final int GS_IDX_SECURITY_INFO = 6;
    private static final int GS_IDX_TRUSTED_ENTITY_VKEYS = 7;
    private static final int GS_IDX_MEMBER_ROOT_HASH = 8;
    private static final int GS_IDX_REQUIRES_SENDER_KYC = 9;
    private static final int GS_IDX_REQUIRES_RECEIVER_KYC = 10;
    private static final int GS_IDX_NETWORK_ID = 11;

    /** Network-id byte baked into the GS datum and into every KYC proof payload
     *  (see {@code lib/types/kyc_proof.ak}: {@code 0x00} preview, {@code 0x01}
     *  preprod, {@code 0x02} mainnet, {@code 0x03} yaci/devnet). Upstream used
     *  to take this from a compile-time {@code env} module; at @7ae4ce3 it is
     *  read from the GS datum instead, so the off-chain side owns it.
     *
     *  <p>Unrecognised values throw rather than defaulting. {@code network_id} is
     *  written once at genesis, is immutable afterwards (no
     *  {@code GlobalStateSpendAction} touches it), and is compared byte-for-byte
     *  against every KYC attestation — so a typo'd or unset {@code network}
     *  property silently baking in {@code 0x02} would permanently invalidate every
     *  attestation for that token. Failing the genesis build is strictly better.
     *
     *  <p>NOTE: the accepted spellings here are deliberately a superset of
     *  {@code AppConfig.Network#getCardanoNetwork()}, which handles
     *  {@code preprod}/{@code preview}/{@code devnet} and falls through to mainnet
     *  for everything else — {@code yaci} maps to the devnet id byte here but
     *  would produce mainnet-shaped ADDRESSES there. Configure the devnet as
     *  {@code network=devnet}. */
    private int kycNetworkId() {
        String n = network.getNetwork();
        return switch (n == null ? "" : n) {
            case "preview" -> 0x0;
            case "preprod" -> 0x1;
            case "mainnet" -> 0x2;
            case "devnet", "yaci" -> 0x3;
            default -> throw new IllegalStateException(
                    "unrecognised network '" + n + "': cannot derive the KYC network_id byte. "
                    + "It is baked into the global-state datum at genesis, is immutable "
                    + "afterwards, and is checked byte-for-byte against every KYC attestation, "
                    + "so guessing here would permanently break the token. "
                    + "Set `network` to one of: preview, preprod, mainnet, devnet, yaci.");
        };
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
        ConstrPlutusData elementData = ConstrPlutusData.of(isRoot ? 0 : 1, innerData);
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
        byte[] out = new byte[a.length + b.length];
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
        Instant deadline = Instant.now().plus(timeout);
        java.time.Duration pollInterval = java.time.Duration.ofSeconds(5);
        int attempt = 0;
        while (Instant.now().isBefore(deadline)) {
            attempt++;
            try {
                List<com.bloxbean.cardano.client.api.model.Utxo> utxos = utxoProvider.findUtxosByPolicy(policyId);
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
        ArrayList<com.bloxbean.cardano.client.api.model.Utxo> sorted = new ArrayList<>(utxos);
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
            Utxo overridePuRoot,
            Utxo overrideGsUtxo,
            Utxo overrideFunding) {
        try {
            Optional<SecurityTokenRegistrationEntity> regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError("security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();
            if (reg.getBootstrapTxHash() == null) {
                return TransactionContext.typedError("registration has no bootstrap UTxO recorded — was genesis init run?");
            }

            byte[] newPowerUserKey = HexUtil.decodeHexString(powerUserPkhHex);
            byte[] newNodeAssetName = concat(LL_NODE_KEY_PREFIX, newPowerUserKey);
            String newNodeAssetNameHex = HexUtil.encodeHexString(newNodeAssetName);

            // Rebuild the parameterised scripts from the persisted registration.
            TransactionInput bootstrapInput = TransactionInput.builder()
                    .transactionId(reg.getBootstrapTxHash())
                    .index(reg.getBootstrapOutputIndex())
                    .build();
            PlutusScript puMintScript = scriptBuilder.buildPowerUsersMintScript(reg.getGlobalStatePolicyId(), bootstrapInput);
            PlutusScript puSpendScript = scriptBuilder.buildPowerUsersSpendScript(
                    reg.getGlobalStatePolicyId(), reg.getPowerUsersPolicyId());
            Address puSpendAddress = AddressProvider.getEntAddress(puSpendScript, network.getCardanoNetwork());

            // Resolve PU root, GS, and funding UTxOs. When chain-mode overrides are
            // provided we use them directly (genesis tx isn't on chain yet so the
            // poll-by-policy paths would time out). Otherwise fall back to on-chain
            // discovery — the admin-page "Sync to chain" button uses that path.
            Utxo puRoot = overridePuRoot;
            if (puRoot == null) {
                puRoot = pollForFirstUtxoByPolicy(reg.getPowerUsersPolicyId(),
                        "power-users linked-list root NFT", java.time.Duration.ofSeconds(90));
                if (puRoot == null) {
                    return TransactionContext.typedError(
                            "power-users linked-list root NFT not found on chain after 90s — " +
                            "genesis tx may still be propagating; try the 'Sync to chain' button on the admin page in a minute");
                }
            }

            Utxo gsUtxo = overrideGsUtxo;
            if (gsUtxo == null) {
                gsUtxo = pollForFirstUtxoByPolicy(reg.getGlobalStatePolicyId(),
                        "global-state NFT", java.time.Duration.ofSeconds(30));
                if (gsUtxo == null) {
                    return TransactionContext.typedError(
                            "global-state NFT not found on chain — was genesis init confirmed?");
                }
            }

            Utxo funding = overrideFunding;
            if (funding == null) {
                List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(adminAddress, 5_000_000L);
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
            ConstrPlutusData addPowerUserRedeemer = ConstrPlutusData.of(2,
                    BytesPlutusData.of(newPowerUserKey),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorInIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(newNodeOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdx)));

            // Spend redeemer on the root: StateTransition (Constr 0) — delegates
            // shape checks to the mint validator.
            ConstrPlutusData rootSpendRedeemer = ConstrPlutusData.of(0);

            // Updated root datum: same Root payload, link now points at new node's key.
            ConstrPlutusData updatedRootDatum = linkedListElement(
                    ConstrPlutusData.of(0),
                    optionSome(BytesPlutusData.of(newPowerUserKey)),
                    /*isRoot=*/ true);

            // New node datum: Node(PowerUser{...}), link = None (it's the tail).
            ConstrPlutusData newNodeDatum = linkedListElement(
                    powerUserData(newPowerUserKey, capabilities),
                    optionNone(),
                    /*isRoot=*/ false);

            // Asset for the new node NFT (asset name = "Node" ++ pkh).
            Asset newNodeNft = Asset.builder()
                    .name("0x" + newNodeAssetNameHex)
                    .value(BigInteger.ONE).build();

            String puPolicyId = reg.getPowerUsersPolicyId();

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
            Value rootOutputValue = oneNftValue(puPolicyId,
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
            Optional<SecurityTokenRegistrationEntity> regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return TransactionContext.typedError(
                        "security-token registration not found for policy " + policyId);
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();
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
            TransactionInput bootstrapInput = TransactionInput.builder()
                    .transactionId(reg.getBootstrapTxHash())
                    .index(reg.getBootstrapOutputIndex())
                    .build();
            PlutusScript denylistMintScript = scriptBuilder.buildDenylistMintScript(
                    reg.getGlobalStatePolicyId(), bootstrapInput);
            PlutusScript denylistSpendScript = scriptBuilder.buildDenylistSpendScript(reg.getDenylistPolicyId());
            Address denylistSpendAddress = AddressProvider.getEntAddress(
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
            // power_user_linked_list_policy_id from it to authenticate the
            // power-user node below.
            Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                    reg.getGlobalStatePolicyId(),
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
            ).orElse(null);
            if (gsUtxo == null) {
                return TransactionContext.typedError("global-state NFT not found on chain");
            }

            // Power-user node as a SECOND ref input. Upstream @7ae4ce3 moved the
            // sanction gate off the GS admin credential and onto a power user
            // holding `is_admin` (see validators/denylist.ak: power_user_from_refs
            // → `power_user_data.is_admin && must_be_signed_by_credential(...)`).
            // The signer is therefore whoever is operating the wallet, not the
            // registration's admin — so the compliance role can be delegated
            // without handing over the master admin key.
            byte[] signerPkh = new Address(feePayerAddress).getPaymentCredentialHash()
                    .orElse(null);
            if (signerPkh == null) {
                return TransactionContext.typedError(
                        "feePayerAddress has no payment credential: " + feePayerAddress);
            }
            String signerPkhHex = HexUtil.encodeHexString(signerPkh);
            // Fail here rather than on-chain: the validator's `is_admin` check
            // surfaces as an opaque script failure otherwise.
            boolean signerIsAdmin = powerUserRepository
                    .findByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, signerPkhHex)
                    .map(pu -> SecurityTokenPowerUserCapability.ADMIN.granted(pu.getCapabilities()))
                    .orElse(false);
            if (!signerIsAdmin) {
                return TransactionContext.typedError(
                        "denylist mutations require a power user holding the ADMIN capability; "
                        + signerPkhHex + " is not one for policy " + policyId);
            }
            Utxo powerUserNode = findPuNode(reg, signerPkh, "denylist-admin");

            // Funding UTxO at fee payer (admin's wallet).
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, 5_000_000L);
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
            // Reference inputs appear in the script context sorted by
            // (txHash, outputIndex), NOT in the order they were added — so both
            // indices must be derived from the sorted pair, not hardcoded.
            List<Utxo> denylistRefInputs = List.of(gsUtxo, powerUserNode);
            int gsRefIdx = lexIndex(denylistRefInputs, gsUtxo);
            int puNodeRefIdx = lexIndex(denylistRefInputs, powerUserNode);

            // Mint redeemer: AddToDenylist = variant 2 of MintRedeemer (same
            // index as AddPowerUser — see types/denylist.ak vs types/power_users.ak).
            // Upstream @7ae4ce3 appended power_user_node_ref_input_index as the
            // 6th field of both AddToDenylist and RemoveFromDenylist.
            ConstrPlutusData addToDenylistRedeemer = ConstrPlutusData.of(2,
                    BytesPlutusData.of(targetStakeHash),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorInIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(anchorOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(newNodeOutIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)));

            // Spend redeemer on the root: StateTransition (Constr 0).
            ConstrPlutusData rootSpendRedeemer = ConstrPlutusData.of(0);

            // Updated root datum: link now points at new node's key (stake hash).
            ConstrPlutusData updatedRootDatum = linkedListElement(
                    ConstrPlutusData.of(0),                    // Root payload
                    optionSome(BytesPlutusData.of(targetStakeHash)),
                    /*isRoot=*/ true);

            // New node datum: Node(Denylist { metadata: () }), link = None.
            // BaFin's on-chain validators don't read the metadata, so unit is fine.
            PlutusData denylistData = ConstrPlutusData.of(0, ConstrPlutusData.of(0));
            ConstrPlutusData newNodeDatum = linkedListElement(
                    denylistData,
                    optionNone(),
                    /*isRoot=*/ false);

            Asset newNodeNft = Asset.builder()
                    .name("0x" + newNodeAssetNameHex)
                    .value(BigInteger.ONE).build();
            Value rootOutputValue = oneNftValue(reg.getDenylistPolicyId(),
                    Asset.builder().name("0x").value(BigInteger.ONE).build());

            // Required signer = the power user whose node is referenced above.
            // The validator checks `must_be_signed_by_credential(self,
            // power_user_credential_hash)`, where the credential comes from the
            // referenced node's key — NOT from the GS datum's admin_credential_hash
            // (that gate moved in upstream @7ae4ce3).
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
                                    .index(gsUtxo.getOutputIndex()).build(),
                            TransactionInput.builder()
                                    .transactionId(powerUserNode.getTxHash())
                                    .index(powerUserNode.getOutputIndex()).build())
                    .withChangeAddress(feePayerAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(signerPkh)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .build();

            log.info("security-token AddToDenylist built: policy={} target_stake={} power_user={}",
                    policyId, HexUtil.encodeHexString(targetStakeHash), signerPkhHex);
            return TransactionContext.ok(transaction.serializeToHex());
        } catch (Exception e) {
            log.error("security-token denylist {} failed for policy={} target={}",
                    isAdd ? "add" : "remove", policyId, targetAddress, e);
            return TransactionContext.typedError(
                    "denylist " + (isAdd ? "add" : "remove") + " failed: " + e.getMessage());
        }
    }

    // ── Reference-script publishing ──────────────────────────────────────────

    /** The two per-token scripts the registration transaction reads from reference
     *  inputs instead of carrying inline, plus the UTxOs that hold them.
     *
     *  <p>{@code refUtxo}s carry {@code referenceScriptHash} so
     *  {@link HybridUtxoSupplier} + {@link HybridScriptSupplier} can resolve them
     *  while the publishing transaction is still unsubmitted. */
    public record PublishedRefScripts(String cborHex,
                                      String txHash,
                                      PlutusScript mintingLogicScript,
                                      Utxo mintingLogicRefUtxo,
                                      PlutusScript globalStateSpendScript,
                                      Utxo globalStateSpendRefUtxo) {

        /** The reference inputs the registration tx must read. */
        public List<Utxo> refUtxos() {
            List<Utxo> out = new ArrayList<>();
            if (mintingLogicRefUtxo != null) out.add(mintingLogicRefUtxo);
            if (globalStateSpendRefUtxo != null) out.add(globalStateSpendRefUtxo);
            return out;
        }

        public List<PlutusScript> scripts() {
            List<PlutusScript> out = new ArrayList<>();
            if (mintingLogicScript != null) out.add(mintingLogicScript);
            if (globalStateSpendScript != null) out.add(globalStateSpendScript);
            return out;
        }
    }

    /** Publish {@code minting_logic_script} and {@code global_state} spend as
     *  REFERENCE SCRIPTS, so the registration transaction can name them by
     *  {@code TransactionInput} rather than carry 11 394 bytes of them inline.
     *
     *  <h3>Why this transaction has to exist</h3>
     *  A registration that also mints attaches five validators inline:
     *  <pre>
     *    7211  minting_logic       (reward validator, per token)
     *    4183  global_state spend  (per token)
     *    1928  registry_mint       (CIP-113 core)
     *    1722  issuance_mint       (per token, but small)
     *    1540  registry_spend      (CIP-113 core)
     *   -----
     *   16584  &gt; 16384 max-tx-size, before datums, outputs or witnesses
     *  </pre>
     *  That is structurally over budget, not marginally: no fee tuning or output
     *  trimming recovers 200 bytes plus the ~5 KB the rest of the transaction needs.
     *  Publishing the two dominant scripts drops the inline total to 5190 bytes.
     *
     *  <p>The CIP-113 core does exactly this for PLB/PLG/unfracking, published once
     *  at protocol deployment and referenced from
     *  {@code protocol-bootstraps-&lt;network&gt;.json}. These two cannot follow that
     *  pattern: both are parameterised per token (security asset name, GS policy,
     *  registry policy, power-users policy — and the GS spend additionally by the
     *  issuance policy that derives from the minting logic), so their hashes do not
     *  exist until this token's genesis transaction has picked its bootstrap UTxO.
     *  They therefore have to be published per registration, in their own
     *  transaction: genesis already carries ~9.4 KB of inline minting scripts and
     *  cannot also carry ~11.4 KB of reference-script outputs.
     *
     *  <p>{@code registry_mint} / {@code registry_spend} are deliberately left
     *  inline. They are protocol-global rather than per-token, so their proper home
     *  is the protocol deployment's reference-script set, not a per-token publish;
     *  paying ~15 ADA of min-UTxO per registration to duplicate them would be waste,
     *  and 5190 bytes inline leaves ~11 KB of headroom in the registration tx.
     *
     *  <h3>Where the outputs go</h3>
     *  To the fee payer's ENTERPRISE address — same payment credential, so the admin
     *  can still spend them and recover the ~51 ADA of min-UTxO once the token is
     *  registered, but a distinct address from the base address every other builder
     *  funds itself from. That separation is load-bearing twice over: the
     *  registration builder picks its funding input by scanning the chaining tx for
     *  an output at the fee-payer address, and {@link AccountService#findAdaOnlyUtxo}
     *  queries by address — either would happily consume a reference-script UTxO and
     *  destroy it.
     *
     *  <p>Amounts come from {@link MinAdaCalculator} against the live protocol
     *  params, with the real address and the real script attached, so the figure is
     *  the ledger's own (160 + serSize) * coinsPerUtxoByte rather than a guess. */
    public TransactionContext<PublishedRefScripts> buildPublishScriptsTransaction(
            String feePayerAddress,
            PlutusScript mintingLogicScript,
            PlutusScript globalStateSpendScript,
            Utxo funding) {
        try {
            Credential feePayerPaymentCred = new Address(feePayerAddress).getPaymentCredential()
                    .orElseThrow(() -> new BuildPreconditionException(
                            "fee payer address has no payment credential: " + feePayerAddress));
            String refScriptAddress = AddressProvider
                    .getEntAddress(feePayerPaymentCred, network.getCardanoNetwork()).getAddress();

            var protocolParams = protocolParamsSupplier.getProtocolParams();
            MinAdaCalculator minAda = new MinAdaCalculator(protocolParams);

            List<PlutusScript> toPublish = List.of(mintingLogicScript, globalStateSpendScript);
            List<BigInteger> coins = new ArrayList<>();
            for (PlutusScript script : toPublish) {
                TransactionOutput candidate = TransactionOutput.builder()
                        .address(refScriptAddress)
                        .value(Value.builder().coin(MinAdaCalculator.DUMMY_COIN_VAL).build())
                        .scriptRef(script)
                        .build();
                // Small flat buffer over the ledger-exact minimum, then round up to a whole
                // ADA. The calculation is already exact (real params, real address, real
                // script), so it only needs to absorb the few-byte CBOR-width slack between
                // DUMMY_COIN_VAL and the real coin.
                BigInteger exact = minAda.calculateMinAda(candidate);
                BigInteger coin = exact.add(BigInteger.valueOf(1_000_000L));
                coin = coin.add(BigInteger.valueOf(999_999L))
                        .divide(BigInteger.valueOf(1_000_000L))
                        .multiply(BigInteger.valueOf(1_000_000L));
                log.info("publishScripts: script {} is {} bytes, min-utxo {} lovelace, using {}",
                        HexUtil.encodeHexString(script.getScriptHash()),
                        script.serializeScriptBody().length, exact, coin);
                coins.add(coin);
            }

            // Refuse rather than let the balancer top up. collectFrom() names one input,
            // but if it does not cover the outputs plus the fee cardano-client reaches for
            // more UTxOs at the sender address — and inside a chain those are the ON-CHAIN
            // ones, including the bootstrap UTxO the unsubmitted genesis tx is already
            // spending. That produces a chain whose third transaction double-spends its
            // first, and it would only surface at submit time.
            BigInteger fundingLovelace = funding.getAmount().stream()
                    .filter(a -> "lovelace".equals(a.getUnit()))
                    .map(Amount::getQuantity)
                    .reduce(BigInteger.ZERO, BigInteger::add);
            BigInteger needed = coins.get(0).add(coins.get(1))
                    .add(BigInteger.valueOf(2_000_000L))   // fee headroom
                    .add(BigInteger.valueOf(1_000_000L));  // min-utxo for the change output
            if (fundingLovelace.compareTo(needed) < 0) {
                return TransactionContext.typedError(
                        "publishScripts: the funding UTxO holds " + fundingLovelace
                        + " lovelace but publishing minting_logic + global_state as reference "
                        + "scripts needs at least " + needed
                        + " (their min-UTxO plus fee and change). Top up the admin wallet; a "
                        + "registration that carries a first mint cannot be built without them.");
            }

            // from() as well as collectFrom(): this is the only transaction the chain builds
            // that runs no script at all, and cardano-client's Tx demands an explicit sender
            // whenever there are no script intents to infer one from.
            Tx tx = new Tx()
                    .from(feePayerAddress)
                    .collectFrom(List.of(funding))
                    .payToAddress(refScriptAddress, Amount.lovelace(coins.get(0)), mintingLogicScript)
                    .payToAddress(refScriptAddress, Amount.lovelace(coins.get(1)), globalStateSpendScript)
                    .withChangeAddress(feePayerAddress);

            Transaction transaction = quickTxBuilder.compose(tx)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .build();
            String txHash = TransactionUtil.getTxHash(transaction.serialize());

            // Locate the two reference-script outputs by the script hash the builder
            // actually wrote, never by a hard-coded index: the balancer inserts the change
            // output and cardano-client has moved it between first and last across
            // versions. A misidentified output here would put a *spendable* UTxO in the
            // registration tx's reference inputs and the script would simply not resolve.
            Utxo mintingLogicRef = findRefScriptOutput(transaction, txHash, mintingLogicScript);
            Utxo gsSpendRef = findRefScriptOutput(transaction, txHash, globalStateSpendScript);
            if (mintingLogicRef == null || gsSpendRef == null) {
                return TransactionContext.typedError(
                        "publishScripts: could not locate the reference-script outputs in the "
                        + "built transaction (mintingLogic=" + (mintingLogicRef != null)
                        + ", globalStateSpend=" + (gsSpendRef != null) + ")");
            }

            log.info("publishScripts: tx {} publishes minting_logic at {}:{} and global_state spend at {}:{}",
                    txHash, txHash, mintingLogicRef.getOutputIndex(), txHash, gsSpendRef.getOutputIndex());

            return TransactionContext.ok(transaction.serializeToHex(), new PublishedRefScripts(
                    transaction.serializeToHex(), txHash,
                    mintingLogicScript, mintingLogicRef,
                    globalStateSpendScript, gsSpendRef));
        } catch (BuildPreconditionException bpe) {
            return TransactionContext.typedError(bpe.getMessage());
        } catch (Exception e) {
            log.error("security-token publish-scripts failed", e);
            return TransactionContext.typedError("publish reference scripts failed: " + e.getMessage());
        }
    }

    /** Serialized size of {@code tx}, refusing it when it does not fit the ledger's
     *  {@code maxTxSize}.
     *
     *  <p>Nothing in cardano-client checks this: the builder happily produces an
     *  over-budget transaction and the evaluator happily scores it, so the first
     *  sign of trouble is a rejection at submit — after the earlier links of a
     *  mempool-chain have already been broadcast. Checking here turns that into a
     *  build-time refusal that names the transaction and its size. */
    private int checkedTxSize(String label, Transaction tx) throws Exception {
        int size = tx.serialize().length;
        long maxTxSize = 16384L;
        try {
            var pp = protocolParamsSupplier.getProtocolParams();
            if (pp != null && pp.getMaxTxSize() != null) {
                maxTxSize = pp.getMaxTxSize();
            }
        } catch (Exception e) {
            log.debug("could not read maxTxSize from protocol params, using {}: {}", maxTxSize, e.toString());
        }
        log.info("chain[{}] serialized size = {} bytes (max {})", label, size, maxTxSize);
        if (size > maxTxSize) {
            throw new BuildPreconditionException(
                    "the " + label + " transaction is " + size + " bytes, over the ledger's "
                    + maxTxSize + "-byte maximum. It would be rejected at submit, after the "
                    + "earlier transactions in the chain had already been broadcast.");
        }
        return size;
    }

    /** Find the output of {@code tx} whose reference script is {@code script}, as a
     *  {@link Utxo} carrying {@code referenceScriptHash} — which is the field both
     *  {@code AikenTransactionEvaluator} and cardano-client's fee calculator key on
     *  when they ask a {@code ScriptSupplier} for the script bytes. */
    private static Utxo findRefScriptOutput(Transaction tx, String txHash, PlutusScript script)
            throws Exception {
        byte[] wantedRefBytes = script.scriptRefBytes();
        String scriptHashHex = HexUtil.encodeHexString(script.getScriptHash());
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput out = outputs.get(i);
            if (out.getScriptRef() == null) continue;
            if (!Arrays.equals(out.getScriptRef(), wantedRefBytes)) continue;
            return Utxo.builder()
                    .address(out.getAddress())
                    .txHash(txHash)
                    .outputIndex(i)
                    .amount(ValueUtil.toAmountList(out.getValue()))
                    .referenceScriptHash(scriptHashHex)
                    .build();
        }
        return null;
    }

    /** Result of {@link #buildFullRegistrationChain}: the unsigned tx CBORs that
     *  the frontend signs in a single CIP-30 {@code signTxs} call and submits
     *  via {@code POST /issue-token/submit-chain} so the wallet's submission
     *  backend never sees the chain (which would reject mempool-chained txs). */
    public record ChainBuildResult(
            String genesisCborHex,
            String addPowerUserCborHex,
            /** Publishes {@code minting_logic} + {@code global_state} spend as reference
             *  scripts. Without it the registration tx cannot fit under max-tx-size when
             *  it carries a first mint — see {@link #buildPublishScriptsTransaction}. */
            String publishScriptsCborHex,
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
            String publishScriptsTxHash,
            String registrationTxHash,
            String registerTransferLogicTxHash) {}

    /** Build the full security-token registration chain in one call.
     *
     *  <p>This used to reject every request up front, because phase 3 (the
     *  registration tx) could not validate at the old pin and phase 1 writes
     *  database rows as a side effect — so starting the chain left orphaned
     *  registration and power-user rows behind on every attempt. The pin now carries
     *  a registration mode ({@code RegisterToken}), so the chain runs.
     *
     *  <p>Phase 3 registers <em>structurally</em> (quantity 0) unless the request
     *  carries {@link SecurityTokenRegisterRequest#getInitialMintQuantity()}, in which
     *  case it folds the token's first mint into the same transaction — a supported
     *  contract shape ({@code verify_token_registration}'s {@code minted_amount > 0}
     *  branch). That is reachable precisely because phase 2 runs first: the
     *  {@code can_mint} power-user node the mint needs as a signing reference input is
     *  an output of the AddPowerUser tx, threaded in through
     *  {@link RegistrationChainInputs} exactly like the chained GlobalState UTxO.
     *
     *  <p>Under {@code requires_receiver_kyc} the first mint is only buildable when the
     *  recipient is the authorising power user itself (the contract's self-mint
     *  exemption). Any other recipient needs a published {@code member_root_hash}, and
     *  publishing one is a GlobalState spend that cannot happen before genesis is on
     *  chain — so mint separately after {@code UpdateMemberRootHash} in that case. The
     *  builder refuses up front rather than emitting a doomed transaction. */
    public TransactionContext<ChainBuildResult> buildFullRegistrationChain(
            SecurityTokenRegisterRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            String adminAddress = request.getFeePayerAddress();
            if (adminAddress == null || adminAddress.isBlank()) {
                return TransactionContext.typedError("feePayerAddress is required");
            }

            // ── PHASE 0 ─ Preconditions decidable from the request alone ───
            //
            // Phase 1 does not merely BUILD the genesis tx, it PERSISTS a
            // SecurityTokenRegistrationEntity row plus the bootstrap power-user row as
            // a side effect. Every mint precondition that used to fire in phase 3
            // (supply cap, signer identity, receiver-KYC) therefore left orphan genesis
            // rows behind for a token that would never reach the chain. Anything
            // derivable from the request is refused HERE instead, before a single row
            // is written. The remaining phase-3 checks (denylist covering node,
            // can_mint, index re-derivation) depend on transactions that do not exist
            // yet and cannot be hoisted.
            String firstMintQuantity = request.getInitialMintQuantity() != null
                    && !request.getInitialMintQuantity().isBlank()
                    ? request.getInitialMintQuantity().trim()
                    : "0";
            BigInteger firstMint;
            try {
                firstMint = new BigInteger(firstMintQuantity);
            } catch (NumberFormatException nfe) {
                return TransactionContext.typedError(
                        "initialMintQuantity must be a non-negative integer, got '"
                        + firstMintQuantity + "'");
            }
            if (firstMint.signum() < 0) {
                return TransactionContext.typedError(
                        "initialMintQuantity must be >= 0, got " + firstMint);
            }
            boolean chainWillMint = firstMint.signum() > 0;

            // Normalise once, here. Genesis PERSISTS the asset name verbatim while the
            // registration's mismatch guard compares against a trimmed copy, so a
            // whitespace-padded name would be refused in phase 3 — after the rows were
            // written — for differing from itself.
            if (request.getAssetName() != null) {
                request.setAssetName(request.getAssetName().trim());
            }

            // Same derivation buildGlobalStateInitTransaction and phase 2 use; hoisted
            // here so the mint preconditions can check it. Kept null-only (not
            // blank-aware) so it stays byte-identical to the other two call sites.
            String bootstrapPkh = request.getBootstrapPowerUserPkh() != null
                    ? request.getBootstrapPowerUserPkh()
                    : request.getAdminPubKeyHash();

            // Unconditional: phase 2 runs on EVERY chain and feeds this value straight
            // into HexUtil.decodeHexString. A malformed value used to throw there —
            // i.e. after phase 1 had persisted both rows. A 40-char hex is worse than
            // a non-hex one: it decodes, mints a node with a wrong-length key, and only
            // fails at submit on the required_signers length.
            if (bootstrapPkh == null || !bootstrapPkh.matches("[0-9a-fA-F]{56}")) {
                return TransactionContext.typedError(
                        "bootstrapPowerUserPkh (or adminPubKeyHash) must be 28-byte hex, got '"
                        + bootstrapPkh + "'");
            }

            // Unconditional for the same reason: buildRegistrationTransaction resolves
            // the recipient's stake credential on BOTH paths (it builds the
            // prog-logic-base target address from it), so an enterprise or malformed
            // recipient throws in phase 3 even for a structural registration.
            String recipient = (request.getRecipientAddress() == null
                    || request.getRecipientAddress().isBlank())
                    ? adminAddress : request.getRecipientAddress();
            byte[] recipientStakeHash;
            try {
                recipientStakeHash = new Address(recipient)
                        .getDelegationCredentialHash().orElse(null);
            } catch (Exception e) {
                return TransactionContext.typedError(
                        "recipientAddress is not a valid Cardano address: " + recipient);
            }
            if (recipientStakeHash == null) {
                return TransactionContext.typedError(
                        "recipient must be a base address with a stake credential (the minting "
                        + "logic vets the STAKE credential under the CIP-113 address model): "
                        + recipient);
            }

            if (chainWillMint) {
                // (a) Supply cap. global_state.ak's MintSecurity branch recomputes
                // `mintable_amount - minted` and rejects a negative remainder. The cap
                // is written by THIS chain's genesis tx from initialMintableAmount, so
                // the comparison is exact here — no chain lookup needed.
                long cap = request.getInitialMintableAmount() != null
                        ? request.getInitialMintableAmount() : 0L;
                if (firstMint.compareTo(BigInteger.valueOf(cap)) > 0) {
                    return TransactionContext.typedError(
                            "initialMintQuantity " + firstMint + " exceeds initialMintableAmount "
                            + cap + ". The supply cap is written by this same genesis transaction, "
                            + "so raise initialMintableAmount or lower the first mint.");
                }

                // (b) The registration tx names the power-user node's OWN key in
                // required_signers (minting_logic_script.ak's
                // must_be_signed_by_credential against power_user_node_key). The only
                // key the fee payer can witness is its own payment credential, so a
                // bootstrap PU pkh that differs would demand a witness only a third
                // party holds — and that surfaces at SUBMIT time, after genesis and
                // AddPowerUser have already been broadcast and mempool-chained.
                byte[] callerPkh;
                try {
                    callerPkh = callerPaymentCredential(adminAddress);
                } catch (BuildPreconditionException bpe) {
                    return TransactionContext.typedError(bpe.getMessage());
                }
                if (!HexUtil.encodeHexString(callerPkh).equalsIgnoreCase(bootstrapPkh)) {
                    return TransactionContext.typedError(
                            "registration with a first mint must be signed by the bootstrap power "
                            + "user itself, but bootstrapPowerUserPkh=" + bootstrapPkh
                            + " differs from the fee payer's payment credential "
                            + HexUtil.encodeHexString(callerPkh)
                            + ". Either pay for this chain from that power user's wallet, or "
                            + "register with initialMintQuantity=0 and let them mint separately.");
                }

                // (b2) Same argument for the ADMIN credential. The registration tx puts
                // it in required_signers too (must_be_signed_by_credential against the
                // GS datum's admin_credential_hash), so a first mint needs BOTH keys and
                // the fee payer can only witness one. Scoped to the mint path
                // deliberately: the admin key is a required signer on the structural
                // path as well, but that path is long-proven and byte-frozen, so
                // tightening it is left as a separate, separately-verified change.
                if (request.getAdminPubKeyHash() == null
                        || !HexUtil.encodeHexString(callerPkh)
                                .equalsIgnoreCase(request.getAdminPubKeyHash())) {
                    return TransactionContext.typedError(
                            "registration with a first mint must be signed by the admin too, but "
                            + "adminPubKeyHash=" + request.getAdminPubKeyHash()
                            + " differs from the fee payer's payment credential "
                            + HexUtil.encodeHexString(callerPkh)
                            + ". The registration transaction names both the admin credential and "
                            + "the power-user node key in required_signers, and only the fee payer "
                            + "signs — so pay for this chain from the admin's wallet, or register "
                            + "with initialMintQuantity=0.");
                }

                // (c) Receiver KYC. verify_mint_destinations reads
                // requires_receiver_kyc off the GS datum this same genesis tx writes,
                // and genesis always writes member_root_hash EMPTY — no root can be
                // published beforehand, because the prog-token policy id the allowlist
                // is keyed on does not exist until genesis picks its bootstrap UTxO.
                // The only satisfiable case is the contract's self-mint exemption,
                // which compares the recipient's STAKE credential against the
                // power-user node key.
                if (request.isRequiresReceiverKyc()) {
                    // …unless the caller opted into the genesis allowlist seed, which writes
                    // a real member_root_hash covering exactly this recipient into the same
                    // genesis datum. That makes the ordinary Membership proof path
                    // satisfiable, at the cost of asserting a KYC status nobody checked —
                    // see SecurityTokenRegisterRequest#seedRecipientInAllowlistAtGenesis.
                    if (!request.isSeedRecipientInAllowlistAtGenesis()
                            && !Arrays.equals(recipientStakeHash, HexUtil.decodeHexString(bootstrapPkh))) {
                        return TransactionContext.typedError(
                                "cannot mint at registration while requiresReceiverKyc is on. The "
                                + "minting logic demands a KYC membership proof against the global "
                                + "state's member_root_hash, and genesis writes that root EMPTY — no "
                                + "root can be published beforehand, because the token's policy id "
                                + "does not exist until this transaction picks its bootstrap UTxO. "
                                + "Fix by one of: (1) set requiresReceiverKyc=false for this chain "
                                + "and turn it on afterwards from the admin page "
                                + "(SetRequiresReceiverKyc); (2) register with initialMintQuantity=0, "
                                + "enroll the recipient, publish the root (UpdateMemberRootHash), "
                                + "then mint separately; or (3) send the first mint to an address "
                                + "whose STAKE credential is the power user " + bootstrapPkh
                                + ", which the contract exempts from the receiver-KYC check.");
                    }
                }
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
            String genesisTxHash = TransactionUtil
                    .getTxHash(genesisTx.serialize());
            checkedTxSize("genesis", genesisTx);
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
            TransactionContext<Void> addPuResult = buildAddPowerUserTransaction(
                    progTokenPolicyId, bootstrapPkh, allCaps, adminAddress,
                    chainedPuRoot, chainedGsUtxo, chainedAdminChange);
            if (!addPuResult.isSuccessful()) {
                return TransactionContext.typedError("chain[addPowerUser]: " + addPuResult.error());
            }
            String addPuCbor = addPuResult.unsignedCborTx();
            Transaction addPuTx = Transaction.deserialize(HexUtil.decodeHexString(addPuCbor));
            String addPuTxHash = TransactionUtil
                    .getTxHash(addPuTx.serialize());
            checkedTxSize("addPowerUser", addPuTx);

            // Find AddPowerUser's admin change output to fund the registration tx.
            Utxo addPuChange = findOutputAtAddress(addPuTx, addPuTxHash, adminAddress,
                    BigInteger.valueOf(5_000_000L));
            if (addPuChange == null) {
                return TransactionContext.typedError(
                        "chain[addPowerUser]: no admin change output found to fund registration tx");
            }
            hybridUtxoSupplier.add(addPuChange);

            // ── PHASE 2.5 ─ Publish the two big per-token scripts ──────────
            // The registration tx attaches five validators inline; minting_logic (7211 B)
            // and the global_state spend (4183 B) alone are 11 394 of the 16 384-byte
            // max-tx-size, and the full set is 16 584 — over budget before a single datum,
            // output or witness. Publishing those two as reference scripts leaves 5190 B
            // inline. See buildPublishScriptsTransaction for the full reasoning.
            //
            // ORDERING. This has to sit after genesis (both scripts are parameterised by
            // policy ids genesis derives from its bootstrap UTxO, so their hashes do not
            // exist earlier) and before registration (which reads them). Putting it after
            // AddPowerUser rather than before is what keeps phases 1 and 2 byte-identical
            // to the proven chain: only the registration tx's funding input moves, from
            // AddPowerUser's change to this tx's change.
            //
            // Both scripts are already built above, for the address derivations.
            //
            // ONLY ON THE MINT PATH. A structural registration attaches minting_logic,
            // registry_mint and registry_spend and comes to ~10.7 KB, which fits — it is
            // the first mint that adds the global_state spend and issuance_mint and takes
            // the total to 16 584. Publishing there anyway would cost ~55 ADA of min-UTxO
            // and a fifth signature to reference scripts nothing in that chain needs, and
            // would move the proven zero-mint chain off its byte-frozen shape for no gain.
            PublishedRefScripts published = null;
            String publishCbor = null;
            String publishTxHash = null;
            Utxo publishChange = null;
            if (chainWillMint) {
                TransactionContext<PublishedRefScripts> publishResult = buildPublishScriptsTransaction(
                        adminAddress, mintingLogicScript, gsSpendScript, addPuChange);
                if (!publishResult.isSuccessful()) {
                    return TransactionContext.typedError("chain[publishScripts]: " + publishResult.error());
                }
                published = publishResult.metadata();
                publishCbor = published.cborHex();
                publishTxHash = published.txHash();
                Transaction publishTx = Transaction.deserialize(HexUtil.decodeHexString(publishCbor));
                checkedTxSize("publishScripts", publishTx);

                // The reference-script UTxOs must be resolvable while this tx is unsubmitted:
                // the utxo supplier answers "what is at this output reference" (and carries
                // the referenceScriptHash), the script supplier answers "what script is that
                // hash". Both are consulted by AikenTransactionEvaluator and by the fee
                // calculator; without the pair the local evaluator errors and the app's
                // three-tier evaluator quietly fabricates ex-units instead.
                published.refUtxos().forEach(hybridUtxoSupplier::add);
                published.scripts().forEach(hybridScriptSupplier::add);

                publishChange = findOutputAtAddress(publishTx, publishTxHash, adminAddress,
                        BigInteger.valueOf(5_000_000L));
                if (publishChange == null) {
                    return TransactionContext.typedError(
                            "chain[publishScripts]: no admin change output >= 5 ADA left to fund the "
                            + "registration tx. Publishing minting_logic + global_state costs about "
                            + "55 ADA of min-UTxO (reclaimable — the outputs sit at the admin's own "
                            + "enterprise address), so a registration that carries a first mint needs "
                            + "that much more in the admin wallet than a structural one.");
                }
                hybridUtxoSupplier.add(publishChange);
            }

            // ── PHASE 3 ─ Build the registration tx ────────────────────────
            // A registration does NOT have to mint. `registry_mint`'s RegistryInsert is
            // explicit that "whether a first mint of `key` occurs in the same tx is NOT
            // checked here", and the substandard's own RegisterToken branch validates
            // minted_amount == 0 as its primary case. So register structurally by
            // default and carry a first mint only when the caller asked for an initial
            // supply — the zero-mint branch (GlobalState referenced, no GS spend, no
            // power-user gate) is the one with on-chain mileage behind it.
            //
            // When a quantity IS asked for, the supply cap is enforced by also spending
            // GS under MintSecurity in the same tx, which decrements mintable_amount.
            // firstMintQuantity / chainWillMint were parsed and validated in PHASE 0,
            // before any DB row existed.
            // On the mint path funding now comes from the publish tx's change rather than
            // AddPowerUser's — the publish tx spent that one. The reference-script outputs
            // it also created sit at the admin's ENTERPRISE address precisely so this scan
            // (which matches on the fee-payer address) cannot pick one of them as a funding
            // input and destroy the reference script.
            request.setChainingTransactionCborHex(chainWillMint ? publishCbor : addPuCbor);
            request.setQuantity(firstMintQuantity);
            request.setGlobalStatePolicyId(reg.getGlobalStatePolicyId());

            // The registration's mint half needs two more UTxOs that are not on chain
            // yet: the power-user node AddPowerUser just created (reference input +
            // signer) and the denylist ROOT from genesis (the absence-covering element,
            // which covers every key while the list is empty).
            Utxo chainedPuNode = null;
            Utxo chainedDenylistRoot = null;
            if (chainWillMint) {
                String bootstrapNodeAssetNameHex = HexUtil.encodeHexString(
                        concat(LL_NODE_KEY_PREFIX, HexUtil.decodeHexString(bootstrapPkh)));
                chainedPuNode = findOutputWithAsset(addPuTx, addPuTxHash,
                        reg.getPowerUsersPolicyId(), bootstrapNodeAssetNameHex);
                chainedDenylistRoot = findOutputWithAsset(genesisTx, genesisTxHash,
                        reg.getDenylistPolicyId(), "");
                if (chainedPuNode == null || chainedDenylistRoot == null) {
                    return TransactionContext.typedError(
                            "chain[registration]: registration with a first mint needs the "
                            + "AddPowerUser node and the denylist root as reference inputs, but "
                            + "they could not be located (puNode=" + (chainedPuNode != null)
                            + ", denylistRoot=" + (chainedDenylistRoot != null) + ")");
                }
                hybridUtxoSupplier.add(chainedPuNode);
                hybridUtxoSupplier.add(chainedDenylistRoot);
            }

            TransactionContext<RegistrationResult> regResult =
                    buildRegistrationTransaction(request, protocolParams,
                            new RegistrationChainInputs(chainedGsUtxo, chainedPuNode,
                                    chainedDenylistRoot, published));
            if (!regResult.isSuccessful()) {
                return TransactionContext.typedError("chain[registration]: " + regResult.error());
            }
            String regCbor = regResult.unsignedCborTx();
            Transaction regTx = Transaction.deserialize(HexUtil.decodeHexString(regCbor));
            String regTxHash = TransactionUtil
                    .getTxHash(regTx.serialize());
            checkedTxSize("registration", regTx);

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
                    certTxHash = TransactionUtil
                            .getTxHash(certTx.serialize());
                    checkedTxSize("registerTransferLogic", certTx);
                    log.info("chain[registerTransferLogic] built cert tx {} (chain length: 4)", certTxHash);
                } else {
                    log.warn("chain[registerTransferLogic] failed (chain will return 3 txs; "
                            + "user will need the runtime cert tx on first transfer): {}",
                            certResult.error());
                }
            } else {
                log.warn("chain[registerTransferLogic] skipped: no admin change output >= 5.5 ADA in registration tx (chain will return 3 txs)");
            }

            return TransactionContext.ok(null, new ChainBuildResult(
                    genesisCbor, addPuCbor, publishCbor, regCbor, certCbor,
                    reg.getGlobalStatePolicyId(), progTokenPolicyId,
                    reg.getDenylistPolicyId(), reg.getPowerUsersPolicyId(),
                    genesisTxHash, addPuTxHash, publishTxHash, regTxHash, certTxHash));
        } catch (Exception e) {
            log.error("security-token chain build failed", e);
            return TransactionContext.typedError("chain build failed: " + e.getMessage());
        } finally {
            // Single exit point for the mempool scratchpads. Previously each of the early
            // returns had to remember its own clear() and the catch block was the only
            // net; the mint path added five more ways to bail out. The script supplier
            // has the same thread-local contract as the utxo supplier.
            hybridUtxoSupplier.clear();
            hybridScriptSupplier.clear();
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

    /** Walk {@code tx}'s outputs and return the first one carrying exactly one unit of
     *  {@code (policyId, assetNameHex)}, wrapped as a {@link Utxo} with inline datum
     *  preserved. Returns null if no match.
     *
     *  <p>The sibling {@link #findOutputAtAddress} matches on address, which cannot
     *  separate the two linked-list outputs an insert emits — the updated ROOT and the
     *  new NODE sit at the same script address. Identity there comes from the NFT's
     *  asset name ({@code ""} = root, {@code "Node" ++ key} = node), so this variant
     *  matches on the asset instead. */
    private static Utxo findOutputWithAsset(Transaction tx, String txHash,
                                            String policyId, String assetNameHex) {
        List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outputs =
                tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            com.bloxbean.cardano.client.transaction.spec.TransactionOutput out = outputs.get(i);
            // ADA-only outputs (the change) can carry a null multi-asset list, which
            // ValueUtil.toAmountList would NPE on. They never match anyway.
            if (out.getValue() == null || out.getValue().getMultiAssets() == null
                    || out.getValue().getMultiAssets().isEmpty()) {
                continue;
            }
            // Go through Amount/AssetType (unit = policyId ++ assetNameHex) rather than
            // Asset.getName(), whose "0x"-prefixed-vs-raw representation depends on
            // whether the Value was built here or round-tripped through CBOR.
            List<Amount> amounts = ValueUtil.toAmountList(out.getValue());
            boolean match = amounts.stream().anyMatch(a -> {
                if ("lovelace".equals(a.getUnit())) return false;
                AssetType at = AssetType.fromUnit(a.getUnit());
                return policyId.equals(at.policyId())
                        && assetNameHex.equalsIgnoreCase(at.assetName())
                        && BigInteger.ONE.equals(a.getQuantity());
            });
            if (!match) continue;
            String inlineDatumHex = out.getInlineDatum() != null
                    ? out.getInlineDatum().serializeToHex() : null;
            return Utxo.builder()
                    .address(out.getAddress())
                    .txHash(txHash)
                    .outputIndex(i)
                    .amount(amounts)
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
     *      power-user node for the BURNER (mintingLogic checks can_burn; the
     *           third-party transfer logic checks can_force_transfer on the
     *           same node — a burner needs both)
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
     *
     *  <h3>Receiver KYC on a PARTIAL burn</h3>
     *  A partial burn leaves a token-bearing continuation output, which
     *  {@code third_party_transfer_logic_script} treats as a destination and
     *  subjects to {@code verify_kyc_proof} when {@code requires_receiver_kyc} is
     *  set. That branch has <em>no</em> self-exemption (contrast
     *  {@code minting_logic_script}, which lets a power user mint to itself), so
     *  the burner must be an allowlisted member in their own right, and the
     *  transaction must carry a Finite validity upper bound. Both are handled
     *  below. A FULL burn emits no token-bearing output and needs neither.
     */
    @Override
    public TransactionContext<Void> buildBurnTransaction(
            BurnTokenRequest request,
            ProtocolBootstrapParams protocolParams) {
        try {
            // ── 1. Resolve registration row + validate burn quantity ───────
            SecurityTokenRegistrationEntity reg = registrationRepository
                    .findByProgrammableTokenPolicyId(request.tokenPolicyId())
                    .orElseThrow(() -> new BuildPreconditionException(
                            "security-token registration not found for policy " + request.tokenPolicyId()));

            BigInteger burnQuantity;
            try {
                burnQuantity = new BigInteger(request.quantity());
            } catch (NumberFormatException nfe) {
                throw new BuildPreconditionException("quantity must be a positive integer");
            }
            if (burnQuantity.signum() <= 0) {
                throw new BuildPreconditionException("burn quantity must be > 0");
            }
            BigInteger mintFieldQuantity = burnQuantity.negate();

            // ── 2. Resolve burner credentials from the request fee-payer ───
            // The BURNER is the connected wallet — not necessarily the original
            // registrant admin. minting_logic.withdraw enforces that this
            // payment PKH is a tx signer AND holds the BURNER capability in the
            // power-users linked-list node. prog-logic-global's
            // collect_input_assets also requires the burner's STAKE credential
            // in extra_signatories (it uses has_or_fail, which crashes with
            // EmptyList rather than failing cleanly when missing).
            Address feePayer = new Address(request.feePayerAddress());
            byte[] burnerKeyHash = feePayer.getPaymentCredentialHash()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "feePayerAddress has no payment credential: " + request.feePayerAddress()));
            byte[] burnerStakeHash = feePayer.getDelegationCredentialHash()
                    .orElseThrow(() -> new BuildPreconditionException(
                            "feePayerAddress must be a base address with a stake credential: "
                            + request.feePayerAddress()));

            // ── 3. Resolve scripts + on-chain inputs via shared helpers ────
            MintLikeScripts s = buildMintLikeScripts(reg, protocolParams);
            Utxo gsUtxo = findGsUtxo(reg);
            Utxo puNode = findPuNode(reg, burnerKeyHash, "burner");
            // A burn drives TWO validators that each gate on a different capability
            // of this one node, and both read it from the node we reference here:
            //   minting_logic_script.ak:368-371 — negative mint ⇒ can_burn
            //   third_party_transfer_logic_script.ak:170 — ThirdPartyAct ⇒ can_force_transfer
            // Read them off chain so a burner missing either gets a message naming
            // the capability instead of an evaluator trap.
            PowerUserCaps burnerCaps = parsePowerUser(puNode);
            if (!burnerCaps.canBurn()) {
                throw new BuildPreconditionException(
                        "burner " + HexUtil.encodeHexString(burnerKeyHash) + " has a power-user node "
                        + "but not the can_burn capability, which the on-chain minting logic requires "
                        + "on the negative-mint branch (minting_logic_script.ak: power_user_data.can_burn). "
                        + "Granting it means the power-users validator's ModifyPowerUser action, which "
                        + "this platform does not yet build — the capability has to be set when the node "
                        + "is created. Burn from a wallet whose node already holds can_burn.");
            }
            if (!burnerCaps.canForceTransfer()) {
                throw new BuildPreconditionException(
                        "burner " + HexUtil.encodeHexString(burnerKeyHash) + " has a power-user node "
                        + "but not the can_force_transfer capability. A burn spends the token UTxO "
                        + "through CIP-113's ThirdPartyAct branch, whose validator "
                        + "(third_party_transfer_logic_script.ak: power_user_data.can_force_transfer) "
                        + "gates on that flag IN ADDITION to minting_logic's can_burn — so a burner "
                        + "needs both. Burn from a wallet whose node holds can_burn AND "
                        + "can_force_transfer.");
            }
            Utxo directoryEntry = findDirectoryEntry(request.tokenPolicyId(), protocolParams);
            List<Utxo> protoIssue = findProtocolAndIssuanceUtxos(protocolParams);
            Utxo protocolParamsUtxo = protoIssue.get(0);
            Utxo issuanceUtxo = protoIssue.get(1);
            Utxo funding = findFunding(request.feePayerAddress(), 5_000_000L);

            // Token UTxO being burned (caller-supplied). Distinct from the GS
            // and PU lookups — its location depends on which wallet holds the
            // tokens. The validator gates on the burner's stake cred so the
            // UTxO's address is implicitly the burner's.
            Utxo tokenUtxo = utxoProvider.findUtxo(
                    request.utxoTxHash(), request.utxoOutputIndex()
            ).orElseThrow(() -> new BuildPreconditionException(
                    "token UTxO not found: " + request.utxoTxHash() + ":" + request.utxoOutputIndex()));

            // Honour request.quantity; the continuation output keeps any
            // leftover balance of the burned policy (partial burn). The CIP-113
            // prog-logic-global ThirdPartyAct branch we use below performs a
            // value-preservation check (input − mint == output) which holds
            // for both full-UTxO and partial burns. FreezeAndSeize ALWAYS
            // burns the entire UTxO because of its own substandard validator
            // (dict.delete on the policy); BaFin's minting_logic has no such
            // constraint.
            String burnedAssetUnit = request.tokenPolicyId() + request.assetName();
            BigInteger utxoTokenBalance = tokenUtxo.getAmount().stream()
                    .filter(a -> burnedAssetUnit.equals(a.getUnit().replace("0x", "")))
                    .map(Amount::getQuantity)
                    .findFirst()
                    .orElse(BigInteger.ZERO);
            if (utxoTokenBalance.signum() <= 0) {
                throw new BuildPreconditionException(
                        "token UTxO does not contain any of " + burnedAssetUnit);
            }
            if (burnQuantity.compareTo(utxoTokenBalance) > 0) {
                throw new BuildPreconditionException(
                        "burn quantity " + burnQuantity + " exceeds the token UTxO's balance of "
                        + utxoTokenBalance + " — pick a UTxO with enough of the token, or split the burn");
            }
            BigInteger remainingTokenInUtxo = utxoTokenBalance.subtract(burnQuantity);

            // ── 4. Parse GS datum + apply mintable_amount delta ────────────
            // Burn INCREMENTS mintable_amount (the cap returns):
            //   remaining = old - minted_amount = old - (-burnQty) = old + burnQty
            List<PlutusData> gsFields = parseGsFields(gsUtxo);
            long currentMintable = ((BigIntPlutusData) gsFields.get(GS_IDX_MINTABLE_AMOUNT)).getValue().longValueExact();
            PlutusData newGsDatum = applyMintableDelta(gsFields, burnQuantity.longValueExact());
            long newMintable = currentMintable + burnQuantity.longValueExact();

            // ── 4b. Receiver-KYC gate on the partial-burn continuation ─────
            // A PARTIAL burn leaves a token-bearing continuation output, which
            // third_party_transfer_logic_script.ak:81-98 counts as a destination and
            // then runs through its per-destination loop:
            //
            //   let kyc_ok = if gs_datum.requires_receiver_kyc {
            //     verify_kyc_proof(action.destination_proof, dest_pkh, …)
            //   } else { True }
            //
            // Note what is NOT there: unlike minting_logic_script.ak:419-424, this
            // branch has NO `dest_pkh == power_user_node_key` self-exemption. So a
            // power user burning part of their OWN holding still needs a real
            // membership proof for their own stake credential — the placeholder that
            // used to be passed here (includeKycProof=false) could never verify. A
            // FULL burn emits no token-bearing output, so the destination list is
            // empty and no proof is needed.
            boolean requiresReceiverKyc = boolFromConstr(gsFields.get(GS_IDX_REQUIRES_RECEIVER_KYC));
            boolean isPartialBurn = remainingTokenInUtxo.signum() > 0;
            boolean needBurnDestinationProof = requiresReceiverKyc && isPartialBurn;

            String burnMpfProofCborHex = null;
            Long burnMpfValidUntilMs = null;
            if (needBurnDestinationProof) {
                ResolvedMembership m = resolveMembershipProof(
                        request.tokenPolicyId(), burnerStakeHash, gsFields,
                        "burn destination", "burning");
                burnMpfProofCborHex = m.proofCborHex();
                burnMpfValidUntilMs = m.validUntilMs();
            }

            // ── 5. Redeemer indices ────────────────────────────────────────
            // The continuation output of a PARTIAL burn still carries the security
            // token, so the third-party validator counts it as a destination and
            // demands a denylist-absence covering node for it. A full burn leaves no
            // token-bearing output and needs none — but we resolve one regardless, so
            // the reference-input set (and therefore every index below) does not depend
            // on the burn being partial or full.
            Utxo denylistCoveringNodeForBurn = findDenylistCoveringNode(reg, burnerStakeHash);
            // Inputs sorted lex by (txHash, outIdx): tokenUtxo, gsUtxo, funding.
            int gsInputIdx = lexIndex(List.of(gsUtxo, tokenUtxo, funding), gsUtxo);
            // Reference inputs MUST include the full set the tx actually has —
            // the validator reads ref inputs by index against the lex-sorted
            // FULL list. Omitting progBaseRef + progGlobalRef from this
            // computation would make our directoryRefIdx + puNodeRefIdx point
            // at the wrong entries at eval time.
            TransactionInput progBaseRefInput = TransactionInput.builder()
                    .transactionId(protocolParams.programmableBaseRefInput().txHash())
                    .index(protocolParams.programmableBaseRefInput().outputIndex())
                    .build();
            TransactionInput progGlobalRefInput = TransactionInput.builder()
                    .transactionId(protocolParams.programmableGlobalRefInput().txHash())
                    .index(protocolParams.programmableGlobalRefInput().outputIndex())
                    .build();
            // GlobalState is ALSO a reference input here, not only a spent input.
            // The third-party validator (which slot 4 now names — see below) reads GS
            // from self.reference_inputs, while minting_logic's burn path reads it from
            // self.inputs. Both must be satisfied by the one GS UTxO, so it appears in
            // both lists. Babbage forbade that overlap; Conway permits it. If this
            // transaction is ever rejected with a non-disjoint-reference-inputs ledger
            // error, THAT is the reason, and the two requirements are then genuinely
            // irreconcilable in a single transaction.
            List<TransactionInput> refInputsSorted = java.util.stream.Stream.of(
                    txInputOf(directoryEntry), txInputOf(puNode),
                    txInputOf(protocolParamsUtxo), txInputOf(issuanceUtxo),
                    txInputOf(gsUtxo), txInputOf(denylistCoveringNodeForBurn),
                    progBaseRefInput, progGlobalRefInput
            ).sorted(new TransactionInputComparator()).toList();
            int directoryRefIdx = refInputsSorted.indexOf(txInputOf(directoryEntry));
            int puNodeRefIdx = refInputsSorted.indexOf(txInputOf(puNode));
            int gsRefIdxForBurn = refInputsSorted.indexOf(txInputOf(gsUtxo));
            int denylistRefIdxForBurn = refInputsSorted.indexOf(txInputOf(denylistCoveringNodeForBurn));
            // Tx has 2 Spends (gs + token), 1 Mint (issuance), 2 Rewards
            // (mintingLogic + programmableLogicGlobal). Redeemers sorted by tag
            // (Spend → Mint → Cert → Reward), so the issuance Mint sits at
            // global index 2.
            int issuancePri = 2;

            // ── 6. Build redeemers ─────────────────────────────────────────
            // types.MintingRegistryProof directly (no SmartTokenMintingAction wrapper in v0.4.0):
            // RefInput { index } = Constr 0 [Int].
            PlutusData issuanceRedeemer =
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)));
            // mintingLogic.withdraw, MintBurn (constructor 0) with negative
            // minted_amount — the validator's else-branch requires can_burn.
            // destination_actions is empty: burns have no destinations, and
            // verify_mint_or_burn short-circuits destination checks when
            // minted_amount <= 0 (minting_logic_script.ak:343-358).
            PlutusData withdrawRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsInputIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    BigIntPlutusData.of(mintFieldQuantity),
                    ListPlutusData.of());
            // Output ordering — continuation FIRST (matches FES; prog-logic-global
            // ThirdPartyAct reads outputs from outputs_start_idx onward to verify
            // the burned policy has been removed):
            //   output 0: continuation of tokenUtxo (same address + datum,
            //             burned policy stripped from value)
            //   output 1: new GS UTxO (mintable_amount incremented)
            //   output N: change to fee-payer (moved to end by preBalanceTx)
            int continuationOutputIdx = 0;
            int gsOutputIdx = 1;
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),
                    BigIntPlutusData.of(BigInteger.valueOf(gsOutputIdx)),
                    ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(issuancePri))));
            // Token UTxO spend redeemer — passthrough. The prog-logic-base
            // validator running against this input delegates authorisation to
            // prog-logic-global's withdraw-0 below.
            PlutusData tokenSpendRedeemer = ConstrPlutusData.of(0);
            // prog-logic-global — invoked via withdraw-0 to authorise the
            // prog-token spend. Burns use the ThirdPartyAct branch (Constr 1)
            // rather than TransferAct (Constr 0): the latter requires per-token
            // proofs and a matching continuation-as-transfer shape that doesn't
            // exist for a burn. ThirdPartyAct just needs the registry-entry
            // index + the outputs_start_idx telling the validator where the
            // continuation outputs begin (so it can verify the burned policy
            // has been removed from them). See FreezeAndSeizeHandler.buildBurn
            // for the same pattern.
            PlutusScript programmableLogicGlobal = protocolScriptBuilderService
                    .getParameterizedProgrammableLogicGlobalScript(protocolParams);
            Address programmableLogicGlobalRewardAddress = AddressProvider.getRewardAddress(
                    programmableLogicGlobal, network.getCardanoNetwork());
            PlutusData programmableGlobalRedeemer = ConstrPlutusData.of(1,
                    BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(continuationOutputIdx)));

            // ThirdPartyAct requires a withdrawal keyed on whatever registry-node slot 4
            // names. Commit 0ec401a put mintingLogic there, so the withdrawal the burn
            // already needed satisfied it for free. Slot 4 now holds the substandard's
            // real third_party_transfer_logic_validator (defect A is fixed), so the burn
            // must withdraw from THAT too, and its reward account must be registered
            // first — buildRegisterTransferLogicTransaction's counterpart.
            //
            // Note this tightens burn authority: the power user must now hold
            // can_force_transfer IN ADDITION to can_burn, because the third-party
            // validator gates on the former while minting_logic gates on the latter.
            PlutusScript thirdPartyTransferLogicScript = scriptBuilder.buildThirdPartyTransferLogicScript(
                    reg.getSecurityAssetNameHex(), reg.getPowerUsersPolicyId(),
                    reg.getGlobalStatePolicyId(), protocolParams.directoryMintParams().scriptHash());
            Address thirdPartyRewardAddress = AddressProvider.getRewardAddress(
                    thirdPartyTransferLogicScript, network.getCardanoNetwork());
            // KNOWN BLOCKER — this reward account is never registered by any code path.
            // The withdrawal below requires it to exist on chain, but the only stake
            // registrations in this substandard are mintingLogic (genesis, ~line 1679)
            // and transferLogic (buildRegisterTransferLogicTransaction) — a DIFFERENT
            // script. There is no counterpart for this one, so a burn builds and
            // evaluates fine (script evaluation does not check reward-account
            // existence) and is then rejected phase-1 at submit with
            // WithdrawalsNotInRewardsCERTS. Fixing it means a
            // buildRegisterThirdPartyTransferLogicTransaction mirroring
            // buildRegisterTransferLogicTransaction, wired into
            // buildFullRegistrationChain and the admin UI. Not yet done — no burn has
            // ever been submitted, so this has never been observed, only derived.
            // ThirdPartyTransferLogicScriptWithdrawRedeemer { registry_node_ref_input_index,
            //   global_state_ref_input_index, power_user_node_ref_input_index,
            //   destination_actions }. One action per unique destination stake credential
            //   among token-bearing outputs — the partial-burn continuation is the only
            //   one, and it returns to the burner.
            PlutusData thirdPartyRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.valueOf(directoryRefIdx)),
                    BigIntPlutusData.of(BigInteger.valueOf(gsRefIdxForBurn)),
                    BigIntPlutusData.of(BigInteger.valueOf(puNodeRefIdx)),
                    isPartialBurn
                            ? ListPlutusData.of(buildDestinationAction(
                                    burnerStakeHash, needBurnDestinationProof,
                                    burnMpfProofCborHex, burnMpfValidUntilMs,
                                    denylistRefIdxForBurn))
                            : ListPlutusData.of());

            // ── 7. Outputs ─────────────────────────────────────────────────
            Value gsValue = buildPreservedGsValue(gsUtxo, reg.getGlobalStatePolicyId());
            Asset burnAsset = Asset.builder()
                    .name("0x" + request.assetName())
                    .value(mintFieldQuantity).build();

            // Continuation value — preserve the token UTxO's lovelace + every
            // other multi-asset; for the burned policy, keep
            // `remainingTokenInUtxo` of the burned asset (zero = full burn →
            // policy entry omitted entirely so we don't emit a zero-quantity
            // asset, which the ledger rejects).
            Value tokenUtxoValue = tokenUtxo.toValue();
            List<MultiAsset> continuationMultiAssets = new ArrayList<>();
            if (tokenUtxoValue.getMultiAssets() != null) {
                for (MultiAsset ma : tokenUtxoValue.getMultiAssets()) {
                    if (!ma.getPolicyId().equals(request.tokenPolicyId())) {
                        continuationMultiAssets.add(ma);
                        continue;
                    }
                    // Keep all OTHER asset names under the same policy verbatim, and the
                    // burned asset at the reduced quantity. A FULL burn drops only the
                    // burned asset — skipping the whole policy entry would silently
                    // discard any sibling asset names, and the balancer would sweep them
                    // into the fee-payer's change output, moving programmable tokens out
                    // of the prog-logic-base address.
                    List<Asset> kept = new ArrayList<>();
                    for (Asset a : ma.getAssets()) {
                        String aHexName = a.getName().startsWith("0x")
                                ? a.getName().substring(2) : a.getName();
                        if (aHexName.equalsIgnoreCase(request.assetName())) {
                            // Zero-quantity assets are rejected by the ledger, so on a
                            // full burn the entry is omitted rather than set to 0.
                            if (remainingTokenInUtxo.signum() > 0) {
                                kept.add(Asset.builder()
                                        .name("0x" + request.assetName())
                                        .value(remainingTokenInUtxo).build());
                            }
                        } else {
                            kept.add(a);
                        }
                    }
                    if (kept.isEmpty()) continue;
                    continuationMultiAssets.add(MultiAsset.builder()
                            .policyId(ma.getPolicyId())
                            .assets(kept).build());
                }
            }
            Value continuationValue = Value.builder()
                    .coin(tokenUtxoValue.getCoin())
                    .multiAssets(continuationMultiAssets)
                    .build();

            // ── 8. Compose tx ──────────────────────────────────────────────
            // Script witnesses:
            //   spending → gsSpend                (attached — BaFin, parameterised)
            //   spending → programmableLogicBase  (REFERENCED via progBaseRefInput)
            //   minting  → issuanceContract       (attached — wrapped per-token)
            //   reward   → mintingLogic           (attached — BaFin, parameterised, can_burn)
            //   reward   → thirdPartyTransferLogic (attached — BaFin, parameterised,
            //                                       can_force_transfer)
            //   reward   → programmableLogicGlobal (REFERENCED via progGlobalRefInput)
            //
            // The protocol scripts MUST be referenced (not attached) — attaching
            // both inline pushes the tx over the 16 KB ledger size limit.
            //
            // SIZE WARNING: this transaction now attaches FOUR validators. The
            // headroom note below was written when the third-party script was not
            // among them; a parameterised third-party validator is in the same size
            // class as transferLogic (~6 KB), so it is spending exactly the headroom
            // that note says was needed. If a real burn is ever rejected for size,
            // the fix is to publish thirdPartyTransferLogic as a reference script and
            // read it in like progBaseRefInput / progGlobalRefInput, rather than
            // attaching it inline. Unmeasured — no burn has ever been built against a
            // real token UTxO.
            //
            // transferLogic is intentionally NOT in the witness set. Registry
            // node field 3 (transfer_logic_script) is only consulted by
            // prog-logic-global's TransferAct branch; this tx takes the
            // ThirdPartyAct branch, which instead requires a withdrawal keyed
            // on registry node field 4 (third_party_transfer_logic_script) —
            //   lib/registry_node.ak :: with_key_and_3rd_party_logic
            //   validators/programmable_logic/third_party.ak ::
            //     expect pairs.has_key_or_fail(self.withdrawals, third_party_logic)
            // Slot 4 now holds the substandard's REAL
            // third_party_transfer_logic_validator (that was the fix in 0ec401a), so
            // this tx withdraws from that script's reward account — see the
            // withdrawal below. Keeping transferLogic out still saves ~5952
            // bytes of transferLogic script witness, keeping the tx under 16 KB.
            Tx tx = new Tx()
                    .collectFrom(List.of(funding))
                    .collectFrom(tokenUtxo, tokenSpendRedeemer)
                    .collectFrom(gsUtxo, gsSpendRedeemer)
                    .withdraw(s.mintingLogicRewardAddress().getAddress(), BigInteger.ZERO, withdrawRedeemer)
                    .withdraw(thirdPartyRewardAddress.getAddress(), BigInteger.ZERO, thirdPartyRedeemer)
                    .withdraw(programmableLogicGlobalRewardAddress.getAddress(), BigInteger.ZERO,
                            programmableGlobalRedeemer)
                    .mintAsset(s.issuanceContract(), burnAsset, issuanceRedeemer)
                    .payToContract(tokenUtxo.getAddress(), ValueUtil.toAmountList(continuationValue),
                            ConstrPlutusData.of(0))                                                  // output 0 (continuation)
                    .payToContract(s.gsSpendAddress().getAddress(), ValueUtil.toAmountList(gsValue),
                            newGsDatum)                                                              // output 1 (new GS)
                    .readFrom(refInputsSorted.toArray(new TransactionInput[0]))
                    .attachSpendingValidator(s.gsSpend())
                    .attachRewardValidator(s.mintingLogic())
                    .attachRewardValidator(thirdPartyTransferLogicScript)
                    .withChangeAddress(request.feePayerAddress());

            // ── 9. Build with burner signature + pinned collateral ─────────
            // Required signers:
            //   burnerKeyHash (PAYMENT) — needed by mintingLogic.withdraw's
            //     can_burn check (matches the PU node's credential_hash field).
            //   burnerStakeHash (STAKE) — needed by prog-logic-global's
            //     collect_input_assets check on the prog-token UTxO's stake
            //     credential. Wallets sign for BOTH payment and stake when
            //     signing as themselves, so this just declares the requirement.
            // Finite validity upper bound — same requirement as the mint: a
            // Membership proof only verifies against a Finite upper bound that is
            // <= proof.valid_until_ms. Set on every burn (harmless when no proof is
            // verified) and clamped to the membership expiry when one is.
            long burnTtlSlot = kycClampedTtlSlot(
                    needBurnDestinationProof ? burnMpfValidUntilMs : null,
                    burnerStakeHash, "burn destination", "burn");

            String feePayerAddress = request.feePayerAddress();
            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(burnerKeyHash, burnerStakeHash)
                    .feePayer(feePayerAddress)
                    .mergeOutputs(false)
                    .withCollateralInputs(TransactionInput.builder()
                            .transactionId(funding.getTxHash())
                            .index(funding.getOutputIndex()).build())
                    .validTo(burnTtlSlot)
                    .preBalanceTx(moveLeadingChangeOutputToEnd(feePayerAddress))
                    .postBalanceTx((bctx, txn) -> {
                        // Same fee-too-small pattern we saw in transfer:
                        // Bloxbean's fee calc comes up short by a few KB
                        // lovelace (final body ends up slightly bigger due to
                        // redeemer-ExUnits/script-data-hash drift). Overpay by
                        // 10000 lovelace, compensate the change output, and
                        // bump total_collateral + shrink collateral_return in
                        // lockstep so the Conway invariants hold.
                        TransactionBody body = txn.getBody();
                        BigInteger feePadding = BigInteger.valueOf(10_000L);
                        BigInteger oldFee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
                        body.setFee(oldFee.add(feePadding));
                        // Subtract from the largest fee-payer-addressed output (the change).
                        List<TransactionOutput> outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (TransactionOutput o : outputs) {
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
                            TransactionOutput ret = body.getCollateralReturn();
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
        } catch (BuildPreconditionException bpe) {
            return TransactionContext.typedError(bpe.getMessage());
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
                        List<Certificate> certs = txn.getBody().getCerts();
                        if (certs != null) {
                            for (int i = 0; i < certs.size(); i++) {
                                if (!(certs.get(i)
                                        instanceof com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration sr)) continue;
                                com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential cred = sr.getStakeCredential();
                                if (cred.getType()
                                        != com.bloxbean.cardano.client.transaction.spec.cert.StakeCredType.SCRIPTHASH) continue;
                                certs.set(i, com.bloxbean.cardano.client.transaction.spec.cert.RegCert.builder()
                                        .stakeCredential(cred)
                                        .coin(BigInteger.valueOf(2_000_000L))
                                        .build());
                                com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet ws = txn.getWitnessSet();
                                if (ws.getRedeemers() == null) {
                                    ws.setRedeemers(new ArrayList<>());
                                }
                                com.bloxbean.cardano.client.plutus.spec.Redeemer publishRedeemer = com.bloxbean.cardano.client.plutus.spec.Redeemer.builder()
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
                        TransactionBody body = txn.getBody();
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
                        TransactionBody body = txn.getBody();
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
                        List<TransactionOutput> outputs = body.getOutputs();
                        com.bloxbean.cardano.client.transaction.spec.TransactionOutput
                                changeOut = null;
                        BigInteger largestChange = BigInteger.ZERO;
                        for (TransactionOutput o : outputs) {
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
            if (gsFields.size() != GS_DATUM_FIELD_COUNT) {
                return TransactionContext.typedError("GS datum has " + gsFields.size()
                        + " fields, expected " + GS_DATUM_FIELD_COUNT
                        + " (pre-@7ae4ce3 global state — must be re-bootstrapped)");
            }
            // Replace only field 7 (member_root_hash); everything else — including
            // requires_sender_kyc / requires_receiver_kyc / network_id — is carried
            // through verbatim, which is what the validator's equals_data check requires.
            PlutusData newGsDatum = replaceGsField(gsFields, GS_IDX_MEMBER_ROOT_HASH, BytesPlutusData.of(newRootHash));

            // Funding UTxO from admin's wallet (backend AdminSigningKeyProvider).
            List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(adminAddress, 5_000_000L);
            if (fundingUtxos.isEmpty()) {
                return TransactionContext.typedError("no funding UTxO at admin address");
            }
            Utxo funding = fundingUtxos.getFirst();

            // GS spend redeemer: Constr 0 (config_ref_idx=0, gs_output_idx=0,
            // action = Constr 8 UpdateMemberRootHash(new_root_hash)). Index 8,
            // not 7: SetRequiresSenderKyc was inserted at 6 in upstream @7ae4ce3.
            PlutusData gsSpendRedeemer = ConstrPlutusData.of(0,
                    BigIntPlutusData.of(BigInteger.ZERO),                          // config_ref_input_index
                    BigIntPlutusData.of(BigInteger.ZERO),                          // global_state_output_index
                    ConstrPlutusData.of(8, BytesPlutusData.of(newRootHash)));     // UpdateMemberRootHash

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
                                                    // "SetRequiresSenderKyc" | "SetRequiresReceiverKyc" |
                                                    // "UpdateMemberRootHash" | "RotateAdmin" |
                                                    // "DeactivateContract" (no fields; irreversible)
            Boolean transfersPaused,                // PauseTransfers
            String newSecurityInfoHex,              // ModifySecurityInfo (CBOR-encoded Data)
            String trustedVkeyHex,                  // AddTrustedEntity / RemoveTrustedEntity (32-byte hex)
            String trustedMetadataHex,              // AddTrustedEntity (CBOR-encoded Data)
            String trustedOldVkeyHex,               // UpdateTrustedEntity
            String trustedNewVkeyHex,               // UpdateTrustedEntity
            String trustedNewMetadataHex,           // UpdateTrustedEntity (CBOR-encoded Data)
            Boolean requiresSenderKycEnabled,       // SetRequiresSenderKyc
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

            // PauseTransfers' on-chain branch reads a power-user node as a reference
            // input (validator does safe_list_at(self.reference_inputs, …) and fails
            // with EmptyList otherwise), then requires that node to hold can_pause AND
            // that node's OWN key to have signed (global_state.ak:278-282).
            //
            // D6: the node must therefore be the CALLER's, not the registration row's
            // genesis admin. requiredSigners only ever carries signerKeyHash (derived
            // from feePayerAddress), so keying off reg.getIssuerAdminPkh() produced an
            // unsatisfiable branch after any RotateAdmin, and locked out every
            // non-admin power user who legitimately holds can_pause.
            List<Utxo> pauseTransfersRefInputs = List.of();
            boolean needsPuRef = changes.stream()
                    .anyMatch(c -> "PauseTransfers".equals(c.action()));
            if (needsPuRef) {
                byte[] callerNodeAssetName = concat(LL_NODE_KEY_PREFIX, signerKeyHash);
                String callerNodeAssetNameHex = HexUtil.encodeHexString(callerNodeAssetName);
                Utxo puNode = utxoProvider.findUtxoByAsset(
                        reg.getPowerUsersPolicyId(), callerNodeAssetNameHex).orElse(null);
                if (puNode == null) {
                    return TransactionContext.typedError(
                            "PauseTransfers requires the CALLER's power-user node on chain, but "
                            + signerPkh + " has none (asset: " + reg.getPowerUsersPolicyId()
                            + "/" + callerNodeAssetNameHex
                            + "). Pause from a wallet that already has a power-user node — "
                            + "AddPowerUser currently only builds the FIRST insertion "
                            + "(anchor = linked-list root), so a second node cannot be added yet.");
                }
                PowerUserCaps callerCaps;
                try {
                    callerCaps = parsePowerUser(puNode);
                } catch (BuildPreconditionException bpe) {
                    return TransactionContext.typedError("PauseTransfers: " + bpe.getMessage());
                }
                if (!callerCaps.canPause()) {
                    return TransactionContext.typedError(
                            "PauseTransfers: caller " + signerPkh + " has a power-user node but not "
                            + "the can_pause capability, which global_state.ak requires. Granting it "
                            + "means the power-users validator's ModifyPowerUser action, which this "
                            + "platform does not yet build — the capability has to be set when the "
                            + "node is created. Pause from a wallet whose node already holds can_pause.");
                }
                pauseTransfersRefInputs = List.of(puNode);
            }

            List<String> unsignedCbors = new ArrayList<>();
            try {
                for (int i = 0; i < changes.size(); i++) {
                    GsChangeSpec change = changes.get(i);
                    ActionAndDatum ad = computeActionAndDatum(change, currentDatum, policyId);
                    if (ad.error != null) {
                        return TransactionContext.typedError("change[" + i + "]: " + ad.error);
                    }

                    List<Utxo> refInputs = "PauseTransfers".equals(change.action())
                            ? pauseTransfersRefInputs
                            : List.of();

                    // RotateAdmin is DUAL-SIGNED on chain: global_state.ak requires
                    // must_be_signed_by_credential for BOTH the outgoing admin (from
                    // the INPUT datum's admin_credential_hash) and the incoming one.
                    // Passing only the fee payer's credential means
                    // new_admin_credential_hash never reaches extra_signatories and
                    // the branch can never validate. Declare both here so the built
                    // transaction states its real signing requirement; the frontend
                    // must collect the second signature (see report).
                    List<byte[]> requiredSigners = new ArrayList<>();
                    requiredSigners.add(signerKeyHash);
                    if ("RotateAdmin".equals(change.action())) {
                        addSignerIfAbsent(requiredSigners,
                                gsAdminCredentialHash(currentDatum));
                        addSignerIfAbsent(requiredSigners,
                                HexUtil.decodeHexString(change.newAdminCredentialHashHex()));
                    }

                    TransactionContext<Void> stepCtx = buildSingleGsUpdateTx(
                            reg, gsSpendScript, gsSpendAddress, gsUtxo, ad.actionRedeemer,
                            ad.newDatum, funding, feePayerAddress, requiredSigners, refInputs);
                    if (!stepCtx.isSuccessful()) {
                        return TransactionContext.typedError(
                                "change[" + i + "] (" + change.action() + "): " + stepCtx.error());
                    }
                    unsignedCbors.add(stepCtx.unsignedCborTx());

                    // Roll forward: next iteration's gsUtxo = THIS tx's output 0
                    // (the new GS) and next iteration's funding = THIS tx's change
                    // output. These synthetic UTxOs aren't on chain yet, so we
                    // register them with the hybrid supplier so Bloxbean's
                    // collateral-output builder (and any other supplier-backed
                    // lookups) can resolve them on the next iteration.
                    byte[] builtBytes = HexUtil.decodeHexString(stepCtx.unsignedCborTx());
                    Transaction builtTx = Transaction.deserialize(builtBytes);
                    String nextTxHash = TransactionUtil
                            .getTxHash(builtBytes);
                    List<TransactionOutput> outs = builtTx.getBody().getOutputs();
                    gsUtxo = utxoFromOutput(nextTxHash, 0, outs.get(0));
                    currentDatum = ad.newDatum;
                    if (outs.size() > 1
                            && outs.get(1).getAddress().equals(feePayerAddress)) {
                        funding = utxoFromOutput(nextTxHash, 1, outs.get(1));
                    }
                    hybridUtxoSupplier.add(gsUtxo);
                    hybridUtxoSupplier.add(funding);
                }
            } finally {
                hybridUtxoSupplier.clear();
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

    /** Thrown by the {@code requireXxx} validators below to abort
     *  {@link #computeActionAndDatum} with a field-level message. Caught there and
     *  turned into {@code ActionAndDatum.error}, which the orchestrator prefixes
     *  with {@code change[i]:} and the controller returns as HTTP 400. */
    private static final class ChangeValidationException extends RuntimeException {
        ChangeValidationException(String message) { super(message); }
    }

    /** Decode a caller-supplied hex string, rejecting a bad charset or an odd
     *  length with a message naming the field (D7). {@code HexUtil} throws an
     *  {@code IllegalArgumentException} whose text does not say which input was
     *  at fault, which is what made these surface as opaque build failures. */
    private static byte[] requireHex(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new ChangeValidationException(field + " is required");
        }
        String v = value.trim();
        if (v.length() % 2 != 0) {
            throw new ChangeValidationException(
                    field + " must be an even number of hex characters (got " + v.length() + ")");
        }
        if (!v.matches("(?i)[0-9a-f]+")) {
            throw new ChangeValidationException(
                    field + " must be hex ([0-9a-fA-F] only)");
        }
        return HexUtil.decodeHexString(v);
    }

    /** Same as {@link #requireHex} plus an exact byte-length check — used for the
     *  32-byte Ed25519 trusted-entity vkeys and the 28-byte admin credential hash
     *  (the latter is checked on chain too:
     *  {@code global_state.ak}'s {@code bytearray.length(new_admin_credential_hash) == 28}). */
    private static byte[] requireHexOfLength(String field, String value, int expectedBytes) {
        byte[] bytes = requireHex(field, value);
        if (bytes.length != expectedBytes) {
            throw new ChangeValidationException(
                    field + " must be exactly " + expectedBytes + " bytes ("
                    + (expectedBytes * 2) + " hex characters), got " + bytes.length);
        }
        return bytes;
    }

    /** Decode a caller-supplied hex payload as CBOR {@code PlutusData} (D7).
     *
     *  <p>The GS datum's {@code security_info} and a trusted entity's
     *  {@code metadata} are both opaque {@code Data} on chain, so the platform
     *  relays whatever the operator supplies — but it must actually BE {@code Data}.
     *  Valid-hex-but-invalid-CBOR (the canonical example is {@code deadbeef}) used
     *  to reach {@code PlutusData.deserialize} and surface as a raw parser message
     *  from deep in the build, with no indication of which field was wrong.
     *
     *  <p>Two failure modes are caught: input that does not parse at all, and input
     *  that parses but leaves trailing bytes unconsumed. The latter matters because
     *  the decoder would silently drop the tail, and the operator would never learn
     *  that part of their payload did not make it on chain.
     *
     *  <p>Trailing bytes are detected by measuring how much of the input the CBOR
     *  decoder actually <em>consumed</em>, NOT by comparing the input's length to a
     *  re-serialisation. A round-trip length comparison is wrong in both directions:
     *  canonicalisation can expand a value (a 65-byte bytestring re-encodes to the
     *  chunked indefinite form, which is longer, hiding a real trailing byte) or
     *  shrink it (non-minimal but perfectly legal input like {@code 1a00000001}
     *  re-encodes to {@code 01}, which would be reported as four trailing bytes that
     *  do not exist). Consumption is the only sound measure. */
    private static PlutusData requireCborPlutusData(String field, String value) {
        byte[] bytes = requireHex(field, value);

        // Pass 1 — structural: decode exactly one item and require the whole input
        // to have been consumed.
        try {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes);
            co.nstant.in.cbor.model.DataItem item = new co.nstant.in.cbor.CborDecoder(in).decodeNext();
            if (item == null) {
                throw new ChangeValidationException(field + " contains no CBOR value");
            }
            int remaining = in.available();
            if (remaining > 0) {
                throw new ChangeValidationException(
                        field + " has " + remaining + " trailing byte(s) after a complete CBOR "
                        + "value — the extra bytes would be silently dropped. Supply exactly one "
                        + "CBOR Data value.");
            }
        } catch (ChangeValidationException cve) {
            throw cve;
        } catch (Exception e) {
            throw new ChangeValidationException(
                    field + " is not valid CBOR: " + e.getMessage()
                    + ". It must be the hex of a CBOR-encoded Plutus Data value "
                    + "(e.g. \"a0\" for an empty map, \"40\" for empty bytes, \"d87980\" for "
                    + "Constr 0 []) — not arbitrary hex.");
        }

        // Pass 2 — semantic: the item must be representable as Plutus Data. CBOR has
        // constructs Data does not (floats, simple values, text strings), so a
        // well-formed CBOR value is not automatically a valid datum payload.
        PlutusData parsed;
        try {
            parsed = PlutusData.deserialize(bytes);
        } catch (Exception e) {
            throw new ChangeValidationException(
                    field + " is well-formed CBOR but not valid Plutus Data: " + e.getMessage()
                    + ". Plutus Data is limited to integers, byte strings, lists, maps and "
                    + "constructors — no floats, text strings or simple values.");
        }
        if (parsed == null) {
            throw new ChangeValidationException(field + " decoded to no PlutusData value");
        }
        return parsed;
    }

    /** {@code admin_credential_hash} — GS datum field 2. Read from the datum the tx
     *  is actually spending rather than from the cached DB row, because RotateAdmin
     *  can have moved it since registration. */
    private static byte[] gsAdminCredentialHash(PlutusData gsDatum) {
        if (!(gsDatum instanceof ConstrPlutusData constr)) {
            throw new IllegalStateException("GS datum is not a Constr");
        }
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        if (fields.size() != GS_DATUM_FIELD_COUNT
                || !(fields.get(GS_IDX_ADMIN_CREDENTIAL_HASH) instanceof BytesPlutusData admin)) {
            throw new IllegalStateException(
                    "GS datum does not carry a 28-byte admin_credential_hash at field 2");
        }
        return admin.getValue();
    }

    /** Append {@code keyHash} unless an equal hash is already present — duplicate
     *  entries in {@code required_signers} are a ledger-level error. */
    private static void addSignerIfAbsent(List<byte[]> signers, byte[] keyHash) {
        if (keyHash == null) return;
        for (byte[] existing : signers) {
            if (Arrays.equals(existing, keyHash)) return;
        }
        signers.add(keyHash);
    }

    /** Compute (action redeemer, new datum) for one change. The new datum mutates
     *  only the field the action owns; all other fields are preserved verbatim.
     *
     *  <p>Every caller-supplied payload is validated first, so a malformed hex or
     *  CBOR field comes back as {@code ActionAndDatum.error} — which the
     *  orchestrator prefixes with {@code change[i]:} and the controller returns as
     *  a 400 naming the field — rather than as a parser message escaping from the
     *  middle of a transaction build (D7). */
    private ActionAndDatum computeActionAndDatum(GsChangeSpec change, PlutusData currentDatum,
                                                 String policyId) {
        try {
            return computeActionAndDatumChecked(change, currentDatum, policyId);
        } catch (ChangeValidationException e) {
            ActionAndDatum out = new ActionAndDatum();
            out.error = e.getMessage();
            return out;
        } catch (ClassCastException e) {
            // The trusted-entity branches cast field 7 to MapPlutusData. Aiken emits
            // `Pairs` as a CBOR map so that holds in practice, but `trustedEntitiesFrom`
            // shows the alternative list-of-Constr encoding is representable — and an
            // unhandled CCE here would escape as an opaque "GS update chain failed:
            // …cannot be cast to…". Name the actual problem instead.
            ActionAndDatum out = new ActionAndDatum();
            out.error = change.action() + ": the on-chain global-state datum has an unexpected "
                    + "shape for this action (" + e.getMessage() + "). The trusted-entity field "
                    + "is expected to be a CBOR map.";
            return out;
        }
    }

    private ActionAndDatum computeActionAndDatumChecked(GsChangeSpec change, PlutusData currentDatum,
                                                        String policyId) {
        ActionAndDatum out = new ActionAndDatum();
        if (!(currentDatum instanceof ConstrPlutusData currentConstr)) {
            out.error = "current GS datum is not a Constr";
            return out;
        }
        List<PlutusData> f = currentConstr.getData().getPlutusDataList();
        if (f.size() != GS_DATUM_FIELD_COUNT) {
            out.error = "current GS datum has " + f.size() + " fields, expected "
                    + GS_DATUM_FIELD_COUNT + " (pre-@7ae4ce3 global state — must be re-bootstrapped)";
            return out;
        }

        // Terminal guard, mirroring `expect !input_datum.deactivated` at
        // global_state.ak:185. That check runs BEFORE branch dispatch, so once
        // DeactivateContract has landed NO action can ever spend the GS UTxO again —
        // not an unpause, not a burn, not another deactivation. Refuse here so the
        // operator gets told the contract is decommissioned rather than watching
        // every admin action trap in the evaluator.
        if (boolFromConstr(f.get(GS_IDX_DEACTIVATED))) {
            out.error = "this token's global state is DEACTIVATED (decommissioned). "
                    + "global_state.ak rejects every spend of a deactivated global-state UTxO, "
                    + "so no admin action — including unpausing, burning, or deactivating again "
                    + "— can be applied. This is irreversible by design.";
            return out;
        }

        // GlobalStateSpendAction constructor indices, from the pinned upstream
        // blueprint (types/global_state/GlobalStateSpendAction). SetRequiresSenderKyc
        // was INSERTED at 6 when the fork was replaced with upstream @7ae4ce3, so
        // SetRequiresReceiverKyc / UpdateMemberRootHash / RotateAdmin each shifted
        // up by one. Emitting the old index does not fail to build — it selects a
        // different action, which is exactly the silent-corruption mode this port
        // is guarding against.
        switch (change.action()) {
            case "PauseTransfers" -> {
                // PauseTransfersAction { transfers_paused, power_user_node_ref_input_index }.
                // The validator does safe_list_at(self.reference_inputs, idx) on this
                // index, so the orchestrator MUST add the admin's PU node as a ref
                // input. With a single ref input the index is 0.
                if (change.transfersPaused() == null) {
                    out.error = "PauseTransfers requires transfersPaused"; return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(1,
                        boolToConstr(change.transfersPaused()),
                        BigIntPlutusData.of(BigInteger.ZERO));
                out.newDatum = replaceGsField(f, GS_IDX_TRANSFERS_PAUSED, boolToConstr(change.transfersPaused()));
            }
            case "ModifySecurityInfo" -> {
                // D7: security_info is opaque `Data` on chain, so the platform relays
                // whatever the operator supplies — but validate that it IS Data before
                // it reaches the tx builder, so bad input comes back as a field-level
                // 400 rather than a CBOR parser message from deep inside the build.
                PlutusData newSecInfo = requireCborPlutusData(
                        "ModifySecurityInfo: newSecurityInfoHex", change.newSecurityInfoHex());
                out.actionRedeemer = ConstrPlutusData.of(2, newSecInfo);
                out.newDatum = replaceGsField(f, GS_IDX_SECURITY_INFO, newSecInfo);
            }
            case "AddTrustedEntity" -> {
                byte[] vkeyBytes = requireHexOfLength(
                        "AddTrustedEntity: trustedVkeyHex", change.trustedVkeyHex(), 32);
                String vkeyHex = HexUtil.encodeHexString(vkeyBytes);
                PlutusData vkey = BytesPlutusData.of(vkeyBytes);
                PlutusData meta = requireCborPlutusData(
                        "AddTrustedEntity: trustedMetadataHex", change.trustedMetadataHex());
                out.actionRedeemer = ConstrPlutusData.of(3, vkey, meta);
                // trusted_entity_vkeys is a Map (Pairs) — read existing, add the new
                // (vkey -> meta) pair. On chain the insert goes through
                // pairs.insert_by_ascending_key with bytearray.compare
                // (global_state.ak:303-308); for uniform 32-byte keys a TreeMap over
                // the lowercase hex encoding gives the same ascending order.
                MapPlutusData oldMap = (MapPlutusData) f.get(GS_IDX_TRUSTED_ENTITY_VKEYS);
                MapPlutusData newMap = MapPlutusData.builder().map(new java.util.LinkedHashMap<>()).build();
                java.util.SortedMap<String, PlutusData> sorted = new java.util.TreeMap<>();
                oldMap.getMap().forEach((k, v) ->
                        sorted.put(HexUtil.encodeHexString(((BytesPlutusData) k).getValue()), v));
                // On chain: `!has_key` — AddTrustedEntity rejects a duplicate outright.
                // A TreeMap.put would silently overwrite instead, producing a datum the
                // validator refuses; catch it here with a message that says so.
                if (sorted.containsKey(vkeyHex)) {
                    out.error = "AddTrustedEntity: " + vkeyHex + " is already a trusted entity "
                            + "of this token — use UpdateTrustedEntity to change its metadata";
                    return out;
                }
                sorted.put(vkeyHex, meta);
                sorted.forEach((kHex, v) -> newMap.put(
                        BytesPlutusData.of(HexUtil.decodeHexString(kHex)), v));
                out.newDatum = replaceGsField(f, GS_IDX_TRUSTED_ENTITY_VKEYS, newMap);
            }
            case "RemoveTrustedEntity" -> {
                byte[] vkeyBytes = requireHexOfLength(
                        "RemoveTrustedEntity: trustedVkeyHex", change.trustedVkeyHex(), 32);
                String vkeyHex = HexUtil.encodeHexString(vkeyBytes);
                out.actionRedeemer = ConstrPlutusData.of(4, BytesPlutusData.of(vkeyBytes));
                MapPlutusData oldMap = (MapPlutusData) f.get(GS_IDX_TRUSTED_ENTITY_VKEYS);
                // On chain: `has_key` — removing an absent vkey fails the branch.
                boolean present = oldMap.getMap().keySet().stream().anyMatch(k ->
                        HexUtil.encodeHexString(((BytesPlutusData) k).getValue()).equals(vkeyHex));
                if (!present) {
                    out.error = "RemoveTrustedEntity: " + vkeyHex + " is not currently a trusted "
                            + "entity of this token — nothing to remove";
                    return out;
                }
                // On chain RemoveTrustedEntity is a list.filter, which preserves the
                // surviving entries' order; MapPlutusData is backed by a LinkedHashMap,
                // so iterating it here preserves decode (i.e. ascending) order too.
                MapPlutusData newMap = MapPlutusData.builder().map(new java.util.LinkedHashMap<>()).build();
                oldMap.getMap().forEach((k, v) -> {
                    String kHex = HexUtil.encodeHexString(((BytesPlutusData) k).getValue());
                    if (!kHex.equals(vkeyHex)) {
                        newMap.put(k, v);
                    }
                });
                out.newDatum = replaceGsField(f, GS_IDX_TRUSTED_ENTITY_VKEYS, newMap);
            }
            case "UpdateTrustedEntity" -> {
                byte[] oldVkeyBytes = requireHexOfLength(
                        "UpdateTrustedEntity: trustedOldVkeyHex", change.trustedOldVkeyHex(), 32);
                byte[] newVkeyBytes = requireHexOfLength(
                        "UpdateTrustedEntity: trustedNewVkeyHex", change.trustedNewVkeyHex(), 32);
                PlutusData newMeta = requireCborPlutusData(
                        "UpdateTrustedEntity: trustedNewMetadataHex", change.trustedNewMetadataHex());
                PlutusData oldVkey = BytesPlutusData.of(oldVkeyBytes);
                PlutusData newVkey = BytesPlutusData.of(newVkeyBytes);
                out.actionRedeemer = ConstrPlutusData.of(5, oldVkey, newVkey, newMeta);
                String oldVkeyHex = HexUtil.encodeHexString(oldVkeyBytes);
                String newVkeyHex = HexUtil.encodeHexString(newVkeyBytes);
                MapPlutusData oldMap = (MapPlutusData) f.get(GS_IDX_TRUSTED_ENTITY_VKEYS);
                boolean oldPresent = oldMap.getMap().keySet().stream().anyMatch(k ->
                        HexUtil.encodeHexString(((BytesPlutusData) k).getValue()).equals(oldVkeyHex));
                if (!oldPresent) {
                    out.error = "UpdateTrustedEntity: trustedOldVkeyHex " + oldVkeyHex
                            + " is not currently a trusted entity of this token — nothing to update";
                    return out;
                }
                // On chain: when renaming (old != new) the new vkey must not already
                // exist, or the update would collapse two entries into one.
                if (!oldVkeyHex.equals(newVkeyHex)) {
                    boolean newClashes = oldMap.getMap().keySet().stream().anyMatch(k ->
                            HexUtil.encodeHexString(((BytesPlutusData) k).getValue()).equals(newVkeyHex));
                    if (newClashes) {
                        out.error = "UpdateTrustedEntity: trustedNewVkeyHex " + newVkeyHex
                                + " is already a trusted entity of this token, so renaming "
                                + oldVkeyHex + " onto it would merge two entries. Remove one first.";
                        return out;
                    }
                }
                // Drop the old key, insert the new one, re-sort ascending. When
                // old == new this is a metadata-only edit, which the on-chain branch
                // (global_state.ak:344-382) explicitly permits.
                java.util.SortedMap<String, PlutusData> sorted = new java.util.TreeMap<>();
                oldMap.getMap().forEach((k, v) -> {
                    String kHex = HexUtil.encodeHexString(((BytesPlutusData) k).getValue());
                    if (!kHex.equals(oldVkeyHex)) sorted.put(kHex, v);
                });
                sorted.put(newVkeyHex, newMeta);
                MapPlutusData newMap = MapPlutusData.builder().map(new java.util.LinkedHashMap<>()).build();
                sorted.forEach((kHex, v) -> newMap.put(
                        BytesPlutusData.of(HexUtil.decodeHexString(kHex)), v));
                out.newDatum = replaceGsField(f, GS_IDX_TRUSTED_ENTITY_VKEYS, newMap);
            }
            case "SetRequiresSenderKyc" -> {
                // NOTE: no validator at the pinned upstream revision reads
                // requires_sender_kyc — transfer_logic_script gates the SENDER
                // check on requires_receiver_kyc. This action still writes the
                // field (the GS spend validator enforces it), it just has no
                // enforcement effect yet.
                if (change.requiresSenderKycEnabled() == null) {
                    out.error = "SetRequiresSenderKyc requires requiresSenderKycEnabled"; return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(6,
                        boolToConstr(change.requiresSenderKycEnabled()));
                out.newDatum = replaceGsField(f, GS_IDX_REQUIRES_SENDER_KYC,
                        boolToConstr(change.requiresSenderKycEnabled()));
            }
            case "SetRequiresReceiverKyc" -> {
                if (change.requiresReceiverKycEnabled() == null) {
                    out.error = "SetRequiresReceiverKyc requires requiresReceiverKycEnabled"; return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(7,
                        boolToConstr(change.requiresReceiverKycEnabled()));
                out.newDatum = replaceGsField(f, GS_IDX_REQUIRES_RECEIVER_KYC,
                        boolToConstr(change.requiresReceiverKycEnabled()));
            }
            case "UpdateMemberRootHash" -> {
                // If newMemberRootHashHex is null, caller wants the current local root.
                byte[] root = change.newMemberRootHashHex() != null
                        ? requireHex("UpdateMemberRootHash: newMemberRootHashHex",
                                     change.newMemberRootHashHex())
                        : allowlistService.currentRoot(policyId);
                // On chain: `new_root_len == 0 || new_root_len == 32`
                // (global_state.ak:424-427) — any other length makes mpf.from_root
                // trap on every subsequent transfer, i.e. it soft-bricks the token.
                if (root.length != 0 && root.length != 32) {
                    out.error = "UpdateMemberRootHash: newMemberRootHashHex must be empty or exactly "
                            + "32 bytes (64 hex characters), got " + root.length + " bytes. Any other "
                            + "length is rejected on chain because it would break every later transfer.";
                    return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(8, BytesPlutusData.of(root));
                out.newDatum = replaceGsField(f, GS_IDX_MEMBER_ROOT_HASH, BytesPlutusData.of(root));
            }
            case "RotateAdmin" -> {
                // 28 bytes is enforced on chain too
                // (global_state.ak: bytearray.length(new_admin_credential_hash) == 28).
                byte[] newAdminBytes = requireHexOfLength(
                        "RotateAdmin: newAdminCredentialHashHex",
                        change.newAdminCredentialHashHex(), 28);
                PlutusData newAdmin = BytesPlutusData.of(newAdminBytes);
                out.actionRedeemer = ConstrPlutusData.of(9, newAdmin);
                out.newDatum = replaceGsField(f, GS_IDX_ADMIN_CREDENTIAL_HASH, newAdmin);
            }
            case "DeactivateContract" -> {
                // D3. Constructor 10, and NULLARY — `DeactivateContract` carries no
                // fields (lib/types/global_state.ak:358), so the redeemer is a bare
                // Constr 10 [].
                //
                // Two on-chain preconditions (global_state.ak:451-466), both checked
                // here so the operator learns which one they tripped:
                //   1. input_datum.transfers_paused must ALREADY be true. Note this
                //      reads the datum being SPENT, so a chain of
                //      [PauseTransfers, DeactivateContract] satisfies it — the
                //      orchestrator rolls `currentDatum` forward between steps.
                //   2. the admin must sign (handled by the caller's required signers).
                //
                // The deactivated==true guard at the top of this method covers the
                // "already deactivated" case.
                if (!boolFromConstr(f.get(GS_IDX_TRANSFERS_PAUSED))) {
                    out.error = "DeactivateContract requires transfers to be paused first "
                            + "(global_state.ak: `input_datum.transfers_paused`). Submit a "
                            + "PauseTransfers change before it — the two can go in the same "
                            + "batch, since each transaction in the chain spends the previous "
                            + "one's global state.";
                    return out;
                }
                out.actionRedeemer = ConstrPlutusData.of(10);
                out.newDatum = replaceGsField(f, GS_IDX_DEACTIVATED, boolToConstr(true));
            }
            default -> {
                out.error = "unknown action: " + change.action();
                return out;
            }
        }
        return out;
    }

    /** Build a single admin-signed GS spend tx. Continuing GS at output 0 by
     *  way of the preBalanceTx swap; change at output 1.
     *
     *  <p>{@code refInputs} carries any reference-input UTxOs the action's
     *  on-chain branch needs to look up. For PauseTransfers this is the
     *  admin's power-user node (the validator does
     *  {@code safe_list_at(self.reference_inputs, power_user_node_ref_input_index)}
     *  and fails with EmptyList without it). Pass an empty list for actions
     *  that are admin-signature-only.
     *
     *  <p>{@code requiredSignerKeyHashes} is every credential the action's on-chain
     *  branch demands in {@code extra_signatories}. It is usually just the admin,
     *  but {@code RotateAdmin} needs BOTH the outgoing and the incoming admin
     *  ({@code global_state.ak}: two {@code must_be_signed_by_credential} calls). */
    private TransactionContext<Void> buildSingleGsUpdateTx(
            SecurityTokenRegistrationEntity reg,
            PlutusScript gsSpendScript,
            Address gsSpendAddress,
            Utxo gsUtxo,
            PlutusData actionRedeemer,
            PlutusData newGsDatum,
            Utxo funding,
            String feePayerAddress,
            List<byte[]> requiredSignerKeyHashes,
            List<Utxo> refInputs) {
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

            if (refInputs != null && !refInputs.isEmpty()) {
                TransactionInput[] refTxInputs = refInputs.stream()
                        .map(u -> TransactionInput.builder()
                                .transactionId(u.getTxHash())
                                .index(u.getOutputIndex())
                                .build())
                        .toArray(TransactionInput[]::new);
                tx = tx.readFrom(refTxInputs);
            }

            Transaction transaction = quickTxBuilder.compose(tx)
                    .withRequiredSigners(requiredSignerKeyHashes.toArray(byte[][]::new))
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
                            com.bloxbean.cardano.client.transaction.spec.TransactionOutput first = outs.removeFirst();
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
        List<Amount> amounts = new ArrayList<>();
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
     *  {@value #GS_DATUM_FIELD_COUNT}-field BaFin {@code GlobalStateDatum} from the
     *  inline datum.
     *
     *  <p>Used by the autonomous publisher's equality gate and by the admin UI's
     *  read-current-state view. */
    public Optional<GlobalStateData> readGlobalState(String policyId) {
        try {
            Optional<SecurityTokenRegistrationEntity> regOpt = registrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) return Optional.empty();
            String globalStatePolicyId = regOpt.get().getGlobalStatePolicyId();

            // Look up by exact (policy, asset_name) — see mint/burn flow comments
            // for why the policy-only helper can return the wrong asset.
            Optional<Utxo> utxoOpt = utxoProvider.findUtxoByAsset(
                    globalStatePolicyId,
                    SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX);
            if (utxoOpt.isEmpty()) return Optional.empty();
            Utxo utxo = utxoOpt.get();

            String datumHex = utxo.getInlineDatum();
            if (datumHex == null || datumHex.isBlank()) return Optional.empty();

            PlutusData data = PlutusData.deserialize(HexUtil.decodeHexString(datumHex));
            if (!(data instanceof ConstrPlutusData constr)) return Optional.empty();
            List<PlutusData> fields = constr.getData().getPlutusDataList();
            if (fields.size() != GS_DATUM_FIELD_COUNT) {
                log.warn("readGlobalState({}): GS datum has {} fields, expected {} — "
                        + "this global state predates the upstream contract pin (fn-bafin-cardano-sc "
                        + "@7ae4ce3) and its flag fields are at different indices; refusing to read it",
                        policyId, fields.size(), GS_DATUM_FIELD_COUNT);
                return Optional.empty();
            }

            // BaFin GlobalStateDatum field order — see
            // src/substandards/security-token/lib/types/global_state.ak.
            boolean transfersPaused = boolFromConstr(fields.get(GS_IDX_TRANSFERS_PAUSED));
            long mintableAmount = intFrom(fields.get(GS_IDX_MINTABLE_AMOUNT));
            String adminCredentialHash = bytesHexFrom(fields.get(GS_IDX_ADMIN_CREDENTIAL_HASH));
            // GS_IDX_POWER_USER_LL_POLICY / GS_IDX_DENYLIST_LL_POLICY: not surfaced
            String securityInfoHex = serializeHex(fields.get(GS_IDX_SECURITY_INFO));
            List<String> trustedEntityVkeys = trustedEntitiesFrom(fields.get(GS_IDX_TRUSTED_ENTITY_VKEYS));
            String memberRootHash = bytesHexFrom(fields.get(GS_IDX_MEMBER_ROOT_HASH));
            boolean requiresSenderKyc = boolFromConstr(fields.get(GS_IDX_REQUIRES_SENDER_KYC));
            boolean requiresReceiverKyc = boolFromConstr(fields.get(GS_IDX_REQUIRES_RECEIVER_KYC));
            long networkId = intFrom(fields.get(GS_IDX_NETWORK_ID));
            boolean deactivated = boolFromConstr(fields.get(GS_IDX_DEACTIVATED));

            return Optional.of(new GlobalStateData(
                    policyId,
                    transfersPaused,
                    mintableAmount,
                    trustedEntityVkeys,
                    securityInfoHex,
                    memberRootHash,
                    requiresReceiverKyc,
                    adminCredentialHash,
                    requiresSenderKyc,
                    networkId,
                    deactivated));
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
        ArrayList<String> out = new ArrayList<String>();
        if (d instanceof MapPlutusData m) {
            m.getMap().forEach((k, v) -> {
                if (k instanceof BytesPlutusData bk) out.add(HexUtil.encodeHexString(bk.getValue()));
            });
        } else if (d instanceof ListPlutusData l) {
            for (PlutusData entry : l.getPlutusDataList()) {
                if (entry instanceof ConstrPlutusData ec) {
                    List<PlutusData> pair = ec.getData().getPlutusDataList();
                    if (!pair.isEmpty() && pair.get(0) instanceof BytesPlutusData bk) {
                        out.add(HexUtil.encodeHexString(bk.getValue()));
                    }
                }
            }
        }
        return out;
    }

    // ── Shared helpers for mint / burn tx construction ─────────────────────
    //
    // Mint and burn build the same kind of MintSecurity GS-spend tx with the
    // same set of parameterised scripts and ref inputs — only the sign of the
    // minted amount, the GS datum's mintable_amount delta, and the input/output
    // shape differ. These helpers collapse the otherwise-duplicated setup so
    // the two entry points stay focused on their genuine differences.

    /** Bundle of parameterised scripts + addresses every MintSecurity-style tx needs. */
    private record MintLikeScripts(
            PlutusScript mintingLogic,
            PlutusScript issuanceContract,
            PlutusScript gsSpend,
            Address gsSpendAddress,
            Address mintingLogicRewardAddress) {}

    /** Thrown by build-helpers when a precondition isn't met. The outer
     *  build* method's catch surfaces the message verbatim via
     *  {@link TransactionContext#typedError(String)} so callers see the same
     *  user-facing error as the original inline checks. */
    private static final class BuildPreconditionException extends RuntimeException {
        BuildPreconditionException(String message) { super(message); }
    }

    private MintLikeScripts buildMintLikeScripts(
            SecurityTokenRegistrationEntity reg, ProtocolBootstrapParams protocolParams)
            throws com.bloxbean.cardano.client.exception.CborSerializationException {
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
        return new MintLikeScripts(
                mintingLogicScript, issuanceContract, gsSpendScript,
                gsSpendAddress, mintingLogicRewardAddress);
    }

    /** Look up the GS UTxO by exact (policy, asset_name) and validate it has
     *  an inline datum. Policy-only lookup is unreliable once the CIP-113
     *  directory has entries under the same policy id. */
    private Utxo findGsUtxo(SecurityTokenRegistrationEntity reg) {
        Utxo gsUtxo = utxoProvider.findUtxoByAsset(
                reg.getGlobalStatePolicyId(),
                SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX
        ).orElseThrow(() -> new BuildPreconditionException(
                "GS NFT not found on chain — has the registration tx confirmed?"));
        if (gsUtxo.getInlineDatum() == null || gsUtxo.getInlineDatum().isBlank()) {
            throw new BuildPreconditionException("GS UTxO is missing its inline datum");
        }
        return gsUtxo;
    }

    /** Deserialize + validate the {@value #GS_DATUM_FIELD_COUNT}-field BaFin
     *  GlobalStateDatum, returning its fields. Throws
     *  {@link BuildPreconditionException} with a user-visible message when the
     *  datum shape is wrong.
     *
     *  <p>The count is checked for EQUALITY, not a lower bound: a 9-field datum
     *  is a pre-@7ae4ce3 global state whose field 8 is {@code requires_receiver_kyc}
     *  rather than {@code requires_sender_kyc}. Accepting it would read and
     *  rewrite the wrong flag silently. Such a deployment must be re-bootstrapped. */
    private static List<PlutusData> parseGsFields(Utxo gsUtxo) {
        try {
            PlutusData datum = PlutusData.deserialize(
                    HexUtil.decodeHexString(gsUtxo.getInlineDatum()));
            if (!(datum instanceof ConstrPlutusData constr)) {
                throw new BuildPreconditionException("GS datum is not a Constr");
            }
            List<PlutusData> fields = constr.getData().getPlutusDataList();
            if (fields.size() != GS_DATUM_FIELD_COUNT) {
                throw new BuildPreconditionException(
                        "GS datum has " + fields.size() + " fields, expected " + GS_DATUM_FIELD_COUNT
                        + " — this global state predates the upstream contract pin "
                        + "(fn-bafin-cardano-sc @7ae4ce3) and must be re-bootstrapped");
            }
            return fields;
        } catch (BuildPreconditionException bpe) {
            throw bpe;
        } catch (Exception e) {
            throw new BuildPreconditionException("could not parse GS datum: " + e.getMessage());
        }
    }

    /** Look up a power-user's linked-list node by signer PKH. The node NFT has
     *  asset name {@code "Node" ++ signerPkh}; we look up by exact
     *  (policy, assetName) because the PU policy also mints a root NFT with
     *  empty asset name. {@code role} appears in the error message
     *  ("admin" / "burner") when the node isn't found. */
    /** Locate a denylist linked-list element that <em>covers</em> {@code targetPkh} —
     *  i.e. witnesses that the key is absent from the list.
     *
     *  <p>Required at @e69c66a by every mint destination
     *  ({@code verify_mint_destinations}) as well as by the transfer and third-party
     *  paths. A node covers the target iff its own key is strictly below it and its
     *  link is strictly above it, with the Root counting as −∞ and a missing link as
     *  +∞ ({@code lib/denylist/absence.ak: covers_key}). The Root of an empty list
     *  therefore covers every key, which is the common case here.
     *
     *  <p>This deliberately picks the covering element rather than always the root:
     *  once anything is denylisted the root's link stops being {@code None} and the
     *  root only covers keys below the first entry.
     *
     *  <p>CAVEAT, deliberately not papered over: for a NON-root covering node the
     *  vendored {@code aiken-design-patterns} linked-list appears to strip the
     *  {@code "Node"} prefix from the link twice ({@code linked-list.ak} writes the
     *  unprefixed key on insert, then {@code get_element_info} drops 4 more bytes),
     *  so the on-chain upper-bound comparison would see a truncated successor key.
     *  Untested — the substandard's own fixtures only ever use root/tail elements
     *  with {@code link: None}. Verify against a populated denylist before relying on
     *  a mid-list covering node. */
    private Utxo findDenylistCoveringNode(SecurityTokenRegistrationEntity reg, byte[] targetPkh) {
        String denylistPolicy = reg.getDenylistPolicyId();
        if (denylistPolicy == null || denylistPolicy.isBlank()) {
            throw new BuildPreconditionException(
                    "registration row has no denylist policy id — re-run the genesis init step");
        }
        PlutusScript denylistSpendScript = scriptBuilder.buildDenylistSpendScript(denylistPolicy);
        String denylistAddress = AddressProvider.getEntAddress(
                denylistSpendScript, network.getCardanoNetwork()).getAddress();
        List<Utxo> elements = utxoProvider.findUtxos(denylistAddress);
        if (elements.isEmpty()) {
            throw new BuildPreconditionException(
                    "no denylist linked-list elements found at " + denylistAddress
                    + " — the denylist root is created at genesis, so this token needs re-bootstrapping");
        }
        // Element identity comes from the NFT asset name: "" = Root, "Node"++key.
        Utxo best = null;
        for (Utxo u : elements) {
            String assetName = u.getAmount().stream()
                    .map(a -> AssetType.fromUnit(a.getUnit()))
                    .filter(at -> denylistPolicy.equals(at.policyId()))
                    .map(AssetType::assetName)
                    .findFirst().orElse(null);
            if (assetName == null) continue;
            boolean isRoot = assetName.isEmpty();
            byte[] key = isRoot ? null : HexUtil.decodeHexString(assetName.substring(LL_NODE_KEY_PREFIX.length * 2));
            // covers_from_below: Root is -inf, otherwise key < target.
            if (key != null && compareUnsigned(key, targetPkh) >= 0) continue;
            // The link lives in the Element datum; absence of a link is +inf. We do not
            // decode it here — instead prefer the greatest key strictly below the target,
            // which is exactly the covering element the list invariant guarantees.
            if (best == null) { best = u; continue; }
            String bestName = best.getAmount().stream()
                    .map(a -> AssetType.fromUnit(a.getUnit()))
                    .filter(at -> denylistPolicy.equals(at.policyId()))
                    .map(AssetType::assetName).findFirst().orElse("");
            byte[] bestKey = bestName.isEmpty()
                    ? null : HexUtil.decodeHexString(bestName.substring(LL_NODE_KEY_PREFIX.length * 2));
            if (bestKey == null || (key != null && compareUnsigned(bestKey, key) < 0)) best = u;
        }
        if (best == null) {
            throw new BuildPreconditionException(
                    "no denylist element covers " + HexUtil.encodeHexString(targetPkh)
                    + " — the address appears to BE denylisted, so this operation is refused");
        }
        return best;
    }

    /** Lexicographic unsigned byte comparison, matching Plutus'
     *  {@code less_than_bytearray} (which compares bytes as unsigned). */
    private static int compareUnsigned(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xff) - (b[i] & 0xff);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    private Utxo findPuNode(SecurityTokenRegistrationEntity reg, byte[] signerPkh, String role) {
        byte[] nodeAssetName = concat(LL_NODE_KEY_PREFIX, signerPkh);
        String nodeAssetNameHex = HexUtil.encodeHexString(nodeAssetName);
        return utxoProvider.findUtxoByAsset(
                reg.getPowerUsersPolicyId(), nodeAssetNameHex
        ).orElseThrow(() -> new BuildPreconditionException(
                role + " power-user node not found on chain — "
                + HexUtil.encodeHexString(signerPkh) + " may not be a registered power user "
                + "(asset: " + reg.getPowerUsersPolicyId() + "/" + nodeAssetNameHex + ")"));
    }

    /** The linked-list node key a {@code run_element_with} call would derive for this
     *  element: the element NFT's asset name with the {@code "Node"} prefix stripped.
     *
     *  <p>This — not the credential hash inside the datum — is what
     *  {@code must_be_signed_by_credential(self, power_user_node_key)} checks against
     *  (the two agree for every node this platform writes, but the asset name is the
     *  authority). */
    private static byte[] linkedListNodeKey(Utxo element, String policyId) {
        String assetName = element.getAmount().stream()
                .map(a -> AssetType.fromUnit(a.getUnit()))
                .filter(at -> policyId.equals(at.policyId()))
                .map(AssetType::assetName)
                .findFirst()
                .orElseThrow(() -> new BuildPreconditionException(
                        "linked-list element " + element.getTxHash() + "#" + element.getOutputIndex()
                        + " carries no NFT under policy " + policyId));
        int prefixHexLen = LL_NODE_KEY_PREFIX.length * 2;
        if (assetName.length() <= prefixHexLen) {
            throw new BuildPreconditionException(
                    "linked-list element " + element.getTxHash() + "#" + element.getOutputIndex()
                    + " is the ROOT (empty asset name), not a Node — it has no key");
        }
        return HexUtil.decodeHexString(assetName.substring(prefixHexLen));
    }

    /** Re-derive every index the registration-with-mint redeemers carry from the
     *  FINISHED transaction and report the first disagreement, or null when all
     *  agree.
     *
     *  <p>The indices are computed before {@code build()} from the input set we chose,
     *  but the balancer may add inputs (which shifts the lex-sorted positions) or
     *  reshape outputs. On the structural path a wrong index merely fails; here it
     *  would point a live validator at the wrong UTxO, so it is checked rather than
     *  hoped for. */
    private static String verifyRegistrationMintIndices(
            Transaction tx, Utxo gsUtxo, Utxo puNode, Utxo denylistNode,
            int expectedGsInputIdx, int expectedPuRefIdx, int expectedDenylistRefIdx,
            int expectedRegistryNodeOutputIdx, String progTokenPolicyId,
            int expectedIssuanceRedeemerIdx, PlutusData issuanceRedeemer) {
        // global_state.ak's MintSecurity branch does
        //   expect self.redeemers[issuance_policy_redeemer_index].1st == Mint(issuance_policy_id)
        // and self.redeemers is ordered by (tag, index) with Spend before Mint. So the
        // issuance Mint redeemer's global position is spendCount + its own mint index,
        // where that index is the policy's rank in the ledger-sorted mint field.
        // Re-derive it from the finished witness set rather than trusting the
        // lexicographic guess made before build().
        List<Redeemer> redeemers = tx.getWitnessSet() != null
                ? tx.getWitnessSet().getRedeemers() : null;
        if (redeemers == null || redeemers.isEmpty()) {
            return "the built transaction carries no redeemers";
        }
        String issuanceRedeemerHex;
        try {
            issuanceRedeemerHex = issuanceRedeemer.serializeToHex();
        } catch (Exception e) {
            return "could not re-serialise the issuance redeemer: " + e.getMessage();
        }
        long spendCount = redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Spend).count();
        Integer issuanceMintIndex = null;
        for (Redeemer r : redeemers) {
            if (r.getTag() != RedeemerTag.Mint) continue;
            try {
                if (issuanceRedeemerHex.equals(r.getData().serializeToHex())) {
                    issuanceMintIndex = r.getIndex().intValue();
                }
            } catch (Exception ignored) {
                // A redeemer we cannot re-serialise is not the one we are looking for.
            }
        }
        if (issuanceMintIndex == null) {
            return "the issuance mint redeemer is missing from the built transaction";
        }
        int actualIssuanceRedeemerIdx = (int) spendCount + issuanceMintIndex;
        if (actualIssuanceRedeemerIdx != expectedIssuanceRedeemerIdx) {
            return "MintSecurity's issuance_policy_redeemer_index is " + expectedIssuanceRedeemerIdx
                   + " but the issuance mint redeemer sits at position " + actualIssuanceRedeemerIdx
                   + " of self.redeemers (" + spendCount + " spend redeemer(s), mint index "
                   + issuanceMintIndex + ")";
        }
        List<TransactionInput> inputs = new ArrayList<>(tx.getBody().getInputs());
        inputs.sort(new TransactionInputComparator());
        int actualGsIdx = inputs.indexOf(txInputOf(gsUtxo));
        if (actualGsIdx != expectedGsInputIdx) {
            return "global_state_input_index is " + expectedGsInputIdx
                   + " but GlobalState landed at input " + actualGsIdx
                   + " (" + inputs.size() + " inputs after balancing)";
        }
        List<TransactionInput> refs = new ArrayList<>(
                tx.getBody().getReferenceInputs() != null
                        ? tx.getBody().getReferenceInputs() : List.of());
        refs.sort(new TransactionInputComparator());
        int actualPuIdx = refs.indexOf(txInputOf(puNode));
        if (actualPuIdx != expectedPuRefIdx) {
            return "power_user_node_ref_input_index is " + expectedPuRefIdx
                   + " but the power-user node landed at reference input " + actualPuIdx;
        }
        int actualDenylistIdx = refs.indexOf(txInputOf(denylistNode));
        if (actualDenylistIdx != expectedDenylistRefIdx) {
            return "the destination action's denylist covering index is " + expectedDenylistRefIdx
                   + " but the covering node landed at reference input " + actualDenylistIdx;
        }
        List<TransactionOutput> outs = tx.getBody().getOutputs();
        if (outs.size() < expectedRegistryNodeOutputIdx + 2) {
            return "expected at least " + (expectedRegistryNodeOutputIdx + 2)
                   + " outputs (prog-token, covering node, new node, GlobalState) but found "
                   + outs.size();
        }
        boolean progTokenAtZero = outs.getFirst().getValue().getMultiAssets() != null
                && outs.getFirst().getValue().getMultiAssets().stream()
                        .anyMatch(ma -> progTokenPolicyId.equals(ma.getPolicyId()));
        if (!progTokenAtZero) {
            return "output 0 does not carry the freshly minted programmable token";
        }
        if (!outs.get(expectedRegistryNodeOutputIdx + 1).getAddress().equals(gsUtxo.getAddress())) {
            return "global_state_output_index is " + (expectedRegistryNodeOutputIdx + 1)
                   + " but output " + (expectedRegistryNodeOutputIdx + 1)
                   + " is not at the GlobalState spend address";
        }
        return null;
    }

    /** The five on-chain {@code PowerUser} capability flags
     *  ({@code lib/types/power_users.ak:11-24}), decoded from a linked-list node's
     *  inline datum. */
    record PowerUserCaps(byte[] credentialHash, boolean isAdmin, boolean canMint,
                         boolean canBurn, boolean canPause, boolean canForceTransfer) {}

    /** Decode a power-user linked-list node's inline datum into its capability flags.
     *
     *  <p>The node datum is {@code linked_list.Element}:
     *  {@code Constr 0 [ Constr 1 [PowerUser], link ]} (constructor 1 = Node,
     *  constructor 0 = Root — see {@link #linkedListElement}). The inner
     *  {@code PowerUser} is {@code Constr 0 [credential_hash, is_admin, can_mint,
     *  can_burn, can_pause, can_force_transfer]}, the exact inverse of
     *  {@link #powerUserData}.
     *
     *  <p>Reading the flags off chain lets the builder refuse a transaction the
     *  validator would reject anyway, with an actionable message instead of an
     *  evaluator trap. */
    private static PowerUserCaps parsePowerUser(Utxo puNode) {
        String datumHex = puNode.getInlineDatum();
        if (datumHex == null || datumHex.isBlank()) {
            throw new BuildPreconditionException(
                    "power-user node " + puNode.getTxHash() + "#" + puNode.getOutputIndex()
                    + " has no inline datum");
        }
        PlutusData element;
        try {
            element = PlutusData.deserialize(HexUtil.decodeHexString(datumHex));
        } catch (Exception e) {
            throw new BuildPreconditionException(
                    "could not decode power-user node datum: " + e.getMessage());
        }
        if (!(element instanceof ConstrPlutusData elementConstr)
                || elementConstr.getData().getPlutusDataList().isEmpty()) {
            throw new BuildPreconditionException("power-user node datum is not a linked_list.Element");
        }
        PlutusData nodeWrapper = elementConstr.getData().getPlutusDataList().get(0);
        if (!(nodeWrapper instanceof ConstrPlutusData nodeConstr)
                || nodeConstr.getAlternative() != 1
                || nodeConstr.getData().getPlutusDataList().isEmpty()) {
            throw new BuildPreconditionException(
                    "power-user linked-list element is the ROOT, not a Node — no capabilities to read");
        }
        PlutusData powerUser = nodeConstr.getData().getPlutusDataList().get(0);
        if (!(powerUser instanceof ConstrPlutusData puConstr)) {
            throw new BuildPreconditionException("power-user node payload is not a PowerUser Constr");
        }
        List<PlutusData> f = puConstr.getData().getPlutusDataList();
        if (f.size() != 6 || !(f.get(0) instanceof BytesPlutusData credHash)) {
            throw new BuildPreconditionException(
                    "PowerUser datum has " + f.size() + " fields, expected 6 "
                    + "(credential_hash + 5 capability flags)");
        }
        return new PowerUserCaps(credHash.getValue(),
                boolFromConstr(f.get(1)), boolFromConstr(f.get(2)), boolFromConstr(f.get(3)),
                boolFromConstr(f.get(4)), boolFromConstr(f.get(5)));
    }

    /** The caller's identity for power-user purposes: the payment credential of the
     *  address that pays for (and therefore signs) the transaction. This is what
     *  {@code withRequiredSigners} puts into {@code extra_signatories}, so it is the
     *  only key {@code must_be_signed_by_credential} can match — which is why the
     *  power-user node must be looked up under it rather than under the registration
     *  row's genesis admin PKH (D6). */
    private static byte[] callerPaymentCredential(String callerAddress) {
        if (callerAddress == null || callerAddress.isBlank()) {
            throw new BuildPreconditionException("feePayerAddress is required");
        }
        return new Address(callerAddress).getPaymentCredentialHash()
                .orElseThrow(() -> new BuildPreconditionException(
                        "feePayerAddress has no payment credential: " + callerAddress));
    }

    /** Look up the CIP-113 directory entry for the given prog-token policy by
     *  walking the directory-spend address and filtering on parsed
     *  {@code RegistryNode.key()}. */
    private Utxo findDirectoryEntry(String tokenPolicyId, ProtocolBootstrapParams protocolParams) {
        PlutusScript directorySpendContract = protocolScriptBuilderService
                .getParameterizedDirectorySpendScript(protocolParams);
        Address directorySpendAddress = AddressProvider.getEntAddress(
                directorySpendContract, network.getCardanoNetwork());
        List<Utxo> registryEntries = utxoProvider.findUtxos(directorySpendAddress.getAddress());
        return registryEntries.stream()
                .filter(u -> registryNodeParser.parse(u.getInlineDatum())
                        .map(node -> tokenPolicyId.equals(node.key()))
                        .orElse(false))
                .findAny()
                .orElseThrow(() -> new BuildPreconditionException(
                        "directory entry for policy " + tokenPolicyId + " not found"));
    }

    /** Resolve the CIP-113 protocol-params and issuance-params UTxOs from the
     *  bootstrap tx. Returns them in stable order: [protocolParams, issuance]. */
    private List<Utxo> findProtocolAndIssuanceUtxos(ProtocolBootstrapParams protocolParams) {
        String bootstrapTxHash = protocolParams.txHash();
        Utxo protocolParamsUtxo = utxoProvider.findUtxo(bootstrapTxHash, 0)
                .orElseThrow(() -> new BuildPreconditionException(
                        "could not resolve protocol params UTxO at " + bootstrapTxHash + ":0"));
        Utxo issuanceUtxo = utxoProvider.findUtxo(bootstrapTxHash, 2)
                .orElseThrow(() -> new BuildPreconditionException(
                        "could not resolve issuance params UTxO at " + bootstrapTxHash + ":2"));
        return List.of(protocolParamsUtxo, issuanceUtxo);
    }

    /** Pick a single ADA-only funding UTxO at the fee-payer address. */
    private Utxo findFunding(String feePayerAddress, long minLovelace) {
        List<Utxo> fundingUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, minLovelace);
        if (fundingUtxos.isEmpty()) {
            throw new BuildPreconditionException("no funding UTxO at fee-payer address");
        }
        return fundingUtxos.getFirst();
    }

    /** Mutate only the {@code mintable_amount} field of the parsed GS fields
     *  by the given (signed) delta — positive for burn, negative for mint —
     *  and return the new {@value #GS_DATUM_FIELD_COUNT}-field Constr datum.
     *  All other fields are preserved verbatim. Throws if the delta would drive
     *  mintable_amount negative. */
    private static PlutusData applyMintableDelta(List<PlutusData> gsFields, long delta) {
        if (!(gsFields.get(GS_IDX_MINTABLE_AMOUNT) instanceof BigIntPlutusData bi)) {
            throw new BuildPreconditionException("GS datum field 1 (mintable_amount) is not an Int");
        }
        long current = bi.getValue().longValueExact();
        long updated = current + delta;
        if (updated < 0) {
            throw new BuildPreconditionException(
                    "mint quantity exceeds remaining mintable_amount (" + current + ")");
        }
        return replaceGsField(gsFields, GS_IDX_MINTABLE_AMOUNT, BigIntPlutusData.of(BigInteger.valueOf(updated)));
    }

    /** Rebuild the {@code GlobalStateDatum} Constr from an existing field list
     *  with exactly one field replaced — the Java mirror of upstream's
     *  {@code utils.replace_data_field}, which every {@code GlobalStateSpendAction}
     *  branch now uses to derive its expected output datum. Going through one
     *  helper (instead of re-listing all fields per action) is what keeps the
     *  off-chain side correct when the datum grows again: upstream compares the
     *  whole output datum with {@code builtin.equals_data}, so a dropped or
     *  reordered field silently invalidates the transaction. */
    private static PlutusData replaceGsField(List<PlutusData> gsFields, int index, PlutusData value) {
        if (gsFields.size() != GS_DATUM_FIELD_COUNT) {
            throw new BuildPreconditionException(
                    "GS datum has " + gsFields.size() + " fields, expected " + GS_DATUM_FIELD_COUNT);
        }
        List<PlutusData> out = new ArrayList<>(gsFields);
        out.set(index, value);
        return ConstrPlutusData.of(0, out.toArray(new PlutusData[0]));
    }

    /** Reproduce the GS UTxO's Value verbatim from its (lovelace + single NFT)
     *  amounts. The GS spend validator's {@code value_preserved} invariant
     *  requires the recreated output to match the input value exactly. */
    private static Value buildPreservedGsValue(Utxo gsUtxo, String gsPolicyId) {
        BigInteger gsLovelace = gsUtxo.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no lovelace"));
        Amount gsNftAmount = gsUtxo.getAmount().stream()
                .filter(a -> !"lovelace".equals(a.getUnit()))
                .findFirst().orElseThrow(() -> new IllegalStateException("GS UTxO has no NFT"));
        return Value.builder()
                .coin(gsLovelace)
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId(gsPolicyId)
                        .assets(List.of(Asset.builder()
                                .name("0x" + AssetType.fromUnit(gsNftAmount.getUnit()).assetName())
                                .value(BigInteger.ONE).build()))
                        .build()))
                .build();
    }

    /** preBalanceTx hook that moves a leading fee-payer change output to the
     *  END of the outputs list. Used so that explicitly-positioned outputs
     *  (the new GS UTxO, recipient prog-token, etc.) keep the indices their
     *  redeemers point at when Bloxbean's balancer prepends a change output. */
    private static com.bloxbean.cardano.client.function.TxBuilder
            moveLeadingChangeOutputToEnd(String feePayerAddress) {
        return (bctx, txn) -> {
            List<com.bloxbean.cardano.client.transaction.spec.TransactionOutput> outs =
                    txn.getBody().getOutputs();
            if (!outs.isEmpty() && outs.getFirst().getAddress().equals(feePayerAddress)) {
                com.bloxbean.cardano.client.transaction.spec.TransactionOutput first = outs.removeFirst();
                outs.addLast(first);
            }
        };
    }

    /** Read view of the on-chain global state datum surfaced to off-chain callers.
     *
     *  <p>{@code requiresSenderKyc}, {@code networkId} and {@code deactivated} were
     *  appended (rather than inserted in datum order) so existing positional callers
     *  keep working. */
    public record GlobalStateData(
            String policyId,
            boolean transfersPaused,
            long mintableAmount,
            java.util.List<String> trustedEntityVkeys,
            String securityInfoHex,
            String memberRootHash,
            boolean requiresReceiverKyc,
            String adminCredentialHash,
            boolean requiresSenderKyc,
            long networkId,
            /** Terminal decommissioning flag. Once true, {@code global_state.ak}
             *  rejects every spend of the GS UTxO, so no admin action, mint or burn
             *  can ever run again. */
            boolean deactivated
    ) {}
}
