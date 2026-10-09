package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;

/** The CIP-170 AUTH_BEGIN record this platform publishes at label 170. */
public final class Cip170AuthBegin {
    /**
     * Labels the signer will attest. 170 itself announces CIP-170 v1.1 transaction attestations
     * (ATTEST_TX) of attested mints. {@code m} is an indexing aid; authority still comes from the
     * credential chain in {@code c}.
     */
    public static final long[] ATTESTED_LABELS = {170L};

    private Cip170AuthBegin() {}

    public static MetadataMap record(String signerAid, String leafSchemaSaid, MetadataList credentialChunks) {
        MetadataMap record = MetadataBuilder.createMap();
        record.put("t", "AUTH_BEGIN");
        record.put("i", signerAid);
        record.put("s", leafSchemaSaid);
        record.put("c", credentialChunks);

        MetadataMap version = MetadataBuilder.createMap();
        version.put("v", "1.0");
        version.put("k", "KERI10JSON");
        version.put("a", "ACDC10JSON");
        record.put("v", version);

        MetadataList labels = MetadataBuilder.createList();
        for (long label : ATTESTED_LABELS) labels.add(java.math.BigInteger.valueOf(label));
        MetadataMap indexing = MetadataBuilder.createMap();
        indexing.put("l", labels);
        record.put("m", indexing);
        return record;
    }
}
