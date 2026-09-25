package org.cardanofoundation.cip113.service.module;

import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RwaTokenLegacyRegistrationGuardTest {
    @Test
    void standaloneRegistrationCannotBypassMandatoryProvenance() {
        var handler = mock(RwaTokenModuleHandler.class, CALLS_REAL_METHODS);
        var result = handler.buildRegistrationTransaction(
                RwaTokenRegisterRequest.builder().build(), null);
        assertFalse(result.isSuccessful());
        assertNull(result.unsignedCborTx());
        assertTrue(result.error().contains("/rwa-token/build-chain"));
    }
}
