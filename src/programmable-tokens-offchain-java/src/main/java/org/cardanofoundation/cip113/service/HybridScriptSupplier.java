package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** {@link ScriptSupplier} that resolves reference scripts which exist only as
 *  outputs of transactions the caller has built but not yet submitted — the
 *  script-side twin of {@link HybridUtxoSupplier}.
 *
 *  <p>Why this is needed. A reference script is carried by a transaction OUTPUT,
 *  and neither {@code Utxo} nor the utxo supplier can hand the script BYTES to a
 *  consumer: {@code Utxo} only records a {@code referenceScriptHash}. Both
 *  {@link com.bloxbean.cardano.aiken.AikenTransactionEvaluator} and
 *  cardano-client's {@code ReferenceScriptResolver} therefore take that hash and
 *  ask a {@code ScriptSupplier} for the script. The default supplier asks the
 *  backend, which by construction cannot know about an unsubmitted output — so a
 *  mempool-chained reference script would silently fail to resolve, the local
 *  evaluator would error, and the app's three-tier evaluator would paper over it
 *  with {@code ceilingCostFallback} costs.
 *
 *  <p>Same threading contract as {@link HybridUtxoSupplier}: the scratchpad is
 *  <b>per-thread</b> so concurrent chain builds cannot clear each other's
 *  entries, and callers MUST {@link #clear()} in a {@code finally}. */
@Slf4j
public class HybridScriptSupplier implements ScriptSupplier {

    private final ScriptSupplier delegate;
    private final ThreadLocal<Map<String, PlutusScript>> pending =
            ThreadLocal.withInitial(HashMap::new);

    public HybridScriptSupplier(ScriptSupplier delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<PlutusScript> getScript(String scriptHash) {
        if (scriptHash == null || scriptHash.isEmpty()) {
            return Optional.empty();
        }
        PlutusScript local = pending.get().get(scriptHash.toLowerCase());
        if (local != null) {
            return Optional.of(local);
        }
        return delegate.getScript(scriptHash);
    }

    /** Register a script that a not-yet-submitted transaction publishes as a
     *  reference script, so consumers building on top of it can resolve it. */
    public void add(PlutusScript script) {
        try {
            String hash = HexUtil.encodeHexString(script.getScriptHash()).toLowerCase();
            pending.get().put(hash, script);
            log.info("adding pending reference script: {} ({} bytes)",
                    hash, script.serializeScriptBody().length);
        } catch (Exception e) {
            throw new IllegalStateException("could not hash reference script", e);
        }
    }

    public void clear() {
        pending.remove();
    }
}
