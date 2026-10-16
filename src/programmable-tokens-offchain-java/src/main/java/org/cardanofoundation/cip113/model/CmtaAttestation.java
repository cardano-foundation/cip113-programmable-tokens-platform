package org.cardanofoundation.cip113.model;

/**
 * An issuer's CIP-30 {@code signData} result over a 67-byte CMTA KYC claim.
 *
 * <p>{@code payloadHex} is the claim itself (never the COSE payload, which in CIP-8 hashed mode is
 * only its Blake2b-224). {@code coseSign1Hex} and {@code coseKeyHex} are the wallet's
 * {@code signature} and {@code key}. The backend never signs.
 *
 * <p>{@code signatureHex} is kept ONLY to recognise the retired raw-Ed25519 format and refuse it
 * by name: the PR #29 contract accepts a COSE {@code Sig_structure} signature and nothing else.
 */
public record CmtaAttestation(String payloadHex, String coseSign1Hex, String coseKeyHex, String signatureHex) {

    public CmtaAttestation(String payloadHex, String coseSign1Hex, String coseKeyHex) {
        this(payloadHex, coseSign1Hex, coseKeyHex, null);
    }
}
