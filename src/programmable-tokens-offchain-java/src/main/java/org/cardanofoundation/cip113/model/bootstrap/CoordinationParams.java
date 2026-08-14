package org.cardanofoundation.cip113.model.bootstrap;

/** The spendable home of the protocol-params NFT (v0.4.0 in-place upgradability). */
public record CoordinationParams(String nonce, String scriptHash, String address) {
}
