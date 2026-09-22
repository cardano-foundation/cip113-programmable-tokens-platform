package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Module;
import org.cardanofoundation.cip113.model.ModuleValidator;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class ModuleService {

    private final ObjectMapper objectMapper;

    /**
     * Modules to hide from {@link #getAllModules()} — the list the issuance
     * wizard offers. Listed by folder id.
     *
     * <p>Disabled modules are still LOADED and still resolvable by
     * {@link #getModuleById(String)} and {@link #getModuleValidator(String, String)}.
     * That is deliberate: those lookups are the runtime path for tokens that were already
     * issued, used by the script builders and by scheduled jobs such as MpfRootSyncJob.
     * Dropping them from the cache made those fail with
     * "kyc-extended contract not found: global_state.global_state.mint".
     *
     * <p>To stop a module's background jobs as well, use its own switch (e.g.
     * KYC_EXTENDED_ENABLED=false) — this property governs issuance choices only.
     */
    @Value("${modules.disabled:}")
    private List<String> disabledModules = new ArrayList<>();

    // Thread-safe in-memory cache of all modules
    private final Map<String, Module> modulesCache = new ConcurrentHashMap<>();

    /**
     * Load all modules from resources/modules at startup
     */
    @PostConstruct
    public void init() {
        log.info("Loading modules from resources/modules...");

        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:modules/*/plutus.json");

            log.info("Found {} plutus.json files in modules directory", resources.length);

            for (Resource resource : resources) {
                try {
                    // Extract folder name as ID from the resource path
                    // Path format: ...modules/{foldername}/plutus.json
                    String uri = resource.getURI().toString();
                    String[] parts = uri.split("/modules/");
                    if (parts.length < 2) {
                        log.warn("Could not extract folder name from path: {}", uri);
                        continue;
                    }
                    String folderName = parts[1].split("/")[0];

                    log.debug("Processing module: {}", folderName);

                    // Parse plutus.json
                    JsonNode root = objectMapper.readTree(resource.getInputStream());
                    JsonNode validatorsNode = root.get("validators");

                    if (validatorsNode == null || !validatorsNode.isArray()) {
                        log.warn("No validators array found in module: {}", folderName);
                        continue;
                    }

                    // Extract validators
                    List<ModuleValidator> validators = new ArrayList<>();
                    for (JsonNode validatorNode : validatorsNode) {
                        String title = validatorNode.get("title").asText();
                        String compiledCode = validatorNode.get("compiledCode").asText();
                        String hash = validatorNode.get("hash").asText();

                        validators.add(new ModuleValidator(title, compiledCode, hash));
                    }

                    // Optional display metadata; absent for modules that have not
                    // supplied one, in which case the id is capitalised as before.
                    String name = defaultName(folderName);
                    String description = "";
                    var metaResource = new PathMatchingResourcePatternResolver()
                            .getResource("classpath:modules/" + folderName + "/metadata.json");
                    if (metaResource.exists()) {
                        JsonNode meta = objectMapper.readTree(metaResource.getInputStream());
                        if (meta.hasNonNull("name")) {
                            name = meta.get("name").asText();
                        }
                        if (meta.hasNonNull("description")) {
                            description = meta.get("description").asText();
                        }
                    }

                    // Create and cache the module
                    Module module = new Module(folderName, name, description, validators);
                    modulesCache.put(folderName, module);

                    log.info("Loaded module '{}' with {} validators", folderName, validators.size());

                } catch (Exception e) {
                    log.error("Error loading module from resource: {}", resource.getFilename(), e);
                }
            }

            log.info("Successfully loaded {} modules into cache", modulesCache.size());

        } catch (IOException e) {
            log.error("Error scanning modules directory", e);
        }
    }

    /** Capitalised folder id — the label the UI used before metadata.json existed. */
    private static String defaultName(String folderName) {
        return folderName.isEmpty()
                ? folderName
                : folderName.substring(0, 1).toUpperCase() + folderName.substring(1);
    }

    /**
     * Get all modules
     *
     * @return list of all modules
     */
    public List<Module> getAllModules() {
        return modulesCache.values().stream()
                .filter(s -> !disabledModules.contains(s.id()))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * Get a specific module by ID (folder name)
     *
     * @param id the module ID (folder name)
     * @return the module or empty if not found
     */
    public Optional<Module> getModuleById(String id) {
        return Optional.ofNullable(modulesCache.get(id));
    }

    public Optional<ModuleValidator> getModuleValidator(String id, String name) {
        return getModuleById(id)
                .flatMap(module -> module.validators()
                        .stream()
                        .filter(validator -> validator.title().contains(name))
                        .findAny());
    }

}
