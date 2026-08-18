package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.CostModelUtil;
import com.bloxbean.cardano.client.api.util.ReferenceScriptUtil;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A tiny in-memory stand-in for a Cardano node: a UTxO set you can "submit" transactions into,
 * plus the protocol params, script and processor plumbing the cardano-client-lib builders need.
 *
 * <p>Everything here is offline. Nothing opens a socket. Submission is <em>virtual</em>:
 * {@link #submit(Transaction)} does not validate anything, it simply promotes the transaction's
 * outputs into the spendable set under its real hash and (optionally) retires its inputs. That
 * is enough to chain transaction N+1 onto transaction N — which is what lets a test build a
 * registration on top of a bootstrap without a node.
 *
 * <p>What virtual submission deliberately does NOT do is run phase-1 ledger validation: no
 * value conservation, no min-UTxO, no fee sufficiency, no stake-registration checks. Phase-2
 * (the actual Plutus scripts) IS run, via {@link #transactionProcessor()}. So a green result
 * here means "the scripts accept this transaction", not "a node would accept this transaction".
 */
@Slf4j
public class OfflineChain {

    /**
     * The fabricated per-redeemer cost {@code YaciConfiguration#ceilingCostFallback} synthesises
     * when BOTH the local aiken evaluator and Blockfrost fail. Nothing in this package can reach
     * that bean — these tests are plain JUnit, construct {@link AikenTransactionEvaluator}
     * directly, and never build a Spring context — but a redeemer carrying exactly these numbers
     * would mean a script trap had been laundered into a "successful" build, so every test
     * asserts against them explicitly rather than trusting that by construction.
     */
    public static final BigInteger CEILING_FALLBACK_MEM = BigInteger.valueOf(1_500_000);
    public static final BigInteger CEILING_FALLBACK_STEPS = BigInteger.valueOf(800_000_000);

    /** Placeholder ex-units QuickTxBuilder stamps on a redeemer before evaluation. */
    public static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);
    public static final BigInteger PLACEHOLDER_STEPS = BigInteger.valueOf(10_000);

    /**
     * The hand-picked cost {@code SecurityTokenSubstandardHandler#buildRegisterTransferLogicTransaction}
     * stamps on the Cert publish redeemer it injects. That injection happens in a
     * {@code postBalanceTx} hook — i.e. AFTER {@code ScriptCostEvaluators.evaluateScriptCost()}
     * has already run — so the redeemer does not exist when the evaluator sees the transaction
     * and these numbers can never be measured. They are a guess by construction, and this test
     * reports them as such rather than counting them as evaluator output.
     */
    public static final BigInteger POST_BALANCE_CERT_MEM = BigInteger.valueOf(1_000_000);
    public static final BigInteger POST_BALANCE_CERT_STEPS = BigInteger.valueOf(500_000_000);

    private final InMemoryUtxoSupplier utxoSupplier = new InMemoryUtxoSupplier();
    private final InMemoryScriptSupplier scriptSupplier = new InMemoryScriptSupplier();
    private final ProtocolParams protocolParams = conwayProtocolParams();
    private final ProtocolParamsSupplier protocolParamsSupplier = () -> protocolParams;

    public UtxoSupplierView utxoSupplier() {
        return utxoSupplier;
    }

    public ScriptSupplier scriptSupplier() {
        return scriptSupplier;
    }

    public ProtocolParamsSupplier protocolParamsSupplier() {
        return protocolParamsSupplier;
    }

    public ProtocolParams protocolParams() {
        return protocolParams;
    }

    /** Register a script so reference-script inputs can be resolved during evaluation. */
    public OfflineChain withScripts(PlutusScript... scripts) {
        for (var script : scripts) {
            scriptSupplier.add(script);
        }
        return this;
    }

    /**
     * A {@link TransactionProcessor} whose evaluation is the real aiken/uplc machine over this
     * chain's UTxO set, and whose submission is a hard failure.
     *
     * <p>QuickTxBuilder falls back to the processor as the tx evaluator when no explicit
     * {@code withTxEvaluator(...)} is set — which is exactly how the production handlers are
     * written, so injecting this makes them evaluate for real without touching their code.
     */
    public TransactionProcessor transactionProcessor() {
        var evaluator = new AikenTransactionEvaluator(utxoSupplier, protocolParamsSupplier, scriptSupplier);
        return new TransactionProcessor() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                return evaluator.evaluateTx(cbor, inputUtxos);
            }

            @Override
            public Result<String> submitTransaction(byte[] cborBytes) {
                throw new UnsupportedOperationException("offline: submission is disabled");
            }
        };
    }

    /** A standalone evaluator over this chain, for {@code withTxEvaluator(...)} call sites. */
    public AikenTransactionEvaluator evaluator() {
        return new AikenTransactionEvaluator(utxoSupplier, protocolParamsSupplier, scriptSupplier);
    }

    /**
     * A QuickTxBuilder shaped exactly like the production bean in {@code YaciConfiguration}:
     * the four-argument form that also carries a {@link ScriptSupplier}.
     *
     * <p>The script supplier is not optional. Any transaction with reference inputs sends
     * QuickTxBuilder through {@code ReferenceScriptResolver.resolveReferenceScript()}, which
     * dereferences the context's script supplier — with the three-argument constructor that
     * field is never set and the build dies with a bare NPE on {@code scriptSupplier}. The
     * bootstrap transaction has no reference inputs and so does not notice; every substandard
     * registration does.
     */
    public com.bloxbean.cardano.client.quicktx.QuickTxBuilder quickTxBuilder() {
        return quickTxBuilderOver(utxoSupplier);
    }

    /**
     * The same builder, but reading UTxOs through {@code supplier} instead of this chain's raw
     * set.
     *
     * <p>Needed for multi-transaction orchestrators. The security-token registration chain builds
     * three transactions back-to-back without submitting anything, feeding each one's outputs
     * into its own {@code HybridUtxoSupplier} so the next can spend them. In production the
     * QuickTxBuilder bean is constructed over that very supplier — so a test that injects a
     * builder reading only the settled chain will fail on the second transaction, at
     * {@code buildCollateralOutput}'s {@code Optional.get()}, because the collateral input it was
     * told to use does not exist yet.
     */
    public com.bloxbean.cardano.client.quicktx.QuickTxBuilder quickTxBuilderOver(
            com.bloxbean.cardano.client.api.UtxoSupplier supplier) {
        var evaluator = new AikenTransactionEvaluator(supplier, protocolParamsSupplier, scriptSupplier);
        TransactionProcessor processor = new TransactionProcessor() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                return evaluator.evaluateTx(cbor, inputUtxos);
            }

            @Override
            public Result<String> submitTransaction(byte[] cborBytes) {
                throw new UnsupportedOperationException("offline: submission is disabled");
            }
        };
        return new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(
                supplier, protocolParamsSupplier, scriptSupplier, processor);
    }

    /**
     * A {@link com.bloxbean.cardano.client.backend.api.UtxoService} over this chain, so the
     * production {@code HybridUtxoSupplier} can be constructed against it and fall through to the
     * offline set for anything not in its in-memory mempool.
     */
    public com.bloxbean.cardano.client.backend.api.UtxoService utxoService() {
        return new com.bloxbean.cardano.client.backend.api.UtxoService() {
            @Override
            public Result<List<Utxo>> getUtxos(String address, int count, int page) {
                return getUtxos(address, count, page, OrderEnum.asc);
            }

            @Override
            public Result<List<Utxo>> getUtxos(String address, int count, int page, OrderEnum order) {
                // Backend pages are 1-based; the supplier layer converts before calling in.
                return Result.success("ok").withValue(
                        utxoSupplier.getPage(address, count, Math.max(page - 1, 0), order));
            }

            @Override
            public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page) {
                return getUtxos(address, unit, count, page, OrderEnum.asc);
            }

            @Override
            public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page, OrderEnum order) {
                var filtered = utxoSupplier.getPage(address, count, Math.max(page - 1, 0), order).stream()
                        .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equalsIgnoreCase(a.getUnit())))
                        .toList();
                return Result.success("ok").withValue(filtered);
            }

            @Override
            public Result<Utxo> getTxOutput(String txHash, int outputIndex) {
                var found = utxoSupplier.getTxOutput(txHash, outputIndex);
                if (found.isEmpty()) {
                    return Result.error("utxo not found: " + txHash + "#" + outputIndex);
                }
                return Result.success("ok").withValue(found.get());
            }

            @Override
            public boolean isUsedAddress(String address) {
                return !utxoSupplier.getAll(address).isEmpty();
            }
        };
    }

    /**
     * Log every redeemer's tag/index/ex-units and the serialized size, and check that the numbers
     * are real evaluator output: not the pre-evaluation placeholder, not the ceiling fallback,
     * and inside the protocol maxima.
     *
     * @return the number of redeemers carrying genuine, sub-ceiling ex-units
     */
    public int reportAndCheckRedeemers(String label, Transaction transaction) throws Exception {
        var redeemers = transaction.getWitnessSet() == null
                ? null : transaction.getWitnessSet().getRedeemers();
        if (redeemers == null || redeemers.isEmpty()) {
            log.info("[{}] no redeemers (script-free transaction)", label);
            return 0;
        }

        var maxMem = new BigInteger(protocolParams.getMaxTxExMem());
        var maxSteps = new BigInteger(protocolParams.getMaxTxExSteps());
        int evaluated = 0;

        for (var redeemer : redeemers) {
            var ex = redeemer.getExUnits();
            log.info("[{}] redeemer tag={} index={} exUnits: mem={} steps={}",
                    label, redeemer.getTag(), redeemer.getIndex(), ex.getMem(), ex.getSteps());

            if (PLACEHOLDER_MEM.equals(ex.getMem()) && PLACEHOLDER_STEPS.equals(ex.getSteps())) {
                throw new AssertionError("[" + label + "] redeemer tag=" + redeemer.getTag()
                        + " index=" + redeemer.getIndex()
                        + " still carries the pre-evaluation placeholder — the evaluator never ran");
            }
            if (CEILING_FALLBACK_MEM.equals(ex.getMem()) && CEILING_FALLBACK_STEPS.equals(ex.getSteps())) {
                throw new AssertionError("[" + label + "] redeemer tag=" + redeemer.getTag()
                        + " index=" + redeemer.getIndex()
                        + " carries YaciConfiguration's ceilingCostFallback (1500000/800000000) —"
                        + " a script trap was laundered into a successful build");
            }
            if (POST_BALANCE_CERT_MEM.equals(ex.getMem()) && POST_BALANCE_CERT_STEPS.equals(ex.getSteps())) {
                log.warn("[{}] redeemer tag={} index={} carries HARDCODED ex-units"
                         + " (1000000/500000000), not evaluator output — it is injected in"
                         + " postBalanceTx, after script cost evaluation has already run,"
                         + " so no evaluator ever sees it", label, redeemer.getTag(), redeemer.getIndex());
                continue;
            }
            if (ex.getMem().signum() <= 0 || ex.getSteps().signum() <= 0) {
                throw new AssertionError("[" + label + "] non-positive ex-units");
            }
            if (ex.getMem().compareTo(maxMem) >= 0 || ex.getSteps().compareTo(maxSteps) >= 0) {
                throw new AssertionError("[" + label + "] ex-units at or above the protocol ceiling");
            }
            evaluated++;
        }

        int size = transaction.serialize().length;
        log.info("[{}] transaction.serialize().length = {} bytes (limit 16384, under={})",
                label, size, size < 16384);
        return evaluated;
    }

    // ---------------------------------------------------------------- UTxO set

    /** Seed a fabricated, deterministic ADA-only UTxO at {@code address}. */
    public Utxo seedAda(String label, String address, int outputIndex, long ada) {
        var txHash = HexUtil.encodeHexString(
                com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(
                        label.getBytes(StandardCharsets.UTF_8)));
        var utxo = Utxo.builder()
                .txHash(txHash)
                .outputIndex(outputIndex)
                .address(address)
                .amount(List.of(Amount.lovelace(
                        BigInteger.valueOf(ada).multiply(BigInteger.valueOf(1_000_000)))))
                .build();
        utxoSupplier.add(utxo);
        return utxo;
    }

    /**
     * Virtually submit {@code transaction}: every output becomes a spendable UTxO keyed by the
     * transaction's real hash, and every input it consumed is retired from the set.
     *
     * <p>Reference scripts carried in an output are registered with the script supplier too, so
     * a later transaction that reads them as a reference input can be evaluated.
     *
     * @return the submitted transaction's outputs, as UTxOs, in output order
     */
    public List<Utxo> submit(Transaction transaction) throws Exception {
        var txHash = TransactionUtil.getTxHash(transaction);

        for (var input : transaction.getBody().getInputs()) {
            utxoSupplier.remove(input.getTransactionId(), input.getIndex());
        }

        var outputs = transaction.getBody().getOutputs();
        var created = new ArrayList<Utxo>(outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            var out = outputs.get(i);

            String referenceScriptHash = null;
            if (out.getScriptRef() != null) {
                var script = ReferenceScriptUtil.deserializeScriptRef(out.getScriptRef());
                referenceScriptHash = HexUtil.encodeHexString(script.getScriptHash());
                if (script instanceof PlutusScript plutusScript) {
                    scriptSupplier.add(plutusScript);
                }
            }

            var utxo = Utxo.builder()
                    .txHash(txHash)
                    .outputIndex(i)
                    .address(out.getAddress())
                    .amount(ValueUtil.toAmountList(out.getValue()))
                    .inlineDatum(out.getInlineDatum() == null
                            ? null
                            : HexUtil.encodeHexString(out.getInlineDatum().serializeToBytes()))
                    .dataHash(out.getDatumHash() == null
                            ? null
                            : HexUtil.encodeHexString(out.getDatumHash()))
                    .referenceScriptHash(referenceScriptHash)
                    .build();
            utxoSupplier.add(utxo);
            created.add(utxo);
        }

        log.info("virtual submit: txHash={} created {} utxos, retired {} inputs",
                txHash, created.size(), transaction.getBody().getInputs().size());
        return created;
    }

    /** All currently-spendable UTxOs at {@code address}. */
    public List<Utxo> utxosAt(String address) {
        return utxoSupplier.getAll(address);
    }

    /**
     * The UTxO holding {@code unit} (policyId ++ assetNameHex), scanning the whole set.
     *
     * <p>Stands in for {@code UtxoProvider.findUtxoByAsset}, which in production is
     * Blockfrost-only — it never consults the local store — and so must be re-pointed for any
     * offline run.
     */
    public Optional<Utxo> findUtxoByUnit(String unit) {
        return utxoSupplier.all().stream()
                .filter(u -> u.getAmount().stream().anyMatch(a -> unit.equalsIgnoreCase(a.getUnit())))
                .findFirst();
    }

    // ---------------------------------------------------------------- protocol params

    /**
     * Realistic Conway-era protocol params. The load-bearing field is
     * {@code costModels.PlutusV3}: it is fed straight into the aiken/uplc machine as the
     * operating cost table, and cardano-client-lib compiles the post-Chang 251-entry model in as
     * {@code CostModelUtil.plutusV3Costs}, which is what makes offline phase-2 possible at all.
     *
     * <p>Caveat carried over from the bootstrap spike: preview's live V3 model is larger than
     * 251 entries post-2026-04-17, so ex-units measured here are indicative, not submit-exact.
     */
    public static ProtocolParams conwayProtocolParams() {
        var costModels = new LinkedHashMap<String, LinkedHashMap<String, Long>>();
        costModels.put("PlutusV1", indexedCostModel(CostModelUtil.PlutusV1CostModel.getCosts()));
        costModels.put("PlutusV2", indexedCostModel(CostModelUtil.PlutusV2CostModel.getCosts()));
        costModels.put("PlutusV3", indexedCostModel(CostModelUtil.plutusV3Costs));

        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .maxBlockSize(90112)
                .maxTxSize(16384)
                .maxBlockHeaderSize(1100)
                .keyDeposit("2000000")
                .poolDeposit("500000000")
                .eMax(18)
                .nOpt(500)
                .a0(new BigDecimal("0.3"))
                .rho(new BigDecimal("0.003"))
                .tau(new BigDecimal("0.2"))
                .decentralisationParam(BigDecimal.ZERO)
                .protocolMajorVer(10)
                .protocolMinorVer(0)
                .minUtxo("4310")
                .minPoolCost("170000000")
                .costModels(costModels)
                .priceMem(new BigDecimal("0.0577"))
                .priceStep(new BigDecimal("0.0000721"))
                .maxTxExMem("14000000")
                .maxTxExSteps("10000000000")
                .maxBlockExMem("62000000")
                .maxBlockExSteps("20000000000")
                .maxValSize("5000")
                .collateralPercent(new BigDecimal("150"))
                .maxCollateralInputs(3)
                .coinsPerUtxoSize("4310")
                .govActionDeposit(BigInteger.valueOf(100_000_000_000L))
                .drepDeposit(BigInteger.valueOf(500_000_000L))
                .drepActivity(20)
                .committeeMinSize(0)
                .committeeMaxTermLength(365)
                .govActionLifetime(6)
                .minFeeRefScriptCostPerByte(new BigDecimal("15"))
                .build();
    }

    /**
     * ProtocolParams stores each cost model as an ordered name→value map; CostModelUtil
     * re-derives the array by sorting on integer keys when they look numeric, so plain indices
     * reproduce the on-chain ordering exactly.
     */
    private static LinkedHashMap<String, Long> indexedCostModel(long[] costs) {
        var map = new LinkedHashMap<String, Long>();
        for (int i = 0; i < costs.length; i++) {
            map.put(String.valueOf(i), costs[i]);
        }
        return map;
    }

    // ---------------------------------------------------------------- suppliers

    /** Read-only view so callers can pass the supplier around without mutating the set. */
    public interface UtxoSupplierView extends com.bloxbean.cardano.client.api.UtxoSupplier {
    }

    static class InMemoryUtxoSupplier implements UtxoSupplierView {
        private final Map<String, List<Utxo>> byAddress = new LinkedHashMap<>();
        private final Map<String, Utxo> byOutRef = new HashMap<>();

        void add(Utxo utxo) {
            byAddress.computeIfAbsent(utxo.getAddress(), k -> new ArrayList<>()).add(utxo);
            byOutRef.put(key(utxo.getTxHash(), utxo.getOutputIndex()), utxo);
        }

        void remove(String txHash, int index) {
            var utxo = byOutRef.remove(key(txHash, index));
            if (utxo != null) {
                var at = byAddress.get(utxo.getAddress());
                if (at != null) {
                    at.remove(utxo);
                }
            }
        }

        private static String key(String txHash, int index) {
            return txHash + "#" + index;
        }

        /** Every spendable UTxO, regardless of address. */
        List<Utxo> all() {
            return List.copyOf(byOutRef.values());
        }

        @Override
        public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
            var all = byAddress.getOrDefault(address, List.of());
            int size = nrOfItems == null ? DEFAULT_NR_OF_ITEMS_TO_FETCH : nrOfItems;
            int pageIdx = page == null ? 0 : page;   // UtxoSupplier pages are 0-based
            int from = pageIdx * size;
            if (from >= all.size()) {
                return List.of();
            }
            return List.copyOf(all.subList(from, Math.min(from + size, all.size())));
        }

        @Override
        public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
            return Optional.ofNullable(byOutRef.get(key(txHash, outputIndex)));
        }

        @Override
        public boolean isUsedAddress(Address address) {
            return byAddress.containsKey(address.toBech32());
        }

        @Override
        public void setSearchByAddressVkh(boolean flag) {
            // Not supported offline: the fabricated set is keyed by full bech32 address.
        }
    }

    static class InMemoryScriptSupplier implements ScriptSupplier {
        private final Map<String, PlutusScript> byHash = new HashMap<>();

        void add(PlutusScript script) {
            try {
                byHash.put(HexUtil.encodeHexString(script.getScriptHash()), script);
            } catch (Exception e) {
                throw new IllegalStateException("cannot hash script", e);
            }
        }

        @Override
        public Optional<PlutusScript> getScript(String scriptHash) {
            return Optional.ofNullable(byHash.get(scriptHash));
        }
    }
}
