package org.cardanofoundation.cip113.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * An attempt to move a CIP-68 {@code (100)} reference token, which would destroy its metadata.
 *
 * <p><strong>Why moving it is not just unusual but destructive.</strong> The reference token's
 * inline datum IS the token's metadata — name, description, decimals, everything a wallet shows.
 * The user token derives the reference token's name and reads that datum. A programmable
 * transfer rebuilds its outputs and writes {@code Void} ({@code d87980}) as the datum, so the
 * metadata is not moved, it is ERASED, and the ledger accepts the transaction because no
 * validator asserts a datum must survive.
 *
 * <p>⛔ MEASURED, BY THE SDK SESSION, TWICE ON DEVNET, through the TypeScript path:
 * {@code d8799fbf446e616d65…ff0101ff} became {@code d87980} in txs {@code df4db48f…} and
 * {@code 7ee37fd2…}. The metadata was gone from the live UTxO set and the reference NFT ended
 * up at an address the issuer no longer controlled. Nothing failed anywhere — not the build,
 * the evaluation, the submission, nor any test.
 *
 * <p>⚠ AND THIS BACKEND DID NOT REFUSE IT EITHER. The SDK session reported that the Java path
 * already guarded this; it did not. {@code Cip68.readLabel} existed and nothing on the transfer
 * or seize path called it. The compliment was the reason the gap was looked for.
 *
 * <p>400: the request is well formed and the server is healthy — the operation is simply not
 * one that can be performed on this asset without losing data.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ReferenceTokenNotTransferableException extends IllegalArgumentException {

    public ReferenceTokenNotTransferableException(String message) {
        super(message);
    }
}
