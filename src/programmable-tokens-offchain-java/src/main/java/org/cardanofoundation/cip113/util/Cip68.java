package org.cardanofoundation.cip113.util;

import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.cardanofoundation.cip113.model.Cip68Metadata;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * CIP-67 asset-name labels and CIP-68 reference-token datums.
 *
 * <h2>Why a reference token at all</h2>
 * CIP-68 splits one logical token into two assets under the <em>same</em> policy: a
 * {@code (100)} reference token of quantity 1 whose inline datum carries the metadata, and a
 * {@code (222)}/{@code (333)} user token carrying the supply. A wallet resolving metadata for
 * the user token derives the reference token's name and reads its datum. Minting only the
 * labelled user token — which is what this platform did before — is worse than not labelling
 * at all: the label advertises metadata that does not exist.
 *
 * <h2>Where the reference token has to live</h2>
 * CIP-113 core's {@code no_escape} requires every output holding a token of the issuance
 * policy to sit at a {@code programmable_logic_base} address with an <em>inline</em> stake
 * credential. The {@code (100)} token is itself a programmable token, so it cannot go to a
 * plain metadata script address the way an ordinary CIP-68 deployment would put it. Callers
 * place it at the admin's PLB base address so the issuer retains the ability to spend it and
 * rewrite the datum later.
 *
 * <h2>Datum shape</h2>
 * {@code Constr 0 [ metadata_map, version, extra ]} with {@code version = 1} and
 * {@code extra = 1}. Keys and text values are byte strings; {@code decimals} is an integer.
 * This is byte-for-byte what {@code @easy1staking/cip113-sdk-ts}'s {@code buildCIP68FTDatum}
 * emits, so the SDK and backend registration paths produce identical reference tokens for the
 * same input — see the round-trip assertion in {@code Cip68Test}.
 */
public final class Cip68 {

    /** CIP-67 label for the reference token that carries the metadata datum. */
    public static final int LABEL_REFERENCE = 100;

    /** CIP-67 label for a non-fungible (quantity 1) user token. */
    public static final int LABEL_NFT = 222;

    /** CIP-67 label for a fungible user token. */
    public static final int LABEL_FT = 333;

    private Cip68() {
    }

    /**
     * The CIP-67 label prefix for {@code label}, as 8 hex characters (4 bytes).
     *
     * <p>Layout is {@code [0000] [label:16] [crc8:8] [0000]}, where the checksum is CRC-8/ATM
     * (polynomial {@code 0x07}, zero init, no reflection) over the two label bytes. The three
     * labels this platform uses come out as {@code 000643b0} (100), {@code 000de140} (222) and
     * {@code 0014df10} (333).
     *
     * @throws IllegalArgumentException if {@code label} does not fit in 16 bits
     */
    public static String labelPrefixHex(int label) {
        if (label < 0 || label > 0xFFFF) {
            throw new IllegalArgumentException("CIP-67 label must fit in 16 bits, got: " + label);
        }
        int high = (label >> 8) & 0xFF;
        int low = label & 0xFF;
        int crc = crc8(new int[]{high, low});
        // Leading and trailing nibbles are the CIP-67 brackets and are always zero.
        return String.format("0%04x%02x0", label, crc);
    }

