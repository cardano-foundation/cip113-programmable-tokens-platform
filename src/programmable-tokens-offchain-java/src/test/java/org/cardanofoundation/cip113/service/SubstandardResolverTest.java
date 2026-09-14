package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.cip171.UplcLinkClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registered token's substandard is derivable from chain data, with no callback.
 *
 * <p>{@code programmable_token_registry} used to be written only by
 * {@code POST /token-context/register}, so a token minted against one deployment was invisible
 * to every other and a database wipe lost it permanently. {@link SubstandardResolver}
 * reconstructs the answer by recomputing each substandard's transfer logic from inputs the
 * chain already carries and comparing against the hash the registry node reports.
 *
 * <p>No Spring context: {@code SubstandardService} takes only an ObjectMapper and loads the
 * blueprints from the classpath, which is all this needs.
 */
class SubstandardResolverTest {

    /** The committed Preview deployment's programmable-logic-base hash. */
    private static final String PLB = "198ec641705835b5e9664d0c8214a676f121e2b0d5ab8a1ecbc0ed38";

    /** Stands in for a registry node's global-state policy; any 28-byte value exercises the path. */
    private static final String GLOBAL_STATE = "9b7f1dcd3a0cb1d9a0b5b1d4c7e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9";

    private SubstandardService substandardService;
    private SubstandardResolver resolver;
    private FreezeAndSeizeScriptBuilderService fes;
    private KycScriptBuilderService kyc;
    private KycExtendedScriptBuilderService kycExtended;

    @BeforeEach
    void setUp() {
        substandardService = new SubstandardService(new ObjectMapper());
        substandardService.init();
        fes = new FreezeAndSeizeScriptBuilderService(substandardService);
        kyc = new KycScriptBuilderService(substandardService);
        kycExtended = new KycExtendedScriptBuilderService(substandardService);
        resolver = new SubstandardResolver(substandardService, disabledUplcLink(), fes, kyc, kycExtended);
    }

