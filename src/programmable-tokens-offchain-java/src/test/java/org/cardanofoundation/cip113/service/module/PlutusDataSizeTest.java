package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Plan Addendum I1: sizes are the canonical {@code serialise_data} length, whatever the source encoding. */
class PlutusDataSizeTest {

    private static PlutusData decode(String hex) throws Exception {
        return PlutusData.deserialize(HexUtil.decodeHexString(hex));
    }

    @Test
    void definiteSourceEncodingIsSizedCanonically() throws Exception {
        // Constr 0 [1] stored DEFINITE (d8 79 81 01, 4 bytes) is d8799f01ff (5 bytes) on chain.
        assertEquals(5, PlutusDataSize.of(decode("d8798101")));
        // A definite list of two ints: 82 01 02 (3) → 9f 01 02 ff (4).
        assertEquals(4, PlutusDataSize.of(decode("820102")));
        // Empty list / empty-field constr stay definite: 80 (1), d87980 (3).
        assertEquals(1, PlutusDataSize.of(ListPlutusData.of()));
        assertEquals(3, PlutusDataSize.of(ConstrPlutusData.of(0)));
    }

    @Test
    void freshlyBuiltDataMatchesCardanoClientWhereThatIsCanonical() throws Exception {
        for (PlutusData d : new PlutusData[]{
                ConstrPlutusData.of(0, BigIntPlutusData.of(1)),
                ConstrPlutusData.of(1, BytesPlutusData.of(new byte[28]), ConstrPlutusData.of(0)),
                ListPlutusData.of(BigIntPlutusData.of(23), BigIntPlutusData.of(24), BigIntPlutusData.of(-25)),
                BytesPlutusData.of(new byte[64]), BytesPlutusData.of(new byte[65]), BytesPlutusData.of(new byte[200]),
                BigIntPlutusData.of(BigInteger.valueOf(Long.MAX_VALUE)),
                BigIntPlutusData.of(new BigInteger("18446744073709551615"))}) {
            assertEquals(d.serializeToBytes().length, PlutusDataSize.of(d), d.serializeToHex());
        }
    }

    @Test
    void edgeEncodings() {
        // 70-byte bytestring: 5f 5840 <64> 46 <6> ff = 75.
        assertEquals(75, PlutusDataSize.of(BytesPlutusData.of(new byte[70])));
        // 2^64 is a bignum: c2 49 <9 bytes> = 11 (cardano-client chunks it to 13 — not canonical).
        assertEquals(11, PlutusDataSize.of(BigIntPlutusData.of(BigInteger.ONE.shiftLeft(64))));
        // Constructor 7 uses tag 1280 (d9 0500), constructor 128 uses tag 102 [128, fields].
        assertEquals(3 + 1, PlutusDataSize.of(ConstrPlutusData.of(7)));
        assertEquals(2 + 1 + 2 + 1, PlutusDataSize.of(ConstrPlutusData.builder().alternative(128)
                .data(ListPlutusData.of()).build()));
        var map = new LinkedHashMap<PlutusData, PlutusData>();
        map.put(BytesPlutusData.of(new byte[32]), ConstrPlutusData.of(0));
        assertEquals(1 + 34 + 3, PlutusDataSize.of(MapPlutusData.builder().map(map).build()));
    }
}
