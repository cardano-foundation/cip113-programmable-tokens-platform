package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.ccl.CclWallets;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.BurnTokenRequest;
import org.cardanofoundation.cip113.model.DummyRegisterRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.TransferTokenRequest;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.cardanofoundation.cip113.service.module.DummyModuleHandler;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Every handler operation that cardano-client-lib builds, built by the real handler on an offline
 * chain and run through the real Aiken evaluator, then virtually submitted so the next operation
 * builds on its outputs.
 *
 * <p>The existing offline suites pin the handlers' refusals and CIP-68 shapes; this one pins that
 * each CCL-built success path is a transaction the validators accept.
 */
@Slf4j
class HandlersOnCclTest {

    private static final String ASSET = HexUtil.encodeHexString("CclToken".getBytes());
    private static final String ADMIN = BootstrapFixture.ADMIN.baseAddress();
    private static final String ALICE = BootstrapFixture.ALICE.baseAddress();

    // ------------------------------------------------------------------ dummy

    @Test
    void dummyRegisterMintTransferAndBurnAllEvaluate() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var rows = new HashMap<String, ProgrammableTokenRegistryEntity>();
        var handler = dummyHandler(chain, boot, rows);

        var registered = handler.buildRegistrationTransaction(DummyRegisterRequest.builder()
                .moduleId("dummy").feePayerAddress(ADMIN).recipientAddress(ADMIN)
                .assetName(ASSET).quantity("1000").build(), boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "dummy/register", registered);
        Assertions.assertEquals(ASSET, rows.get(policyId).getAssetName(), "registration writes the registry row");

        evaluateAndSubmit(chain, "dummy/mint", handler.buildMintTransaction(
                new MintTokenRequest(ADMIN, policyId, ASSET, "500", ADMIN, null), boot.params()));

