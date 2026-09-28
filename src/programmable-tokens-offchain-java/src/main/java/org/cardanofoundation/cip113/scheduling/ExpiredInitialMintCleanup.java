package org.cardanofoundation.cip113.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.cardanofoundation.cip113.service.InitialMintAttestationStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Reclaims only expired initial mints that never published transaction bytes.
 *
 * ⛔ GATED ON {@code keri.enabled} BECAUSE ITS DEPENDENCY IS. {@link InitialMintAttestationStore} is
 * {@code @ConditionalOnProperty("keri.enabled")}, and this class held it as a HARD constructor
 * dependency while being an unconditional {@code @Component} — so on any deployment that runs
 * without a KERI agent the context failed at startup:
 *
 * <pre>
 *   Parameter 1 of constructor in …ExpiredInitialMintCleanup required a bean of type
 *   …InitialMintAttestationStore that could not be found.
 * </pre>
 *
 * Measured on the preview deployment, 2026-09-28 — after a successful bootstrap, which is a bad
 * moment to discover it. preview and preprod both set {@code KERI_ENABLED=false}, so this was
 * never going to start there.
 *
 * <p>The gate is the CORRECT fix rather than a workaround: there are no initial-mint attestations
 * to expire when nothing issues them, and this job's own schedule is keyed
 * {@code keri.initialMintExpiryCleanupIntervalMs}. It is a KERI job by its own configuration.
 *
 * <p>⚑ THE OTHER SHAPE IS ALSO IN THIS CODEBASE, and it is the one to copy when a consumer must
 * survive its dependency being absent: {@code TokenOperationsService} holds
 * {@code ObjectProvider<MintAttestationService>}, which tolerates the bean not existing. Use the
 * gate when the whole component is meaningless without the feature, and ObjectProvider when only
 * part of it is.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
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
