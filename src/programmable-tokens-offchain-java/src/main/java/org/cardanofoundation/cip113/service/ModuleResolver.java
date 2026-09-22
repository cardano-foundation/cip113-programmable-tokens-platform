package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.cip171.UplcLinkClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Works out which module a registered token belongs to, from chain data alone.
 *
 * <h2>Why this can be derived rather than remembered</h2>
 *
 * A registry node records the token's transfer-logic script HASH, and a hash cannot be
 * un-applied. But every module's transfer logic is parameterised from a base this
 * backend already ships, over inputs that are themselves on chain:
 *
 * <ul>
 *   <li>{@code freeze-and-seize}, {@code kyc}, {@code kyc-extended} — parameterised by
 *       (programmable-logic-base hash, global-state policy). The first comes from the
 *       deployment record; the second is a field of the registry node datum.</li>
 *   <li>{@code dummy} — not parameterised at all. Its transfer validator is protocol-global,
 *       so the blueprint's own hash is the answer.</li>
 * </ul>
 *
 * So each candidate can be RECOMPUTED and compared against what the chain reported. Exactly
 * one should match, and the comparison is self-checking: a wrong assumption about the inputs
 * produces no match rather than a confident wrong answer.
 *
 * <h2>What is deliberately not covered</h2>
 *
 * {@code rwa-token}'s transfer logic takes four parameters including a {@code denylistScriptHash}
 * that no registry node carries, so it cannot be recomputed from chain data. It is left out
 * rather than guessed at; tokens of that module resolve to empty and are not indexed by
 * this path. They still arrive through the registration callback, which knows the answer
 * because it was there when the token was made.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleResolver {

    private final ModuleService moduleService;
    private final UplcLinkClient uplcLinkClient;
    private final FreezeAndSeizeScriptBuilderService freezeAndSeizeScriptBuilder;
    private final KycScriptBuilderService kycScriptBuilder;
    private final KycExtendedScriptBuilderService kycExtendedScriptBuilder;

    /** Modules whose transfer logic is (plbHash, globalStatePolicyId). */
    private Map<String, BiFunction<String, String, PlutusScript>> parameterisedCandidates() {
        var map = new LinkedHashMap<String, BiFunction<String, String, PlutusScript>>();
        map.put("freeze-and-seize", freezeAndSeizeScriptBuilder::buildTransferScript);
        map.put("kyc", kycScriptBuilder::buildTransferScript);
        map.put("kyc-extended", kycExtendedScriptBuilder::buildTransferScript);
        return map;
    }

    /**
     * @param progLogicBaseScriptHash   the deployment's programmable-logic-base hash
     * @param globalStatePolicyId       the registry node's global-state policy ({@code ""} when none)
     * @param observedTransferLogicHash the transfer-logic hash the registry node actually carries
     * @return the module id, or empty when nothing reproduces the observed hash
     */
    public Optional<String> resolve(String progLogicBaseScriptHash,
                                    String globalStatePolicyId,
                                    String observedTransferLogicHash) {
        return resolve(progLogicBaseScriptHash, globalStatePolicyId, observedTransferLogicHash,
                List.of());
    }

    /**
     * @param candidatePolicyIds policy ids seen elsewhere in the registering transaction, used
     *                           when the registry node carries no global state of its own.
     *                           freeze-and-seize is the case that needs this: it never writes
     *                           {@code global_state_cs}, so its blacklist policy — the second
     *                           parameter of its transfer logic — appears nowhere in the node.
     *                           Trying each observed policy is a bounded search whose result is
     *                           PROVEN rather than guessed: a candidate is accepted only when it
     *                           reproduces the exact hash the chain reported.
     */
    public Optional<String> resolve(String progLogicBaseScriptHash,
                                    String globalStatePolicyId,
                                    String observedTransferLogicHash,
                                    Collection<String> candidatePolicyIds) {

        if (observedTransferLogicHash == null || observedTransferLogicHash.isBlank()) {
            return Optional.empty();
        }

        // Every candidate is tried, including modules listed in `modules.disabled`.
        // That property governs which modules the issuance wizard OFFERS, not which ones
        // this deployment can recognise: ModuleService keeps disabled ones loaded and
        // resolvable precisely because already-issued tokens still need serving. Filtering here
        // would make an existing kyc token unindexable on a deployment that has stopped offering
        // kyc, which is the opposite of what the property means.

        // dummy first: unparameterised, so it is a straight blueprint lookup and cannot be
        // confused with a parameterised one.
        var dummyTransfer = moduleService
                .getModuleValidator("dummy", "transfer.transfer.withdraw");
        if (dummyTransfer.isPresent()
                && observedTransferLogicHash.equalsIgnoreCase(dummyTransfer.get().scriptHash())) {
            return Optional.of("dummy");
        }

        // The node's own global state first, when it has one (kyc, kyc-extended).
        if (globalStatePolicyId != null && !globalStatePolicyId.isBlank()) {
            var byOwnState = matchAgainst(progLogicBaseScriptHash, globalStatePolicyId,
                    observedTransferLogicHash);
            if (byOwnState.isPresent()) {
                return byOwnState;
            }
        }

        // Otherwise fall back to policies observed in the same transaction. This is what
        // recovers freeze-and-seize, whose blacklist policy the registry node never records.
        for (String candidate : candidatePolicyIds) {
            if (candidate == null || candidate.isBlank() || candidate.equals(globalStatePolicyId)) {
                continue;
            }
            var match = matchAgainst(progLogicBaseScriptHash, candidate, observedTransferLogicHash);
            if (match.isPresent()) {
                log.debug("resolved {} via transaction policy {}", match.get(), candidate);
                return match;
            }
        }

        // Last resort: ask uplc.link what this script was built from. Local derivation cannot
        // identify freeze-and-seize, whose transfer logic is parameterised on a blacklist policy
        // recorded neither in the registry node nor in the registering transaction. A published
        // CIP-171 record does carry it.
        return resolveViaCip171(observedTransferLogicHash);
    }

    /**
     * Map a CIP-171 record's {@code sourcePath} onto a module this backend actually ships.
     *
     * <p>The registry is permissionless: anyone may publish a record naming any source path. So
     * the answer is treated as a HINT that must land on a module already loaded here —
     * {@code src/modules/freeze-and-seize} resolves only because freeze-and-seize is one of
     * ours. An unrecognised path resolves to empty rather than inventing a module id from a
     * third party's string.
     */
    private Optional<String> resolveViaCip171(String observedTransferLogicHash) {
        return uplcLinkClient.byHash(observedTransferLogicHash)
                .map(record -> record.path("sourcePath").asText(""))
                .flatMap(ModuleResolver::moduleIdFromSourcePath)
                .filter(id -> moduleService.getModuleById(id).isPresent())
                .map(id -> {
                    log.info("resolved module {} for transfer logic {} via uplc.link CIP-171 record",
                            id, observedTransferLogicHash);
                    return id;
                });
    }

    /** {@code src/modules/freeze-and-seize} -> {@code freeze-and-seize}. */
    static Optional<String> moduleIdFromSourcePath(String sourcePath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            return Optional.empty();
        }
        var trimmed = sourcePath.endsWith("/")
                ? sourcePath.substring(0, sourcePath.length() - 1)
                : sourcePath;
        var slash = trimmed.lastIndexOf('/');
        var candidate = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return candidate.isBlank() ? Optional.empty() : Optional.of(candidate);
    }

    /** Try every parameterised module against one candidate second-parameter. */
    private Optional<String> matchAgainst(String progLogicBaseScriptHash,
                                          String secondParameter,
                                          String observedTransferLogicHash) {
        for (var candidate : parameterisedCandidates().entrySet()) {
            try {
                PlutusScript script = candidate.getValue()
                        .apply(progLogicBaseScriptHash, secondParameter);
                String derived = HexUtil.encodeHexString(script.getScriptHash());
                if (observedTransferLogicHash.equalsIgnoreCase(derived)) {
                    return Optional.of(candidate.getKey());
                }
            } catch (Exception e) {
                // A candidate that cannot be built is not a match; it must not stop the others.
                log.debug("module candidate {} could not be parameterised: {}",
                        candidate.getKey(), e.getMessage());
            }
        }
        return Optional.empty();
    }
}
