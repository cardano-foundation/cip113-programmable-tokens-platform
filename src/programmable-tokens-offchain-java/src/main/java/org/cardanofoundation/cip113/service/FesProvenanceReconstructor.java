package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.cip171.Cip171Parameters;
import org.cardanofoundation.cip113.cip171.UplcLinkClient;
import org.cardanofoundation.cip113.entity.BlacklistInitEntity;
import org.cardanofoundation.cip113.entity.FreezeAndSeizeTokenRegistrationEntity;
import org.cardanofoundation.cip113.repository.BlacklistInitRepository;
import org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Rebuilds a freeze-and-seize token's deployment record from its published CIP-171 provenance.
 *
 * <h2>The hole this fills</h2>
 *
 * {@code blacklist_init} and {@code freeze_and_seize_token_registration} are written ONLY by the
 * registration callback. Nothing derives them from chain, so a database reset loses them
 * permanently for every token registered beforehand — and the token then fails with
 * "could not find programmable token or blacklist init data" server-side, or with a
 * "does not match this FES instance" from the SDK client-side, neither of which names the
 * actual problem.
 *
 * <h2>Two lookups into one record</h2>
 *
 * The values are recoverable because FES parameterises its scripts on exactly them, and a
 * CIP-171 record publishes the parameters applied to each script:
 *
 * <ol>
 *   <li>by-hash on the token's transfer logic gives {@code blacklist_node_cs} — the blacklist
 *       policy.</li>
 *   <li>by-hash on THAT policy — it is {@code blacklist_mint}'s own hash — gives
 *       {@code utxo_ref} and {@code manager_pkh}, the bootstrap UTxO and the admin key.</li>
 * </ol>
 *
 * <p>Both hops resolve to the SAME record and the same transaction; the registry's by-hash
 * simply returns the matching script rather than the whole record. Measured on preview: a
 * lookup of the transfer hash and a lookup of the blacklist policy both return
 * {@code 2d62d6d7…}. That is also why the blacklist init transaction needs no CIP-171 record
 * of its own — the registration's record already covers {@code blacklist_mint}, because the
 * record is deliberately built after compliance init so those parameterisations are captured.
 *
 * <h2>Nothing is trusted</h2>
 *
 * The registry is permissionless, so neither hop is believed on its word. Each is checked by
 * recomputing a script from the recovered values and comparing against a hash the CHAIN
 * already told us:
 *
 * <ul>
 *   <li>the blacklist policy must reproduce the registry node's transfer-logic hash;</li>
 *   <li>the bootstrap UTxO and admin key must reproduce that same blacklist policy.</li>
 * </ul>
 *
 * A mismatch is refused and logged rather than persisted. So a wrong or hostile record cannot
 * write a row: it can only fail to write one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FesProvenanceReconstructor {

    private final UplcLinkClient uplcLinkClient;
    private final FreezeAndSeizeScriptBuilderService fesScriptBuilder;
    private final BlacklistInitRepository blacklistInitRepository;
    private final FreezeAndSeizeTokenRegistrationRepository tokenRegistrationRepository;

    /**
     * @param policyId            the programmable token's policy id
     * @param transferLogicScript the transfer-logic hash the registry node records
     * @param progLogicBaseHash   the deployment's programmable-logic-base hash
     * @return the persisted registration, or empty when nothing could be proven
     */
    @Transactional
    public Optional<FreezeAndSeizeTokenRegistrationEntity> reconstruct(String policyId,
                                                                       String transferLogicScript,
                                                                       String progLogicBaseHash) {
        if (policyId == null || transferLogicScript == null || transferLogicScript.isBlank()) {
            return Optional.empty();
        }

        var existing = tokenRegistrationRepository.findByProgrammableTokenPolicyId(policyId);
        if (existing.isPresent()) {
            return existing;
        }

        // ---- hop 1: the blacklist policy, proven against the chain's transfer-logic hash ----
        var hop1 = uplcLinkClient.byHash(transferLogicScript);
        if (hop1.isEmpty()) {
            log.info("No CIP-171 record for transfer logic {} of token {} — cannot rebuild its "
                    + "freeze-and-seize record. The registration published no provenance.",
                    transferLogicScript, policyId);
            return Optional.empty();
        }

        var blacklistPolicy = Cip171Parameters.bytes(hop1.get(), "blacklist_node_cs");
        if (blacklistPolicy.isEmpty()) {
            log.warn("CIP-171 record for {} carries no blacklist_node_cs parameter", transferLogicScript);
            return Optional.empty();
        }

        if (!reproducesTransferLogic(progLogicBaseHash, blacklistPolicy.get(), transferLogicScript)) {
            log.warn("REFUSING CIP-171 rebuild for token {}: blacklist policy {} does not reproduce "
                    + "the transfer logic {} the chain reports. The record does not describe this token.",
                    policyId, blacklistPolicy.get(), transferLogicScript);
            return Optional.empty();
        }

        // ---- hop 2: the bootstrap UTxO and admin key, proven against the blacklist policy ----
        var hop2 = uplcLinkClient.byHash(blacklistPolicy.get());
        if (hop2.isEmpty()) {
            log.info("No CIP-171 record for blacklist policy {} (token {})", blacklistPolicy.get(), policyId);
            return Optional.empty();
        }

        var bootstrapUtxo = Cip171Parameters.outputReference(hop2.get(), "utxo_ref");
        var adminPkh = Cip171Parameters.bytes(hop2.get(), "manager_pkh");
        if (bootstrapUtxo.isEmpty() || adminPkh.isEmpty()) {
            log.warn("CIP-171 record for blacklist {} lacks utxo_ref or manager_pkh", blacklistPolicy.get());
            return Optional.empty();
        }

        if (!reproducesBlacklistPolicy(bootstrapUtxo.get(), adminPkh.get(), blacklistPolicy.get())) {
            log.warn("REFUSING CIP-171 rebuild for token {}: utxo_ref {}#{} and manager_pkh {} do not "
                    + "reproduce blacklist policy {}.", policyId, bootstrapUtxo.get().getTransactionId(),
                    bootstrapUtxo.get().getIndex(), adminPkh.get(), blacklistPolicy.get());
            return Optional.empty();
        }

        // ---- both hops proven: persist ----
        var blacklistInit = blacklistInitRepository.findByBlacklistNodePolicyId(blacklistPolicy.get())
                .orElseGet(() -> blacklistInitRepository.save(BlacklistInitEntity.builder()
                        .blacklistNodePolicyId(blacklistPolicy.get())
                        .adminPkh(adminPkh.get())
                        .txHash(bootstrapUtxo.get().getTransactionId())
                        .outputIndex(bootstrapUtxo.get().getIndex())
                        // Not in the record and not derivable from it. Null means "not stated",
                        // which the registration cross-check already treats as "no evidence".
                        .cip68Enabled(null)
                        .build()));

        var registration = tokenRegistrationRepository.save(FreezeAndSeizeTokenRegistrationEntity.builder()
                .programmableTokenPolicyId(policyId)
                .issuerAdminPkh(adminPkh.get())
                .blacklistInit(blacklistInit)
                .build());

        log.info("Rebuilt freeze-and-seize record for token {} from CIP-171: blacklist={}, "
                        + "admin={}, bootstrapUtxo={}#{} — both hops verified against chain hashes.",
                policyId, blacklistPolicy.get(), adminPkh.get(),
                bootstrapUtxo.get().getTransactionId(), bootstrapUtxo.get().getIndex());

        return Optional.of(registration);
    }

    private boolean reproducesTransferLogic(String progLogicBaseHash, String blacklistPolicy, String expected) {
        try {
            var script = fesScriptBuilder.buildTransferScript(progLogicBaseHash, blacklistPolicy);
            return expected.equalsIgnoreCase(HexUtil.encodeHexString(script.getScriptHash()));
        } catch (Exception e) {
            log.debug("transfer-logic verification threw: {}", e.toString());
            return false;
        }
    }

    private boolean reproducesBlacklistPolicy(TransactionInput bootstrapUtxo, String adminPkh, String expected) {
        try {
            var script = fesScriptBuilder.buildBlacklistMintScript(bootstrapUtxo, adminPkh);
            return expected.equalsIgnoreCase(HexUtil.encodeHexString(script.getScriptHash()));
        } catch (Exception e) {
            log.debug("blacklist-policy verification threw: {}", e.toString());
            return false;
        }
    }
}
