package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link DefaultUtxoSupplier} that additionally resolves UTxOs which exist only
 *  as outputs of transactions the caller has built but not yet submitted — the
 *  mempool-chaining scratchpad every multi-tx builder in this codebase needs.
 *
 *  <p>The scratchpad is <b>per-thread</b>. This bean is a singleton, and the
 *  entries a chain builder registers are meaningful only to that one build: with
 *  a shared list, {@link #clear()} at the end of one build would delete the
 *  entries of any build running concurrently on another request thread, which
 *  surfaces as an unresolvable input in an unrelated transaction. A thread-local
 *  list gives each builder its own scratchpad while keeping the injection site
 *  and the API unchanged.
 *
 *  <p>Callers MUST still clear in a {@code finally} — a thread-local that is
 *  never released keeps stale UTxOs alive for the next request served by the
 *  same pool thread. */
@Service
@Slf4j
public class HybridUtxoSupplier extends DefaultUtxoSupplier {

    private final ThreadLocal<List<Utxo>> mempoolUtxo = ThreadLocal.withInitial(ArrayList::new);

    public HybridUtxoSupplier(UtxoService utxoService) {
        super(utxoService);
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        log.info("Get tx output: {}:{}", txHash, outputIndex);
        return mempoolUtxo.get().stream()
                .filter(utxo -> utxo.getTxHash().equals(txHash) && utxo.getOutputIndex() == outputIndex)
                .findFirst()
                .or(()->super.getTxOutput(txHash, outputIndex));
    }

    public void add(Utxo utxo) {
        log.info("adding utxo: {}", utxo);
        mempoolUtxo.get().add(utxo);
    }

    public void clear() {
        log.info("clearing utxos");
        // remove(), not clear(): drops the thread-local entry outright so a pooled
        // request thread carries nothing forward.
        mempoolUtxo.remove();
    }

}
