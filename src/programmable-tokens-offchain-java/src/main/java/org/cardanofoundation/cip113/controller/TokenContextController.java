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
import org.cardanofoundation.cip113.repository.RegistryNodeRepository;
import org.cardanofoundation.cip113.service.FesProvenanceReconstructor;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
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
    private final RegistryNodeRepository registryNodeRepository;
    private final FesProvenanceReconstructor fesProvenanceReconstructor;
    private final FreezeAndSeizeTokenRegistrationRepository freezeAndSeizeTokenRegistrationRepository;
    private final BlacklistInitRepository blacklistInitRepository;
    private final org.cardanofoundation.cip113.service.Cip68MetadataService cip68MetadataService;
    private final org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService fesScriptBuilder;
    private final org.cardanofoundation.cip113.service.ProtocolScriptBuilderService protocolScriptBuilderService;
    private final org.cardanofoundation.cip113.service.ProtocolBootstrapService protocolBootstrapService;

    /** Optional — only present when the rwa-token module is enabled. */
    @Autowired(required = false)
    private RwaTokenRegistrationRepository rwaTokenRegistrationRepository;

    /** Prototype-scoped handler; resolved per request to read the live GS datum.
     *  {@code ObjectProvider} keeps this singleton controller decoupled from the
     *  handler's lifecycle. {@code @Autowired(required=false)} so the controller
     *  still loads when the rwa-token module is disabled. */
    @Autowired(required = false)
    private ObjectProvider<RwaTokenModuleHandler> rwaTokenHandlerProvider;

    /**
     * Get token context — returns moduleId + init params for a given policy ID.
     * Used by the SDK to determine which module handles a token.
     */
    @GetMapping("/{policyId}")
    public ResponseEntity<TokenContextResponse> getTokenContext(@PathVariable String policyId) {
        var registryEntry = programmableTokenRegistryRepository.findByPolicyId(policyId);

        if (registryEntry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var entry = registryEntry.get();
        var moduleId = entry.getModuleId();
        var assetName = entry.getAssetName();

        // The registry node is keyed by the token's policy id. Null when we have not indexed it,
        // which a client must be able to distinguish from "indexed, no provenance published".
        var registryNode = registryNodeRepository.findByKey(policyId);
        var transferLogicScript = registryNode
                .map(node -> node.getTransferLogicScript())
                .filter(hash -> hash != null && !hash.isBlank())
                .orElse(null);

        String blacklistNodePolicyId = null;
        String issuerAdminPkh = null;
        String blacklistInitTxHash = null;
        Integer blacklistInitOutputIndex = null;
        Boolean requiresReceiverKyc = null;
        Boolean requiresSenderKyc = null;
        Boolean transfersPaused = null;
        String blacklistAdminPkh = null;
        org.cardanofoundation.cip113.model.Cip68Metadata cip68Metadata = null;
        String cip68Status = null;

        if ("freeze-and-seize".equals(moduleId)) {
            var tokenRegistration = freezeAndSeizeTokenRegistrationRepository
                    .findByProgrammableTokenPolicyId(policyId);

            // These rows are written only by the registration callback and are never derived
            // from chain, so a database reset loses them for every token registered before it.
            // The values are recoverable from the token's published CIP-171 provenance, and the
            // reconstruction verifies both hops against hashes the chain already reports -- so
            // it either persists a proven record or persists nothing.
            if (tokenRegistration.isEmpty() && transferLogicScript != null) {
                tokenRegistration = fesProvenanceReconstructor.reconstruct(
                        policyId,
                        transferLogicScript,
                        registryNode.map(node -> node.getProtocolParams().getProgLogicScriptHash())
                                .orElse(null));
            }

            if (tokenRegistration.isPresent()) {
                var fesReg = tokenRegistration.get();
                issuerAdminPkh = fesReg.getIssuerAdminPkh();
                var blacklistInit = fesReg.getBlacklistInit();
                if (blacklistInit != null) {
                    blacklistNodePolicyId = blacklistInit.getBlacklistNodePolicyId();
                    blacklistInitTxHash = blacklistInit.getTxHash();
                    blacklistInitOutputIndex = blacklistInit.getOutputIndex();
                    blacklistAdminPkh = blacklistInit.getAdminPkh();
                }
            }
        }

        if ("rwa-token".equals(moduleId) && rwaTokenRegistrationRepository != null) {
            var stReg = rwaTokenRegistrationRepository.findByProgrammableTokenPolicyId(policyId);
            if (stReg.isPresent()) {
                issuerAdminPkh = stReg.get().getIssuerAdminPkh();
                // The DB column for requiresReceiverKyc is set ONCE at registration
                // time and isn't refreshed when the admin runs SetRequiresReceiverKyc
                // on chain. transfersPaused has no DB column at all. Prefer the live
                // on-chain GS datum for both flags; fall back to the DB cache for
                // requiresReceiverKyc only when the indexer hasn't seen the GS UTxO.
                requiresReceiverKyc = stReg.get().isRequiresReceiverKyc();
                if (rwaTokenHandlerProvider != null) {
                    RwaTokenModuleHandler handler = rwaTokenHandlerProvider.getIfAvailable();
                    if (handler != null) {
                        var live = handler.readGlobalState(policyId);
                        if (live.isPresent()) {
                            requiresReceiverKyc = live.get().requiresReceiverKyc();
                            requiresSenderKyc = live.get().requiresSenderKyc();
                            transfersPaused = live.get().transfersPaused();
                        }
                    }
                }
            }
        }

        // CIP-68 metadata lives in the reference token's datum, not in any row here. Read it back
        // rather than reporting only the cip68Enabled flag the registration callback stored: a
        // flag says metadata was INTENDED, which is not the same as metadata being there.
        if (assetName != null && !assetName.isBlank()) {
            var cip68 = cip68MetadataService.read(policyId, assetName);
            cip68Metadata = cip68.metadata();
            cip68Status = cip68.reason() == null ? null : cip68.reason().name();
        }

        return ResponseEntity.ok(new TokenContextResponse(
                policyId,
                moduleId,
                assetName,
                blacklistNodePolicyId,
                issuerAdminPkh,
                blacklistInitTxHash,
                blacklistInitOutputIndex,
                requiresReceiverKyc,
                requiresSenderKyc,
                transfersPaused,
                transferLogicScript,
                blacklistAdminPkh,
                cip68Metadata,
                cip68Status
        ));
    }

    /**
     * Register a token in the backend DB after SDK-built on-chain registration.
     * This is a DB-only operation — no transaction building.
     * Called by the frontend as a callback after successful on-chain registration.
     */
    @PostMapping("/register")
    public ResponseEntity<?> registerToken(@RequestBody TokenRegistrationRequest request) {
        log.info("Token registry callback: policyId={}, moduleId={}", request.policyId(), request.moduleId());

        // Check if already registered
        if (programmableTokenRegistryRepository.existsByPolicyId(request.policyId())) {
            log.info("Token {} already registered, skipping", request.policyId());
            return ResponseEntity.ok().build();
        }

        // 1. Save to unified programmable token registry
        programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                .policyId(request.policyId())
                .moduleId(request.moduleId())
                .assetName(request.assetName() != null ? request.assetName() : "")
                .build());

        // 2. For FES: insert blacklist init (if not already present), then token registration
        if ("freeze-and-seize".equals(request.moduleId()) && request.blacklistNodePolicyId() != null) {
            // ⛔ DERIVE BEFORE STORING. Everything below arrives from the CALLER — policyId,
            // issuerAdminPkh, assetName — and until now was written on trust. A freeze-and-seize
            // token's policy id IS the hash of issuance_mint parameterised by
            // issuer_admin(adminPkh, assetName), so those three are not independent: either they
            // reproduce each other or the row describes a token that does not exist.
            //
            // Two such rows were found on preprod 2026-09-30, and both cost real debugging time:
            //   * one whose issuerAdminPkh derived a DIFFERENT policy than the token it was keyed
            //     by, so every later operation was refused in terms that named the token;
            //   * one for a policy that had NEVER BEEN MINTED — a registration recorded for an
            //     attempt that failed, whose plausible-looking policy id then appeared in an error
            //     message and sent the reader looking for a token that never existed.
            //
            // `01bbe84` established the rule for the read side — "offered as a candidate, never
            // trusted as an answer" — and the write side kept trusting. This closes it: a row that
            // cannot be derived is refused, loudly, at the moment it would have been stored.
            try {
                var protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
                var issuerAdmin = fesScriptBuilder.buildIssuerAdminScript(
                        com.bloxbean.cardano.client.address.Credential.fromKey(request.issuerAdminPkh()),
                        request.assetName());
                var derived = protocolScriptBuilderService
                        .getParameterizedIssuanceMintScript(protocolParams, issuerAdmin)
                        .getPolicyId();
                if (!derived.equalsIgnoreCase(request.policyId())) {
                    log.error("Refusing token registration: policyId {} is not derivable from the "
                            + "supplied (issuerAdminPkh {}, assetName {}) — that pair derives {}",
                            request.policyId(), request.issuerAdminPkh(), request.assetName(), derived);
                    return ResponseEntity.badRequest().body(java.util.Map.of(
                            "error", "the supplied policyId is not derivable from issuerAdminPkh and assetName",
                            "policyId", request.policyId(),
                            "derivedFromSuppliedPair", derived,
                            "issuerAdminPkh", String.valueOf(request.issuerAdminPkh()),
                            "assetName", String.valueOf(request.assetName()),
                            "detail", "A freeze-and-seize token's policy id is the hash of "
                                    + "issuance_mint parameterised by issuer_admin(adminPkh, assetName), "
                                    + "so these three cannot disagree. Storing them would record a row "
                                    + "describing a different token — or one that was never minted. "
                                    + "For CIP-68 the assetName must be the LABELLED name as minted."));
                }
            } catch (Exception e) {
                // ⚠ A DERIVATION THAT CANNOT RUN MUST NOT SILENTLY PASS. If the protocol params or
                // the blueprint are unavailable, we cannot tell a good row from a bad one — and the
                // whole point of this guard is that a bad row is expensive and invisible. Refuse and
                // say why, rather than fall back to the trust this replaces.
                log.error("Could not derive the policy id to validate a token registration", e);
                return ResponseEntity.internalServerError().body(java.util.Map.of(
                        "error", "could not verify the supplied policyId against issuerAdminPkh and assetName",
                        "detail", String.valueOf(e.getMessage()),
                        "why", "The registration is refused rather than stored unverified: a row whose "
                                + "policy id is not derivable from its own fields describes a token that "
                                + "does not exist, and every later operation fails naming the token."));
            }
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
                        // Passed straight through, null included: null means "the caller did not
                        // say", which the registration cross-check treats as "no evidence, stay
                        // silent". Defaulting it to false here would invent a claim the SDK never
                        // made and reject correct CIP-68 registrations.
                        .cip68Enabled(request.cip68Enabled())
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

        log.info("Token {} registered successfully as {}", request.policyId(), request.moduleId());
        return ResponseEntity.ok().build();
    }
}
