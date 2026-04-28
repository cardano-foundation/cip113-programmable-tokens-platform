package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.util.Pair;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.BlacklistInitResponse;
import org.cardanofoundation.cip113.repository.BlacklistInitRepository;
import org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.KycTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.repository.GlobalStateInitRepository;
import org.cardanofoundation.cip113.service.BlacklistQueryService;
import org.cardanofoundation.cip113.service.ComplianceOperationsService;
import org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.AddToBlacklistRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.BlacklistInitRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable.RemoveFromBlacklistRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.Seizeable.MultiSeizeRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.Seizeable.SeizeRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.AddTrustedEntityRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.GlobalStateInitRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.GlobalStateUpdateRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.GlobalStateManageable.RemoveTrustedEntityRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.WhitelistManageable.AddToWhitelistRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.WhitelistManageable.RemoveFromWhitelistRequest;
import org.cardanofoundation.cip113.service.substandard.capabilities.WhitelistManageable.WhitelistInitRequest;
import org.cardanofoundation.cip113.service.substandard.KycSubstandardHandler;
import org.cardanofoundation.cip113.service.substandard.context.FreezeAndSeizeContext;
import org.cardanofoundation.cip113.service.substandard.context.KycContext;
import org.cardanofoundation.cip113.service.substandard.context.SubstandardContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for compliance operations on programmable tokens.
 *
 * <p>This controller exposes endpoints for:</p>
 * <ul>
 *   <li><b>Blacklist</b> - Freeze/unfreeze addresses (init, add, remove)</li>
 *   <li><b>Whitelist</b> - KYC/securities compliance (init, add, remove)</li>
 *   <li><b>Seize</b> - Asset recovery from blacklisted addresses</li>
 * </ul>
 *
 * <p>All endpoints require a substandard that supports the relevant capability.
 * For example, blacklist operations require a substandard implementing
 * {@link org.cardanofoundation.cip113.service.substandard.capabilities.BlacklistManageable}.</p>
 */
@RestController
@RequestMapping("${apiPrefix}/compliance")
@RequiredArgsConstructor
@Slf4j
public class ComplianceController {

    private final ComplianceOperationsService complianceOperationsService;
    private final BlacklistQueryService blacklistQueryService;
    private final ApplicationContext applicationContext;

    private final BlacklistInitRepository blacklistInitRepository;

    private final FreezeAndSeizeTokenRegistrationRepository freezeAndSeizeTokenRegistrationRepository;
    private final KycTokenRegistrationRepository kycTokenRegistrationRepository;
    private final GlobalStateInitRepository globalStateInitRepository;

    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    // ========== Blacklist Endpoints ==========

