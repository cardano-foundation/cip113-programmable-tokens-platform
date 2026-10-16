package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/** Plan Addendum I13: two read-modify-writes of the same registration row cannot both win. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = RwaTokenCreationTransactionTest.Config.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:rwa_version;MODE=PostgreSQL;NON_KEYWORDS=KEY,NEXT;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RwaTokenRegistrationVersionTest {
    @Autowired RwaTokenRegistrationRepository repository;
    @MockBean org.cardanofoundation.cip113.service.module.ModuleHandlerFactory handlerFactory;
    @MockBean UtxoProvider utxoProvider;

    @Test
    void concurrentGsLogicsUpdateFails() {
        String policy = "ab".repeat(28);
        repository.save(RwaTokenRegistrationEntity.builder()
                .programmableTokenPolicyId(policy).issuerAdminPkh("01".repeat(28))
                .globalStatePolicyId("02".repeat(28)).denylistPolicyId("03".repeat(28))
                .powerUsersPolicyId("04".repeat(28)).securityAssetNameHex("aa")
                .bootstrapTxHash("05".repeat(32)).bootstrapOutputIndex(0)
                .gsLogics("[]").build());
        var first = repository.findByProgrammableTokenPolicyId(policy).orElseThrow();
        var second = repository.findByProgrammableTokenPolicyId(policy).orElseThrow();
        assertEquals(0L, first.getVersion());

        first.setGsLogics("[{\"logicHash\":\"v1\"}]");
        repository.save(first);
        second.setGsLogics("[{\"logicHash\":\"other\"}]");
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.save(second),
                "a stale gs_logics write must fail instead of silently dropping the other update");
        assertTrue(repository.findByProgrammableTokenPolicyId(policy).orElseThrow().getGsLogics().contains("v1"));
    }
}
