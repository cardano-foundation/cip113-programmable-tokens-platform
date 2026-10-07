package org.cardanofoundation.cip113.offline;

import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T-101 — a power user can be added anywhere in the list, not only first.
 *
 * <h2>What was broken, and why it mattered to a rotation</h2>
 *
 * {@code buildAddPowerUserTransaction} always handed the validator the list ROOT as the anchor. The
 * validator never required that — it derives the anchor from the input it is spending — so
 * inserting anywhere was always legal on chain and the limit was entirely off-chain. It worked for
 * exactly one insertion.
 *
 * <p>That is a dead end for an admin rotation: mint, burn and pause each check the CALLER'S OWN
 * power-user node as a reference input on chain, so a rotated-in admin needs a node, and on any
 * token that already had one power user the transaction to give them one could not be built.
 *
 * <h2>⛔ The failure the old code would have produced is worse than a refusal</h2>
 *
 * It also hardcoded the new node's link to {@code None}, which is correct only when the anchor is
 * the tail. Inserting in the MIDDLE that way points the new node at nothing, so every element after
 * the anchor is dropped from the chain while its NFT stays on chain — a silently truncated
 * authority list, not an error. The splice is therefore the other half of this ticket, and the
 * ordering rule below is what decides where it happens.
 *
 * <p>⚠ Scope of this test: the ORDERING DECISION, which is what was wrong. Building and submitting
 * a second insertion end to end is T-102's preprod run — the offline harness would need a
 * UTxO-provider extension to serve the list walk, and a transaction nobody submits proves less
 * than a real one.
 */
class PowerUserInsertionTest {

    /** The root sorts below everything; its key is the empty string. */
    private static final String ROOT = "";
    private static final String K10 = "10".repeat(28);
    private static final String K50 = "50".repeat(28);
    private static final String K90 = "90".repeat(28);

    private static int covering(List<String> list, String newKey) {
        return RwaTokenModuleHandler.coveringIndex(list, newKey);
    }

    @Test
    @DisplayName("an empty list: the root covers everything — the case that used to be the only one")
    void emptyListAnchorsOnTheRoot() {
        assertEquals(0, covering(List.of(ROOT), K50));
    }

    @Test
    @DisplayName("a key below every existing one anchors on the ROOT, not on the first node")
    void lowestKeyAnchorsOnRoot() {
        // ⛔ THE INSERTION THE OLD CODE GOT RIGHT BY ACCIDENT. Anchoring on the root is correct
        // here, which is why "always use the root" looked like it worked.
        assertEquals(0, covering(List.of(ROOT, K50, K90), K10));
    }

    @Test
    @DisplayName("a key in the MIDDLE anchors on the element before it")
    void middleKeyAnchorsOnPredecessor() {
        // This is the case the old code built invalidly: it would have anchored on the root (0) and
        // set the new node's link to None, dropping K90 from the chain.
        assertEquals(1, covering(List.of(ROOT, K10, K90), K50));
    }

    @Test
    @DisplayName("a key above every existing one anchors on the LAST element")
    void highestKeyAnchorsOnTail() {
        assertEquals(2, covering(List.of(ROOT, K10, K50), K90));
    }

    @Test
    @DisplayName("a key already present returns -1 so the caller refuses instead of duplicating")
    void duplicateIsRejected() {
        assertEquals(-1, covering(List.of(ROOT, K10, K50, K90), K50));
        assertEquals(-1, covering(List.of(ROOT, K10), K10));
    }

    @Test
    @DisplayName("comparison is UNSIGNED — a leading byte above 0x7f must not sort below everything")
    void comparisonIsUnsigned() {
        // ⛔ Java bytes are SIGNED. With a signed comparison 0xff… reads as negative and would
        // anchor on the root, producing a list that the on-chain sortedness check rejects — after
        // the operator has signed. parseDenylist uses the same compareUnsigned on read, so a signed
        // comparison here would also disagree with the walk that produced the list.
        String high = "ff".repeat(28);
        String low = "01".repeat(28);
        assertEquals(2, covering(List.of(ROOT, low, K90), high),
                "0xff… sorts ABOVE 0x90…, so it anchors on the tail");
        assertEquals(0, covering(List.of(ROOT, K90, high), low),
                "0x01… sorts below everything, so it anchors on the root");
    }

    @Test
    @DisplayName("a three-element list accepts a fourth at every position")
    void everyPositionIsReachable() {
        List<String> list = List.of(ROOT, K10, K50, K90);
        assertEquals(0, covering(list, "05".repeat(28)));   // before the first
        assertEquals(1, covering(list, "20".repeat(28)));   // between 1st and 2nd
        assertEquals(2, covering(list, "70".repeat(28)));   // between 2nd and 3rd
        assertEquals(3, covering(list, "95".repeat(28)));   // after the last
    }
}