    /** CRC-8/ATM over {@code bytes}: polynomial 0x07, init 0x00, no reflection, no final xor. */
    private static int crc8(int[] bytes) {
        int crc = 0x00;
        for (int b : bytes) {
            crc ^= b & 0xFF;
            for (int i = 0; i < 8; i++) {
                crc = ((crc & 0x80) != 0) ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    /**
     * Prefix {@code assetNameHex} with the CIP-67 label for {@code label}.
     *
     * @param label        one of {@link #LABEL_REFERENCE}, {@link #LABEL_NFT}, {@link #LABEL_FT}
     * @param assetNameHex the unlabelled asset name, hex, without a {@code 0x} prefix
     */
    public static String labeledAssetName(int label, String assetNameHex) {
        String base = assetNameHex == null ? "" : assetNameHex;
        // A blank base yields a name that is nothing but the 4-byte label. Both readers in this
        // platform — readLabel here and the TypeScript `readLabel` — reject `length <= 8`, so such
        // a token would be permanently unresolvable as a CIP-68 pair: the (100) and the (222)/(333)
        // would each decode as "unlabelled" and neither could derive the other. Refuse at the
        // source rather than mint an asset no CIP-68 consumer can interpret.
        if (base.isBlank()) {
            throw new IllegalArgumentException(
                    "CIP-68 needs a non-empty base asset name: a label-only name is not resolvable "
                    + "as a CIP-68 pair by any consumer, including this platform's own readers.");
        }
        String labeled = labelPrefixHex(label) + base;
        // The ledger caps asset names at 32 bytes. The 4-byte label eats into that budget, so a
        // base name of 29-32 bytes is registrable WITHOUT CIP-68 and not with it. Failing here
        // names the cause; letting it through fails at submission as an opaque serialisation
        // error, after the user has already signed.
        if (labeled.length() > 64) {
            throw new IllegalArgumentException(
                    "asset name is too long for CIP-68: the 4-byte (" + label + ") label plus a "
                    + (base.length() / 2) + "-byte name exceeds the ledger's 32-byte limit. "
                    + "Use a name of at most 28 bytes.");
        }
        return labeled;
    }

    /**
     * Whether {@code assetNameHex} starts with a well-formed CIP-67 label.
     *
     * <p>Checks the CRC, not just the length: an ordinary asset name whose first four bytes
     * happen to look bracketed would otherwise be mistaken for a labelled one and silently
     * truncated.
     */
    public static boolean hasLabel(String assetNameHex) {
        return readLabel(assetNameHex) != null;
    }

    /**
     * The CIP-67 label on {@code assetNameHex}, or null if it carries none.
     */
    public static Integer readLabel(String assetNameHex) {
        if (assetNameHex == null || assetNameHex.length() <= 8) {
            return null;
        }
        String prefix = assetNameHex.substring(0, 8).toLowerCase();
        int label;
        try {
            label = Integer.parseInt(prefix.substring(1, 5), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        // Label 0 encodes as "00000000", so an ordinary asset name beginning with four zero bytes
        // would otherwise be read as labelled and silently truncated by stripLabel. CIP-67 reserves
        // no meaning for label 0 and this platform never mints it, so exclude it.
        if (label == 0) {
            return null;
        }
        // Recomputing the whole prefix validates the brackets and the checksum in one comparison.
        return labelPrefixHex(label).equals(prefix) ? label : null;
    }

    /** {@code assetNameHex} with any CIP-67 label removed; unchanged if it carries none. */
    public static String stripLabel(String assetNameHex) {
        return hasLabel(assetNameHex) ? assetNameHex.substring(8) : assetNameHex;
    }

    /**
     * The {@code (100)} reference-token name paired with a labelled user-token name.
     *
     * @throws IllegalArgumentException if {@code userAssetNameHex} carries no CIP-67 label — a
     *                                  reference token for an unlabelled name would be
     *                                  unresolvable by any CIP-68 consumer
     */
    public static String referenceNameFor(String userAssetNameHex) {
        if (!hasLabel(userAssetNameHex)) {
            throw new IllegalArgumentException(
                    "cannot derive a CIP-68 reference name from the unlabelled asset name '"
                    + userAssetNameHex + "' — the user token must carry a (222)/(333) label");
        }
        return labeledAssetName(LABEL_REFERENCE, stripLabel(userAssetNameHex));
    }

    /**
     * Choose the user-token label from the supply.
     *
     * <h3>Why the second parameter exists</h3>
     * The naive rule — "a supply of exactly 1 is {@code (222)}" — is wrong for this platform,
     * because the quantity minted <em>now</em> is not the same thing as the supply that will ever
     * exist. {@code (222)} asserts non-fungibility: exactly one unit, forever. Nothing in the
     * {@code dummy} or {@code freeze-and-seize} contracts caps lifetime supply — both expose an
     * unconstrained later mint under the same policy and asset name — so registering one unit and
     * labelling it {@code (222)} would let a second {@code (222)} unit be minted the next day,
     * leaving a "non-fungible" token with a supply of two. Off-chain refusal cannot fix that: an
     * operator holding the minting authority can build the transaction without this backend.
     *
     * <p>So the label follows the <em>on-chain</em> cap, not the requested amount:
     * <ul>
     *   <li>{@code lifetimeSupplyCapped == false} — later mints are possible and unbounded, so the
     *       token is fungible whatever it starts at: always {@code (333)}. This is
     *       {@code dummy} and {@code freeze-and-seize}.</li>
     *   <li>{@code lifetimeSupplyCapped == true} — {@code quantity} is a ceiling a validator
     *       enforces, so a ceiling of exactly 1 genuinely is non-fungible: {@code (222)}, else
     *       {@code (333)}. This is {@code security-token}, whose GlobalState
     *       {@code mintable_amount} only ever decreases (see {@code global_state.ak}'s
     *       {@code MintSecurity} branch).</li>
     * </ul>
     *
     * @param quantity            the supply; a lifetime ceiling when {@code lifetimeSupplyCapped},
     *                            otherwise merely the first mint. Must not be negative.
     * @param lifetimeSupplyCapped whether a validator bounds total supply at {@code quantity}
     */
    public static int userTokenLabel(BigInteger quantity, boolean lifetimeSupplyCapped) {
        if (quantity == null) {
            throw new IllegalArgumentException("quantity must not be null when choosing a CIP-67 label");
        }
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative, got: " + quantity);
        }
        if (!lifetimeSupplyCapped) {
            return LABEL_FT;
        }
        return BigInteger.ONE.equals(quantity) ? LABEL_NFT : LABEL_FT;
    }

    /**
     * The label for a token whose supply is <em>not</em> bounded on chain — always {@code (333)}.
     *
     * <p>Shorthand for {@code userTokenLabel(quantity, false)}, kept as a named method so the call
     * sites in {@code dummy} and {@code freeze-and-seize} read as a decision rather than a
     * magic boolean.
     */
    public static int uncappedUserTokenLabel() {
        return LABEL_FT;
    }

    /**
     * The largest serialized {@code (100)} datum this platform will build.
     *
     * <h3>Where the number comes from</h3>
     * The datum is entirely user-supplied text and rides inside a transaction that is already
     * close to the ledger's 16 384-byte limit. The tightest measured path is the security-token
     * mint at <strong>14 923 bytes</strong>, whose datum (the six-field reference fixture)
     * serializes to roughly 220 bytes — leaving 1 461 bytes of headroom. A 512-byte ceiling can
     * therefore grow that transaction by at most ~292 bytes, to ~15 215, keeping well over a
     * kilobyte of slack for fee/change/collateral variation.
     *
     * <p>This is a <em>budget</em>, not the real limit: {@link #preflightTxSize} measures the
     * finished CBOR against the live {@code maxTxSize} and is what actually guarantees
     * submittability. The budget exists so the failure names the metadata — the field the user
     * typed — instead of surfacing as an opaque oversized-transaction error after they have
     * already paid for a blacklist init or a genesis.
     */
    public static final int MAX_DATUM_BYTES = 512;

    /** Per-field ceilings, so an over-budget datum can name the offending field. */
    private static final int MAX_NAME_CHARS = 64;
    private static final int MAX_TICKER_CHARS = 16;
    private static final int MAX_URL_CHARS = 128;
    private static final int MAX_LOGO_CHARS = 128;
    private static final int MAX_DESCRIPTION_CHARS = 256;

    /**
     * Build the inline datum for the {@code (100)} reference token.
     *
     * <p>Optional fields are omitted from the map entirely when null or blank, so a token
     * registered with only a name yields a single-entry map rather than five empty strings.
     *
     * <p>Every field is length-checked and the finished datum is measured against
     * {@link #MAX_DATUM_BYTES}. Oversized metadata is <strong>refused, never truncated</strong>:
     * the datum is the permanent, on-chain statement of what this token is, and silently shipping
     * a half-sentence description would be a worse outcome than an error the user can act on.
     * The check lives here rather than in a DTO annotation because this method is the single
     * choke point every substandard's CIP-68 path passes through.
     *
     * @throws IllegalArgumentException if {@code metadata} is null, its name is blank, any field
     *                                  exceeds its ceiling, or the datum exceeds the budget
     */
    public static PlutusData buildDatum(Cip68Metadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("CIP-68 metadata must not be null");
        }
        if (metadata.name() == null || metadata.name().isBlank()) {
            throw new IllegalArgumentException("CIP-68 metadata requires a non-blank name");
        }
        checkFieldLength("name", metadata.name(), MAX_NAME_CHARS);
        checkFieldLength("description", metadata.description(), MAX_DESCRIPTION_CHARS);
        checkFieldLength("ticker", metadata.ticker(), MAX_TICKER_CHARS);
        checkFieldLength("url", metadata.url(), MAX_URL_CHARS);
        checkFieldLength("logo", metadata.logo(), MAX_LOGO_CHARS);

        var map = new MapPlutusData();
        putText(map, "name", metadata.name());
        putText(map, "description", metadata.description());
        putText(map, "ticker", metadata.ticker());
        if (metadata.decimals() != null) {
            map.put(text("decimals"), BigIntPlutusData.of(metadata.decimals()));
        }
        putText(map, "url", metadata.url());
        putText(map, "logo", metadata.logo());

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        map,
                        BigIntPlutusData.of(1),  // CIP-68 version
                        BigIntPlutusData.of(1))) // extra_plutus_data
                .build();

        int datumBytes = serializedSize(datum);
        if (datumBytes > MAX_DATUM_BYTES) {
            throw new IllegalArgumentException(
                    "CIP-68 metadata is too large: the reference-token datum serializes to "
                    + datumBytes + " bytes, over the " + MAX_DATUM_BYTES + "-byte budget. The "
                    + "datum travels inside a transaction that is already close to the ledger's "
                    + "16384-byte limit. Shorten the metadata — nothing is truncated for you, "
                    + "because the datum is this token's permanent on-chain description.");
        }
        return datum;
    }

    /** The CBOR length of {@code datum}, or a hard failure if it cannot be serialized at all. */
    private static int serializedSize(PlutusData datum) {
        try {
            return datum.serializeToBytes().length;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "CIP-68 metadata could not be serialized to a Plutus datum: " + e.getMessage(), e);
        }
    }

