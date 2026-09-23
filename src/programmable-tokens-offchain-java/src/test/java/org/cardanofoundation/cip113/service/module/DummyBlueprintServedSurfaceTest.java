package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dummy blueprint the backend actually SERVES, checked against what the handler needs.
 *
 * <h2>Why this exists</h2>
 *
 * {@code contracts-pin.json} proves the shipped bytes are the bytes we intended. It cannot
 * prove those bytes are usable — a pin is happy to pin a blueprint that is internally
 * consistent and functionally wrong.
 *
 * <p>This is the complementary half, and it is the check that would have caught the
 * five-month-old defect on its own: the backend shipped a {@code dummy} blueprint built by
 * {@code Aiken v1.1.9+unknown} carrying four validators, while the source tree carried six.
 * The two missing ones were {@code transfer.issue.publish} and {@code transfer.transfer.publish}
 * — added by {@code e63fa0a} precisely so the withdraw-0 credentials could be registered.
 * A backend serving the four-validator artifact cannot register those credentials at all.
 *
 * <p>{@link DummyModuleHandler} does not read this file directly; it takes validators
 * from {@code ModuleService}, which globs {@code classpath:modules/{@literal *}/plutus.json}.
 * So the bytes asserted here are the bytes that reach {@code PlutusBlueprintUtil} and become
 * a script in a built transaction — which is what makes a stale artifact a chain-visible
 * defect rather than a packaging untidiness.
 */
class DummyBlueprintServedSurfaceTest {

    private static final String SERVED = "/modules/dummy/plutus.json";

    /** The four the handler resolves by name, plus the two publish handlers e63fa0a added. */
    private static final String ISSUE_WITHDRAW = "transfer.issue.withdraw";
    private static final String TRANSFER_WITHDRAW = "transfer.transfer.withdraw";
    private static final String ISSUE_PUBLISH = "transfer.issue.publish";
    private static final String TRANSFER_PUBLISH = "transfer.transfer.publish";

    @Test
    @DisplayName("the served dummy blueprint carries the publish handlers the withdraw-0 registration needs")
    void servedBlueprintDeclaresPublishHandlers() throws Exception {
        Map<String, String> hashes = servedValidatorHashes();

        assertTrue(hashes.containsKey(ISSUE_PUBLISH),
                "served dummy blueprint has no " + ISSUE_PUBLISH + " — the withdraw-0 credential "
                        + "for the issue logic cannot be registered. Declared validators: " + hashes.keySet());
        assertTrue(hashes.containsKey(TRANSFER_PUBLISH),
                "served dummy blueprint has no " + TRANSFER_PUBLISH + " — the withdraw-0 credential "
                        + "for the transfer logic cannot be registered. Declared validators: " + hashes.keySet());
    }

    @Test
    @DisplayName("the served blueprint was built by the pinned compiler, not the stale one")
    void servedBlueprintCompiler() throws Exception {
        JsonNode root = readServed();
        String compiler = root.path("preamble").path("compiler").path("version").asText();
        assertEquals("v1.1.21+42babe5", compiler,
                "served dummy blueprint was built by " + compiler + ". A different aiken yields "
                        + "different script hashes and a non-interoperable deployment.");
    }

    /**
     * Self-consistency: the hash the handler derives from {@code compiledCode} — via exactly the
     * call {@link DummyModuleHandler} makes — must equal the hash the blueprint declares.
     * This is what ties the served bytes to the script that goes on chain.
     */
    @Test
    @DisplayName("each served validator's declared hash is the hash derived from its compiledCode")
    void declaredHashesAreDerivable() throws Exception {
        for (JsonNode v : readServed().get("validators")) {
            String title = v.get("title").asText();
            String declared = v.get("hash").asText();
            var script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    v.get("compiledCode").asText(), PlutusVersion.v3);
            String derived = HexUtil.encodeHexString(script.getScriptHash());
            assertEquals(declared, derived,
                    title + ": blueprint declares " + declared + " but its compiledCode derives "
                            + derived);
        }
    }

    /**
     * The two script hashes the handler actually puts in a transaction, asserted as literals so
     * that a silent artifact swap shows up here as a changed expectation rather than as a
     * different script quietly going on chain.
     */
    @Test
    @DisplayName("the handler's two withdraw scripts are the pinned v1.1.21 ones")
    void withdrawScriptHashesArePinned() throws Exception {
        Map<String, String> hashes = servedValidatorHashes();
        assertEquals("4be2c6d3f5c5e66f45d20801c0d55341f0ae3b939187c9591ec29028",
                hashes.get(ISSUE_WITHDRAW), ISSUE_WITHDRAW + " is not the v1.1.21 script");
        assertEquals("6ae51f97696717eb0de100c151a30965a3c4ff97c167124b2b206ba9",
                hashes.get(TRANSFER_WITHDRAW), TRANSFER_WITHDRAW + " is not the v1.1.21 script");
    }

    private static Map<String, String> servedValidatorHashes() throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode v : readServed().get("validators")) {
            out.put(v.get("title").asText(), v.get("hash").asText());
        }
        return out;
    }

    private static JsonNode readServed() throws Exception {
        try (InputStream in = DummyBlueprintServedSurfaceTest.class.getResourceAsStream(SERVED)) {
            assertNotNull(in, SERVED + " is not on the classpath");
            return new ObjectMapper().readTree(in);
        }
    }
}
