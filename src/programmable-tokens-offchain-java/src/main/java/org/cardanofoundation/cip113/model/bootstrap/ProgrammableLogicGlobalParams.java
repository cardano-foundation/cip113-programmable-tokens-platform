package org.cardanofoundation.cip113.model.bootstrap;

/** The deployed dispatcher and the exact unfracking hash it was compiled against. */
public record ProgrammableLogicGlobalParams(String scriptHash, String unfrackingParameter) {
}
