package org.cardanofoundation.cip113.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.repository.RegistryNodeRepository;
import org.springframework.stereotype.Component;

/**
 * Startup guard against the exact failure mode V12__registry_node_v0_4_0_credential_fields.sql
 * warns about: that migration deletes every {@code registry_node} row (they were silently wrong
 * under the pre-v0.4.0 parser) but cannot itself re-index the chain — yaci-store's sync cursor is
 * untouched, so historical registrations only reappear if an operator manually rewinds it (see
 * the migration's SQL comments for the exact steps).
 *
 * <p>Without this check, that gap is invisible: {@code RegistryController} just returns empty
 * lists / 404s for tokens that ARE registered on chain, with nothing in the logs pointing at the
 * cause. Every protocol deployment mints an origin node at bootstrap time, so
 * {@code registry_node} being empty while {@code protocol_params} is non-empty is never a
 * legitimate steady state — it means indexing hasn't caught up with (or hasn't been rewound past)
 * a protocol version that's already live.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RegistryReindexHealthCheck {

    private final RegistryNodeRepository registryNodeRepository;
    private final ProtocolParamsService protocolParamsService;

    @PostConstruct
    public void checkForMissingReindex() {
        boolean hasProtocolParams = !protocolParamsService.getAll().isEmpty();
        boolean hasRegistryNodes = registryNodeRepository.count() > 0;

        if (hasProtocolParams && !hasRegistryNodes) {
            log.warn("registry_node is empty but protocol_params is not: at least one deployed " +
                    "protocol has no indexed registry nodes, not even its origin node. If this " +
                    "follows a schema migration (V12__registry_node_v0_4_0_credential_fields.sql), " +
                    "this is expected until the indexer is rewound and replayed — see that " +
                    "migration's comments for the manual steps (rewind yaci-store's `cursor_`/`era` " +
                    "tables or store.cardano.sync-start-slot/sync-start-blockhash, then restart). " +
                    "Until then, /registry/* endpoints will silently report zero registered tokens.");
        }
    }
}
