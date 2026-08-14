package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * WP-3 round-trip coverage for the v0.4.0 {@link RegistryNode} model.
 *
 * <p>The strongest available proof the serializer is right: the origin node's
 * serialized form must be byte-identical to the datum built in
 * {@code PreviewProtocolDeploymentMintTest#originNodeDatum} (replicated below), whose
 * transaction was accepted on the devnet (tx {@code 2209835b…78d75c}, block 6638) — see
 * docs/PLATFORM-V0.4.0-PORT-PLAN.md and docs/deployments/devnet-v0.4.0.json.
 */
class RegistryNodeRoundTripTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final RegistryNodeParser registryNodeParser = new RegistryNodeParser(OBJECT_MAPPER);

    /** registry_node.empty_vkey = VerificationKey(#"") — Credential index 0. Copied verbatim
     *  from PreviewProtocolDeploymentMintTest#emptyVkey(). */
    private static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(""));
    }

    /** registry_node.sentinel_next_key. Copied verbatim from PreviewProtocolDeploymentMintTest. */
    private static final String SENTINEL_NEXT_KEY = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    /**
     * Byte-for-byte copy of {@code PreviewProtocolDeploymentMintTest#originNodeDatum}
     * (known-good: the transaction carrying it was accepted on chain), built independently
     * of {@link RegistryNode#toPlutusData()} so the comparison in
     * {@link #originNodeSerializesByteIdenticalToDeploymentDatum()} is meaningful.
     */
    private static ConstrPlutusData deploymentOriginNodeDatum() {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(""),                                              // 0: key = origin_node_key
                BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),       // 1: next = sentinel
                emptyVkey(),                                                          // 2: minting_logic_script
                emptyVkey(),                                                          // 3: transfer_logic_script
                emptyVkey(),                                                          // 4: third_party_transfer_logic_script
                emptyVkey(),                                                          // 5: unfracking_logic_script (NEW)
                BytesPlutusData.of(""));                                              // 6: global_state_cs
    }

    @Test
    void originNodeSerializesByteIdenticalToDeploymentDatum() {
        var originNode = new RegistryNode(
                "",
                SENTINEL_NEXT_KEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                "");

        String actual = originNode.toPlutusData().serializeToHex();
        String expected = deploymentOriginNodeDatum().serializeToHex();

        Assertions.assertEquals(expected, actual,
                "origin node serialization must be byte-identical to the known-good on-chain deployment datum");
    }

    @Test
    void originNodeRoundTripsThroughParser() {
        var originNode = new RegistryNode(
                "",
                SENTINEL_NEXT_KEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                RegistryNode.EMPTY_VKEY,
                "");

        var parsed = registryNodeParser.parse(deploymentOriginNodeDatum().serializeToHex());

        Assertions.assertTrue(parsed.isPresent(), "known-good on-chain origin node datum must parse");
        Assertions.assertEquals(originNode, parsed.get());
    }

    @Test
    void populatedNodeRoundTripsWithMixedCredentialVariants() {
        var node = new RegistryNode(
                "0befd1269cf3b5b41cce136c92c64b45dde93e4bfe11875839b713d1",
                "aaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126104",
                Credential.fromScript("1111111111111111111111111111111111111111111111111111111a"),
                Credential.fromKey("2222222222222222222222222222222222222222222222222222222b"),
                Credential.fromScript("3333333333333333333333333333333333333333333333333333333c"),
                Credential.fromKey("4444444444444444444444444444444444444444444444444444444d"),
                "5555555555555555555555555555555555555555555555555555555e");

        var parsed = registryNodeParser.parse(node.toPlutusData().serializeToHex());

        Assertions.assertTrue(parsed.isPresent());
        Assertions.assertEquals(node, parsed.get());
        // Distinct Credential variants (Key vs Script) at the same byte length must not collapse
        // to equal nodes — the constructor tag is part of the value.
        Assertions.assertNotEquals(node.mintingLogicScript(), node.transferLogicScript());
    }
}
