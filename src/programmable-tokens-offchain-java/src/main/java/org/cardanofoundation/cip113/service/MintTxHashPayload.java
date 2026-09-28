package org.cardanofoundation.cip113.service;

import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The public reconstruction rule for a transaction-bound mint attestation.
 * The order and spelling of these two keys are part of the protocol.
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

    private static String normalize(String transactionHash) {
        if (transactionHash == null || !transactionHash.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Mint transaction hash must be 64 hex characters");
        return transactionHash.toLowerCase(Locale.ROOT);
    }
}
