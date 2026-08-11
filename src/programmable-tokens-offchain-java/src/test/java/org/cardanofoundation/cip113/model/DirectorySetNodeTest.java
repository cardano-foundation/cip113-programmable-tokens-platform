package org.cardanofoundation.cip113.model;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.onchain.RegistryNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class DirectorySetNodeTest {

    // Origin node shape (lib/registry_node.ak origin_node): key="", next=sentinel,
    // all four credentials = empty_vkey = VerificationKey(#""), global_state_cs="".
    private static final RegistryNode ORIGIN_NODE = new RegistryNode(
            "",
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY,
            "");

    @Test
    public void deserialise() {
        var fooOpt = DirectorySetNode.fromInlineDatum(ORIGIN_NODE.toPlutusData().serializeToHex());
        if (fooOpt.isEmpty()) {
            Assertions.fail("could not deserialise datum");
        }
        Assertions.assertEquals(new DirectorySetNode("",
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY, RegistryNode.EMPTY_VKEY,
                ""), fooOpt.get());
    }

    @Test
    public void rejectsFiveFieldConstr() {
        // pre-v0.4.0 shape: key, next, transfer, third_party, global_state (5 fields)
        var legacyDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(""),
                BytesPlutusData.of(""),
                ConstrPlutusData.of(1, BytesPlutusData.of("")),
                ConstrPlutusData.of(1, BytesPlutusData.of("")),
                BytesPlutusData.of(""));

        var result = DirectorySetNode.fromInlineDatum(legacyDatum.serializeToHex());

        Assertions.assertTrue(result.isEmpty(), "5-field constr must be rejected, not silently accepted");
    }

}