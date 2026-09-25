package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RwaCip171ProvenanceServiceTest {
    private static ModuleService modules;
    private static RwaTokenScriptBuilderService builder;
    private static ProtocolBootstrapParams protocol;

    @BeforeAll static void setup() {
        modules = new ModuleService(new ObjectMapper());
        modules.init();
        builder = new RwaTokenScriptBuilderService(modules,
                new ProtocolScriptBuilderService(new CoreScriptFactory(new CoreBlueprint())));
        protocol = mock(ProtocolBootstrapParams.class, RETURNS_DEEP_STUBS);
        when(protocol.registry().scriptHash()).thenReturn("11".repeat(28));
        when(protocol.protocolParams().policyId()).thenReturn("22".repeat(28));
    }

    private record Built(RwaTokenRegistrationEntity registration, RwaTokenScriptBuilderService.RwaTokenScripts scripts) {}
    private static Built build(String nonce) throws Exception {
        var input = TransactionInput.builder().transactionId(nonce.repeat(32)).index(2).build();
        var gs = builder.buildGlobalStateMintScript(input).getPolicyId();
        var pu = builder.buildPowerUsersMintScript(gs, input).getPolicyId();
        var puSpend = builder.buildPowerUsersSpendScript(gs, pu).getPolicyId();
        var dl = builder.buildDenylistMintScript(gs, input, puSpend).getPolicyId();
        // Labelled CIP-68 security asset: verifies captured bytes preserve the label.
        var scripts = builder.resolveScripts("000de14074657374", gs, pu, dl, protocol);
        var reg = RwaTokenRegistrationEntity.builder().globalStatePolicyId(gs).powerUsersPolicyId(pu)
                .denylistPolicyId(dl).programmableTokenPolicyId(scripts.programmableTokenPolicyId())
                .mintingAuthorityHash(scripts.mintingAuthority().getPolicyId()).build();
        return new Built(reg, scripts);
    }

    private static ConstrPlutusData decode(MetadataList list) throws Exception {
        var bytes = new ByteArrayOutputStream();
        for (int i = 0; i < list.size(); i++) {
            var chunk = (byte[]) list.getValueAt(i);
            assertTrue(chunk.length > 0 && chunk.length <= 64);
            bytes.write(chunk);
        }
        return (ConstrPlutusData) PlutusData.deserialize(bytes.toByteArray());
    }

    @Test void capturesRealBuildersDeduplicatesAndReplaysAllElevenIdentities() throws Exception {
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            var built = build("01");
            build("01"); // genesis and later resolveScripts repeat parameterizations.
            var records = scope.records(built.registration(), built.scripts());
            var cmta = decode(records.cmta()).getData().getPlutusDataList();
            var core = decode(records.issuance()).getData().getPlutusDataList();
            assertEquals(6, cmta.size());
            assertEquals(6, core.size());
            assertEquals("https://github.com/cardano-foundation/cpt-rwa-ch-de-cmta-reference",
                    new String(((BytesPlutusData) cmta.get(0)).getValue(), StandardCharsets.UTF_8));
            assertEquals(20, ((BytesPlutusData) cmta.get(1)).getValue().length);
            assertEquals(0, ((BytesPlutusData) cmta.get(4)).getValue().length);
            var parameters = ((MapPlutusData) cmta.get(5)).getMap();
            assertEquals(10, parameters.size());
            assertEquals(33, parameters.values().stream().mapToInt(v -> ((ListPlutusData) v).getPlutusDataList().size()).sum());
            assertEquals(1, ((MapPlutusData) core.get(5)).getMap().size());
            var gsHash = modules.getModuleValidator("rwa-token", "global_state.global_state_mint_validator.mint")
                    .orElseThrow().scriptHash();
            var gsParams = ((ListPlutusData) parameters.get(BytesPlutusData.of(HexUtil.decodeHexString(gsHash))))
                    .getPlutusDataList();
            assertEquals(BytesPlutusData.of(HexUtil.decodeHexString("01".repeat(32))),
                    PlutusData.deserialize(((BytesPlutusData) gsParams.get(0)).getValue()));
            assertEquals(BigIntPlutusData.of(2), PlutusData.deserialize(((BytesPlutusData) gsParams.get(1)).getValue()));
            var transferHash = modules.getModuleValidator("rwa-token", "transfer_logic_script.transfer_logic_validator.withdraw")
                    .orElseThrow().scriptHash();
            var transferParams = ((ListPlutusData) parameters.get(BytesPlutusData.of(HexUtil.decodeHexString(transferHash))))
                    .getPlutusDataList();
            assertEquals(BytesPlutusData.of(HexUtil.decodeHexString("000de14074657374")),
                    PlutusData.deserialize(((BytesPlutusData) transferParams.get(0)).getValue()));
            for (var entry : parameters.entrySet()) {
                assertEquals(28, ((BytesPlutusData) entry.getKey()).getValue().length);
                for (var wrapped : ((ListPlutusData) entry.getValue()).getPlutusDataList())
                    assertNotNull(PlutusData.deserialize(((BytesPlutusData) wrapped).getValue()));
            }
        }
    }

    @Test void rejectsMissingCoverageAndWrongPersistedIdentity() throws Exception {
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            assertThrows(IllegalStateException.class, () -> scope.records(Map.of()));
            var built = build("02");
            built.registration().setGlobalStatePolicyId("ff".repeat(28));
            assertThrows(IllegalStateException.class, () -> scope.records(built.registration(), built.scripts()));
            built.registration().setMintingAuthorityHash("ff".repeat(28));
            assertThrows(IllegalStateException.class, () -> scope.records(built.registration(), built.scripts()));
        }
    }

    @Test void conflictingApplicationsPoisonCaptureAndCloseClearsIt() throws Exception {
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            var built = build("03");
            assertThrows(RuntimeException.class, () -> build("04"));
            assertThrows(IllegalStateException.class, () -> scope.records(built.registration(), built.scripts()));
        }
        try (var next = RwaCip171ProvenanceService.beginCapture()) {
            var built = build("04");
            assertNotNull(next.records(built.registration(), built.scripts()));
        }
    }

    @Test void rejectsUnpinnedTemplateAndReplayMismatch() throws Exception {
        var contract = modules.getModuleValidator("rwa-token", "denylist.denylist_validator.spend").orElseThrow();
        var params = ListPlutusData.of(BytesPlutusData.of(new byte[28]));
        var otherScript = builder.buildDenylistSpendScript("ff".repeat(28));
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            assertThrows(IllegalStateException.class, () -> RwaCip171ProvenanceService.recordCmta(
                    "denylist_spend", contract.title(), "00", params, otherScript));
        }
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            // A legitimate raw template with a substituted applied script must also fail.
            RwaCip171ProvenanceService.recordCmta("denylist_spend", contract.title(), contract.scriptBytes(), params, otherScript);
            assertThrows(RuntimeException.class, () -> build("05"));
        }
    }

    @Test void scopesAreThreadConfinedAndNeverInherited() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentRecord("06", barrier));
            var second = executor.submit(() -> concurrentRecord("07", barrier));
            assertNotEquals(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            assertThrows(IllegalStateException.class, RwaCip171ProvenanceService::beginCapture);
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> assertThrows(IllegalStateException.class, () -> scope.records(Map.of())))
                        .get(30, TimeUnit.SECONDS);
                executor.submit(() -> assertThrows(IllegalStateException.class, scope::close)).get(30, TimeUnit.SECONDS);
            }
        }
    }

    private static String concurrentRecord(String nonce, CyclicBarrier barrier) throws Exception {
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            var built = build(nonce);
            barrier.await(30, TimeUnit.SECONDS);
            return decode(scope.records(built.registration(), built.scripts()).cmta()).serializeToHex();
        }
    }

    @Test void alteredArtifactOrCompilerPinFailsBeforeScopeStarts() {
        assertThrows(IllegalStateException.class, () -> RwaCip171ProvenanceService.beginCapture(resource -> {
            var bytes = read(resource);
            if (resource.equals("modules/rwa-token/plutus.json")) bytes[bytes.length - 1] ^= 1;
            return bytes;
        }));
        assertThrows(IllegalStateException.class, () -> RwaCip171ProvenanceService.beginCapture(resource -> {
            var bytes = read(resource);
            return resource.equals("contracts-pin.json")
                    ? new String(bytes, StandardCharsets.UTF_8).replace("v1.1.23+8949565", "v1.0.0").getBytes(StandardCharsets.UTF_8)
                    : bytes;
        }));
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            assertNotNull(scope); // both failed preflights left the thread available.
        }
    }

    @Test void sourceTupleChangesRequireAnIndependentRebuildReceipt() throws Exception {
        var replacements = Map.of(
                "repository", "https://github.com/example/different-source",
                "commit", "ab".repeat(20),
                "source_path", "different/project",
                "environment", "preview",
                "aiken_compiler", "v1.1.24+1234567",
                "sha256", "ab".repeat(32));
        var mapper = new ObjectMapper();
        for (String name : new String[]{"rwa-token", "cip113-core"}) {
            for (var replacement : replacements.entrySet()) {
                var manifest = mapper.readTree(read("contracts-pin.json"));
                for (var pin : manifest.path("blueprints")) {
                    if (name.equals(pin.path("name").asText()))
                        ((ObjectNode) pin).put(replacement.getKey(), replacement.getValue());
                }
                byte[] changed = mapper.writeValueAsBytes(manifest);
                var error = assertThrows(IllegalStateException.class,
                        () -> RwaCip171ProvenanceService.beginCapture(resource -> resource.equals("contracts-pin.json")
                                ? changed : read(resource)), name + ": " + replacement.getKey());
                assertTrue(error.getMessage().contains(replacement.getKey().equals("sha256")
                        ? "checksum differs" : "source rebuild receipt differs"), error.getMessage());
            }
        }
        try (var scope = RwaCip171ProvenanceService.beginCapture()) {
            assertNotNull(scope);
        }
    }

    @Test void receiptMustExistAndMatchTheCompleteShippedArtifact() throws Exception {
        assertThrows(IllegalStateException.class, () -> RwaCip171ProvenanceService.beginCapture(resource -> {
            if (resource.equals("cip171-rebuild-receipt.json"))
                throw new IllegalStateException("missing source rebuild receipt");
            return read(resource);
        }));
        var mapper = new ObjectMapper();
        for (String name : new String[]{"rwa-token", "cip113-core"}) {
            var receipt = mapper.readTree(read("cip171-rebuild-receipt.json"));
            for (var entry : receipt.path("blueprints")) {
                if (name.equals(entry.path("name").asText()))
                    ((ObjectNode) entry).put("rebuilt_sha256", "ff".repeat(32));
            }
            byte[] changed = mapper.writeValueAsBytes(receipt);
            var error = assertThrows(IllegalStateException.class,
                    () -> RwaCip171ProvenanceService.beginCapture(resource -> resource.equals("cip171-rebuild-receipt.json")
                            ? changed : read(resource)));
            assertTrue(error.getMessage().contains("source rebuild receipt differs from shipped artifact"));
        }
    }

    @Test void updatingTheOrdinaryArtifactPinDoesNotBypassTheRebuildReceipt() throws Exception {
        var mapper = new ObjectMapper();
        byte[] modifiedArtifact = (new String(read("plutus.json"), StandardCharsets.UTF_8) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        String modifiedHash = HexUtil.encodeHexString(java.security.MessageDigest.getInstance("SHA-256").digest(modifiedArtifact));
        var manifest = mapper.readTree(read("contracts-pin.json"));
        for (var pin : manifest.path("blueprints")) {
            if (pin.path("name").asText().equals("cip113-core")) ((ObjectNode) pin).put("sha256", modifiedHash);
        }
        byte[] changed = mapper.writeValueAsBytes(manifest);
        var error = assertThrows(IllegalStateException.class, () -> RwaCip171ProvenanceService.beginCapture(resource ->
                switch (resource) {
                    case "contracts-pin.json" -> changed;
                    case "plutus.json" -> modifiedArtifact;
                    default -> read(resource);
                }));
        assertTrue(error.getMessage().contains("source rebuild receipt differs from shipped artifact"));
    }

    @Test void coreDependencyRecoveryEvidenceCannotBeOmittedOrAltered() throws Exception {
        var mapper = new ObjectMapper();
        for (String field : new String[]{"repository", "commit", "archive_sha256", "lock_etag", "missing", "cmta"}) {
            var receipt = mapper.readTree(read("cip171-rebuild-receipt.json"));
            for (var entry : receipt.path("blueprints")) {
                if (entry.path("name").asText().equals("cip113-core")) {
                    if (field.equals("missing")) ((ObjectNode) entry).remove("build_dependencies");
                    else if (!field.equals("cmta"))
                        ((ObjectNode) entry.path("build_dependencies").get(0)).put(field, "altered");
                } else if (field.equals("cmta")) {
                    ((ObjectNode) entry).putArray("build_dependencies").addObject().put("commit", "unexpected");
                }
            }
            byte[] changed = mapper.writeValueAsBytes(receipt);
            var error = assertThrows(IllegalStateException.class,
                    () -> RwaCip171ProvenanceService.beginCapture(resource -> resource.equals("cip171-rebuild-receipt.json")
                            ? changed : read(resource)), field);
            assertTrue(error.getMessage().contains("dependency receipt"), error.getMessage());
        }
    }

    private static byte[] read(String resource) {
        try (var stream = RwaCip171ProvenanceServiceTest.class.getClassLoader().getResourceAsStream(resource)) {
            return stream.readAllBytes();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
