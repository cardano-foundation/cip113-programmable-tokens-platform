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

            // Latest-only: every committed record must describe the exact contract surface
            // this build targets. Keeping unusable entries here turns configuration drift into
            // an ordering-dependent runtime choice, so stale records fail startup immediately.
            bootstrapsByTxHash.clear();
            ProtocolBootstrapParams firstUsable = null;
            for (ProtocolBootstrapParams params : bootstrapsList) {
                requireCurrentSchema(params);
                if (bootstrapsByTxHash.putIfAbsent(params.txHash(), params) != null) {
                    throw new IllegalStateException(
                            protocolBootstrapFilename + " contains duplicate txHash " + params.txHash());
                }
                if (firstUsable == null) firstUsable = params;
                log.info("Loaded protocol bootstrap for txHash: {}", params.txHash());
            }
            if (firstUsable == null) {
                throw new IllegalStateException(
                        protocolBootstrapFilename + " contains no alpha.4 deployment. Deploy and record "
                                + "the current protocol — see docs/DEVNET-GUIDE.md.");
            }

            // Set default protocol bootstrap params
            if (defaultTxHash != null && !defaultTxHash.isEmpty()) {
                protocolBootstrapParams = bootstrapsByTxHash.get(defaultTxHash);
                if (protocolBootstrapParams == null) {
                    throw new IllegalStateException(
                            "programmable.token.default.txHash names " + defaultTxHash
                                    + ", which is not an alpha.4 deployment in "
                                    + protocolBootstrapFilename);
                }
                log.info("Using default protocol bootstrap with txHash: {}", defaultTxHash);
            } else {
                protocolBootstrapParams = firstUsable;
                log.info("No default txHash configured, using bootstrap: {}",
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
     *   <li><strong>Wrong schema.</strong> A pre-alpha.4 record cannot describe the merged
     *       protocol-params/registry validators, the replaceable issuance logic, the dispatcher,
     *       or all seven reference-script inputs. Renaming old fields would produce hashes for a
     *       different protocol, so such a deployment has to be redeployed.</li>
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
                            + ". This development build supports alpha.4 only; deploy and record the current "
                            + "protocol rather than adapting an older record.");
        }

        var missing = new ArrayList<String>();
        if (params.protocolParams() == null || params.protocolParams().policyId() == null
                || params.protocolParams().utxo() == null) missing.add("protocolParams");
        if (params.programmableLogicBase() == null) missing.add("programmableLogicBase");
        if (params.transfer() == null) missing.add("transfer");
        if (params.thirdParty() == null) missing.add("thirdParty");
        if (params.unfracking() == null) missing.add("unfracking");
        if (params.programmableLogicGlobal() == null) missing.add("programmableLogicGlobal");
        if (params.upgradeMultisig() == null || params.upgradeMultisig().txInput() == null
                || params.upgradeMultisig().utxo() == null) missing.add("upgradeMultisig");
        if (params.upgradeAuthority() == null) missing.add("upgradeAuthority");
        if (params.issuanceLogic() == null) missing.add("issuanceLogic");
        if (params.issuance() == null) missing.add("issuance");
        if (params.registry() == null) missing.add("registry");
        if (params.maxInlineDatumBytes() == null) missing.add("maxInlineDatumBytes");
        if (params.programmableBaseRefInput() == null) missing.add("programmableBaseRefInput");
        if (params.programmableLogicGlobalRefInput() == null) missing.add("programmableLogicGlobalRefInput");
        if (params.transferRefInput() == null) missing.add("transferRefInput");
        if (params.thirdPartyRefInput() == null) missing.add("thirdPartyRefInput");
        if (params.unfrackingRefInput() == null) missing.add("unfrackingRefInput");
        if (params.issuanceLogicRefInput() == null) missing.add("issuanceLogicRefInput");
        if (params.upgradeMultisigRefInput() == null) missing.add("upgradeMultisigRefInput");
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
