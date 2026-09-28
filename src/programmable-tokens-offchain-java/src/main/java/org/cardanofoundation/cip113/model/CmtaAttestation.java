package org.cardanofoundation.cip113.model;

/** Issuer-controlled CMTA raw Ed25519 attestation. A null issuer key asks the
 * backend to identify the matching trusted key; the backend never signs. */
public record CmtaAttestation(String payloadHex, String signatureHex, String issuerVkeyHex) {}