    /**
     * Initialize a blacklist for a programmable token.
     * Creates the on-chain linked list structure for tracking blacklisted addresses.
     * Requires the token to be already registered in the programmable token registry.
     *
     * @param request        The blacklist initialization request (contains tokenPolicyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/blacklist/init")
    public ResponseEntity<?> initBlacklist(@RequestBody BlacklistInitRequest request,
                                           @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/blacklist/init - substandardId: {}, admin: {}",
                request.substandardId(), request.adminAddress());

        try {
            // Resolve substandard from policyId via unified registry
            var substandardId = request.substandardId();

            var context = switch (substandardId) {
                case "freeze-and-seize" -> FreezeAndSeizeContext.emptyContext();
                default -> null;
            };

            var txContext = complianceOperationsService.initBlacklist(substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(new BlacklistInitResponse(txContext.metadata().policyId(), txContext.unsignedCborTx()));
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error initializing blacklist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Add an address to the blacklist (freeze).
     * Once blacklisted, the address cannot transfer programmable tokens.
     *
     * @param request        The add to blacklist request (contains tokenPolicyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/blacklist/add")
    @Transactional
    public ResponseEntity<?> addToBlacklist(
            @RequestBody AddToBlacklistRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/blacklist/add - tokenPolicyId: {}, target: {}",
                request.tokenPolicyId(), request.targetAddress());

        try {
            // Resolve substandard from policyId via unified registry
            var substandardId = resolveSubstandardId(request.tokenPolicyId());

            var context = switch (substandardId) {
                case "freeze-and-seize" -> {
                    var dataOpt = freezeAndSeizeTokenRegistrationRepository.findByProgrammableTokenPolicyId(request.tokenPolicyId())
                            .flatMap(token -> blacklistInitRepository.findByBlacklistNodePolicyId(token.getBlacklistInit().getBlacklistNodePolicyId())
                                    .map(blacklistInitEntity -> new Pair<>(token, blacklistInitEntity)));

                    if (dataOpt.isEmpty()) {
                        throw new RuntimeException("could not find programmable token or blacklist init data");
                    }

                    var data = dataOpt.get();
                    var tokenRegistration = data.first();
                    var blacklistInitEntity = data.second();
                    yield FreezeAndSeizeContext.builder()
                            .issuerAdminPkh(tokenRegistration.getIssuerAdminPkh())
                            .assetName(request.assetName())
                            .blacklistManagerPkh(blacklistInitEntity.getAdminPkh())
                            .blacklistInitTxInput(TransactionInput.builder()
                                    .transactionId(blacklistInitEntity.getTxHash())
                                    .index(blacklistInitEntity.getOutputIndex())
                                    .build())
                            .build();
                }

                default -> null;
            };

            var txContext = complianceOperationsService.addToBlacklist(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error adding to blacklist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Remove an address from the blacklist (unfreeze).
     * Once removed, the address can transfer programmable tokens again.
     *
     * @param request        The remove from blacklist request (contains tokenPolicyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/blacklist/remove")
    public ResponseEntity<?> removeFromBlacklist(
            @RequestBody RemoveFromBlacklistRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/blacklist/remove - tokenPolicyId: {}, target: {}",
                request.tokenPolicyId(), request.targetAddress());

        try {
            // Resolve substandard from policyId via unified registry
            var substandardId = resolveSubstandardId(request.tokenPolicyId());

            var context = switch (substandardId) {
                case "freeze-and-seize" -> {
                    var dataOpt = freezeAndSeizeTokenRegistrationRepository.findByProgrammableTokenPolicyId(request.tokenPolicyId())
                            .flatMap(token -> blacklistInitRepository.findByBlacklistNodePolicyId(token.getBlacklistInit().getBlacklistNodePolicyId())
                                    .map(blacklistInitEntity -> new Pair<>(token, blacklistInitEntity)));

                    if (dataOpt.isEmpty()) {
                        throw new RuntimeException("could not find programmable token or blacklist init data");
                    }

                    var data = dataOpt.get();
                    var tokenRegistration = data.first();
                    var blacklistInitEntity = data.second();
                    yield FreezeAndSeizeContext.builder()
                            .issuerAdminPkh(tokenRegistration.getIssuerAdminPkh())
                            .assetName(request.assetName())
                            .blacklistManagerPkh(blacklistInitEntity.getAdminPkh())
                            .blacklistInitTxInput(TransactionInput.builder()
                                    .transactionId(blacklistInitEntity.getTxHash())
                                    .index(blacklistInitEntity.getOutputIndex())
                                    .build())
                            .build();
                }

                default -> null;
            };

            var txContext = complianceOperationsService.removeFromBlacklist(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error removing from blacklist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Check if an address is blacklisted for a specific token.
     * Returns the blacklist status without requiring transaction building.
     * This is a read-only query operation that checks the on-chain blacklist linked-list.
     *
     * @param tokenPolicyId  The programmable token policy ID
     * @param address        The bech32 address to check
     * @param protocolTxHash Optional protocol version tx hash (currently unused for queries)
     * @return JSON response with blacklist status
     */
    @GetMapping("/blacklist/check")
    public ResponseEntity<?> checkBlacklistStatus(
            @RequestParam String tokenPolicyId,
            @RequestParam String address,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("GET /compliance/blacklist/check - tokenPolicyId: {}, address: {}",
                tokenPolicyId, address);

        try {
            boolean isBlacklisted = blacklistQueryService.isAddressBlacklisted(
                    tokenPolicyId,
                    address
            );

            return ResponseEntity.ok(java.util.Map.of(
                    "tokenPolicyId", tokenPolicyId,
                    "address", address,
                    "blacklisted", isBlacklisted,
                    "frozen", isBlacklisted
            ));

        } catch (UnsupportedOperationException e) {
            log.warn("Blacklist check not implemented: {}", e.getMessage());
            // Return false for not-yet-implemented check (fail-safe)
            return ResponseEntity.ok(java.util.Map.of(
                    "tokenPolicyId", tokenPolicyId,
                    "address", address,
                    "blacklisted", false,
                    "frozen", false,
                    "error", "Blockchain query implementation pending"
            ));
        } catch (Exception e) {
            log.error("Error checking blacklist status", e);
            return ResponseEntity.internalServerError()
                    .body(java.util.Map.of("error", e.getMessage()));
        }
    }