        evaluateAndSubmit(chain, "dummy/transfer", handler.buildTransferTransaction(
                transfer(ADMIN, policyId + ASSET, "300", ALICE), boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(300), held(chain, boot, ALICE, policyId + ASSET),
                "the recipient's smart wallet receives the transfer");

        var adminHolding = holding(chain, boot, ADMIN, policyId + ASSET);
        evaluateAndSubmit(chain, "dummy/burn", handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, ASSET, "200",
                        adminHolding.getTxHash(), adminHolding.getOutputIndex()), boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(1000), held(chain, boot, ADMIN, policyId + ASSET),
                "1000 registered + 500 minted - 300 transferred - 200 burnt");
    }

    @Test
    void burningAnotherHoldersUtxoIsRefusedAsAThirdPartyBurn() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var handler = dummyHandler(chain, boot, new HashMap<>());

        var registered = handler.buildRegistrationTransaction(DummyRegisterRequest.builder()
                .moduleId("dummy").feePayerAddress(ADMIN).recipientAddress(ALICE)
                .assetName(ASSET).quantity("1000").build(), boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "dummy/register", registered);

        var alicesHolding = holding(chain, boot, ALICE, policyId + ASSET);
        var refused = handler.buildBurnTransaction(new BurnTokenRequest(ADMIN, policyId, ASSET, "1",
                alicesHolding.getTxHash(), alicesHolding.getOutputIndex()), boot.params());

        Assertions.assertFalse(refused.isSuccessful(), "the admin must not burn Alice's tokens through an owner burn");
        Assertions.assertNull(refused.unsignedCborTx());
        Assertions.assertTrue(String.valueOf(refused.error()).contains("third-party burn (CCL gap #12)"),
                "got: " + refused.error());
    }

    /** The CIP-68 (100) reference token must never be burnt through the ordinary burn: it breaks the pair for good. */
    @Test
    void dummyBurnRefusesTheReferenceTokenAndUnregisteredNames() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var handler = dummyHandler(chain, boot, new HashMap<>());

        var registered = handler.buildRegistrationTransaction(DummyRegisterRequest.builder()
                .moduleId("dummy").feePayerAddress(ADMIN).recipientAddress(ADMIN).assetName(ASSET).quantity("1000")
                .cip68Metadata(new org.cardanofoundation.cip113.model.Cip68Metadata("Ccl", "ref", "CCL", 0, null, null))
                .build(), boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "dummy/register-cip68", registered);

        var referenceName = org.cardanofoundation.cip113.util.Cip68.labeledAssetName(
                org.cardanofoundation.cip113.util.Cip68.LABEL_REFERENCE, ASSET);
        var reference = holding(chain, boot, ADMIN, policyId + referenceName);
        var refused = handler.buildBurnTransaction(new BurnTokenRequest(ADMIN, policyId, referenceName, "1",
                reference.getTxHash(), reference.getOutputIndex()), boot.params());
        Assertions.assertFalse(refused.isSuccessful(), "the (100) reference token must not be burnt");
        Assertions.assertTrue(String.valueOf(refused.error()).contains("(100)"), "got: " + refused.error());

        var unregistered = handler.buildBurnTransaction(new BurnTokenRequest(ADMIN, policyId, ASSET, "1",
                reference.getTxHash(), reference.getOutputIndex()), boot.params());
        Assertions.assertFalse(unregistered.isSuccessful(), "the bare name is not the registered (333) name");
        Assertions.assertTrue(String.valueOf(unregistered.error()).contains("does not match the registered"),
                "got: " + unregistered.error());
    }

    // ------------------------------------------------------------------ freeze-and-seize

    /** Any 28-byte policy: the blacklist nodes are seeded, not minted, so nothing checks its preimage. */
    private static final String BLACKLIST_POLICY = "b1ac".repeat(14);

    @Test
    void freezeAndSeizeRegisterMintTransferAndSeizeAllEvaluate() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        seedBlacklist(chain, "", stakeKeyOf(ALICE), "ff".repeat(28));

        var registered = fes.handler.buildRegistrationTransaction(
                org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                        .moduleId("freeze-and-seize").feePayerAddress(ADMIN).recipientAddress(ADMIN)
                        .assetName(ASSET).quantity("1000").adminPubKeyHash(paymentKeyOf(ADMIN))
                        .blacklistNodePolicyId(BLACKLIST_POLICY).build(),
                boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "fes/register", registered);

        evaluateAndSubmit(chain, "fes/mint", fes.handler.buildMintTransaction(
                new MintTokenRequest(ADMIN, policyId, ASSET, "500", ALICE, null), boot.params()));

        evaluateAndSubmit(chain, "fes/transfer", fes.handler.buildTransferTransaction(
                transfer(ADMIN, policyId + ASSET, "300", ALICE), boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(800), held(chain, boot, ALICE, policyId + ASSET));

        var target = holding(chain, boot, ALICE, policyId + ASSET);
        var seizedAmount = target.getAmount().stream().filter(a -> a.getUnit().equals(policyId + ASSET))
                .findFirst().orElseThrow().getQuantity();
        evaluateAndSubmit(chain, "fes/seize", fes.handler.buildSeizeTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.Seizeable.SeizeRequest(
                        ADMIN, policyId + ASSET, target.getTxHash(), target.getOutputIndex(), ADMIN),
                boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(800).subtract(seizedAmount), held(chain, boot, ALICE, policyId + ASSET),
                "exactly the named UTxO is seized");
        Assertions.assertEquals(BigInteger.valueOf(700).add(seizedAmount), held(chain, boot, ADMIN, policyId + ASSET));
    }

    /**
     * CCL returns a holder's programmable change with no inline datum. Such a UTxO must stay
     * seizable (CCL keeps the continuing output's datum as it is) and burnable (the hand-built
     * burn must carry the input's datum, or none, onto its continuation, as ThirdPartyAct demands).
     */
    @Test
    void aDatumlessChangeUtxoCanBeSeizedAndBurnt() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        seedBlacklist(chain, "", "ff".repeat(28));

        var registered = fes.handler.buildRegistrationTransaction(
                org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                        .moduleId("freeze-and-seize").feePayerAddress(ADMIN).recipientAddress(ADMIN)
                        .assetName(ASSET).quantity("1000").adminPubKeyHash(paymentKeyOf(ADMIN))
                        .blacklistNodePolicyId(BLACKLIST_POLICY).build(),
                boot.params());
        var policyId = registered.metadata().policyId();
        var unit = policyId + ASSET;
        evaluateAndSubmit(chain, "fes/register", registered);
        evaluateAndSubmit(chain, "fes/transfer", fes.handler.buildTransferTransaction(
                transfer(ADMIN, unit, "100", ALICE), boot.params()));

        var change = datumlessHolding(chain, boot, ADMIN, unit);
        evaluateAndSubmit(chain, "fes/seize-change", fes.handler.buildSeizeTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.Seizeable.SeizeRequest(
                        ADMIN, unit, change.getTxHash(), change.getOutputIndex(), ALICE),
                boot.params()));

        Assertions.assertEquals(BigInteger.valueOf(1000), held(chain, boot, ALICE, unit));

        // The seized holding at Alice's wallet carries no datum either (CCL writes none on a
        // third-party destination); the admin burns it with the hand-built burn (CCL gap #12).
        var seized = datumlessHolding(chain, boot, ALICE, unit);
        var seizedAmount = seized.getAmount().stream().filter(a -> a.getUnit().equals(unit))
                .findFirst().orElseThrow().getQuantity();
        evaluateAndSubmit(chain, "fes/burn-datumless", fes.handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, ASSET, seizedAmount.toString(),
                        seized.getTxHash(), seized.getOutputIndex()), boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(1000).subtract(seizedAmount), held(chain, boot, ALICE, unit));
    }

    private static Utxo datumlessHolding(OfflineChain chain, BootstrapFixture.Bootstrapped boot,
                                         String holder, String unit) throws Exception {
        return chain.utxosAt(smartWallet(chain, boot, holder)).stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().equals(unit)))
                .filter(u -> u.getInlineDatum() == null || u.getInlineDatum().isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError(holder + " holds no datumless " + unit));
    }

    /** The admin burns exactly the requested quantity from a holder's UTxO, then the rest, via the CCL third-party burn. */
    @Test
    void freezeAndSeizeBurnBurnsExactlyTheRequestedQuantity() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        seedBlacklist(chain, "", "ff".repeat(28));

        var registered = fes.handler.buildRegistrationTransaction(
                org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                        .moduleId("freeze-and-seize").feePayerAddress(ADMIN).recipientAddress(ALICE)
                        .assetName(ASSET).quantity("1000").adminPubKeyHash(paymentKeyOf(ADMIN))
                        .blacklistNodePolicyId(BLACKLIST_POLICY).build(),
                boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "fes/register", registered);

        var target = holding(chain, boot, ALICE, policyId + ASSET);
        evaluateAndSubmit(chain, "fes/partial-burn", fes.handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, ASSET, "400", target.getTxHash(), target.getOutputIndex()),
                boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(600), held(chain, boot, ALICE, policyId + ASSET),
                "only the requested 400 are burnt; the continuation keeps the rest");

        var rest = holding(chain, boot, ALICE, policyId + ASSET);
        evaluateAndSubmit(chain, "fes/full-burn", fes.handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, ASSET, "600", rest.getTxHash(), rest.getOutputIndex()),
                boot.params()));
        Assertions.assertEquals(BigInteger.ZERO, held(chain, boot, ALICE, policyId + ASSET));
    }

    /** More than the UTxO holds, and the CIP-68 (100) reference token, are refused (BurnTokenRequest refuses a non-positive quantity). */
    @Test
    void freezeAndSeizeBurnRefusesAnOverdrawnQuantityAndTheReferenceToken() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        var policyId = "c1".repeat(28);
        var userName = org.cardanofoundation.cip113.util.Cip68.labeledAssetName(
                org.cardanofoundation.cip113.util.Cip68.LABEL_FT, ASSET);
        var referenceName = org.cardanofoundation.cip113.util.Cip68.labeledAssetName(
                org.cardanofoundation.cip113.util.Cip68.LABEL_REFERENCE, ASSET);
        var pair = Utxo.builder().txHash("c2".repeat(32)).outputIndex(0).address(smartWallet(chain, boot, ALICE))
                .amount(java.util.List.of(com.bloxbean.cardano.client.api.model.Amount.ada(2),
                        new com.bloxbean.cardano.client.api.model.Amount(policyId + userName, BigInteger.TEN),
                        new com.bloxbean.cardano.client.api.model.Amount(policyId + referenceName, BigInteger.ONE)))
                .inlineDatum(org.cardanofoundation.cip113.core.CoreDatums.programmableTokenDatum().serializeToHex())
                .build();
        chain.seedUtxo(pair);

        var overdrawn = fes.handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, userName, "11", pair.getTxHash(), pair.getOutputIndex()),
                boot.params());
        Assertions.assertFalse(overdrawn.isSuccessful(), "the UTxO holds only 10");
        Assertions.assertTrue(String.valueOf(overdrawn.error()).contains("exceeds"), "got: " + overdrawn.error());

        var reference = fes.handler.buildBurnTransaction(
                new BurnTokenRequest(ADMIN, policyId, referenceName, "1", pair.getTxHash(), pair.getOutputIndex()),
                boot.params());
        Assertions.assertFalse(reference.isSuccessful(), "the (100) itself is never burnt");
        Assertions.assertTrue(String.valueOf(reference.error()).contains("(100)"), "got: " + reference.error());
    }

    @Test
    void aBlacklistedHolderCannotTransfer() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        seedBlacklist(chain, "", stakeKeyOf(ALICE), "ff".repeat(28));

        var registered = fes.handler.buildRegistrationTransaction(
                org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                        .moduleId("freeze-and-seize").feePayerAddress(ADMIN).recipientAddress(ALICE)
                        .assetName(ASSET).quantity("1000").adminPubKeyHash(paymentKeyOf(ADMIN))
                        .blacklistNodePolicyId(BLACKLIST_POLICY).build(),
                boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "fes/register", registered);

        var refused = fes.handler.buildTransferTransaction(transfer(ALICE, policyId + ASSET, "1", ADMIN), boot.params());
        Assertions.assertFalse(refused.isSuccessful(), "Alice is on the blacklist");
        Assertions.assertTrue(String.valueOf(refused.error()).contains("could not resolve blacklist exemption"),
                "got: " + refused.error());
    }

    /**
     * The transfer still asks the chain whether its derived transfer logic is the one the token
     * was registered with. A different blacklist policy derives a different script.
     */
    @Test
    void aTransferWhoseDerivedLogicDisagreesWithTheRegistryIsRefused() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var fes = freezeAndSeizeHandler(chain, boot);
        seedBlacklist(chain, "", "ff".repeat(28));

        var registered = fes.handler.buildRegistrationTransaction(
                org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                        .moduleId("freeze-and-seize").feePayerAddress(ADMIN).recipientAddress(ADMIN)
                        .assetName(ASSET).quantity("1000").adminPubKeyHash(paymentKeyOf(ADMIN))
                        .blacklistNodePolicyId(BLACKLIST_POLICY).build(),
                boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "fes/register", registered);

        fes.handler.setContext(org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                .blacklistNodePolicyId("c0de".repeat(14))
                .issuerAdminPkh(paymentKeyOf(ADMIN))
                .build());
        var refused = fes.handler.buildTransferTransaction(transfer(ADMIN, policyId + ASSET, "1", ALICE), boot.params());

        Assertions.assertFalse(refused.isSuccessful());
        Assertions.assertTrue(String.valueOf(refused.error()).contains("a credential the registration never registered"),
                "got: " + refused.error());
    }

    private record FesFixture(org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler handler) {}

    private static FesFixture freezeAndSeizeHandler(OfflineChain chain, BootstrapFixture.Bootstrapped boot) throws Exception {
        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier().getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString())).thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        Mockito.when(utxoProvider.findIssuanceCborHexUtxo(Mockito.any())).thenReturn(Optional.of(boot.issuanceCborHexUtxo()));

        var inits = Mockito.mock(org.cardanofoundation.cip113.repository.BlacklistInitRepository.class);
        // A row with no recorded credentials: "no evidence", so the cross-checks stay silent.
        Mockito.when(inits.findByBlacklistNodePolicyId(BLACKLIST_POLICY)).thenReturn(Optional.of(
                org.cardanofoundation.cip113.entity.BlacklistInitEntity.builder()
                        .blacklistNodePolicyId(BLACKLIST_POLICY).adminPkh(paymentKeyOf(ADMIN))
                        .txHash("00".repeat(32)).outputIndex(0).build()));
        var registrations = Mockito.mock(ScriptRegistrationService.class);
        Mockito.when(registrations.isStakeAddressRegistered(Mockito.anyString())).thenReturn(true);
        var moduleService = HandlerFixtures.moduleService();

        var handler = new org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxoProvider),
                moduleService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(moduleService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService()),
                Mockito.mock(org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository.class),
                inits,
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                Mockito.mock(CustomStakeRegistrationRepository.class),
                registrations,
                utxoProvider,
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                CclTestWiring.deployments(chain),
                CclTestWiring.quickTx(chain));
        handler.setContext(org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                .blacklistNodePolicyId(BLACKLIST_POLICY)
                .issuerAdminPkh(paymentKeyOf(ADMIN))
                .assetName(ASSET)
                .build());
        return new FesFixture(handler);
    }

    /** The sorted blacklist linked list {@code keys[0] -> keys[1] -> ...}, each node holding the policy's token. */
    private static void seedBlacklist(OfflineChain chain, String... keys) throws Exception {
        var spend = new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(HandlerFixtures.moduleService())
                .buildBlacklistSpendScript(BLACKLIST_POLICY);
        var address = com.bloxbean.cardano.client.address.AddressProvider
                .getEntAddress(spend, HandlerFixtures.NETWORK.getCardanoNetwork()).getAddress();
        for (int i = 0; i + 1 < keys.length; i++) {
            var datum = new org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNode(keys[i], keys[i + 1]);
            chain.seedUtxo(Utxo.builder()
                    .txHash("b1".repeat(32)).outputIndex(i).address(address)
                    .amount(java.util.List.of(com.bloxbean.cardano.client.api.model.Amount.ada(2),
                            new com.bloxbean.cardano.client.api.model.Amount(BLACKLIST_POLICY, BigInteger.ONE)))
                    .inlineDatum(datum.toPlutusData().serializeToHex())
                    .build());
        }
    }

    static String paymentKeyOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address).getPaymentCredentialHash()
                .map(HexUtil::encodeHexString).orElseThrow();
    }

    static String stakeKeyOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address).getDelegationCredentialHash()
                .map(HexUtil::encodeHexString).orElseThrow();
    }

    // ------------------------------------------------------------------ kyc

    @Test
    void kycRegisterMintAndTransferWithAnAttestationAllEvaluate() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var entity = com.bloxbean.cardano.client.crypto.KeyGenUtil.generateKey();
        var kyc = kycHandler(chain);

        var init = kyc.buildGlobalStateInitTransaction(new org.cardanofoundation.cip113.service.module.capabilities
                .GlobalStateManageable.GlobalStateInitRequest("kyc", ADMIN, paymentKeyOf(ADMIN),
                java.util.List.of(HexUtil.encodeHexString(entity.getVkey().getBytes())), false, null, null), boot.params());
        var globalStatePolicy = init.metadata().globalStatePolicyId();
        var initTx = evaluateAndSubmit(chain, "kyc/global-state-init", init);
        kyc.setContext(org.cardanofoundation.cip113.service.module.context.KycContext.builder()
                .issuerAdminPkh(paymentKeyOf(ADMIN)).globalStatePolicyId(globalStatePolicy)
                .globalStateInitTxInput(initTx.getBody().getInputs().getFirst()).build());

        var registered = kyc.buildRegistrationTransaction(org.cardanofoundation.cip113.model.KycRegisterRequest.builder()
                .moduleId("kyc").feePayerAddress(ADMIN).recipientAddress(ADMIN).assetName(ASSET).quantity("1000")
                .adminPubKeyHash(paymentKeyOf(ADMIN)).globalStatePolicyId(globalStatePolicy).build(), boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "kyc/register", registered);

        evaluateAndSubmit(chain, "kyc/mint", kyc.buildMintTransaction(
                new MintTokenRequest(ADMIN, policyId, ASSET, "500", ADMIN, null), boot.params()));

        var attestation = attest(entity, ADMIN);
        evaluateAndSubmit(chain, "kyc/transfer", kyc.buildTransferTransaction(new TransferTokenRequest(
                ADMIN, policyId + ASSET, "300", ALICE, attestation[0], attestation[1], 0, null, null, null, null),
                boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(300), held(chain, boot, ALICE, policyId + ASSET));

        var adminHolding = holding(chain, boot, ADMIN, policyId + ASSET);
        var burn = kyc.buildBurnTransaction(new BurnTokenRequest(ADMIN, policyId, ASSET, "1",
                adminHolding.getTxHash(), adminHolding.getOutputIndex()), boot.params());
        Assertions.assertFalse(burn.isSuccessful(), "an owner burn needs a KYC proof the request cannot carry");
        Assertions.assertTrue(String.valueOf(burn.error()).contains("CCL gap #18"), "got: " + burn.error());
    }

    @Test
    void kycExtendedRegisterMintAndTransferToAMemberAllEvaluate() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var entity = com.bloxbean.cardano.client.crypto.KeyGenUtil.generateKey();
        var mpf = Mockito.mock(org.cardanofoundation.cip113.service.MpfTreeService.class);
        Mockito.when(mpf.containsValid(Mockito.anyString(), Mockito.any(), Mockito.anyLong())).thenReturn(true);
        var ext = kycExtendedHandler(chain, mpf);

        var init = ext.buildGlobalStateInitTransaction(new org.cardanofoundation.cip113.service.module.capabilities
                .GlobalStateManageable.GlobalStateInitRequest("kyc-extended", ADMIN, paymentKeyOf(ADMIN),
                java.util.List.of(HexUtil.encodeHexString(entity.getVkey().getBytes())), false, null, null), boot.params());
        var globalStatePolicy = init.metadata().globalStatePolicyId();
        var initTx = evaluateAndSubmit(chain, "kyc-ext/global-state-init", init);
        ext.setContext(org.cardanofoundation.cip113.service.module.context.KycExtendedContext.builder()
                .issuerAdminPkh(paymentKeyOf(ADMIN)).globalStatePolicyId(globalStatePolicy)
                .globalStateInitTxInput(initTx.getBody().getInputs().getFirst()).build());

        var registered = ext.buildRegistrationTransaction(org.cardanofoundation.cip113.model.KycExtendedRegisterRequest.builder()
                .moduleId("kyc-extended").feePayerAddress(ADMIN).recipientAddress(ADMIN).assetName(ASSET).quantity("1000")
                .adminPubKeyHash(paymentKeyOf(ADMIN)).globalStatePolicyId(globalStatePolicy).build(), boot.params());
        var policyId = registered.metadata().policyId();
        evaluateAndSubmit(chain, "kyc-ext/register", registered);

        evaluateAndSubmit(chain, "kyc-ext/mint", ext.buildMintTransaction(
                new MintTokenRequest(ADMIN, policyId, ASSET, "500", ADMIN, null), boot.params()));

        // Alice is a member: her leaf goes into the trie, and the trie's root into global state.
        var aliceStake = HexUtil.decodeHexString(stakeKeyOf(ALICE));
        long memberUntil = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
        var trie = new com.bloxbean.cardano.vds.mpf.MpfTrie(new com.bloxbean.cardano.vds.mpf.internal.TestNodeStore());
        trie.put(aliceStake, java.nio.ByteBuffer.allocate(8).putLong(memberUntil).array());
        evaluateAndSubmit(chain, "kyc-ext/member-root", ext.buildUpdateMemberRootHashTransaction(
                policyId, trie.getRootHash(), ADMIN, paymentKeyOf(ADMIN), boot.params()));
        var aliceProof = HexUtil.encodeHexString(com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.serialize(
                trie.getProofPlutusData(aliceStake).orElseThrow().serialize()));

        var attestation = attest(entity, ADMIN);
        evaluateAndSubmit(chain, "kyc-ext/transfer", ext.buildTransferTransaction(new TransferTokenRequest(
                ADMIN, policyId + ASSET, "300", ALICE, attestation[0], attestation[1], 0, null, null,
                aliceProof, memberUntil), boot.params()));
        Assertions.assertEquals(BigInteger.valueOf(300), held(chain, boot, ALICE, policyId + ASSET));
    }

    /** A trusted entity's attestation of {@code holder}: stake key (28) || role (1) || valid_until ms (8), Ed25519-signed. */
    private static String[] attest(com.bloxbean.cardano.client.crypto.Keys entity, String holder) throws Exception {
        var payload = java.nio.ByteBuffer.allocate(37).put(HexUtil.decodeHexString(stakeKeyOf(holder))).put((byte) 1)
                .putLong(System.currentTimeMillis() + 60 * 60 * 1000L).array();
        var signature = com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                .sign(payload, entity.getSkey().getBytes());
        return new String[]{HexUtil.encodeHexString(payload), HexUtil.encodeHexString(signature)};
    }

    private static org.cardanofoundation.cip113.service.UtxoProvider chainUtxos(OfflineChain chain,
                                                                               BootstrapFixture.Bootstrapped boot) {
        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier().getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString())).thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        Mockito.when(utxoProvider.findUtxosFromBlockfrost(Mockito.anyString())).thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        if (boot != null) {
            Mockito.when(utxoProvider.findIssuanceCborHexUtxo(Mockito.any())).thenReturn(Optional.of(boot.issuanceCborHexUtxo()));
        }
        return utxoProvider;
    }

    private static org.cardanofoundation.cip113.service.module.KycModuleHandler kycHandler(OfflineChain chain) throws Exception {
        var utxos = chainUtxos(chain, null);
        var initRows = new HashMap<String, org.cardanofoundation.cip113.entity.GlobalStateInitEntity>();
        var inits = Mockito.mock(org.cardanofoundation.cip113.repository.GlobalStateInitRepository.class);
        Mockito.when(inits.save(Mockito.any())).thenAnswer(inv -> {
            var row = (org.cardanofoundation.cip113.entity.GlobalStateInitEntity) inv.getArgument(0);
            initRows.put(row.getGlobalStatePolicyId(), row);
            return row;
        });
        Mockito.when(inits.findByGlobalStatePolicyId(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(initRows.get((String) inv.getArgument(0))));
        var stakeRegistrations = Mockito.mock(CustomStakeRegistrationRepository.class);
        var moduleService = HandlerFixtures.moduleService();
        var cardanoConverters = Mockito.mock(org.cardanofoundation.conversions.CardanoConverters.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(cardanoConverters.time().toSlot(Mockito.any(java.time.LocalDateTime.class))).thenReturn(100_000_000L);
        return new org.cardanofoundation.cip113.service.module.KycModuleHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxos),
                moduleService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.KycScriptBuilderService(moduleService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxos),
                chain.quickTxBuilder(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService()),
                Mockito.mock(org.cardanofoundation.cip113.repository.KycTokenRegistrationRepository.class),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                inits,
                stakeRegistrations,
                utxos,
                cardanoConverters,
                CclTestWiring.deployments(chain),
                CclTestWiring.quickTx(chain));
    }

    private static org.cardanofoundation.cip113.service.module.KycExtendedModuleHandler kycExtendedHandler(
            OfflineChain chain, org.cardanofoundation.cip113.service.MpfTreeService mpf) throws Exception {
        var utxos = chainUtxos(chain, null);
        var initRows = new HashMap<String, org.cardanofoundation.cip113.entity.GlobalStateInitEntity>();
        var inits = Mockito.mock(org.cardanofoundation.cip113.repository.GlobalStateInitRepository.class);
        Mockito.when(inits.save(Mockito.any())).thenAnswer(inv -> {
            var row = (org.cardanofoundation.cip113.entity.GlobalStateInitEntity) inv.getArgument(0);
            initRows.put(row.getGlobalStatePolicyId(), row);
            return row;
        });
        Mockito.when(inits.findByGlobalStatePolicyId(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(initRows.get((String) inv.getArgument(0))));
        var stakeRegistrations = Mockito.mock(CustomStakeRegistrationRepository.class);
        var moduleService = HandlerFixtures.moduleService();
        var cardanoConverters = Mockito.mock(org.cardanofoundation.conversions.CardanoConverters.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(cardanoConverters.time().toSlot(Mockito.any(java.time.LocalDateTime.class))).thenReturn(100_000_000L);
        return new org.cardanofoundation.cip113.service.module.KycExtendedModuleHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxos),
                moduleService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.KycExtendedScriptBuilderService(moduleService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxos),
                chain.quickTxBuilder(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService()),
                Mockito.mock(org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository.class),
                Mockito.mock(org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository.class),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                inits,
                stakeRegistrations,
                utxos,
                cardanoConverters,
                mpf,
                CclTestWiring.deployments(chain),
                CclTestWiring.quickTx(chain));
    }

    // ------------------------------------------------------------------ helpers

    private static DummyModuleHandler dummyHandler(OfflineChain chain, BootstrapFixture.Bootstrapped boot,
                                                   Map<String, ProgrammableTokenRegistryEntity> rows) throws Exception {
        var registry = Mockito.mock(ProgrammableTokenRegistryRepository.class);
        Mockito.when(registry.save(Mockito.any())).thenAnswer(inv -> {
            var entity = (ProgrammableTokenRegistryEntity) inv.getArgument(0);
            rows.put(entity.getPolicyId(), entity);
            return entity;
        });
        Mockito.when(registry.findByPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> Optional.ofNullable(rows.get((String) inv.getArgument(0))));
        var registrations = Mockito.mock(ScriptRegistrationService.class);
        Mockito.when(registrations.isStakeAddressRegistered(Mockito.anyString())).thenReturn(true);
        return new DummyModuleHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, boot.registryOriginUtxo().getAddress(),
                        HexUtil.encodeHexString(boot.registrySpend().getScriptHash())),
                Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.moduleService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                registry,
                Mockito.mock(CustomStakeRegistrationRepository.class),
                registrations,
                CclTestWiring.deployments(chain),
                CclTestWiring.quickTx(chain));
    }

    /** Asserts the build succeeded, runs every redeemer through the evaluator, and puts the tx on the chain. */
    static Transaction evaluateAndSubmit(OfflineChain chain, String label, TransactionContext<?> built) throws Exception {
        Assertions.assertTrue(built.isSuccessful(), label + " failed to build: " + built.error());
        var tx = Transaction.deserialize(HexUtil.decodeHexString(built.unsignedCborTx()));
        int evaluated = chain.reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, label + ": no redeemer was evaluated");
        Assertions.assertTrue(tx.serialize().length < 16384, label + " exceeds the max tx size");
        chain.submit(tx);
        return tx;
    }

    static String smartWallet(OfflineChain chain, BootstrapFixture.Bootstrapped boot, String holder) throws Exception {
        var deployment = CclTestWiring.deployments(chain).service(boot.params()).advanced().deployment();
        return CclWallets.smartWallet(deployment, holder).toBech32();
    }

    static Utxo holding(OfflineChain chain, BootstrapFixture.Bootstrapped boot, String holder, String unit) throws Exception {
        return chain.utxosAt(smartWallet(chain, boot, holder)).stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> a.getUnit().equals(unit)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(holder + " holds no " + unit));
    }

    static BigInteger held(OfflineChain chain, BootstrapFixture.Bootstrapped boot, String holder, String unit) throws Exception {
        return chain.utxosAt(smartWallet(chain, boot, holder)).stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> a.getUnit().equals(unit))
                .map(a -> a.getQuantity())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    static TransferTokenRequest transfer(String sender, String unit, String quantity, String recipient) {
        return new TransferTokenRequest(sender, unit, quantity, recipient, null, null, null, null, null, null, null);
    }
}
