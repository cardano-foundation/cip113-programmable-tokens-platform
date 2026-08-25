package org.cardanofoundation.cip113.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The CIP-113 core contract surface the backend is written against, asserted field by
 * field against the blueprint it actually loads from the classpath.
 *
 * <h2>Why a hash check is not enough</h2>
 *
 * Every core change re-hashes every validator — the protocol's parameter chaining
 * cascades it: {@code issuance_mint}'s template bytes feed the {@code IssuanceCborHex}
 * datum, {@code registry_mint} is parameterised by that policy, and so on. So "the hashes
 * moved" is the normal state of an upgrade and says nothing about <em>what</em> moved.
 *
 * What actually breaks a hand-written builder is narrower and invisible to a hash:
 *
 * <ul>
 *   <li>a validator appearing or disappearing — at the pinned revision the transfer path
 *       is {@code programmable_logic_global}; upstream has since dissolved it into
 *       {@code transfer} plus {@code third_party};</li>
 *   <li>a parameter changing <em>type</em> at the same position. Upstream's
 *       {@code issuance_mint} parameter 4 goes from {@code plg_stake_cred: Credential} to
 *       {@code params_policy: PolicyId}: same arity, same position, but
 *       {@code Constr1[bytes]} becomes bare bytes. Applied the old way it still produces
 *       a script — a <em>different</em> one, with a different policy id, and the failure
 *       surfaces as an unrelated-looking registry mismatch much later.</li>
 * </ul>
 *
 * So this test pins the family set, each family's purposes, its hash, and its full
 * parameter list as {@code (name, type)} pairs. Swapping the blueprint then fails here,
 * naming the specific thing that moved, instead of producing transactions the chain
 * rejects.
 *
 * <h2>Relationship to the other guards</h2>
 *
 * {@code Cip113CoreUpstreamPinTest} proves the vendored bytes are upstream's. This proves
 * the backend's own expectations still match those bytes. The first catches an
 * unauthorised edit; the second catches an authorised upgrade whose consequences have not
 * been absorbed yet — updating this table is meant to be a deliberate step in an upgrade,
 * with the diff serving as the checklist of what the Java side must follow.
 */
class CoreBlueprintSurfaceTest {

    /** A blueprint parameter: its declared name and its schema type reference. */
    record Param(String name, String type) {}

    /** One script family — all blueprint entries sharing a title prefix and a hash. */
    record Family(String hash, Set<String> purposes, List<Param> params) {}

    private static Map.Entry<String, Family> entry(String name, String hash,
                                                   Set<String> purposes, List<Param> params) {
        return Map.entry(name, new Family(hash, purposes, params));
    }

