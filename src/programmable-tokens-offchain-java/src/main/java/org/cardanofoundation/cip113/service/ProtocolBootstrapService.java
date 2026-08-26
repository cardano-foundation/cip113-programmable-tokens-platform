package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.model.blueprint.Plutus;
import org.cardanofoundation.cip113.model.blueprint.Validator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolBootstrapService {

    private final ObjectMapper objectMapper;

    private final AppConfig.Network network;

    @Value("${programmable.token.default.txHash:}")
    private String defaultTxHash;

    @Getter
    private Plutus plutus;

    @Getter
    private ProtocolBootstrapParams protocolBootstrapParams;

    // Map of txHash -> ProtocolBootstrapParams for all available versions
    private final Map<String, ProtocolBootstrapParams> bootstrapsByTxHash = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        log.info("defaultTxHash: {}", defaultTxHash);
        log.info("network: {}", network.getNetwork());

        try {

            var protocolBootstrapFilename = String.format("protocol-bootstraps-%s.json", network.getNetwork());
            log.info("protocolBootstrapFilename: {}", protocolBootstrapFilename);

            // Load array of protocol bootstrap configurations
            var bootstrapsList = objectMapper.readValue(
                    this.getClass().getClassLoader().getResourceAsStream(protocolBootstrapFilename),
                    new TypeReference<List<ProtocolBootstrapParams>>() {}
            );

            // A deployment record this build cannot use is SKIPPED with a warning, not treated as
            // a fatal file error. This file is an append-only history, and after a core upgrade
            // that moves programmable_logic_base's hash EVERY earlier entry in it is unusable by
            // definition — so failing the whole file would mean the only way to keep a record of
            // past deployments is to delete it. Being unable to transact on an old deployment is
            // a property of that deployment, not a corruption of the file.
            //
            // What is not tolerated is SELECTING one: the checks below fail hard on the record
            // actually chosen, so an unusable default is a startup failure rather than a
            // transaction the chain rejects much later.
            var rejected = new LinkedHashMap<String, String>();
            // Tracked explicitly rather than read back off bootstrapsByTxHash: that map is a
            // ConcurrentHashMap, whose iteration order is unspecified, so "the first usable one"
            // has to be remembered while walking the file rather than recovered from the map
            // afterwards. Picking an arbitrary deployment would be worse than picking none.
            ProtocolBootstrapParams firstUsable = null;
            for (ProtocolBootstrapParams params : bootstrapsList) {
                try {
                    requireCurrentSchema(params);
                } catch (IllegalStateException e) {
                    rejected.put(params.txHash(), e.getMessage());
                    log.warn("Skipping unusable protocol bootstrap {}: {}", params.txHash(), e.getMessage());
                    continue;
                }
                bootstrapsByTxHash.put(params.txHash(), params);
                if (firstUsable == null) {
                    firstUsable = params;
                }
                log.info("Loaded protocol bootstrap for txHash: {}", params.txHash());
            }

            if (bootstrapsByTxHash.isEmpty()) {
                throw new IllegalStateException(
                        protocolBootstrapFilename + " contains no deployment this build can use ("
                                + rejected.size() + " skipped). Deploy the protocol and record the "
                                + "result — see docs/DEVNET-GUIDE.md.\n  "
                                + String.join("\n  ", rejected.values()));
            }

            // Set default protocol bootstrap params
            if (defaultTxHash != null && !defaultTxHash.isEmpty()) {
                protocolBootstrapParams = bootstrapsByTxHash.get(defaultTxHash);
                if (protocolBootstrapParams == null) {
                    // "Not in the file" and "in the file but skipped" are different problems.
                    // Naming a deployment that was rejected and then quietly falling back to a
                    // different one is how an operator ends up transacting on a protocol they
                    // did not choose.
                    if (rejected.containsKey(defaultTxHash)) {
                        throw new IllegalStateException(
                                "programmable.token.default.txHash names " + defaultTxHash
                                        + ", which this build cannot use: " + rejected.get(defaultTxHash));
                    }
                    log.warn("Default txHash {} not found in bootstraps, using first available", defaultTxHash);
                    protocolBootstrapParams = firstUsable;
                } else {
                    log.info("Using default protocol bootstrap with txHash: {}", defaultTxHash);
                }
            } else {
                // No default specified: the first USABLE one, which is not necessarily the first
                // in the file once historical entries are being skipped.
                protocolBootstrapParams = bootstrapsByTxHash.values().iterator().next();
                log.info("No default txHash configured, using first usable bootstrap: {}",
                        protocolBootstrapParams.txHash());
            }

            // Load plutus contracts
            plutus = objectMapper.readValue(
                    this.getClass().getClassLoader().getResourceAsStream("plutus.json"),
                    Plutus.class
            );

            log.info("Successfully initialized ProtocolBootstrapService with {} bootstrap versions", bootstrapsByTxHash.size());
        } catch (IOException e) {
            log.error("could not load bootstrap or protocol blueprint", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Fails fast, at load time, on any deployment record this build cannot actually use.
     *
     * <p>Two distinct problems get the same treatment, because they have the same
     * consequence:
     *
     * <ul>
     *   <li><strong>Wrong schema.</strong> A record from before the validator split describes
     *       a protocol whose {@code programmable_logic_global} validator does not exist in the
     *       current blueprint, and whose {@code programmable_logic_base} hash is different —
     *       so every programmable address it names belongs to a protocol this build cannot
     *       transact on. There is no upgrade path: the in-place mechanism swaps delegates,
     *       not PLB. Such a deployment has to be redeployed.</li>
     *   <li><strong>Missing components.</strong> Jackson fills absent fields with
     *       {@code null}, and a record handed out with null limbs NPEs the first time a
     *       builder reads one — far from the cause, and only for whichever operation happened
     *       to need that limb.</li>
     * </ul>
     *
     * <p>Both are refused here, naming the record and the reason. The alternative — reading
     * leniently and filling in blanks — yields transactions that are well-formed, submitted,
     * and rejected on chain for reasons that point nowhere near a stale JSON file.
     */
    private static void requireCurrentSchema(ProtocolBootstrapParams params) {
        Integer version = params.schemaVersion();
        if (version == null || version != ProtocolBootstrapParams.CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException(
                    "Protocol bootstrap entry txHash=" + params.txHash() + " declares schemaVersion="
                            + (version == null ? "none (pre-versioning)" : version)
                            + ", but this build requires " + ProtocolBootstrapParams.CURRENT_SCHEMA_VERSION
                            + ". Records below version 2 describe a protocol whose coordinator validator "
                            + "(programmable_logic_global) no longer exists and whose programmable_logic_base "
                            + "hash has moved, so every programmable-token address in that deployment belongs "
                            + "to a protocol this build cannot transact on. There is no in-place migration: "
                            + "the upgrade mechanism swaps delegates, not PLB. Deploy the protocol afresh and "
                            + "record the result. See docs/DEVNET-GUIDE.md.");
        }

        var missing = new ArrayList<String>();
        if (params.protocolParams() == null) missing.add("protocolParams");
        if (params.coordinationParams() == null) missing.add("coordinationParams");
        if (params.transferParams() == null) missing.add("transferParams");
        if (params.thirdPartyParams() == null) missing.add("thirdPartyParams");
        if (params.unfrackingParams() == null) missing.add("unfrackingParams");
        if (params.upgradeMultisigParams() == null) missing.add("upgradeMultisigParams");
        if (params.programmableLogicBaseParams() == null
                || params.programmableLogicBaseParams().protocolParamsPolicyId() == null) {
            missing.add("programmableLogicBaseParams.protocolParamsPolicyId");
        }
        if (params.issuanceParams() == null) missing.add("issuanceParams");
        if (params.directoryMintParams() == null) missing.add("directoryMintParams");
        if (params.directorySpendParams() == null) missing.add("directorySpendParams");
        if (params.maxInlineDatumBytes() == null) missing.add("maxInlineDatumBytes");
        if (params.programmableBaseRefInput() == null) missing.add("programmableBaseRefInput");
        if (params.transferRefInput() == null) missing.add("transferRefInput");
        if (params.thirdPartyRefInput() == null) missing.add("thirdPartyRefInput");
        if (params.unfrackingRefInput() == null) missing.add("unfrackingRefInput");
        if (params.txHash() == null) missing.add("txHash");

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Protocol bootstrap entry txHash=" + params.txHash()
                            + " declares schemaVersion " + version + " but is missing: "
                            + String.join(", ", missing)
                            + ". Regenerate it from a real deployment rather than hand-editing it.");
        }
    }

    /**
     * Get protocol bootstrap params by transaction hash
     *
     * @param txHash the transaction hash
     * @return the protocol bootstrap params or empty if not found
     */
    public Optional<ProtocolBootstrapParams> getProtocolBootstrapParamsByTxHash(String txHash) {
        return Optional.ofNullable(bootstrapsByTxHash.get(txHash));
    }

    /**
     * Get all available protocol bootstrap configurations
     *
     * @return map of txHash to ProtocolBootstrapParams
     */
    public Map<String, ProtocolBootstrapParams> getAllBootstraps() {
        return Map.copyOf(bootstrapsByTxHash);
    }

    public Optional<String> getProtocolContract(String contractTitle) {
        return plutus.validators().stream()
                .filter(validator -> validator.title().equals(contractTitle))
                .findAny()
                .map(Validator::compiledCode);
    }

}
