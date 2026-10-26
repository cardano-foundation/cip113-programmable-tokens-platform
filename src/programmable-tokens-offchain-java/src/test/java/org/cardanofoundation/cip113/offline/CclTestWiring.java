package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import org.cardanofoundation.cip113.ccl.CclDeployments;
import org.cardanofoundation.cip113.ccl.CclQuickTx;

/**
 * The two CCL collaborators every handler takes, wired over an {@link OfflineChain} the way the
 * application context wires them over the live backend.
 */
public final class CclTestWiring {

    private CclTestWiring() {}

    /** CCL's per-deployment service, resolving deployments through an {@link OfflineBackend} over {@code chain}. */
    public static CclDeployments deployments(OfflineChain chain) throws Exception {
        return new CclDeployments(HandlerFixtures.NETWORK, new OfflineBackend(chain),
                HandlerFixtures.protocolScriptBuilderService());
    }

    /** A builder factory reading the chain's own UTxO set, with the real Aiken evaluator. */
    public static CclQuickTx quickTx(OfflineChain chain) {
        return quickTx(chain, chain.utxoSupplier());
    }

    /**
     * A builder factory reading through {@code supplier} — the same instance the test's own
     * builder reads, so a chained build sees the outputs of transactions not yet on the chain.
     */
    public static CclQuickTx quickTx(OfflineChain chain, UtxoSupplier supplier) {
        return new CclQuickTx(supplier, chain.scriptSupplier(), chain.protocolParamsSupplier(),
                new com.bloxbean.cardano.aiken.AikenTransactionEvaluator(
                        supplier, chain.protocolParamsSupplier(), chain.scriptSupplier()));
    }
}
