package org.cardanofoundation.cip113.scheduling;

import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.cardanofoundation.cip113.service.InitialMintAttestationStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpiredInitialMintCleanupTest {
    @Test void cursorPassesFullPageOfUnreleasableAttempts() throws Exception {
        var intents = mock(MintAttestationIntentRepository.class);
        var store = mock(InitialMintAttestationStore.class);
        var firstPage = IntStream.range(0, 64).mapToObj(i -> String.format("%04d", i)).toList();
        when(intents.findExpiredInitialIds(any(Instant.class), eq(""), anyList(), any(Pageable.class)))
                .thenReturn(firstPage);
        when(intents.findExpiredInitialIds(any(Instant.class), eq("0063"), anyList(), any(Pageable.class)))
                .thenReturn(List.of("0064"));
        var cleanup = new ExpiredInitialMintCleanup(intents, store);
        cleanup.sweep();
        cleanup.sweep();
        verify(store).releaseExpired(eq("0064"), any(Instant.class));
        assertEquals(65, mockingDetails(store).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("releaseExpired")).count());
    }
}
