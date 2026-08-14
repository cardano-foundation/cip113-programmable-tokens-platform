package org.cardanofoundation.cip113.model.bootstrap;

/** Standalone unfracking withdraw-0 validator (v2, registry-gated). */
public record UnfrackingParams(String protocolParamsPolicyId, String scriptHash, String rewardAddress) {
}