    private static void checkFieldLength(String field, String value, int maxChars) {
        if (value != null && value.length() > maxChars) {
            throw new IllegalArgumentException(
                    "CIP-68 metadata field '" + field + "' is " + value.length()
                    + " characters, over the " + maxChars + "-character limit. The metadata rides "
                    + "on chain inside a transaction bounded at 16384 bytes; shorten it rather "
                    + "than have it silently cut.");
        }
    }

    /**
     * Refuse a finished transaction that the ledger would reject as oversized.
     *
     * <p>{@link #MAX_DATUM_BYTES} bounds the metadata, but the metadata is not the only thing that
     * grew: the CIP-68 branch adds a whole output, its value and its datum, on top of a chain that
     * was already measured at 14 923 bytes on its tightest path. Only the finished CBOR knows the
     * real number, so it is measured here — against the <em>live</em> {@code maxTxSize} rather than
     * a compiled-in 16384, because that parameter is governance-settable and a future increase
     * should relax this check automatically.
     *
     * <p>Called only on the CIP-68 branches, so a transaction that carries no reference token
     * behaves exactly as it did before this check existed.
     *
     * @param context short label naming the path, for the error message
     * @param txCborBytes the serialized transaction
     * @param params live protocol parameters
     * @throws IllegalArgumentException if the transaction is over the limit
     */
    public static void preflightTxSize(String context, byte[] txCborBytes, ProtocolParams params) {
        if (txCborBytes == null) {
            return;
        }
        Long maxTxSize = params == null || params.getMaxTxSize() == null
                ? null : params.getMaxTxSize().longValue();
        if (maxTxSize == null || maxTxSize <= 0) {
            // No live parameter to check against. Saying nothing is better than inventing a limit:
            // a fabricated ceiling is the same class of mistake as a fabricated cost model.
            return;
        }
        if (txCborBytes.length > maxTxSize) {
            throw new IllegalArgumentException(
                    context + ": the transaction is " + txCborBytes.length + " bytes, over the "
                    + "protocol's maxTxSize of " + maxTxSize + ". The CIP-68 reference token adds "
                    + "an output and its metadata datum; shorten the metadata (name, description, "
                    + "ticker, url, logo) and retry. Nothing was truncated.");
        }
    }

