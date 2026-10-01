package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.cardanofoundation.cip113.cip171.Cip171Parameters;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A token minted under ANOTHER deployment must rebuild from its own CIP-171 record.
 *
 * <h2>Why it could not before</h2>
 *
 * {@code transfer} is parameterised by {@code (programmableLogicBase.scriptHash,
 * blacklistNodePolicyId)}, and the reconstruction proves itself by recomputing that script and
 * comparing to the hash the chain's registry node reports. It used the base THIS instance knows.
 *
 * <p>⛔ But {@code protocol-bootstraps-<network>.json} holds ONE record and a re-bootstrap REPLACES
 * it, so a token from a previous deployment — or from another platform instance — belongs to a base
 * this instance may not record at all. The caller then passes null, nothing reproduces, and the
 * refusal claimed "the record does not describe this token" when the truth was that this instance
 * could not name the deployment the record describes.
 *
 * <p>The record publishes the base itself, as {@code programmable_logic_base_cred}. Reading it makes
 * the proof independent of local deployment history without weakening it: the recovered base still
 * has to reproduce the chain's transfer hash.
 *
 * <h2>Why a Credential needed its own extractor</h2>
 *
 * {@code programmable_logic_base_cred} is a {@code Credential} — {@code Constr 1 [scriptHash]} — not
 * a byte string, so {@code Cip171Parameters.bytes} returns EMPTY for it. That empty reads as "the
 * record does not carry it", which is why the base looked unrecoverable from provenance. The first
 * test below pins exactly that distinction.
 */
class CrossDeploymentProvenanceRebuildTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_HASH = "fccbf139b8ab7146024e2f832297770e4187b7f509d09adbadfe5a46";
    private static final String POLICY_ID = "3c88a4f59a83378259484c3266649bf198d9f096e54022240c85c55e";

    /** A CIP-171 record carrying one script with the given (title -> hex PlutusData) parameters. */
    private static ObjectNode recordWith(String[][] params) {
        ObjectNode record = MAPPER.createObjectNode();
        record.put("sourcePath", "aiken/example_transfer_logic.ak");
        ArrayNode scripts = record.putArray("scripts");
        ObjectNode script = scripts.addObject();
        ArrayNode required = script.putArray("requiredParameters");
        ArrayNode provided = script.putArray("providedParameters");
        for (String[] p : params) {
            required.addObject().put("title", p[0]);
            provided.add(p[1]);
        }
        return record;
    }

    private static String scriptCredentialHex(String hash) {
        // Credential::Script is Constr 1 [bytes]
        var c = ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(hash)));
        try {
            return HexUtil.encodeHexString(c.serializeToBytes());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String bytesHex(String hash) {
        try {
            return HexUtil.encodeHexString(
                    BytesPlutusData.of(HexUtil.decodeHexString(hash)).serializeToBytes());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("credentialHash reads a Credential parameter that bytes() cannot")
    void credentialHashReadsWhatBytesCannot() {
        var record = recordWith(new String[][] {
                {"programmable_logic_base_cred", scriptCredentialHex(BASE_HASH)},
                {"blacklist_node_cs", bytesHex(POLICY_ID)},
        });

        assertEquals(BASE_HASH,
                Cip171Parameters.credentialHash(record, "programmable_logic_base_cred").orElse(null),
                "the deployment base must be recoverable from the record");

        // ⛔ THE POINT OF THE NEW EXTRACTOR, pinned: bytes() sees a Constr and gives up, and that
        // silent empty is indistinguishable from "the record does not carry the base".
        assertTrue(Cip171Parameters.bytes(record, "programmable_logic_base_cred").isEmpty(),
                "bytes() must NOT decode a Credential — if it starts to, credentialHash is redundant "
                        + "and this test should be revisited rather than deleted");

        // And the plain byte-string parameter still works through bytes(), unchanged.
        assertEquals(POLICY_ID, Cip171Parameters.bytes(record, "blacklist_node_cs").orElse(null));
    }

    @Test
    @DisplayName("a verification-key credential is read too, not just a script one")
    void readsEitherCredentialAlternative() {
        var keyCred = ConstrPlutusData.of(0, BytesPlutusData.of(HexUtil.decodeHexString(BASE_HASH)));
        String hex;
        try {
            hex = HexUtil.encodeHexString(keyCred.serializeToBytes());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        var record = recordWith(new String[][] {{"some_cred", hex}});
        assertEquals(BASE_HASH, Cip171Parameters.credentialHash(record, "some_cred").orElse(null),
                "Constr 0 is a verification-key credential and carries a hash just the same");
    }

    @Test
    @DisplayName("absent, malformed and empty parameters are 'absent', never an exception")
    void malformedIsAbsentNotAnError() {
        var record = recordWith(new String[][] {
                {"not_a_cred", bytesHex(BASE_HASH)},           // bytes where a Constr is expected
                {"garbage", "zzzz"},                            // undecodable
                {"empty", ""},                                  // blank
        });
        assertTrue(Cip171Parameters.credentialHash(record, "missing").isEmpty());
        assertTrue(Cip171Parameters.credentialHash(record, "not_a_cred").isEmpty());
        assertTrue(Cip171Parameters.credentialHash(record, "garbage").isEmpty());
        assertTrue(Cip171Parameters.credentialHash(record, "empty").isEmpty());
        assertTrue(Cip171Parameters.credentialHash(null, "anything").isEmpty());
    }

    // ---------------------------------------------------------------------------------------
    // The reconstructor's and the client's behaviour are structural: reaching them at runtime
    // needs an HTTP registry, a script builder and three repositories, and a test that heavy
    // asserts its own mocks. These pin the two properties that were wrong.
    // ---------------------------------------------------------------------------------------

    private static String read(String path) throws IOException {
        Path p = Path.of(path);
        assertTrue(Files.isRegularFile(p), path + " is gone — this check is now blind");
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the rebuild proves itself against the RECORD's base, not only the caller's")
    void rebuildPrefersTheRecordsOwnBase() throws IOException {
        String src = read("src/main/java/org/cardanofoundation/cip113/service/FesProvenanceReconstructor.java");

        assertTrue(src.contains("credentialHash(hop1.get(), \"programmable_logic_base_cred\")"),
                "the reconstruction no longer recovers the deployment base from the record, so a "
                        + "token from another deployment cannot be rebuilt — the caller's base is the "
                        + "only one tried and it is null for a deployment this instance lost.");

        // The recovered base must be USED for the proof, not merely read and logged.
        assertTrue(src.contains("reproducesTransferLogic(b, blacklistPolicy.get(), transferLogicScript)"),
                "the recovered base must still have to reproduce the chain's transfer hash — that "
                        + "comparison is what keeps a hostile record from writing a row.");

        // ⛔ NON-VACUITY: the transfer credential must be persisted, or V33's guard stays blind on
        // every rebuilt row and the 3141 failure comes back undiagnosed.
        assertTrue(src.contains(".moduleTransferStakeAddress(moduleTransferStakeAddress)"),
                "a rebuilt blacklist_init no longer records module_transfer_stake_address, so the "
                        + "transfer cross-check added in V33 is silent for every reconstructed token");
    }

    @Test
    @DisplayName("a 'not found' expires so a record indexed later IS picked up, and a hit is cached forever")
    void negativeLookupsExpireAndPositivesDoNot() throws Exception {
        // ⛔ THE REQUIREMENT, DRIVEN RATHER THAN READ. uplc.link indexes records on a timer, so the
        // first lookup after a registration legitimately misses. The old client memoised that miss
        // for the life of the process, so every retry answered from memory and a record that DID
        // arrive could never be picked up — which made any retry-based reconstruction impossible.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var answer = new java.util.concurrent.atomic.AtomicReference<java.util.Optional<com.fasterxml.jackson.databind.JsonNode>>(
                java.util.Optional.empty());

        // TTL of 1ms: the point is that it EXPIRES, not how long it takes.
        var client = new org.cardanofoundation.cip113.cip171.UplcLinkClient(
                org.springframework.web.reactive.function.client.WebClient.builder(), "http://unused", true, 1000, 1) {
            @Override
            protected java.util.Optional<com.fasterxml.jackson.databind.JsonNode> fetch(String hash) {
                calls.incrementAndGet();
                return answer.get();
            }
        };

        assertTrue(client.byHash("aa").isEmpty(), "nothing published yet");
        assertEquals(1, calls.get(), "the first lookup must actually ask");

        assertTrue(client.byHash("aa").isEmpty());
        assertEquals(1, calls.get(),
                "an immediate retry must be served from the negative cache — otherwise every page "
                        + "load hammers the registry for a record that is not there yet");

        Thread.sleep(5); // past the 1ms TTL

        // The record has now been indexed, which is exactly the case that used to be unreachable.
        answer.set(java.util.Optional.of(recordWith(new String[][] {
                {"blacklist_node_cs", bytesHex(POLICY_ID)}})));

        var found = client.byHash("aa");
        assertTrue(found.isPresent(),
                "after the negative expired the client must re-ask and pick up the record. If this "
                        + "fails, a token registered minutes ago can never be reconstructed.");
        assertEquals(2, calls.get(), "it must have asked a second time");

        assertTrue(client.byHash("aa").isPresent());
        assertEquals(2, calls.get(),
                "a POSITIVE must be cached permanently — a CIP-171 record is immutable, so re-asking "
                        + "forever is pure waste");
    }

    @Test
    @DisplayName("a blank or null hash never reaches the transport")
    void blankHashIsNotFetched() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var client = new org.cardanofoundation.cip113.cip171.UplcLinkClient(
                org.springframework.web.reactive.function.client.WebClient.builder(), "http://unused", true, 1000, 1000) {
            @Override
            protected java.util.Optional<com.fasterxml.jackson.databind.JsonNode> fetch(String hash) {
                calls.incrementAndGet();
                return java.util.Optional.empty();
            }
        };
        assertTrue(client.byHash(null).isEmpty());
        assertTrue(client.byHash("   ").isEmpty());
        assertEquals(0, calls.get(), "neither should have been looked up");
    }
}
