package org.cardanofoundation.cip113.model.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every key in a committed deployment record must have somewhere to land in the model.
 *
 * <p>⛔ THE DEFECT THIS EXISTS FOR, measured 2026-09-30. {@code programmableLogicGlobal} was typed as
 * {@code ScriptParams(String scriptHash)} while the committed records carry
 * {@code programmableLogicGlobal.unfrackingParameter} — and the consequence depended on WHICH MAPPER
 * read the file. Spring's injected ObjectMapper has {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so
 * it dropped the field in silence: the parse succeeded, startup succeeded, and the symptom surfaced
 * in a BROWSER, five steps into an operator's flow, naming a field they had never seen. A plain
 * {@code new ObjectMapper()} threw instead — which is why
 * {@code PreviewDeploymentRecordDerivationTest} could not initialise at {@code main}.
 *
 * <p>⚑ SO ONE DEFECT WORE TWO FACES, and neither pointed at the cause. The loud one looked like a
 * broken test; the quiet one looked like an SDK complaint about a field nobody had heard of.
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} sits on the OUTER record and does not reach
 * nested types, which is what let the same file be both loadable and unloadable.
 *
 * <p>This checks the SHAPE rather than that one field, because the mechanism will lose the next one
 * the same way — and it uses a STRICT mapper deliberately, so the quiet face cannot hide here.
 *
 * <p>⚠ Deliberately not a round trip. A round trip proves the model self-consistent: serialise what
 * was parsed, compare, agree. The question here is whether the model can hold what the FILE says, so
 * the file is the oracle and reflection is the instrument.
 */
class RecordKeyCoverageTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Both records the application ships. An empty preprod array is not a failure. */
    private static final List<String> RECORDS = List.of(
            "src/main/resources/protocol-bootstraps-preview.json",
            "src/main/resources/protocol-bootstraps-preprod.json");

    /** Keys deliberately not bound as model components. */
    private static final Set<String> NOT_BOUND = Set.of();

    @Test
    @DisplayName("no key in a committed record is silently dropped by the model")
    void everyRecordedKeyHasSomewhereToLand() throws IOException {
        var problems = new ArrayList<String>();
        var examined = 0;

        for (String file : RECORDS) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            JsonNode root = MAPPER.readTree(Files.readString(p));
            Iterable<JsonNode> entries = root.isArray() ? root : List.of(root);
            for (JsonNode entry : entries) {
                if (!entry.isObject()) continue;
                examined++;
                problems.addAll(unbindable(entry, ProtocolBootstrapParams.class, file + " → "));
            }
        }

        // ⛔ A CHECK THAT EXAMINED NOTHING MUST NOT REPORT SUCCESS. The records could be renamed,
        // moved or emptied, and this would then pass in silence exactly as the defect did.
        assertTrue(examined > 0,
                "no deployment record was examined, so this proved nothing. Looked for: " + RECORDS);

        if (!problems.isEmpty()) {
            fail("A committed deployment record carries keys the model cannot hold. Jackson drops them "
                    + "silently: the parse succeeds, startup succeeds, and the value is simply gone by "
                    + "the time anything reads it.\n\n  "
                    + String.join("\n  ", problems)
                    + "\n\nAdd the field to the binding type, or list it in NOT_BOUND with a reason.");
        }
    }

    @Test
    @DisplayName("the check is not vacuous — the old, narrower type IS reported")
    void theCheckCanFail() throws IOException {
        // ⚑ PROOF OF HARNESS. Run the same comparison against the type the dispatcher USED to be
        // bound to and assert it is reported. Without this the main test could pass by recursing
        // into nothing at all, which is the failure mode of every reflection-based check.
        JsonNode root = MAPPER.readTree(Files.readString(Path.of(RECORDS.get(0))));
        JsonNode first = root.isArray() ? root.get(0) : root;
        JsonNode plg = first.get("programmableLogicGlobal");
        assertTrue(plg != null && plg.has("unfrackingParameter"),
                "the preview record no longer carries programmableLogicGlobal.unfrackingParameter, so "
                        + "this proof-of-harness is testing nothing");

        var reported = unbindable(plg, ScriptParams.class, "");
        assertTrue(reported.stream().anyMatch(s -> s.startsWith("unfrackingParameter")),
                "binding the dispatcher to ScriptParams must report unfrackingParameter as unbindable "
                        + "— that is the exact defect this suite exists for. Got: " + reported);

        assertTrue(unbindable(plg, DispatcherParams.class, "").isEmpty(),
                "DispatcherParams must bind every key the record carries for the dispatcher");
    }

    /** Recurse a JSON object against a record type, reporting keys with no component to bind to. */
    private static List<String> unbindable(JsonNode node, Class<?> type, String where) {
        var out = new ArrayList<String>();
        if (!node.isObject() || !type.isRecord()) return out;

        Map<String, RecordComponent> byName = Arrays.stream(type.getRecordComponents())
                .collect(Collectors.toMap(RecordComponent::getName, c -> c, (a, b) -> a, LinkedHashMap::new));

        node.fieldNames().forEachRemaining(name -> {
            if (NOT_BOUND.contains(name)) return;
            RecordComponent c = byName.get(name);
            if (c == null) {
                out.add(where + name + "  (no component on " + type.getSimpleName() + ")");
                return;
            }
            JsonNode child = node.get(name);
            // Only recurse into nested records; a String or Long component binds whatever it is given.
            if (child != null && child.isObject() && c.getType().isRecord()) {
                out.addAll(unbindable(child, c.getType(), where + name + "."));
            }
        });
        return out;
    }
}