    private static String hashOf(com.bloxbean.cardano.client.plutus.spec.PlutusScript s) {
        try {
            return HexUtil.encodeHexString(s.getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("a freeze-and-seize transfer hash resolves back to freeze-and-seize")
    void resolvesFreezeAndSeize() {
        var observed = hashOf(fes.buildTransferScript(PLB, GLOBAL_STATE));
        assertEquals("freeze-and-seize", resolver.resolve(PLB, GLOBAL_STATE, observed).orElse(null));
    }

    @Test
    @DisplayName("a kyc transfer hash resolves to kyc even though kyc is in substandards.disabled")
    void resolvesKyc() {
        var observed = hashOf(kyc.buildTransferScript(PLB, GLOBAL_STATE));
        assertEquals("kyc", resolver.resolve(PLB, GLOBAL_STATE, observed).orElse(null));
    }

    @Test
    @DisplayName("a kyc-extended transfer hash resolves to kyc-extended")
    void resolvesKycExtended() {
        var observed = hashOf(kycExtended.buildTransferScript(PLB, GLOBAL_STATE));
        assertEquals("kyc-extended", resolver.resolve(PLB, GLOBAL_STATE, observed).orElse(null));
    }

    @Test
    @DisplayName("dummy resolves from its unparameterised blueprint hash, with no global state")
    void resolvesDummy() {
        var dummyTransfer = substandardService
                .getSubstandardValidator("dummy", "transfer.transfer.withdraw")
                .orElseThrow(() -> new AssertionError("dummy transfer validator missing from the blueprint"));
        // Deliberately blank global state: dummy's transfer logic is protocol-global and takes
        // no parameters, so it must resolve without one.
        assertEquals("dummy", resolver.resolve(PLB, "", dummyTransfer.scriptHash()).orElse(null));
    }

    @Test
    @DisplayName("the four substandards produce four DIFFERENT hashes, so a match is not luck")
    void candidatesAreDistinguishable() {
        var f = hashOf(fes.buildTransferScript(PLB, GLOBAL_STATE));
        var k = hashOf(kyc.buildTransferScript(PLB, GLOBAL_STATE));
        var x = hashOf(kycExtended.buildTransferScript(PLB, GLOBAL_STATE));
        var d = substandardService.getSubstandardValidator("dummy", "transfer.transfer.withdraw")
                .orElseThrow().scriptHash();
        assertEquals(4, java.util.Set.of(f, k, x, d).size(),
                "two substandards derive the same transfer hash — resolution would be ambiguous");
    }

    @Test
    @DisplayName("an unrecognised hash resolves to empty rather than to a guess")
    void unknownResolvesEmpty() {
        var notAnySubstandard = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        assertTrue(resolver.resolve(PLB, GLOBAL_STATE, notAnySubstandard).isEmpty());
        assertTrue(resolver.resolve(PLB, GLOBAL_STATE, null).isEmpty());
        assertTrue(resolver.resolve(PLB, GLOBAL_STATE, "").isEmpty());
    }

    @Test
    @DisplayName("the wrong global state does not resolve — the match is sensitive to its inputs")
    void wrongGlobalStateDoesNotResolve() {
        var observed = hashOf(fes.buildTransferScript(PLB, GLOBAL_STATE));
        var otherGlobalState = "0000000000000000000000000000000000000000000000000000cafe";
        assertTrue(resolver.resolve(PLB, otherGlobalState, observed).isEmpty(),
                "resolved against a global state that did not produce the hash");
    }

    // ---------------------------------------------------------------------
    // CIP-171 fallback (uplc.link)
    // ---------------------------------------------------------------------

    /** A client that answers nothing, so the local-derivation tests above stay offline. */
    private static UplcLinkClient disabledUplcLink() {
        return new UplcLinkClient(WebClient.builder(), "", false, 1000);
    }

    /** A client that answers with one canned record, whatever it is asked. */
    private static UplcLinkClient stubbedWith(String json) {
        return new UplcLinkClient(WebClient.builder(), "", false, 1000) {
            @Override
            public Optional<JsonNode> byHash(String scriptHash) {
                try {
                    return Optional.of(new ObjectMapper().readTree(json));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /**
     * Trimmed from a real preview.uplc.link response for the freeze-and-seize transfer logic —
     * the field names are the service's, not invented.
     */
    private static final String REAL_FES_RECORD = """
            {
              "txHash": "2d62d6d7981d30e0a20abc0d9562060db09ea225e6d3722ac7feab1716946f4e",
              "sourceUrl": "https://github.com/cardano-foundation/cip113-programmable-tokens-platform",
              "commitHash": "12637c7c8c7b2bbd08b59100bf7ff714401618da",
              "sourcePath": "src/substandards/freeze-and-seize",
              "compilerType": "AIKEN",
              "compilerVersion": "v1.1.21+42babe5",
              "status": "VERIFIED",
              "scripts": [
                {
                  "scriptName": "example_transfer_logic.transfer",
                  "rawHash": "d95bf16fdd1ce4260100f158d042a91801ca753339d57d97078075c8",
                  "finalHash": "278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d",
                  "parameterizationStatus": "COMPLETE"
                }
              ]
            }
            """;

    @Test
    @DisplayName("freeze-and-seize resolves from a CIP-171 record when local derivation cannot")
    void resolvesFreezeAndSeizeViaCip171() {
        // FES's transfer logic is parameterised on a blacklist policy that appears in neither the
        // registry node nor the registering transaction, so nothing local can reproduce this hash.
        var unreachableLocally = "278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d";
        assertTrue(resolver.resolve(PLB, "", unreachableLocally).isEmpty(),
                "precondition: this must NOT be resolvable without the registry");

        var withRegistry = new SubstandardResolver(
                substandardService, stubbedWith(REAL_FES_RECORD), fes, kyc, kycExtended);
        assertEquals("freeze-and-seize",
                withRegistry.resolve(PLB, "", unreachableLocally).orElse(null));
    }

    @Test
    @DisplayName("a record naming a substandard we do not ship is refused, not trusted")
    void refusesUnknownSubstandardFromRegistry() {
        var hostile = REAL_FES_RECORD.replace(
                "src/substandards/freeze-and-seize", "src/substandards/not-a-substandard-we-have");
        var withRegistry = new SubstandardResolver(
                substandardService, stubbedWith(hostile), fes, kyc, kycExtended);
        assertTrue(withRegistry.resolve(PLB, "", "278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d").isEmpty(),
                "the registry is permissionless; an unrecognised sourcePath must not become a substandard id");
    }

    @Test
    @DisplayName("sourcePath maps to a substandard id, and degenerate paths map to nothing")
    void sourcePathMapping() {
        assertEquals("freeze-and-seize",
                SubstandardResolver.substandardIdFromSourcePath("src/substandards/freeze-and-seize").orElse(null));
        assertEquals("freeze-and-seize",
                SubstandardResolver.substandardIdFromSourcePath("src/substandards/freeze-and-seize/").orElse(null));
        assertEquals("dummy", SubstandardResolver.substandardIdFromSourcePath("dummy").orElse(null));
        assertTrue(SubstandardResolver.substandardIdFromSourcePath("").isEmpty());
        assertTrue(SubstandardResolver.substandardIdFromSourcePath(null).isEmpty());
        // A root-level record (the core protocol) names no substandard.
        assertTrue(SubstandardResolver.substandardIdFromSourcePath("/").isEmpty());
    }
}