    /**
     * The lovelace a {@code (100)} reference-token output needs to clear min-UTxO.
     *
     * <p>Unlike the 1 ADA the plain programmable-token outputs carry, this output's size is
     * driven by user-supplied strings — a long {@code url} or {@code logo} URI can push it well
     * past what a flat constant would cover — so it is sized against the ledger formula
     * ({@code coinsPerUtxoByte * (serSize(txOut) + 160)}) on the real output shape, then rounded
     * up to a whole ADA. Under-sizing here would make the transaction fail at submission with an
     * opaque min-UTxO error rather than anything pointing at the metadata.
     *
     * @param params  live protocol params, for {@code coinsPerUtxoSize}
     * @param address the reference token's destination (the admin's PLB base address)
     * @param value   the output's value, whose coin field is replaced for the sizing pass
     * @param datum   the CIP-68 metadata datum from {@link #buildDatum}
     */
    public static BigInteger referenceOutputCoin(ProtocolParams params,
                                                 String address,
                                                 Value value,
                                                 PlutusData datum) {
        // DUMMY_COIN_VAL keeps the coin field at the same CBOR width as any realistic final
        // value, so the size measured here matches the size of the output actually emitted.
        var candidate = TransactionOutput.builder()
                .address(address)
                .value(value.toBuilder().coin(MinAdaCalculator.DUMMY_COIN_VAL).build())
                .inlineDatum(datum)
                .build();
        var exact = new MinAdaCalculator(params).calculateMinAda(candidate);
        var oneAda = BigInteger.valueOf(1_000_000);
        return exact.add(oneAda).subtract(BigInteger.ONE).divide(oneAda).multiply(oneAda);
    }

    private static void putText(MapPlutusData map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(text(key), text(value));
        }
    }

    private static BytesPlutusData text(String value) {
        return BytesPlutusData.of(value.getBytes(StandardCharsets.UTF_8));
    }
}
