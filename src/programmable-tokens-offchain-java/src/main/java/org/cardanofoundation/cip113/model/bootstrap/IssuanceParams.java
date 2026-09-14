package org.cardanofoundation.cip113.model.bootstrap;

public record IssuanceParams(TxInput txInput, String policyId, String alwaysFailScriptHash) {
}
