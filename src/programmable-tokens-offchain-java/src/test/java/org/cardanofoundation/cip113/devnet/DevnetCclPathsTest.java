package org.cardanofoundation.cip113.devnet;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.ccl.CclDeployments;
import org.cardanofoundation.cip113.ccl.CclQuickTx;
import org.cardanofoundation.cip113.ccl.CclWallets;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.BlacklistInitEntity;
import org.cardanofoundation.cip113.entity.FreezeAndSeizeTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.GlobalStateInitEntity;
import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.KycTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.BurnTokenRequest;
import org.cardanofoundation.cip113.model.DummyRegisterRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.TransferTokenRequest;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser;
import org.cardanofoundation.cip113.offline.BootstrapFixture;
import org.cardanofoundation.cip113.offline.HandlerFixtures;
import org.cardanofoundation.cip113.repository.BlacklistInitRepository;
import org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.GlobalStateInitRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.KycTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.ComplianceOperationsService;
import org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService;
import org.cardanofoundation.cip113.service.HybridScriptSupplier;
import org.cardanofoundation.cip113.service.HybridUtxoSupplier;
import org.cardanofoundation.cip113.service.KycExtendedScriptBuilderService;
import org.cardanofoundation.cip113.service.KycScriptBuilderService;
import org.cardanofoundation.cip113.service.LinkedListService;
import org.cardanofoundation.cip113.service.ModuleService;
import org.cardanofoundation.cip113.service.MpfTreeService;
import org.cardanofoundation.cip113.service.ProtocolDeploymentResolver;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.cardanofoundation.cip113.service.TokenOperationsService;
import org.cardanofoundation.cip113.service.UtxoProvider;
import org.cardanofoundation.cip113.service.module.DummyModuleHandler;
import org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler;
import org.cardanofoundation.cip113.service.module.KycExtendedModuleHandler;
import org.cardanofoundation.cip113.service.module.KycModuleHandler;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext;
import org.cardanofoundation.cip113.service.module.context.KycContext;
import org.cardanofoundation.cip113.service.module.context.KycExtendedContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every CIP-113 operation the handlers build with cardano-client-lib, submitted to a devnet
 * through the REST layer's services ({@link TokenOperationsService}, {@link ComplianceOperationsService}).
 *
 * <p>Skipped unless {@code CARDANO_BACKEND_URL} is set. Each run deploys a fresh protocol
 * ({@link DevnetProtocolGenesis}) so registrations never collide with an earlier run:
 * <pre>
 *   yaci-devkit up --enable-yaci-store
 *   CARDANO_BACKEND_URL='http://[::1]:8080/api/v1/' \
 *     ./gradlew test --tests '*DevnetCclPathsTest*' --rerun
 * </pre>
 *
 * <p>Builds use the plain Aiken evaluator, not the platform's ceiling-cost fallback, so a wrong
 * redeemer fails here instead of being priced and passed on to the ledger.
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "CARDANO_BACKEND_URL", matches = ".+")
class DevnetCclPathsTest {

    private static final String BACKEND_URL = System.getenv("CARDANO_BACKEND_URL");
    private static final long CONFIRM_TIMEOUT_MS = 60_000;
    /** The default Yaci devnet translates slots only 5 minutes ahead (3k/f); stay inside it. */
    private static final long DEVNET_VALIDITY_MS = 2 * 60 * 1000L;

    private final String admin = BootstrapFixture.ADMIN.baseAddress();
    private final String alice = BootstrapFixture.ALICE.baseAddress();

    private BFBackendService backend;
    private ProtocolBootstrapParams record;
    private Cip113Deployment deployment;
    private TokenOperationsService facade;
    private ComplianceOperationsService compliance;
    private FreezeAndSeizeHandler freezeAndSeize;
    private KycModuleHandler kyc;
    private KycExtendedModuleHandler kycExtended;
    private final Map<String, BlacklistInitEntity> blacklistInitRows = new HashMap<>();
    private final Map<String, GlobalStateInitEntity> globalStateInitRows = new HashMap<>();
    private final Map<String, ProgrammableTokenRegistryEntity> registryRows = new HashMap<>();
    private String dummyPolicy;
    private String dummyUnit;

