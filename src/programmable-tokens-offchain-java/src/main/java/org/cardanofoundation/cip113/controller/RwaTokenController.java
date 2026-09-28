package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.RwaTokenDenylistEntryEntity;
import org.cardanofoundation.cip113.entity.RwaTokenPowerUserEntity;
import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.model.RwaTokenSummary;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.context.RwaTokenContext;
import org.cardanofoundation.cip113.repository.RwaTokenDenylistEntryRepository;
import org.cardanofoundation.cip113.repository.RwaTokenMemberLeafRepository;
import org.cardanofoundation.cip113.repository.RwaTokenPowerUserRepository;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.scheduling.AdminSigningKeyProvider;
import org.cardanofoundation.cip113.service.RwaTokenAllowlistService;
import org.cardanofoundation.cip113.service.RwaTokenAdminRequestVerifier;
import org.cardanofoundation.cip113.service.RwaTokenCreationRequestVerifier;
import org.cardanofoundation.cip113.service.RwaTokenCreationService;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.CacheControl;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Public + admin endpoints for the rwa-token module. */
@RestController
@RequestMapping("${apiPrefix}/rwa-token")
@ConditionalOnProperty(name = "rwaToken.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RwaTokenController {

    private static final int LIST_CAP = 200;

    private final RwaTokenRegistrationRepository registrationRepo;
    private final RwaTokenMemberLeafRepository memberLeafRepo;
    private final RwaTokenDenylistEntryRepository denylistRepo;
    private final RwaTokenPowerUserRepository powerUserRepo;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final RwaTokenAllowlistService allowlistService;
    private final RwaTokenAdminRequestVerifier adminRequestVerifier;
    private final RwaTokenCreationRequestVerifier creationRequestVerifier;
    private final RwaTokenCreationService creationService;
    private final ObjectMapper objectMapper;
    private final AdminSigningKeyProvider adminSigningKeyProvider;
    private final ModuleHandlerFactory handlerFactory;
    private final ProtocolBootstrapService protocolBootstrapService;

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Legacy backend signer discovery. RWA registrations use the issuer wallet
     *  as GS admin; this endpoint grants no RWA member-root authority. */
    @GetMapping("/admin-pkh")
    public ResponseEntity<?> getAdminPkh() {
        if (!adminSigningKeyProvider.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of("error", "admin signing key not configured"));
        }
        return ResponseEntity.ok(Map.of(
                "adminPkh", adminSigningKeyProvider.getAdminPkh(),
                "adminAddress", adminSigningKeyProvider.getAdminAddress()
        ));
    }

    // ── Genesis init ────────────────────────────────────────────────────────

    /** Build the genesis tx that mints the GS NFT + denylist root + power-users root
     *  in one go. Returns the unsigned CBOR for the wallet to sign + submit.
     *  Persists a {@code RwaTokenRegistrationEntity} row (keyed by
     *  {@code pending-<globalStatePolicyId>}) and auto-seeds the bootstrap admin into
     *  the off-chain power-user table so the admin page shows them immediately. */
    /** Build an on-chain {@code AddPowerUser} transaction that inserts the given
     *  power user into the linked list. Returns unsigned CBOR for the wallet to
     *  sign + submit. Body: {@code { policyId, powerUserPkh, capabilities,
     *  adminAddress }}. v1 only handles the first insertion (anchor = root). */
    @PostMapping("/{policyId}/power-users/on-chain")
    public ResponseEntity<?> addPowerUserOnChain(@PathVariable String policyId,
                                                 @RequestBody Map<String, Object> body) {
        try {
            String powerUserPkh = (String) body.get("powerUserPkh");
            if (powerUserPkh == null || powerUserPkh.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "powerUserPkh is required"));
            }
            Object capsObj = body.get("capabilities");
            if (!(capsObj instanceof Number)) {
                return ResponseEntity.badRequest().body(Map.of("error", "capabilities (int) is required"));
            }
            String adminAddress = (String) body.get("adminAddress");
            if (adminAddress == null || adminAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "adminAddress is required"));
            }
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildAddPowerUserTransaction(
                    policyId, powerUserPkh, ((Number) capsObj).intValue(), adminAddress);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("rwa-token AddPowerUser failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Re-point this token's CIP-113 registry node at the transfer-logic scripts the
     * currently-vendored contracts derive.
     *
     * <p>The in-place upgrade path for transfer rules: the node's {@code key} and
     * {@code minting_logic_script} are frozen at insert, so the token's policy id and
     * identity survive the change. Admin-signed, and refused once {@code LockUpgrades}
     * has been executed.
     *
     * <p>Body: {@code { feePayerAddress }}.
     */
    @PostMapping("/{policyId}/registry-node/upgrade")
    public ResponseEntity<?> upgradeRegistryNode(@PathVariable String policyId,
                                                 @RequestBody Map<String, Object> body) {
        try {
            String feePayerAddress = (String) body.get("feePayerAddress");
            if (feePayerAddress == null || feePayerAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "feePayerAddress is required"));
            }
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            TransactionContext<Void> result =
                    handler.buildUpgradeRegistryNodeTransaction(policyId, feePayerAddress, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("rwa-token registry-node upgrade failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build a chain of admin-signed GS update transactions, one per change.
     *  Each {@code GlobalStateSpendAction} is its own redeemer variant and the
     *  on-chain validator forbids batching, so N field changes = N txs. The
     *  chain mempool-chains: tx[i+1] spends tx[i]'s GS output instead of going
     *  back to chain. Frontend signs all N at once via CIP-103 signTxs and
     *  submits sequentially via {@code /issue-token/submit-chain}.
     *
     *  <p>Body: {@code { feePayerAddress, changes: [{action, ...}, ...] }} where
     *  each change is a {@link RwaTokenModuleHandler.GsChangeSpec}. */
    @PostMapping("/{policyId}/global-state/update-chain")
    public ResponseEntity<?> updateGlobalStateChain(@PathVariable String policyId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            String feePayerAddress = (String) body.get("feePayerAddress");
            if (feePayerAddress == null || feePayerAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "feePayerAddress is required"));
            }
            Object changesObj = body.get("changes");
            if (!(changesObj instanceof List<?> changesList) || changesList.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "changes (non-empty array) is required"));
            }
            List<RwaTokenModuleHandler.GsChangeSpec> changes = new java.util.ArrayList<>();
            for (Object o : changesList) {
                if (!(o instanceof Map<?, ?> m)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "each change must be an object"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> change = (Map<String, Object>) m;
                if ("UpdateMemberRootHash".equals(change.get("action"))) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "member roots must be published through the reviewed members flow"));
                }
                changes.add(new RwaTokenModuleHandler.GsChangeSpec(
                        (String) change.get("action"),
                        (Boolean) change.get("transfersPaused"),
                        (String) change.get("newSecurityInfoHex"),
                        (String) change.get("trustedVkeyHex"),
                        (String) change.get("trustedMetadataHex"),
                        (String) change.get("trustedOldVkeyHex"),
                        (String) change.get("trustedNewVkeyHex"),
                        (String) change.get("trustedNewMetadataHex"),
                        (Boolean) change.get("requiresSenderKycEnabled"),
                        (Boolean) change.get("requiresReceiverKycEnabled"),
                        (String) change.get("newMemberRootHashHex"),
                        (String) change.get("newAdminCredentialHashHex"),
                        (String) change.get("newMintingScriptCredentialHashHex")
                ));
            }

            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            String signerPkh = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    new com.bloxbean.cardano.client.address.Address(feePayerAddress)
                            .getPaymentCredentialHash()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "feePayerAddress has no payment credential: " + feePayerAddress)));
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            TransactionContext<List<String>> result = handler.buildGlobalStateUpdateChain(
                    policyId, changes, feePayerAddress, signerPkh, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTxs", result.metadata()));
        } catch (Exception e) {
            log.error("rwa-token GS update chain failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** The visible baseline and staged Veridian leaves. This read has no authority to publish. */
    @GetMapping("/{policyId}/members")
    public ResponseEntity<?> listMembers(@PathVariable String policyId, @org.springframework.web.bind.annotation.RequestHeader HttpHeaders headers) {
        try {
            authorizeAdminRequest(policyId, "GET", "/rwa-token/" + policyId + "/members", "", headers);
            byte[] root = allowlistService.liveOnchainRoot(policyId);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "baselineRootHash", HexUtil.encodeHexString(root),
                    "baseline", allowlistService.activeLeaves(policyId, root),
                    "pending", allowlistService.pendingLeaves(policyId)));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", e.getReason() == null ? "admin authentication failed" : e.getReason()));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    /** Build a candidate UpdateMemberRootHash transaction from the selected
     *  staged members and optional manual member. The live GS admin authorizes
     *  this request with CIP-30 signData, then reviews and signs the transaction
     *  with the wallet before submitting it. */
    @PostMapping("/{policyId}/update-member-root-hash")
    public ResponseEntity<?> updateMemberRootHash(@PathVariable String policyId,
                                                  @RequestBody Map<String, Object> body,
                                                  @org.springframework.web.bind.annotation.RequestHeader HttpHeaders headers) {
        try {
            String feePayerAddress = (String) body.get("feePayerAddress");
            if (feePayerAddress == null || feePayerAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "feePayerAddress is required"));
            }
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }

            Object manualObj = body.get("manualMember");
            RwaTokenAllowlistService.MemberLeaf manual = manualObj == null ? null : parseMember(manualObj);
            Object selectedObj = body.get("selectedPendingMembers");
            if (!(selectedObj instanceof List<?> rawSelected) || rawSelected.size() > 100)
                return ResponseEntity.badRequest().body(Map.of("error", "selectedPendingMembers must be an array of at most 100"));
            List<RwaTokenAllowlistService.MemberLeaf> selectedMembers = rawSelected.stream()
                    .map(this::parseMember).toList();
            java.util.Set<String> selected = new java.util.HashSet<>();
            for (var leaf : selectedMembers) {
                if (!selected.add(leaf.credentialType() + ":" + leaf.credentialHash()))
                    return ResponseEntity.badRequest().body(Map.of("error", "duplicate pending member"));
            }
            String canonicalBody = RwaTokenAdminRequestVerifier.canonicalBody(feePayerAddress, manual, selectedMembers);
            authorizeAdminRequest(policyId, "POST", "/rwa-token/" + policyId + "/update-member-root-hash",
                    canonicalBody, headers);
            var candidate = allowlistService.prepareCandidate(policyId, manual, selected);
            for (var leaf : selectedMembers) {
                if (!candidate.added().contains(leaf))
                    return ResponseEntity.status(409).body(Map.of("error", "pending member expiry changed; refresh and review"));
            }
            byte[] currentLocalRoot = HexUtil.decodeHexString(candidate.rootHash());

            String signerPkh = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    new com.bloxbean.cardano.client.address.Address(feePayerAddress)
                            .getPaymentCredentialHash()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "feePayerAddress has no payment credential: " + feePayerAddress)));

            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            var liveAdmin = handler.readGlobalState(policyId).orElseThrow(() ->
                    new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                            "live GS is unavailable"));
            if (!signerPkh.equalsIgnoreCase(liveAdmin.adminCredentialHash())) {
                return ResponseEntity.status(403).cacheControl(CacheControl.noStore())
                        .body(Map.of("error", "feePayerAddress must belong to the live GS admin"));
            }
            TransactionContext<Void> result = handler.buildUpdateMemberRootHashTransaction(
                    policyId, currentLocalRoot, feePayerAddress, signerPkh, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            String txHash = TransactionUtil.getTxHash(HexUtil.decodeHexString(result.unsignedCborTx()));
            allowlistService.saveCandidate(policyId, candidate, txHash);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "unsignedCborTx", result.unsignedCborTx(),
                    "txHash", txHash,
                    "baselineRootHash", candidate.baselineRootHash(),
                    "newRootHashHex", candidate.rootHash(),
                    "baseline", candidate.baseline(),
                    "added", candidate.added(),
                    "leaves", candidate.leaves()));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode()).cacheControl(CacheControl.noStore())
                    .body(Map.of("error", e.getReason() == null ? "admin authentication failed" : e.getReason()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("rwa-token UpdateMemberRootHash failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private RwaTokenAllowlistService.MemberLeaf parseMember(Object value) {
        if (!(value instanceof Map<?, ?> map)
                || !(map.get("credentialHash") instanceof String hash)
                || !(map.get("credentialType") instanceof Integer type)
                || !(map.get("validUntilMs") instanceof Number expiry)
                || !hash.matches("(?i)[0-9a-f]{56}")
                || (type != 0 && type != 1)
                || !(expiry instanceof Integer || expiry instanceof Long)
                || expiry.longValue() < 0) throw new IllegalArgumentException("invalid member credential or expiry");
        return new RwaTokenAllowlistService.MemberLeaf(hash.toLowerCase(java.util.Locale.ROOT),
                type.shortValue(), expiry.longValue());
    }

    private void authorizeAdminRequest(String policyId, String method, String path,
                                       String canonicalBody, HttpHeaders headers) {
        var registration = registrationRepo.findByProgrammableTokenPolicyId(policyId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "rwa-token registration not found"));
        RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                .getHandler("rwa-token", RwaTokenContext.emptyContext());
        var gs = handler.readGlobalState(policyId).orElseThrow(() ->
                new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "live GS is unavailable"));
        adminRequestVerifier.verifyAndConsume(policyId, registration.getGlobalStatePolicyId(),
                gs.adminCredentialHash(), method, path, canonicalBody, headers);
    }

    /** One-shot admin tx that registers the module's transfer-logic stake
     *  credential on chain via a Conway RegCert. Must be called once after
     *  registration, before the first burn (or any future op that withdraws
     *  against transferLogic). Isolated from burn so Eternl-style wallets
     *  signing the structurally-simpler tx without issue.
     *  Body: {@code { feePayerAddress }}. */
    @PostMapping("/{policyId}/register-transfer-logic")
    public ResponseEntity<?> registerTransferLogic(@PathVariable String policyId,
                                                   @RequestBody Map<String, Object> body) {
        try {
            String feePayerAddress = (String) body.get("feePayerAddress");
            if (feePayerAddress == null || feePayerAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "feePayerAddress is required"));
            }
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildRegisterTransferLogicTransaction(
                    policyId, feePayerAddress, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("rwa-token register-transfer-logic failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** One-shot admin tx that registers the module's THIRD-PARTY transfer-logic
     *  stake credential. Required before the first burn: {@code ThirdPartyAct} demands a
     *  withdrawal keyed on registry-node slot 4, which now names this validator, and a
     *  withdrawal from an unregistered reward account is rejected at submit — after the
     *  user has signed. Script evaluation does not catch it, so the burn builder refuses
     *  up front and points here.
     *
     *  <p>Distinct from {@code /register-transfer-logic}: that one registers
     *  {@code transfer_logic}, a different script with a different reward address, used by
     *  the TransferAct path. Both are needed, for different operations.
     *  Body: {@code { feePayerAddress }}. */
    @PostMapping("/{policyId}/register-third-party-transfer-logic")
    public ResponseEntity<?> registerThirdPartyTransferLogic(@PathVariable String policyId,
                                                             @RequestBody Map<String, Object> body) {
        try {
            String feePayerAddress = (String) body.get("feePayerAddress");
            if (feePayerAddress == null || feePayerAddress.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "feePayerAddress is required"));
            }
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler) handlerFactory
                    .getHandler("rwa-token", RwaTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildRegisterThirdPartyTransferLogicTransaction(
                    policyId, feePayerAddress, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("rwa-token register-third-party-transfer-logic failed for policy={}",
                    policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build the full rwa-token registration chain (genesis → AddPowerUser →
     *  CMTA provenance → issuance provenance → optional publishScripts →
     *  registration → optional transfer-logic certificates) as up to eight unsigned
     *  CBORs. The frontend signs them all in one CIP-30 {@code signTxs} call, then
     *  POSTs the signed CBORs (in order) to {@code /issue-token/submit-chain} which
     *  submits them sequentially via the backend's submission service — bypassing the
     *  wallet's submission backend so mempool-chained txs aren't rejected.
     *
     *  <p>{@code publishScriptsCborHex} publishes {@code minting_logic} and the
     *  {@code global_state} spend validator as reference scripts when the registration
     *  includes a first mint. The structural registration fits without this phase.
     *
     *  <p>Returns {@code { genesisCborHex, addPowerUserCborHex,
     *  cmtaProvenanceCborHex, issuanceProvenanceCborHex, publishScriptsCborHex?,
     *  registrationCborHex, registerTransferLogicCborHex?,
     *  registerThirdPartyTransferLogicCborHex?, globalStatePolicyId,
     *  programmableTokenPolicyId, denylistPolicyId, powerUsersPolicyId, genesisTxHash,
     *  addPowerUserTxHash, cmtaProvenanceTxHash, issuanceProvenanceTxHash,
     *  publishScriptsTxHash?, registrationTxHash,
     *  registerTransferLogicTxHash?, registerThirdPartyTransferLogicTxHash? } }. */
    @PostMapping("/build-chain")
    public ResponseEntity<?> buildChain(@RequestBody byte[] rawBody,
                                        @org.springframework.web.bind.annotation.RequestHeader HttpHeaders headers) {
        try {
            RwaTokenRegisterRequest request = objectMapper.readValue(rawBody, RwaTokenRegisterRequest.class);
            creationRequestVerifier.verifyCreationAndConsume(
                    "/rwa-token/build-chain", rawBody, request.getFeePayerAddress(), headers);
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            RwaTokenModuleHandler.ChainBuildResult meta = creationService.buildChain(request, protocolParams);
            // Use HashMap (Map.of caps at 10 entries; we now have 12).
            // Null-value entries (e.g. the optional 4th tx) are skipped so the
            // JSON response omits them, keeping the wire shape forward-compat.
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("genesisCborHex", meta.genesisCborHex());
            resp.put("addPowerUserCborHex", meta.addPowerUserCborHex());
            resp.put("cmtaProvenanceCborHex", meta.cmtaProvenanceCborHex());
            resp.put("issuanceProvenanceCborHex", meta.issuanceProvenanceCborHex());
            // Present only when the registration carries a first mint — that is the only
            // case whose validator set does not fit inline. Omitted (not null) otherwise,
            // matching the optional 4th tx and keeping the wire shape forward-compatible.
            if (meta.publishScriptsCborHex() != null) {
                resp.put("publishScriptsCborHex", meta.publishScriptsCborHex());
                resp.put("publishScriptsTxHash", meta.publishScriptsTxHash());
            }
            resp.put("registrationCborHex", meta.registrationCborHex());
            if (meta.registerThirdPartyTransferLogicCborHex() != null) {
                resp.put("registerThirdPartyTransferLogicCborHex",
                        meta.registerThirdPartyTransferLogicCborHex());
                resp.put("registerThirdPartyTransferLogicTxHash",
                        meta.registerThirdPartyTransferLogicTxHash());
            }
            if (meta.registerTransferLogicCborHex() != null) {
                resp.put("registerTransferLogicCborHex", meta.registerTransferLogicCborHex());
                resp.put("registerTransferLogicTxHash", meta.registerTransferLogicTxHash());
            }
            resp.put("globalStatePolicyId", meta.globalStatePolicyId());
            resp.put("programmableTokenPolicyId", meta.programmableTokenPolicyId());
            resp.put("denylistPolicyId", meta.denylistPolicyId());
            resp.put("powerUsersPolicyId", meta.powerUsersPolicyId());
            resp.put("genesisTxHash", meta.genesisTxHash());
            resp.put("addPowerUserTxHash", meta.addPowerUserTxHash());
            resp.put("cmtaProvenanceTxHash", meta.cmtaProvenanceTxHash());
            resp.put("issuanceProvenanceTxHash", meta.issuanceProvenanceTxHash());
            resp.put("registrationTxHash", meta.registrationTxHash());
            return ResponseEntity.ok(resp);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RwaTokenCreationService.BuildFailed e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("rwa-token build-chain failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/init")
    public ResponseEntity<?> initGlobalState(@RequestBody byte[] rawBody,
                                             @org.springframework.web.bind.annotation.RequestHeader HttpHeaders headers) {
        return ResponseEntity.badRequest().body(Map.of("error",
                "Standalone CMTA init is disabled because CIP-171 provenance is required. "
                + "Use /rwa-token/build-chain to create and publish both records."));
    }

    @GetMapping("/tokens")
    public List<RwaTokenSummary> listTokens() {
        return registrationRepo.findAllByOrderByLastRootUpdateAtDesc(PageRequest.of(0, LIST_CAP)).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Read the on-chain GS datum for a registered rwa-token policy.
     *  The mint UI uses this to display the remaining mintable_amount and the
     *  current transfers_paused state before letting the admin mint more. */
    @GetMapping("/{policyId}/global-state")
    public ResponseEntity<?> getGlobalState(@PathVariable String policyId) {
        try {
            RwaTokenModuleHandler handler = (RwaTokenModuleHandler)
                    handlerFactory.getHandler("rwa-token", RwaTokenContext.emptyContext());
            byte[] activeRoot = allowlistService.liveOnchainRoot(policyId);
            allowlistService.activeLeaves(policyId, activeRoot);
            int pendingCount = allowlistService.pendingLeaves(policyId).size();
            String localRootHex = HexUtil.encodeHexString(activeRoot);
            return handler.readGlobalState(policyId)
                    .<ResponseEntity<?>>map(gs -> {
                        Map<String, Object> resp = new java.util.HashMap<>();
                        resp.put("policyId", gs.policyId());
                        registrationRepo.findByProgrammableTokenPolicyId(policyId)
                                .ifPresent(reg -> resp.put("globalStatePolicyId", reg.getGlobalStatePolicyId()));
                        resp.put("transfersPaused", gs.transfersPaused());
                        resp.put("mintableAmount", gs.mintableAmount());
                        resp.put("trustedEntityVkeys", gs.trustedEntityVkeys());
                        resp.put("networkId", gs.networkId());
                        resp.put("securityInfoHex", gs.securityInfoHex() != null ? gs.securityInfoHex() : "");
                        resp.put("memberRootHash", gs.memberRootHash() != null ? gs.memberRootHash() : "");
                        resp.put("memberRootHashLocal", localRootHex);
                        resp.put("pendingMemberCount", pendingCount);
                        resp.put("requiresReceiverKyc", gs.requiresReceiverKyc());
                        // D4: the UI cannot offer SetRequiresSenderKyc without knowing
                        // the current value to diff against.
                        resp.put("requiresSenderKyc", gs.requiresSenderKyc());
                        // D3: terminal decommissioning flag. The admin panel needs it to
                        // render the "already decommissioned" state instead of offering
                        // actions that can no longer be applied.
                        resp.put("deactivated", gs.deactivated());
                        resp.put("adminCredentialHash", gs.adminCredentialHash());
                        // Both KYC gates are independent on chain (transfer_logic_script.ak:123
                        // reads requires_sender_kyc, :157 reads requires_receiver_kyc), so the UI
                        // needs both to decide which proofs to collect.
                        resp.put("requiresSenderKyc", gs.requiresSenderKyc());
                        resp.put("deactivated", gs.deactivated());
                        // The minting proxy delegates to whichever authority this names, so
                        // it is the only on-chain answer to "which mint rules are in force".
                        // upgradesLocked tells the panel whether rotating is still possible
                        // at all, rather than offering an action the chain will reject.
                        resp.put("mintingScriptCredentialHash", gs.mintingScriptCredentialHash());
                        resp.put("upgradesLocked", gs.upgradesLocked());
                        return ResponseEntity.ok(resp);
                    })
                    .orElse(ResponseEntity.status(404).body(Map.of(
                            "error", "global-state UTxO not found on chain for " + policyId)));
        } catch (Exception e) {
            log.error("rwa-token getGlobalState failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Allowlist (members) ──────────────────────────────────────────────────

    /**
     * @param credentialType which credential form the hash belongs to — {@code 0}
     *   (VerificationKey, the default) or {@code 1} (Script). It is the first byte of the
     *   MPF leaf key, so it selects WHICH leaf the proof is generated against: a holder at
     *   a script stake credential needs {@code 1}, or they receive a proof for a leaf the
     *   validator does not look up. Optional, because one hash almost always has exactly
     *   one leaf; when it has two, this is how the caller says which one it means.
     */
    @GetMapping("/{policyId}/proofs/{memberPkh}")
    public ResponseEntity<?> getMemberProof(@PathVariable String policyId,
                                            @PathVariable String memberPkh,
                                            @RequestParam(required = false) Short credentialType) {
        try {
            byte[] pkhBytes = HexUtil.decodeHexString(memberPkh);
            long now = System.currentTimeMillis();

            // Manual members exist only in the immutable confirmed snapshot. The
            // staging table alone never authorises a proof.
            List<RwaTokenAllowlistService.MemberLeaf> matches = allowlistService.activeLeaves(policyId).stream()
                    .filter(l -> l.credentialHash().equalsIgnoreCase(memberPkh)).toList();
            List<org.cardanofoundation.cip113.entity.RwaTokenMemberLeafEntity> staged =
                    memberLeafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh);
            if (matches.isEmpty()) {
                if (!staged.isEmpty()) {
                    var liveStage = staged.stream().filter(l -> l.getValidUntilMs() >= now).findFirst();
                    if (liveStage.isEmpty()) return ResponseEntity.status(410).body(Map.of(
                            "error", "staged membership has expired; run verification again",
                            "validUntilMs", staged.getFirst().getValidUntilMs()));
                    return ResponseEntity.status(425).body(Map.of(
                            "error", "member is staged; admin root publication is pending",
                            "addedAt", liveStage.get().getAddedAt().toEpochMilli()));
                }
                return ResponseEntity.status(404).body(Map.of("error", "member not found in allowlist"));
            }
            if (credentialType == null && matches.size() > 1) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        memberPkh + " is enrolled under more than one credential form for this "
                        + "policy; pass ?credentialType=0 (VerificationKey) or 1 (Script) to say "
                        + "which holder you mean"));
            }
            short wantType = credentialType != null ? credentialType : matches.getFirst().credentialType();
            java.util.Optional<RwaTokenAllowlistService.MemberLeaf> existing =
                    matches.stream().filter(l -> l.credentialType() == wantType).findFirst();
            if (existing.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error",
                        "member not found in allowlist under credential type " + wantType));
            }
            if (existing.get().validUntilMs() < now) {
                boolean refreshStaged = staged.stream().anyMatch(l -> l.getCredentialType() == wantType
                        && l.getValidUntilMs() > now);
                if (refreshStaged) return ResponseEntity.status(425).body(Map.of(
                        "error", "renewed expiry is staged; admin root publication is pending",
                        "addedAt", staged.stream().filter(l -> l.getCredentialType() == wantType)
                                .findFirst().orElseThrow().getAddedAt().toEpochMilli()));
                return ResponseEntity.status(410).body(Map.of("error", "member leaf has expired",
                        "validUntilMs", existing.get().validUntilMs()));
            }

            java.util.Optional<RwaTokenAllowlistService.MpfLeafView> view =
                    allowlistService.inclusionProof(policyId, pkhBytes, wantType, now);
            if (view.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "MPF proof unavailable"));
            }
            RwaTokenAllowlistService.MpfLeafView v = view.get();
            return ResponseEntity.ok(Map.of(
                    "memberPkh", memberPkh,
                    "proofCborHex", HexUtil.encodeHexString(v.proofCbor()),
                    "validUntilMs", v.validUntilMs(),
                    "rootHashOnchain", HexUtil.encodeHexString(v.rootHashOnchain()),
                    "rootHashLocal", HexUtil.encodeHexString(v.rootHashLocal())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid memberPkh hex"));
        } catch (Exception e) {
            log.error("getMemberProof failed for policy={} memberPkh={}", policyId, memberPkh, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Denylist ─────────────────────────────────────────────────────────────

    @GetMapping("/{policyId}/denylist")
    public List<Map<String, Object>> listDenylist(@PathVariable String policyId) {
        return denylistRepo.findByProgrammableTokenPolicyId(policyId).stream()
                .map(this::denylistEntryToMap)
                .toList();
    }

    /** Admin-only off-chain mirror update. Builds + submits the on-chain tx
     *  once {@code RwaTokenModuleHandler#buildAddDenylistEntryTransaction}
     *  is filled in; today persists locally only. */
    @PostMapping("/{policyId}/denylist")
    public ResponseEntity<?> addDenylistEntry(@PathVariable String policyId, @RequestBody Map<String, Object> body) {
        String memberPkh = (String) body.get("memberPkh");
        if (memberPkh == null || memberPkh.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "memberPkh is required"));
        }
        if (denylistRepo.existsByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh)) {
            return ResponseEntity.status(409).body(Map.of("error", "already on denylist"));
        }
        RwaTokenDenylistEntryEntity entry = RwaTokenDenylistEntryEntity.builder()
                .programmableTokenPolicyId(policyId)
                .memberPkh(memberPkh)
                .reason((String) body.getOrDefault("reason", null))
                .addedByPowerUserPkh((String) body.getOrDefault("addedByPowerUserPkh", null))
                .addedAt(Instant.now())
                .build();
        denylistRepo.save(entry);
        return ResponseEntity.ok(denylistEntryToMap(entry));
    }

    @DeleteMapping("/{policyId}/denylist/{memberPkh}")
    public ResponseEntity<?> removeDenylistEntry(@PathVariable String policyId, @PathVariable String memberPkh) {
        java.util.Optional<RwaTokenDenylistEntryEntity> existing = denylistRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        denylistRepo.delete(existing.get());
        return ResponseEntity.noContent().build();
    }

    // ── Power users ──────────────────────────────────────────────────────────

    @GetMapping("/{policyId}/power-users")
    public List<Map<String, Object>> listPowerUsers(@PathVariable String policyId) {
        return powerUserRepo.findByProgrammableTokenPolicyId(policyId).stream()
                .map(this::powerUserToMap)
                .toList();
    }

    @PostMapping("/{policyId}/power-users")
    public ResponseEntity<?> addPowerUser(@PathVariable String policyId, @RequestBody Map<String, Object> body) {
        String pkh = (String) body.get("powerUserPkh");
        if (pkh == null || pkh.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "powerUserPkh is required"));
        }
        Object capObj = body.get("capabilities");
        if (!(capObj instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "capabilities is required (int bitfield, see RwaTokenPowerUserCapability)"));
        }
        int caps = ((Number) capObj).intValue();
        if (powerUserRepo.existsByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, pkh)) {
            return ResponseEntity.status(409).body(Map.of("error", "power user already exists"));
        }
        RwaTokenPowerUserEntity entity = RwaTokenPowerUserEntity.builder()
                .programmableTokenPolicyId(policyId)
                .powerUserPkh(pkh)
                .capabilities(caps)
                .label((String) body.getOrDefault("label", null))
                .addedAt(Instant.now())
                .build();
        powerUserRepo.save(entity);
        return ResponseEntity.ok(powerUserToMap(entity));
    }

    @PatchMapping("/{policyId}/power-users/{powerUserPkh}")
    public ResponseEntity<?> updatePowerUser(@PathVariable String policyId,
                                             @PathVariable String powerUserPkh,
                                             @RequestBody Map<String, Object> body) {
        java.util.Optional<RwaTokenPowerUserEntity> existing = powerUserRepo.findByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, powerUserPkh);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RwaTokenPowerUserEntity entity = existing.get();
        if (body.get("capabilities") instanceof Number n) entity.setCapabilities(n.intValue());
        if (body.containsKey("label")) entity.setLabel((String) body.get("label"));
        powerUserRepo.save(entity);
        return ResponseEntity.ok(powerUserToMap(entity));
    }

    @DeleteMapping("/{policyId}/power-users/{powerUserPkh}")
    public ResponseEntity<?> removePowerUser(@PathVariable String policyId, @PathVariable String powerUserPkh) {
        java.util.Optional<RwaTokenPowerUserEntity> existing = powerUserRepo.findByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, powerUserPkh);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        powerUserRepo.delete(existing.get());
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private RwaTokenSummary toSummary(RwaTokenRegistrationEntity reg) {
        String assetName = programmableTokenRegistryRepository.findByPolicyId(reg.getProgrammableTokenPolicyId())
                .map(p -> p.getAssetName())
                .orElse("");
        String displayName = decodeAssetNameSafely(assetName);
        long registeredAt = reg.getLastRootUpdateAt() != null
                ? reg.getLastRootUpdateAt().atOffset(ZoneOffset.UTC).toInstant().toEpochMilli()
                : 0L;
        return new RwaTokenSummary(
                reg.getProgrammableTokenPolicyId(),
                assetName,
                displayName,
                null,
                reg.isRequiresReceiverKyc(),
                registeredAt
        );
    }

    private Map<String, Object> denylistEntryToMap(RwaTokenDenylistEntryEntity e) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("memberPkh", e.getMemberPkh());
        m.put("reason", e.getReason());
        m.put("addedByPowerUserPkh", e.getAddedByPowerUserPkh());
        m.put("addedAt", e.getAddedAt() != null ? e.getAddedAt().toEpochMilli() : null);
        return m;
    }

    private Map<String, Object> powerUserToMap(RwaTokenPowerUserEntity e) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("powerUserPkh", e.getPowerUserPkh());
        m.put("capabilities", e.getCapabilities());
        m.put("label", e.getLabel());
        m.put("addedAt", e.getAddedAt() != null ? e.getAddedAt().toEpochMilli() : null);
        return m;
    }

    private String decodeAssetNameSafely(String hexAssetName) {
        if (hexAssetName == null || hexAssetName.isBlank()) return "<unnamed>";
        try {
            byte[] bytes = HexUtil.decodeHexString(hexAssetName);
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.isBlank()) return "<unnamed>";
            return s;
        } catch (Exception e) {
            return "<unnamed>";
        }
    }
}
