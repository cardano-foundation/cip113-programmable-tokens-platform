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
import org.cardanofoundation.cip113.service.HybridScriptSupplier;
import org.cardanofoundation.cip113.service.HybridUtxoSupplier;
import org.springframework.beans.factory.annotation.Value;
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

    /** Reference-script resolver for the whole app. Wrapping {@link DefaultScriptSupplier}
     *  is what makes a mempool-chained reference script usable: a reference script lives in
     *  a transaction OUTPUT, and every consumer of one (the Aiken evaluator, the fee
     *  calculator, {@code ReferenceScriptResolver}) resolves it by asking a
     *  {@code ScriptSupplier} for the hash recorded on the UTxO. The backend cannot answer
     *  for an unsubmitted output, so without this the local evaluator errors on any
     *  chain-published reference script and {@link #ceilingCostFallback} silently fabricates
     *  its costs. */
    @Bean
    public HybridScriptSupplier hybridScriptSupplier(BFBackendService bfBackendService) {
        return new HybridScriptSupplier(new DefaultScriptSupplier(bfBackendService.getScriptService()));
    }

    /**
     * Protocol parameters for transaction building — above all the Plutus cost models, which
     * are hashed into every script transaction's {@code scriptIntegrityHash}.
     *
     * <p>On the public testnets the primary source (Blockfrost) has served a stale PlutusV3
     * cost model across a hard fork, so a Koios overlay pins the current epoch's — see
     * {@link CostModelOverlayProtocolParamsSupplier} for that history.
     *
     * <p><b>The overlay must not run on a devnet.</b> It is not a fallback but a
     * cross-chain substitution: {@code koios.url} points at a PUBLIC network, so on a devnet
     * it fetches preview's cost model and writes it over the local chain's. Those genuinely
     * differ — a Yaci devnet serves 251 PlutusV3 entries where post-fork preview serves 350 —
     * and the cost models are part of the script integrity hash. Every Plutus transaction is
     * then built against one chain's cost model and validated against another's, and the node
     * rejects it with {@code ScriptIntegrityHashMismatch}: two hashes, no indication that a
     * cost model came from the wrong chain.
     *
     * <p>On a devnet the primary source is yaci-store, reading the node this backend is
     * actually talking to, so it is authoritative by construction and needs no overlay.
     */
    @Bean
    public ProtocolParamsSupplier protocolParamsSupplier(BFBackendService bfBackendService,
                                                         KoiosBackendService koiosBackendService,
                                                         @Value("${network}") String network) {
        var primary = new DefaultProtocolParamsSupplier(bfBackendService.getEpochService());

        if ("devnet".equals(network) || "dev".equals(network) || "yaci".equals(network)) {
            log.info("INIT ProtocolParams: using the local backend directly (no Koios cost-model "
                    + "overlay on {} — the overlay reads a public network and would substitute "
                    + "another chain's cost model into the script integrity hash)", network);
            return primary;
        }

        // Koios's no-arg /epoch_params can return a stale finalized epoch on preview,
        // causing PPViewHashesDontMatch — overlay forces the current epoch's cost model.
        return new CostModelOverlayProtocolParamsSupplier(primary, koiosBackendService);
    }

    /**
     * Slot configuration for the local evaluator.
     *
     * <p>The evaluator translates the transaction's validity range into the POSIX times a
     * script sees, and without a slot config it assumes MAINNET — whose Shelley era starts
     * at slot 4 492 800. A devnet's slots start near zero, so every transaction carrying a
     * validity bound is rejected before it is even scored, with
     * {@code SlotTooFarInThePast { oldest_allowed: 4492800 }}.
     *
     * <p>A devnet's zero time is its genesis {@code systemStart}, which changes each time
     * the cluster is recreated — so it is read from configuration rather than baked in.
     * Returning {@code null} keeps the library default, which is correct for mainnet.
     */
    private static com.bloxbean.cardano.client.common.model.SlotConfig slotConfigFor(
            String network, Long devnetSystemStartMs) {
        return switch (network == null ? "" : network) {
            case "devnet", "yaci", "dev" -> {
                if (devnetSystemStartMs == null) {
                    log.warn("network={} but cardano.devnet.system-start-ms is unset — the local "
                             + "evaluator will assume MAINNET slots and reject every transaction "
                             + "with a validity bound as SlotTooFarInThePast", network);
                    yield null;
                }
                // Devnet slots are one second and start at zero.
                yield new com.bloxbean.cardano.client.common.model.SlotConfig(
                        1000, 0L, devnetSystemStartMs);
            }
            default -> null;
        };
    }

    @Bean
    public TransactionEvaluator aikenTransactionEvaluator(HybridUtxoSupplier hybridUtxoSupplier,
                                                          HybridScriptSupplier hybridScriptSupplier,
                                                          ProtocolParamsSupplier protocolParamsSupplier,
                                                          BFBackendService bfBackendService,
                                                          @Value("${network}") String network,
                                                          @Value("${cardano.devnet.system-start-ms:#{null}}")
                                                          Long devnetSystemStartMs) {

        var slotConfig = slotConfigFor(network, devnetSystemStartMs);
        var aikenEvaluator = slotConfig == null
                ? new AikenTransactionEvaluator(hybridUtxoSupplier, protocolParamsSupplier, hybridScriptSupplier)
                : new AikenTransactionEvaluator(hybridUtxoSupplier, protocolParamsSupplier, hybridScriptSupplier,
                        slotConfig);

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
                                         HybridScriptSupplier hybridScriptSupplier,
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
        // The hybrid supplier, not a bare DefaultScriptSupplier: this is what
        // ReferenceScriptResolver and the Conway ref-script fee calculation consult, and
        // both run on transactions whose reference scripts may still be unsubmitted.
        return new QuickTxBuilder(hybridUtxoSupplier,
                protocolParamsSupplier,
                hybridScriptSupplier,
                transactionProcessor);

    }


}
