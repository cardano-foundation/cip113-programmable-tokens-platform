package org.cardanofoundation.cip113.model.bootstrap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A deployment record must survive deserialize → serialize, because that is the trip to the browser.
 *
 * <p>⛔ THE SECOND FIELD LOST AT THIS EXACT BOUNDARY. The first was the blueprint's {@code preamble},
 * which {@code GET /protocol/blueprint} omitted entirely — and the SDK reported it as
 * <em>Blueprint "unknown v0.0.0" targets an EARLIER CIP-113 protocol version</em>, pointing at the
 * contracts while the fault was one missing field on the wire. See
 * {@code BlueprintPreambleSurvivesApiBoundaryTest}, which exists for that one.
 *
 * <p>This one is {@code programmableLogicGlobal.unfrackingParameter}, measured 2026-09-30 from a real
 * FES registration on preprod. The committed record carried it; the model bound
 * {@code programmableLogicGlobal} to {@code ScriptParams(String scriptHash)}, which had nowhere to put
 * it; and the frontend's own mapper is {@code return bp} — a faithful pass-through — so the value was
 * gone before it left this service. The SDK then refused, correctly, naming a field the operator had
 * never seen.
 *
 * <p>⚑ WHY THIS IS NOT COVERED BY {@link RecordKeyCoverageTest}, and why both exist. That one checks
 * FILE → MODEL: can the model hold what the file says. This checks MODEL → JSON: does what the model
 * holds still reach the caller. They are different hops, and the preamble defect proves the second can
 * fail on its own — a field can bind perfectly and still be dropped on the way out by an annotation, a
 * null, or a DTO that was never widened.
 *
 * <p>⚠ Deliberately a ROUND TRIP against the FILE as oracle, not model-to-model. A model-to-model
 * comparison would prove the mapper self-consistent and nothing else.
 */
class DeploymentRecordSurvivesApiBoundaryTest {

    /** Strict, so a key the model cannot hold is an error here rather than a silent omission. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final List<String> RECORDS = List.of(
            "src/main/resources/protocol-bootstraps-preview.json",
            "src/main/resources/protocol-bootstraps-preprod.json");

    @Test
    @DisplayName("every committed record round-trips with its dispatcher's unfrackingParameter intact")
    void theDispatcherClaimSurvivesTheBoundary() throws IOException {
        var examined = 0;

        for (String file : RECORDS) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            JsonNode root = MAPPER.readTree(Files.readString(p));
            for (JsonNode entry : root.isArray() ? root : MAPPER.createArrayNode().add(root)) {
                if (!entry.isObject()) continue;
                examined++;

                // The file is the oracle.
                JsonNode declared = entry.get("programmableLogicGlobal");
                assertNotNull(declared, file + ": no programmableLogicGlobal in the record");
                assertTrue(declared.has("unfrackingParameter"),
                        file + ": the record itself is missing programmableLogicGlobal."
                                + "unfrackingParameter. It is REQUIRED and has no default — it records "
                                + "whether the dispatcher was compiled against the real unfracking hash "
                                + "or the disabled sentinel, and nothing else in the record says which.");
                String expected = declared.get("unfrackingParameter").asText();

                // Deserialize into the model, then serialize as the API would.
                ProtocolBootstrapParams params =
                        MAPPER.treeToValue(entry, ProtocolBootstrapParams.class);
                assertNotNull(params.programmableLogicGlobal(),
                        file + ": the dispatcher did not bind at all");
                assertEquals(expected, params.programmableLogicGlobal().unfrackingParameter(),
                        file + ": unfrackingParameter did not survive binding");

                JsonNode out = MAPPER.valueToTree(params);
                JsonNode plg = out.get("programmableLogicGlobal");
                assertNotNull(plg, file + ": programmableLogicGlobal vanished on the way OUT");
                assertTrue(plg.has("unfrackingParameter"),
                        file + ": unfrackingParameter is absent from what this service would SERVE. "
                                + "It binds and is then dropped on serialisation — the exact shape of "
                                + "the preamble defect, and the frontend passes through faithfully, so "
                                + "whatever is missing here is missing in the SDK.");
                assertEquals(expected, plg.get("unfrackingParameter").asText(),
                        file + ": unfrackingParameter changed value across the boundary");
            }
        }

        // ⛔ A check that examined nothing must not pass. Records get renamed and emptied.
        assertTrue(examined > 0, "no deployment record was examined. Looked for: " + RECORDS);
    }

    @Test
    @DisplayName("the check is not vacuous — a dispatcher bound to the OLD type fails to bind at all")
    void theCheckCanFail() throws IOException {
        // ⚑ PROOF OF HARNESS. With FAIL_ON_UNKNOWN_PROPERTIES enabled, binding the dispatcher to the
        // type it used to have must THROW — which is what the strict mapper does and what Spring's
        // lenient one did not. If this ever stops throwing, ScriptParams has grown the field and this
        // suite is no longer testing the boundary it was written for.
        JsonNode root = MAPPER.readTree(Files.readString(Path.of(RECORDS.get(0))));
        JsonNode plg = (root.isArray() ? root.get(0) : root).get("programmableLogicGlobal");
        assertTrue(plg != null && plg.has("unfrackingParameter"),
                "the preview record no longer carries the field, so this proves nothing");

        var threw = false;
        try {
            MAPPER.treeToValue(plg, ScriptParams.class);
        } catch (Exception e) {
            threw = true;
        }
        assertTrue(threw, "binding the dispatcher to ScriptParams must fail under a strict mapper — "
                + "that is the defect this suite exists for");
    }
}
