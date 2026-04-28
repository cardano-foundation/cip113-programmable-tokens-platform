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

    private List<Utxo> getBlockfrostUtxos(String address) {
        try {
            var utxoResult = bfBackendService.getUtxoService().getUtxos(address, 100, 1);
            if (utxoResult.isSuccessful()) {
                return utxoResult.getValue();
            } else {
                log.warn("error: {}", utxoResult.getResponse());
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

}
