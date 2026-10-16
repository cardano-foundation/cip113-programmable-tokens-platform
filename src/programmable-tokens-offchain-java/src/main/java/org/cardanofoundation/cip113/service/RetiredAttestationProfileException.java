package org.cardanofoundation.cip113.service;

/** An unbuilt intent prepared with the retired {d, txHash} child profile. Raised before any Veridian dispatch. */
public class RetiredAttestationProfileException extends RuntimeException {
    public static final String CODE = "RETIRED_ATTESTATION_PROFILE";

    public RetiredAttestationProfileException() {
        super("This attempt was prepared with a retired attestation format; start a new one");
    }
}
