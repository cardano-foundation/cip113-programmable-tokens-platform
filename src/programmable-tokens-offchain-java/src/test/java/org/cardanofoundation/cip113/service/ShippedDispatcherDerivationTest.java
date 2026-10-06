package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.model.bootstrap.DispatcherParams;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the records shipped to deployments, not test resources that shadow their names.
 *
 * <h2>⚠ WHY THIS CLASS NO LONGER NAMES A NETWORK OR A HASH</h2>
 *
 * It used to assert two things that read as invariants and were really observations about whichever
 * deployment happened to be committed at the time:
 *
 * <ul>
 *   <li>that preprod's dispatcher is {@code 24ef08d8…}, and</li>
 *   <li>that preview is a deployment with unfracking ENABLED.</li>
 * </ul>
 *
 * Both went false on 2026-10-02 for the most ordinary reason there is — preprod and preview were
 * redeployed ({@code 075934e}, {@code 4f90d04}), the new preprod dispatcher is {@code b4e81fa9…},
 * and the new preview bootstrap disabled unfracking. Nothing was wrong with the code or the records;
 * the test was pinning a deployment's IDENTITY while claiming to check its DERIVATION, so a routine
 * redeployment reddened CI for four days and the signal was read as a build break.
 *
 * <p>⛔ A hash copied out of a record is not an oracle for that record. The record is the authority
 * on what is deployed — on-chain first — so the only things worth asserting here are properties that
 * hold for EVERY deployment, whatever is in it. Re-derivation of every committed record from the
 * blueprint is asserted by {@code CommittedDeploymentHashesTest}, which walks all of
 * {@code protocol-bootstraps-*.json} and needs no edit when a network is redeployed. What is left
 * here is the one property that test cannot see, plus the factory's refusals.
 */
class ShippedDispatcherDerivationTest {
    private static final Path RESOURCES = Path.of("src/main/resources");

    private final ObjectMapper mapper = new ObjectMapper();
    private final CoreScriptFactory scripts = new CoreScriptFactory(new CoreBlueprint());

    private record Shipped(String network, ProtocolBootstrapParams params) {}

    /** Every non-empty {@code protocol-bootstraps-*.json} under main resources. */
    private List<Shipped> shippedRecords() throws Exception {
        var out = new ArrayList<Shipped>();
        try (var files = Files.list(RESOURCES)) {
            for (Path p : files
                    .filter(f -> f.getFileName().toString().startsWith("protocol-bootstraps-"))
                    .filter(f -> f.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .toList()) {
                var json = Files.readString(p);
                if (json.isBlank() || json.trim().equals("[]")) continue;   // no deployment recorded
                var network = p.getFileName().toString()
                        .replace("protocol-bootstraps-", "").replace(".json", "");
                for (var params : mapper.readValue(json, ProtocolBootstrapParams[].class)) {
                    out.add(new Shipped(network, params));
                }
            }
        }
        return out;
    }

    private ProtocolBootstrapParams shipped(String network) throws Exception {
        var json = Files.readString(RESOURCES.resolve("protocol-bootstraps-" + network + ".json"));
        return mapper.readTree(json).get(0).traverse(mapper).readValueAs(ProtocolBootstrapParams.class);
    }

    private ProtocolBootstrapParams changed(ProtocolBootstrapParams original, String field, String value) throws Exception {
        ObjectNode node = mapper.valueToTree(original);
        ((ObjectNode) node.get("programmableLogicGlobal")).put(field, value);
        return mapper.treeToValue(node, ProtocolBootstrapParams.class);
    }

    /**
     * ⛔ THE ONE PROPERTY {@code CommittedDeploymentHashesTest} CANNOT SEE. That test checks the
     * dispatcher re-derives from {@code unfrackingParameter}, which is true for ANY value of it —
     * a third, garbage parameter derives a dispatcher just as consistently as the right one. So a
     * record carrying a parameter that is neither the sentinel nor the unfracking script it was
     * bootstrapped with would pass every derivation check in the suite, and would then withdraw-0
     * from a dispatcher credential nothing registered: ledger 3141.
     *
     * <p>The parameter has exactly two legal spellings, and this says so without naming a network.
     */
    @Test
    void everyShippedDispatcherParameterIsTheSentinelOrTheUnfrackingScript() throws Exception {
        var records = shippedRecords();
        assertFalse(records.isEmpty(),
                "no shipped protocol-bootstraps-*.json record could be read from " + RESOURCES
                        + " — this test is now blind");

        var problems = new ArrayList<String>();
        for (var r : records) {
            var dispatcher = r.params().programmableLogicGlobal();
            var parameter = dispatcher == null ? null : dispatcher.unfrackingParameter();
            var unfracking = r.params().unfracking() == null ? null : r.params().unfracking().scriptHash();

            if (parameter == null || !parameter.matches("(?i)[0-9a-f]{56}")) {
                problems.add("  " + r.network() + ": unfrackingParameter is not 28 bytes of hex: " + parameter);
                continue;
            }
            var disabled = DispatcherParams.UNFRACKING_DISABLED.equalsIgnoreCase(parameter);
            if (!disabled && !parameter.equalsIgnoreCase(unfracking)) {
                problems.add("  " + r.network() + ": unfrackingParameter is neither the disabled"
                        + " sentinel nor this record's unfracking script"
                        + "\n      unfrackingParameter : " + parameter
                        + "\n      unfracking.scriptHash: " + unfracking);
            }
            // And whichever spelling it is, the dispatcher it was compiled against is the one recorded.
            assertEquals(dispatcher.scriptHash(),
                    scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, r.params()).getPolicyId(),
                    () -> r.network() + ": the shipped dispatcher does not re-derive");
        }

        assertTrue(problems.isEmpty(),
                "A shipped deployment record parameterises the dispatcher against something that is "
                        + "neither \"unfracking is off\" nor a script it deployed. Every derivation check "
                        + "in this suite still passes on such a record, because derivation follows the "
                        + "parameter wherever it points — but the resulting credential is not the one the "
                        + "bootstrap registered, and a transfer withdrawing-0 from it gets ledger 3141.\n\n"
                        + String.join("\n", problems));
    }

    @Test
    void missingMalformedAndMismatchedParametersAreRejectedEvenAfterCacheWarmup() throws Exception {
        var preprod = shipped("preprod");
        scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, preprod);

        for (String parameter : new String[]{null, "xyz", "0".repeat(54)}) {
            var changed = changed(preprod, "unfrackingParameter", parameter);
            var error = assertThrows(IllegalStateException.class,
                    () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, changed));
            assertTrue(error.getMessage().contains("unfrackingParameter"));
        }

        var wrongParameter = changed(preprod, "unfrackingParameter", preprod.unfracking().scriptHash());
        var parameterError = assertThrows(IllegalStateException.class,
                () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, wrongParameter));
        assertTrue(parameterError.getMessage().contains("records programmableLogicGlobal.scriptHash"));

        var wrongHash = changed(preprod, "scriptHash", "f".repeat(56));
        var hashError = assertThrows(IllegalStateException.class,
                () -> scripts.script(CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL, wrongHash));
        assertTrue(hashError.getMessage().contains("records programmableLogicGlobal.scriptHash"));
    }
}
