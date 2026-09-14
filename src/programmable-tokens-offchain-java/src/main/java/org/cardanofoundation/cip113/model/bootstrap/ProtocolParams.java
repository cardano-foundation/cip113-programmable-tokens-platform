package org.cardanofoundation.cip113.model.bootstrap;

/** The merged protocol-params mint/spend validator: one hash is policy and address. */
public record ProtocolParams(TxInput txInput, String policyId, TxInput utxo) {
}
