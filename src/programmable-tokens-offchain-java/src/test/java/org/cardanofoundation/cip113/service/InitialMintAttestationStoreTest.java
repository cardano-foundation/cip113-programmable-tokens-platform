package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.entity.*;
import org.cardanofoundation.cip113.repository.*;
import org.cardanofoundation.cip113.service.module.Cip170MintChildBuilder;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.cardanofoundation.cip113.service.InitialMintFixtures.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = InitialMintAttestationStoreTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {"keri.enabled=true", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:initial_mint;MODE=PostgreSQL;NON_KEYWORDS=KEY,NEXT;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
class InitialMintAttestationStoreTest {
    @SpringBootConfiguration @EnableAutoConfiguration
    @EntityScan(basePackageClasses = MintAttestationIntentEntity.class)
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.cip113.repository", includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = {MintAttestationIntentRepository.class, RwaGenesisReservationRepository.class, RwaGenesisFundingReservationRepository.class, RwaTokenRegistrationRepository.class,
                    RwaTokenMemberRootSnapshotRepository.class, RwaTokenPowerUserRepository.class, ProgrammableTokenRegistryRepository.class}))
    @Import({InitialMintAttestationStore.class, MintAttestationStore.class})
    static class Config { @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules(); } }
    @Autowired InitialMintAttestationStore store;
    @Autowired MintAttestationStore mintStore;
    @Autowired MintAttestationIntentRepository intents;
    @Autowired RwaGenesisReservationRepository genesis;
    @Autowired RwaGenesisFundingReservationRepository funding;
    @Autowired ObjectMapper mapper;
    @Autowired RwaTokenRegistrationRepository registrations;
    @Autowired RwaTokenMemberRootSnapshotRepository snapshots;
    @Autowired RwaTokenPowerUserRepository powerUsers;
    @Autowired ProgrammableTokenRegistryRepository tokenRegistry;
    @MockBean RwaTokenCreationService creation;
    @MockBean ModuleHandlerFactory handlers;
    @MockBean Cip170MintChildBuilder childBuilder;
    @MockBean RwaTokenModuleHandler handler;
    @MockBean MintAttestationService mints;
    @MockBean ProtocolDeploymentResolver protocols;
    @MockBean(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) com.bloxbean.cardano.client.backend.api.BackendService bfBackendService;
    @BeforeEach void setup() throws Exception {
        intents.deleteAll(); funding.deleteAll(); genesis.deleteAll();
        registrations.deleteAll(); snapshots.deleteAll(); powerUsers.deleteAll(); tokenRegistry.deleteAll();
        when(mints.fields(any())).thenReturn(fields(false)); when(mints.attestation(any())).thenReturn(attestation(chain(false)));
        var deployment = deployment(); when(protocols.resolve(DEPLOYMENT)).thenReturn(deployment);
        when(handlers.getHandler(eq("rwa-token"), any())).thenReturn(handler);
    }
    MintAttestationIntentEntity prepared(String id) throws Exception {
        var i = new MintAttestationIntentEntity(); i.setId(id); i.setSessionId("session"); i.setWalletAid(APPROVAL.signerAid());
        i.setIssuerAid("issuer"); i.setCredentialSaid("credential"); i.setFieldsJson(mapper.writeValueAsString(fields(false)));
        i.setDigest(id); i.setDocumentJson("{}"); i.setPreimage("{}"); i.setStatus("PREPARED");
        i.setWalletKelFloor("0"); i.setExpiresAt(Instant.now().plusSeconds(600));
        i.setInitialRegistrationJson(mapper.writeValueAsString(registration(false))); i.setInitialPlanJson(mapper.writeValueAsString(plan()));
        var built = chain(false);
        var prefix = new RwaTokenModuleHandler.ChainBuildResult(built.genesisCborHex(), built.addPowerUserCborHex(),
                built.cmtaProvenanceCborHex(), built.issuanceProvenanceCborHex(), built.publishScriptsCborHex(),
                built.registrationCborHex(), null, null, null, built.globalStatePolicyId(),
                built.programmableTokenPolicyId(), built.denylistPolicyId(), built.powerUsersPolicyId(),
                built.genesisTxHash(), built.addPowerUserTxHash(), built.cmtaProvenanceTxHash(),
                built.issuanceProvenanceTxHash(), built.publishScriptsTxHash(), built.registrationTxHash(), null, null, null);
        i.setInitialPrefixJson(mapper.writeValueAsString(prefix));
        var reg = RwaTokenRegistrationEntity.builder().programmableTokenPolicyId(POLICY).issuerAdminPkh(ADMIN)
                .globalStatePolicyId(GS).denylistPolicyId("19".repeat(28)).powerUsersPolicyId("1a".repeat(28))
                .bootstrapTxHash(BOOTSTRAP).bootstrapOutputIndex(0).securityAssetNameHex(ASSET)
                .memberRootHashLocal("").memberRootHashOnchain("").build();
        var snapshot = new RwaTokenMemberRootSnapshotEntity(); snapshot.setProgrammableTokenPolicyId(POLICY);
        snapshot.setRootHash(""); snapshot.setBaselineRootHash(""); snapshot.setLeavesJson("[]");
        snapshot.setTxHash(built.genesisTxHash());
        var power = RwaTokenPowerUserEntity.builder().programmableTokenPolicyId(POLICY)
                .powerUserPkh(ADMIN).capabilities(31).label("Bootstrap admin").build();
        var registry = ProgrammableTokenRegistryEntity.builder().policyId(POLICY).moduleId("rwa-token").assetName(ASSET).build();
        i.setInitialSnapshotJson(mapper.writeValueAsString(java.util.Map.of("registration", reg,
                "memberSnapshot", snapshot, "bootstrapPowerUser", power, "tokenRegistry", registry)));
        return store.prepare(i, plan());
    }
    void anchored() throws Exception {
        prepared("intent"); var owner = mintStore.claimAnchor("intent"); mintStore.saveAnchor("intent", owner, "1", "{}");
    }
    @Test void previewCapturesRowsButRollsBackEveryCanonicalSideEffect() throws Exception {
        var built = chain(false);
        var prefix = new RwaTokenModuleHandler.ChainBuildResult(built.genesisCborHex(), built.addPowerUserCborHex(),
                built.cmtaProvenanceCborHex(), built.issuanceProvenanceCborHex(), built.publishScriptsCborHex(),
                built.registrationCborHex(), null, null, null, built.globalStatePolicyId(),
                built.programmableTokenPolicyId(), built.denylistPolicyId(), built.powerUsersPolicyId(),
                built.genesisTxHash(), built.addPowerUserTxHash(), built.cmtaProvenanceTxHash(),
                built.issuanceProvenanceTxHash(), built.publishScriptsTxHash(), built.registrationTxHash(), null, null, null);
        when(creation.buildPrepared(any(), any(), any(), isNull(), any())).thenAnswer(a -> {
            assertTrue(genesis.existsById(GS), "preview must reserve bootstrap before constructing the chain");
            assertTrue(funding.existsById(BOOTSTRAP.toLowerCase() + "#0"),
                    "preview must reserve pinned funding before constructing the chain");
            var reg = RwaTokenRegistrationEntity.builder().programmableTokenPolicyId(POLICY)
                    .issuerAdminPkh(ADMIN).globalStatePolicyId(GS).denylistPolicyId("19".repeat(28))
                    .powerUsersPolicyId("1a".repeat(28)).bootstrapTxHash(BOOTSTRAP).bootstrapOutputIndex(0)
                    .securityAssetNameHex(ASSET).memberRootHashLocal("")
                    .memberRootHashOnchain("").build();
            registrations.saveAndFlush(reg);
            var snapshot = new RwaTokenMemberRootSnapshotEntity(); snapshot.setProgrammableTokenPolicyId(POLICY);
            snapshot.setRootHash(""); snapshot.setBaselineRootHash(""); snapshot.setLeavesJson("[]");
            snapshot.setTxHash(prefix.genesisTxHash()); snapshots.saveAndFlush(snapshot);
            powerUsers.saveAndFlush(RwaTokenPowerUserEntity.builder().programmableTokenPolicyId(POLICY)
                    .powerUserPkh(ADMIN).capabilities(31).build());
            tokenRegistry.saveAndFlush(ProgrammableTokenRegistryEntity.builder().policyId(POLICY)
                    .moduleId("rwa-token").assetName(ASSET).build());
            funding.saveAndFlush(sideEffect());
            return prefix;
        });
        var preview = store.preview(registration(false), deployment(), plan(), Instant.now().plusSeconds(600));
        assertEquals(prefix.registrationTxHash(), preview.prefix().registrationTxHash());
        assertTrue(preview.snapshotJson().contains(POLICY));
        assertFalse(registrations.existsByProgrammableTokenPolicyId(POLICY));
        assertTrue(snapshots.findByProgrammableTokenPolicyIdAndRootHash(POLICY, "").isEmpty());
        assertTrue(powerUsers.findByProgrammableTokenPolicyId(POLICY).isEmpty());
        assertFalse(tokenRegistry.existsByPolicyId(POLICY));
        assertFalse(funding.existsById("side-effect"));
        assertFalse(genesis.existsById(GS), "preview bootstrap reservation must roll back");
        assertFalse(funding.existsById(BOOTSTRAP.toLowerCase() + "#0"),
                "preview funding reservation must roll back");
    }
    @Test void preparationReservationsAndIntentCommitTogetherOrRollBack() throws Exception {
        prepared("first");
        assertEquals(1, intents.count()); assertEquals(1, genesis.count()); assertEquals(1, funding.count());
        assertThrows(RuntimeException.class, () -> prepared("second"));
        assertEquals(1, intents.count()); assertEquals(1, genesis.count()); assertEquals(1, funding.count());
    }
    @Test void failedBuilderRollsBackSideEffectsWithoutPublishingChain() throws Exception {
        anchored(); var claim = store.claimBuild("intent");
        doAnswer(a -> { funding.saveAndFlush(sideEffect()); throw new IllegalStateException("evaluation failed"); })
                .when(handler).completeInitialMintChain(any(), any(), any(), any(), any());
        assertThrows(IllegalStateException.class, () -> store.build("intent", claim.owner()));
        assertFalse(funding.existsById("side-effect")); assertNull(store.get("intent").getInitialChainJson());
        mintStore.releaseBuild("intent", claim.owner()); assertEquals("ANCHORED", store.get("intent").getStatus());
    }
    @Test void publicationCommitsWholeChainAndSideEffectsAndRecoveryNeverRebuilds() throws Exception {
        anchored(); var expected = chain(false);
        doAnswer(a -> { funding.saveAndFlush(sideEffect()); return expected; }).when(handler).completeInitialMintChain(any(), any(), any(), any(), any());
        var claim = store.claimBuild("intent"); assertEquals(expected, store.build("intent", claim.owner()));
        assertTrue(funding.existsById("side-effect")); assertEquals("BUILT", store.get("intent").getStatus());
        var i = intents.findById("intent").orElseThrow(); i.setExpiresAt(Instant.now().minusSeconds(10)); intents.saveAndFlush(i);
        assertEquals(expected, store.claimBuild("intent").chain());
        assertThrows(RuntimeException.class, () -> store.release("intent"));
        verify(handler, times(1)).completeInitialMintChain(any(), any(), any(), any(), any());
    }
    @Test void finalValidationFailureRollsBackEvenSuccessfulBuilderSideEffects() throws Exception {
        anchored(); var claim = store.claimBuild("intent");
        doAnswer(a -> { funding.saveAndFlush(sideEffect()); return chain(true); }).when(handler).completeInitialMintChain(any(), any(), any(), any(), any());
        assertThrows(IllegalArgumentException.class, () -> store.build("intent", claim.owner()));
        assertFalse(funding.existsById("side-effect")); assertNull(store.get("intent").getInitialChainJson());
    }
    @Test void cancellationReleasesOnlyOwnedUnbuiltInputsAndFencesStaleBuilder() throws Exception {
        anchored(); var first = store.claimBuild("intent");
        assertThrows(RuntimeException.class, () -> store.release("intent"));
        var i = intents.findById("intent").orElseThrow(); i.setLeaseUntil(Instant.now().minusSeconds(1)); intents.saveAndFlush(i);
        assertEquals("RELEASED", store.release("intent").getStatus());
        assertEquals(0, funding.count()); assertEquals(0, genesis.count());
        assertThrows(RuntimeException.class, () -> store.build("intent", first.owner()));
        assertThrows(RuntimeException.class, () -> store.claimBuild("intent"));
        verifyNoInteractions(creation);
    }
    @Test void staleWorkerCannotPublishOrReleaseNewerBuildClaim() throws Exception {
        anchored(); var first = store.claimBuild("intent");
        var i = intents.findById("intent").orElseThrow(); i.setLeaseUntil(Instant.now().minusSeconds(1)); intents.saveAndFlush(i);
        var second = store.claimBuild("intent"); assertNotEquals(first.owner(), second.owner());
        assertThrows(RuntimeException.class, () -> store.build("intent", first.owner()));
        mintStore.releaseBuild("intent", first.owner()); assertEquals(second.owner(), store.get("intent").getClaimOwner());
    }
    @Test void concurrentBuildClaimsHaveOneWinner() throws Exception {
        anchored(); var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Boolean> claim = () -> { start.await(); try { store.claimBuild("intent"); return true; } catch (RuntimeException e) { return false; } };
            var a = workers.submit(claim); var b = workers.submit(claim); start.countDown();
            assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        }
    }
    @Test void failedOwnershipReleaseRollsBackEarlierDeletes() throws Exception {
        prepared("intent"); var row = funding.findById(BOOTSTRAP + "#0").orElseThrow(); row.setGlobalStatePolicyId("ff".repeat(28)); funding.saveAndFlush(row);
        assertThrows(RuntimeException.class, () -> store.release("intent"));
        assertEquals(1, genesis.count()); assertEquals(1, funding.count()); assertEquals("PREPARED", store.get("intent").getStatus());
    }
    @Test void expiryCleanupReleasesOnlyExpiredUnbuiltAttempt() throws Exception {
        prepared("intent");
        var now = Instant.now();
        assertFalse(store.releaseExpired("intent", now));
        assertEquals(1, funding.count());
        var i = intents.findById("intent").orElseThrow();
        i.setExpiresAt(now.minusSeconds(1)); intents.saveAndFlush(i);
        assertEquals(java.util.List.of("intent"), intents.findExpiredInitialIds(now, "",
                java.util.List.of("PREPARED", "ANCHORED", "BUILDING"), org.springframework.data.domain.PageRequest.of(0, 64)));
        assertTrue(store.releaseExpired("intent", now));
        assertEquals("RELEASED", store.get("intent").getStatus());
        assertEquals(0, funding.count()); assertEquals(0, genesis.count());
        assertFalse(store.releaseExpired("intent", now));
    }
    @Test void expiryCleanupPreservesLiveBuildClaim() throws Exception {
        anchored(); store.claimBuild("intent");
        var i = intents.findById("intent").orElseThrow();
        i.setExpiresAt(Instant.now().minusSeconds(1)); intents.saveAndFlush(i);
        assertFalse(store.releaseExpired("intent", Instant.now()));
        assertEquals("BUILDING", store.get("intent").getStatus());
        assertEquals(1, funding.count()); assertEquals(1, genesis.count());
    }
    private void publishedWithExpiredUnstartedChain() throws Exception {
        anchored();
        when(handler.completeInitialMintChain(any(), any(), any(), any(), any())).thenReturn(chain(false));
        var claim = store.claimBuild("intent"); store.build("intent", claim.owner());
        when(bfBackendService.getBlockService().getLatestBlock().isSuccessful()).thenReturn(true);
        when(bfBackendService.getBlockService().getLatestBlock().getValue().getHash()).thenReturn("ab".repeat(32));
        when(bfBackendService.getBlockService().getLatestBlock().getValue().getSlot()).thenReturn(1_000_000_000L);
        when(bfBackendService.getTransactionService().getTransaction(anyString()).code()).thenReturn(404);
        when(bfBackendService.getUtxoService().getUtxos(PAYER, 100, 1).isSuccessful()).thenReturn(true);
        when(bfBackendService.getUtxoService().getUtxos(PAYER, 100, 1).getValue()).thenReturn(plan().funding());
    }
    @Test void expiredUnstartedArchivePreservesPublishedChainRowsAndEveryReservation() throws Exception {
        publishedWithExpiredUnstartedChain();
        var original = store.get("intent").getInitialChainJson();
        assertTrue(store.recovery(store.get("intent")).canStartNewPolicy());
        assertEquals("ARCHIVED_EXPIRED", store.archiveExpired("intent").status());
        assertEquals(original, store.get("intent").getInitialChainJson());
        assertEquals(chain(false), store.chain(store.get("intent")));
        assertEquals(1, genesis.count()); assertEquals(1, funding.count());
        assertTrue(registrations.existsByProgrammableTokenPolicyId(POLICY));
        assertThrows(RuntimeException.class, () -> store.release("intent"));
        assertThrows(RuntimeException.class, () -> store.claimBuild("intent"));
        assertThrows(RuntimeException.class, () -> prepared("another"));
        assertEquals("ARCHIVED_EXPIRED", store.archiveExpired("intent").status());
    }
    @Test void stillValidMintCannotBeArchivedEvenWhenIntentApprovalExpired() throws Exception {
        publishedWithExpiredUnstartedChain();
        var i = intents.findById("intent").orElseThrow(); i.setExpiresAt(Instant.now().minusSeconds(60)); intents.saveAndFlush(i);
        when(bfBackendService.getBlockService().getLatestBlock().getValue().getSlot()).thenReturn(999_999_999L);
        assertEquals("UNCONFIRMED", store.recovery(store.get("intent")).status());
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
        assertEquals("BUILT", store.get("intent").getStatus());
    }
    @Test void changingTipHashAtSameSlotCannotAuthorizeArchive() throws Exception {
        publishedWithExpiredUnstartedChain();
        when(bfBackendService.getBlockService().getLatestBlock().getValue().getHash())
                .thenReturn("ab".repeat(32), "cd".repeat(32));
        assertEquals("UNKNOWN", store.recovery(store.get("intent")).status());
    }
    @Test void changingTipSlotAtSameHashCannotAuthorizeArchive() throws Exception {
        publishedWithExpiredUnstartedChain();
        when(bfBackendService.getBlockService().getLatestBlock().getValue().getSlot())
                .thenReturn(1_000_000_000L, 1_000_000_001L);
        assertEquals("UNKNOWN", store.recovery(store.get("intent")).status());
    }
    @Test void unavailableOrSpentBootstrapCannotAuthorizeArchive() throws Exception {
        publishedWithExpiredUnstartedChain();
        when(bfBackendService.getUtxoService().getUtxos(PAYER, 100, 1).getValue()).thenReturn(java.util.List.of());
        assertEquals("UNKNOWN", store.recovery(store.get("intent")).status());
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    @Test void providerErrorIsUnknownAndCannotArchive() throws Exception {
        publishedWithExpiredUnstartedChain();
        when(bfBackendService.getTransactionService().getTransaction(anyString()).code()).thenReturn(503);
        assertEquals("UNKNOWN", store.recovery(store.get("intent")).status());
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    @Test void confirmedMintWithUnspentBootstrapIsContradictoryAndUnknown() throws Exception {
        publishedWithExpiredUnstartedChain();
        var hash = chain(false).registrationTxHash();
        confirmedTransaction(hash);
        var recovery = store.recovery(store.get("intent"));
        assertEquals("UNKNOWN", recovery.status());
        assertTrue(recovery.reason().contains("contradicts"));
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    @Test void partialConfirmedChainWithSpentBootstrapMustResumeAndCannotArchive() throws Exception {
        publishedWithExpiredUnstartedChain();
        confirmedTransaction(chain(false).genesisTxHash());
        when(bfBackendService.getUtxoService().getUtxos(PAYER, 100, 1).getValue()).thenReturn(java.util.List.of());
        var recovery = store.recovery(store.get("intent"));
        assertEquals("PARTIAL", recovery.status()); assertFalse(recovery.canStartNewPolicy());
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    @Test void laterGenesisExpiryBlocksArchiveEvenAfterMintExpired() throws Exception {
        publishedWithExpiredUnstartedChain(); changeGenesisTtl(1_000_000_010L);
        assertEquals("UNCONFIRMED", store.recovery(store.get("intent")).status());
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    @Test void missingGenesisExpiryCannotAuthorizeArchive() throws Exception {
        publishedWithExpiredUnstartedChain(); changeGenesisTtl(0L);
        var recovery = store.recovery(store.get("intent"));
        assertEquals("UNKNOWN", recovery.status()); assertTrue(recovery.reason().contains("finite validity"));
        assertThrows(RuntimeException.class, () -> store.archiveExpired("intent"));
    }
    private void changeGenesisTtl(long ttl) throws Exception {
        var saved = store.get("intent");
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(saved.getInitialChainJson());
        var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(
                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(json.get("genesisCborHex").asText()));
        tx.getBody().setTtl(ttl);
        json.put("genesisCborHex", com.bloxbean.cardano.client.util.HexUtil.encodeHexString(tx.serialize()));
        json.put("genesisTxHash", com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx.serialize()));
        saved.setInitialChainJson(mapper.writeValueAsString(json)); intents.saveAndFlush(saved);
    }
    @SuppressWarnings("unchecked")
    private void confirmedTransaction(String hash) throws Exception {
        com.bloxbean.cardano.client.api.model.Result<com.bloxbean.cardano.client.backend.model.TransactionContent> response =
                mock(com.bloxbean.cardano.client.api.model.Result.class);
        var tx = new com.bloxbean.cardano.client.backend.model.TransactionContent();
        tx.setHash(hash); tx.setBlock("ab".repeat(32)); tx.setValidContract(true);
        when(response.code()).thenReturn(200); when(response.isSuccessful()).thenReturn(true); when(response.getValue()).thenReturn(tx);
        when(bfBackendService.getTransactionService().getTransaction(hash)).thenReturn(response);
    }
    private RwaGenesisFundingReservationEntity sideEffect() { var row = new RwaGenesisFundingReservationEntity(); row.setInputRef("side-effect"); row.setGlobalStatePolicyId(GS); return row; }
}
