package org.cardanofoundation.cip113.util;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CIP-67 label encoding and CIP-68 datum construction. Needs no network.
 *
 * <p>The three expected prefixes are the published CIP-67 values, and they are also the ones
 * hardcoded in the frontend's {@code lib/utils/cip68.ts} and computed by the TypeScript SDK's
 * {@code labeledAssetName}. All three implementations must agree or a token registered through
 * one path is invisible to the other.
 */
class Cip68Test {

    @Test
    void labelPrefixesMatchCip67() {
        // Published CIP-67 examples: [0000] [label:16] [crc8:8] [0000].
        assertEquals("000643b0", Cip68.labelPrefixHex(100));
        assertEquals("000de140", Cip68.labelPrefixHex(222));
        assertEquals("0014df10", Cip68.labelPrefixHex(333));
        // (444) is the published CIP-67 rich-fungible label — a fourth independent vector, so
        // this pins the CRC rather than three lucky constants.
        assertEquals("001bc280", Cip68.labelPrefixHex(444));
        // Degenerate ends of the range: CRC-8 of two zero bytes is zero.
        assertEquals("00000000", Cip68.labelPrefixHex(0));
        assertEquals("0ffff240", Cip68.labelPrefixHex(0xFFFF));
    }

    @Test
    void labelPrefixRejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Cip68.labelPrefixHex(-1));
        assertThrows(IllegalArgumentException.class, () -> Cip68.labelPrefixHex(0x10000));
    }

    @Test
    void labeledAssetNamePrefixesTheBaseName() {
        String base = HexUtil.encodeHexString("MYTKN".getBytes(StandardCharsets.UTF_8));
        assertEquals("0014df10" + base, Cip68.labeledAssetName(Cip68.LABEL_FT, base));
        assertEquals("000643b0" + base, Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, base));
        assertEquals("000de140" + base, Cip68.labeledAssetName(Cip68.LABEL_NFT, base));
    }

    @Test
    void labeledAssetNameRejectsNamesThatOverflowTheLedgerLimit() {
        // 28 bytes + the 4-byte label is exactly the ledger's 32-byte maximum.
        String maxBase = "aa".repeat(28);
        assertEquals(64, Cip68.labeledAssetName(Cip68.LABEL_FT, maxBase).length());

        // 29 bytes tips it over. Registrable without CIP-68, not with it — so it must fail here,
        // where the cause is nameable, rather than at submission after the user has signed.
        String tooLong = "aa".repeat(29);
        var e = assertThrows(IllegalArgumentException.class,
                () -> Cip68.labeledAssetName(Cip68.LABEL_FT, tooLong));
        assertTrue(e.getMessage().contains("32-byte"), "error should name the ledger limit: " + e.getMessage());
    }

    @Test
    void labelRoundTripsThroughStripAndRead() {
        String base = HexUtil.encodeHexString("MYTKN".getBytes(StandardCharsets.UTF_8));
        String user = Cip68.labeledAssetName(Cip68.LABEL_FT, base);

        assertTrue(Cip68.hasLabel(user));
        assertEquals(333, Cip68.readLabel(user));
        assertEquals(base, Cip68.stripLabel(user));
        assertEquals(Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, base), Cip68.referenceNameFor(user));

        // An unlabelled name must not be mistaken for a labelled one, and must not silently
        // yield a reference name — a (100) token paired with an unlabelled user token is
        // unresolvable by any CIP-68 consumer.
        assertFalse(Cip68.hasLabel(base));
        assertNull(Cip68.readLabel(base));
        assertEquals(base, Cip68.stripLabel(base));
        assertThrows(IllegalArgumentException.class, () -> Cip68.referenceNameFor(base));
    }

    @Test
    void aValidLookingPrefixWithABadChecksumIsNotALabel() {
        // Same shape as a real label, one bit off in the CRC byte. Length-only detection would
        // strip four bytes off an ordinary asset name here.
        String base = HexUtil.encodeHexString("MYTKN".getBytes(StandardCharsets.UTF_8));
        String bogus = "0014df20" + base; // (333)'s CRC is df, not... see: byte 'f1' -> '20'
        assertFalse(Cip68.hasLabel(bogus), "checksum must be validated, not just the bracket shape");
        assertEquals(bogus, Cip68.stripLabel(bogus));
    }

    @Test
    void userTokenLabelIsNftOnlyForACappedSupplyOfExactlyOne() {
        // Capped: `quantity` is a ceiling a validator enforces, so 1 really is non-fungible.
        assertEquals(Cip68.LABEL_NFT, Cip68.userTokenLabel(BigInteger.ONE, true));
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(BigInteger.ZERO, true));
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(BigInteger.TWO, true));
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(new BigInteger("1000000"), true));
        assertThrows(IllegalArgumentException.class,
                () -> Cip68.userTokenLabel(BigInteger.valueOf(-1), true));
    }

    @Test
    void anUncappedSupplyIsAlwaysFungibleEvenAtOne() {
        // The whole point of the flag. dummy and freeze-and-seize expose an unconstrained later
        // mint under the same policy and name, so registering ONE unit does not make the token
        // non-fungible — a second unit can be minted tomorrow. Labelling it (222) would be a
        // promise the substandard cannot keep, and no off-chain check can keep it either: whoever
        // holds the minting authority can build the transaction without this backend.
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(BigInteger.ONE, false));
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(BigInteger.ZERO, false));
        assertEquals(Cip68.LABEL_FT, Cip68.userTokenLabel(new BigInteger("1000000"), false));
        assertEquals(Cip68.LABEL_FT, Cip68.uncappedUserTokenLabel());
        assertThrows(IllegalArgumentException.class,
                () -> Cip68.userTokenLabel(BigInteger.valueOf(-1), false));
    }

    @Test
    void datumIsConstrZeroWithMapVersionAndExtra() {
        var meta = new Cip68Metadata("My Token", "A description", "MYTKN", 6,
                "https://example.com", "ipfs://logo");
        var datum = (ConstrPlutusData) Cip68.buildDatum(meta);

        assertEquals(0, datum.getAlternative());
        var fields = datum.getData().getPlutusDataList();
        assertEquals(3, fields.size(), "CIP-68 datum is Constr 0 [metadata, version, extra]");
        assertEquals(BigInteger.ONE, ((BigIntPlutusData) fields.get(1)).getValue(), "version");
        assertEquals(BigInteger.ONE, ((BigIntPlutusData) fields.get(2)).getValue(), "extra");

        var map = (MapPlutusData) fields.get(0);
        assertEquals("My Token", textAt(map, "name"));
        assertEquals("A description", textAt(map, "description"));
        assertEquals("MYTKN", textAt(map, "ticker"));
        assertEquals("https://example.com", textAt(map, "url"));
        assertEquals("ipfs://logo", textAt(map, "logo"));
        assertEquals(BigInteger.valueOf(6), intAt(map, "decimals"), "decimals is an integer, not a byte string");
    }

    @Test
    void optionalFieldsAreOmittedRatherThanEmpty() {
        var meta = new Cip68Metadata("Only A Name", null, "  ", null, "", null);
        var datum = (ConstrPlutusData) Cip68.buildDatum(meta);
        var map = (MapPlutusData) datum.getData().getPlutusDataList().get(0);

        assertEquals("Only A Name", textAt(map, "name"));
        assertNull(map.getMap().get(Cip68Datums.key("description")));
        assertNull(map.getMap().get(Cip68Datums.key("ticker")));
        assertNull(map.getMap().get(Cip68Datums.key("url")));
        assertNull(map.getMap().get(Cip68Datums.key("logo")));
        assertNull(map.getMap().get(Cip68Datums.key("decimals")));
        assertEquals(1, map.getMap().size(), "a name-only token yields a single-entry map");
    }

    @Test
    void datumSerializesDeterministically() throws Exception {
        var meta = new Cip68Metadata("My Token", null, "MYTKN", 0, null, null);
        var first = Cip68.buildDatum(meta).serializeToHex();
        var second = Cip68.buildDatum(meta).serializeToHex();
        assertEquals(first, second, "same metadata must produce the same datum bytes");
        assertTrue(first.length() > 0);
    }

    @Test
    void datumRequiresAName() {
        assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(null));
        assertThrows(IllegalArgumentException.class,
                () -> Cip68.buildDatum(new Cip68Metadata(null, null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> Cip68.buildDatum(new Cip68Metadata("   ", null, null, null, null, null)));
    }

    @Test
    void aBlankBaseNameIsRefused() {
        // labeledAssetName used to accept a blank base and return an 8-hex, label-ONLY name. Both
        // readers in this platform reject `length <= 8` (readLabel here, readLabel in the
        // TypeScript SDK), so the resulting (100) and (222)/(333) would each decode as
        // "unlabelled" and neither could derive the other — a permanently unresolvable pair.
        for (String blank : new String[]{null, "", "   "}) {
            assertThrows(IllegalArgumentException.class,
                    () -> Cip68.labeledAssetName(Cip68.LABEL_FT, blank),
                    "blank base name must be refused, got accepted for: '" + blank + "'");
            assertThrows(IllegalArgumentException.class,
                    () -> Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, blank));
        }
        // The would-be output, had it been allowed, is exactly what the readers reject.
        assertNull(Cip68.readLabel("0014df10"), "a label-only name is not readable as labelled");
    }

    @Test
    void oversizedMetadataIsRefusedNotTruncated() {
        // ~2 KB of description against ~1.4 KB of headroom on the tightest measured path. The
        // requirement is a REFUSAL: truncating would silently ship a half-sentence as the token's
        // permanent on-chain description.
        String huge = "x".repeat(2048);
        var meta = new Cip68Metadata("Token", huge, "TK", 6, null, null);
        var thrown = assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(meta));
        assertTrue(thrown.getMessage().contains("description"),
                "the error must name the offending field, got: " + thrown.getMessage());

        // Every free-text field is bounded, not just description.
        assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(
                new Cip68Metadata("n".repeat(200), null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(
                new Cip68Metadata("Token", null, "T".repeat(64), null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(
                new Cip68Metadata("Token", null, null, null, "https://" + "u".repeat(300), null)));
        assertThrows(IllegalArgumentException.class, () -> Cip68.buildDatum(
                new Cip68Metadata("Token", null, null, null, null, "ipfs://" + "l".repeat(300))));
    }

    @Test
    void aFullyPopulatedRealisticDatumFitsTheBudget() throws Exception {
        // The counterpart assertion: the budget must not be so tight that ordinary metadata
        // fails. This is the fixture the offline evaluator harness uses, at its measured size.
        var meta = new Cip68Metadata(
                "Offline Token",
                "A token registered and evaluated entirely offline",
                "OFFTK", 6,
                "https://example.invalid/offline-token",
                "ipfs://bafkreialsoinvalidbutwellformedlookinglogohash");
        int bytes = Cip68.buildDatum(meta).serializeToBytes().length;
        assertTrue(bytes <= Cip68.MAX_DATUM_BYTES,
                "the realistic fixture must fit the budget, was " + bytes);
        assertTrue(bytes > 0);
    }

    @Test
    void txSizePreflightRefusesAnOversizedTransaction() {
        var params = new com.bloxbean.cardano.client.api.model.ProtocolParams();
        params.setMaxTxSize(16384);

        // Under the limit: silent.
        Cip68.preflightTxSize("test", new byte[16384], params);
        // Over it: refused, and the message points at the metadata as the thing to shorten.
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> Cip68.preflightTxSize("test", new byte[16385], params));
        assertTrue(thrown.getMessage().contains("16385"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("maxTxSize"), thrown.getMessage());

        // No live parameter: say nothing rather than invent a ceiling. Fabricating a limit is the
        // same class of mistake as ceilingCostFallback fabricating ex-units.
        Cip68.preflightTxSize("test", new byte[99999], new com.bloxbean.cardano.client.api.model.ProtocolParams());
        Cip68.preflightTxSize("test", null, params);
    }

    private static String textAt(MapPlutusData map, String key) {
        var value = map.getMap().get(Cip68Datums.key(key));
        return new String(((com.bloxbean.cardano.client.plutus.spec.BytesPlutusData) value).getValue(),
                StandardCharsets.UTF_8);
    }

    private static BigInteger intAt(MapPlutusData map, String key) {
        var value = map.getMap().get(Cip68Datums.key(key));
        return ((BigIntPlutusData) value).getValue();
    }

    /** Key builder mirroring {@link Cip68}'s encoding, so lookups hash identically. */
    private static final class Cip68Datums {
        static com.bloxbean.cardano.client.plutus.spec.BytesPlutusData key(String k) {
            return com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(
                    k.getBytes(StandardCharsets.UTF_8));
        }
    }
}
