package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.controller.KeriController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BackendCardanoSigningUnavailableTest {

    @Test
    void missingMnemonicCannotGenerateKycProof() {
        for (String mnemonic : new String[] {"", "   "}) {
            var service = new KycProofService(mnemonic, 30, "preview");
            assertThrows(BackendCardanoSigningUnavailableException.class,
                    () -> service.generateProof("not-an-address", 0));
        }
    }

    @Test
    void unavailableSignerReturnsServiceUnavailableFromBothEndpoints() {
        KeriService service = mock(KeriService.class);
        when(service.getSigningEntityVkey()).thenThrow(new BackendCardanoSigningUnavailableException());
        when(service.generateKycProof("session")).thenThrow(new BackendCardanoSigningUnavailableException());
        var controller = new KeriController(service);

        assertEquals(503, controller.getSigningEntityVkey().getStatusCode().value());
        assertEquals(503, controller.generateKycProof("session").getStatusCode().value());
    }
}
