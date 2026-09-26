package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import java.util.ArrayList;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity;
import org.cardanofoundation.cip113.entity.RwaTokenPowerUserEntity;
import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.cardanofoundation.cip113.repository.RwaGenesisFundingReservationRepository;
import org.cardanofoundation.cip113.repository.RwaGenesisReservationRepository;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.repository.RwaTokenMemberRootSnapshotRepository;
import org.cardanofoundation.cip113.repository.RwaTokenPowerUserRepository;
import org.cardanofoundation.cip113.service.module.Cip170MintChildBuilder;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.context.RwaTokenContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Initial-chain publication and all genesis side effects share this database transaction. */
@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InitialMintAttestationStore {
    private final MintAttestationIntentRepository intents;
    private final RwaGenesisReservationRepository genesisReservations;
    private final RwaGenesisFundingReservationRepository fundingReservations;
    private final RwaTokenRegistrationRepository registrations;
    private final RwaTokenMemberRootSnapshotRepository snapshots;
    private final RwaTokenPowerUserRepository powerUsers;
    private final ProgrammableTokenRegistryRepository tokenRegistry;
    private final RwaTokenCreationService creation;
    private final ModuleHandlerFactory handlers;
    private final Cip170MintChildBuilder childBuilder;
    private final PlatformTransactionManager transactionManager;
    private final MintAttestationService mints;
    private final ProtocolDeploymentResolver protocols;
    private final ObjectMapper mapper;
    private final BackendService bfBackendService;

    public record Claim(String owner, RwaTokenModuleHandler.ChainBuildResult chain) {}
    public record Preview(RwaTokenModuleHandler.ChainBuildResult prefix, String snapshotJson) {}
    private record StagedRows(RwaTokenRegistrationEntity registration,
                              RwaTokenMemberRootSnapshotEntity memberSnapshot,
                              RwaTokenPowerUserEntity bootstrapPowerUser,
                              ProgrammableTokenRegistryEntity tokenRegistry) {}

    /** Build a deterministic registration prefix in a transaction that cannot commit.
     * Only detached row values leave the preview; no canonical registration rows do. */
    public Preview preview(RwaTokenRegisterRequest request, ProtocolBootstrapParams params,
            RwaTokenModuleHandler.GenesisPlan plan, Instant expiresAt) {
        var tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setTimeout(120);
        return tx.execute(status -> {
            try {
                // buildPrepared verifies ownership of every pinned input. Claim
                // them inside this rollback-only transaction so its checks see
                // the same plan that will be claimed durably by prepare().
                reserve(plan);
                var prefix = creation.buildPrepared(request, params, plan, null, expiresAt);
                String policy = plan.programmableTokenPolicyId();
                var registration = registrations.findByProgrammableTokenPolicyId(policy)
                        .orElseThrow(() -> new IllegalStateException("Preview produced no registration row"));
                var memberSnapshot = snapshots.findByProgrammableTokenPolicyIdAndRootHash(
                        policy, registration.getMemberRootHashLocal())
                        .orElseThrow(() -> new IllegalStateException("Preview produced no member snapshot"));
                var users = powerUsers.findByProgrammableTokenPolicyId(policy);
                if (users.size() != 1) throw new IllegalStateException("Preview must produce one bootstrap power user");
                var registry = tokenRegistry.findByPolicyId(policy)
                        .orElseThrow(() -> new IllegalStateException("Preview produced no token registry row"));
                var staged = new StagedRows(registration, memberSnapshot, users.getFirst(), registry);
                // A generated ID from a rolled-back insertion cannot be reused at publication.
                String json = mapper.writeValueAsString(staged);
                status.setRollbackOnly();
                return new Preview(prefix, json);
            } catch (Exception e) {
                status.setRollbackOnly();
                throw new RwaTokenCreationService.BuildFailed("Initial mint preview failed: " + e.getMessage());
            }
        });
    }

    public java.util.Optional<MintAttestationIntentEntity> find(String id) { return intents.findById(id).map(InitialMintAttestationStore::initial); }
    public MintAttestationIntentEntity get(String id) { return initial(intents.findById(id).orElseThrow(InitialMintAttestationStore::notFound)); }
    private MintAttestationIntentEntity locked(String id) { return initial(intents.findLockedById(id).orElseThrow(InitialMintAttestationStore::notFound)); }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Initial mint intent not found"); }
    private static MintAttestationIntentEntity initial(MintAttestationIntentEntity i) {
        if (i.getInitialRegistrationJson() == null || i.getInitialPlanJson() == null) throw notFound();
        return i;
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    @Transactional(rollbackFor = Exception.class)
    public MintAttestationIntentEntity prepare(MintAttestationIntentEntity intent, RwaTokenModuleHandler.GenesisPlan plan) {
        reserve(plan);
        return intents.saveAndFlush(intent);
    }

    private void reserve(RwaTokenModuleHandler.GenesisPlan plan) {
        var bootstrap = plan.funding().getFirst();
        if (registrations.existsByProgrammableTokenPolicyId(plan.programmableTokenPolicyId())
                || registrations.existsByBootstrapTxHashAndBootstrapOutputIndex(bootstrap.getTxHash(), bootstrap.getOutputIndex())
                || genesisReservations.claim(plan.globalStatePolicyId(), bootstrap.getTxHash(), bootstrap.getOutputIndex()) != 1)
            throw conflict("Bootstrap is reserved; prepare again using another available wallet input");
        for (var funding : plan.funding()) {
            if (fundingReservations.claim(funding.getTxHash().toLowerCase(java.util.Locale.ROOT) + "#" + funding.getOutputIndex(), plan.globalStatePolicyId()) != 1)
                throw conflict("Funding is reserved; prepare again using another available wallet input");
        }
    }

    @Transactional
    public Claim claimBuild(String id) throws Exception {
        var i = locked(id);
        if ("BUILT".equals(i.getStatus())) return new Claim(null, chain(i));
        MintAttestationStore.unexpired(i);
        if (!Set.of("ANCHORED", "BUILDING").contains(i.getStatus())) throw conflict("Initial mint has no verified KERI approval");
        if (liveClaim(i)) throw conflict("Creation attempt is already processing");
        String owner = UUID.randomUUID().toString();
        i.setClaimOwner(owner); i.setLeaseUntil(Instant.now().plusSeconds(240)); i.setStatus("BUILDING");
        return new Claim(owner, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public RwaTokenModuleHandler.ChainBuildResult build(String id, String owner) throws Exception {
        // This lock also excludes cancellation. An expired builder cannot republish after a newer claim.
        var i = locked(id); requireOwner(i, owner); MintAttestationStore.unexpired(i);
        var plan = mapper.readValue(i.getInitialPlanJson(), RwaTokenModuleHandler.GenesisPlan.class);
        var request = mapper.readValue(i.getInitialRegistrationJson(), RwaTokenRegisterRequest.class);
        var fields = mints.fields(i);
        var params = protocols.resolve(fields.protocolTxHash());
        if (params == null || !params.txHash().equals(fields.protocolTxHash())
                || !plan.programmableTokenPolicyId().equals(fields.tokenPolicyId()))
            throw new IllegalStateException("Prepared deployment or policy differs from initial mint approval");
        if (i.getInitialPrefixJson() == null || i.getInitialSnapshotJson() == null)
            throw new IllegalStateException("Initial mint has no frozen preview");
        var prefix = mapper.readValue(i.getInitialPrefixJson(), RwaTokenModuleHandler.ChainBuildResult.class);
        if (!prefix.programmableTokenPolicyId().equals(plan.programmableTokenPolicyId())
                || !prefix.globalStatePolicyId().equals(plan.globalStatePolicyId()))
            throw new IllegalStateException("Frozen prefix differs from reserved policy");
        var staged = mapper.readValue(i.getInitialSnapshotJson(), StagedRows.class);
        materialize(prefix, staged, request, plan, fields);
        var handler = (RwaTokenModuleHandler) handlers.getHandler("rwa-token", RwaTokenContext.emptyContext());
        var result = handler.completeInitialMintChain(prefix, request.getFeePayerAddress(),
                params, mints.attestation(i), childBuilder);
        // Validate against the original request and the exact frozen registration.
        var frozen = mapper.readValue(i.getInitialRegistrationJson(), RwaTokenRegisterRequest.class);
        InitialMintTransactionValidator.validate(result, fields, frozen, plan, params, mints.attestation(i));
        requireOwner(i, owner); MintAttestationStore.unexpired(i);
        i.setInitialChainJson(mapper.writeValueAsString(result));
        i.setUnsignedCbor(result.registrationCborHex()); i.setTransactionHash(result.registrationTxHash());
        i.setStatus("BUILT"); i.setClaimOwner(null); i.setLeaseUntil(null);
        intents.saveAndFlush(i);
        return result;
    }

    private void materialize(RwaTokenModuleHandler.ChainBuildResult prefix, StagedRows staged,
            RwaTokenRegisterRequest request, RwaTokenModuleHandler.GenesisPlan plan,
            org.cardanofoundation.cip113.model.MintAttestationRequest fields) {
        String policy = prefix.programmableTokenPolicyId();
        if (staged == null || staged.registration() == null || staged.memberSnapshot() == null
                || staged.bootstrapPowerUser() == null || staged.tokenRegistry() == null
                || !policy.equals(staged.registration().getProgrammableTokenPolicyId())
                || !policy.equals(staged.memberSnapshot().getProgrammableTokenPolicyId())
                || !policy.equals(staged.bootstrapPowerUser().getProgrammableTokenPolicyId())
                || !policy.equals(staged.tokenRegistry().getPolicyId())
                || !prefix.globalStatePolicyId().equals(staged.registration().getGlobalStatePolicyId())
                || !prefix.genesisTxHash().equals(staged.memberSnapshot().getTxHash())
                || !Objects.equals(staged.registration().getIssuerAdminPkh(), request.getAdminPubKeyHash())
                || !Objects.equals(staged.registration().getBootstrapTxHash(), plan.funding().getFirst().getTxHash())
                || !Objects.equals(staged.registration().getBootstrapOutputIndex(), plan.funding().getFirst().getOutputIndex())
                || !Objects.equals(staged.registration().getSecurityAssetNameHex(), fields.assetName())
                || !Objects.equals(staged.tokenRegistry().getAssetName(), fields.assetName())
                || !Objects.equals(staged.memberSnapshot().getRootHash(), staged.registration().getMemberRootHashOnchain())
                || !Objects.equals(staged.memberSnapshot().getRootHash(), staged.registration().getMemberRootHashLocal()))
            throw new IllegalStateException("Staged creation rows do not match the frozen chain");
        if (registrations.existsByProgrammableTokenPolicyId(policy)
                || tokenRegistry.existsByPolicyId(policy)
                || !powerUsers.findByProgrammableTokenPolicyId(policy).isEmpty()
                || snapshots.findByProgrammableTokenPolicyIdAndRootHash(policy,
                        staged.memberSnapshot().getRootHash()).isPresent())
            throw conflict("Creation rows already exist; recover the prior attempt");
        staged.memberSnapshot().setId(null);
        staged.bootstrapPowerUser().setId(null);
        registrations.saveAndFlush(staged.registration());
        snapshots.saveAndFlush(staged.memberSnapshot());
        powerUsers.saveAndFlush(staged.bootstrapPowerUser());
        tokenRegistry.saveAndFlush(staged.tokenRegistry());
    }

    public RwaTokenModuleHandler.ChainBuildResult chain(MintAttestationIntentEntity i) throws Exception {
        if (!Set.of("BUILT", "ARCHIVED_EXPIRED").contains(i.getStatus()) || i.getInitialChainJson() == null) throw conflict("Creation chain has not been published");
        return mapper.readValue(i.getInitialChainJson(), RwaTokenModuleHandler.ChainBuildResult.class);
    }

    public record Recovery(String status, boolean canStartNewPolicy, String tipHash, Long tipSlot, String reason) {}
    private record ChainPoint(String hash, long slot) {}

    /** Read-only observation. UNKNOWN never authorizes discarding/replacing an attempt. */
    public Recovery recovery(MintAttestationIntentEntity intent) {
        if ("ARCHIVED_EXPIRED".equals(intent.getStatus()))
            return new Recovery("ARCHIVED_EXPIRED", true, null, null,
                    "Expired unstarted attempt archived; its policy and funding reservations are retained");
        if (!"BUILT".equals(intent.getStatus()))
            return new Recovery("NOT_BUILT", false, null, null, "No published registration chain");
        try {
            var saved = chain(intent);
            var mint = Transaction.deserialize(HexUtil.decodeHexString(saved.registrationCborHex()));
            long ttl = mint.getBody().getTtl();
            if (ttl <= 0 || !TransactionUtil.getTxHash(mint.serialize()).equals(saved.registrationTxHash()))
                return unknown("Frozen registration hash or validity bound is unavailable");
            var plan = mapper.readValue(intent.getInitialPlanJson(), RwaTokenModuleHandler.GenesisPlan.class);
            var bootstrap = plan.funding().getFirst();
            var genesis = Transaction.deserialize(HexUtil.decodeHexString(saved.genesisCborHex()));
            if (!TransactionUtil.getTxHash(genesis.serialize()).equals(saved.genesisTxHash()))
                return unknown("Frozen genesis hash is unavailable");
            if (genesis.getBody().getInputs().stream().noneMatch(input ->
                    input.getTransactionId().equalsIgnoreCase(bootstrap.getTxHash())
                            && input.getIndex() == bootstrap.getOutputIndex()))
                return unknown("Saved bootstrap is not a genesis input");
            long genesisTtl = genesis.getBody().getTtl();
            if (genesisTtl <= 0)
                return unknown("Frozen genesis has no finite validity bound; it may still spend its bootstrap");
            var before = chainPoint();
            var hashes = new ArrayList<String>();
            hashes.add(saved.genesisTxHash()); hashes.add(saved.addPowerUserTxHash());
            hashes.add(saved.cmtaProvenanceTxHash()); hashes.add(saved.issuanceProvenanceTxHash());
            if (saved.publishScriptsTxHash() != null) hashes.add(saved.publishScriptsTxHash());
            hashes.add(saved.registrationTxHash());
            if (saved.attestationTxHash() != null) hashes.add(saved.attestationTxHash());
            if (saved.registerTransferLogicTxHash() != null) hashes.add(saved.registerTransferLogicTxHash());
            if (saved.registerThirdPartyTransferLogicTxHash() != null) hashes.add(saved.registerThirdPartyTransferLogicTxHash());
            int confirmed = 0;
            for (String hash : hashes) {
                if (hash == null || !hash.matches("[a-fA-F0-9]{64}")) return unknown("Saved transaction hash is unavailable");
                var response = bfBackendService.getTransactionService().getTransaction(hash);
                if (response == null) return unknown("Transaction lookup failed");
                if (response.code() == 404) continue;
                if (!response.isSuccessful() || response.getValue() == null) return unknown("Transaction lookup failed");
                var tx = response.getValue();
                if (!hash.equalsIgnoreCase(tx.getHash()) || tx.getBlock() == null || tx.getBlock().isBlank()
                        || !Boolean.TRUE.equals(tx.getValidContract()))
                    return unknown("Transaction inclusion or successful execution could not be established");
                confirmed++;
            }
            var fields = mapper.readValue(intent.getFieldsJson(), MintAttestationRequest.class);
            // Query the same backend as the block/transaction reads, bypassing the local index.
            // Require a complete paginated result; an absent/failed page is never evidence of safety.
            boolean bootstrapUnspent = false;
            boolean complete = false;
            for (int page = 1; page <= 100; page++) {
                var response = bfBackendService.getUtxoService().getUtxos(fields.feePayerAddress(), 100, page);
                if (response == null || !response.isSuccessful() || response.getValue() == null)
                    return unknown("Current funding UTxO lookup failed");
                for (var utxo : response.getValue()) {
                    if (bootstrap.getTxHash().equalsIgnoreCase(utxo.getTxHash())
                            && bootstrap.getOutputIndex() == utxo.getOutputIndex()) bootstrapUnspent = true;
                }
                if (response.getValue().size() < 100) { complete = true; break; }
            }
            if (!complete) return unknown("Funding UTxO lookup was incomplete");
            var after = chainPoint();
            if (!before.equals(after)) return unknown("Chain point changed during reconciliation; retry");
            if (confirmed > 0 && bootstrapUnspent)
                return unknown("Chain data contradicts itself: a saved transaction is confirmed but its bootstrap is unspent");
            if (confirmed > 0)
                return new Recovery(confirmed == hashes.size() ? "CONFIRMED" : "PARTIAL", false,
                        after.hash(), after.slot(), "Resume the saved chain; a fresh policy is not authorized by this recovery check");
            if (!bootstrapUnspent) return unknown("Bootstrap is spent or unavailable; reconcile the saved chain");
            if (after.slot() <= Math.max(ttl, genesisTtl))
                return new Recovery("UNCONFIRMED", false, after.hash(), after.slot(), "Frozen genesis or mint transaction is still valid");
            return new Recovery("EXPIRED_UNSTARTED", true, after.hash(), after.slot(),
                    "Genesis and mint validity have ended and the original bootstrap remains unspent; a different policy can be prepared");
        } catch (Exception unavailable) {
            return unknown("Chain reconciliation is unavailable; retain the saved chain and retry");
        }
    }

    /** Serialize the decision with build/cancel. Published bytes and all reservations remain immutable. */
    @Transactional(rollbackFor = Exception.class)
    public Recovery archiveExpired(String id) {
        var intent = locked(id);
        if ("ARCHIVED_EXPIRED".equals(intent.getStatus())) return recovery(intent);
        var observed = recovery(intent);
        if (!"EXPIRED_UNSTARTED".equals(observed.status()) || !observed.canStartNewPolicy())
            throw conflict("Cannot archive this creation attempt: " + observed.reason());
        intent.setStatus("ARCHIVED_EXPIRED");
        return new Recovery("ARCHIVED_EXPIRED", true, observed.tipHash(), observed.tipSlot(),
                "Expired unstarted attempt archived; use a new request ID and different wallet funding inputs");
    }

    private ChainPoint chainPoint() throws Exception {
        var response = bfBackendService.getBlockService().getLatestBlock();
        if (response == null || !response.isSuccessful() || response.getValue() == null)
            throw new IllegalStateException("Current chain point unavailable");
        var block = response.getValue();
        String hash = block.getHash(); long slot = block.getSlot();
        if (hash == null || hash.isBlank() || slot < 0)
            throw new IllegalStateException("Current chain point unavailable");
        return new ChainPoint(hash, slot);
    }
    private static Recovery unknown(String reason) { return new Recovery("UNKNOWN", false, null, null, reason); }

    @Transactional(rollbackFor = Exception.class)
    public MintAttestationIntentEntity release(String id) throws Exception {
        return releaseLocked(locked(id));
    }

    /** Expiry is checked again after taking the intent row lock. */
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseExpired(String id, Instant now) throws Exception {
        var i = locked(id);
        if (!i.getExpiresAt().isBefore(now) ||
                !Set.of("PREPARED", "ANCHORING", "ANCHORED", "BUILDING").contains(i.getStatus()) ||
                i.getInitialChainJson() != null || i.getUnsignedCbor() != null || liveClaim(i))
            return false;
        releaseLocked(i);
        return true;
    }

    private MintAttestationIntentEntity releaseLocked(MintAttestationIntentEntity i) throws Exception {
        if ("RELEASED".equals(i.getStatus())) return i;
        if ("BUILT".equals(i.getStatus()) || i.getInitialChainJson() != null || i.getUnsignedCbor() != null)
            throw conflict("A published creation chain cannot be cancelled; reconcile its transaction hashes");
        if (liveClaim(i)) throw conflict("Creation/signing is in progress; wait before cancelling");
        var plan = mapper.readValue(i.getInitialPlanJson(), RwaTokenModuleHandler.GenesisPlan.class);
        if (registrations.existsByProgrammableTokenPolicyId(plan.programmableTokenPolicyId()))
            throw conflict("Registration state exists; retain its reservations for reconciliation");
        var bootstrap = plan.funding().getFirst();
        for (var funding : plan.funding()) {
            String ref = funding.getTxHash().toLowerCase(java.util.Locale.ROOT) + "#" + funding.getOutputIndex();
            if (fundingReservations.releaseOwned(ref, plan.globalStatePolicyId()) != 1)
                throw conflict("Funding reservation ownership changed; no reservations released");
        }
        if (genesisReservations.releaseOwned(plan.globalStatePolicyId(), bootstrap.getTxHash(), bootstrap.getOutputIndex()) != 1)
            throw conflict("Bootstrap reservation ownership changed; no reservations released");
        i.setStatus("RELEASED"); i.setClaimOwner(null); i.setLeaseUntil(null);
        return i;
    }
    private static boolean liveClaim(MintAttestationIntentEntity i) {
        return i.getClaimOwner() != null && i.getLeaseUntil() != null && i.getLeaseUntil().isAfter(Instant.now());
    }
    private static void requireOwner(MintAttestationIntentEntity i, String owner) {
        if (!"BUILDING".equals(i.getStatus()) || !Objects.equals(owner, i.getClaimOwner()) || !liveClaim(i))
            throw conflict("Creation processing claim expired; saved state was not replaced");
    }
}
