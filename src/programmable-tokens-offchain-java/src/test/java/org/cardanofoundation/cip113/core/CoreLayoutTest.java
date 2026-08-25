package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.easy1staking.cardano.comparator.TransactionInputComparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two ledger orderings {@link CoreLayout} derives redeemer indices from.
 *
 * <p>These are worth testing in isolation because the failure they guard against is
 * invisible in review and expensive on chain: an index that points one slot away resolves
 * to some other UTxO or credential, and the validator rejects it with a complaint about
 * whatever it found there. Nothing in the transaction says "this index was computed with
 * the wrong comparator".
 */
class CoreLayoutTest {

    private static TransactionInput in(String txHash, int index) {
        return TransactionInput.builder().transactionId(txHash).index(index).build();
    }

    private static final String TX_A = "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String TX_B = "458a1eee00000000000000000000000000000000000000000000000000000000";
    private static final String TX_C = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    @DisplayName("reference inputs sort by transaction id, then output index")
    void referenceInputOrder() {
        var layout = CoreLayout.builder()
                .referenceInput(in(TX_C, 0))
                .referenceInput(in(TX_A, 5))
                .referenceInput(in(TX_B, 1))
                .referenceInput(in(TX_B, 0))
                .referenceInput(in(TX_A, 0))
                .build();

        assertEquals(
                List.of(in(TX_A, 0), in(TX_A, 5), in(TX_B, 0), in(TX_B, 1), in(TX_C, 0)),
                layout.referenceInputs());

        assertEquals(0, layout.referenceInputIndex(in(TX_A, 0)));
        assertEquals(3, layout.referenceInputIndex(in(TX_B, 1)));
        assertEquals(4, layout.referenceInputIndex(in(TX_C, 0)));
    }

    /**
     * The existing handlers sort reference inputs with the library's comparator and take
     * {@code indexOf}. Migrating them onto {@link CoreLayout} is only safe if the two agree
     * on every input, so this asserts that directly rather than assuming it — and it will
     * keep asserting it if the library's ordering ever changes underneath us.
     */
    @Test
    @DisplayName("agrees with the TransactionInputComparator the handlers use today")
    void agreesWithLibraryComparator() {
        var inputs = new ArrayList<>(List.of(
                in(TX_C, 0), in(TX_A, 5), in(TX_B, 1), in(TX_B, 0), in(TX_A, 0), in(TX_C, 7)));

        var viaLibrary = new ArrayList<>(inputs);
        viaLibrary.sort(new TransactionInputComparator());

        var viaLayout = new ArrayList<>(inputs);
        viaLayout.sort(CoreLayout.REFERENCE_INPUT_ORDER);

        assertEquals(viaLibrary, viaLayout);
    }

    /**
     * The case a hex-only sort gets wrong. {@code 00…} as a KEY credential and {@code ff…}
     * as a SCRIPT credential: by hash alone the key sorts first, but the ledger puts every
     * script credential ahead of every key credential, so the script is at index 0.
     */
    @Test
    @DisplayName("every script credential sorts before every key credential")
    void withdrawalOrderPutsScriptsFirst() {
        var lowKey = CoreLayout.WithdrawalKey.key("00".repeat(28));
        var highScript = CoreLayout.WithdrawalKey.script("ff".repeat(28));

        var layout = CoreLayout.builder()
                .withdrawal(lowKey)
                .withdrawal(highScript)
                .build();

        assertEquals(List.of(highScript, lowKey), layout.withdrawals());
        assertEquals(0, layout.withdrawalIndex(highScript));
        assertEquals(1, layout.withdrawalIndex(lowKey));
    }

    @Test
    @DisplayName("within each credential kind, hashes compare bytewise")
    void withdrawalOrderWithinKind() {
        var s1 = CoreLayout.WithdrawalKey.script("11".repeat(28));
        var s2 = CoreLayout.WithdrawalKey.script("22".repeat(28));
        var k1 = CoreLayout.WithdrawalKey.key("aa".repeat(28));
        var k2 = CoreLayout.WithdrawalKey.key("bb".repeat(28));

        var layout = CoreLayout.builder()
                .withdrawal(k2).withdrawal(s2).withdrawal(k1).withdrawal(s1)
                .build();

        assertEquals(List.of(s1, s2, k1, k2), layout.withdrawals());
    }

