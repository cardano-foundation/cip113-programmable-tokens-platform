package org.cardanofoundation.cip113.cip171;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Reads parameters out of a CIP-171 record.
 *
 * <h2>Why by title and not by index</h2>
 *
 * A record's scripts carry {@code requiredParameters} (each with a {@code title} taken from the
 * blueprint) alongside {@code providedParameters}, in the same order. So a parameter can be
 * found by NAME — {@code blacklist_node_cs}, {@code utxo_ref}, {@code manager_pkh} — rather than
 * by position. That matters: positional reads of a third party's data are the kind of thing
 * that keeps working until a validator gains a parameter, and then silently returns the wrong
 * field. The record describes itself; this reads what it says.
 */
public final class Cip171Parameters {

    private Cip171Parameters() {
    }

    /** The hex-encoded PlutusData applied as {@code title}, on any script of this record. */
    public static Optional<String> findByTitle(JsonNode record, String title) {
        if (record == null) return Optional.empty();
        for (JsonNode script : record.path("scripts")) {
            JsonNode required = script.path("requiredParameters");
            JsonNode provided = script.path("providedParameters");
            for (int i = 0; i < required.size() && i < provided.size(); i++) {
                if (title.equals(required.get(i).path("title").asText())) {
                    var hex = provided.get(i).asText();
                    return hex == null || hex.isBlank() ? Optional.empty() : Optional.of(hex);
                }
            }
        }
        return Optional.empty();
    }

    /** A parameter that is a plain byte string — a policy id or a key hash. */
    public static Optional<String> bytes(JsonNode record, String title) {
        return findByTitle(record, title).flatMap(hex -> {
            try {
                if (PlutusData.deserialize(HexUtil.decodeHexString(hex)) instanceof BytesPlutusData b) {
                    return Optional.of(HexUtil.encodeHexString(b.getValue()));
                }
            } catch (Exception ignored) {
                // Not decodable is "absent", not an error: the record is third-party data.
            }
            return Optional.empty();
        });
    }

    /** A parameter that is an {@code OutputReference}: {@code Constr 0 [bytes32, int]}. */
    public static Optional<TransactionInput> outputReference(JsonNode record, String title) {
        return findByTitle(record, title).flatMap(hex -> {
            try {
                if (PlutusData.deserialize(HexUtil.decodeHexString(hex)) instanceof ConstrPlutusData c
                        && c.getAlternative() == 0) {
                    var fields = c.getData().getPlutusDataList();
                    if (fields.size() == 2
                            && fields.get(0) instanceof BytesPlutusData txHash
                            && fields.get(1) instanceof BigIntPlutusData index) {
                        return Optional.of(new TransactionInput(
                                HexUtil.encodeHexString(txHash.getValue()),
                                index.getValue().intValue()));
                    }
                }
            } catch (Exception ignored) {
                // As above.
            }
            return Optional.empty();
        });
    }
}
