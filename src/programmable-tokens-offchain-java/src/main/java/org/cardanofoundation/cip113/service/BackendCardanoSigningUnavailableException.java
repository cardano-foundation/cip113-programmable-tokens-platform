package org.cardanofoundation.cip113.service;

/** The optional backend Cardano signer has not been configured. */
public class BackendCardanoSigningUnavailableException extends RuntimeException {
    public BackendCardanoSigningUnavailableException() {
        super("Backend Cardano signing is disabled; KERI_SIGNING_MNEMONIC is not configured");
    }
}
