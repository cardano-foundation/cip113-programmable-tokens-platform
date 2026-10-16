package org.cardanofoundation.cip113.service;

import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The retired child-profile payload {d, txHash}. Kept to recognise and recover mints built before
 * CIP-170 ATTEST_TX; new attestations use {@link TxAttestationSeal}.
 */
public final class MintTxHashPayload {
    private MintTxHashPayload() {}

    public static Map<String, Object> signed(String transactionHash) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("d", "");
        payload.put("txHash", normalize(transactionHash));
        return Saider.saidify(payload).sad();
    }

    public static String digest(String transactionHash) {
        return (String) signed(transactionHash).get("d");
    }

    public static String preimage(String transactionHash) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("d", "#".repeat(44));
        payload.put("txHash", normalize(transactionHash));
        return Serder.dumps(payload);
    }

    /** True only for a stored document of exactly this retired shape (keys d, txHash in order). */
    public static boolean isDocument(String documentJson) {
        if (documentJson == null) return false;
        try {
            Map<String, Object> document = new com.fasterxml.jackson.databind.ObjectMapper().readValue(documentJson,
                    new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
            return new java.util.ArrayList<>(document.keySet()).equals(java.util.List.of("d", "txHash"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalize(String transactionHash) {
        if (transactionHash == null || !transactionHash.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Mint transaction hash must be 64 hex characters");
        return transactionHash.toLowerCase(Locale.ROOT);
    }
}
