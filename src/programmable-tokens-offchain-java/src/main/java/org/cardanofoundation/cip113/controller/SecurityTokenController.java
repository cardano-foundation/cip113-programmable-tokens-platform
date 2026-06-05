package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.SecurityTokenDenylistEntryEntity;
import org.cardanofoundation.cip113.entity.SecurityTokenPowerUserEntity;
import org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.SecurityTokenRegisterRequest;
import org.cardanofoundation.cip113.model.SecurityTokenSummary;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler;
import org.cardanofoundation.cip113.service.substandard.SubstandardHandlerFactory;
import org.cardanofoundation.cip113.service.substandard.context.SecurityTokenContext;
import org.cardanofoundation.cip113.repository.SecurityTokenDenylistEntryRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenMemberLeafRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenPowerUserRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository;
import org.cardanofoundation.cip113.scheduling.AdminSigningKeyProvider;
import org.cardanofoundation.cip113.service.SecurityTokenAllowlistService;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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

/** Public + admin endpoints for the security-token substandard. */
@RestController
@RequestMapping("${apiPrefix}/security-token")
@ConditionalOnProperty(name = "securityToken.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityTokenController {

    private static final int LIST_CAP = 200;

    private final SecurityTokenRegistrationRepository registrationRepo;
    private final SecurityTokenMemberLeafRepository memberLeafRepo;
    private final SecurityTokenDenylistEntryRepository denylistRepo;
    private final SecurityTokenPowerUserRepository powerUserRepo;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final SecurityTokenAllowlistService allowlistService;
    private final AdminSigningKeyProvider adminSigningKeyProvider;
    private final SubstandardHandlerFactory handlerFactory;
    private final ProtocolBootstrapService protocolBootstrapService;

    // ── Discovery ────────────────────────────────────────────────────────────

    /** Backend admin pkh + address. Token registrations must use this pkh as
     *  {@code issuerAdminPkh} so the backend can autonomously sign root updates. */
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
     *  Persists a {@code SecurityTokenRegistrationEntity} row (keyed by
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
            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler) handlerFactory
                    .getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildAddPowerUserTransaction(
                    policyId, powerUserPkh, ((Number) capsObj).intValue(), adminAddress);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("security-token AddPowerUser failed for policy={}", policyId, e);
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
     *  each change is a {@link SecurityTokenSubstandardHandler.GsChangeSpec}. */
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
            List<SecurityTokenSubstandardHandler.GsChangeSpec> changes = new java.util.ArrayList<>();
            for (Object o : changesList) {
                if (!(o instanceof Map<?, ?> m)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "each change must be an object"));
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> change = (Map<String, Object>) m;
                changes.add(new SecurityTokenSubstandardHandler.GsChangeSpec(
                        (String) change.get("action"),
                        (Boolean) change.get("transfersPaused"),
                        (String) change.get("newSecurityInfoHex"),
                        (String) change.get("trustedVkeyHex"),
                        (String) change.get("trustedMetadataHex"),
                        (String) change.get("trustedOldVkeyHex"),
                        (String) change.get("trustedNewVkeyHex"),
                        (String) change.get("trustedNewMetadataHex"),
                        (Boolean) change.get("requiresReceiverKycEnabled"),
                        (String) change.get("newMemberRootHashHex"),
                        (String) change.get("newAdminCredentialHashHex")
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

            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler) handlerFactory
                    .getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<List<String>> result = handler.buildGlobalStateUpdateChain(
                    policyId, changes, feePayerAddress, signerPkh, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTxs", result.metadata()));
        } catch (Exception e) {
            log.error("security-token GS update chain failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Frontend callback after a successful manual root-publish. Updates the DB
     *  row's {@code memberRootHashOnchain / lastRootUpdateTxHash / lastRootUpdateAt}
     *  and marks the current leaves as published. Previously the autonomous
     *  SecurityTokenRootSyncJob did this after submit+confirm; with that job
     *  gone, the user-triggered path needs an explicit acknowledgement so the
     *  DB stays in sync with the chain.
     *
     *  <p>Body: {@code { txHash, newRootHashHex }}. Idempotent — safe to call
     *  multiple times for the same tx hash. */
    @PostMapping("/{policyId}/global-state/root-published")
    public ResponseEntity<?> acknowledgeRootPublish(@PathVariable String policyId,
                                                    @RequestBody Map<String, Object> body) {
        try {
            String txHash = (String) body.get("txHash");
            String newRootHashHex = (String) body.get("newRootHashHex");
            if (txHash == null || txHash.isBlank() || newRootHashHex == null || newRootHashHex.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "txHash and newRootHashHex are required"));
            }
            java.util.Optional<SecurityTokenRegistrationEntity> regOpt = registrationRepo.findByProgrammableTokenPolicyId(policyId);
            if (regOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error",
                        "security-token registration not found for " + policyId));
            }
            SecurityTokenRegistrationEntity reg = regOpt.get();
            reg.setMemberRootHashOnchain(newRootHashHex);
            reg.setMemberRootHashLocal(newRootHashHex);
            reg.setLastRootUpdateTxHash(txHash);
            reg.setLastRootUpdateAt(java.time.Instant.now());
            registrationRepo.save(reg);
            int marked = allowlistService.markLeavesPublished(policyId, java.time.Instant.now());
            log.info("security-token root publish ack: policy={} tx={} root={} leaves_marked={}",
                    policyId, txHash, newRootHashHex, marked);
            return ResponseEntity.ok(Map.of(
                    "policyId", policyId,
                    "memberRootHashOnchain", newRootHashHex,
                    "lastRootUpdateTxHash", txHash,
                    "lastRootUpdateAt", reg.getLastRootUpdateAt().toString(),
                    "leavesMarkedPublished", marked));
        } catch (Exception e) {
            log.error("security-token acknowledgeRootPublish failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** User-signed UpdateMemberRootHash tx. Admin opens the admin panel, clicks
     *  "Publish member root", backend computes the current local MPF root from
     *  the allowlist service, builds the GS-spend tx with the new root as the
     *  redeemer's action payload, and returns the unsigned CBOR. Frontend signs
     *  with the user's wallet (must be the on-chain admin per the GS datum)
     *  and submits. Body: {@code { feePayerAddress }} (must equal the admin
     *  wallet whose payment cred = on-chain {@code admin_credential_hash}). */
    @PostMapping("/{policyId}/update-member-root-hash")
    public ResponseEntity<?> updateMemberRootHash(@PathVariable String policyId,
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

            byte[] currentLocalRoot = allowlistService.currentRoot(policyId);

            String signerPkh = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    new com.bloxbean.cardano.client.address.Address(feePayerAddress)
                            .getPaymentCredentialHash()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "feePayerAddress has no payment credential: " + feePayerAddress)));

            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler) handlerFactory
                    .getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildUpdateMemberRootHashTransaction(
                    policyId, currentLocalRoot, feePayerAddress, signerPkh, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of(
                    "unsignedCborTx", result.unsignedCborTx(),
                    "newRootHashHex", com.bloxbean.cardano.client.util.HexUtil.encodeHexString(currentLocalRoot)));
        } catch (Exception e) {
            log.error("security-token UpdateMemberRootHash failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** One-shot admin tx that registers the substandard's transfer-logic stake
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
            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler) handlerFactory
                    .getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<Void> result = handler.buildRegisterTransferLogicTransaction(
                    policyId, feePayerAddress, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        result.error() != null ? result.error() : "build failed"));
            }
            return ResponseEntity.ok(Map.of("unsignedCborTx", result.unsignedCborTx()));
        } catch (Exception e) {
            log.error("security-token register-transfer-logic failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Build the full security-token registration chain (genesis → AddPowerUser →
     *  registration -> transfer registration) as four unsigned CBORs. The frontend signs all four in one
     *  CIP-30 {@code signTxs} call, then POSTs the signed CBORs (in order) to
     *  {@code /issue-token/submit-chain} which submits them sequentially via the
     *  backend's submission service — bypassing the wallet's submission backend so
     *  mempool-chained txs aren't rejected.
     *
     *  <p>Returns {@code { genesisCborHex, addPowerUserCborHex, registrationCborHex,
     *  globalStatePolicyId, programmableTokenPolicyId, denylistPolicyId,
     *  powerUsersPolicyId, genesisTxHash, addPowerUserTxHash, registrationTxHash } }. */
    @PostMapping("/build-chain")
    public ResponseEntity<?> buildChain(@RequestBody SecurityTokenRegisterRequest request) {
        try {
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler)
                    handlerFactory.getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<SecurityTokenSubstandardHandler.ChainBuildResult> result = handler.buildFullRegistrationChain(request, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", result.error() != null ? result.error() : "chain build failed"));
            }
            SecurityTokenSubstandardHandler.ChainBuildResult meta = result.metadata();
            // Use HashMap (Map.of caps at 10 entries; we now have 12).
            // Null-value entries (e.g. the optional 4th tx) are skipped so the
            // JSON response omits them, keeping the wire shape forward-compat.
            Map<String, Object> resp = new java.util.HashMap<>();
            resp.put("genesisCborHex", meta.genesisCborHex());
            resp.put("addPowerUserCborHex", meta.addPowerUserCborHex());
            resp.put("registrationCborHex", meta.registrationCborHex());
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
            resp.put("registrationTxHash", meta.registrationTxHash());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("security-token build-chain failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/init")
    public ResponseEntity<?> initGlobalState(@RequestBody SecurityTokenRegisterRequest request) {
        try {
            ProtocolBootstrapParams protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
            if (protocolParams == null) {
                return ResponseEntity.status(503).body(Map.of("error", "protocol params not loaded"));
            }
            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler)
                    handlerFactory.getHandler("security-token", SecurityTokenContext.emptyContext());
            TransactionContext<TransactionContext.RegistrationResult> result = handler.buildGlobalStateInitTransaction(request, protocolParams);
            if (!result.isSuccessful()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", result.error() != null ? result.error() : "init failed"));
            }
            // The wizard expects {globalStatePolicyId} for kyc-extended parity, but
            // the value we return here is actually the prog-token (issuance) policy id —
            // that's what the registration tx is keyed on downstream. We also return
            // {programmableTokenPolicyId} explicitly so clients have an unambiguous name.
            String progTokenPolicyId = result.metadata() != null ? result.metadata().policyId() : null;
            return ResponseEntity.ok(Map.of(
                    "unsignedCborTx", result.unsignedCborTx(),
                    "globalStatePolicyId", progTokenPolicyId,
                    "programmableTokenPolicyId", progTokenPolicyId));
        } catch (Exception e) {
            log.error("security-token init failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/tokens")
    public List<SecurityTokenSummary> listTokens() {
        return registrationRepo.findAllByOrderByLastRootUpdateAtDesc(PageRequest.of(0, LIST_CAP)).stream()
                .map(this::toSummary)
                .toList();
    }

    /** Read the on-chain GS datum for a registered security-token policy.
     *  The mint UI uses this to display the remaining mintable_amount and the
     *  current transfers_paused state before letting the admin mint more. */
    @GetMapping("/{policyId}/global-state")
    public ResponseEntity<?> getGlobalState(@PathVariable String policyId) {
        try {
            SecurityTokenSubstandardHandler handler = (SecurityTokenSubstandardHandler)
                    handlerFactory.getHandler("security-token", SecurityTokenContext.emptyContext());
            // Compute the current local MPF root so the admin UI can show whether
            // the on-chain root is stale (i.e. needs a publish). The local root
            // is the SHA-256 of the merkleized leaf set in the backend's
            // allowlist DB; the on-chain root is what's currently in the GS datum.
            String localRootHex = com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    allowlistService.currentRoot(policyId));
            return handler.readGlobalState(policyId)
                    .<ResponseEntity<?>>map(gs -> {
                        Map<String, Object> resp = new java.util.HashMap<>();
                        resp.put("policyId", gs.policyId());
                        resp.put("transfersPaused", gs.transfersPaused());
                        resp.put("mintableAmount", gs.mintableAmount());
                        resp.put("trustedEntityVkeys", gs.trustedEntityVkeys());
                        resp.put("securityInfoHex", gs.securityInfoHex() != null ? gs.securityInfoHex() : "");
                        resp.put("memberRootHash", gs.memberRootHash() != null ? gs.memberRootHash() : "");
                        resp.put("memberRootHashLocal", localRootHex);
                        resp.put("requiresReceiverKyc", gs.requiresReceiverKyc());
                        resp.put("adminCredentialHash", gs.adminCredentialHash());
                        return ResponseEntity.ok(resp);
                    })
                    .orElse(ResponseEntity.status(404).body(Map.of(
                            "error", "global-state UTxO not found on chain for " + policyId)));
        } catch (Exception e) {
            log.error("security-token getGlobalState failed for policy={}", policyId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Allowlist (members) ──────────────────────────────────────────────────

    @GetMapping("/{policyId}/proofs/{memberPkh}")
    public ResponseEntity<?> getMemberProof(@PathVariable String policyId, @PathVariable String memberPkh) {
        try {
            byte[] pkhBytes = HexUtil.decodeHexString(memberPkh);
            long now = System.currentTimeMillis();

            // 404 not present, 410 expired, 425 not yet published — frontend hooks switch on these.
            java.util.Optional<org.cardanofoundation.cip113.entity.SecurityTokenMemberLeafEntity> existing = memberLeafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh);
            if (existing.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "member not found in allowlist"));
            }
            if (existing.get().getValidUntilMs() < now) {
                return ResponseEntity.status(410).body(Map.of("error", "member leaf has expired",
                        "validUntilMs", existing.get().getValidUntilMs()));
            }
            if (existing.get().getPublishedAt() == null) {
                return ResponseEntity.status(425).body(Map.of(
                        "error", "member added to local allowlist but on-chain publish is pending",
                        "addedAt", existing.get().getAddedAt().toEpochMilli(),
                        "memberPkh", memberPkh));
            }

            java.util.Optional<SecurityTokenAllowlistService.MpfLeafView> view = allowlistService.inclusionProof(policyId, pkhBytes, now);
            if (view.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "MPF proof unavailable"));
            }
            SecurityTokenAllowlistService.MpfLeafView v = view.get();
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

    /** Admin/test-only: upsert an allowlist member manually. Regular users land
     *  in the tree via the KERI auto-upsert hook ({@code SecurityTokenMembershipHook}). */
    @PostMapping("/{policyId}/members")
    public ResponseEntity<?> upsertMember(@PathVariable String policyId, @RequestBody Map<String, Object> body) {
        if (!"security-token".equals(programmableTokenRegistryRepository.findByPolicyId(policyId)
                .map(reg -> reg.getSubstandardId()).orElse(""))) {
            return ResponseEntity.badRequest().body(Map.of("error", "policyId is not a security-token"));
        }
        String boundAddress = (String) body.get("boundAddress");
        if (boundAddress == null || boundAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "boundAddress is required"));
        }
        Object validUntilObj = body.get("validUntilMs");
        if (!(validUntilObj instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("error", "validUntilMs is required (number)"));
        }
        long validUntilMs = ((Number) validUntilObj).longValue();
        String sessionId = (String) body.getOrDefault("kycSessionId", null);

        // Identity is the stake credential — see SecurityTokenSubstandardHandler#buildTransferTransaction.
        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(boundAddress);
        if (pkh == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "could not derive stake credential hash from boundAddress (base address required)"));
        }
        try {
            allowlistService.putMember(policyId, pkh, validUntilMs, boundAddress, sessionId);
            byte[] localRoot = allowlistService.currentRoot(policyId);
            return ResponseEntity.ok(Map.of(
                    "memberPkh", HexUtil.encodeHexString(pkh),
                    "currentRootLocal", HexUtil.encodeHexString(localRoot)));
        } catch (Exception e) {
            log.error("upsertMember failed for policy={} address={}", policyId, boundAddress, e);
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
     *  once {@code SecurityTokenSubstandardHandler#buildAddDenylistEntryTransaction}
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
        SecurityTokenDenylistEntryEntity entry = SecurityTokenDenylistEntryEntity.builder()
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
        java.util.Optional<SecurityTokenDenylistEntryEntity> existing = denylistRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh);
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
                    "capabilities is required (int bitfield, see SecurityTokenPowerUserCapability)"));
        }
        int caps = ((Number) capObj).intValue();
        if (powerUserRepo.existsByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, pkh)) {
            return ResponseEntity.status(409).body(Map.of("error", "power user already exists"));
        }
        SecurityTokenPowerUserEntity entity = SecurityTokenPowerUserEntity.builder()
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
        java.util.Optional<SecurityTokenPowerUserEntity> existing = powerUserRepo.findByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, powerUserPkh);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SecurityTokenPowerUserEntity entity = existing.get();
        if (body.get("capabilities") instanceof Number n) entity.setCapabilities(n.intValue());
        if (body.containsKey("label")) entity.setLabel((String) body.get("label"));
        powerUserRepo.save(entity);
        return ResponseEntity.ok(powerUserToMap(entity));
    }

    @DeleteMapping("/{policyId}/power-users/{powerUserPkh}")
    public ResponseEntity<?> removePowerUser(@PathVariable String policyId, @PathVariable String powerUserPkh) {
        java.util.Optional<SecurityTokenPowerUserEntity> existing = powerUserRepo.findByProgrammableTokenPolicyIdAndPowerUserPkh(policyId, powerUserPkh);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        powerUserRepo.delete(existing.get());
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private SecurityTokenSummary toSummary(SecurityTokenRegistrationEntity reg) {
        String assetName = programmableTokenRegistryRepository.findByPolicyId(reg.getProgrammableTokenPolicyId())
                .map(p -> p.getAssetName())
                .orElse("");
        String displayName = decodeAssetNameSafely(assetName);
        long registeredAt = reg.getLastRootUpdateAt() != null
                ? reg.getLastRootUpdateAt().atOffset(ZoneOffset.UTC).toInstant().toEpochMilli()
                : 0L;
        return new SecurityTokenSummary(
                reg.getProgrammableTokenPolicyId(),
                assetName,
                displayName,
                null,
                reg.isRequiresReceiverKyc(),
                registeredAt
        );
    }

    private Map<String, Object> denylistEntryToMap(SecurityTokenDenylistEntryEntity e) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("memberPkh", e.getMemberPkh());
        m.put("reason", e.getReason());
        m.put("addedByPowerUserPkh", e.getAddedByPowerUserPkh());
        m.put("addedAt", e.getAddedAt() != null ? e.getAddedAt().toEpochMilli() : null);
        return m;
    }

    private Map<String, Object> powerUserToMap(SecurityTokenPowerUserEntity e) {
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
