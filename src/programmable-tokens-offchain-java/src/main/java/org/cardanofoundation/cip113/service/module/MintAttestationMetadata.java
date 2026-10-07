package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.cardanofoundation.cip113.model.Cip170AttestationData;

/** The shared CIP-170 label-170 envelope for an attested mint: ATTEST (v1.0) or ATTEST_TX (v1.1). */
public final class MintAttestationMetadata {
    private MintAttestationMetadata() {}

    static void attach(Tx tx, Cip170AttestationData attestation) {
        if (attestation == null) return;
        tx.attachMetadata(toMetadata(attestation));
    }

    public static Metadata toMetadata(Cip170AttestationData attestation) {
        if (attestation.signerAid() == null || attestation.signerAid().isBlank())
            throw new IllegalArgumentException("incomplete CIP-170 mint attestation");
        MetadataMap version = MetadataBuilder.createMap();
        MetadataMap record = MetadataBuilder.createMap();
        if (attestation.isAttestTx()) {
            if (attestation.digest() != null || attestation.seqNumber() != null)
                throw new IllegalArgumentException("CIP-170 ATTEST_TX carries no digest or sequence number");
            version.put("v", "1.1");
            record.put("t", Cip170AttestationData.ATTEST_TX);
            record.put("i", attestation.signerAid());
            record.put("v", version);
        } else {
            if (attestation.digest() == null || attestation.digest().isBlank()
                    || attestation.seqNumber() == null || attestation.seqNumber().isBlank())
                throw new IllegalArgumentException("incomplete CIP-170 mint attestation");
            version.put("v", attestation.cipVersion() == null ? "1.0" : attestation.cipVersion());
            record.put("t", Cip170AttestationData.ATTEST);
            record.put("i", attestation.signerAid());
            record.put("d", attestation.digest());
            record.put("s", attestation.seqNumber());
            record.put("v", version);
        }
        var metadata = MetadataBuilder.createMetadata();
        metadata.put(170L, record);
        return metadata;
    }
}
