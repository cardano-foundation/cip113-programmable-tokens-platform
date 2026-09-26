package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.bootstrap.*;
import org.cardanofoundation.cip113.service.module.*;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InitialMintAttestationServiceTest {
    static final String ID = "11111111-1111-1111-1111-111111111111";
    static final String PAYER = InitialMintFixtures.PAYER;
    static final String POLICY = InitialMintFixtures.POLICY;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final MintAttestationRequestVerifier verifier = mock(MintAttestationRequestVerifier.class);
    final MintAttestationService mints = mock(MintAttestationService.class);
    final MintAttestationStore mintStore = mock(MintAttestationStore.class);
    final InitialMintAttestationStore store = mock(InitialMintAttestationStore.class);
    final MintAttestationTransport transport = mock(MintAttestationTransport.class);
    final ModuleHandlerFactory handlers = mock(ModuleHandlerFactory.class);
    final ProtocolDeploymentResolver protocols = mock(ProtocolDeploymentResolver.class);
    final RwaTokenModuleHandler handler = mock(RwaTokenModuleHandler.class);
    final InitialMintAttestationService service = new InitialMintAttestationService(verifier, mints, mintStore, store, transport, handlers, protocols, mapper);
    MintAttestationIntentEntity saved;

    @BeforeEach void setup() throws Exception {
        var params = InitialMintFixtures.deployment();
        when(protocols.resolve(any())).thenReturn(params);
        when(handlers.getHandler(eq("rwa-token"), any())).thenReturn(handler);
        when(handler.planGenesis(any(), any())).thenReturn(InitialMintFixtures.plan());
        when(store.preview(any(), any(), any(), any())).thenAnswer(a -> {
            RwaTokenRegisterRequest r = a.getArgument(0);
            var built = InitialMintFixtures.chain(r.getCip68Metadata() != null);
            var prefix = new RwaTokenModuleHandler.ChainBuildResult(built.genesisCborHex(), built.addPowerUserCborHex(),
                    built.cmtaProvenanceCborHex(), built.issuanceProvenanceCborHex(), built.publishScriptsCborHex(),
                    built.registrationCborHex(), null, null, null, built.globalStatePolicyId(),
                    built.programmableTokenPolicyId(), built.denylistPolicyId(), built.powerUsersPolicyId(),
                    built.genesisTxHash(), built.addPowerUserTxHash(), built.cmtaProvenanceTxHash(),
                    built.issuanceProvenanceTxHash(), built.publishScriptsTxHash(), built.registrationTxHash(), null, null, null);
            return new InitialMintAttestationStore.Preview(prefix, "{}");
        });
        when(mints.config()).thenReturn(Map.of("network", "preview", "audience", "https://api.example/api/v1"));
        when(mints.normalize(any())).thenAnswer(a -> {
            var r = (MintAttestationRequest) a.getArgument(0);
            String destination = InitialMintFixtures.DEST;
            return new MintAttestationRequest(r.sessionId(), r.network(), r.protocolTxHash(), r.tokenPolicyId(), r.assetName(), r.quantity(), r.feePayerAddress(), r.recipientAddress(), destination);
        });
        when(mints.boundSession(any())).thenReturn(KycSessionEntity.builder().sessionId("session").aid("E" + "a".repeat(43)).credentialAid("credential").build());
        when(mints.fields(any())).thenAnswer(a -> mapper.readValue(((MintAttestationIntentEntity)a.getArgument(0)).getFieldsJson(), MintAttestationRequest.class));
        when(mints.targetDeadline(any())).thenReturn(Instant.now().plusSeconds(900));
        when(verifier.audience()).thenReturn("https://api.example/api/v1");
        when(transport.issuerAid()).thenReturn("E" + "b".repeat(43));
        doAnswer(a -> { ((MintAttestationIntentEntity)a.getArgument(0)).setWalletKelFloor("0"); return null; }).when(transport).prepareExchange(any(), any());
        when(store.prepare(any(), any())).thenAnswer(a -> { saved = a.getArgument(0); return saved; });
        when(store.get(ID)).thenAnswer(a -> saved);
    }
    RwaTokenRegisterRequest registration() {
        return RwaTokenRegisterRequest.builder().moduleId("rwa-token").feePayerAddress(PAYER).assetName("746f6b656e")
                .initialMintQuantity("10").initialMintableAmount(100L).adminPubKeyHash(InitialMintFixtures.ADMIN).build();
    }
    @Test void refusesInitialMintWhoseFrozenTransactionIsNearExpiry() throws Exception {
        when(mints.targetDeadline(any())).thenReturn(Instant.now().plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(prepareBody(registration()), new HttpHeaders()));
        verify(store, never()).prepare(any(), any());
    }
    byte[] prepareBody(RwaTokenRegisterRequest registration) throws Exception {
        return mapper.writeValueAsBytes(new InitialMintAttestationService.Prepare(ID, "session", registration));
    }
    byte[] action(String payer) throws Exception { return mapper.writeValueAsBytes(new InitialMintAttestationService.Action("session", payer)); }
    @Test void preparesFrozenInitialMintAndExactPublicSaidBeforeDispatch() throws Exception {
        var view = service.prepare(prepareBody(registration()), new HttpHeaders());
        assertEquals(ID, view.intentId()); assertEquals(POLICY, view.fields().tokenPolicyId());
        assertEquals("PREPARED", view.status()); assertEquals("NOT_BUILT", view.submissionStatus());
        assertEquals(MintTxHashPayload.digest(InitialMintFixtures.chain(false).registrationTxHash()), saved.getDigest());
        assertEquals(Set.of("d", "txHash"), mapper.readTree(saved.getDocumentJson()).properties().stream()
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertEquals(InitialMintFixtures.chain(false).registrationTxHash(), mapper.readTree(saved.getDocumentJson()).get("txHash").asText());
        assertFalse(saved.getDocumentJson().contains("session"));
        assertNotEquals(saved.getPreimage(), saved.getDocumentJson());
        verify(verifier).verifyAndConsume(eq(InitialMintAttestationService.PATH + "/prepare"), any(), eq(PAYER), any());
        verify(transport, never()).sendAndWait(any(), anyBoolean());
        verify(mintStore, never()).create(any());
    }
    @Test void sameRequestIdReturnsSavedAttemptAndRejectsDifferentSettings() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        when(store.find(ID)).thenReturn(Optional.of(saved));
        assertEquals(ID, service.prepare(prepareBody(registration()), new HttpHeaders()).intentId());
        var changed = registration(); changed.setInitialMintQuantity("11");
        assertThrows(IllegalArgumentException.class, () -> service.prepare(prepareBody(changed), new HttpHeaders()));
        verify(store, times(1)).prepare(any(), any());
    }
    @Test void cip68SignsOnlyItsFrozenMintHash() throws Exception {
        var r = registration(); r.setCip68Metadata(InitialMintFixtures.METADATA);
        var view = service.prepare(prepareBody(r), new HttpHeaders());
        assertTrue(view.fields().assetName().startsWith("0014df10"));
        var doc = mapper.readTree(saved.getDocumentJson());
        assertEquals(InitialMintFixtures.chain(true).registrationTxHash(), doc.get("txHash").asText());
        assertEquals(2, doc.size());
    }
    @Test void rejectsZeroSupplyAndRawClientAttestationBeforeReservations() throws Exception {
        var zero = registration(); zero.setInitialMintQuantity("0");
        assertThrows(IllegalArgumentException.class, () -> service.prepare(prepareBody(zero), new HttpHeaders()));
        var forged = registration(); forged.setAttestation(new Cip170AttestationData("aid", "digest", "1", "1.0"));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(prepareBody(forged), new HttpHeaders()));
        verify(store, never()).prepare(any(), any());
    }
    @Test void authenticateIdActionsAgainstStoredPayerAndRejectChangedSession() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        byte[] body = mapper.writeValueAsBytes(new InitialMintAttestationService.Action("another-session", PAYER));
        assertThrows(IllegalArgumentException.class, () -> service.anchor(ID, body, new HttpHeaders()));
        verify(verifier).verifyAndConsume(eq(InitialMintAttestationService.PATH + "/" + ID + "/anchor"), eq(body), eq(PAYER), any());
        verify(mintStore, never()).claimAnchor(any());
    }
    @Test void builtRecoveryRetainsExactChainAfterExpiryWithoutKeriOrUtxoChecks() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("BUILT"); saved.setExpiresAt(Instant.now().minusSeconds(100));
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.chain(saved)).thenReturn(chain);
        clearInvocations(mints, transport);
        assertSame(chain, service.finalizeCreation(ID, action(PAYER), new HttpHeaders()));
        verify(mints, never()).boundSession(any()); verify(transport, never()).verifiedEvent(any(), any(), any());
        verify(store, never()).claimBuild(any());
    }
    @Test void tamperedFrozenCreationRequestFailsBeforeBuild() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("ANCHORED"); saved.setSequenceNumber("1");
        var changed = registration(); changed.setInitialMintQuantity("11");
        saved.setInitialRegistrationJson(mapper.writeValueAsString(changed));
        assertThrows(IllegalStateException.class, () -> service.finalizeCreation(ID, action(PAYER), new HttpHeaders()));
        verify(store, never()).claimBuild(any());
    }
    @Test void expiredUnbuiltIntentCannotBuild() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("ANCHORED"); saved.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(RuntimeException.class, () -> service.finalizeCreation(ID, action(PAYER), new HttpHeaders()));
        verify(store, never()).claimBuild(any());
    }
    @Test void combinedApprovalSignsOnceAndBuildsAfterAnchoring() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        clearInvocations(verifier);
        var combined = spy(service);
        doNothing().when(combined).verifyAnchor(any());
        when(mintStore.claimAnchor(ID)).thenReturn("anchor-owner");
        when(mintStore.beginDispatch(ID, "anchor-owner")).thenReturn(true);
        when(transport.sendAndWait(saved, true)).thenReturn(new MintAttestationTransport.Anchor("1", "event", "note"));
        doAnswer(a -> { saved.setStatus("ANCHORED"); saved.setSequenceNumber("1"); return null; })
                .when(mintStore).saveAnchor(ID, "anchor-owner", "1", "event");
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.claimBuild(ID)).thenReturn(new InitialMintAttestationStore.Claim("build-owner", null));
        when(store.build(ID, "build-owner")).thenReturn(chain);

        byte[] body = action(PAYER);
        assertSame(chain, combined.approveAndBuild(ID, body, new HttpHeaders()));
        verify(verifier, times(1)).verifyAndConsume(eq(InitialMintAttestationService.PATH + "/" + ID + "/approve-and-build"), eq(body), eq(PAYER), any());
        verify(mintStore).beginDispatch(ID, "anchor-owner");
        verify(transport).acknowledge("note");
        verify(store).build(ID, "build-owner");
    }
    @Test void combinedRetryFromAnchoredSkipsWalletDispatch() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("ANCHORED");
        var combined = spy(service);
        doNothing().when(combined).verifyAnchor(any());
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.claimBuild(ID)).thenReturn(new InitialMintAttestationStore.Claim("owner", null));
        when(store.build(ID, "owner")).thenReturn(chain);
        assertSame(chain, combined.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(mintStore, never()).claimAnchor(any());
        verify(transport, never()).sendAndWait(any(), anyBoolean());
    }
    @Test void combinedBuiltRetryAfterExpiryReturnsStoredChainWithoutKeri() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("BUILT"); saved.setExpiresAt(Instant.now().minusSeconds(1));
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.chain(saved)).thenReturn(chain);
        clearInvocations(mints, transport);
        assertSame(chain, service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(mints, never()).boundSession(any());
        verify(transport, never()).sendAndWait(any(), anyBoolean());
        verify(store, never()).claimBuild(any());
    }
    @Test void combinedRejectsChangedSessionBeforeDispatch() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        byte[] body = mapper.writeValueAsBytes(new InitialMintAttestationService.Action("other", PAYER));
        assertThrows(IllegalArgumentException.class, () -> service.approveAndBuild(ID, body, new HttpHeaders()));
        verify(mintStore, never()).claimAnchor(any());
        verify(store, never()).claimBuild(any());
    }
    @Test void combinedRejectsMalformedActionAndUnavailableKeriProof() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        assertThrows(Exception.class, () -> service.approveAndBuild(ID, "{".getBytes(), new HttpHeaders()));
        saved.setStatus("ANCHORED"); saved.setSequenceNumber("1");
        assertThrows(IllegalStateException.class, () -> service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(store, never()).claimBuild(any());
    }
    @Test void combinedDoesNotBuildExpiredOrCancelledIntent() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("ANCHORED"); saved.setExpiresAt(Instant.now().minusSeconds(1));
        assertThrows(RuntimeException.class, () -> service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        saved.setStatus("RELEASED"); saved.setExpiresAt(Instant.now().plusSeconds(60));
        assertThrows(RuntimeException.class, () -> service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(store, never()).claimBuild(any());
    }
    @Test void combinedReturnsChainIfAnotherRequestBuiltWhileWaiting() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        when(mintStore.claimAnchor(ID)).thenReturn("owner");
        when(mintStore.beginDispatch(ID, "owner")).thenReturn(true);
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.chain(saved)).thenReturn(chain);
        when(transport.sendAndWait(saved, true)).thenAnswer(a -> {
            saved.setStatus("BUILT");
            return new MintAttestationTransport.Anchor("1", "event", "note");
        });
        assertSame(chain, service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(store, never()).claimBuild(any());
        verify(store, never()).build(any(), any());
    }
    @Test void combinedResumeUsesExistingDispatchMarker() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders());
        saved.setStatus("ANCHORING"); saved.setDispatchStarted(true);
        var combined = spy(service);
        doNothing().when(combined).verifyAnchor(any());
        when(mintStore.claimAnchor(ID)).thenReturn("owner");
        when(mintStore.beginDispatch(ID, "owner")).thenReturn(false);
        when(transport.sendAndWait(saved, false)).thenReturn(new MintAttestationTransport.Anchor("1", "event", "note"));
        doAnswer(a -> { saved.setStatus("ANCHORED"); return null; }).when(mintStore).saveAnchor(ID, "owner", "1", "event");
        var chain = mock(RwaTokenModuleHandler.ChainBuildResult.class);
        when(store.claimBuild(ID)).thenReturn(new InitialMintAttestationStore.Claim("builder", null));
        when(store.build(ID, "builder")).thenReturn(chain);
        assertSame(chain, combined.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        verify(transport).sendAndWait(saved, false);
        verify(transport, never()).sendAndWait(saved, true);
    }
    @Test void recoveryDoesNotQueryChainForDifferentSession() throws Exception {
        var intent = new MintAttestationIntentEntity(); intent.setSessionId("owner");
        when(store.get(ID)).thenReturn(intent);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> service.recovery(ID, "other"));
        verify(store, never()).recovery(any());
    }
    @Test void archiveAuthenticatesSavedPayerBeforeMutatingState() throws Exception {
        var intent = new MintAttestationIntentEntity(); intent.setSessionId("session");
        intent.setFieldsJson(mapper.writeValueAsString(InitialMintFixtures.fields(false)));
        when(store.get(ID)).thenReturn(intent);
        byte[] body = mapper.writeValueAsBytes(new InitialMintAttestationService.Action("session", PAYER));
        var headers = new HttpHeaders();
        doThrow(new IllegalArgumentException("Signature invalid")).when(verifier)
                .verifyAndConsume(InitialMintAttestationService.PATH + "/" + ID + "/archive-expired", body, PAYER, headers);
        assertThrows(IllegalArgumentException.class, () -> service.archiveExpired(ID, body, headers));
        verify(store, never()).archiveExpired(anyString());
    }

    @Test void shorterGenesisDeadlineClampsApprovalAndNearExpiryGenesisBlocksPreparation() throws Exception {
        var genesisDeadline = Instant.now().plusSeconds(400);
        when(mints.targetDeadline(any())).thenReturn(Instant.now().plusSeconds(900), genesisDeadline);
        assertEquals(genesisDeadline.minusSeconds(120), service.prepare(prepareBody(registration()), new HttpHeaders()).expiresAt());
        clearInvocations(store);
        when(mints.targetDeadline(any())).thenReturn(Instant.now().plusSeconds(900), Instant.now().plusSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(prepareBody(registration()), new HttpHeaders()));
        verify(store, never()).prepare(any(), any());
    }
    @Test void archivedAttemptCannotRedispatchOrBuild() throws Exception {
        service.prepare(prepareBody(registration()), new HttpHeaders()); saved.setStatus("ARCHIVED_EXPIRED");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.approveAndBuild(ID, action(PAYER), new HttpHeaders()));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.anchor(ID, action(PAYER), new HttpHeaders()));
        verify(mintStore, never()).claimAnchor(any()); verify(store, never()).claimBuild(any());
    }

}
