package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.easy1staking.cardano.util.UtxoUtil;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UtxoProvider {

    private final BFBackendService bfBackendService;

    @Nullable
    private final UtxoRepository utxoRepository;

    public Optional<Utxo> findUtxo(String txHash, int outputIndex) {

        if (utxoRepository == null) {
            try {
                var utxoResult = bfBackendService.getUtxoService().getTxOutput(txHash, outputIndex);
                if (utxoResult.isSuccessful()) {
                    return Optional.of(utxoResult.getValue());
                } else {
                    return Optional.empty();
                }
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
        } else {
            return utxoRepository.findById(UtxoId.builder()
                            .txHash(txHash)
                            .outputIndex(outputIndex)
                            .build())
                    .map(UtxoUtil::toUtxo);
        }

    }

    public List<Utxo> findUtxos(String address) {

        if (utxoRepository == null) {
            return getBlockfrostUtxos(address);
        } else {
            var utxos = utxoRepository.findUnspentByOwnerAddr(address, Pageable.unpaged())
                    .stream()
                    .flatMap(Collection::stream)
                    .map(UtxoUtil::toUtxo)
                    .toList();

            if (utxos.isEmpty()) {
                log.info("No UTxos found for address {}", address);
                // falling back on blockfrost if indexer is behind
                return getBlockfrostUtxos(address);
            } else {
                return utxos;
            }

        }

    }

    /** Fetch UTxOs straight from Blockfrost, bypassing the YACI cache — used by paths
     *  that cannot tolerate stale indexer state (e.g. the autonomous root-sync job). */
    public List<Utxo> findUtxosFromBlockfrost(String address) {
        return getBlockfrostUtxos(address);
    }

    private List<Utxo> getBlockfrostUtxos(String address) {
        try {
            var utxoResult = bfBackendService.getUtxoService().getUtxos(address, 100, 1);
            if (utxoResult.isSuccessful()) {
                return utxoResult.getValue();
            } else {
                if (utxoResult.code() == 404) {
                    log.debug("No UTxOs at address {} (404)", address);
                } else {
                    log.warn("Blockfrost UTxO lookup failed for address {}: {}", address, utxoResult.getResponse());
                }
                return List.of();
            }
        } catch (ApiException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Utxo> findUtxosByPaymentPkh(String paymentPkh) {

        if (utxoRepository == null) {
            throw new RuntimeException("Unsupported");
        } else {
            return utxoRepository.findUnspentByOwnerPaymentCredential(paymentPkh, Pageable.unpaged())
                    .stream()
                    .flatMap(Collection::stream)
                    .map(UtxoUtil::toUtxo)
                    .toList();
        }

    }

    /**
     * Find UTxOs by stake credential (delegation credential).
     * For programmable tokens, the user's payment PKH is used as the stake credential.
     *
     * @param stakePkh The stake key hash / delegation credential
     * @return List of UTxOs with this stake credential
     */
    public List<Utxo> findUtxosByStakePkh(String stakePkh) {

        if (utxoRepository == null) {
            throw new RuntimeException("Unsupported - requires local UTXO repository");
        } else {
            return utxoRepository.findUnspentByOwnerStakeCredential(stakePkh, Pageable.unpaged())
                    .stream()
                    .flatMap(Collection::stream)
                    .map(UtxoUtil::toUtxo)
                    .toList();
        }

    }

    /**
     * Find UTxOs containing tokens from a given policy ID.
     * Uses Blockfrost Asset API to locate addresses holding the policy's assets,
     * then retrieves UTxOs at those addresses.
     *
     * @param policyId The minting policy ID
     * @return List of UTxOs containing tokens from this policy
     */
    public List<Utxo> findUtxosByPolicy(String policyId) {
        try {
            var assetService = bfBackendService.getAssetService();

            var assetsResult = assetService.getAllPolicyAssets(policyId);
            if (!assetsResult.isSuccessful() || assetsResult.getValue().isEmpty()) {
                log.warn("No assets found for policy: {}", policyId);
                return List.of();
            }

            var firstAssetUnit = assetsResult.getValue().getFirst().getAsset();

            var addressesResult = assetService.getAssetAddresses(firstAssetUnit, 1, 1);
            if (!addressesResult.isSuccessful() || addressesResult.getValue().isEmpty()) {
                log.warn("No addresses found for asset: {}", firstAssetUnit);
                return List.of();
            }

            var address = addressesResult.getValue().getFirst().getAddress();

            var utxoResult = bfBackendService.getUtxoService().getUtxos(address, firstAssetUnit, 100, 1);
            if (utxoResult.isSuccessful()) {
                return utxoResult.getValue();
            }

            log.warn("No UTxOs found for asset {} at address {}", firstAssetUnit, address);
            return List.of();

        } catch (Exception e) {
            throw new RuntimeException("Failed to find UTxOs by policy: " + policyId, e);
        }
    }

    /** Find the UTxO holding the specific NFT {@code (policyId, assetNameHex)}.
     *
     *  <p>Unlike {@link #findUtxosByPolicy} which only looks at the first asset
     *  under a policy (and therefore misses linked-list node NFTs whose policy
     *  also has a root NFT), this helper goes straight to the asset's
     *  Blockfrost record, finds the single address holding it, and returns the
     *  matching UTxO. Used by the admin-action handlers to resolve specific
     *  power-user / denylist nodes by their unique asset name.
     *
     *  @return the UTxO holding the NFT, or {@link Optional#empty()} if the
     *          asset isn't indexed yet or has no on-chain holder. */
    public Optional<Utxo> findUtxoByAsset(String policyId, String assetNameHex) {
        try {
            String unit = policyId + assetNameHex.toLowerCase();
            var addressesResult = bfBackendService.getAssetService().getAssetAddresses(unit, 1, 1);
            if (!addressesResult.isSuccessful() || addressesResult.getValue() == null
                    || addressesResult.getValue().isEmpty()) {
                log.debug("findUtxoByAsset({}): no addresses found for asset", unit);
                return Optional.empty();
            }
            String address = addressesResult.getValue().getFirst().getAddress();

            var utxosResult = bfBackendService.getUtxoService().getUtxos(address, unit, 100, 1);
            if (!utxosResult.isSuccessful() || utxosResult.getValue() == null
                    || utxosResult.getValue().isEmpty()) {
                log.debug("findUtxoByAsset({}): no UTxOs found at {}", unit, address);
                return Optional.empty();
            }
            // Blockfrost can return more than one UTxO at the address that
            // contains the unit. The linked-list invariant guarantees the NFT
            // is unique (quantity == 1 in exactly one UTxO), so the first hit
            // that actually contains the asset is the right one.
            for (Utxo u : utxosResult.getValue()) {
                boolean hasAsset = u.getAmount().stream()
                        .anyMatch(a -> unit.equalsIgnoreCase(a.getUnit()));
                if (hasAsset) return Optional.of(u);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("findUtxoByAsset({}, {}) failed: {}", policyId, assetNameHex, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Tri-state answer to "does this asset exist on chain right now".
     *
     * <p>{@link #findUtxoByAsset} collapses every failure into {@link Optional#empty()}, which is
     * indistinguishable from a confirmed absence. That is fine for a lookup whose caller only
     * wants to spend the UTxO if it happens to be there, and wrong for a caller deciding whether
     * to <em>mint</em> something that must exist at most once: a throttled or unreachable
     * Blockfrost would read as "not minted yet" and authorise a duplicate.
     */
    public enum AssetPresence {
        /** The chain has this asset. */
        PRESENT,
        /** The chain positively does not have this asset. */
        ABSENT,
        /** The lookup could not be completed — this is NOT evidence of absence. */
        UNKNOWN
    }

    /**
     * Whether {@code (policyId, assetNameHex)} exists on chain, distinguishing a confirmed
     * absence from a lookup that could not be completed.
     *
     * <p>Existence is answered from the asset's address records rather than from a UTxO scan:
     * an asset Blockfrost knows the holders of exists, whether or not a subsequent UTxO query
     * agrees. A 404 is Blockfrost's positive statement that it has never indexed the asset and
     * is the only failure mapped to {@link AssetPresence#ABSENT}; every other non-2xx, and every
     * transport failure, is {@link AssetPresence#UNKNOWN}.
     */
    public AssetPresence assetPresence(String policyId, String assetNameHex) {
        String unit = policyId + assetNameHex.toLowerCase();
        try {
            var addressesResult = bfBackendService.getAssetService().getAssetAddresses(unit, 1, 1);
            if (addressesResult.isSuccessful()) {
                var holders = addressesResult.getValue();
                return holders == null || holders.isEmpty()
                        ? AssetPresence.ABSENT : AssetPresence.PRESENT;
            }
            if (addressesResult.code() == 404) {
                log.debug("assetPresence({}): 404, asset has never been indexed", unit);
                return AssetPresence.ABSENT;
            }
            log.warn("assetPresence({}): lookup failed with HTTP {} ({}) — reporting UNKNOWN, "
                     + "NOT absence", unit, addressesResult.code(), addressesResult.getResponse());
            return AssetPresence.UNKNOWN;
        } catch (Exception e) {
            log.warn("assetPresence({}) threw {} — reporting UNKNOWN, NOT absence",
                    unit, e.toString());
            return AssetPresence.UNKNOWN;
        }
    }
}
