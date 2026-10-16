package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The CIP-170 v1.1 transaction seal: the SAID of {@code {d, t: "cardano-tx-attest", n, txHash}}.
 * The KERI wallet anchors it; any verifier rebuilds it from the on-chain transaction ID.
 * Key order, spelling and the integer type of {@code n} are part of the protocol.
 */
public final class TxAttestationSeal {
    public static final String PURPOSE = "cardano-tx-attest";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TxAttestationSeal() {}

    public static Map<String, Object> signed(String transactionHash, long networkMagic) {
        return Saider.saidify(payload("", transactionHash, networkMagic)).sad();
    }

    public static String digest(String transactionHash, long networkMagic) {
        return (String) signed(transactionHash, networkMagic).get("d");
    }

    public static String preimage(String transactionHash, long networkMagic) {
        return Serder.dumps(payload("#".repeat(44), transactionHash, networkMagic));
    }

    /** No fallback: an unknown network must never produce a mainnet seal. */
    public static long magic(String network) {
        if (network == null) throw new IllegalArgumentException("Network is required for a transaction seal");
        return switch (network) {
            case "mainnet" -> 764824073L;
            case "preprod" -> 1L;
            case "preview" -> 2L;
            case "devnet" -> 42L;
            default -> throw new IllegalArgumentException("No network magic for " + network);
        };
    }

    /** True only for a stored document of exactly this seal shape (keys d, t, n, txHash in order). */
    public static boolean isSealDocument(String documentJson) {
        if (documentJson == null) return false;
        try {
            Map<String, Object> document = MAPPER.readValue(documentJson, new TypeReference<LinkedHashMap<String, Object>>() {});
            return new ArrayList<>(document.keySet()).equals(List.of("d", "t", "n", "txHash"))
                    && PURPOSE.equals(document.get("t"));
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, Object> payload(String d, String transactionHash, long networkMagic) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("d", d);
        payload.put("t", PURPOSE);
        payload.put("n", networkMagic);
        payload.put("txHash", normalize(transactionHash));
        return payload;
    }

    private static String normalize(String transactionHash) {
        if (transactionHash == null || !transactionHash.matches("[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Transaction hash must be 64 hex characters");
        return transactionHash.toLowerCase(Locale.ROOT);
    }
}