    /**
     * A wallet adding a key-hash reward withdrawal is the classic source of a shifted
     * index. Because scripts sort first it cannot move a script's position — but a SECOND
     * substandard script withdrawal can, and does. Both halves are asserted here because
     * only knowing the first invites the conclusion that script indices are stable.
     */
    @Test
    @DisplayName("a key withdrawal cannot shift script positions; another script withdrawal can")
    void whatShiftsAnIndex() {
        var coreDelegate = CoreLayout.WithdrawalKey.script("cc".repeat(28));
        var substandard = CoreLayout.WithdrawalKey.script("ee".repeat(28));

        var before = CoreLayout.builder().withdrawal(coreDelegate).withdrawal(substandard).build();
        assertEquals(0, before.withdrawalIndex(coreDelegate));

        var withWalletKey = CoreLayout.builder()
                .withdrawal(coreDelegate).withdrawal(substandard)
                .withdrawal(CoreLayout.WithdrawalKey.key("00".repeat(28)))
                .build();
        assertEquals(0, withWalletKey.withdrawalIndex(coreDelegate),
                "a key credential sorts after every script, so it cannot displace one");

        var withEarlierScript = CoreLayout.builder()
                .withdrawal(coreDelegate).withdrawal(substandard)
                .withdrawal(CoreLayout.WithdrawalKey.script("00".repeat(28)))
                .build();
        assertEquals(1, withEarlierScript.withdrawalIndex(coreDelegate),
                "a script credential with a lower hash displaces every script after it");
    }

    @Test
    @DisplayName("a Credential maps to the same key regardless of how it was constructed")
    void credentialMapping() {
        byte[] hash = HexUtil.decodeHexString("ab".repeat(28));

        assertEquals(CoreLayout.WithdrawalKey.script("ab".repeat(28)),
                CoreLayout.WithdrawalKey.of(Credential.fromScript(hash)));
        assertEquals(CoreLayout.WithdrawalKey.key("ab".repeat(28)),
                CoreLayout.WithdrawalKey.of(Credential.fromKey(hash)));

        // Same hash, different kind: the ledger treats these as different accounts, so the
        // layout must too. Conflating them would silently return one's index for the other.
        assertTrue(!CoreLayout.WithdrawalKey.of(Credential.fromScript(hash))
                .equals(CoreLayout.WithdrawalKey.of(Credential.fromKey(hash))));
    }

    @Test
    @DisplayName("asking for the position of something undeclared fails loudly")
    void undeclaredLookupsThrow() {
        var layout = CoreLayout.builder()
                .referenceInput(in(TX_A, 0))
                .withdrawal(CoreLayout.WithdrawalKey.script("11".repeat(28)))
                .build();

        // The alternative is indexOf's -1 quietly becoming a redeemer field, which the
        // validator then resolves out of range and rejects for an unrelated-looking reason.
        assertThrows(IllegalArgumentException.class, () -> layout.referenceInputIndex(in(TX_B, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> layout.withdrawalIndex(CoreLayout.WithdrawalKey.script("22".repeat(28))));
    }

    /**
     * Regression for a mismatch between how inputs were ORDERED and how they were compared for
     * identity: the comparator lowercased the transaction id, while {@code TransactionInput}'s
     * own {@code equals} — used by the de-duplicating set and by {@code indexOf} — did not. Two
     * spellings of one input therefore occupied two slots that the sort considered equal, and a
     * lookup for the other spelling missed. Normalising at the boundary is what makes ordering
     * and identity agree.
     */
    @Test
    @DisplayName("a transaction id in different letter case is one input, not two")
    void transactionIdCaseIsNormalised() {
        var lower = in(TX_B, 1);
        var upper = in(TX_B.toUpperCase(), 1);

        var layout = CoreLayout.builder()
                .referenceInput(lower)
                .referenceInput(upper)
                .referenceInput(in(TX_A, 0))
                .build();

        assertEquals(2, layout.referenceInputs().size(), "the same input must not occupy two slots");
        assertEquals(1, layout.referenceInputIndex(lower));
        assertEquals(1, layout.referenceInputIndex(upper), "either spelling must resolve to the same slot");
    }

    @Test
    @DisplayName("a withdrawal hash in different letter case is one credential, not two")
    void withdrawalHashCaseIsNormalised() {
        var lower = new CoreLayout.WithdrawalKey("ab".repeat(28), true);
        var upper = new CoreLayout.WithdrawalKey("AB".repeat(28), true);

        assertEquals(lower, upper, "the record's canonical constructor must normalise too");

        var layout = CoreLayout.builder().withdrawal(lower).withdrawal(upper).build();
        assertEquals(1, layout.withdrawals().size());
        assertEquals(0, layout.withdrawalIndex(upper));
    }

    @Test
    @DisplayName("an id that cannot name a real input is rejected, not sorted somewhere plausible")
    void malformedInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CoreLayout.builder().referenceInput("deadbeef", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> CoreLayout.builder().referenceInput(TX_A, -1).build());
    }

    @Test
    @DisplayName("declaration order does not affect the result")
    void declarationOrderIsIrrelevant() {
        var a = CoreLayout.builder()
                .referenceInput(in(TX_A, 0)).referenceInput(in(TX_B, 3)).referenceInput(in(TX_C, 1))
                .build();
        var b = CoreLayout.builder()
                .referenceInput(in(TX_C, 1)).referenceInput(in(TX_A, 0)).referenceInput(in(TX_B, 3))
                .build();

        assertEquals(a.referenceInputs(), b.referenceInputs());
    }
}
