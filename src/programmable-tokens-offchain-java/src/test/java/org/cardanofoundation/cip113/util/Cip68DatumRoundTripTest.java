package org.cardanofoundation.cip113.util;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link Cip68#parseDatum} against {@link Cip68#buildDatum}, and against the things that are not
 * one of its datums at all.
 *
 * <p>A round trip on its own is a weak test: an encoder and decoder that share a misunderstanding
 * agree with each other perfectly. So the round trips here are paired with datums this code did
 * NOT write — a hand-built map with the keys in a different order, a constr of the wrong
 * alternative, a datum whose bytes are not UTF-8 — because those are the ones a token issued by
 * somebody else will look like.
 */
class Cip68DatumRoundTripTest {

    private static BytesPlutusData text(String s) {
        return BytesPlutusData.of(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyFieldSurvivesTheRoundTrip() {
        var original = new Cip68Metadata(
                "Demo Token", "A preview test token", "DEMO", 6,
                "https://programmabletokens.xyz", "ipfs://QmLogoHash");

        var parsed = Cip68.parseDatum(Cip68.buildDatum(original));

        assertNotNull(parsed);
        assertEquals(original, parsed, "the decoder is meant to be the encoder's exact inverse");
    }

    @Test
    void theOptionalFieldsAreOptionalInBothDirections() {
        // The encoder omits blank values entirely rather than writing empty bytes, so the decoder
        // has to report their absence as null — not as "".
        var sparse = new Cip68Metadata("Bare", null, null, null, null, null);

        var parsed = Cip68.parseDatum(Cip68.buildDatum(sparse));

        assertNotNull(parsed);
        assertEquals("Bare", parsed.name());
        assertNull(parsed.description());
        assertNull(parsed.ticker());
        assertNull(parsed.decimals(), "an absent decimals must stay absent, never become 0");
        assertNull(parsed.url());
        assertNull(parsed.logo());
    }

    @Test
    void zeroDecimalsIsNotTheSameAsNoDecimals() {
        var parsed = Cip68.parseDatum(Cip68.buildDatum(
                new Cip68Metadata("Indivisible", null, "IND", 0, null, null)));

        assertNotNull(parsed);
        assertEquals(0, parsed.decimals(),
                "0 decimals is a real value; collapsing it to null would make an indivisible "
                + "token indistinguishable from one whose decimals were never recorded");
    }

    // ---- datums this code did not write ------------------------------------

    @Test
    void aDatumFromSomebodyElseParsesJustAsWell() {
        // Same shape, keys in a different order, an extra key we know nothing about. All legal:
        // CIP-68 metadata is an open map and nothing fixes the ordering.
        var map = new MapPlutusData();
        map.put(text("ticker"), text("OTHR"));
        map.put(text("publisher"), text("someone else"));
        map.put(text("decimals"), BigIntPlutusData.of(2));
        map.put(text("name"), text("Foreign Token"));

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(map, BigIntPlutusData.of(1), BigIntPlutusData.of(1)))
                .build();

        var parsed = Cip68.parseDatum(datum);

        assertNotNull(parsed, "a well-formed CIP-68 datum must parse regardless of who wrote it");
        assertEquals("Foreign Token", parsed.name());
        assertEquals("OTHR", parsed.ticker());
        assertEquals(2, parsed.decimals());
        assertNull(parsed.description(), "an unknown key must not leak into a known field");
    }

    @Test
    void theWrongConstructorIsNotMetadata() {
        var map = new MapPlutusData();
        map.put(text("name"), text("Not metadata"));
        var datum = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(map))
                .build();

        assertNull(Cip68.parseDatum(datum),
                "a constr of a different alternative is a different datum, not a damaged one");
    }

    @Test
    void aDatumWithNoNameIsNotMetadata() {
        var map = new MapPlutusData();
        map.put(text("ticker"), text("NONE"));
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(map, BigIntPlutusData.of(1), BigIntPlutusData.of(1)))
                .build();

        assertNull(Cip68.parseDatum(datum),
                "a reference datum with no name is far more likely to be a different datum that "
                + "happens to share this shape");
    }

    @Test
    void bytesThatAreNotTextAreOmittedRatherThanMangled() {
        var map = new MapPlutusData();
        map.put(text("name"), text("Readable"));
        // 0xC3 starts a two-byte UTF-8 sequence and then stops: malformed, not merely unusual.
        map.put(text("ticker"), BytesPlutusData.of(new byte[]{(byte) 0xC3}));

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(map, BigIntPlutusData.of(1), BigIntPlutusData.of(1)))
                .build();

        var parsed = Cip68.parseDatum(datum);

        assertNotNull(parsed, "one unreadable field must not discard the readable ones");
        assertEquals("Readable", parsed.name());
        assertNull(parsed.ticker(),
                "malformed bytes must be reported as absent, not decoded into replacement "
                + "characters that look like real content");
    }

    @Test
    void somethingThatIsNotAConstrIsNotMetadata() {
        assertNull(Cip68.parseDatum(BigIntPlutusData.of(42)));
        assertNull(Cip68.parseDatum(text("just bytes")));
        assertNull(Cip68.parseDatum(null));
    }
}
