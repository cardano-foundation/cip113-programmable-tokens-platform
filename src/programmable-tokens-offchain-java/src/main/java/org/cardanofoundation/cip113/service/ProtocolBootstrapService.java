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

            // Store all bootstraps in map
            for (ProtocolBootstrapParams params : bootstrapsList) {
                requireV040Complete(params);
                bootstrapsByTxHash.put(params.txHash(), params);
                log.info("Loaded protocol bootstrap for txHash: {}", params.txHash());
            }

            // Set default protocol bootstrap params
            if (defaultTxHash != null && !defaultTxHash.isEmpty()) {
                protocolBootstrapParams = bootstrapsByTxHash.get(defaultTxHash);
                if (protocolBootstrapParams == null) {
                    log.warn("Default txHash {} not found in bootstraps, using first available", defaultTxHash);
                    protocolBootstrapParams = bootstrapsList.getFirst();
                } else {
                    log.info("Using default protocol bootstrap with txHash: {}", defaultTxHash);
                }
            } else {
                // No default specified, use first one
                protocolBootstrapParams = bootstrapsList.getFirst();
                log.info("No default txHash configured, using first bootstrap: {}", protocolBootstrapParams.txHash());
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
     * Fails fast, at load time, when a bootstrap entry is missing a component that v0.4.0
     * requires. Older bootstrap files (protocol-bootstraps-preview.json,
     * protocol-bootstraps-preprod.json) predate the v0.4.0 contract port and describe
     * deployments that no longer match the current contracts; Jackson happily fills their
     * absent fields with {@code null} and would otherwise hand out a record that NPEs the
     * first time something reads one of those limbs (e.g. inside
     * {@link ProtocolScriptBuilderService}). Refusing to load it here, with the exact missing
     * section named, turns that latent NPE into a loud, actionable startup failure instead.
     */
    private static void requireV040Complete(ProtocolBootstrapParams params) {
        var missing = new ArrayList<String>();

        // Components new in v0.4.0 — absent entirely (rather than merely renamed) in legacy
        // bootstrap files, so Jackson leaves them null.
        if (params.coordinationParams() == null) {
            missing.add("coordinationParams");
        }
        if (params.unfrackingParams() == null) {
            missing.add("unfrackingParams");
        }
        if (params.upgradeMultisigParams() == null) {
            missing.add("upgradeMultisigParams");
        }
        if (params.unfrackingRefInput() == null) {
            missing.add("unfrackingRefInput");
        }
        // programmableLogicBaseParams itself is present in legacy files too, but under the
        // pre-rename key programmableLogicGlobalScriptHash; protocolParamsPolicyId is the
        // v0.4.0 key and is silently null for those entries (see ProgrammableLogicBaseParams).
        if (params.programmableLogicBaseParams() == null
                || params.programmableLogicBaseParams().protocolParamsPolicyId() == null) {
            missing.add("programmableLogicBaseParams.protocolParamsPolicyId");
        }

        // Components that predate v0.4.0 and are present in every known bootstrap file today,
        // but are still required by ProtocolScriptBuilderService/callers — checked so a
        // hand-edited or truncated bootstrap file fails here too, not with an NPE downstream.
        if (params.protocolParams() == null) {
            missing.add("protocolParams");
        }
        if (params.programmableLogicGlobalPrams() == null) {
            missing.add("programmableLogicGlobalPrams");
        }
        if (params.issuanceParams() == null) {
            missing.add("issuanceParams");
        }
        if (params.directoryMintParams() == null) {
            missing.add("directoryMintParams");
        }
        if (params.directorySpendParams() == null) {
            missing.add("directorySpendParams");
        }
        if (params.programmableBaseRefInput() == null) {
            missing.add("programmableBaseRefInput");
        }
        if (params.programmableGlobalRefInput() == null) {
            missing.add("programmableGlobalRefInput");
        }
        if (params.txHash() == null) {
            missing.add("txHash");
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Protocol bootstrap entry txHash=" + params.txHash()
                            + " is missing v0.4.0 component(s): " + String.join(", ", missing)
                            + ". This bootstrap file predates the v0.4.0 contract port; "
                            + "regenerate it from a real v0.4.0 deployment before using it.");
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
