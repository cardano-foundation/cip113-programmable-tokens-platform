package org.cardanofoundation.cip113.model.bootstrap;

/** A ledger credential as carried by the live protocol-params datum. */
public record CredentialParams(String type, String hash) {
}
