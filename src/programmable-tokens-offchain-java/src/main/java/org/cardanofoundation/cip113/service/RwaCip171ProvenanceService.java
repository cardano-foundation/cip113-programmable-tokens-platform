package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.cip171.CompilerType;
import org.cardanofoundation.cip113.cip171.UplcLinkRequest;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import org.springframework.core.io.ClassPathResource;

import java.net.URI;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Captures the actual synchronous creation's parameter applications, then replays pinned templates.
 * The non-inheritable scope is removed on close, including exceptional exits. Calls outside a
 * creation scope do nothing; no provenance can accidentally be reused by a later request. */
public final class RwaCip171ProvenanceService {
    private RwaCip171ProvenanceService() {}

    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    // The core source pins a floating fuzz branch through this archive ETag. Keep this
    // reviewed recipe aligned with scripts/verify-cip171-sources.py when upgrading sources.
    private static final Map<String, String> CORE_FUZZ_DEPENDENCY = Map.of(
            "repository", "https://github.com/aiken-lang/fuzz",
            "commit", "06874926ec70747f3fc4e2b9364ee9e1393441cc",
            "archive_sha256", "b8158eb84ec81114cfc5fa179927a82aafae64de41001e9767fdb02ceb8892d9",
            "lock_etag", "9843473958e51725a9274b487d2d4aac0395ec1a2e30f090724fa737226bc127");
    private static final Map<String, String> CMTA_TITLES = Map.of(
            "global_state_mint", "global_state.global_state_mint_validator.mint",
            "global_state_spend", "global_state.global_state_spend_validator.spend",
            "denylist_mint", "denylist.mint.mint",
            "denylist_spend", "denylist.denylist_validator.spend",
            "power_users_mint", "power_users.mint.mint",
            "power_users_spend", "power_users.power_users_validator.spend",
            "minting_logic", "minting_logic_script.minting_logic_validator.withdraw",
            "minting_authority", "minting_authority.minting_authority_validator.withdraw",
            "transfer_logic", "transfer_logic_script.transfer_logic_validator.withdraw",
            "third_party_transfer_logic", "third_party_transfer_logic_script.third_party_transfer_logic_validator.withdraw");

    public static Capture beginCapture() {
        return beginCapture(RwaCip171ProvenanceService::readResource);
    }

    static Capture beginCapture(Function<String, byte[]> resources) {
        if (ACTIVE.get() != null) throw failure("nested capture is not supported");
        // Load and verify both artifacts before genesis can write anything.
        var scope = new Capture(loadPin("rwa-token", resources), loadPin("cip113-core", resources));
        ACTIVE.set(scope);
        return scope;
    }

    public static void recordCmta(String name, String title, String compiledCode,
                                  ListPlutusData parameters, PlutusScript applied) {
        var scope = ACTIVE.get();
        if (scope != null) scope.record(name, title, compiledCode, parameters, applied, false);
    }

    public static void recordIssuance(String compiledCode, ListPlutusData parameters, PlutusScript applied) {
        var scope = ACTIVE.get();
        if (scope != null) scope.record("issuance_mint", CoreValidator.ISSUANCE_MINT.title(),
                compiledCode, parameters, applied, true);
    }

    public record Records(MetadataList cmta, MetadataList issuance) {}
    private record Application(String rawHash, List<String> parameters, String appliedHash) {}
    private record Template(String code, String rawHash, int arity) {}
    private record Pin(String repository, String commit, String compiler, String path, String environment,
                       Map<String, Template> templates) {}

    public static final class Capture implements AutoCloseable {
        private final Pin cmta;
        private final Pin core;
        private final Map<String, Application> applications = new LinkedHashMap<>();
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private boolean failed;

        private Capture(Pin cmta, Pin core) { this.cmta = cmta; this.core = core; }

        private void requireActive() {
            if (closed || Thread.currentThread() != owner || ACTIVE.get() != this)
                throw failure("capture must be used on its owning creation thread while open");
            if (failed) throw failure("capture previously failed");
        }

        private void record(String name, String title, String code, ListPlutusData parameters,
                            PlutusScript applied, boolean issuance) {
            requireActive();
            try {
                var expectedTitle = issuance ? CoreValidator.ISSUANCE_MINT.title() : CMTA_TITLES.get(name);
                var template = (issuance ? core : cmta).templates().get(title);
                if (!title.equals(expectedTitle) || template == null || !template.code().equals(code))
                    throw failure("application does not match the pinned template: " + name);
                var serialized = parameters.getPlutusDataList().stream().map(PlutusData::serializeToHex).toList();
                if (serialized.size() != template.arity()) throw failure("parameter arity mismatch: " + name);
                var application = new Application(template.rawHash(), serialized, hash(applied));
                var previous = applications.putIfAbsent(name, application);
                if (previous != null && !previous.equals(application))
                    throw failure("conflicting parameter applications: " + name);
            } catch (RuntimeException e) {
                failed = true;
                throw e;
            }
        }

