package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.service.ProtocolBootstrapService;
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
     * The real {@link ProtocolScriptBuilderService}, backed by a bootstrap service that resolves
     * compiled validators straight out of {@code src/main/resources/plutus.json}.
     *
     * <p>Mocked rather than {@code init()}-ed on purpose: the real init also loads
     * {@code protocol-bootstraps-devnet.json}, and this test's protocol is the one
     * {@link BootstrapFixture} just built, not whatever is committed in that file.
     */
    public static ProtocolScriptBuilderService protocolScriptBuilderService() throws Exception {
        var validators = BootstrapFixture.protocolValidators();
        var bootstrapService = Mockito.mock(ProtocolBootstrapService.class);
        Mockito.when(bootstrapService.getProtocolContract(anyString())).thenAnswer(inv -> {
            String title = inv.getArgument(0);
            return validators.stream()
                    .filter(v -> v.title().equals(title))
                    .findAny()
                    .map(org.cardanofoundation.cip113.model.blueprint.Validator::compiledCode);
        });
        return new ProtocolScriptBuilderService(bootstrapService);
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
