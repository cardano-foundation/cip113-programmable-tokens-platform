package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.Builder;

@Builder(toBuilder = true)
public record RegistryNode(String key,
                           String next,
                           String transferLogicScript,
                           String thirdPartyTransferLogicScript,
                           String globalStatePolicyId) {

    public PlutusData toPlutusData() {

        if (key == null || key.isBlank()) {
            return ConstrPlutusData.of(0,
                    BytesPlutusData.of(""),
                    BytesPlutusData.of(HexUtil.decodeHexString(next)),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    ConstrPlutusData.of(0, BytesPlutusData.of("")),
                    BytesPlutusData.of(""));
        } else {
            return ConstrPlutusData.of(0,
                    BytesPlutusData.of(HexUtil.decodeHexString(key)),
                    BytesPlutusData.of(HexUtil.decodeHexString(next)),
                    ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(transferLogicScript))),
                    ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(thirdPartyTransferLogicScript))),
                    BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)));
        }


    }

}