        /** Verifies all eleven applied hashes against the identities about to be registered. */
        public Records records(RwaTokenRegistrationEntity reg, RwaTokenScriptBuilderService.RwaTokenScripts scripts) {
            requireActive();
            var expected = new LinkedHashMap<String, String>();
            expected.put("global_state_mint", reg.getGlobalStatePolicyId());
            expected.put("global_state_spend", hash(scripts.globalStateSpend()));
            expected.put("denylist_mint", reg.getDenylistPolicyId());
            expected.put("denylist_spend", hash(scripts.denylistSpend()));
            expected.put("power_users_mint", reg.getPowerUsersPolicyId());
            expected.put("power_users_spend", hash(scripts.powerUsersSpend()));
            expected.put("minting_logic", hash(scripts.mintingLogicProxy()));
            expected.put("minting_authority", hash(scripts.mintingAuthority()));
            expected.put("transfer_logic", hash(scripts.transferLogic()));
            expected.put("third_party_transfer_logic", hash(scripts.thirdPartyTransferLogic()));
            expected.put("issuance_mint", reg.getProgrammableTokenPolicyId());
            if (!hash(scripts.issuanceScript()).equals(reg.getProgrammableTokenPolicyId())
                    || !scripts.programmableTokenPolicyId().equals(reg.getProgrammableTokenPolicyId())
                    || !hash(scripts.mintingAuthority()).equals(reg.getMintingAuthorityHash()))
                throw failure("persisted issuance or minting-authority identity does not match deployed scripts");
            return records(expected);
        }

