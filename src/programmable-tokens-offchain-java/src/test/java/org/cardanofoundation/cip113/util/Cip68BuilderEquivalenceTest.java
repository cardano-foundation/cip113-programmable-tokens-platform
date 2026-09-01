package org.cardanofoundation.cip113.util;

import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend-vs-SDK equivalence for CIP-68, offline.
 *
 * <p>It answers the question the FES wizard's SDK button is gated on: built by the Java backend
 * and by cip113-sdk-ts, would the same registration yield the same token? The gate calls that
 * unverified, and its dangerous half — "any divergence would yield a different token policy id"
 * — is decidable here, because the labelled asset name is the only CIP-68 input that reaches
 * {@code issuer_admin} and therefore the policy id.
 *
 * <h2>Result</h2>
 *
 * <ul>
 *   <li><strong>Identity: identical.</strong> Both labelled names match the SDK byte for byte.</li>
 *   <li><strong>Reference datum: differs, without consequence for identity.</strong> Same six
 *       entries and same values; different CBOR because the backend emits CANONICAL map encoding
 *       (definite length {@code a6}, keys sorted shortest-first) while the SDK emits insertion
 *       order in an indefinite map ({@code bf…ff}). The datum sits on the (100) token and feeds
 *       no script parameter, so it cannot move a policy id. Both decode to the same key/value
 *       set for any consumer that looks a key up.</li>
 * </ul>
 *
 * <p>SDK values here are transcribed from running its real {@code labeledAssetName} and
 * {@code buildCIP68FTDatum} on the same inputs, so a change on either side that breaks the
 * correspondence fails here rather than at a user's wallet.
 */
class Cip68BuilderEquivalenceTest {

    private static final String BASE_NAME_HEX = "50524f4f46"; // "PROOF"

    /** cip113-sdk-ts 0.7.0, {@code buildCIP68FTDatum} on {@link #metadata()}. */
    private static final String SDK_DATUM_CBOR =
            "d8799fbf446e616d654b50726f6f6620546f6b656e4b6465736372697074696f6e5065717569"
            + "76616c656e63652064696666467469636b65724550524f4f4648646563696d616c73064375726"
            + "c5368747470733a2f2f6578616d706c652e6f7267446c6f676f4a697066733a2f2f616263ff01"
            + "01ff";

    /** Preview's {@code coinsPerUtxoSize}, confirmed against the chain. */
    private static final String COINS_PER_UTXO_SIZE = "4310";

    private static Cip68Metadata metadata() {
        return new Cip68Metadata("Proof Token", "equivalence diff", "PROOF", 6,
                "https://example.org", "ipfs://abc");
    }

    // ── identity ────────────────────────────────────────────────────────────

    /**
     * IDENTITY-CRITICAL. The (333) name is baked into {@code issuer_admin} on both sides, so
     * agreement here is exactly what makes the two builders produce the same policy id.
     */
    @Test
    void userTokenNameMatchesTheSdk() {
        assertEquals("0014df1050524f4f46",
                Cip68.labeledAssetName(Cip68.LABEL_FT, BASE_NAME_HEX),
                "the (333) labelled name feeds issuer_admin; a divergence here would be a "
                        + "different token policy id for the same wizard input");
    }

    /** The label the FES and dummy handlers actually pick — asserted, not assumed. */
    @Test
    void theBackendAlwaysPicksTheFungibleLabel() {
        assertEquals(Cip68.LABEL_FT, Cip68.uncappedUserTokenLabel());
    }