    // ========== Whitelist Endpoints ==========
    // Used by substandardss that maintain an on-chain address whitelist (linked-list pattern).
    // KYC does NOT use these endpoints — it uses the /global-state/* endpoints instead.

    /**
     * Initialize a whitelist for a programmable token (security token).
     * Creates the on-chain linked list structure for tracking approved addresses.
     * Requires the token to be already registered in the programmable token registry.
     *
     * @param request        The whitelist initialization request (contains tokenPolicyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/whitelist/init")
    public ResponseEntity<?> initWhitelist(
            @RequestBody WhitelistInitRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/whitelist/init - tokenPolicyId: {}, admin: {}",
                request.tokenPolicyId(), request.adminAddress());

        try {
            var substandardId = request.substandardId() != null && !request.substandardId().isBlank()
                    ? request.substandardId()
                    : resolveSubstandardId(request.tokenPolicyId());

            SubstandardContext context = null; // extend with a switch when a whitelist-based substandard needs context

            var txContext = complianceOperationsService.initWhitelist(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error initializing whitelist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Add an address to the whitelist (grant approval).
     * Once whitelisted, the address can receive and transfer the token.
     *
     * @param request        The add to whitelist request (contains policyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/whitelist/add")
    public ResponseEntity<?> addToWhitelist(
            @RequestBody AddToWhitelistRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/whitelist/add - policyId: {}, target: {}",
                request.policyId(), request.targetCredential());

        try {
            var substandardId = resolveSubstandardId(request.policyId());

            SubstandardContext context = null; // extend with a switch when a whitelist-based substandard needs context

            var txContext = complianceOperationsService.addToWhitelist(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error adding to whitelist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Remove an address from the whitelist (revoke approval).
     * Once removed, the address can no longer receive the token.
     *
     * @param request        The remove from whitelist request (contains policyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/whitelist/remove")
    public ResponseEntity<?> removeFromWhitelist(
            @RequestBody RemoveFromWhitelistRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/whitelist/remove - policyId: {}, target: {}",
                request.policyId(), request.targetCredential());

        try {
            var substandardId = resolveSubstandardId(request.policyId());

            SubstandardContext context = null; // extend with a switch when a whitelist-based substandard needs context

            var txContext = complianceOperationsService.removeFromWhitelist(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error removing from whitelist", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ========== Global State Endpoints ==========
    // Used by the KYC substandard. Routes through GlobalStateManageable — not WhitelistManageable.

    /**
     * Initialize the global state UTxO for a KYC token deployment.
     *
     * @param request        The global state initialization request
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex and minted policy ID
     */
    @PostMapping("/global-state/init")
    public ResponseEntity<?> initGlobalState(
            @RequestBody GlobalStateInitRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/global-state/init - substandardId: {}, admin: {}",
                request.substandardId(), request.adminAddress());

