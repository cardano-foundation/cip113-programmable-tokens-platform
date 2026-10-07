package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.service.module.MintAttestationMetadata;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The label-170 record written into an attested mint. */
class MintAttestationMetadataTest {
    private static final String AID = "E" + "a".repeat(43);

    @Test void attestTxNamesOnlyTheSignerAndVersion() {
        var record = (MetadataMap) MintAttestationMetadata.toMetadata(Cip170AttestationData.attestTx(AID))
                .get(BigInteger.valueOf(170));
        assertEquals(List.of("t", "i", "v"), record.keys().stream().map(Object::toString).toList());
        assertEquals("ATTEST_TX", record.get("t"));
        assertEquals(AID, record.get("i"));
        assertEquals("1.1", ((MetadataMap) record.get("v")).get("v"));
    }

    @Test void attestTxRejectsDigestOrSequenceAndMissingSigner() {
        assertThrows(IllegalArgumentException.class, () -> MintAttestationMetadata.toMetadata(
                new Cip170AttestationData(AID, "E" + "b".repeat(43), null, "1.1", Cip170AttestationData.ATTEST_TX)));
        assertThrows(IllegalArgumentException.class, () -> MintAttestationMetadata.toMetadata(
                new Cip170AttestationData(AID, null, "1", "1.1", Cip170AttestationData.ATTEST_TX)));
        assertThrows(IllegalArgumentException.class, () -> MintAttestationMetadata.toMetadata(
                Cip170AttestationData.attestTx(" ")));
    }

    @Test void legacyAttestIsUnchanged() {
        var record = (MetadataMap) MintAttestationMetadata.toMetadata(
                new Cip170AttestationData(AID, "E" + "b".repeat(43), "1a", "1.0")).get(BigInteger.valueOf(170));
        assertEquals(List.of("t", "i", "d", "s", "v"), record.keys().stream().map(Object::toString).toList());
        assertEquals("ATTEST", record.get("t"));
        assertThrows(IllegalArgumentException.class, () -> MintAttestationMetadata.toMetadata(
                new Cip170AttestationData(AID, null, "1a", "1.0")));
    }

    @Test void jsonWithoutTypeStillMeansAttest() throws Exception {
        var parsed = new ObjectMapper().readValue(
                "{\"signerAid\":\"" + AID + "\",\"digest\":\"d\",\"seqNumber\":\"1\",\"cipVersion\":\"1.0\"}",
                Cip170AttestationData.class);
        assertNull(parsed.type());
        assertFalse(parsed.isAttestTx());
    }

    @Test void attestTxJsonRoundTripsWithoutDerivedProperties() throws Exception {
        var mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(Cip170AttestationData.attestTx(AID));
        assertFalse(json.contains("attestTx"));
        assertEquals(Cip170AttestationData.attestTx(AID), mapper.readValue(json, Cip170AttestationData.class));
    }
}
