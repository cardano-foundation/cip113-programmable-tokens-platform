package org.cardanofoundation.cip113.model;

/**
 * CIP-170 ATTEST metadata fields.
 * Populated after the user's Veridian wallet anchors the digest via an interact event.
 *
 * @param signerAid  CESR qb64 AID of the signer (user's KERI identifier)
 * @param digest     CESR qb64 digest of the attested data (unit + quantity)
 * @param seqNumber  Hex-encoded sequence number of the KERI interact event
 * @param cipVersion CIP-170 version string (e.g. "1.0")
 */
public record Cip170AttestationData(
        String signerAid,
        String digest,
        String seqNumber,
        String cipVersion) {
}
