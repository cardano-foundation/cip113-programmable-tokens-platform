package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.cardanofoundation.cip113.model.DummyRegisterRequest;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.substandard.DummySubstandardHandler;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Offline build + real phase-2 evaluation of the CIP-68 registration paths, driving the
 * <em>production</em> substandard handlers against a virtually-submitted protocol bootstrap.
 *
 * <p>Nothing is re-implemented here: the handler under test is the same
 * {@code DummySubstandardHandler} the REST layer uses, constructed by hand with offline
 * collaborators (see {@link HandlerFixtures}). The handler never sets an explicit tx evaluator,
 * so the {@code TransactionProcessor} injected into its {@link QuickTxBuilder} <em>is</em> the
 * evaluator — and ours runs the real aiken/uplc machine. A script that traps therefore fails the
 * build, rather than being papered over by {@code YaciConfiguration}'s ceiling fallback (which is
 * a Spring bean these tests never construct; see
 * {@link OfflineChain#CEILING_FALLBACK_MEM}).
 */
@Slf4j
public class OfflineCip68EvalTest {

    /** 12-byte base name, well inside the 28-byte budget the 4-byte CIP-67 label leaves. */
    private static final String BASE_ASSET_NAME_HEX =
            HexUtil.encodeHexString("OfflineToken".getBytes(StandardCharsets.UTF_8));

    /**
     * A well-formed 28-byte policy id standing in for a previously-initialised blacklist root.
     * freeze-and-seize's transfer script is parameterised by it; nothing in the registration
     * transaction dereferences it on chain, so a fixed value is sufficient here.
     */
    private static final String BLACKLIST_NODE_POLICY_ID =
            "b1acc115700000000000000000000000000000000000000000000000";

    private static final Cip68Metadata METADATA = new Cip68Metadata(
            "Offline Token",
            "A token registered and evaluated entirely offline",
            "OFFTK",
            6,
            "https://example.invalid/offline-token",
            "ipfs://bafkreialsoinvalidbutwellformedlookinglogohash");

    @Test
    public void bootstrapIsVirtuallySubmittableAndSpendable() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        log.info("bootstrap outputs ({} total):", boot.outputs().size());
        for (var utxo : boot.outputs()) {
            log.info("  [{}] {} amounts={} inlineDatum={} refScript={}",
                    utxo.getOutputIndex(), utxo.getAddress(), utxo.getAmount(),
                    utxo.getInlineDatum() == null ? "-" : utxo.getInlineDatum().length() / 2 + "B",
                    utxo.getReferenceScriptHash() == null ? "-" : utxo.getReferenceScriptHash());
        }

        Assertions.assertNotNull(boot.coordinationUtxo(), "coordination utxo must exist after submit");
        Assertions.assertNotNull(boot.registryOriginUtxo(), "registry origin node utxo must exist");
        Assertions.assertNotNull(boot.issuanceCborHexUtxo(), "issuance template utxo must exist");

        // Every handler resolves the protocol by INDEX — findProtocolAndIssuanceUtxos and the
        // dummy handler both hardcode bootstrapTxHash:0 and :2 — so the offline bootstrap's
        // output ordering has to match the real deployment's, not merely contain the right UTxOs.
        Assertions.assertEquals(0, boot.coordinationUtxo().getOutputIndex(),
                "handlers read the coordination UTxO at bootstrapTxHash:0");
        Assertions.assertEquals(2, boot.issuanceCborHexUtxo().getOutputIndex(),
                "handlers read the issuance template at bootstrapTxHash:2");

        var adminUtxos = chain.utxosAt(BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertFalse(adminUtxos.isEmpty(), "admin must still hold change + re-fragmented outputs");
        for (var u : adminUtxos) {
            Assertions.assertEquals(boot.params().txHash(), u.getTxHash(),
                    "every admin utxo must now come from the bootstrap tx (seeds were spent)");
        }
    }

    // ------------------------------------------------------------------ dummy substandard

    @Test
    public void dummyRegistrationWithCip68MintsThePairAndEvaluates() throws Exception {
        var result = runDummyRegistration(METADATA, "1000000");
        var tx = result.transaction();
        var label = "dummy/cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var policyId = result.issuancePolicyId();
        var tokens = Cip68Evidence.tokensOfPolicy(tx, policyId);
        log.info("[{}] issuance policy {} carries {} token(s):", label, policyId, tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] policy={} name={} label={} qty={}",
                    label, t.outputIndex(), t.policyId(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "CIP-68 registration must mint a PAIR under the issuance policy");

        var userToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_FT).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (333) user token found"));
        var refToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        // 1_000_000 is fungible, so the label must be (333) and NOT (222).
        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "a supply of 1000000 is fungible and must take the (333) label");
        Assertions.assertEquals(new BigInteger("1000000"), userToken.quantity());
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, userToken.baseNameHex(),
                "the user token's base name must survive labelling unchanged");

        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, refToken.baseNameHex(),
                "reference and user token must share a base name or the pair is unresolvable");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        // Both outputs must sit at a programmable_logic_base address with an INLINE stake
        // credential — core's no_escape applies to the reference token too, since it is itself
        // a programmable token.
        var plb = result.plbScriptHash();
        var recipientStake = stakeCredHex(BootstrapFixture.ALICE.baseAddress());
        var issuerStake = stakeCredHex(BootstrapFixture.ADMIN.baseAddress());

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, userToken.outputIndex(), plb, recipientStake);
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, refToken.outputIndex(), plb, issuerStake);

        // The reference token goes to the ISSUER, not the recipient — that is what lets the
        // issuer spend it later to rewrite the metadata.
        Assertions.assertNotEquals(userToken.outputIndex(), refToken.outputIndex(),
                "user and reference token must occupy different outputs");

        Cip68Evidence.assertDatumRoundTrip(label, tx, refToken.outputIndex(), METADATA);

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    @Test
    public void dummyRegistrationWithSingleUnitTakesTheNftLabel() throws Exception {
        var result = runDummyRegistration(METADATA, "1");
        var tx = result.transaction();
        var label = "dummy/cip68-nft";

        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        var userToken = tokens.stream().filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();

        log.info("[{}] user token name={} label={} qty={}",
                label, userToken.assetNameHex(), userToken.label(), userToken.quantity());

        // This is the 222-vs-333 boundary: quantity exactly 1 is non-fungible.
        Assertions.assertEquals(Cip68.LABEL_NFT, userToken.label().intValue(),
                "a supply of exactly 1 is non-fungible and must take the (222) label");
        Assertions.assertEquals(BigInteger.ONE, userToken.quantity());
    }

    @Test
    public void dummyRegistrationWithoutCip68IsUnchanged() throws Exception {
        var result = runDummyRegistration(null, "1000000");
        var tx = result.transaction();
        var label = "dummy/no-cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, result.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(1, tokens.size(),
                "without CIP-68 metadata exactly one asset must mint — no reference token");
        var only = tokens.getFirst();
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, only.assetNameHex(),
                "the asset name must be the bare base name, unlabelled");
        Assertions.assertNull(only.label(),
                "the non-CIP-68 path must not attach a CIP-67 label");

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, only.outputIndex(),
                result.plbScriptHash(), stakeCredHex(BootstrapFixture.ALICE.baseAddress()));

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    // ------------------------------------------------------------------ freeze-and-seize

    @Test
    public void freezeAndSeizeRegistrationWithCip68MintsThePairAndEvaluates() throws Exception {
        var withCip68 = runFreezeAndSeizeRegistration(METADATA, "1000000");
        var tx = withCip68.transaction();
        var label = "freeze-and-seize/cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = withCip68.chain().reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, withCip68.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, withCip68.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "CIP-68 registration must mint a PAIR under the issuance policy");

        var userToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_FT).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (333) user token found"));
        var refToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "a supply of 1000000 is fungible and must take the (333) label");
        Assertions.assertEquals(new BigInteger("1000000"), userToken.quantity());
        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        var plb = withCip68.plbScriptHash();
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, userToken.outputIndex(), plb,
                stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, refToken.outputIndex(), plb,
                stakeCredHex(BootstrapFixture.ADMIN.baseAddress()));

        Cip68Evidence.assertDatumRoundTrip(label, tx, refToken.outputIndex(), METADATA);

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);

        // The crux for this substandard: issuer_admin is parameterised by the asset NAME, so the
        // CIP-67 label participates in the script hash and therefore in the issuance policy id.
        var withoutCip68 = runFreezeAndSeizeRegistration(null, "1000000");
        log.info("[freeze-and-seize] policy WITH cip68    = {}", withCip68.issuancePolicyId());
        log.info("[freeze-and-seize] policy WITHOUT cip68 = {}", withoutCip68.issuancePolicyId());
        Assertions.assertNotEquals(withCip68.issuancePolicyId(), withoutCip68.issuancePolicyId(),
                "freeze-and-seize bakes the asset name into issuer_admin, so labelling the name"
                + " MUST change the issuance policy id — equal ids would mean the label never"
                + " reached the script parameter");
    }

    @Test
    public void freezeAndSeizeRegistrationWithoutCip68IsUnchanged() throws Exception {
        var result = runFreezeAndSeizeRegistration(null, "1000000");
        var tx = result.transaction();
        var label = "freeze-and-seize/no-cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = result.chain().reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, result.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(1, tokens.size(),
                "without CIP-68 metadata exactly one asset must mint — no reference token");
        var only = tokens.getFirst();
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, only.assetNameHex(),
                "the asset name must be the bare base name, unlabelled");
        Assertions.assertNull(only.label(), "the non-CIP-68 path must not attach a CIP-67 label");

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, only.outputIndex(),
                result.plbScriptHash(), stakeCredHex(BootstrapFixture.ALICE.baseAddress()));

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    // ------------------------------------------------------------------ security-token

    /**
     * security-token is the one substandard whose CIP-68 pair cannot be completed at
     * registration: {@code verify_token_registration} rejects more than one asset name minted
     * under the issuance policy, so the {@code (100)} token has to ride along with the first
     * {@code MintBurn}. That makes this a multi-transaction case — genesis, AddPowerUser and
     * registration must all be built and virtually submitted before the mint can be built.
     */
    @Test
    public void securityTokenChainAndCip68Mint() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var label = "security-token";

        var registrations = new java.util.HashMap<String,
                org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity>();

        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier().getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        Mockito.when(utxoProvider.findUtxoByAsset(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    String unit = inv.getArgument(0) + (String) inv.getArgument(1);
                    return chain.findUtxoByUnit(unit);
                });

        var registrationRepository =
                Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository.class);
        Mockito.when(registrationRepository.save(Mockito.any())).thenAnswer(inv -> {
            var entity = (org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity) inv.getArgument(0);
            registrations.put(entity.getProgrammableTokenPolicyId(), entity);
            return entity;
        });
        Mockito.when(registrationRepository.findByProgrammableTokenPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(registrations.get((String) inv.getArgument(0))));

        var substandardService = HandlerFixtures.substandardService();

        // The chain orchestrator builds genesis → AddPowerUser → registration back-to-back with
        // nothing submitted in between, handing each transaction's outputs to this supplier so
        // the next one can spend them. Production wires the QuickTxBuilder over the very same
        // object, so the test must too.
        var hybridUtxoSupplier =
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService());

        var handler = new org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler(
                new org.cardanofoundation.cip113.service.SecurityTokenScriptBuilderService(substandardService),
                HandlerFixtures.protocolScriptBuilderService(),
                Mockito.mock(org.cardanofoundation.cip113.service.SecurityTokenAllowlistService.class),
                registrationRepository,
                Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenDenylistEntryRepository.class),
                Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenPowerUserRepository.class),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                utxoProvider,
                new AccountService(utxoProvider),
                chain.quickTxBuilderOver(hybridUtxoSupplier),
                chain.protocolParamsSupplier(),
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                hybridUtxoSupplier,
                Mockito.mock(CustomStakeRegistrationRepository.class),
                Mockito.mock(org.cardanofoundation.conversions.CardanoConverters.class));

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var registerRequest = org.cardanofoundation.cip113.model.SecurityTokenRegisterRequest.builder()
                .substandardId("security-token")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity("0")                     // registration is structurally mint-free
                .cip68Metadata(METADATA)
                .adminPubKeyHash(adminPkh)
                .initialMintableAmount(1_000_000L) // the cap the (333)/(222) label is chosen from
                .bootstrapPowerUserPkh(adminPkh)
                .bootstrapPowerUserCapabilities(255)
                .bootstrapPowerUserLabel("admin")
                .requiresReceiverKyc(false)
                .requiresSenderKyc(false)
                .build();

        var chainResult = handler.buildFullRegistrationChain(registerRequest, boot.params());
        Assertions.assertTrue(chainResult.isSuccessful(),
                "security-token registration chain build failed: " + chainResult.error());

        var built = chainResult.metadata();
        log.info("[{}] chain built: globalStatePolicy={} progTokenPolicy={} denylistPolicy={}",
                label, built.globalStatePolicyId(), built.programmableTokenPolicyId(),
                built.denylistPolicyId());

        // Virtually submit the chain in order, so each tx sees the previous one's outputs.
        var stages = new java.util.LinkedHashMap<String, String>();
        stages.put("genesis", built.genesisCborHex());
        stages.put("addPowerUser", built.addPowerUserCborHex());
        stages.put("registration", built.registrationCborHex());
        if (built.registerTransferLogicCborHex() != null) {
            stages.put("registerTransferLogic", built.registerTransferLogicCborHex());
        }

        for (var stage : stages.entrySet()) {
            Assertions.assertNotNull(stage.getValue(), stage.getKey() + " cbor missing from chain result");
            var stageTx = Transaction.deserialize(HexUtil.decodeHexString(stage.getValue()));
            int evaluated = chain.reportAndCheckRedeemers(label + "/" + stage.getKey(), stageTx);
            log.info("[{}/{}] {} redeemer(s) genuinely evaluated, size={} bytes",
                    label, stage.getKey(), evaluated, stageTx.serialize().length);

            // registerTransferLogic's ONLY redeemer is the Cert one injected in postBalanceTx
            // with hand-picked ex-units, so it legitimately has nothing the evaluator could
            // measure. Every other stage must have really run its scripts.
            if (!"registerTransferLogic".equals(stage.getKey())) {
                Assertions.assertTrue(evaluated > 0,
                        stage.getKey() + " produced no genuinely evaluated redeemer");
            }
            Assertions.assertTrue(stageTx.serialize().length < 16384,
                    stage.getKey() + " exceeds 16384 bytes");
            chain.submit(stageTx);
        }

        // ── the mint: this is where security-token's CIP-68 pair is completed ──
        var mintRequest = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                built.programmableTokenPolicyId(),
                registrations.get(built.programmableTokenPolicyId()).getSecurityAssetNameHex(),
                "1000",
                BootstrapFixture.ALICE.baseAddress(),
                null,
                METADATA);

        var mintResult = handler.buildMintTransaction(mintRequest, boot.params());
        Assertions.assertTrue(mintResult.isSuccessful(),
                "security-token CIP-68 mint build failed: " + mintResult.error());

        var mintTx = Transaction.deserialize(HexUtil.decodeHexString(mintResult.unsignedCborTx()));
        Cip68Evidence.dumpOutputs(label + "/mint", mintTx);
        int evaluated = chain.reportAndCheckRedeemers(label + "/mint", mintTx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated on the mint");

        var policyId = built.programmableTokenPolicyId();
        var tokens = Cip68Evidence.tokensOfPolicy(mintTx, policyId);
        log.info("[{}/mint] issuance policy {} carries {} token(s):", label, policyId, tokens.size());
        for (var t : tokens) {
            log.info("[{}/mint]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "the first MintBurn must complete the CIP-68 pair: user token + (100) reference");

        var userToken = tokens.stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no user token found"));
        var refToken = tokens.stream()
                .filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        // The label was settled at GENESIS from initialMintableAmount (1_000_000 → fungible),
        // not from this mint's quantity, and is baked into the policy id.
        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "the genesis cap of 1000000 is fungible, so the user token must carry (333)");
        Assertions.assertEquals(new BigInteger("1000"), userToken.quantity());
        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        // Output layout is load-bearing here: gs_output_index = 1 is hardcoded in the GS spend
        // redeemer, so the reference token must be APPENDED at 2, never inserted ahead of it.
        Assertions.assertEquals(0, userToken.outputIndex(), "user token must be output 0");
        Assertions.assertEquals(2, refToken.outputIndex(),
                "the reference token must be APPENDED at output 2 — inserting it earlier would"
                + " shift the GlobalState output away from the hardcoded gs_output_index = 1");

        var plb = boot.params().programmableLogicBaseParams().scriptHash();
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/mint", mintTx, userToken.outputIndex(),
                plb, stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/mint", mintTx, refToken.outputIndex(),
                plb, stakeCredHex(BootstrapFixture.ADMIN.baseAddress()));

        Cip68Evidence.assertDatumRoundTrip(label + "/mint", mintTx, refToken.outputIndex(), METADATA);

        int size = mintTx.serialize().length;
        log.info("[{}/mint] transaction.serialize().length = {} bytes", label, size);
        Assertions.assertTrue(size < 16384, "mint tx exceeds 16384 bytes: " + size);
    }

    // ------------------------------------------------------------------ plumbing

    private record DummyResult(OfflineChain chain,
                               Transaction transaction,
                               String issuancePolicyId,
                               String plbScriptHash) {
    }

    private static OfflineChain chainOf(DummyResult result) {
        return result.chain();
    }

    /**
     * Drive the real {@link org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler}
     * through one registration on a fresh offline protocol.
     *
     * <p>Unlike dummy, this handler reaches the chain through {@code UtxoProvider} rather than a
     * yaci-store repository, so that is the seam the offline chain is spliced into.
     */
    private FesResult runFreezeAndSeizeRegistration(Cip68Metadata metadata, String quantity) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier()
                        .getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));

        var substandardService = HandlerFixtures.substandardService();

        var handler = new org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                Mockito.mock(org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser.class),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxoProvider),
                substandardService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(substandardService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(
                        Mockito.mock(com.bloxbean.cardano.client.backend.api.UtxoService.class)),
                Mockito.mock(org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository.class),
                blacklistInitRepository(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                Mockito.mock(CustomStakeRegistrationRepository.class),
                utxoProvider,
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class));

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var request = org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                .substandardId("freeze-and-seize")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity(quantity)
                .cip68Metadata(metadata)
                .adminPubKeyHash(adminPkh)
                .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                .build();

        var context = handler.buildRegistrationTransaction(request, boot.params());

        Assertions.assertTrue(context.isSuccessful(),
                "freeze-and-seize registration build failed: " + context.error());
        Assertions.assertNotNull(context.unsignedCborTx(), "no cbor returned");

        var tx = Transaction.deserialize(HexUtil.decodeHexString(context.unsignedCborTx()));
        var policyId = context.metadata().policyId();
        log.info("freeze-and-seize registration built: policy={} cip68={} quantity={}",
                policyId, metadata != null, quantity);

        return new FesResult(chain, tx, policyId,
                boot.params().programmableLogicBaseParams().scriptHash());
    }

    private record FesResult(OfflineChain chain,
                             Transaction transaction,
                             String issuancePolicyId,
                             String plbScriptHash) {
    }

    /**
     * The handler looks the blacklist-init row up AFTER the transaction is fully built and
     * balanced, and bails out if it is missing — so a present row is required to get the CBOR
     * back at all, even though nothing in the transaction depends on its contents.
     */
    @SuppressWarnings("unchecked")
    private static org.cardanofoundation.cip113.repository.BlacklistInitRepository blacklistInitRepository() {
        var repo = Mockito.mock(org.cardanofoundation.cip113.repository.BlacklistInitRepository.class);
        Mockito.when(repo.findByBlacklistNodePolicyId(Mockito.anyString()))
                .thenReturn(java.util.Optional.of(
                        Mockito.mock(org.cardanofoundation.cip113.entity.BlacklistInitEntity.class)));
        return repo;
    }

    private static String stakeCredHex(String baseAddress) {
        return new Address(baseAddress).getDelegationCredentialHash()
                .map(HexUtil::encodeHexString)
                .orElseThrow();
    }

    /**
     * Bootstrap a fresh offline protocol, then drive the real {@link DummySubstandardHandler}
     * through one registration on top of it.
     *
     * <p>A fresh chain per call is deliberate: the dummy substandard's issuance policy does NOT
     * depend on the asset name, so two registrations on one chain would collide on the handler's
     * "already registered" guard.
     */
    private DummyResult runDummyRegistration(Cip68Metadata metadata, String quantity) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var registrySpendHash = HexUtil.encodeHexString(boot.registrySpend().getScriptHash());
        var registryAddress = boot.registryOriginUtxo().getAddress();

        var handler = new DummySubstandardHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, registryAddress, registrySpendHash),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.substandardService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                Mockito.mock(CustomStakeRegistrationRepository.class));

        var request = DummyRegisterRequest.builder()
                .substandardId("dummy")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity(quantity)
                .cip68Metadata(metadata)
                .build();

        var context = handler.buildRegistrationTransaction(request, boot.params());

        Assertions.assertTrue(context.isSuccessful(),
                "dummy registration build failed: " + context.error());
        Assertions.assertNotNull(context.unsignedCborTx(), "no cbor returned");

        var tx = Transaction.deserialize(HexUtil.decodeHexString(context.unsignedCborTx()));
        var policyId = context.metadata().policyId();
        log.info("dummy registration built: policy={} cip68={} quantity={}",
                policyId, metadata != null, quantity);

        return new DummyResult(chain, tx, policyId,
                boot.params().programmableLogicBaseParams().scriptHash());
    }
}
