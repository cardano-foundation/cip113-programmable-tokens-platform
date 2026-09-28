package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.entity.KycIssuanceEntity;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.ScriptParams;
import org.cardanofoundation.cip113.repository.KycIssuanceRepository;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.cardanofoundation.cip113.service.module.Cip170MintChildBuilder;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MintAttestationServiceTest {
    private final MintAttestationStore store = mock(MintAttestationStore.class);
    private final MintAttestationRequestVerifier verifier = mock(MintAttestationRequestVerifier.class);
    private final MintAttestationTransport transport = mock(MintAttestationTransport.class);
    private final KycSessionRepository sessions = mock(KycSessionRepository.class);
    private final KycIssuanceRepository issuances = mock(KycIssuanceRepository.class);
    private final ProtocolDeploymentResolver protocols = mock(ProtocolDeploymentResolver.class);
    private final ObjectProvider<TokenOperationsService> operationsProvider = mock(ObjectProvider.class);
    private final TokenOperationsService operations = mock(TokenOperationsService.class);
    private final Cip170MintChildBuilder childBuilder = mock(Cip170MintChildBuilder.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final org.cardanofoundation.conversions.CardanoConverters converters =
            org.cardanofoundation.conversions.ClasspathConversionsFactory.createConverters(
                    org.cardanofoundation.conversions.domain.NetworkType.PREVIEW);
    private final MintAttestationService service = new MintAttestationService(store, verifier, transport, sessions,
            issuances, protocols, new AppConfig.Network("preview"), mapper, operationsProvider, childBuilder, converters);
    private final String payer = AddressProvider.getBaseAddress(Credential.fromKey("01".repeat(28)),
            Credential.fromKey("02".repeat(28)), Networks.preview()).getAddress();
    private final String recipient = AddressProvider.getBaseAddress(Credential.fromKey("03".repeat(28)),
            Credential.fromScript("04".repeat(28)), Networks.preview()).getAddress();
    private KycSessionEntity session;
    private MintAttestationIntentEntity saved;
    @BeforeEach void setup() throws Exception {
        var deployment = mock(ProtocolBootstrapParams.class);
        when(deployment.txHash()).thenReturn("05".repeat(32));
        when(deployment.programmableLogicBase()).thenReturn(new ScriptParams("06".repeat(28)));
        when(protocols.resolve(any())).thenReturn(deployment);
        session = KycSessionEntity.builder().sessionId("session").aid("E" + "a".repeat(43))
                .credentialAid("credential").cardanoAddress(HexUtil.encodeHexString(new com.bloxbean.cardano.client.address.Address(payer).getBytes())).build();
        when(sessions.findById("session")).thenReturn(Optional.of(session));
        when(issuances.findById("session")).thenReturn(Optional.empty());
        when(transport.issuerAid()).thenReturn("E" + "b".repeat(43));
        when(verifier.audience()).thenReturn("https://issuer.example/api/v1");
        when(store.find(anyString())).thenReturn(Optional.empty());
        when(operationsProvider.getObject()).thenReturn(operations);
        when(operations.buildMintDraft(any())).thenAnswer(a -> draft(a.getArgument(0)));
        when(store.create(any())).thenAnswer(a -> { saved = a.getArgument(0); saved.setWalletKelFloor("0"); return saved; });
    }
    private MintAttestationRequest request(String recipient, String destination) {
        return new MintAttestationRequest("session", "preview", null, "07".repeat(28), "", "00042", payer, recipient, destination,
                "11111111-1111-4111-8111-111111111111");
    }
    private String draft(MintAttestationRequest f) throws Exception {
        var asset = Asset.builder().name("0x" + f.assetName()).value(new BigInteger(f.quantity())).build();
        var delivered = TransactionOutput.builder().address(f.programmableRecipientAddress())
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000L))
                        .multiAssets(List.of(MultiAsset.builder().policyId(f.tokenPolicyId())
                                .assets(List.of(asset)).build())).build()).build();
        var change = TransactionOutput.builder().address(f.feePayerAddress())
                .value(Value.builder().coin(BigInteger.valueOf(7_000_000L)).build()).build();
        var body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("55".repeat(32)).index(0).build()))
                .outputs(List.of(delivered, change)).fee(BigInteger.valueOf(200_000L))
                .ttl(converters.time().toSlot(LocalDateTime.ofInstant(
                        java.time.Instant.now().plusSeconds(900), ZoneOffset.UTC)))
                .mint(List.of(MultiAsset.builder().policyId(f.tokenPolicyId()).assets(List.of(asset)).build())).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true).build().serializeToHex();
    }
    @Test void freezesDefaultDeploymentAndActualProgrammableRecipientWithScriptStakeType() throws Exception {
        var view = service.prepare(mapper.writeValueAsBytes(request(recipient, null)), new HttpHeaders());
        assertEquals("05".repeat(32), view.fields().protocolTxHash());
        assertEquals("42", view.fields().quantity());
        String expected = AddressProvider.getBaseAddress(Credential.fromScript("06".repeat(28)),
                Credential.fromScript("04".repeat(28)), Networks.preview()).getAddress();
        assertEquals(expected, view.fields().programmableRecipientAddress());
        assertEquals("NOT_CHECKED", view.authorityStatus());
        assertEquals(MintTxHashPayload.digest(view.targetTxHash()), view.digest());
        assertEquals(Set.of("d", "txHash"), mapper.readTree(saved.getDocumentJson()).properties().stream()
                .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()));
        assertNull(view.documentUrl());
        assertTrue(view.expiresAt().isBefore(java.time.Instant.now().plusSeconds(790)),
                "Approval must expire before the frozen mint validity deadline");
        assertNotEquals(saved.getDocumentJson(), saved.getPreimage());
        verify(verifier).verifyAndConsume(eq("/keri/mint-attestations/prepare"), any(), eq(payer), any());
    }
    @Test void rejectsMintThatWillExpireBeforeApprovalCanFinish() throws Exception {
        doAnswer(a -> {
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(draft(a.getArgument(0))));
            tx.getBody().setTtl(converters.time().toSlot(LocalDateTime.ofInstant(
                    java.time.Instant.now().plusSeconds(60), ZoneOffset.UTC)));
            return tx.serializeToHex();
        }).when(operations).buildMintDraft(any());
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare(mapper.writeValueAsBytes(request(recipient, null)), new HttpHeaders()));
        verify(store, never()).create(any());
    }
    @Test void rejectsWrongDerivedDestinationAndUnadmittedOrReboundSession() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.prepare(mapper.writeValueAsBytes(request(recipient, payer)), new HttpHeaders()));
        var issue = new KycIssuanceEntity(); issue.setStatus("WAITING");
        when(issuances.findById("session")).thenReturn(Optional.of(issue));
        assertThrows(IllegalArgumentException.class, () -> service.prepare(mapper.writeValueAsBytes(request(recipient, null)), new HttpHeaders()));
        when(issuances.findById("session")).thenReturn(Optional.empty());
        session.setCardanoAddress(recipient);
        assertThrows(IllegalArgumentException.class, () -> service.prepare(mapper.writeValueAsBytes(request(recipient, null)), new HttpHeaders()));
        verify(store, never()).create(any()); verify(transport, never()).prepareExchange(any(), any());
    }
    @Test void anchorRejectsChangedQuantityBeforeAnyNotificationAndBindsResolvedFields() throws Exception {
        var prepared = service.prepare(mapper.writeValueAsBytes(request(recipient, null)), new HttpHeaders());
        when(store.get(prepared.intentId())).thenReturn(saved);
        var f = prepared.fields();
        var changed = new MintAttestationRequest(f.sessionId(), f.network(), f.protocolTxHash(), f.tokenPolicyId(), f.assetName(),
                "43", f.feePayerAddress(), f.recipientAddress(), f.programmableRecipientAddress());
        assertThrows(IllegalArgumentException.class, () -> service.anchor(prepared.intentId(), mapper.writeValueAsBytes(changed), new HttpHeaders()));
        verify(transport, never()).sendAndWait(any(), anyBoolean());
        verify(store, never()).claimAnchor(any());
    }
}
