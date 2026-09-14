package org.cardanofoundation.cip113.model.bootstrap;

/** The one-shot upgrade-multisig policy, reward credential, and mutable config UTxO. */
public record UpgradeMultisigParams(String scriptHash, TxInput txInput, TxInput utxo) {
}