    /**
     * The surface at the pinned revision, {@code 726d757} (see
     * {@code src/core-contracts/UPSTREAM_PIN.json}).
     */
    /**
     * The surface at the vendored revision, {@code 9db7e06} / {@code 0.5.0-alpha.2} (see
     * {@code src/core-contracts/UPSTREAM_PIN.json}).
     *
     * <p>Two entries are worth reading against the previous revision, because they are the
     * changes that would otherwise have been absorbed silently:
     *
     * <ul>
     *   <li>{@code programmable_logic_global} is gone; {@code transfer} and
     *       {@code third_party} are new. A backend still resolving the old title gets nothing
     *       back.</li>
     *   <li>{@code issuance_mint}'s fourth parameter is now {@code params_policy: PolicyId}
     *       where it was {@code plg_stake_cred: Credential} — same arity, same position,
     *       different encoding. This is the entry that justifies checking parameter TYPES:
     *       applying the old shape produces a valid script with a different policy id, and
     *       nothing downstream says why.</li>
     * </ul>
     */
    private static final Map<String, Family> EXPECTED = Map.ofEntries(
            entry("always_fail", "e9d8d9c7fc531f0b179d502c86bffee829613c537794dab053ae28fe",
                    Set.of("else", "spend"),
                    List.of(new Param("_nonce", "ByteArray"))),
            entry("coordination_spend", "4ee565e02806380747942c2f31f368105386b51349db0dd37cc1cd28",
                    Set.of("else", "spend"),
                    List.of(new Param("_nonce", "ByteArray"))),
            entry("issuance_cbor_hex_mint", "218be633d9b7c3c2cb9b6d3d63e21fa74bca1fed34a8f1c103f29adc",
                    Set.of("else", "mint"),
                    List.of(new Param("utxo_ref", "cardano/transaction/OutputReference"),
                            new Param("always_fail_hash", "ByteArray"))),
            entry("issuance_mint", "aae1ba7cc0b2a50ecc069334124d9c4a97df5f122afb8598353a05fd",
                    Set.of("else", "mint"),
                    List.of(new Param("programmable_logic_base", "cardano/address/Credential"),
                            new Param("registry_node_cs", "cardano/assets/PolicyId"),
                            new Param("minting_logic_cred", "cardano/address/Credential"),
                            new Param("params_policy", "cardano/assets/PolicyId"))),
            entry("programmable_logic_base", "af228b45d78e6f3b12cb8a4a12db8a7f236b130876398c5570c179a6",
                    Set.of("else", "spend"),
                    List.of(new Param("params_policy", "cardano/assets/PolicyId"))),
            entry("protocol_params_mint", "2255479ebfc5ffd42f9e1d96bf09eb5d039a0a53de6a191a8abf39f6",
                    Set.of("else", "mint"),
                    List.of(new Param("utxo_ref", "cardano/transaction/OutputReference"),
                            new Param("coordination_addr_hash", "ByteArray"))),
            entry("registry_mint", "647da5bc02568ac680690908a226d55d7d8f236f4299bcfae1f37e69",
                    Set.of("else", "mint"),
                    List.of(new Param("utxo_ref", "cardano/transaction/OutputReference"),
                            new Param("issuance_cbor_hex_cs", "cardano/assets/PolicyId"),
                            new Param("registry_spend_cred", "cardano/address/Credential"))),
            entry("registry_spend", "3b7a6c0057c40e5cbdb3add6255acca5b716ade651b52ee2304ed7c1",
                    Set.of("else", "spend"),
                    List.of(new Param("protocol_params_cs", "cardano/assets/PolicyId"))),
            entry("third_party", "65f526caca0e1b869c4ff90f31a54482c99dab85011ad4569038a829",
                    Set.of("else", "publish", "withdraw"),
                    List.of(new Param("params_policy", "cardano/assets/PolicyId"))),
            entry("transfer", "a0af5477a21bbbf6dcd8e41a67d25ed45893055b66268f03a25fb18e",
                    Set.of("else", "publish", "withdraw"),
                    List.of(new Param("params_policy", "cardano/assets/PolicyId"))),
            entry("unfracking", "810fcc63f84896de370748f6b1d13958d511178178abadd84a1a0c1e",
                    Set.of("else", "publish", "withdraw"),
                    List.of(new Param("params_policy", "cardano/assets/PolicyId"))),
            entry("upgrade_multisig", "53782c02a189a2a15f9f27f95ae7f863cfeb580a4831a5b7b2244c98",
                    Set.of("else", "withdraw"),
                    List.of(new Param("signers", "List<aiken/crypto/VerificationKeyHash>"),
                            new Param("threshold", "Int")))
    );

    private static final int EXPECTED_ENTRIES = 27;