    @BeforeAll
    void deployAndWire() throws Exception {
        backend = new BFBackendService(BACKEND_URL, "devnet");
        DevnetProtocolGenesisTest.topUp(admin, 1000);
        DevnetProtocolGenesisTest.topUp(admin, 1000);
        DevnetProtocolGenesisTest.topUp(alice, 1000);
        DevnetProtocolGenesisTest.awaitFatUtxos(backend, admin, 2);
        var deployed = new DevnetProtocolGenesis().deploy(backend, BootstrapFixture.ADMIN, BootstrapFixture.NETWORK);
        record = deployed.record();
        deployment = deployed.deployment();

        var utxos = new HybridUtxoSupplier(backend.getUtxoService());
        var scripts = new HybridScriptSupplier(new DefaultScriptSupplier(backend.getScriptService()));
        var protocolParams = new DefaultProtocolParamsSupplier(backend.getEpochService());
        // Devnet time, as YaciConfiguration gives the platform's evaluator: 1 s slots from the
        // running node's systemStart. Without it a validity bound (kyc) is translated against the
        // wrong network and evaluation fails with SlotTooFarInThePast.
        var shelley = HandlerFixtures.OBJECT_MAPPER.readTree(new java.io.File(System.getProperty("user.home")
                + "/.yaci-cli/local-clusters/default/node/genesis/shelley-genesis.json"));
        long systemStartMs = java.time.Instant.parse(shelley.get("systemStart").asText()).toEpochMilli();
        var evaluator = new AikenTransactionEvaluator(utxos, protocolParams, scripts,
                new com.bloxbean.cardano.client.common.model.SlotConfig(1000, 0L, systemStartMs));

        ProtocolScriptBuilderService protocolScripts = HandlerFixtures.protocolScriptBuilderService();
        var network = new AppConfig.Network("devnet");
        var deployments = new CclDeployments(network, backend, protocolScripts);
        var cclQuickTx = new CclQuickTx(utxos, scripts, protocolParams, evaluator);

        // Shared, in-memory stand-ins for the database.
        var rows = inMemory(ProgrammableTokenRegistryRepository.class, registryRows,
                ProgrammableTokenRegistryEntity::getPolicyId, "findByPolicyId");
        var blacklistInits = inMemory(BlacklistInitRepository.class, blacklistInitRows,
                BlacklistInitEntity::getBlacklistNodePolicyId, "findByBlacklistNodePolicyId");
        var fesRegistrations = inMemory(FreezeAndSeizeTokenRegistrationRepository.class,
                new HashMap<String, FreezeAndSeizeTokenRegistrationEntity>(),
                FreezeAndSeizeTokenRegistrationEntity::getProgrammableTokenPolicyId, "findByProgrammableTokenPolicyId");
        var globalStateInits = inMemory(GlobalStateInitRepository.class, globalStateInitRows,
                GlobalStateInitEntity::getGlobalStatePolicyId, "findByGlobalStatePolicyId");
        var kycRegistrations = inMemory(KycTokenRegistrationRepository.class,
                new HashMap<String, KycTokenRegistrationEntity>(),
                KycTokenRegistrationEntity::getProgrammableTokenPolicyId, "findByProgrammableTokenPolicyId");
        var kycExtendedRegistrations = inMemory(KycExtendedTokenRegistrationRepository.class,
                new HashMap<String, KycExtendedTokenRegistrationEntity>(),
                KycExtendedTokenRegistrationEntity::getProgrammableTokenPolicyId, "findByProgrammableTokenPolicyId");
        // Yaci Store's account endpoint carries no registration state, so this stand-in answers
        // from what this run registered: the handlers note every certificate they add
        // (noteRegistrationAttempted), and the protocol is deployed fresh at the start of the run.
        var registered = java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        var stakeRegistrations = mock(ScriptRegistrationService.class);
        org.mockito.Mockito.doAnswer(inv -> registered.add(inv.getArgument(0))).when(stakeRegistrations)
                .noteRegistrationAttempted(anyString(), any());
        when(stakeRegistrations.isStakeAddressRegistered(anyString())).thenAnswer(inv -> registered.contains(inv.getArgument(0)));
        // dummy asks the indexer database whether the fee payer holds anything and whether its
        // policy is already in the directory: on a fresh deployment, yes and no.
        var indexer = mock(UtxoRepository.class);
        when(indexer.findUnspentByOwnerAddr(anyString(), any())).thenReturn(Optional.of(List.of(
                mock(com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity.class))));
        when(indexer.findUnspentByOwnerPaymentCredential(anyString(), any())).thenReturn(Optional.of(List.of()));
        // The member tree lives in the database; the test publishes its root on chain itself.
        var memberTree = mock(MpfTreeService.class);
        when(memberTree.containsValid(anyString(), any(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(true);

        ModuleService modules = HandlerFixtures.moduleService();
        var utxoProvider = new UtxoProvider(backend, null, null);
        // Admin transactions (blacklist, global state) are hand-built on the platform's builder.
        var quickTx = new QuickTxBuilder(utxos, protocolParams, scripts, new com.bloxbean.cardano.client.api.TransactionProcessor() {
            @Override public com.bloxbean.cardano.client.api.model.Result<String> submitTransaction(byte[] cbor) {
                throw new UnsupportedOperationException();
            }
            @Override public com.bloxbean.cardano.client.api.model.Result<List<com.bloxbean.cardano.client.api.model.EvaluationResult>>
                    evaluateTx(byte[] cbor, java.util.Set<com.bloxbean.cardano.client.api.model.Utxo> inputs)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                return evaluator.evaluateTx(cbor, inputs);
            }
        });
        var genesis = System.getProperty("user.home") + "/.yaci-cli/local-clusters/default/node/genesis/";
        var converters = new AppConfig().cardanoConverters("devnet", genesis + "byron-genesis.json",
                genesis + "shelley-genesis.json", new org.springframework.core.io.DefaultResourceLoader());
        var om = HandlerFixtures.OBJECT_MAPPER;

        Map<Class<?>, Object> real = new HashMap<>(Map.ofEntries(
                Map.entry(com.fasterxml.jackson.databind.ObjectMapper.class, om),
                Map.entry(AppConfig.Network.class, network),
                Map.entry(BlacklistNodeParser.class, new BlacklistNodeParser(om)),
                Map.entry(RegistryNodeParser.class, new RegistryNodeParser(om)),
                Map.entry(AccountService.class, adminAccountService(utxoProvider)),
                Map.entry(ModuleService.class, modules),
                Map.entry(ProtocolScriptBuilderService.class, protocolScripts),
                Map.entry(FreezeAndSeizeScriptBuilderService.class, new FreezeAndSeizeScriptBuilderService(modules)),
                Map.entry(KycScriptBuilderService.class, new KycScriptBuilderService(modules)),
                Map.entry(KycExtendedScriptBuilderService.class, new KycExtendedScriptBuilderService(modules)),
                Map.entry(LinkedListService.class, new LinkedListService(utxoProvider)),
                Map.entry(QuickTxBuilder.class, quickTx),
                Map.entry(com.bloxbean.cardano.client.api.ProtocolParamsSupplier.class, protocolParams),
                Map.entry(HybridUtxoSupplier.class, utxos),
                Map.entry(UtxoRepository.class, indexer),
                Map.entry(FreezeAndSeizeTokenRegistrationRepository.class, fesRegistrations),
                Map.entry(BlacklistInitRepository.class, blacklistInits),
                Map.entry(ProgrammableTokenRegistryRepository.class, rows),
                Map.entry(GlobalStateInitRepository.class, globalStateInits),
                Map.entry(KycTokenRegistrationRepository.class, kycRegistrations),
                Map.entry(KycExtendedTokenRegistrationRepository.class, kycExtendedRegistrations),
                Map.entry(MpfTreeService.class, memberTree),
                Map.entry(ScriptRegistrationService.class, stakeRegistrations),
                Map.entry(UtxoProvider.class, utxoProvider),
                Map.entry(BFBackendService.class, backend),
                Map.entry(org.cardanofoundation.conversions.CardanoConverters.class, converters),
                Map.entry(CclDeployments.class, deployments),
                Map.entry(CclQuickTx.class, cclQuickTx)));

        // The real handlers, as Spring builds them.
        var dummy = construct(DummyModuleHandler.class, real);
        freezeAndSeize = construct(FreezeAndSeizeHandler.class, real);
        kyc = construct(KycModuleHandler.class, real);
        kyc.setTransferValidityMs(DEVNET_VALIDITY_MS);
        kycExtended = construct(KycExtendedModuleHandler.class, real);
        kycExtended.setTransferValidityMs(DEVNET_VALIDITY_MS);

        // The factory hands out a context-bound handler, as ModuleHandlerFactory does.
        var handlers = mock(ModuleHandlerFactory.class);
        when(handlers.getHandler("dummy")).thenReturn(dummy);
        when(handlers.getHandler(eq("freeze-and-seize"), any())).thenAnswer(inv -> {
            freezeAndSeize.setContext(inv.getArgument(1));
            return freezeAndSeize;
        });
        when(handlers.getHandler(eq("kyc"), any())).thenAnswer(inv -> {
            kyc.setContext(inv.getArgument(1));
            return kyc;
        });
        when(handlers.getHandler(eq("kyc-extended"), any())).thenAnswer(inv -> {
            kycExtended.setContext(inv.getArgument(1));
            return kycExtended;
        });
        var resolver = mock(ProtocolDeploymentResolver.class);
        when(resolver.resolve(any())).thenReturn(record);
        real.put(ModuleHandlerFactory.class, handlers);
        real.put(ProtocolDeploymentResolver.class, resolver);
        facade = construct(TokenOperationsService.class, real);
        compliance = construct(ComplianceOperationsService.class, real);

        registerDummyStakeCredentials(modules);
    }

    /**
     * The legacy blacklist operations look the manager's UTxOs up by payment key hash, which only
     * the indexer database can answer; on devnet the manager is the admin, so answer from its address.
     */
    private AccountService adminAccountService(UtxoProvider utxoProvider) {
        var accounts = org.mockito.Mockito.spy(new AccountService(utxoProvider));
        org.mockito.Mockito.doAnswer(inv -> accounts.findAdaOnlyUtxo(admin, inv.getArgument(1)))
                .when(accounts).findAdaOnlyUtxoByPaymentPubKeyHash(org.mockito.ArgumentMatchers.eq(adminPkh()), any());
        return accounts;
    }

    /** Builds {@code type} through its constructor: {@code real} where given, a Mockito mock otherwise. */
    @SuppressWarnings("unchecked")
    private static <T> T construct(Class<T> type, Map<Class<?>, Object> real) throws Exception {
        var constructor = type.getConstructors()[0];
        Object[] args = Arrays.stream(constructor.getParameterTypes())
                .map(t -> real.containsKey(t) ? real.get(t) : mock(t)).toArray();
        return (T) constructor.newInstance(args);
    }

    /** A JPA repository mock backed by {@code store}: {@code save} plus one {@code findBy…} key lookup. */
    private static <R, E> R inMemory(Class<R> repository, Map<String, E> store,
                                     java.util.function.Function<E, String> key, String finder) throws Exception {
        R repo = mock(repository);
        when(repository.getMethod("save", Object.class).invoke(repo, new Object[]{any()})).thenAnswer(inv -> {
            @SuppressWarnings("unchecked") E e = (E) inv.getArgument(0);
            store.put(key.apply(e), e);
            return e;
        });
        when(repository.getMethod(finder, String.class).invoke(repo, new Object[]{anyString()}))
                .thenAnswer(inv -> Optional.ofNullable(store.get((String) inv.getArgument(0))));
        return repo;
    }

    private boolean isRegistered(String rewardAddress) {
        try {
            var info = backend.getAccountService().getAccountInformation(rewardAddress);
            return info.isSuccessful() && info.getValue() != null && Boolean.TRUE.equals(info.getValue().getActive());
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------- dummy

    @Test
    @Order(10)
    void dummyRegisterAndFirstMint() throws Exception {
        var request = DummyRegisterRequest.builder().moduleId("dummy")
                .feePayerAddress(admin).assetName(HexUtil.encodeHexString("CCLDUMMY".getBytes()))
                .quantity("1000").build();
        TransactionContext<TransactionContext.RegistrationResult> built = facade.registerToken(request, null);
        assertThat(built.isSuccessful()).as("ccl dummy register: %s", built.error()).isTrue();
        dummyPolicy = built.metadata().policyId();
        dummyUnit = dummyPolicy + request.getAssetName();
        submitAndAwait("dummy register+mint", built.unsignedCborTx());
        assertThat(heldAt(admin, dummyUnit)).isEqualTo(BigInteger.valueOf(1000));
    }

    @Test
    @Order(11)
    void dummyMintToAlice() throws Exception {
        var built = facade.mintToken(new MintTokenRequest(admin, dummyPolicy, dummyUnit.substring(56), "50", alice, null, null), null);
        assertThat(built.isSuccessful()).as("ccl dummy mint: %s", built.error()).isTrue();
        submitAndAwait("dummy mint", built.unsignedCborTx());
        assertThat(heldAt(alice, dummyUnit)).isEqualTo(BigInteger.valueOf(50));
    }

    /** Spends the holder's stake-keyed wallet from their own base address (SmartWalletKey.STAKE). */
    @Test
    @Order(13)
    void dummyTransferAdminToAlice() throws Exception {
        var built = facade.transferToken(new TransferTokenRequest(admin, dummyUnit, "100", alice,
                null, null, null, null, null, null, null), null);
        assertThat(built.isSuccessful()).as("ccl dummy transfer: %s", built.error()).isTrue();
        submitAndAwait("dummy transfer", built.unsignedCborTx());
        assertThat(heldAt(admin, dummyUnit)).isEqualTo(BigInteger.valueOf(900));
        assertThat(heldAt(alice, dummyUnit)).isEqualTo(BigInteger.valueOf(150));
    }

    /** An owner burn from the admin's own smart wallet. */
    @Test
    @Order(14)
    void dummyBurnFromAdmin() throws Exception {
        var holding = holdingAt(admin, dummyUnit);
        var built = facade.burnToken(new BurnTokenRequest(admin, dummyPolicy, dummyUnit.substring(56), "25",
                holding.getTxHash(), holding.getOutputIndex()), null);
        assertThat(built.isSuccessful()).as("ccl dummy burn: %s", built.error()).isTrue();
        submitAndAwait("dummy burn", built.unsignedCborTx());
        assertThat(heldAt(admin, dummyUnit)).isEqualTo(BigInteger.valueOf(875));
    }


    // ---------------------------------------------------------- freeze-and-seize

    /** Unique per run: issuer_admin is parameterised by (admin, asset name) only, so a reused name would find its reward account already registered by an earlier run on the same devnet. */
    private static final String FES_ASSET = HexUtil.encodeHexString(("FES" + System.currentTimeMillis() % 1_000_000).getBytes());
    private String blacklistPolicy;
    private String fesPolicy;

    /** CCL has no API for substandard state: the blacklist init is hand-built by the handler. */
    @Test
    @Order(20)
    void fesBlacklistInit() throws Exception {
        var built = freezeAndSeize.buildBlacklistInitTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.BlacklistManageable.BlacklistInitRequest(
                        "freeze-and-seize", admin, admin, FES_ASSET, "1000", null), record);
        assertThat(built.isSuccessful()).as("legacy blacklist init: %s", built.error()).isTrue();
        blacklistPolicy = built.metadata().policyId();
        submitAndAwait("fes blacklist init (hand-built)", built.unsignedCborTx());
    }

    @Test
    @Order(21)
    void fesRegisterAndFirstMint() throws Exception {
        var request = org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                .moduleId("freeze-and-seize").feePayerAddress(admin).recipientAddress(alice)
                .assetName(FES_ASSET).quantity("1000").adminPubKeyHash(adminPkh()).blacklistNodePolicyId(blacklistPolicy)
                .build();
        var built = facade.registerToken(request, null);
        assertThat(built.isSuccessful()).as("ccl fes register: %s", built.error()).isTrue();
        fesPolicy = built.metadata().policyId();
        submitAndAwait("fes register+mint", built.unsignedCborTx());
        assertThat(heldAt(alice, fesPolicy + FES_ASSET)).isEqualTo(BigInteger.valueOf(1000));
    }

    @Test
    @Order(22)
    void fesMintToAdmin() throws Exception {
        var built = facade.mintToken(new MintTokenRequest(admin, fesPolicy, FES_ASSET, "200", admin, null, null), null);
        assertThat(built.isSuccessful()).as("ccl fes mint: %s", built.error()).isTrue();
        submitAndAwait("fes mint", built.unsignedCborTx());
        assertThat(heldAt(admin, fesPolicy + FES_ASSET)).isEqualTo(BigInteger.valueOf(200));
    }

    /** The admin seizes Alice's holding into their own wallet, through the compliance facade. */
    @Test
    @Order(23)
    void fesSeizeAliceToAdmin() throws Exception {
        String unit = fesPolicy + FES_ASSET;
        String aliceWallet = CclWallets.smartWallet(deployment, alice).toBech32();
        var held = backend.getUtxoService().getUtxos(aliceWallet, 100, 1).getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit()))).findFirst().orElseThrow();
        var context = org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                .issuerAdminPkh(adminPkh()).assetName(FES_ASSET).blacklistNodePolicyId(blacklistPolicy).build();
        var built = compliance.seize("freeze-and-seize",
                new org.cardanofoundation.cip113.service.module.capabilities.Seizeable.SeizeRequest(
                        admin, unit, held.getTxHash(), held.getOutputIndex(), admin), null, context);
        assertThat(built.isSuccessful()).as("ccl fes seize: %s", built.error()).isTrue();
        submitAndAwait("fes seize", built.unsignedCborTx());
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.ZERO);
        assertThat(heldAt(admin, unit)).isEqualTo(BigInteger.valueOf(1200));
    }

    /**
     * The late-redeemer case: the transfer logic's redeemer names the blacklist node by its
     * position among the finished transaction's reference inputs, which CCL decides. Resolved by
     * the platform's SubstandardRedeemerExtension during CCL's build.
     */
    @Test
    @Order(24)
    void fesTransferAdminToAliceWithTheBlacklistProof() throws Exception {
        String unit = fesPolicy + FES_ASSET;
        var built = facade.transferToken(new TransferTokenRequest(admin, unit, "300", alice,
                null, null, null, null, null, null, null), null);
        assertThat(built.isSuccessful()).as("ccl fes transfer: %s", built.error()).isTrue();
        submitAndAwait("fes transfer", built.unsignedCborTx());
        assertThat(heldAt(admin, unit)).isEqualTo(BigInteger.valueOf(900));
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.valueOf(300));
    }

    /** Blacklist management has no CCL API: the handler's hand-built path freezes Alice. */
    @Test
    @Order(25)
    void fesFreezeAlice() throws Exception {
        var init = blacklistInitRows.get(blacklistPolicy);
        freezeAndSeize.setContext(org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                .issuerAdminPkh(adminPkh()).assetName(FES_ASSET).blacklistManagerPkh(init.getAdminPkh())
                .blacklistInitTxInput(com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                        .transactionId(init.getTxHash()).index(init.getOutputIndex()).build())
                .blacklistNodePolicyId(blacklistPolicy).build());
        var built = freezeAndSeize.buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.BlacklistManageable.AddToBlacklistRequest(
                        fesPolicy, FES_ASSET, alice, admin), record);
        assertThat(built.isSuccessful()).as("add to blacklist: %s", built.error()).isTrue();
        submitAndAwait("fes freeze alice (hand-built)", built.unsignedCborTx());
    }

    /** A frozen holder has no exempting node: the transfer is refused before building. */
    @Test
    @Order(26)
    void fesTransferFromAFrozenHolderIsRefused() throws Exception {
        String unit = fesPolicy + FES_ASSET;
        var built = facade.transferToken(new TransferTokenRequest(alice, unit, "10", admin,
                null, null, null, null, null, null, null), null);
        assertThat(built.isSuccessful()).isFalse();
        assertThat(built.error()).contains("could not resolve blacklist exemption");
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.valueOf(300));
    }

    /** The admin burns part of frozen Alice's holding: a CCL third-party burn (CCL gap #12 closed). */
    @Test
    @Order(27)
    void fesBurnAFrozenHoldersTokens() throws Exception {
        String unit = fesPolicy + FES_ASSET;
        String aliceWallet = CclWallets.smartWallet(deployment, alice).toBech32();
        var held = backend.getUtxoService().getUtxos(aliceWallet, 100, 1).getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit()))).findFirst().orElseThrow();
        var built = freezeAndSeize.buildBurnTransaction(new BurnTokenRequest(admin, fesPolicy, FES_ASSET, "100",
                held.getTxHash(), held.getOutputIndex()), record);
        assertThat(built.isSuccessful()).as("ccl fes burn: %s", built.error()).isTrue();
        submitAndAwait("fes burn", built.unsignedCborTx());
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.valueOf(200));
        assertThat(heldAt(admin, unit)).isEqualTo(BigInteger.valueOf(900));
    }
    // ----------------------------------------------------------------------- kyc

    private static final String KYC_ASSET = HexUtil.encodeHexString(("KYC" + System.currentTimeMillis() % 1_000_000).getBytes());
    private com.bloxbean.cardano.client.crypto.Keys kycEntity;
    private String globalStatePolicy;
    private String kycPolicy;

    /** CCL has no API for substandard state: the global-state init (one trusted entity) is hand-built. */
    @Test
    @Order(30)
    void kycGlobalStateInit() throws Exception {
        kycEntity = com.bloxbean.cardano.client.crypto.KeyGenUtil.generateKey();
        var built = kyc.buildGlobalStateInitTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitRequest(
                        "kyc", admin, null, List.of(HexUtil.encodeHexString(kycEntity.getVkey().getBytes())), false, null, null),
                record);
        assertThat(built.isSuccessful()).as("global state init: %s", built.error()).isTrue();
        globalStatePolicy = built.metadata().globalStatePolicyId();
        submitAndAwait("kyc global state init (hand-built)", built.unsignedCborTx());
    }

    @Test
    @Order(31)
    void kycRegisterAndFirstMint() throws Exception {
        var request = org.cardanofoundation.cip113.model.KycRegisterRequest.builder()
                .moduleId("kyc").feePayerAddress(admin).recipientAddress(admin).assetName(KYC_ASSET).quantity("1000")
                .adminPubKeyHash(adminPkh()).globalStatePolicyId(globalStatePolicy).build();
        var built = facade.registerToken(request, null);
        assertThat(built.isSuccessful()).as("ccl kyc register: %s", built.error()).isTrue();
        kycPolicy = built.metadata().policyId();
        submitAndAwait("kyc register+mint", built.unsignedCborTx());
        assertThat(heldAt(admin, kycPolicy + KYC_ASSET)).isEqualTo(BigInteger.valueOf(1000));
    }

    @Test
    @Order(32)
    void kycMintToAdmin() throws Exception {
        var built = facade.mintToken(new MintTokenRequest(admin, kycPolicy, KYC_ASSET, "100", admin, null, null), null);
        assertThat(built.isSuccessful()).as("ccl kyc mint: %s", built.error()).isTrue();
        submitAndAwait("kyc mint", built.unsignedCborTx());
        assertThat(heldAt(admin, kycPolicy + KYC_ASSET)).isEqualTo(BigInteger.valueOf(1100));
    }

    /**
     * The trusted entity attests the sender: payload = stake key hash (28) || role (1) ||
     * valid_until ms (8, big-endian), Ed25519-signed. The proof names the global-state UTxO by its
     * reference-input position in the finished transaction — resolved during CCL's build.
     */
    @Test
    @Order(33)
    void kycTransferAdminToAliceWithAnAttestation() throws Exception {
        String unit = kycPolicy + KYC_ASSET;
        byte[] stake = new Address(admin).getDelegationCredentialHash().orElseThrow();
        var payload = java.nio.ByteBuffer.allocate(37).put(stake).put((byte) 1)
                .putLong(System.currentTimeMillis() + 60 * 60 * 1000L).array();
        byte[] signature = com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(payload, kycEntity.getSkey().getBytes());
        var built = facade.transferToken(new TransferTokenRequest(admin, unit, "40", alice,
                HexUtil.encodeHexString(payload), HexUtil.encodeHexString(signature), 0, null, null, null, null), null);
        assertThat(built.isSuccessful()).as("ccl kyc transfer: %s", built.error()).isTrue();
        submitAndAwait("kyc transfer", built.unsignedCborTx());
        assertThat(heldAt(admin, unit)).isEqualTo(BigInteger.valueOf(1060));
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.valueOf(40));
    }

    /** CCL gap #18: an owner burn runs the KYC transfer check, and a burn request carries no attestation. */
    @Test
    @Order(34)
    void kycBurnIsRefused() throws Exception {
        String unit = kycPolicy + KYC_ASSET;
        var holding = holdingAt(admin, unit);
        var built = facade.burnToken(new BurnTokenRequest(admin, kycPolicy, KYC_ASSET, "1",
                holding.getTxHash(), holding.getOutputIndex()), null);
        assertThat(built.isSuccessful()).isFalse();
        assertThat(built.error()).contains("CCL gap #18");
    }

    // -------------------------------------------------------------- kyc-extended

    private static final String KYCX_ASSET = HexUtil.encodeHexString(("KYCX" + System.currentTimeMillis() % 1_000_000).getBytes());
    private com.bloxbean.cardano.client.crypto.Keys kycxEntity;
    private String kycxGlobalStatePolicy;
    private String kycxPolicy;
    private long aliceMemberUntil;
    private String aliceMemberProof;

    @Test
    @Order(40)
    void kycExtendedGlobalStateInit() throws Exception {
        kycxEntity = com.bloxbean.cardano.client.crypto.KeyGenUtil.generateKey();
        var built = kycExtended.buildGlobalStateInitTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.GlobalStateManageable.GlobalStateInitRequest(
                        "kyc-extended", admin, null, List.of(HexUtil.encodeHexString(kycxEntity.getVkey().getBytes())), false, null, null),
                record);
        assertThat(built.isSuccessful()).as("kyc-extended global state init: %s", built.error()).isTrue();
        kycxGlobalStatePolicy = built.metadata().globalStatePolicyId();
        submitAndAwait("kyc-extended global state init (hand-built)", built.unsignedCborTx());
    }

    @Test
    @Order(41)
    void kycExtendedRegisterAndFirstMint() throws Exception {
        var request = org.cardanofoundation.cip113.model.KycExtendedRegisterRequest.builder()
                .moduleId("kyc-extended").feePayerAddress(admin).recipientAddress(admin).assetName(KYCX_ASSET).quantity("1000")
                .adminPubKeyHash(adminPkh()).globalStatePolicyId(kycxGlobalStatePolicy).build();
        var built = facade.registerToken(request, null);
        assertThat(built.isSuccessful()).as("ccl kyc-extended register: %s", built.error()).isTrue();
        kycxPolicy = built.metadata().policyId();
        submitAndAwait("kyc-extended register+mint", built.unsignedCborTx());
        assertThat(heldAt(admin, kycxPolicy + KYCX_ASSET)).isEqualTo(BigInteger.valueOf(1000));
    }

    @Test
    @Order(42)
    void kycExtendedMintToAdmin() throws Exception {
        var built = facade.mintToken(new MintTokenRequest(admin, kycxPolicy, KYCX_ASSET, "100", admin, null, null), null);
        assertThat(built.isSuccessful()).as("ccl kyc-extended mint: %s", built.error()).isTrue();
        submitAndAwait("kyc-extended mint", built.unsignedCborTx());
        assertThat(heldAt(admin, kycxPolicy + KYCX_ASSET)).isEqualTo(BigInteger.valueOf(1100));
    }

    /** Alice becomes a member: her leaf goes into the trie and its root into global state (hand-built admin tx). */
    @Test
    @Order(43)
    void kycExtendedPublishAliceAsAMember() throws Exception {
        var aliceStake = new Address(alice).getDelegationCredentialHash().orElseThrow();
        aliceMemberUntil = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
        var trie = new com.bloxbean.cardano.vds.mpf.MpfTrie(new com.bloxbean.cardano.vds.mpf.internal.TestNodeStore());
        trie.put(aliceStake, java.nio.ByteBuffer.allocate(8).putLong(aliceMemberUntil).array());
        aliceMemberProof = HexUtil.encodeHexString(com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                trie.getProofPlutusData(aliceStake).orElseThrow().serialize()));
        kycExtended.setContext(KycExtendedContext.builder().issuerAdminPkh(adminPkh())
                .globalStatePolicyId(kycxGlobalStatePolicy)
                .globalStateInitTxInput(globalStateInitInput(kycxGlobalStatePolicy)).build());
        var built = kycExtended.buildUpdateMemberRootHashTransaction(kycxPolicy, trie.getRootHash(), admin, adminPkh(), record);
        assertThat(built.isSuccessful()).as("member root update: %s", built.error()).isTrue();
        submitAndAwait("kyc-extended member root (hand-built)", built.unsignedCborTx());
    }

    /** Admin (attested by the trusted entity) to Alice (a member, proved against the on-chain root). */
    @Test
    @Order(44)
    void kycExtendedTransferAdminToAMember() throws Exception {
        String unit = kycxPolicy + KYCX_ASSET;
        byte[] stake = new Address(admin).getDelegationCredentialHash().orElseThrow();
        var payload = java.nio.ByteBuffer.allocate(37).put(stake).put((byte) 1)
                .putLong(System.currentTimeMillis() + 60 * 60 * 1000L).array();
        byte[] signature = com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(payload, kycxEntity.getSkey().getBytes());
        var built = facade.transferToken(new TransferTokenRequest(admin, unit, "40", alice,
                HexUtil.encodeHexString(payload), HexUtil.encodeHexString(signature), 0, null, null,
                aliceMemberProof, aliceMemberUntil), null);
        assertThat(built.isSuccessful()).as("ccl kyc-extended transfer: %s", built.error()).isTrue();
        submitAndAwait("kyc-extended transfer", built.unsignedCborTx());
        assertThat(heldAt(admin, unit)).isEqualTo(BigInteger.valueOf(1060));
        assertThat(heldAt(alice, unit)).isEqualTo(BigInteger.valueOf(40));
    }

    private com.bloxbean.cardano.client.transaction.spec.TransactionInput globalStateInitInput(String policy) {
        var init = globalStateInitRows.get(policy);
        return com.bloxbean.cardano.client.transaction.spec.TransactionInput.builder()
                .transactionId(init.getTxHash()).index(init.getOutputIndex()).build();
    }

    private static String adminPkh() {
        return HexUtil.encodeHexString(new Address(BootstrapFixture.ADMIN.baseAddress()).getPaymentCredentialHash().orElseThrow());
    }

    // ----------------------------------------------------------------- helpers

    /** Programmable holdings of {@code unit} at the holder's platform smart wallet (PLB + stake). */
    private BigInteger heldAt(String holder, String unit) throws Exception {
        String wallet = CclWallets.smartWallet(deployment, holder).toBech32();
        var utxos = backend.getUtxoService().getUtxos(wallet, 100, 1);
        if (!utxos.isSuccessful()) return BigInteger.ZERO;
        return utxos.getValue().stream().flatMap(u -> u.getAmount().stream())
                .filter(a -> unit.equals(a.getUnit())).map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** One UTxO of the holder's smart wallet carrying {@code unit}. */
    private com.bloxbean.cardano.client.api.model.Utxo holdingAt(String holder, String unit) throws Exception {
        String wallet = CclWallets.smartWallet(deployment, holder).toBech32();
        return backend.getUtxoService().getUtxos(wallet, 100, 1).getValue().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equals(a.getUnit())))
                .findFirst().orElseThrow();
    }


    /** Dummy's issue/transfer validators take no parameters: register their reward accounts once. */
    private void registerDummyStakeCredentials(ModuleService modules) {
        var tx = new Tx().from(admin);
        int added = 0;
        for (String title : List.of("transfer.issue.withdraw", "transfer.transfer.withdraw")) {
            var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    modules.getModuleValidator("dummy", title).orElseThrow().scriptBytes(), PlutusVersion.v3);
            String reward = AddressProvider.getRewardAddress(script, BootstrapFixture.NETWORK).toBech32();
            var account = backend.getAccountService();
            boolean registered;
            try {
                var info = account.getAccountInformation(reward);
                registered = info.isSuccessful() && info.getValue() != null && Boolean.TRUE.equals(info.getValue().getActive());
            } catch (Exception e) {
                registered = false;
            }
            if (!registered) {
                tx.registerStakeAddress(reward);
                added++;
            }
        }
        if (added == 0) return;
        var result = new QuickTxBuilder(backend).compose(tx)
                .withSigner(SignerProviders.signerFrom(BootstrapFixture.ADMIN))
                .completeAndWait(log::info);
        // A credential registered between the check and the submit is the only acceptable failure.
        assertThat(result.isSuccessful() || String.valueOf(result.getResponse()).contains("StakeKeyRegisteredDELEG"))
                .as("dummy stake registration: %s", result.getResponse()).isTrue();
    }

    /** Sign with the admin payment key, plus the stake key when the transaction asks for it. */
    private void submitAndAwait(String label, String cborHex) throws Exception {
        var tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
        byte[] stakeHash = new Address(BootstrapFixture.ADMIN.stakeAddress()).getDelegationCredentialHash().orElseThrow();
        boolean needsStake = tx.getBody().getRequiredSigners() != null
                && tx.getBody().getRequiredSigners().stream().anyMatch(rs -> Arrays.equals(rs, stakeHash));
        var signed = BootstrapFixture.ADMIN.sign(tx);
        if (needsStake) signed = BootstrapFixture.ADMIN.signWithStakeKey(signed);
        var result = backend.getTransactionService().submitTransaction(signed.serialize());
        assertThat(result.isSuccessful()).as("[%s] submit rejected: %s", label, result.getResponse()).isTrue();
        String hash = result.getValue();
        log.info("[devnet ccl] {} submitted: {}", label, hash);
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                var found = backend.getTransactionService().getTransaction(hash);
                if (found.isSuccessful() && found.getValue() != null) {
                    log.info("[devnet ccl] {} confirmed: {}", label, hash);
                    Thread.sleep(1_500); // let the store index the outputs before the next step reads them
                    return;
                }
            } catch (Exception ignored) {
                // not indexed yet
            }
            Thread.sleep(1_000);
        }
        throw new AssertionError("[" + label + "] " + hash + " accepted but never appeared on chain");
    }
}
