package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.backend.api.BackendService;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;

/**
 * Overlays the primary backend's PlutusV3 cost model with one fetched from a secondary
 * backend (Koios), pinned to the <em>current</em> epoch.
 *
 * <p>Why: after the 2026-04-17 preview hard-fork the V3 cost model grew from 297 → 350
 * entries. Blockfrost preview's {@code /epochs/latest/parameters} continued returning the
 * old 297-entry slice for a long time after the fork, so {@code scriptIntegrityHash}
 * computed locally never matched what the node expected → every Plutus tx was rejected
 * with {@code PPViewHashesDontMatch}.
 *
 * <p>Why we don't just call Koios's {@code getProtocolParameters()}: Koios's no-argument
 * endpoint returns the most recently <em>finalized</em> epoch's params, which on preview
 * can lag the current epoch by many days. We need the current epoch's cost model
 * specifically — so we explicitly look it up via {@code getLatestEpoch()} → epoch number
 * → {@code getProtocolParameters(epochNo)}.
 */
@Slf4j
public class CostModelOverlayProtocolParamsSupplier implements ProtocolParamsSupplier {

    private static final String PLUTUS_V3 = "PlutusV3";

    private final ProtocolParamsSupplier primary;
    private final BackendService overlayBackend;

    public CostModelOverlayProtocolParamsSupplier(ProtocolParamsSupplier primary,
                                                  BackendService overlayBackend) {
        this.primary = primary;
        this.overlayBackend = overlayBackend;
    }

    @Override
    public ProtocolParams getProtocolParams() {
        ProtocolParams params = primary.getProtocolParams();

        LinkedHashMap<String, Long> overlay = fetchCurrentEpochV3CostModel();
        if (overlay == null || overlay.isEmpty()) {
            log.warn("Overlay source returned no PlutusV3 cost model; using primary params as-is");
            return params;
        }

        LinkedHashMap<String, LinkedHashMap<String, Long>> merged =
                params.getCostModels() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(params.getCostModels());
        LinkedHashMap<String, Long> previous = merged.put(PLUTUS_V3, overlay);
        int prevSize = previous == null ? 0 : previous.size();
        log.debug("Overlayed PlutusV3 cost model: primary had {} entries, overlay has {}", prevSize, overlay.size());
        params.setCostModels(merged);
        return params;
    }

    private LinkedHashMap<String, Long> fetchCurrentEpochV3CostModel() {
        try {
            // We use BlockService.getLatestBlock() (Koios's /blocks?limit=1) instead of
            // EpochService.getLatestEpoch() because Koios's /epoch_info endpoint returns rows
            // starting from the last fully-finalized epoch (e.g. 1249 while the chain tip is on
            // 1277), which gives us a stale 297-entry V3 cost model. /blocks always reflects the
            // live chain tip, so its epoch_no is always current.
            var latestBlock = overlayBackend.getBlockService().getLatestBlock();
            if (!latestBlock.isSuccessful() || latestBlock.getValue() == null) {
                log.warn("Failed to fetch latest block from overlay backend: {}", latestBlock);
                return null;
            }
            int currentEpoch = latestBlock.getValue().getEpoch();
            log.debug("Overlay backend reports current epoch (from latest block): {}", currentEpoch);
            var params = overlayBackend.getEpochService().getProtocolParameters(currentEpoch);
            if (!params.isSuccessful() || params.getValue() == null) {
                log.warn("Failed to fetch protocol params for epoch {} from overlay backend: {}",
                        currentEpoch, params);
                return null;
            }
            return params.getValue().getCostModels() == null
                    ? null
                    : params.getValue().getCostModels().get(PLUTUS_V3);
        } catch (ApiException e) {
            log.warn("Overlay backend lookup failed; falling back to primary params", e);
            return null;
        }
    }
}