    @Test
    @DisplayName("core blueprint exposes exactly the validator families the backend expects")
    void surfaceIsUnchanged() throws Exception {
        Map<String, Family> actual = loadSurface();

        List<String> problems = new ArrayList<>();

        for (String name : new TreeMap<>(EXPECTED).keySet()) {
            Family want = EXPECTED.get(name);
            Family got = actual.get(name);
            if (got == null) {
                problems.add("MISSING family '" + name + "' — the backend resolves it by title and "
                        + "would fail at runtime, not here, if this test did not exist.");
                continue;
            }
            if (!want.hash().equals(got.hash())) {
                problems.add("HASH   " + name + ": expected " + want.hash() + ", got " + got.hash());
            }
            if (!want.purposes().equals(got.purposes())) {
                problems.add("PURPOSES " + name + ": expected " + new java.util.TreeSet<>(want.purposes())
                        + ", got " + new java.util.TreeSet<>(got.purposes()));
            }
            if (!want.params().equals(got.params())) {
                problems.add("PARAMS " + name + ":\n    expected " + want.params()
                        + "\n    got      " + got.params()
                        + "\n    (a same-arity type change here silently produces a DIFFERENT script)");
            }
        }
        actual.keySet().stream()
                .filter(k -> !EXPECTED.containsKey(k))
                .sorted()
                .forEach(k -> problems.add("UNEXPECTED family '" + k + "' — new validator upstream; "
                        + "it needs deploying and wiring before this table is updated."));

        if (!problems.isEmpty()) {
            fail("The core blueprint's surface differs from what the backend is written against.\n"
                    + "If this is a deliberate upgrade, work through each line below — the diff IS the\n"
                    + "migration checklist — then update EXPECTED in this test.\n"
                    + "See docs/CORE-UPGRADE-PLAN.md.\n\n"
                    + String.join("\n", problems));
        }
    }

    /**
     * Guards the family-count arithmetic the surface table relies on: families collapse
     * multiple blueprint entries (a withdraw validator also exposes {@code publish} and
     * {@code else}), so a family set that matches while the entry count moves means an
     * entry appeared or vanished inside an existing family.
     */
    @Test
    @DisplayName("core blueprint entry count matches the family table")
    void entryCountIsUnchanged() throws Exception {
        JsonNode root = readBlueprint();
        assertEquals(EXPECTED_ENTRIES, root.get("validators").size(),
                "blueprint entry count moved without the family table changing: an entry was added "
                        + "to or removed from an existing family.");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static JsonNode readBlueprint() throws Exception {
        try (InputStream in = CoreBlueprintSurfaceTest.class.getResourceAsStream("/plutus.json")) {
            assertNotNull(in, "core blueprint /plutus.json is not on the test classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    /** Collapse the blueprint's per-purpose entries into one record per script family. */
    private static Map<String, Family> loadSurface() throws Exception {
        JsonNode root = readBlueprint();
        Map<String, Family> out = new TreeMap<>();
        for (JsonNode v : root.get("validators")) {
            String title = v.get("title").asText();
            String family = title.substring(0, title.indexOf('.'));
            String purpose = title.substring(title.lastIndexOf('.') + 1);

            List<Param> params = new ArrayList<>();
            if (v.has("parameters")) {
                for (JsonNode p : v.get("parameters")) {
                    params.add(new Param(p.get("title").asText(), schemaType(p)));
                }
            }

            Family existing = out.get(family);
            if (existing == null) {
                Set<String> purposes = new java.util.TreeSet<>();
                purposes.add(purpose);
                out.put(family, new Family(v.get("hash").asText(), purposes, params));
            } else {
                existing.purposes().add(purpose);
                // Within a family every entry is the same script, so hash and parameters
                // must agree. If they ever did not, the "family" abstraction this test is
                // built on would be wrong, and silently comparing only the first entry
                // would hide it.
                assertEquals(existing.hash(), v.get("hash").asText(),
                        "blueprint entries in family '" + family + "' disagree on hash");
                assertEquals(existing.params(), params,
                        "blueprint entries in family '" + family + "' disagree on parameters");
            }
        }
        return out;
    }

    /**
     * A parameter's type as the blueprint states it. Aiken writes a JSON-pointer
     * {@code $ref} with {@code /} escaped as {@code ~1}; inline schemas (the primitives)
     * carry {@code dataType} instead. Both are normalised to a readable name so a failure
     * message says {@code cardano/address/Credential}, not a pointer.
     */
    private static String schemaType(JsonNode param) {
        JsonNode schema = param.get("schema");
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            return ref.substring(ref.lastIndexOf('/') + 1).replace("~1", "/");
        }
        if (schema.has("dataType")) {
            return schema.get("dataType").asText();
        }
        return schema.toString();
    }
}
