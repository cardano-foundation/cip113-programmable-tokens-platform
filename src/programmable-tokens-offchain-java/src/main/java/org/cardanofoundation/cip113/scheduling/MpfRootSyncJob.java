package org.cardanofoundation.cip113.scheduling;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.cardanofoundation.cip113.repository.GlobalStateInitRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.MpfRootSyncTrigger;
import org.cardanofoundation.cip113.service.MpfTreeService;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
import org.cardanofoundation.cip113.service.substandard.KycExtendedSubstandardHandler;
import org.cardanofoundation.cip113.service.substandard.SubstandardHandlerFactory;
import org.cardanofoundation.cip113.service.substandard.context.KycExtendedContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically syncs the on-chain {@code member_root_hash} for every kyc-extended
 * registered token. Each tick: prune expired leaves → equality-gate → publish iff
 * local root differs from on-chain root.
 *
 * Fees are paid only when the local trie has actually diverged. A no-op tick
 * (root unchanged) returns before the handler is invoked.
 *
 * @see MpfRootSyncTrigger for the post-insert debounced nudge that complements this.
 */
@Component
@ConditionalOnProperty(name = "kycExtended.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class MpfRootSyncJob {

    private final KycExtendedTokenRegistrationRepository registrationRepo;
    private final GlobalStateInitRepository globalStateInitRepository;
    private final MpfTreeService mpfTreeService;
    private final SubstandardHandlerFactory handlerFactory;
    private final ProtocolBootstrapService protocolBootstrapService;
    private final AdminSigningKeyProvider adminSigner;
    private final BFBackendService bfBackendService;

    /** Warn-once set: policies where the global-state UTxO is missing on-chain.
     *  Cleared when publish succeeds. */
    private final Set<String> globalStateMissingPolicies =
            Collections.synchronizedSet(new HashSet<>());

    /** Per-policy cooldown to prevent concurrent trigger fires from racing on the same fee UTxO. */
    private final ConcurrentHashMap<String, Instant> lastSubmitAttemptAt = new ConcurrentHashMap<>();
    private static final Duration SUBMIT_COOLDOWN = Duration.ofSeconds(60);

    @Scheduled(fixedDelayString = "${kycExtended.rootHashUpdateIntervalSeconds:10}000")
    public void syncRoots() {
        for (var reg : registrationRepo.findAll()) {
            try {
                syncOne(reg.getProgrammableTokenPolicyId());
            } catch (Exception e) {
                log.warn("syncOne failed for policy {}: {}", reg.getProgrammableTokenPolicyId(), e.getMessage());
            }
        }
    }

    /** Sync entry point. Safe to call from the scheduled tick or from
     *  {@link MpfRootSyncTrigger} after a debounce window. */
    public void syncOne(String policyId) {
        var regOpt = registrationRepo.findByProgrammableTokenPolicyId(policyId);
        if (regOpt.isEmpty()) return;
        var reg = regOpt.get();

        try {
            var prune = mpfTreeService.pruneExpired(policyId, System.currentTimeMillis());
            if (prune.removedCount() > 0) {
                log.info("Pruned {} expired leaves for policy {}", prune.removedCount(), policyId);
            }
        } catch (Exception e) {
            log.error("pruneExpired failed for policy {} — skipping publish this tick", policyId, e);
            return;
        }

        // Snapshot leaf set + root atomically: we'll later mark exactly these leaves as
        // published, so the proof-generation trie matches the on-chain root bit-for-bit.
        var snapshot = mpfTreeService.snapshotForPublish(policyId);
        byte[] local = snapshot.root();
        // Read actual on-chain root (Blockfrost-direct) rather than the DB-cached value
        // — the DB can lag behind the chain after a confirmation-poll timeout.
        byte[] onchain = readActualOnchainRoot(reg).orElseGet(() ->
                reg.getMemberRootHashOnchain() != null
                        ? HexUtil.decodeHexString(reg.getMemberRootHashOnchain())
                        : new byte[32]);
        if (Arrays.equals(local, onchain)) {
            String onchainHex = HexUtil.encodeHexString(onchain);
            if (!onchainHex.equals(reg.getMemberRootHashOnchain())) {
                reg.setMemberRootHashOnchain(onchainHex);
                registrationRepo.save(reg);
                mpfTreeService.markLeavesPublished(policyId, Instant.now());
            }
            persistLocalRoot(reg, local);
            return;
        }

        persistLocalRoot(reg, local);

        if (!adminSigner.isAvailable()) {
            log.warn("syncOne({}): root differs but admin signing key is not configured — cannot publish", policyId);
            return;
        }

        var lastAttempt = lastSubmitAttemptAt.get(policyId);
        if (lastAttempt != null && Duration.between(lastAttempt, Instant.now()).compareTo(SUBMIT_COOLDOWN) < 0) {
            return;
        }
        lastSubmitAttemptAt.put(policyId, Instant.now());

        try {
            publishRootUpdate(reg, local, snapshot.leafIds());
        } catch (Exception e) {
            log.error("publishRootUpdate failed for policy {} — will retry next tick/trigger", policyId, e);
        }
    }

    private void publishRootUpdate(KycExtendedTokenRegistrationEntity reg, byte[] newLocalRoot,
                                   java.util.Set<Long> snapshotLeafIds) throws Exception {
        var policyId = reg.getProgrammableTokenPolicyId();
        var signerPkh = adminSigner.getAdminPkh();

        // The on-chain validator checks that issuerAdminPkh signs the tx — the backend
        // signing key must match what was registered, otherwise the tx will be rejected.
        if (!signerPkh.equalsIgnoreCase(reg.getIssuerAdminPkh())) {
            log.warn("publishRootUpdate({}): adminSigner PKH {} does not match stored issuerAdminPkh {} — " +
                    "on-chain validator will reject. Re-register the token with issuerAdminPkh = {}.",
                    policyId, signerPkh, reg.getIssuerAdminPkh(), signerPkh);
        }

        var protocolParams = protocolBootstrapService.getProtocolBootstrapParams();
        if (protocolParams == null) {
            throw new IllegalStateException("Protocol bootstrap params not loaded");
        }

        var initOpt = globalStateInitRepository.findByGlobalStatePolicyId(reg.getTelPolicyId());
        if (initOpt.isEmpty()) {
            log.warn("publishRootUpdate({}): no GlobalStateInitEntity for tel_policy_id {} — cannot rebuild scripts, skipping",
                    policyId, reg.getTelPolicyId());
            return;
        }
        var init = initOpt.get();
        var ctx = KycExtendedContext.builder()
                .issuerAdminPkh(reg.getIssuerAdminPkh())
                .globalStatePolicyId(reg.getTelPolicyId())
                .globalStateInitTxInput(TransactionInput.builder()
                        .transactionId(init.getTxHash())
                        .index(init.getOutputIndex())
                        .build())
                .memberRootHashOnchain(reg.getMemberRootHashOnchain())
                .memberRootHashLocal(reg.getMemberRootHashLocal())
                .build();

        var handler = (KycExtendedSubstandardHandler) handlerFactory.getHandler("kyc-extended", ctx);
        var txContext = handler.buildUpdateMemberRootHashTransaction(
                policyId, newLocalRoot, adminSigner.getAdminAddress(), signerPkh, protocolParams);
        if (!txContext.isSuccessful()) {
            String error = txContext.error() != null ? txContext.error() : "";
            if (error.contains("Global state UTxO not found")) {
                if (globalStateMissingPolicies.add(policyId)) {
                    log.warn("publishRootUpdate({}): global-state UTxO not found on-chain. " +
                            "Re-run the global-state init step from the frontend.", policyId);
                }
            } else {
                log.warn("publishRootUpdate({}): handler returned error: {}", policyId, error);
            }
            return;
        }
        var unsigned = Transaction.deserialize(HexUtil.decodeHexString(txContext.unsignedCborTx()));
        var signed = adminSigner.sign(unsigned);
        var signedBytes = signed.serialize();
        var submit = bfBackendService.getTransactionService().submitTransaction(signedBytes);
        if (submit == null || !submit.isSuccessful()) {
            log.warn("publishRootUpdate({}): submit failed: {}", policyId,
                    submit != null ? submit.getResponse() : "null");
            return;
        }
        var txHash = TransactionUtil.getTxHash(signedBytes);
        log.info("Submitted UpdateMemberRootHash tx {} for policy {}, new root {} — awaiting confirmation",
                txHash, policyId, HexUtil.encodeHexString(newLocalRoot));

        // Wait for the global state UTxO to actually carry the new root before marking leaves
        // — until the tx is included in a block, proofs against the new root will be rejected.
        boolean confirmed = waitForOnchainRoot(reg, newLocalRoot, java.time.Duration.ofMinutes(2));
        if (!confirmed) {
            log.warn("publishRootUpdate({}): tx {} not confirmed before timeout — will re-check next tick.",
                    policyId, txHash);
            return;
        }

        globalStateMissingPolicies.remove(policyId);
        lastSubmitAttemptAt.remove(policyId);
        log.info("Confirmed UpdateMemberRootHash tx {} for policy {}", txHash, policyId);
        reg.setMemberRootHashOnchain(HexUtil.encodeHexString(newLocalRoot));
        reg.setMemberRootHashLocal(HexUtil.encodeHexString(newLocalRoot));
        reg.setLastRootUpdateTxHash(txHash);
        reg.setLastRootUpdateAt(Instant.now());
        registrationRepo.save(reg);

        // Mark exactly the snapshot's leaves so the proof-generation trie matches the on-chain root.
        mpfTreeService.markLeavesPublishedById(snapshotLeafIds);
    }

    private java.util.Optional<byte[]> readActualOnchainRoot(KycExtendedTokenRegistrationEntity reg) {
        try {
            var ctx = KycExtendedContext.builder()
                    .issuerAdminPkh(reg.getIssuerAdminPkh())
                    .globalStatePolicyId(reg.getTelPolicyId())
                    .build();
            var handler = (KycExtendedSubstandardHandler) handlerFactory.getHandler("kyc-extended", ctx);
            return handler.readGlobalState(reg.getProgrammableTokenPolicyId())
                    .map(gs -> gs.memberRootHash() != null
                            ? HexUtil.decodeHexString(gs.memberRootHash())
                            : new byte[32]);
        } catch (Exception e) {
            log.debug("readActualOnchainRoot({}) failed: {}", reg.getProgrammableTokenPolicyId(), e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /** Poll until the on-chain global state UTxO datum carries {@code expectedRoot},
     *  up to {@code timeout}. Returns true on success, false on timeout. */
    private boolean waitForOnchainRoot(KycExtendedTokenRegistrationEntity reg, byte[] expectedRoot, java.time.Duration timeout) {
        var policyId = reg.getProgrammableTokenPolicyId();
        var deadline = Instant.now().plus(timeout);
        var pollInterval = java.time.Duration.ofSeconds(10);
        String expectedHex = HexUtil.encodeHexString(expectedRoot);

        var ctx = KycExtendedContext.builder()
                .issuerAdminPkh(reg.getIssuerAdminPkh())
                .globalStatePolicyId(reg.getTelPolicyId())
                .build();
        var handler = (KycExtendedSubstandardHandler) handlerFactory.getHandler("kyc-extended", ctx);

        while (Instant.now().isBefore(deadline)) {
            try {
                var gs = handler.readGlobalState(policyId);
                if (gs.isPresent() && expectedHex.equalsIgnoreCase(gs.get().memberRootHash())) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("waitForOnchainRoot({}): read failed (will retry): {}", policyId, e.getMessage());
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void persistLocalRoot(KycExtendedTokenRegistrationEntity reg, byte[] local) {
        String localHex = HexUtil.encodeHexString(local);
        if (!localHex.equals(reg.getMemberRootHashLocal())) {
            reg.setMemberRootHashLocal(localHex);
            registrationRepo.save(reg);
        }
    }
}