        try {
            var substandardId = request.substandardId();

            var context = switch (substandardId) {
                case "kyc" -> KycContext.emptyContext();
                default -> (SubstandardContext) null;
            };

            var txContext = complianceOperationsService.initGlobalState(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error initializing global state", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Add a trusted entity (verification key) to the global state.
     *
     * @param request        The add-entity request
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/global-state/add-entity")
    public ResponseEntity<?> addToGlobalState(
            @RequestBody AddTrustedEntityRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/global-state/add-entity - policyId: {}, vkey: {}",
                request.policyId(), request.verificationKey());

        try {
            var substandardId = resolveSubstandardId(request.policyId());

            var context = switch (substandardId) {
                case "kyc" -> {
                    var kycData = kycTokenRegistrationRepository
                            .findByProgrammableTokenPolicyId(request.policyId())
                            .orElseThrow(() -> new RuntimeException("could not find KYC token registration"));
                    var gsInit = kycData.getGlobalStateInit();
                    yield KycContext.builder()
                            .issuerAdminPkh(kycData.getIssuerAdminPkh())
                            .globalStatePolicyId(gsInit.getGlobalStatePolicyId())
                            .globalStateInitTxInput(TransactionInput.builder()
                                    .transactionId(gsInit.getTxHash())
                                    .index(gsInit.getOutputIndex())
                                    .build())
                            .build();
                }
                default -> (SubstandardContext) null;
            };

            var txContext = complianceOperationsService.addTrustedEntity(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error adding trusted entity to global state", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Remove a trusted entity (verification key) from the global state.
     *
     * @param request        The remove-entity request
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/global-state/remove-entity")
    public ResponseEntity<?> removeFromGlobalState(
            @RequestBody RemoveTrustedEntityRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/global-state/remove-entity - policyId: {}, vkey: {}",
                request.policyId(), request.verificationKey());

        try {
            var substandardId = resolveSubstandardId(request.policyId());

            var context = switch (substandardId) {
                case "kyc" -> {
                    var kycData = kycTokenRegistrationRepository
                            .findByProgrammableTokenPolicyId(request.policyId())
                            .orElseThrow(() -> new RuntimeException("could not find KYC token registration"));
                    var gsInit = kycData.getGlobalStateInit();
                    yield KycContext.builder()
                            .issuerAdminPkh(kycData.getIssuerAdminPkh())
                            .globalStatePolicyId(gsInit.getGlobalStatePolicyId())
                            .globalStateInitTxInput(TransactionInput.builder()
                                    .transactionId(gsInit.getTxHash())
                                    .index(gsInit.getOutputIndex())
                                    .build())
                            .build();
                }
                default -> (SubstandardContext) null;
            };

            var txContext = complianceOperationsService.removeTrustedEntity(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error removing trusted entity from global state", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Read the current on-chain global state for a KYC token.
     * Returns parsed datum fields: transfers_paused, mintable_amount, trusted_entities, security_info.
     *
     * @param policyId The programmable token policy ID
     * @return Parsed global state data
     */
    @GetMapping("/global-state/read")
    public ResponseEntity<?> readGlobalState(@RequestParam String policyId) {

        log.info("GET /compliance/global-state/read - policyId: {}", policyId);

        try {
            var handler = applicationContext.getBean(KycSubstandardHandler.class);
            var result = handler.readGlobalState(policyId);

            if (result.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(result.get());

        } catch (Exception e) {
            log.error("Error reading global state for policyId={}", policyId, e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Update the global state UTxO for a programmable token.
     * Supports pausing/unpausing transfers, updating mintable amount, and modifying security info.
     *
     * @param request        The global state update request (action + value)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/global-state/update")
    public ResponseEntity<?> updateGlobalState(
            @RequestBody GlobalStateUpdateRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/global-state/update - policyId: {}, action: {}",
                request.policyId(), request.action());

        try {
            var substandardId = resolveSubstandardId(request.policyId());

            var context = switch (substandardId) {
                case "kyc" -> {
                    var kycDataOpt = kycTokenRegistrationRepository
                            .findByProgrammableTokenPolicyId(request.policyId());
                    if (kycDataOpt.isEmpty()) {
                        throw new RuntimeException("could not find KYC token registration");
                    }
                    var kycData = kycDataOpt.get();
                    var gsInit = kycData.getGlobalStateInit();
                    yield KycContext.builder()
                            .issuerAdminPkh(kycData.getIssuerAdminPkh())
                            .globalStatePolicyId(gsInit.getGlobalStatePolicyId())
                            .globalStateInitTxInput(TransactionInput.builder()
                                    .transactionId(gsInit.getTxHash())
                                    .index(gsInit.getOutputIndex())
                                    .build())
                            .build();
                }
                default -> null;
            };

            var txContext = complianceOperationsService.updateGlobalState(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error updating global state", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ========== Seize Endpoints ==========

    /**
     * Seize assets from a blacklisted address.
     * The target address must be on the blacklist for this operation to succeed.
     *
     * @param request        The seize request (contains unit with policyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/seize")
    public ResponseEntity<?> seize(
            @RequestBody SeizeRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/seize - unit: {}, destination: {}",
                request.unit(), request.destinationAddress());

        try {

            var progToken = AssetType.fromUnit(request.unit());

            // Resolve substandard from policyId via unified registry
            var substandardId = resolveSubstandardId(progToken.policyId());

            var context = switch (substandardId) {
                case "freeze-and-seize" -> {
                    var dataOpt = freezeAndSeizeTokenRegistrationRepository.findByProgrammableTokenPolicyId(progToken.policyId())
                            .flatMap(token -> blacklistInitRepository.findByBlacklistNodePolicyId(token.getBlacklistInit().getBlacklistNodePolicyId())
                                    .map(blacklistInitEntity -> new Pair<>(token, blacklistInitEntity)));

                    if (dataOpt.isEmpty()) {
                        throw new RuntimeException("could not find programmable token or blacklist init data");
                    }

                    var data = dataOpt.get();
                    var tokenRegistration = data.first();
                    var blacklistInitEntity = data.second();
                    yield FreezeAndSeizeContext.builder()
                            .issuerAdminPkh(tokenRegistration.getIssuerAdminPkh())
                            .assetName(progToken.assetName())
                            .blacklistManagerPkh(blacklistInitEntity.getAdminPkh())
                            .blacklistInitTxInput(TransactionInput.builder()
                                    .transactionId(blacklistInitEntity.getTxHash())
                                    .index(blacklistInitEntity.getOutputIndex())
                                    .build())
                            .build();
                }

                default -> null;
            };


            var txContext = complianceOperationsService.seize(
                    substandardId, request, protocolTxHash, context);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error seizing assets", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /**
     * Seize assets from multiple UTxOs in a single transaction.
     * More efficient for seizing from addresses with multiple token UTxOs.
     *
     * @param request        The multi-seize request (contains policyId)
     * @param protocolTxHash Optional protocol version tx hash
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/seize/multi")
    public ResponseEntity<?> multiSeize(
            @RequestBody MultiSeizeRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /compliance/seize/multi - policyId: {}, utxo count: {}, destination: {}",
                request.policyId(), request.utxoReferences().size(), request.destinationAddress());

        try {
            // Resolve substandard from policyId via unified registry
            var substandardId = resolveSubstandardId(request.policyId());

            var txContext = complianceOperationsService.multiSeize(
                    substandardId, request, protocolTxHash, null);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (UnsupportedOperationException e) {
            log.warn("Capability not supported: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            log.error("Error multi-seizing assets", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    // ========== Helper Methods ==========

    /**
     * Resolve substandard ID from the unified programmable token registry.
     *
     * @param policyId The programmable token policy ID
     * @return The substandard ID
     * @throws IllegalArgumentException if the token is not registered
     */
    private String resolveSubstandardId(String policyId) {
        return programmableTokenRegistryRepository.findByPolicyId(policyId)
                .map(ProgrammableTokenRegistryEntity::getSubstandardId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Token not registered in programmable token registry: " + policyId));
    }
}
