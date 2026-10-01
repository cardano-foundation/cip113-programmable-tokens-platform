package org.cardanofoundation.cip113.offline;

import org.cardanofoundation.cip113.service.ReferenceTokenNotTransferableException;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Moving a CIP-68 {@code (100)} reference token erases its metadata, so it must be refused.
 *
 * <p>The datum on the reference token IS the metadata. A programmable transfer rebuilds outputs
 * with a {@code Void} datum ({@code d87980}), and no validator requires a datum to survive, so
 * the ledger accepts the erasure. Measured by the SDK session twice on devnet through the
 * TypeScript path: the metadata vanished from the live UTxO set and the reference NFT landed at
 * an address its issuer no longer controlled, with nothing failing anywhere.
 *
 * <p>⚠ THIS BACKEND WAS REPORTED TO ALREADY GUARD IT AND DID NOT. {@code Cip68.readLabel}
 * existed; nothing on the transfer or seize path called it. The test therefore pins the
 * REFUSAL, not the primitive — a test that only proved {@code readLabel} works would have
 * passed throughout the period the guard was missing.
 */
class ReferenceTokenGuardTest {

    /** "CIP113" — an ordinary asset name, used as the unlabelled base throughout. */
    private static final String BASE = "434950313133";

    @Test
    @DisplayName("refuses to transfer or seize a (100) reference token")
    void refusesTheReferenceToken() {
        String reference = Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE);

        for (String op : new String[] {"transfer", "seize"}) {
            var e = assertThrows(ReferenceTokenNotTransferableException.class,
                    () -> Cip68.refuseReferenceToken(op, reference),
                    "a (100) reference token must be refused on " + op);
            assertTrue(e.getMessage().contains(op),
                    "the refusal should name the operation; got: " + e.getMessage());
            assertTrue(e.getMessage().contains(reference),
                    "the refusal should name the asset; got: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("allows the user tokens, which are what a holder actually moves")
    void allowsUserTokens() {
        // ⛔ THE HALF THAT STOPS AN OVER-BROAD GUARD. Refusing every labelled asset would block
        // all programmable transfers, which is a worse defect than the one being fixed.
        for (int label : new int[] {Cip68.LABEL_FT, Cip68.LABEL_NFT}) {
            String name = Cip68.labeledAssetName(label, BASE);
            assertDoesNotThrow(() -> Cip68.refuseReferenceToken("transfer", name),
                    "label " + label + " is a USER token and must stay transferable");
        }
    }

    @Test
    @DisplayName("an unlabelled asset name is not a reference token")
    void allowsUnlabelledNames() {
        // Pre-CIP-68 tokens carry no label at all; readLabel returns null and must not refuse.
        assertDoesNotThrow(() -> Cip68.refuseReferenceToken("transfer", BASE));
        assertDoesNotThrow(() -> Cip68.refuseReferenceToken("transfer", ""));
    }

    @Test
    @DisplayName("the label the guard keys on is the one Cip68 builds, not a hardcoded literal")
    void theGuardAndTheBuilderAgree() {
        // ⚑ NON-VACUITY, and the reason it matters: if labeledAssetName's prefix format ever
        // changed, a guard matching a literal "000643b0" would silently stop firing while this
        // test still passed. Deriving both sides from Cip68 keeps them locked together.
        String reference = Cip68.labeledAssetName(Cip68.LABEL_REFERENCE, BASE);
        assertEquals(Integer.valueOf(Cip68.LABEL_REFERENCE), Cip68.readLabel(reference),
                "labeledAssetName/readLabel must round-trip, or this whole test is blind");
        assertThrows(ReferenceTokenNotTransferableException.class,
                () -> Cip68.refuseReferenceToken("transfer", reference));
    }
}
