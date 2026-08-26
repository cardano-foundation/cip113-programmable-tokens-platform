package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Where things land in a transaction once the ledger has put them in ITS order.
 *
 * <p>Several CIP-113 redeemers do not describe what a transaction does; they say
 * <em>where to look</em> — the position of the protocol-params UTxO among the reference
 * inputs, of a registry node among them, of a delegate's entry in the withdrawal map, of
 * the first paired continuation output. A validator resolves the thing at that position
 * and checks it. Point at the wrong slot and the check fails, with a message about the
 * thing that happened to be there.
 *
 * <p>The catch is that none of these positions is the order the builder added things in.
 * The ledger re-orders reference inputs and keys withdrawals by credential, so an index
 * is only meaningful once the <em>complete</em> set is known. That is the structural
 * reason this cannot live in a substandard handler: a handler knows its own withdrawals
 * and reference inputs, not the framework's, and the framework knows its own and not the
 * handler's. Whoever computes an index must see both.
 *
 * <p>So callers declare everything, then read positions off the finished layout. Nothing
 * here builds a transaction; it is a pure function from "what the transaction contains"
 * to "where each thing sits", which is what makes the orderings testable on their own
 * rather than only through a script that rejects them.
 *
 * <h2>The two orderings</h2>
 *
 * <p><strong>Reference inputs</strong> are ordered as a set of {@code TransactionInput}:
 * by transaction id bytes, then by output index.
 *
 * <p><strong>Withdrawals</strong> are keyed by {@code RewardAccount = (network,
 * credential)}, and cardano-ledger's {@code Credential} derives {@code Ord} with
 * {@code ScriptHashObj} declared before {@code KeyHashObj}. So <em>every</em> script
 * credential sorts before <em>every</em> key credential, and hashes compare bytewise
 * within each kind. This is emphatically not the sort order of bech32 reward addresses,
 * which is the intuitive thing to reach for and is wrong: the bech32 form encodes the
 * header byte first, so it interleaves the two kinds.
 *
 * <p>An index must be computed over the complete withdrawal set — but "shifts every position
 * after it" means after it <em>in ledger order</em>, not after it in the builder. Because
 * every script credential sorts ahead of every key credential, a key-hash reward withdrawal
 * a wallet adds can never displace a script's index, however many it adds. What does
 * displace one is another SCRIPT withdrawal with a lower hash — a second substandard, say.
 * {@code CoreLayoutTest#whatShiftsAnIndex} pins both halves, because knowing only the first
 * invites the conclusion that script indices are stable.
 */
public final class CoreLayout {

    private final List<TransactionInput> referenceInputs;
    private final List<WithdrawalKey> withdrawals;

    private CoreLayout(List<TransactionInput> referenceInputs, List<WithdrawalKey> withdrawals) {
        this.referenceInputs = referenceInputs;
        this.withdrawals = withdrawals;
    }

    /**
     * A withdrawal's ledger sort key: the credential, reduced to what the ordering depends
     * on. {@code isScript} is not cosmetic — it is the primary sort dimension.
     */
    public record WithdrawalKey(String hashHex, boolean isScript) {

        /**
         * Normalises on construction, so ordering, equality, deduplication and lookup cannot
         * disagree. Without this the comparator (which lowercases) would call two spellings of
         * one credential equal while {@code equals} called them different — producing a set
         * with two entries for one account, an unstable sort between them, and a lookup that
         * misses. The factories below already lowercased; the canonical constructor did not,
         * and a record's canonical constructor is public whether or not anyone meant it to be.
         */
        public WithdrawalKey {
            hashHex = hashHex == null ? null : hashHex.toLowerCase();
        }

        public static WithdrawalKey of(Credential credential) {
            return new WithdrawalKey(
                    HexUtil.encodeHexString(credential.getBytes()).toLowerCase(),
                    credential.getType() == CredentialType.Script);
        }

        public static WithdrawalKey script(String hashHex) {
            return new WithdrawalKey(hashHex.toLowerCase(), true);
        }

        public static WithdrawalKey key(String hashHex) {
            return new WithdrawalKey(hashHex.toLowerCase(), false);
        }
    }

    /**
     * Ledger order for reference inputs: transaction id first, then output index.
     *
     * <p>Compared as hex rather than as raw bytes because a transaction id is a fixed-width
     * 32-byte hash rendered in lowercase hex, and lowercase hex of equal length compares
     * identically to the underlying bytes. (That equivalence does NOT hold for
     * variable-length or mixed-case input, which is why the comparator lowercases rather
     * than trusting the caller.)
     */
    public static final Comparator<TransactionInput> REFERENCE_INPUT_ORDER =
            Comparator.comparing((TransactionInput i) -> i.getTransactionId().toLowerCase())
                    .thenComparingInt(TransactionInput::getIndex);

    /**
     * Ledger order for withdrawals: script credentials before key credentials, bytewise by
     * hash within each kind.
     *
     * <p>{@code ScriptHashObj} is declared before {@code KeyHashObj} in cardano-ledger's
     * {@code Credential}, and the derived {@code Ord} therefore orders by constructor
     * first. Plutus hands the script the withdrawal map already in this order.
     */
    public static final Comparator<WithdrawalKey> WITHDRAWAL_ORDER =
            Comparator.comparing((WithdrawalKey w) -> w.isScript() ? 0 : 1)
                    .thenComparing(WithdrawalKey::hashHex);

    /**
     * @param referenceInputs every reference input the transaction will carry, in any order
     * @param withdrawals     every withdrawal the transaction will carry, in any order —
     *                        the framework's, the substandard's, and any the wallet adds
     */
    public static CoreLayout of(Set<TransactionInput> referenceInputs, Set<WithdrawalKey> withdrawals) {
        // Normalise before de-duplicating or indexing. TransactionInput's equals() compares the
        // transaction id as a STRING, so two spellings of one input are two distinct elements to
        // a Set and to indexOf, while REFERENCE_INPUT_ORDER (which lowercases) calls them equal.
        // That combination is worse than either alone: the set holds a phantom second entry,
        // the sort between them is unspecified, and a lookup for the other spelling misses. One
        // canonical form at the boundary removes the whole class of problem.
        var sortedRefs = new ArrayList<TransactionInput>();
        for (TransactionInput input : referenceInputs) {
            TransactionInput canonical = canonicalise(input);
            if (!sortedRefs.contains(canonical)) {
                sortedRefs.add(canonical);
            }
        }
        sortedRefs.sort(REFERENCE_INPUT_ORDER);
        var sortedWdrls = new ArrayList<>(withdrawals);
        sortedWdrls.sort(WITHDRAWAL_ORDER);
        return new CoreLayout(List.copyOf(sortedRefs), List.copyOf(sortedWdrls));
    }

    /** The reference inputs in the order the ledger will present them. */
    public List<TransactionInput> referenceInputs() {
        return referenceInputs;
    }

    /** The withdrawal keys in the order the ledger will present them. */
    public List<WithdrawalKey> withdrawals() {
        return withdrawals;
    }

    /**
     * Position of a reference input, for {@code params_idx}, {@code registry_node_idx} and
     * the {@code node_idx} of a registry proof.
     *
     * @throws IllegalArgumentException if it is not among the declared reference inputs.
     *         A caller asking for the position of something absent has a bug that would
     *         otherwise be encoded as {@code -1} and spend the rest of its life as an
     *         out-of-range index inside a redeemer.
     */
    public int referenceInputIndex(TransactionInput input) {
        int idx = referenceInputs.indexOf(canonicalise(input));
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "not a declared reference input: " + input.getTransactionId() + "#" + input.getIndex()
                            + ". Declared: " + describeReferenceInputs());
        }
        return idx;
    }

    /**
     * Position of a withdrawal, for {@code wdrl_idx}.
     *
     * @throws IllegalArgumentException if it is not among the declared withdrawals.
     */
    public int withdrawalIndex(Credential credential) {
        return withdrawalIndex(WithdrawalKey.of(credential));
    }

    public int withdrawalIndex(WithdrawalKey key) {
        int idx = withdrawals.indexOf(key);
        if (idx < 0) {
            throw new IllegalArgumentException(
                    "not a declared withdrawal: " + (key.isScript() ? "script " : "key ") + key.hashHex()
                            + ". Declared: " + withdrawals);
        }
        return idx;
    }

    /**
     * Re-order a builder's withdrawals into the order the ledger will key them.
     *
     * <p>Needed because a withdrawal's redeemer is matched to its entry by POSITION in the
     * canonical map, not by the order the builder added it. Adding them in an arbitrary order
     * and trusting the serialiser to sort works only if it also re-indexes the redeemers; the
     * cheap way to not depend on that is to add them already sorted. Upstream's integration
     * guide says the same thing in one line: add the withdrawal calls in ledger order.
     *
     * @param items        whatever the caller wants ordered — script/address/redeemer triples,
     *                     usually
     * @param credentialOf how to get each item's withdrawal credential
     */
    public <T> List<T> inWithdrawalOrder(List<T> items, java.util.function.Function<T, Credential> credentialOf) {
        var sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(item -> withdrawalIndex(credentialOf.apply(item))));
        return List.copyOf(sorted);
    }

    /**
     * One spelling per transaction input. Also the place a malformed id is caught: a
     * transaction id is a 32-byte hash, so anything that is not 64 hex characters cannot name
     * a real input and would otherwise sort somewhere plausible and index something wrong.
     */
    private static TransactionInput canonicalise(TransactionInput input) {
        String txId = input.getTransactionId();
        if (txId == null || !txId.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "not a transaction id (expected 64 hex characters): " + txId);
        }
        if (input.getIndex() < 0) {
            throw new IllegalArgumentException("negative output index: " + input.getIndex());
        }
        String lower = txId.toLowerCase();
        return lower.equals(txId)
                ? input
                : TransactionInput.builder().transactionId(lower).index(input.getIndex()).build();
    }

    private String describeReferenceInputs() {
        var sb = new StringBuilder();
        for (int i = 0; i < referenceInputs.size(); i++) {
            var in = referenceInputs.get(i);
            sb.append(i).append('=').append(in.getTransactionId()).append('#').append(in.getIndex()).append(' ');
        }
        return sb.toString().trim();
    }

    /**
     * Accumulates declarations. Order of declaration is irrelevant to the result — that is
     * the point — but a {@link LinkedHashSet} keeps it stable for diagnostics, so a failure
     * message lists things in the order someone wrote them.
     */
    public static final class Builder {
        private final Set<TransactionInput> referenceInputs = new LinkedHashSet<>();
        private final Set<WithdrawalKey> withdrawals = new LinkedHashSet<>();

        public Builder referenceInput(TransactionInput input) {
            referenceInputs.add(Objects.requireNonNull(input, "reference input"));
            return this;
        }

        public Builder referenceInput(String txHash, int outputIndex) {
            return referenceInput(TransactionInput.builder()
                    .transactionId(txHash)
                    .index(outputIndex)
                    .build());
        }

        public Builder withdrawal(Credential credential) {
            withdrawals.add(WithdrawalKey.of(Objects.requireNonNull(credential, "credential")));
            return this;
        }

        public Builder withdrawal(WithdrawalKey key) {
            withdrawals.add(Objects.requireNonNull(key, "withdrawal key"));
            return this;
        }

        public CoreLayout build() {
            return CoreLayout.of(referenceInputs, withdrawals);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
