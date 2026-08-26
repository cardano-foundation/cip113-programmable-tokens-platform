package org.cardanofoundation.cip113.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CIP-113 core blueprint, resolved once at startup and indexed by {@link CoreValidator}.
 *
 * <p>This is the single point at which the backend turns a blueprint title into compiled
 * code. It replaces a per-call-site {@code Optional<String>} lookup that every caller had
 * to null-check in its own words, and it moves the failure from "some builder throws
 * halfway through a transaction" to "the application refuses to start".
 *
 * <p>That relocation is the point. A missing validator means the blueprint on the
 * classpath is not the one this code was written against — after upstream's split, for
 * instance, {@code programmable_logic_global} simply is not there any more. Discovering
 * that when a user tries to transfer is strictly worse than discovering it at boot, and
 * the diagnostic is better too: {@link #verifyAllResolvable()} names every missing title
 * at once instead of stopping at the first.
 *
 * @see CoreValidator for why titles are derived rather than spelled out
 */
@Component
@Slf4j
public class CoreBlueprint {

    /** Classpath location of the core blueprint. Provenance is recorded in {@code contracts-pin.json}; see {@code docs/CONTRACTS.md}. */
    private static final String BLUEPRINT_RESOURCE = "plutus.json";

    private final Map<CoreValidator, String> compiledCode = new EnumMap<>(CoreValidator.class);
    private final String upstreamVersion;

    /**
     * Deliberately does not declare {@code IOException}. An unreadable or absent core
     * blueprint is not a condition any caller can do anything about — the application
     * cannot build a single transaction without it — and making it checked only forced
     * every construction site, including field initialisers in tests, to carry a handler
     * for something that means "this build is broken".
     */
    public CoreBlueprint() {
        JsonNode root;
        try (InputStream in = new ClassPathResource(BLUEPRINT_RESOURCE).getInputStream()) {
            root = new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "could not read the core blueprint '" + BLUEPRINT_RESOURCE + "' from the classpath", e);
        }
        this.upstreamVersion = root.path("preamble").path("version").asText("unknown");

        Map<String, String> byTitle = new HashMap<>();
        for (JsonNode v : root.get("validators")) {
            byTitle.put(v.get("title").asText(), v.get("compiledCode").asText());
        }
        for (CoreValidator v : CoreValidator.values()) {
            String code = byTitle.get(v.title());
            if (code != null) {
                compiledCode.put(v, code);
            }
        }
    }

    /**
     * Fail fast at startup rather than mid-transaction. Deliberately reports EVERY
     * unresolvable validator, because after a core upgrade there is usually more than one
     * and finding them one boot at a time is the slow way to learn what changed.
     */
    @PostConstruct
    void verifyAllResolvable() {
        List<String> missing = new ArrayList<>();
        for (CoreValidator v : CoreValidator.values()) {
            if (!compiledCode.containsKey(v)) {
                missing.add(v.name() + " (title '" + v.title() + "')");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "The core blueprint on the classpath does not contain every validator this "
                            + "backend resolves. This means " + BLUEPRINT_RESOURCE + " is not the "
                            + "revision the code was written against - check src/main/resources/"
                            + "contracts-pin.json and docs/CONTRACTS.md.\n  missing: "
                            + String.join("\n           ", missing));
        }
        log.info("Core blueprint {} resolved: {} validators", upstreamVersion, compiledCode.size());
    }

    /**
     * Unparameterised compiled code for a core validator.
     *
     * <p>Never returns {@code null}: {@link #verifyAllResolvable()} has already refused to
     * start the application if any validator were missing, so callers do not repeat that
     * check.
     */
    public String compiledCode(CoreValidator validator) {
        String code = compiledCode.get(validator);
        if (code == null) {
            // Unreachable while the startup check runs, but a direct construction in a test
            // bypasses @PostConstruct - better a named failure than an NPE downstream.
            throw new IllegalStateException("core validator not in blueprint: " + validator.title());
        }
        return code;
    }

    /** The {@code preamble.version} of the loaded blueprint, for logging and diagnostics. */
    public String upstreamVersion() {
        return upstreamVersion;
    }
}
