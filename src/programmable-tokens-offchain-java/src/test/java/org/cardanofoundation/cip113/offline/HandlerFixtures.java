package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.core.CoreBlueprint;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.service.ProtocolScriptBuilderService;
import org.cardanofoundation.cip113.service.SubstandardService;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Wires the production substandard handlers to an {@link OfflineChain} instead of Spring, a
 * database and a node.
 *
 * <p>The point is to exercise the <em>real</em> handler code — the CIP-68 branch under test lives
 * inside {@code buildRegistrationTransaction} / {@code buildMintTransaction}, and a test that
 * re-implemented that logic would only prove the test agrees with itself. So every collaborator
 * here is either the real service (when it is a plain constructor-injected POJO that reads only
 * the classpath) or the thinnest possible adapter onto the offline chain.
 *
 * <p>The handlers all call {@code quickTxBuilder.compose(tx)...build()} without ever setting
 * {@code withTxEvaluator(...)}. QuickTxBuilder falls back to the injected
 * {@code TransactionProcessor} as the evaluator, so handing them a processor backed by
 * {@code AikenTransactionEvaluator} makes the production path phase-2 evaluate for real,
 * untouched.
 */
@Slf4j
public final class HandlerFixtures {

    public static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** {@code AppConfig.Network("devnet")} resolves to Network(0, 42) — the fixture's network. */
    public static final AppConfig.Network NETWORK = new AppConfig.Network("devnet");

    private HandlerFixtures() {
    }

    /** The real SubstandardService, loading {@code classpath:substandards/&#42;/plutus.json}. */
    public static SubstandardService substandardService() {
        var service = new SubstandardService(OBJECT_MAPPER);
        service.init();
        return service;
    }

    /**
     * The real {@link ProtocolScriptBuilderService}, over the real {@link CoreScriptFactory}
     * and {@link CoreBlueprint}.
     *
     * <p>Nothing is mocked any more. {@code CoreBlueprint} reads {@code plutus.json} off the
     * classpath, which is the same file {@link BootstrapFixture#protocolValidators()} reads,
     * so the scripts these handlers build are parameterised from exactly the bytes the
     * fixture deployed. Previously this stubbed {@code ProtocolBootstrapService} to answer
     * blueprint-title lookups, because the real bootstrap service also loads
     * {@code protocol-bootstraps-devnet.json} — a committed deployment record that has
     * nothing to do with the protocol the fixture just built. Resolving the blueprint is now
     * separate from resolving a deployment, so that stub is no longer needed.
     */
    public static ProtocolScriptBuilderService protocolScriptBuilderService() throws Exception {
        return new ProtocolScriptBuilderService(new CoreScriptFactory(new CoreBlueprint()));
    }

    /** Convert an offline-chain UTxO into the JPA entity the yaci-store repositories return. */
    public static AddressUtxoEntity toEntity(Utxo utxo) {
        var amts = utxo.getAmount().stream()
                .map(a -> {
                    String unit = a.getUnit();
                    String policyId = "lovelace".equals(unit) || unit.length() < 56
                            ? null : unit.substring(0, 56);
                    String assetName = "lovelace".equals(unit) || unit.length() <= 56
                            ? null : unit.substring(56);
                    return Amt.builder()
                            .unit(unit)
                            .policyId(policyId)
                            .assetName(assetName)
                            .quantity(a.getQuantity())
                            .build();
                })
                .toList();

        var lovelace = utxo.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(com.bloxbean.cardano.client.api.model.Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);

        var address = new com.bloxbean.cardano.client.address.Address(utxo.getAddress());

        return AddressUtxoEntity.builder()
                .txHash(utxo.getTxHash())
                .outputIndex(utxo.getOutputIndex())
                .ownerAddr(utxo.getAddress())
                .ownerAddrFull(utxo.getAddress())
                .ownerPaymentCredential(address.getPaymentCredentialHash()
                        .map(com.bloxbean.cardano.client.util.HexUtil::encodeHexString).orElse(null))
                .ownerStakeCredential(address.getDelegationCredentialHash()
                        .map(com.bloxbean.cardano.client.util.HexUtil::encodeHexString).orElse(null))
                .lovelaceAmount(lovelace)
                .amounts(amts)
                .dataHash(utxo.getDataHash())
                .inlineDatum(utxo.getInlineDatum())
                .referenceScriptHash(utxo.getReferenceScriptHash())
                .isCollateralReturn(Boolean.FALSE)
                .build();
    }

    public static List<AddressUtxoEntity> toEntities(List<Utxo> utxos) {
        return utxos.stream().map(HandlerFixtures::toEntity).toList();
    }

    /**
     * A {@link UtxoRepository} that answers the three lookups the dummy handler makes, out of the
     * live offline chain, so a virtually-submitted transaction is immediately visible to the next
     * handler call.
     *
     * @param chain              the offline chain to read through to
     * @param registryAddress    the directory-spend address whose UTxOs are the registry nodes
     * @param registryPaymentCred the payment credential the handler queries registry nodes by
     */
    public static UtxoRepository utxoRepository(OfflineChain chain,
                                                String registryAddress,
                                                String registryPaymentCred) {
        var repo = Mockito.mock(UtxoRepository.class);

        Mockito.when(repo.findById(any(UtxoId.class))).thenAnswer(inv -> {
            UtxoId id = inv.getArgument(0);
            return chain.utxoSupplier().getTxOutput(id.getTxHash(), id.getOutputIndex())
                    .map(HandlerFixtures::toEntity);
        });

        Mockito.when(repo.findUnspentByOwnerAddr(anyString(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    String addr = inv.getArgument(0);
                    return Optional.of(toEntities(chain.utxosAt(addr)));
                });

        Mockito.when(repo.findUnspentByOwnerPaymentCredential(eq(registryPaymentCred), any(Pageable.class)))
                .thenAnswer(inv -> Optional.of(toEntities(chain.utxosAt(registryAddress))));

        return repo;
    }
}
