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
     * Choose the user-token label from the requested supply.
     *
     * <p>A supply of exactly one is a non-fungible token and takes {@code (222)}; anything else
     * is fungible and takes {@code (333)}. A supply of zero — a security-token registration,
     * which is structurally mint-free — is fungible, because the tokens that will eventually be
     * minted against the cap are.
     *
     * @param quantity the supply the caller asked for; must not be negative
     */
    public static int userTokenLabel(BigInteger quantity) {
        if (quantity == null) {
            throw new IllegalArgumentException("quantity must not be null when choosing a CIP-67 label");
        }
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative, got: " + quantity);
        }
        return BigInteger.ONE.equals(quantity) ? LABEL_NFT : LABEL_FT;
    }

    /** {@link #userTokenLabel(BigInteger)} over a decimal string. */
    public static int userTokenLabel(String quantity) {
        return userTokenLabel(new BigInteger(quantity == null || quantity.isBlank() ? "0" : quantity.trim()));
    }

    /**
     * Build the inline datum for the {@code (100)} reference token.
     *
     * <p>Optional fields are omitted from the map entirely when null or blank, so a token
     * registered with only a name yields a single-entry map rather than five empty strings.
     *
     * @throws IllegalArgumentException if {@code metadata} is null or its name is blank
     */
    public static PlutusData buildDatum(Cip68Metadata metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("CIP-68 metadata must not be null");
        }
        if (metadata.name() == null || metadata.name().isBlank()) {
            throw new IllegalArgumentException("CIP-68 metadata requires a non-blank name");
        }

        var map = new MapPlutusData();
        putText(map, "name", metadata.name());
        putText(map, "description", metadata.description());
        putText(map, "ticker", metadata.ticker());
        if (metadata.decimals() != null) {
            map.put(text("decimals"), BigIntPlutusData.of(metadata.decimals()));
        }
        putText(map, "url", metadata.url());
        putText(map, "logo", metadata.logo());

        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        map,
                        BigIntPlutusData.of(1),  // CIP-68 version
                        BigIntPlutusData.of(1))) // extra_plutus_data
                .build();
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
