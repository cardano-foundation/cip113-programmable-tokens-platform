package org.cardanofoundation.cip113.model.bootstrap;

/** The merged registry mint/spend validator: one hash is policy and address. */
public record RegistryParams(TxInput txInput, String issuanceScriptHash, String scriptHash) {
}
