package org.cardanofoundation.cip113.ccl;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.programmabletoken.cip113.LedgerOrdering;
import com.bloxbean.cardano.client.quicktx.extension.BalanceFinalization;
import com.bloxbean.cardano.client.quicktx.extension.ExtensionBuildContext;
import com.bloxbean.cardano.client.quicktx.extension.QuickTxExtension;
import com.bloxbean.cardano.client.quicktx.extension.TxBuildExtension;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Fills in a substandard's redeemer (a withdraw-zero, or a spent script input) from the transaction cardano-client-lib actually
 * built — the "late redeemer" CCL has no API for (open point: late redeemers).
 *
 * <p>CCL takes a substandard redeemer as fixed data up front, but some substandards prove things by
 * position in the finished transaction: freeze-and-seize names, per spent input, the reference
 * input holding the blacklist node that exempts its owner. Those positions exist only after CCL has
 * selected inputs and added its own reference inputs. This extension runs after CCL's
 * ({@link #order()} 10 vs 0) inside the same QuickTx build:
 * <ul>
 *   <li>{@code beforeScriptEvaluation} — resolve on the final layout, before the scripts run, so
 *       real evaluation sees the real redeemer;</li>
 *   <li>{@code afterBalance} — re-resolve; report {@code REFINALIZED} only if the data changed
 *       (anything else spends the builder's three stabilisation passes for nothing), and resolve
 *       here too if evaluation was skipped;</li>
 *   <li>{@code verify} — the redeemer in the finished transaction must equal a fresh resolution.</li>
 * </ul>
 * Only redeemer data is rewritten, never indices or the body, so CCL's own stability fingerprint
 * (inputs, reference inputs, withdrawals, outputs, mint) is untouched.
 */
public final class SubstandardRedeemerExtension implements QuickTxExtension {

    /** Computes the redeemer from the finished transaction; {@code lookup} resolves its inputs. */
    @FunctionalInterface
    public interface Resolver {
        PlutusData resolve(Transaction transaction, Function<TransactionInput, Optional<Utxo>> lookup);
    }

    private final String id;
    private final String target;
    private final Function<Transaction, Redeemer> locator;
    private final Resolver resolver;

    /** The Reward redeemer of the withdraw-zero against {@code withdrawal}. */
    public SubstandardRedeemerExtension(Credential withdrawal, Resolver resolver) {
        this("platform-substandard-" + HexUtil.encodeHexString(withdrawal.getBytes()),
                "withdrawal " + HexUtil.encodeHexString(withdrawal.getBytes()),
                transaction -> redeemerAt(transaction, RedeemerTag.Reward,
                        LedgerOrdering.indexOfWithdrawal(transaction, withdrawal), "withdrawal"),
                resolver);
    }

    /**
     * The Spend redeemer of {@code input}, for a substandard script input whose redeemer names
     * positions in the finished transaction (a global state spent under a supply cap, say).
     */
    public static SubstandardRedeemerExtension forSpend(Utxo input, Resolver resolver) {
        String ref = input.getTxHash() + "#" + input.getOutputIndex();
        return new SubstandardRedeemerExtension("platform-substandard-spend-" + ref, "spent input " + ref,
                transaction -> redeemerAt(transaction, RedeemerTag.Spend,
                        LedgerOrdering.indexOfInput(transaction, input.getTxHash(), input.getOutputIndex()),
                        "input"),
                resolver);
    }

    private SubstandardRedeemerExtension(String id, String target, Function<Transaction, Redeemer> locator,
                                         Resolver resolver) {
        this.id = id;
        this.target = target;
        this.locator = locator;
        this.resolver = resolver;
    }

    @Override public String id() { return id; }
    @Override public String schemaVersion() { return "1"; }
    @Override public Set<String> operations() { return Set.of(); }
    @Override public int order() { return 10; }

    @Override
    public TxBuildExtension newBuildExtension() {
        return new TxBuildExtension() {
            @Override
            public void beforeScriptEvaluation(ExtensionBuildContext context, Transaction transaction) {
                apply(context, transaction);
            }

            @Override
            public BalanceFinalization afterBalance(ExtensionBuildContext context, Transaction transaction) {
                return apply(context, transaction) ? BalanceFinalization.REFINALIZED : BalanceFinalization.STABLE;
            }

            @Override
            public void verify(ExtensionBuildContext context, Transaction transaction) {
                PlutusData expected = resolve(context, transaction);
                if (!expected.equals(locator.apply(transaction).getData()))
                    throw new IllegalStateException("substandard redeemer for " + target
                            + " no longer matches the finished transaction's layout");
            }
        };
    }

    /** @return whether the redeemer data changed */
    private boolean apply(ExtensionBuildContext context, Transaction transaction) {
        Redeemer redeemer = locator.apply(transaction);
        PlutusData resolved = resolve(context, transaction);
        if (resolved.equals(redeemer.getData())) return false;
        redeemer.setData(resolved);
        return true;
    }

    private PlutusData resolve(ExtensionBuildContext context, Transaction transaction) {
        return resolver.resolve(transaction, in -> context.getUtxoSupplier()
                .getTxOutput(in.getTransactionId(), in.getIndex()));
    }

    private static Redeemer redeemerAt(Transaction transaction, RedeemerTag tag, int index, String what) {
        if (index < 0 || transaction.getWitnessSet() == null || transaction.getWitnessSet().getRedeemers() == null)
            throw new IllegalStateException("the transaction has no " + what + " for this substandard redeemer");
        return transaction.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == tag && r.getIndex().intValue() == index)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + tag + " redeemer at " + what + " index " + index));
    }
}
