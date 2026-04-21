package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.model.ProtocolVersionInfo;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.Cip113ProtocolParamsService;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${apiPrefix}/protocol-params")
@RequiredArgsConstructor
@Slf4j
public class ProtocolParamsController {

    private final Cip113ProtocolParamsService cip113ProtocolParamsService;

    private final ProtocolBootstrapService protocolBootstrapService;

    private final CardanoConverters cardanoConverters;

    /**
     * Get the latest protocol params version
     *
     * @return the latest protocol params or 404 if none exist
     */
    @GetMapping("/latest")
    public ResponseEntity<ProtocolParamsEntity> getLatest() {
        log.debug("GET /latest - fetching latest protocol params");
        return cip113ProtocolParamsService.getLatest()
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all protocol params versions (ordered by slot ascending)
     *
     * @return list of all protocol params
     */
    @GetMapping("/all")
    public ResponseEntity<List<ProtocolParamsEntity>> getAll() {
        log.debug("GET /all - fetching all protocol params");
        List<ProtocolParamsEntity> allParams = cip113ProtocolParamsService.getAll();
        return ResponseEntity.ok(allParams);
    }

    /**
     * Get protocol params by transaction hash
     *
     * @param txHash the transaction hash
     * @return the protocol params or 404 if not found
     */
    @GetMapping("/by-tx/{txHash}")
    public ResponseEntity<ProtocolParamsEntity> getByTxHash(@PathVariable String txHash) {
        log.debug("GET /by-tx/{} - fetching protocol params by tx hash", txHash);
        return cip113ProtocolParamsService.getByTxHash(txHash)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get protocol params by slot number
     *
     * @param slot the slot number
     * @return the protocol params or 404 if not found
     */
    @GetMapping("/by-slot/{slot}")
    public ResponseEntity<ProtocolParamsEntity> getBySlot(@PathVariable Long slot) {
        log.debug("GET /by-slot/{} - fetching protocol params by slot", slot);
        return cip113ProtocolParamsService.getBySlot(slot)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get protocol params valid at a given slot (closest version <= slot)
     *
     * @param slot the slot number
     * @return the protocol params valid at that slot or 404 if none
     */
    @GetMapping("/valid-at-slot/{slot}")
    public ResponseEntity<ProtocolParamsEntity> getValidAtSlot(@PathVariable Long slot) {
        log.debug("GET /valid-at-slot/{} - fetching protocol params valid at slot", slot);
        return cip113ProtocolParamsService.getValidAtSlot(slot)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all protocol versions with default marked
     * Returns list of all protocol versions with essential fields for UI display
     * The default version is marked based on txHash from protocol-bootstraps-preview.json
     *
     * @return list of protocol version info ordered by slot descending (newest first)
     */
    @GetMapping("/versions")
    public ResponseEntity<List<ProtocolVersionInfo>> getVersions() {
        log.debug("GET /versions - fetching all protocol versions");

        try {
            // Get default txHash from protocol-bootstraps-preview.json
            String defaultTxHash = protocolBootstrapService.getProtocolBootstrapParams().txHash();
            log.debug("Default protocol version txHash: {}", defaultTxHash);

            // Get all protocol params (already ordered by slot ascending)
            List<ProtocolParamsEntity> allParams = cip113ProtocolParamsService.getAll();

            // Convert to DTOs with isDefault flag and timestamp conversion
            List<ProtocolVersionInfo> versions = allParams.stream()
                    .map(entity -> {
                        var timestamp = cardanoConverters.slot()
                                .slotToTime(entity.getSlot())
                                .toEpochSecond(ZoneOffset.UTC);
                        return ProtocolVersionInfo.builder()
                                .registryNodePolicyId(entity.getRegistryNodePolicyId())
                                .progLogicScriptHash(entity.getProgLogicScriptHash())
                                .txHash(entity.getTxHash())
                                .slot(entity.getSlot())
                                .timestamp(timestamp)
                                .isDefault(entity.getTxHash().equals(defaultTxHash))
                                .build();
                    })
                    .collect(Collectors.toList());

            log.debug("Returning {} protocol versions", versions.size());
            return ResponseEntity.ok(versions);

        } catch (Exception e) {
            log.error("Error fetching protocol versions", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
