package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity;
import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.repository.RwaGenesisFundingReservationRepository;
import org.cardanofoundation.cip113.repository.RwaGenesisReservationRepository;
import org.cardanofoundation.cip113.repository.RwaTokenCreationRequestNonceRepository;
import org.cardanofoundation.cip113.repository.RwaTokenMemberRootSnapshotRepository;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises actual repository constraints and the proxied creation transaction. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = RwaTokenCreationTransactionTest.Config.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:rwa_creation;MODE=PostgreSQL;NON_KEYWORDS=KEY,NEXT;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Sql(statements = {
        "ALTER TABLE rwa_genesis_reservation ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP",
        "ALTER TABLE rwa_genesis_funding_reservation ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP"
})
class RwaTokenCreationTransactionTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = RwaTokenRegistrationEntity.class)
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.cip113.repository",
            includeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = {RwaTokenRegistrationRepository.class, RwaTokenMemberRootSnapshotRepository.class,
                            RwaGenesisReservationRepository.class, RwaGenesisFundingReservationRepository.class,
                            RwaTokenCreationRequestNonceRepository.class}))
    @Import(RwaTokenCreationService.class)
    static class Config {}
    private static final String TOKEN = "ab".repeat(28);
    private static final String GS = "cd".repeat(28);
    private static final String INPUT = "ef".repeat(32) + "#0";

    @Autowired RwaTokenCreationService creation;
    @Autowired RwaTokenRegistrationRepository registrations;
    @Autowired RwaTokenMemberRootSnapshotRepository snapshots;
    @Autowired RwaGenesisReservationRepository genesisReservations;
    @Autowired RwaGenesisFundingReservationRepository fundingReservations;
    @Autowired RwaTokenCreationRequestNonceRepository nonces;
    @Autowired PlatformTransactionManager transactions;
    @MockBean ModuleHandlerFactory handlerFactory;
    @MockBean UtxoProvider utxoProvider;

    @Test
    void failedLateBuildRollsBackEveryRowButKeepsConsumedNonce() {
        var tx = new TransactionTemplate(transactions);
        String nonce = "01".repeat(32);
        assertEquals(Integer.valueOf(1), tx.execute(status -> nonces.consume(nonce, GS, Instant.now().plusSeconds(300))));
        var handler = mock(RwaTokenModuleHandler.class);
        when(handlerFactory.getHandler(eq("rwa-token"), any())).thenReturn(handler);
        when(handler.buildFullRegistrationChain(any(), any())).thenAnswer(inv -> {
            registrations.saveAndFlush(RwaTokenRegistrationEntity.builder()
                    .programmableTokenPolicyId(TOKEN).globalStatePolicyId(GS)
                    .issuerAdminPkh(GS).denylistPolicyId(GS).powerUsersPolicyId(GS).build());
            var snapshot = new RwaTokenMemberRootSnapshotEntity();
            snapshot.setProgrammableTokenPolicyId(TOKEN);
            snapshot.setRootHash("00".repeat(32));
            snapshot.setBaselineRootHash("00".repeat(32));
            snapshot.setLeavesJson("[]");
            snapshots.saveAndFlush(snapshot);
            assertEquals(1, genesisReservations.claim(GS, "ef".repeat(32), 0));
            assertEquals(1, fundingReservations.claim(INPUT, GS));
            return TransactionContext.typedError("chain[registration]: deliberately failed");
        });
        assertThrows(RwaTokenCreationService.BuildFailed.class,
                () -> creation.buildChain(RwaTokenRegisterRequest.builder().build(), null));
        assertFalse(registrations.existsById(TOKEN));
        assertTrue(snapshots.findByProgrammableTokenPolicyIdAndRootHash(TOKEN, "00".repeat(32)).isEmpty());
        assertFalse(genesisReservations.existsById(GS));
        assertFalse(fundingReservations.existsById(INPUT));
        assertTrue(nonces.existsById(nonce));
        assertEquals(Integer.valueOf(1), tx.execute(status -> fundingReservations.claim(INPUT, GS)),
                "a new signed request may reuse funding from a failed build");
    }

    @Test
    void concurrentNonceAndFundingClaimsHaveOneWinnerEach() throws Exception {
        assertEquals(1, concurrentClaims(() -> nonces.consume("02".repeat(32), GS,
                Instant.now().plusSeconds(300))));
        assertEquals(1, concurrentClaims(() -> fundingReservations.claim(
                "fe".repeat(32) + "#0", GS)));
    }

    private long concurrentClaims(java.util.concurrent.Callable<Integer> claim) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return new TransactionTemplate(transactions).execute(status -> {
                    try { return claim.call(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            long wins = 0;
            for (var task : tasks) wins += task.get(10, TimeUnit.SECONDS);
            return wins;
        }
    }
}
