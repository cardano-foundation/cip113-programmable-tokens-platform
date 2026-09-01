package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.model.ProtocolVersionInfo;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.ProtocolParamsService;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${apiPrefix}/protocol-params")
@RequiredArgsConstructor
@Slf4j
public class ProtocolParamsController {

    private final ProtocolParamsService protocolParamsService;

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
        return protocolParamsService.getLatest()
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
        List<ProtocolParamsEntity> allParams = protocolParamsService.getAll();
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
        return protocolParamsService.getByTxHash(txHash)
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
        return protocolParamsService.getBySlot(slot)
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
        return protocolParamsService.getValidAtSlot(slot)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all indexed protocol-params versions, with the configured deployment's current one
     * marked {@code isDefault}.
     *
     * <p>Versions come from the chain: {@code ProtocolParamsEventListener} decodes the inline
     * datum of every {@code ProtocolParams}-NFT output it sees, but only for transactions
     * allowlisted in {@code protocol-params-{network}.json}. A deployment missing from that
     * file is invisible here no matter how well the bootstrap record describes it, and the
     * endpoint returns {@code 200 []} rather than failing — so an empty list means "nothing
     * indexed", not "nothing deployed".
     *
     * <h2>Why the default is not matched on txHash</h2>
     *
     * It used to be {@code entity.txHash == bootstrapRecord.txHash}, which quietly stopped
     * working. A bootstrap record is keyed by the REFERENCE-SCRIPT PUBLISH transaction, while
     * an indexed version is keyed by the transaction that CREATED the params UTxO. In the
     * 2026-05 preview deployment those were one transaction and the comparison held; the SDK's
     * 2026-08 deployment splits them (bootstrap {@code 35954fc8…}, publish {@code e466d078…}),
     * so the two hashes are never equal and every version came back {@code isDefault: false} —
     * a version switcher with nothing selected.
     *
     * <p>So the match is now on what actually identifies a deployment: its registry-node policy
     * and its {@code programmable_logic_base} hash. Both are stable across an in-place upgrade
     * by design — the upgrade mechanism swaps delegates and never moves PLB — which is also why
     * the newest such version wins rather than all of them being flagged: an upgrade appends a
     * version to the same deployment, and the current one is the live wiring.
     *
     * @return protocol version info, ordered by slot ascending
     */
    @GetMapping("/versions")
    public ResponseEntity<List<ProtocolVersionInfo>> getVersions() {
        log.debug("GET /versions - fetching all protocol versions");

        try {
            var bootstrap = protocolBootstrapService.getProtocolBootstrapParams();
            var defaultRegistryNodePolicyId = bootstrap.directoryMintParams().scriptHash();
            var defaultProgLogicScriptHash = bootstrap.programmableLogicBaseParams().scriptHash();

            // Already ordered by slot ascending.
            List<ProtocolParamsEntity> allParams = protocolParamsService.getAll();

            // The configured deployment's CURRENT version: newest of the versions belonging to
            // it. Null when that deployment has not been indexed yet, in which case nothing is
            // flagged rather than something arbitrary being flagged.
            String defaultTxHash = allParams.stream()
                    .filter(entity -> defaultRegistryNodePolicyId.equals(entity.getRegistryNodePolicyId())
                            && defaultProgLogicScriptHash.equals(entity.getProgLogicScriptHash()))
                    .max(Comparator.comparing(ProtocolParamsEntity::getSlot))
                    .map(ProtocolParamsEntity::getTxHash)
                    .orElse(null);

            if (defaultTxHash == null) {
                log.warn("No indexed protocol params match the configured deployment "
                                + "(bootstrap txHash={}, registryNodePolicyId={}, progLogic={}). "
                                + "Is its params transaction listed in protocol-params-<network>.json, "
                                + "and has the store synced past it?",
                        bootstrap.txHash(), defaultRegistryNodePolicyId, defaultProgLogicScriptHash);
            } else {
                log.debug("Default protocol version txHash: {}", defaultTxHash);
            }

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
