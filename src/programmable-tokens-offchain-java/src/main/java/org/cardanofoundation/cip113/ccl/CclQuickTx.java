package org.cardanofoundation.cip113.ccl;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113ProgrammableTokenService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.extension.QuickTxExtension;
import org.cardanofoundation.cip113.service.HybridScriptSupplier;
import org.cardanofoundation.cip113.service.HybridUtxoSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * A {@link QuickTxBuilder} for one CCL build.
 *
 * <p>Built fresh per operation, on the same suppliers and evaluator as the platform's shared
 * builder: the {@code HybridUtxoSupplier} is what lets a chained flow spend the outputs of a
 * transaction that is not on chain yet, and CCL reads the registry and coordination UTxO through
 * whatever supplier the build runs on. A fresh builder also keeps extensions off the shared
 * singleton — {@code withExtension} mutates the builder it is called on, and refuses a second
 * extension with the same id.
 */
@Component
public class CclQuickTx {

    private final UtxoSupplier utxoSupplier;
    private final ScriptSupplier scriptSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionEvaluator evaluator;

    @Autowired
    public CclQuickTx(HybridUtxoSupplier utxoSupplier, HybridScriptSupplier scriptSupplier,
                      ProtocolParamsSupplier protocolParamsSupplier, TransactionEvaluator evaluator) {
        this((UtxoSupplier) utxoSupplier, scriptSupplier, protocolParamsSupplier, evaluator);
    }

    public CclQuickTx(UtxoSupplier utxoSupplier, ScriptSupplier scriptSupplier,
                      ProtocolParamsSupplier protocolParamsSupplier, TransactionEvaluator evaluator) {
        this.utxoSupplier = utxoSupplier;
        this.scriptSupplier = scriptSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
        this.evaluator = evaluator;
    }

    /** A builder carrying {@code service}'s programmable-token extension, plus any platform extensions. */
    public QuickTxBuilder builder(Cip113ProgrammableTokenService service, QuickTxExtension... platformExtensions) {
        var builder = new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, scriptSupplier,
                new TransactionProcessor() {
                    @Override
                    public Result<String> submitTransaction(byte[] cborData) {
                        throw new UnsupportedOperationException(
                                "the platform returns unsigned transactions; it never submits");
                    }

                    @Override
                    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                            throws ApiException {
                        return evaluator.evaluateTx(cbor, inputUtxos);
                    }
                })
                .withExtension(service.extension());
        for (QuickTxExtension extension : platformExtensions) builder.withExtension(extension);
        return builder;
    }

    public UtxoSupplier utxoSupplier() {
        return utxoSupplier;
    }
}
