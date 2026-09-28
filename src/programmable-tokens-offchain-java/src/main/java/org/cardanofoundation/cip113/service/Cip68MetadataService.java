package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.cardanofoundation.cip113.util.Cip68;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * A token's CIP-68 metadata, read back off the chain.
 *
 * <p><b>Why this did not exist.</b> {@link Cip68#buildDatum} has been here since CIP-68 support
 * was added and {@link Cip68#referenceNameFor} derives the reference asset name, but nothing ever
 * read a datum back — the backend stored a {@code cip68Enabled} flag and nothing else. So a
 * token's name, ticker, logo and decimals were write-only: entered once at registration, sent to
 * the chain, and never visible again through this API.
 *
 * <p><b>Absent, unreadable and not-indexed are three different answers</b> and this returns them
 * as such rather than collapsing them to an empty Optional with a shrug:
 *
 * <ul>
 *   <li>the asset name carries no CIP-67 label → this token is not CIP-68 at all;</li>
 *   <li>no UTxO holds the reference token → either it was burned, or the indexer has not reached
 *       it yet, and the caller must not render "no metadata" for the second case;</li>
 *   <li>the UTxO has no inline datum, or one that is not CIP-68 → the reference token exists but
 *       says nothing this can use.</li>
 * </ul>
 *
 * The distinction matters on a page: "this token published no metadata" is a fact about the
 * token, and "we have not indexed it" is a fact about us.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Cip68MetadataService {

    private final UtxoProvider utxoProvider;

    /** Why metadata is unavailable, when it is. */
    public enum Unavailable {
        /** The asset name carries no CIP-67 label, so there is no reference token to look for. */
        NOT_CIP68,
        /** No UTxO holds the reference token — burned, or not indexed yet. */
        REFERENCE_TOKEN_NOT_FOUND,
        /** The reference token is there but carries nothing this can read. */
        NO_READABLE_DATUM,
    }

    public record Result(Cip68Metadata metadata, Unavailable reason) {
        public static Result found(Cip68Metadata m) { return new Result(m, null); }
        public static Result unavailable(Unavailable r) { return new Result(null, r); }
        public boolean isPresent() { return metadata != null; }
    }

    /**
     * @param policyId       the token's policy id
     * @param userAssetNameHex the USER token's asset name hex, CIP-67 label included — the
     *                         reference name is derived from it, never guessed
     */
    public Result read(String policyId, String userAssetNameHex) {
        if (userAssetNameHex == null || !Cip68.hasLabel(userAssetNameHex)) {
            return Result.unavailable(Unavailable.NOT_CIP68);
        }

        String referenceNameHex;
        try {
            referenceNameHex = Cip68.referenceNameFor(userAssetNameHex);
        } catch (IllegalArgumentException e) {
            return Result.unavailable(Unavailable.NOT_CIP68);
        }

        var utxo = utxoProvider.findUtxoByAsset(policyId, referenceNameHex);
        if (utxo.isEmpty()) {
            return Result.unavailable(Unavailable.REFERENCE_TOKEN_NOT_FOUND);
        }

        var inlineDatum = utxo.get().getInlineDatum();
        if (inlineDatum == null || inlineDatum.isBlank()) {
            return Result.unavailable(Unavailable.NO_READABLE_DATUM);
        }

        try {
            PlutusData data = PlutusData.deserialize(HexUtil.decodeHexString(inlineDatum));
            var metadata = Cip68.parseDatum(data);
            return metadata == null
                    ? Result.unavailable(Unavailable.NO_READABLE_DATUM)
                    : Result.found(metadata);
        } catch (Exception e) {
            // A datum that will not deserialise is the token's problem, not a server fault: it is
            // on chain and this is reporting what is there. Logged at debug, reported as
            // unreadable, never thrown.
            log.debug("reference token datum for {} did not parse: {}", policyId, e.toString());
            return Result.unavailable(Unavailable.NO_READABLE_DATUM);
        }
    }
}
