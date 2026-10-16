package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import org.cardanofoundation.cip113.service.Cip170AuthBegin;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The AUTH_BEGIN record announces label 170 so indexers can find this signer's ATTEST_TX records. */
class Cip170AuthBeginTest {
    @Test void announcesLabel170AndKeepsTheChainFields() throws Exception {
        MetadataList chunks = MetadataBuilder.createList();
        chunks.add(new byte[]{1, 2, 3});
        var metadata = MetadataBuilder.createMetadata();
        metadata.put(170L, Cip170AuthBegin.record("E" + "a".repeat(43), "E" + "b".repeat(43), chunks));
        // Read back what goes on chain, not the builder's in-memory map.
        var onChain = com.bloxbean.cardano.client.metadata.cbor.CBORMetadata.deserialize(metadata.serialize());
        var record = (MetadataMap) onChain.get(BigInteger.valueOf(170));

        assertEquals("AUTH_BEGIN", record.get("t"));
        assertEquals("E" + "a".repeat(43), record.get("i"));
        assertEquals("E" + "b".repeat(43), record.get("s"));
        assertEquals("1.0", ((MetadataMap) record.get("v")).get("v"));
        var labels = (MetadataList) ((MetadataMap) record.get("m")).get("l");
        assertEquals(List.of(BigInteger.valueOf(170)), List.of(labels.getValueAt(0)));
        assertEquals(1, labels.size());
    }
}
