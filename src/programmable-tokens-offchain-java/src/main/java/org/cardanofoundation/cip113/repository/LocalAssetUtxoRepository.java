package org.cardanofoundation.cip113.repository;

import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Locate an unspent UTxO by the asset it holds, using the backend's OWN chain index.
 *
 * <h2>Why this exists</h2>
 *
 * {@code UtxoProvider.findUtxoByAsset} resolves a UTxO by asking the Blockfrost-compatible
 * asset index: "which address holds this unit?", then listing that address's UTxOs. On the
 * public networks that works. On a local devnet it does not — yaci-store answers
 * {@code /assets/{unit}/addresses} with {@code 200 []} and does not implement
 * {@code /assets/{unit}} at all. An empty-but-successful response is indistinguishable from a
 * confirmed absence, so every asset lookup silently reported "not on chain" for assets that
 * were demonstrably there. That surfaced as
 * {@code global-state UTxO not found on chain for <policy>} while the UTxO sat in a block.
 *
 * <p>The backend does not need that index. It <em>is</em> a chain indexer: it writes
 * {@code address_utxo} itself, from the node it is synced to, on every network. So the same
 * question can be answered locally and identically on devnet, preview, preprod and mainnet —
 * which is why this is the primary lookup rather than a devnet special case.
 *
 * <h2>Unspent</h2>
 *
 * yaci-store keeps no {@code spent} flag on {@code address_utxo}; consumption is recorded by
 * a row in {@code tx_input}. "Unspent" is therefore the absence of such a row, which is what
 * the {@code NOT EXISTS} below expresses — the same shape {@code UtxoRepository}'s own
 * {@code findUnspentBy*} queries use.
 *
 * <p>The query is native because {@code address_utxo.amounts} is a {@code jsonb} column, not
 * a mapped association, so JPQL cannot see inside it. {@code @>} is a containment test
 * against a one-element array, which is index-friendly and matches on {@code unit} alone
 * regardless of the quantity or the other assets in the same UTxO.
 */
public interface LocalAssetUtxoRepository extends JpaRepository<AddressUtxoEntity, String> {

    /**
     * @param unitJson a JSON array holding the single unit to match, e.g.
     *                 {@code [{"unit":"<policyId><assetNameHex>"}]}. Passed pre-serialised
     *                 because binding a jsonb parameter portably through JPA is otherwise
     *                 awkward; {@code UtxoProvider} builds it.
     */
    @Query(value = """
            SELECT u.* FROM address_utxo u
            WHERE u.amounts @> CAST(:unitJson AS jsonb)
              AND NOT EXISTS (
                  SELECT 1 FROM tx_input i
                  WHERE i.tx_hash = u.tx_hash AND i.output_index = u.output_index)
            ORDER BY u.slot DESC
            """, nativeQuery = true)
    List<AddressUtxoEntity> findUnspentHoldingUnit(@Param("unitJson") String unitJson);
}
