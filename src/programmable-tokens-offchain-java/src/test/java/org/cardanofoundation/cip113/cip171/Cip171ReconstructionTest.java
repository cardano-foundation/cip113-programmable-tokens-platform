package org.cardanofoundation.cip113.cip171;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CIP-171 rebuild, proven against REAL registry data rather than a fixture I invented.
 *
 * <p>Every hex string below was measured from preview-api.uplc.link for a real freeze-and-seize
 * token, and both records here are the same registry record — a lookup of the transfer hash and
 * a lookup of the blacklist policy both return transaction {@code 2d62d6d7…}, because the
 * registration's provenance already covers {@code blacklist_mint}. That is why the blacklist
 * init transaction needs no record of its own.
 *
 * <p>What makes this a proof rather than a fixture comparison: the script hashes are recomputed
 * HERE from this repository's own freeze-and-seize blueprint. If the recovered parameters were
 * wrong, or the blueprint were a different revision, the recomputation would not land on the
 * hashes the chain reports.
 */
class Cip171ReconstructionTest {

    /** The deployment's programmable-logic-base, from the committed Preview record. */
    private static final String PLB = "198ec641705835b5e9664d0c8214a676f121e2b0d5ab8a1ecbc0ed38";

    /** What the registry node reports for this token. */
    private static final String OBSERVED_TRANSFER_LOGIC =
            "278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d";

    /** What hop 2 is keyed on, and what hop 1 must yield. */
    private static final String BLACKLIST_POLICY =
            "9a20498043c1031c08f70a4df2fe4e43e33768eb5dfe221546150e32";

    /** Hop 1: by-hash(transfer logic) -> the transfer script and its applied parameters. */
    private static final String HOP1 = """
            {"txHash":"2d62d6d7981d30e0a20abc0d9562060db09ea225e6d3722ac7feab1716946f4e",
             "sourcePath":"src/substandards/freeze-and-seize","status":"VERIFIED",
             "scripts":[{"scriptName":"example_transfer_logic.transfer",
               "finalHash":"278ecd35897748c372e39a6c60210a734813eb6622e8234264692f0d",
               "requiredParameters":[{"title":"programmable_logic_base_cred"},{"title":"blacklist_node_cs"}],
               "providedParameters":[
                 "d87a9f581c198ec641705835b5e9664d0c8214a676f121e2b0d5ab8a1ecbc0ed38ff",
                 "581c9a20498043c1031c08f70a4df2fe4e43e33768eb5dfe221546150e32"]}]}
            """;

    /** Hop 2: by-hash(blacklist policy) -> blacklist_mint and its applied parameters. */
    private static final String HOP2 = """
            {"txHash":"2d62d6d7981d30e0a20abc0d9562060db09ea225e6d3722ac7feab1716946f4e",
             "sourcePath":"src/substandards/freeze-and-seize","status":"VERIFIED",
             "scripts":[{"scriptName":"blacklist_mint",
               "finalHash":"9a20498043c1031c08f70a4df2fe4e43e33768eb5dfe221546150e32",
               "requiredParameters":[{"title":"utxo_ref"},{"title":"manager_pkh"}],
               "providedParameters":[
                 "d8799f5820d70f6d5cfe4536429ac71dd16891280fd621462126ab5ad0f45307ec10e27b8501ff",
                 "581c32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2"]}]}
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private FreezeAndSeizeScriptBuilderService fes;

    @BeforeEach
    void setUp() {
        var substandardService = new SubstandardService(MAPPER);
        substandardService.init();
        fes = new FreezeAndSeizeScriptBuilderService(substandardService);
    }

    @Test
    @DisplayName("hop 1 yields the blacklist policy, and it reproduces the chain's transfer-logic hash")
    void hopOneIsVerifiable() throws Exception {
        var record = MAPPER.readTree(HOP1);

        var blacklist = Cip171Parameters.bytes(record, "blacklist_node_cs").orElseThrow();
        assertEquals(BLACKLIST_POLICY, blacklist);

        // The proof: recompute from OUR blueprint and land on the hash the chain reports.
        var derived = HexUtil.encodeHexString(fes.buildTransferScript(PLB, blacklist).getScriptHash());
        assertEquals(OBSERVED_TRANSFER_LOGIC, derived,
                "the recovered blacklist policy does not reproduce the observed transfer logic");
    }

    @Test
    @DisplayName("hop 2 yields the bootstrap UTxO and admin key, and they reproduce the blacklist policy")
    void hopTwoIsVerifiable() throws Exception {
        var record = MAPPER.readTree(HOP2);

        var utxo = Cip171Parameters.outputReference(record, "utxo_ref").orElseThrow();
        assertEquals("d70f6d5cfe4536429ac71dd16891280fd621462126ab5ad0f45307ec10e27b85",
                utxo.getTransactionId());
        assertEquals(1, utxo.getIndex());

        var adminPkh = Cip171Parameters.bytes(record, "manager_pkh").orElseThrow();
        assertEquals("32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2", adminPkh);

        var derived = HexUtil.encodeHexString(
                fes.buildBlacklistMintScript(utxo, adminPkh).getScriptHash());
        assertEquals(BLACKLIST_POLICY, derived,
                "the recovered utxo_ref and manager_pkh do not reproduce the blacklist policy");
    }

    @Test
    @DisplayName("a tampered parameter fails verification instead of being persisted")
    void tamperedRecordIsRefused() throws Exception {
        // One nibble changed in the blacklist policy the record claims.
        var hostile = HOP1.replace("581c9a20498043c1", "581c9a20498043c2");
        var blacklist = Cip171Parameters.bytes(MAPPER.readTree(hostile), "blacklist_node_cs").orElseThrow();

        var derived = HexUtil.encodeHexString(fes.buildTransferScript(PLB, blacklist).getScriptHash());
        assertTrue(!OBSERVED_TRANSFER_LOGIC.equals(derived),
                "a tampered blacklist policy still reproduced the observed hash — the check is vacuous");
    }

    @Test
    @DisplayName("parameters are read by title, so a reordered record still resolves")
    void readsByTitleNotPosition() throws Exception {
        var swapped = """
                {"scripts":[{"scriptName":"blacklist_mint",
                  "requiredParameters":[{"title":"manager_pkh"},{"title":"utxo_ref"}],
                  "providedParameters":[
                    "581c32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2",
                    "d8799f5820d70f6d5cfe4536429ac71dd16891280fd621462126ab5ad0f45307ec10e27b8501ff"]}]}
                """;
        var record = MAPPER.readTree(swapped);
        assertEquals("32e7e00eae28502a2aa271cf4202b1b01b94ca8efe642e380c93d5e2",
                Cip171Parameters.bytes(record, "manager_pkh").orElseThrow());
        assertEquals(1, Cip171Parameters.outputReference(record, "utxo_ref").orElseThrow().getIndex());
    }
}
