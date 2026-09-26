package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.Tx;
import org.cardanofoundation.cip113.model.Cip170AttestationData;

/** The shared CIP-170 ATTEST envelope for an anchored admin mint. */
final class MintAttestationMetadata {
    private MintAttestationMetadata() {}

    static void attach(Tx tx, Cip170AttestationData attestation) {
        if (attestation == null) return;
        if (attestation.signerAid() == null || attestation.signerAid().isBlank()
                || attestation.digest() == null || attestation.digest().isBlank()
                || attestation.seqNumber() == null || attestation.seqNumber().isBlank()) {
            throw new IllegalArgumentException("incomplete CIP-170 mint attestation");
        }
        MetadataMap version = MetadataBuilder.createMap();
        version.put("v", attestation.cipVersion() == null ? "1.0" : attestation.cipVersion());
        MetadataMap attest = MetadataBuilder.createMap();
        attest.put("t", "ATTEST");
        attest.put("i", attestation.signerAid());
        attest.put("d", attestation.digest());
        attest.put("s", attestation.seqNumber());
        attest.put("v", version);
        var metadata = MetadataBuilder.createMetadata();
        metadata.put(170L, attest);
        tx.attachMetadata(metadata);
    }
}