        // Package-visible for isolated identity/replay tests.
        Records records(Map<String, String> expected) {
            requireActive();
            if (expected.size() != 11 || !expected.keySet().containsAll(CMTA_TITLES.keySet())
                    || !expected.containsKey("issuance_mint") || !applications.keySet().equals(expected.keySet()))
                throw failure("creation must capture all ten CMTA templates and the core issuance policy");
            var cmtaParameters = new LinkedHashMap<String, List<String>>();
            var issuanceParameters = new LinkedHashMap<String, List<String>>();
            for (var entry : expected.entrySet()) {
                var name = entry.getKey();
                var application = applications.get(name);
                var issuance = name.equals("issuance_mint");
                var title = issuance ? CoreValidator.ISSUANCE_MINT.title() : CMTA_TITLES.get(name);
                var template = (issuance ? core : cmta).templates().get(title);
                try {
                    var decoded = new PlutusData[application.parameters().size()];
                    for (int i = 0; i < decoded.length; i++)
                        decoded[i] = PlutusData.deserialize(HexUtil.decodeHexString(application.parameters().get(i)));
                    var params = ListPlutusData.of(decoded);
                    var replay = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                            AikenScriptUtil.applyParamToScript(params, template.code()), PlutusVersion.v3);
                    if (!hash(replay).equals(application.appliedHash())
                            || !application.appliedHash().equals(entry.getValue()))
                        throw failure("parameter replay differs from deployed script: " + name);
                    var map = issuance ? issuanceParameters : cmtaParameters;
                    if (map.putIfAbsent(application.rawHash(), application.parameters()) != null)
                        throw failure("duplicate raw template hash: " + name);
                } catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw failure("could not replay " + name, e); }
            }
            return new Records(request(cmta, cmtaParameters).toMetadataChunkList(),
                    request(core, issuanceParameters).toMetadataChunkList());
        }

        @Override public void close() {
            if (Thread.currentThread() != owner) throw failure("capture must close on its owning creation thread");
            if (!closed) {
                if (ACTIVE.get() != this) throw failure("capture ownership lost");
                ACTIVE.remove();
                applications.clear();
                closed = true;
            }
        }
    }

    private static UplcLinkRequest request(Pin pin, Map<String, List<String>> parameters) {
        return UplcLinkRequest.builder().compilerType(CompilerType.AIKEN).sourceUrl(pin.repository())
                .commitHash(pin.commit()).sourcePath(pin.path()).compilerVersion(pin.compiler())
                .environment(pin.environment()).parameters(parameters).build();
    }

    private static byte[] readResource(String resource) {
        try (var in = new ClassPathResource(resource).getInputStream()) { return in.readAllBytes(); }
        catch (Exception e) { throw failure("could not read " + resource, e); }
    }

    private static Pin loadPin(String name, Function<String, byte[]> resources) {
        try {
            var mapper = new ObjectMapper();
            JsonNode manifest = mapper.readTree(resources.apply("contracts-pin.json"));
            JsonNode pin = null;
            for (var candidate : manifest.path("blueprints")) {
                if (name.equals(candidate.path("name").asText())) {
                    if (pin != null) throw failure("duplicate blueprint pin: " + name);
                    pin = candidate;
                }
            }
            if (pin == null) throw failure("missing blueprint pin: " + name);
            String repo = pin.path("repository").asText();
            var uri = URI.create(repo);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !pin.path("commit").asText().matches("[0-9a-f]{40}"))
                throw failure("invalid source pin: " + name);
            byte[] bytes = resources.apply(pin.path("resource").asText());
            String artifactHash = HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!artifactHash.equals(pin.path("sha256").asText()))
                throw failure("blueprint checksum differs from pin: " + name);
            verifyRebuildReceipt(name, pin, artifactHash, mapper.readTree(resources.apply("cip171-rebuild-receipt.json")));
            var blueprint = mapper.readTree(bytes);
            var compiler = pin.path("aiken_compiler").asText();
            if (compiler.isBlank() || !compiler.equals(blueprint.path("preamble").path("compiler").path("version").asText())
                    || !"Aiken".equals(blueprint.path("preamble").path("compiler").path("name").asText())
                    || !"v3".equals(blueprint.path("preamble").path("plutusVersion").asText()))
                throw failure("compiler or Plutus version differs from pin: " + name);
            var templates = new LinkedHashMap<String, Template>();
            for (var validator : blueprint.path("validators")) {
                var code = validator.path("compiledCode").asText();
                var rawHash = hash(PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(code, PlutusVersion.v3));
                if (!rawHash.equals(validator.path("hash").asText())) throw failure("raw template hash mismatch: " + name);
                var previous = templates.put(validator.path("title").asText(),
                        new Template(code, rawHash, validator.path("parameters").size()));
                if (previous != null) throw failure("duplicate template title: " + name);
            }
            return new Pin(repo, pin.path("commit").asText(), compiler,
                    pin.path("source_path").asText(""), pin.path("environment").asText(""), Map.copyOf(templates));
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw failure("could not verify blueprint pin: " + name, e); }
    }

    /** The release-time source rebuild is attested separately from the ordinary artifact pin.
     * Changing a source tuple requires rerunning scripts/verify-cip171-sources.py; runtime never
     * fetches source or invokes a compiler. The receipt is a reviewed build artifact, not a signature. */
    private static void verifyRebuildReceipt(String name, JsonNode pin, String artifactHash, JsonNode receipt) {
        if (!receipt.path("schema_version").isIntegralNumber() || receipt.path("schema_version").asInt() != 1
                || !receipt.path("blueprints").isArray() || receipt.path("blueprints").size() != 2)
            throw failure("invalid source rebuild receipt");
        JsonNode entry = null;
        for (var candidate : receipt.path("blueprints")) {
            String candidateName = candidate.path("name").asText();
            if (!candidateName.equals("rwa-token") && !candidateName.equals("cip113-core"))
                throw failure("unexpected source rebuild receipt entry: " + candidateName);
            if (name.equals(candidateName)) {
                if (entry != null) throw failure("duplicate source rebuild receipt: " + name);
                entry = candidate;
            }
        }
        if (entry == null) throw failure("missing source rebuild receipt: " + name);
        for (String field : List.of("repository", "commit", "resource", "aiken_compiler", "source_path", "environment")) {
            var pinned = pin.path(field);
            boolean optional = field.equals("source_path") || field.equals("environment");
            if ((!pinned.isTextual() && !(optional && pinned.isMissingNode())) || !entry.path(field).isTextual()
                    || !pinned.asText("").equals(entry.path(field).asText()))
                throw failure("source rebuild receipt differs from pin (" + field + "): " + name);
        }
        if (!entry.path("rebuilt_sha256").isTextual() || !artifactHash.equals(entry.path("rebuilt_sha256").asText()))
            throw failure("source rebuild receipt differs from shipped artifact: " + name);
        var dependencies = entry.path("build_dependencies");
        if (name.equals("cip113-core")) {
            if (!dependencies.isArray() || dependencies.size() != 1 || !dependencies.get(0).isObject()
                    || dependencies.get(0).size() != CORE_FUZZ_DEPENDENCY.size())
                throw failure("missing or invalid core source rebuild dependency receipt");
            for (var field : CORE_FUZZ_DEPENDENCY.entrySet()) {
                var value = dependencies.get(0).path(field.getKey());
                if (!value.isTextual() || !field.getValue().equals(value.asText()))
                    throw failure("core source rebuild dependency receipt mismatch: " + field.getKey());
            }
        } else if (!dependencies.isMissingNode() && !(dependencies.isArray() && dependencies.isEmpty())) {
            throw failure("unexpected CMTA source rebuild dependency receipt");
        }
    }

    private static String hash(PlutusScript script) {
        try { return HexUtil.encodeHexString(script.getScriptHash()); }
        catch (Exception e) { throw failure("could not hash script", e); }
    }
    private static IllegalStateException failure(String detail) {
        return new IllegalStateException("CIP-171 provenance: " + detail);
    }
    private static IllegalStateException failure(String detail, Exception cause) {
        return new IllegalStateException("CIP-171 provenance: " + detail, cause);
    }
}
