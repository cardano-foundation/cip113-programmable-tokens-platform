package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.AssetService;
import com.bloxbean.cardano.client.backend.api.EpochService;
import com.bloxbean.cardano.client.backend.api.ScriptService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.model.TxContentOutputAmount;
import com.bloxbean.cardano.client.backend.model.TxContentUtxo;
import com.bloxbean.cardano.client.backend.model.TxContentUtxoOutputs;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * cardano-client-lib's {@code BackendService} over an {@link OfflineChain}.
 *
 * <p>CCL resolves a deployment through a backend, not a UTxO supplier: the coordination UTxO via
 * the UTxO service, the issuance template and published reference scripts via
 * {@code getTransactionUtxos}, the protocol parameters via the epoch service. This answers each
 * from the chain.
 *
 * <p>{@code getTransactionUtxos} answers like a real backend: every output of the transaction,
 * spent or not, as far as this backend has seen it (the chain keeps no history, so an output
 * spent before it was first asked about is missing). CCL judges spentness itself, through the UTxO
 * supplier. The script service knows no scripts, so CCL witnesses only what it was given — nothing
 * reaches a live client. The asset service answers "no holders": the platform's deployments name
 * the template outref, and every handler reads global state itself ({@code readFrom}), so CCL
 * never needs the asset index.
 */
public final class OfflineBackend extends BFBackendService {

    /** Outputs per transaction scanned by {@code getTransactionUtxos}; the bootstrap emits < 16. */
    private static final int MAX_OUTPUTS = 64;

    private final OfflineChain chain;
    private final TransactionService transactions = Mockito.mock(TransactionService.class);
    private final AssetService assets = Mockito.mock(AssetService.class);
    private final EpochService epochs = Mockito.mock(EpochService.class);
    private final ScriptService scripts = Mockito.mock(ScriptService.class);
    private final Map<String, Map<Integer, TxContentUtxoOutputs>> seen = new ConcurrentHashMap<>();

    public OfflineBackend(OfflineChain chain) throws Exception {
        super("http://offline.invalid/api/v1/", "offline");
        this.chain = chain;
        Mockito.when(transactions.getTransactionUtxos(Mockito.anyString()))
                .thenAnswer(inv -> transactionUtxos(inv.getArgument(0)));
        Mockito.when(assets.getAllAssetAddresses(Mockito.anyString())).thenReturn(Result.success("ok").withValue(List.of()));
        Mockito.when(assets.getAllPolicyAssets(Mockito.anyString())).thenReturn(Result.success("ok").withValue(List.of()));
        Mockito.when(epochs.getProtocolParameters()).thenAnswer(inv -> Result.success("ok").withValue(chain.protocolParams()));
        Mockito.when(epochs.getProtocolParameters(Mockito.anyInt())).thenAnswer(inv -> Result.success("ok").withValue(chain.protocolParams()));
        Mockito.when(scripts.getPlutusScript(Mockito.anyString())).thenReturn(Result.error("offline: unknown script"));
    }

    @Override public UtxoService getUtxoService() { return chain.utxoService(); }
    @Override public TransactionService getTransactionService() { return transactions; }
    @Override public AssetService getAssetService() { return assets; }
    @Override public EpochService getEpochService() { return epochs; }
    @Override public ScriptService getScriptService() { return scripts; }

    /** Records every output of {@code txHash} still on the chain, so a later call still reports it once spent. */
    public void observe(String txHash) {
        var outputs = seen.computeIfAbsent(txHash, h -> new TreeMap<>());
        for (int i = 0; i < MAX_OUTPUTS; i++) {
            chain.utxoSupplier().getTxOutput(txHash, i).ifPresent(utxo -> outputs.put(utxo.getOutputIndex(),
                    TxContentUtxoOutputs.builder()
                            .address(utxo.getAddress())
                            .outputIndex(utxo.getOutputIndex())
                            .inlineDatum(utxo.getInlineDatum())
                            .dataHash(utxo.getDataHash())
                            .referenceScriptHash(utxo.getReferenceScriptHash())
                            .amount(utxo.getAmount().stream().map(OfflineBackend::amount).toList())
                            .build()));
        }
    }

    private Result<TxContentUtxo> transactionUtxos(String txHash) {
        observe(txHash);
        var outputs = seen.get(txHash);
        if (outputs.isEmpty()) return Result.error("unknown transaction " + txHash);
        return Result.success("ok").withValue(TxContentUtxo.builder()
                .inputs(List.of()).outputs(List.copyOf(outputs.values())).build());
    }

    private static TxContentOutputAmount amount(Amount a) {
        return new TxContentOutputAmount(a.getUnit(), a.getQuantity().toString());
    }
}
