package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.config.SchemaConfig;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.keri.IdentifierConfig;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import id.veridian.signify.app.Contacting;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.generated.keria.model.Contact;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class KeriIssuePreflightTest {

    private final SignifyClient client = mock(SignifyClient.class);
    private final KycSessionRepository sessions = mock(KycSessionRepository.class);

    @Test
    void missingRecipientContactPreventsCredentialIssuance() throws Exception {
        Contacting.Contacts contacts = mock(Contacting.Contacts.class);
        when(client.contacts()).thenReturn(contacts);
        when(contacts.get("wallet-aid")).thenReturn(Optional.empty());
        KeriService service = service("http://127.0.0.1:1/oobi/");

        assertThrows(IllegalStateException.class,
                () -> service.issueCredential("session", "A", "B", "a@example.test"));

        verify(client, never()).credentials();
        verify(client, never()).ipex();
    }

    @Test
    void unavailableSchemaPreventsCredentialIssuance() throws Exception {
        Contacting.Contacts contacts = mock(Contacting.Contacts.class);
        when(client.contacts()).thenReturn(contacts);
        when(contacts.get("wallet-aid")).thenReturn(Optional.of(new Contact().id("wallet-aid")));
        KeriService service = service("http://127.0.0.1:1/oobi/");

        assertThrows(IllegalStateException.class,
                () -> service.issueCredential("session", "A", "B", "a@example.test"));

        verify(client, never()).credentials();
        verify(client, never()).ipex();
    }

    private KeriService service(String schemaBase) {
        when(sessions.findById("session")).thenReturn(Optional.of(KycSessionEntity.builder()
                .sessionId("session").aid("wallet-aid").build()));
        SchemaConfig schema = new SchemaConfig();
        schema.setBaseUrl(schemaBase);
        SchemaConfig.SchemaEntry entry = new SchemaConfig.SchemaEntry();
        entry.setSaid("expected-said");
        entry.setLabel("Test");
        schema.setSchemas(Map.of("USER", entry));
        return new KeriService(mock(IdentifierConfig.class), client, sessions,
                mock(KycProofService.class), schema, mock(KycIssuanceStore.class), new ObjectMapper(),
                mock(QuickTxBuilder.class), List.of(), "issuer", "registry", "", "preview");
    }
}