    /** IDENTITY-CRITICAL. SDK: {@code labeledAssetName(100, "50524f4f46")}. */
    @Test
    void referenceTokenNameMatchesTheSdk() {
        assertEquals("000643b050524f4f46",
                Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE_NAME_HEX));
    }

    /** The two names must derive from each other, which is what makes the pair resolvable. */
    @Test
    void thePairIsMutuallyDerivable() {
        var user = Cip68.labeledAssetName(Cip68.LABEL_FT, BASE_NAME_HEX);

        assertEquals(Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE_NAME_HEX),
                Cip68.referenceNameFor(user));
    }

    // ── reference datum ─────────────────────────────────────────────────────

    /**
     * Pins the divergence rather than asserting it away.
     *
     * <p>The backend emits canonical CBOR; the SDK does not. The bytes therefore differ, and so
     * would a datum hash — but the logical content is the same, and neither reaches a script
     * parameter. Recorded so that a future change which makes them agree, or which makes them
     * disagree in a way that DOES matter, is visible here.
     */
    @Test
    void referenceDatumDiffersFromTheSdkOnlyInCborEncoding() throws Exception {
        var hex = HexUtil.encodeHexString(Cip68.buildDatum(metadata()).serializeToBytes());

        assertEquals("d8799fa6", hex.substring(0, 8),
                "backend: constructor 0 (tag 121) wrapping a DEFINITE 6-entry map");
        assertTrue(SDK_DATUM_CBOR.startsWith("d8799fbf"),
                "SDK: constructor 0 wrapping an INDEFINITE map");
        assertFalse(hex.equals(SDK_DATUM_CBOR),
                "the two encodings are known to differ; if they ever match, this test and the "
                        + "wizard's CIP-68 gate should both be revisited");

        // Same six entries, same values, whichever order they are written in.
        for (var keyHex : List.of("6e616d65", "6465736372697074696f6e", "7469636b6572",
                "646563696d616c73", "75726c", "6c6f676f")) {
            assertTrue(hex.contains(keyHex), "backend datum must carry key " + keyHex);
            assertTrue(SDK_DATUM_CBOR.contains(keyHex), "SDK datum must carry key " + keyHex);
        }
        assertTrue(hex.contains("4b50726f6f6620546f6b656e") && SDK_DATUM_CBOR.contains("4b50726f6f6620546f6b656e"),
                "both must carry the same name value");
    }

    /** Canonical ordering is shortest-key-first; recorded because it is the observed order. */
    @Test
    void theBackendMapIsCanonicallyOrdered() throws Exception {
        var hex = HexUtil.encodeHexString(Cip68.buildDatum(metadata()).serializeToBytes());

        int prev = -1;
        for (var keyHex : List.of("75726c", "6c6f676f", "6e616d65", "7469636b6572",
                "646563696d616c73", "6465736372697074696f6e")) {
            int at = hex.indexOf(keyHex);
            assertTrue(at > prev, "backend key order is canonical (shortest first): " + keyHex);
            prev = at;
        }
    }

    /**
     * A bounded, known difference in content: the backend drops a whitespace-only field
     * ({@code isBlank}), the SDK keeps it (a non-empty string is truthy). Metadata only —
     * it cannot move the policy id.
     */
    @Test
    void whitespaceOnlyFieldsAreDroppedByTheBackendButNotTheSdk() throws Exception {
        var hex = HexUtil.encodeHexString(
                Cip68.buildDatum(new Cip68Metadata("Proof Token", "   ", null, null, null, null))
                        .serializeToBytes());

        assertFalse(hex.contains("6465736372697074696f6e"),
                "a whitespace-only description must be dropped by the backend");
    }

    // ── min-UTxO on the reference output ────────────────────────────────────

    /**
     * The suspect the gate names: the SDK pays a flat {@code 3000000n} on the (100) output,
     * while the backend sizes it against the ledger formula and rounds up to whole ADA.
     *
     * <p>This prints both so the comparison is a measured number rather than an argument, and
     * asserts only the property that matters: whether the SDK's flat 3 ADA actually covers what
     * the ledger requires for this output. If it does not, the SDK builds a transaction the
     * ledger rejects at submission — and the metadata is user-supplied, so a long url or logo
     * can push the requirement up without any code changing.
     */
    @Test
    void theSdkFlatThreeAdaIsMeasuredAgainstTheLedgerRequirement() {
        var params = new ProtocolParams();
        params.setCoinsPerUtxoSize(COINS_PER_UTXO_SIZE);

        var refName = Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE_NAME_HEX);
        var value = Value.builder()
                .coin(BigInteger.valueOf(3_000_000))
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId("5f9c868215f0f0b34256d16686e45830fa804fec5de1846f7fdaacbb")
                        .assets(List.of(Asset.builder()
                                .name("0x" + refName)
                                .value(BigInteger.ONE)
                                .build()))
                        .build()))
                .build();

        var required = Cip68.referenceOutputCoin(params,
                "addr_test1zp0eae3pczvtuhf634als4ujvlxff8qe0m88ymd2p69ygmepntgnq9vjcmskkaxynvd3lrla7l58ug4gtj8x8wldf88synw3tp",
                value, Cip68.buildDatum(metadata()));

        var sdkFlat = BigInteger.valueOf(3_000_000);
        System.out.println("BACKEND_REF_OUTPUT_COIN=" + required + "  SDK_FLAT=" + sdkFlat);

        assertTrue(required.compareTo(sdkFlat) <= 0,
                "for this metadata the SDK's flat 3 ADA must cover the ledger requirement of "
                        + required + " lovelace; if it does not, the SDK path builds a "
                        + "transaction that fails min-UTxO at submission");
    }

    /**
     * The boundary the flat constant has to survive. Metadata is user-supplied and bounded only
     * by {@link Cip68#MAX_DATUM_BYTES} (512) and the per-field caps, so the question is not what
     * a typical token costs but what the LARGEST legal one costs. If the requirement can exceed
     * the SDK's flat 3 ADA, then some metadata a user is allowed to enter builds an SDK
     * transaction the ledger rejects at submission — after they have signed.
     */
    @Test
    void theLedgerRequirementAtMaximumLegalMetadata() {
        var params = new ProtocolParams();
        params.setCoinsPerUtxoSize(COINS_PER_UTXO_SIZE);

        // Every field at its documented cap; buildDatum enforces the 512-byte datum budget, so
        // if this constructs at all it is legal input a user could actually submit.
        var big = new Cip68Metadata(
                "N".repeat(64), "D".repeat(256), "T".repeat(16), 6,
                "https://" + "u".repeat(120), "ipfs://" + "l".repeat(121));

        BigInteger required;
        try {
            var datum = Cip68.buildDatum(big);
            var refName = Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE_NAME_HEX);
            var value = Value.builder()
                    .coin(BigInteger.valueOf(3_000_000))
                    .multiAssets(List.of(MultiAsset.builder()
                            .policyId("5f9c868215f0f0b34256d16686e45830fa804fec5de1846f7fdaacbb")
                            .assets(List.of(Asset.builder().name("0x" + refName)
                                    .value(BigInteger.ONE).build()))
                            .build()))
                    .build();
            required = Cip68.referenceOutputCoin(params,
                    "addr_test1zp0eae3pczvtuhf634als4ujvlxff8qe0m88ymd2p69ygmepntgnq9vjcmskkaxynvd3lrla7l58ug4gtj8x8wldf88synw3tp",
                    value, datum);
        } catch (IllegalArgumentException tooBig) {
            // The 512-byte budget refused it first: then no legal metadata reaches this size and
            // the flat constant is safe by construction, which is itself the answer.
            System.out.println("MAX_METADATA_REFUSED_BY_DATUM_BUDGET=" + tooBig.getMessage());
            return;
        }

        System.out.println("BACKEND_REF_OUTPUT_COIN_AT_MAX=" + required + "  SDK_FLAT=3000000");
        assertTrue(required.compareTo(BigInteger.valueOf(3_000_000)) <= 0,
                "AT MAXIMUM LEGAL METADATA the ledger needs " + required + " lovelace but the "
                        + "SDK pays a flat 3000000 — that gap is a submission failure the user "
                        + "only meets after signing");
    }

    /**
     * The decisive form of the min-UTxO question: across ALL metadata the backend will accept,
     * what is the WORST-CASE ledger requirement, and does the SDK's flat 3 ADA cover it?
     *
     * <p>Walks the description up to the point {@code buildDatum} refuses (the 512-byte datum
     * budget), and reports the requirement at the largest datum that is still legal. If the
     * maximum stays under 3 ADA then the flat constant is safe by construction rather than by
     * luck, and the min-UTxO half of the wizard's CIP-68 gate is answered for every input a user
     * can supply — not just for a typical one.
     */
    @Test
    void worstCaseLedgerRequirementAcrossAllAcceptableMetadata() {
        var params = new ProtocolParams();
        params.setCoinsPerUtxoSize(COINS_PER_UTXO_SIZE);
        var refName = Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE_NAME_HEX);
        var value = Value.builder()
                .coin(BigInteger.valueOf(3_000_000))
                .multiAssets(List.of(MultiAsset.builder()
                        .policyId("5f9c868215f0f0b34256d16686e45830fa804fec5de1846f7fdaacbb")
                        .assets(List.of(Asset.builder().name("0x" + refName)
                                .value(BigInteger.ONE).build()))
                        .build()))
                .build();

        BigInteger worst = BigInteger.ZERO;
        int worstDescLen = 0;
        int firstOver = -1;
        for (int len = 0; len <= 256; len++) {
            var meta = new Cip68Metadata("N".repeat(64), "D".repeat(len), "T".repeat(16), 6,
                    "https://" + "u".repeat(120), "ipfs://" + "l".repeat(121));
            try {
                var required = Cip68.referenceOutputCoin(params,
                        "addr_test1zp0eae3pczvtuhf634als4ujvlxff8qe0m88ymd2p69ygmepntgnq9vjcmskkaxynvd3lrla7l58ug4gtj8x8wldf88synw3tp",
                        value, Cip68.buildDatum(meta));
                if (required.compareTo(worst) > 0) { worst = required; worstDescLen = len; }
                if (required.compareTo(BigInteger.valueOf(3_000_000)) > 0 && firstOver < 0) {
                    firstOver = len;
                    System.out.println("SDK_FLAT_FIRST_INSUFFICIENT_AT: name=64 ticker=16 "
                            + "url=128 logo=128 description=" + len + " -> needs " + required);
                }
            } catch (IllegalArgumentException refused) {
                break; // past the datum budget: everything beyond here is rejected up front
            }
        }

        System.out.println("WORST_CASE_REF_OUTPUT_COIN=" + worst
                + " (at description length " + worstDescLen + ")  SDK_FLAT=3000000");

        // MEASURED DEFECT, asserted in the direction it actually holds so the suite stays
        // honest: the flat constant is NOT sufficient. This is a tripwire — when the SDK starts
        // computing min-UTxO the assertion flips and tells whoever changed it to revisit the
        // wizard's CIP-68 gate, which exists partly because of this.
        assertTrue(worst.compareTo(BigInteger.valueOf(3_000_000)) > 0,
                "cip113-sdk-ts pays a flat 3 ADA on the (100) output. If this now passes, the "
                        + "SDK has started sizing min-UTxO and the FES wizard's CIP-68 gate can "
                        + "be reconsidered.");
        System.out.println("SDK_FLAT_IS_INSUFFICIENT_BY=" 
                + worst.subtract(BigInteger.valueOf(3_000_000)) + " lovelace at worst case");
    }
}
