package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class RegistryNodeParser {

    // Retained only so existing call sites that construct this class directly
    // (new RegistryNodeParser(objectMapper)) keep compiling; parsing below
    // walks the deserialized PlutusData graph directly and no longer needs it.
    private final ObjectMapper objectMapper;

    public Optional<RegistryNode> parse(String inlineDatum) {
        try {
            var data = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatum));
            return Optional.of(parseStrict(data));
        } catch (Exception e) {
            // warn, not error: callers scan every UTxO at the directory address and filter on
            // the result, so a pre-v0.4.0 (5-field) node here is an expected "not a match" during
            // any transition period, not an anomaly worth an ERROR-level stack trace per UTxO.
            log.warn("Failed to parse registry node from inline datum: {} ({})", inlineDatum, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses a v0.4.0 RegistryNode datum, failing loudly (throwing) on any shape
     * mismatch rather than defaulting a missing/misplaced field to a plausible
     * value. RegistryNode is a 7-field constructor (lib/registry_node.ak) — a
     * 5- or 6-field constr (the pre-v0.4.0 shape) must be rejected here, not
     * silently accepted with fields shifted into the wrong slots.
     */
    private RegistryNode parseStrict(PlutusData data) {
        if (!(data instanceof ConstrPlutusData constr)) {
            throw new IllegalArgumentException("registry node datum is not a constructor: " + data);
        }
        if (constr.getAlternative() != 0) {
            throw new IllegalArgumentException(
                    "registry node datum has unexpected constructor alternative " + constr.getAlternative());
        }
        List<PlutusData> fields = constr.getData().getPlutusDataList();
        if (fields.size() != 7) {
            throw new IllegalArgumentException(
                    "registry node datum must have exactly 7 fields (v0.4.0 RegistryNode), got " + fields.size());
        }

        String key = bytesHex(fields.get(0), "key");
        String next = bytesHex(fields.get(1), "next");
        Credential mintingLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(2), "minting_logic_script");
        Credential transferLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(3), "transfer_logic_script");
        Credential thirdPartyTransferLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(4), "third_party_transfer_logic_script");
        Credential unfrackingLogicScript = PlutusCredentialCodec.fromPlutusData(fields.get(5), "unfracking_logic_script");
        String globalStatePolicyId = bytesHex(fields.get(6), "global_state_cs");

        return RegistryNode.builder()
                .key(key)
                .next(next)
                .mintingLogicScript(mintingLogicScript)
                .transferLogicScript(transferLogicScript)
                .thirdPartyTransferLogicScript(thirdPartyTransferLogicScript)
                .unfrackingLogicScript(unfrackingLogicScript)
                .globalStatePolicyId(globalStatePolicyId)
                .build();
    }

    private static String bytesHex(PlutusData field, String fieldName) {
        if (!(field instanceof BytesPlutusData bytes)) {
            throw new IllegalArgumentException("registry node field '" + fieldName + "' is not a bytestring: " + field);
        }
        return HexUtil.encodeHexString(bytes.getValue());
    }
}
