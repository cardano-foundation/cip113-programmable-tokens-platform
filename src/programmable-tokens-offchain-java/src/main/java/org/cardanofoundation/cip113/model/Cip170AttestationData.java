package org.cardanofoundation.cip113.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * CIP-170 label-170 record fields.
 * Populated after the user's Veridian wallet anchors the digest via an interact event.
 *
 * @param signerAid  CESR qb64 AID of the signer (user's KERI identifier)
 * @param digest     CESR qb64 digest of the attested data; null for {@code ATTEST_TX}
 * @param seqNumber  Hex-encoded sequence number of the KERI interact event; null for {@code ATTEST_TX}
 * @param cipVersion CIP-170 version string (e.g. "1.0")
 * @param type       {@code ATTEST} (null means ATTEST) or {@code ATTEST_TX}
 */
public record Cip170AttestationData(
        String signerAid,
        String digest,
        String seqNumber,
        String cipVersion,
        String type) {
    public static final String ATTEST = "ATTEST";
    public static final String ATTEST_TX = "ATTEST_TX";

    public Cip170AttestationData(String signerAid, String digest, String seqNumber, String cipVersion) {
        this(signerAid, digest, seqNumber, cipVersion, null);
    }

    /** CIP-170 v1.1: the record names only the signer; the KEL seals the transaction's own ID. */
    public static Cip170AttestationData attestTx(String signerAid) {
        return new Cip170AttestationData(signerAid, null, null, "1.1", ATTEST_TX);
    }

    @JsonIgnore
    public boolean isAttestTx() { return ATTEST_TX.equals(type); }
}
