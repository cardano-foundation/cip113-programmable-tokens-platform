package org.cardanofoundation.cip113.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.cardanofoundation.cip113.service.InitialMintAttestationStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Reclaims only expired initial mints that never published transaction bytes. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpiredInitialMintCleanup {
    private static final int BATCH_SIZE = 64;
    private static final List<String> CANDIDATE_STATUSES =
            List.of("PREPARED", "ANCHORING", "ANCHORED", "BUILDING");

    private final MintAttestationIntentRepository intents;
    private final InitialMintAttestationStore store;
    private String cursor = "";

    @Scheduled(fixedDelayString = "${keri.initialMintExpiryCleanupIntervalMs:60000}")
    public void sweep() {
        Instant now = Instant.now();
        List<String> ids;
        try {
            ids = intents.findExpiredInitialIds(now, cursor, CANDIDATE_STATUSES,
                    PageRequest.of(0, BATCH_SIZE));
        } catch (Exception e) {
            log.warn("Could not scan expired initial-mint attempts", e);
            return;
        }
        if (ids.isEmpty()) { cursor = ""; return; }
        for (String id : ids) {
            cursor = id;
            try {
                if (store.releaseExpired(id, now))
                    log.info("Released expired unbuilt initial-mint attempt id={}", id);
            } catch (Exception e) {
                log.warn("Could not release expired initial-mint attempt id={}; will revisit", id, e);
            }
        }
    }
}
