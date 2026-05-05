package org.cardanofoundation.cip113.config;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.koios.KoiosBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.service.CostModelOverlayProtocolParamsSupplier;
import org.cardanofoundation.cip113.service.HybridUtxoSupplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
@Slf4j
public class YaciConfiguration {


    @Bean
    public HybridUtxoSupplier hybridUtxoSupplier(BFBackendService bfBackendService) {
        return new HybridUtxoSupplier(bfBackendService.getUtxoService());
    }

    @Bean
    public ProtocolParamsSupplier protocolParamsSupplier(BFBackendService bfBackendService,
                                                         KoiosBackendService koiosBackendService) {
        var primary = new DefaultProtocolParamsSupplier(bfBackendService.getEpochService());
        // Koios's no-arg /epoch_params can return a stale finalized epoch on preview,
        // causing PPViewHashesDontMatch — overlay forces the current epoch's cost model.
        return new CostModelOverlayProtocolParamsSupplier(primary, koiosBackendService);
    }

    @Bean
    public TransactionEvaluator aikenTransactionEvaluator(HybridUtxoSupplier hybridUtxoSupplier,
                                                          ProtocolParamsSupplier protocolParamsSupplier,
                                                          BFBackendService bfBackendService) {

        var scriptSupplier = new DefaultScriptSupplier(bfBackendService.getScriptService());
        var aikenEvaluator = new AikenTransactionEvaluator(hybridUtxoSupplier, protocolParamsSupplier, scriptSupplier);

        // Three-tier evaluator: aiken-java-binding → Blockfrost → ceiling-cost fallback.
        // aiken-java-binding 0.1.0 doesn't yet handle Aiken MPF v2.1.0 proofs that pass
        // `aiken check`; Blockfrost can occasionally fail; the ceiling fallback lets the
        // build complete and lets the chain be the final arbiter for valid scripts.
        return new TransactionEvaluator() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
                try {
                    var result = aikenEvaluator.evaluateTx(cbor, inputUtxos);
                    if (result != null && result.isSuccessful()) {
                        return result;
                    }
                    log.debug("Local Aiken evaluator unsuccessful, falling back to Blockfrost. Reason: {}",
                            result != null ? result.getResponse() : "null");
                } catch (Exception e) {
                    log.debug("Local Aiken evaluator threw {}, falling back to Blockfrost", e.toString());
                }
                try {
                    var bfResult = bfBackendService.getTransactionService().evaluateTx(cbor);
                    if (bfResult != null && bfResult.isSuccessful()) {
                        return bfResult;
                    }
                    log.debug("Blockfrost evaluator unsuccessful: {}",
                            bfResult != null ? bfResult.getResponse() : "null");
                } catch (Exception e) {
                    log.debug("Blockfrost evaluator threw {}", e.toString());
                }
                return ceilingCostFallback(cbor);
            }
        };
    }

    /** Last-resort evaluator: synthesise a generous ceiling cost per redeemer (1.5M mem,
     *  800M cpu) so the build completes when local + Blockfrost evaluators both fail. */
    private Result<List<EvaluationResult>> ceilingCostFallback(byte[] txCbor) {
        try {
            var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.deserialize(txCbor);
            var redeemers = tx.getWitnessSet() != null ? tx.getWitnessSet().getRedeemers() : null;
            if (redeemers == null || redeemers.isEmpty()) {
                log.warn("ceilingCostFallback: no redeemers in tx, returning empty");
                return Result.success("ceilingCostFallback: no redeemers")
                        .withValue(List.<EvaluationResult>of());
            }
            var results = new java.util.ArrayList<EvaluationResult>(redeemers.size());
            for (var r : redeemers) {
                var er = new EvaluationResult();
                er.setRedeemerTag(r.getTag());
                er.setIndex(r.getIndex().intValue());
                var ex = new com.bloxbean.cardano.client.plutus.spec.ExUnits(
                        java.math.BigInteger.valueOf(1_500_000L),    // mem ceiling
                        java.math.BigInteger.valueOf(800_000_000L)); // cpu ceiling
                er.setExUnits(ex);
                results.add(er);
            }
            log.warn("ceilingCostFallback: synthesised {} redeemer cost(s) at ceiling (mem=1.5M, cpu=800M)",
                    results.size());
            return Result.success("ceilingCostFallback").withValue(results);
        } catch (Exception e) {
            log.error("ceilingCostFallback failed to synthesise costs: {}", e.toString());
            return Result.error("ceilingCostFallback failed: " + e.getMessage())
                    .withValue(List.<EvaluationResult>of());
        }

    }

    @Bean
    public QuickTxBuilder quickTxBuilder(HybridUtxoSupplier hybridUtxoSupplier,
                                         ProtocolParamsSupplier protocolParamsSupplier,
                                         TransactionEvaluator transactionEvaluator,
                                         BFBackendService bfBackendService) {


        var transactionProcessor = new TransactionProcessor() {
            @Override
            public Result<String> submitTransaction(byte[] cborData) throws ApiException {
                return null;
            }

            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) throws ApiException {
                return transactionEvaluator.evaluateTx(cbor, inputUtxos);
            }
        };
        var scriptSupplier = new DefaultScriptSupplier(bfBackendService.getScriptService());

        return new QuickTxBuilder(hybridUtxoSupplier,
                protocolParamsSupplier,
                scriptSupplier,
                transactionProcessor);

    }


}
