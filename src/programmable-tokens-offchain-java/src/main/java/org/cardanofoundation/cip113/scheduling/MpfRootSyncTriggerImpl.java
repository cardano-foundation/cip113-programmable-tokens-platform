package org.cardanofoundation.cip113.scheduling;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.service.MpfRootSyncTrigger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Debounces post-insert nudges so many rapid inserts collapse into one root publish.
 *  The interface lives in the {@code service} package to break the cycle with
 *  {@link org.cardanofoundation.cip113.service.MpfTreeService}. */
@Component
@ConditionalOnProperty(name = "kycExtended.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MpfRootSyncTriggerImpl implements MpfRootSyncTrigger {

    private final MpfRootSyncJob rootSyncJob;

    @Value("${kycExtended.postInsertSyncDelayMs:5000}")
    private long delayMs;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "mpf-root-sync-trigger");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();

    @Override
    public void onRootChanged(String policyId) {
        pending.compute(policyId, (k, existing) -> {
            if (existing != null && !existing.isDone()) return existing;
            return executor.schedule(() -> {
                pending.remove(policyId);
                try {
                    rootSyncJob.syncOne(policyId);
                } catch (Exception e) {
                    log.warn("syncOne({}) from trigger failed: {}", policyId, e.getMessage());
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
