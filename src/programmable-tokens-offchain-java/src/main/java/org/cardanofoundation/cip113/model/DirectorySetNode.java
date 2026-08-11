package org.cardanofoundation.cip113.model;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.onchain.PlutusCredentialCodec;

import java.util.List;
import java.util.Optional;

/**
 * Second, duplicate parser for the on-chain {@code RegistryNode} datum (see
 * {@link org.cardanofoundation.cip113.model.onchain.RegistryNodeParser} for the primary
 * one). Kept as its own type deliberately — not merged in WP-3 to limit blast radius —
 * but fixed for the same v0.4.0 7-field shape and the same "fail loudly on the wrong
 * arity" requirement.
 */
@Slf4j
public record DirectorySetNode(String key,
                                String next,
                                Credential mintingLogicScript,
                                Credential transferLogicScript,
                                Credential thirdPartyTransferLogicScript,
                                Credential unfrackingLogicScript,
                                String globalStateCs) {

    public static Optional<DirectorySetNode> fromInlineDatum(String inlineDatum) {
        try {
            var plutusData = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatum));
            return Optional.of(parseStrict(plutusData));
        } catch (Exception e) {
            // warn, not error — see RegistryNodeParser.parse for why (same scan-and-filter usage).
            log.warn("Failed to parse directory set node from inline datum: {} ({})", inlineDatum, e.getMessage());
            return Optional.empty();
        }
    }

    private static DirectorySetNode parseStrict(PlutusData data) {
        if (!(data instanceof ConstrPlutusData constr)) {
            throw new IllegalArgumentException("directory set node datum is not a constructor: " + data);
        }
        if (constr.getAlternative() != 0) {
            throw new IllegalArgumentException(
                    "directory set node datum has unexpected constructor alternative " + constr.getAlternative());
        }
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        if (fields.size() != 7) {
            throw new IllegalArgumentException(
                    "directory set node datum must have exactly 7 fields (v0.4.0 RegistryNode), got " + fields.size());
        }

        String key = bytesHex(fields.get(0), "key");
        String next = bytesHex(fields.get(1), "next");
        Credential mintingLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(2), "minting_logic_script");
        Credential transferLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(3), "transfer_logic_script");
        Credential thirdPartyTransferLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(4), "third_party_transfer_logic_script");
        Credential unfrackingLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(5), "unfracking_logic_script");
        String globalStateCs = bytesHex(fields.get(6), "global_state_cs");

        return new DirectorySetNode(key, next, mintingLogicScript, transferLogicScript,
                thirdPartyTransferLogicScript, unfrackingLogicScript, globalStateCs);
    }

    private static String bytesHex(PlutusData field, String fieldName) {
        if (!(field instanceof BytesPlutusData bytes)) {
            throw new IllegalArgumentException("directory set node field '" + fieldName + "' is not a bytestring: " + field);
        }
        return HexUtil.encodeHexString(bytes.getValue());
    }
}
