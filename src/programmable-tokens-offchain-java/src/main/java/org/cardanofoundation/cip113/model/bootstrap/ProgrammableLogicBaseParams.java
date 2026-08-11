package org.cardanofoundation.cip113.model.bootstrap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Note: pre-v0.4.0 bootstrap JSONs (e.g. the shipped {@code protocol-bootstraps-preview.json}
 * and {@code protocol-bootstraps-preprod.json}) were written before this record's first
 * component was renamed from {@code programmableLogicGlobalScriptHash} to
 * {@code protocolParamsPolicyId}. They still carry the old key, whose stored value is genuinely
 * the PLG hash, not a protocol-params policy id — the two are not interchangeable. Rather than
 * renaming the key in those legacy files (which would attach the old PLG-hash value to the new,
 * wrong-meaning name), unknown properties are ignored here so those entries keep loading; for
 * legacy entries {@code protocolParamsPolicyId} is therefore {@code null} and callers must not
 * assume it is populated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProgrammableLogicBaseParams(String protocolParamsPolicyId, String scriptHash) {

}
