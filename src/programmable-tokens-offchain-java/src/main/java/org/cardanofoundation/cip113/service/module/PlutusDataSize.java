package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

import java.math.BigInteger;

/**
 * The byte length of {@code builtin.serialise_data(d)} — the size the GlobalState logic's
 * {@code max_global_state_datum_bytes} check measures — computed from the DATA, not from any
 * particular CBOR encoding of it.
 *
 * <p>Plutus re-encodes canonically: constructor fields and non-empty lists are
 * INDEFINITE-length, empty ones definite; maps are definite; bytestrings over 64 bytes are
 * chunked into 64-byte pieces; integers outside the 64-bit CBOR range become tag-2/3 bignums
 * over a (chunked if long) bytestring; constructors 0–6 use tags 121–127, 7–127 use
 * 1280–1400, larger ones tag 102 {@code [index, fields]}.
 *
 * <p>Needed because cardano-client keeps the SOURCE encoding of decoded data (a definite list
 * stays definite on re-serialisation) and chunks even short bignums, so its byte count can be
 * off by a byte per list — exactly at the boundary where a cap check matters.
 */
final class PlutusDataSize {
    private PlutusDataSize() {}

    private static final BigInteger CBOR_UINT_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    static int of(PlutusData d) {
        if (d instanceof ConstrPlutusData c) {
            long alt = c.getAlternative();
            int fields = listSize(c.getData());
            if (alt <= 6) return header(121 + alt) + fields;
            if (alt <= 127) return header(1280 + alt - 7) + fields;
            // tag 102 [alternative, fields]
            return header(102) + 1 + header(alt) + fields;
        }
        if (d instanceof ListPlutusData l) return listSize(l);
        if (d instanceof MapPlutusData m) {
            int size = header(m.getMap().size());
            for (var e : m.getMap().entrySet()) size += of(e.getKey()) + of(e.getValue());
            return size;
        }
        if (d instanceof BytesPlutusData b) return bytes(b.getValue().length);
        if (d instanceof BigIntPlutusData i) return integer(i.getValue());
        throw new IllegalArgumentException("unsupported PlutusData " + d.getClass().getSimpleName());
    }

    private static int listSize(ListPlutusData l) {
        var items = l.getPlutusDataList();
        if (items.isEmpty()) return 1;                    // 0x80
        int size = 2;                                     // 0x9f … 0xff
        for (PlutusData item : items) size += of(item);
        return size;
    }

    private static int integer(BigInteger v) {
        BigInteger magnitude = v.signum() >= 0 ? v : v.negate().subtract(BigInteger.ONE);
        if (magnitude.compareTo(CBOR_UINT_MAX) <= 0)
            return magnitude.bitLength() > 32 ? 9 : header(magnitude.longValue());
        int length = (magnitude.bitLength() + 7) / 8;
        return 1 + bytes(length);                         // tag 2/3 (one byte) + bytestring
    }

    private static int bytes(int length) {
        if (length <= 64) return header(length) + length;
        int size = 2;                                     // 0x5f … 0xff
        for (int remaining = length; remaining > 0; remaining -= 64) {
            int chunk = Math.min(64, remaining);
            size += header(chunk) + chunk;
        }
        return size;
    }

    /** CBOR initial byte plus argument bytes for an argument value. */
    static int header(long value) {
        if (value < 24) return 1;
        if (value < 0x100) return 2;
        if (value < 0x10000) return 3;
        if (value < 0x100000000L) return 5;
        return 9;
    }

}
