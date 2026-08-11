package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class RegistryNodeParserTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RegistryNodeParser registryNodeParser = new RegistryNodeParser(OBJECT_MAPPER);

    @Test
    public void testParseRegistryNode() {
        var node = new RegistryNode(
                "0befd1269cf3b5b41cce136c92c64b45dde93e4bfe11875839b713d1",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                Credential.fromScript("eee513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126101"),
                Credential.fromScript("aaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102"),
                Credential.fromScript("def513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126103"),
                Credential.fromKey(""),
                "1234567890abcdef1234567890abcdef1234567890abcdef12345678");

        var inlineDatum = node.toPlutusData().serializeToHex();

        var registryNodeOpt = registryNodeParser.parse(inlineDatum);

        if (registryNodeOpt.isEmpty()) {
            Assertions.fail("Failed to parse registry node");
        }

        var registryNode = registryNodeOpt.get();

        // Whole-node equality: field order (and each Credential's constructor tag)
        // must round-trip exactly, since registry_mint checks re-emission this way
        // (lib/linked_list.ak, is_updated_directory_node).
        Assertions.assertEquals(node, registryNode);
        Assertions.assertEquals("0befd1269cf3b5b41cce136c92c64b45dde93e4bfe11875839b713d1", registryNode.key());
        Assertions.assertEquals("ffffffffffffffffffffffffffffffffffffffffffffffffffffff", registryNode.next());
        Assertions.assertEquals(Credential.fromScript("aaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102"), registryNode.transferLogicScript());
        Assertions.assertEquals(Credential.fromScript("def513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126103"), registryNode.thirdPartyTransferLogicScript());
        Assertions.assertEquals("1234567890abcdef1234567890abcdef1234567890abcdef12345678", registryNode.globalStatePolicyId());
    }

    @Test
    public void testParseSentinelNode() {
        // Sentinel/origin node: empty key, all credentials unset (empty_vkey).
        var node = new RegistryNode(
                "",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                "");

        var registryNodeOpt = registryNodeParser.parse(node.toPlutusData().serializeToHex());

        if (registryNodeOpt.isEmpty()) {
            Assertions.fail("Failed to parse sentinel node");
        }

        var registryNode = registryNodeOpt.get();

        Assertions.assertEquals(node, registryNode);
        Assertions.assertEquals("", registryNode.key());
        Assertions.assertEquals("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", registryNode.next());
    }

    @Test
    public void testParseInvalidDatum() {
        var invalidDatum = "invalid_hex_data";

        var registryNodeOpt = registryNodeParser.parse(invalidDatum);

        // Should return empty on parse failure
        Assertions.assertTrue(registryNodeOpt.isEmpty());
    }

    @Test
    public void testRejectsFiveFieldConstr() {
        // Pre-v0.4.0 shape: key, next, transfer, third_party, global_state (5 fields).
        // Must be rejected outright, not silently accepted with fields shifted into
        // the wrong slots (the bug this parser replaces).
        var legacyDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(""),
                ConstrPlutusData.of(1, BytesPlutusData.of("")),
                ConstrPlutusData.of(1, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        var registryNodeOpt = registryNodeParser.parse(legacyDatum.serializeToHex());

        Assertions.assertTrue(registryNodeOpt.isEmpty(), "5-field constr must be rejected, not silently accepted");
    }

    @Test
    public void testRejectsSixFieldConstr() {
        var sixFieldDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(""),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                ConstrPlutusData.of(0, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        var registryNodeOpt = registryNodeParser.parse(sixFieldDatum.serializeToHex());

        Assertions.assertTrue(registryNodeOpt.isEmpty(), "6-field constr must be rejected, not silently accepted");
    }
}
