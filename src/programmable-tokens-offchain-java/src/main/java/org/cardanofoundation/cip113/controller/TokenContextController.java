package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.BlacklistInitEntity;
import org.cardanofoundation.cip113.entity.FreezeAndSeizeTokenRegistrationEntity;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.TokenContextResponse;
import org.cardanofoundation.cip113.model.TokenRegistrationRequest;
import org.cardanofoundation.cip113.repository.BlacklistInitRepository;
import org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${apiPrefix}/token-context")
@RequiredArgsConstructor
@Slf4j
public class TokenContextController {

    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final FreezeAndSeizeTokenRegistrationRepository freezeAndSeizeTokenRegistrationRepository;
    private final BlacklistInitRepository blacklistInitRepository;

    /** Optional — only present when the security-token substandard is enabled. */
    @Autowired(required = false)
    private SecurityTokenRegistrationRepository securityTokenRegistrationRepository;

    /** Prototype-scoped handler; resolved per request to read the live GS datum.
     *  {@code ObjectProvider} keeps this singleton controller decoupled from the
     *  handler's lifecycle. {@code @Autowired(required=false)} so the controller
     *  still loads when the security-token substandard is disabled. */
    @Autowired(required = false)
    private ObjectProvider<SecurityTokenSubstandardHandler> securityTokenHandlerProvider;

    /**
     * Get token context — returns substandardId + init params for a given policy ID.
     * Used by the SDK to determine which substandard handles a token.
     */
    @GetMapping("/{policyId}")
    public ResponseEntity<TokenContextResponse> getTokenContext(@PathVariable String policyId) {
        var registryEntry = programmableTokenRegistryRepository.findByPolicyId(policyId);

        if (registryEntry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var entry = registryEntry.get();
        var substandardId = entry.getSubstandardId();
        var assetName = entry.getAssetName();
        String blacklistNodePolicyId = null;
        String issuerAdminPkh = null;
        String blacklistInitTxHash = null;
        Integer blacklistInitOutputIndex = null;
        Boolean requiresReceiverKyc = null;
        Boolean transfersPaused = null;

        if ("freeze-and-seize".equals(substandardId)) {
            var tokenRegistration = freezeAndSeizeTokenRegistrationRepository
                    .findByProgrammableTokenPolicyId(policyId);

            if (tokenRegistration.isPresent()) {
                var fesReg = tokenRegistration.get();
                issuerAdminPkh = fesReg.getIssuerAdminPkh();
                var blacklistInit = fesReg.getBlacklistInit();
                if (blacklistInit != null) {
                    blacklistNodePolicyId = blacklistInit.getBlacklistNodePolicyId();
                    blacklistInitTxHash = blacklistInit.getTxHash();
                    blacklistInitOutputIndex = blacklistInit.getOutputIndex();
                }
            }
        }

        if ("security-token".equals(substandardId) && securityTokenRegistrationRepository != null) {
            var stReg = securityTokenRegistrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (stReg.isPresent()) {
                issuerAdminPkh = stReg.get().getIssuerAdminPkh();
                // The DB column for requiresReceiverKyc is set ONCE at registration
                // time and isn't refreshed when the admin runs SetRequiresReceiverKyc
                // on chain. transfersPaused has no DB column at all. Prefer the live
                // on-chain GS datum for both flags; fall back to the DB cache for
                // requiresReceiverKyc only when the indexer hasn't seen the GS UTxO.
                requiresReceiverKyc = stReg.get().isRequiresReceiverKyc();
                if (securityTokenHandlerProvider != null) {
                    SecurityTokenSubstandardHandler handler = securityTokenHandlerProvider.getIfAvailable();
                    if (handler != null) {
                        var live = handler.readGlobalState(policyId);
                        if (live.isPresent()) {
                            requiresReceiverKyc = live.get().requiresReceiverKyc();
                            transfersPaused = live.get().transfersPaused();
                        }
                    }
                }
            }
        }

        return ResponseEntity.ok(new TokenContextResponse(
                policyId,
                substandardId,
                assetName,
                blacklistNodePolicyId,
                issuerAdminPkh,
                blacklistInitTxHash,
                blacklistInitOutputIndex,
                requiresReceiverKyc,
                transfersPaused
        ));
    }

    /**
     * Register a token in the backend DB after SDK-built on-chain registration.
     * This is a DB-only operation — no transaction building.
     * Called by the frontend as a callback after successful on-chain registration.
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerToken(@RequestBody TokenRegistrationRequest request) {
        log.info("Token registry callback: policyId={}, substandardId={}", request.policyId(), request.substandardId());

        // Check if already registered
        if (programmableTokenRegistryRepository.existsByPolicyId(request.policyId())) {
            log.info("Token {} already registered, skipping", request.policyId());
            return ResponseEntity.ok().build();
        }

        // 1. Save to unified programmable token registry
        programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                .policyId(request.policyId())
                .substandardId(request.substandardId())
                .assetName(request.assetName() != null ? request.assetName() : "")
                .build());

        // 2. For FES: insert blacklist init (if not already present), then token registration
        if ("freeze-and-seize".equals(request.substandardId()) && request.blacklistNodePolicyId() != null) {
            // 2a. Insert blacklist init row if it doesn't exist yet (SDK-built registrations)
            var blacklistInitOpt = blacklistInitRepository
                    .findByBlacklistNodePolicyId(request.blacklistNodePolicyId());

            if (blacklistInitOpt.isEmpty() && request.blacklistInitTxHash() != null) {
                log.info("Inserting blacklist init for policyId={}, bootstrapUtxo={}#{}",
                        request.blacklistNodePolicyId(), request.blacklistInitTxHash(), request.blacklistInitOutputIndex());
                var newBlacklistInit = BlacklistInitEntity.builder()
                        .blacklistNodePolicyId(request.blacklistNodePolicyId())
                        .adminPkh(request.blacklistAdminPkh() != null ? request.blacklistAdminPkh() : "")
                        .txHash(request.blacklistInitTxHash())
                        .outputIndex(request.blacklistInitOutputIndex() != null ? request.blacklistInitOutputIndex() : 0)
                        .build();
                blacklistInitRepository.save(newBlacklistInit);
                blacklistInitOpt = java.util.Optional.of(newBlacklistInit);
            }

            // 2b. Insert FES token registration (FK to blacklist init)
            if (blacklistInitOpt.isPresent()) {
                freezeAndSeizeTokenRegistrationRepository.save(FreezeAndSeizeTokenRegistrationEntity.builder()
                        .programmableTokenPolicyId(request.policyId())
                        .issuerAdminPkh(request.issuerAdminPkh() != null ? request.issuerAdminPkh() : "")
                        .blacklistInit(blacklistInitOpt.get())
                        .build());
            } else {
                log.warn("BlacklistInit not found and no init data provided for blacklistNodePolicyId: {}", request.blacklistNodePolicyId());
            }
        }

        log.info("Token {} registered successfully as {}", request.policyId(), request.substandardId());
        return ResponseEntity.ok().build();
    }
}
