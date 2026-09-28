package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = MintAttestationStoreTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:mint_intent;MODE=PostgreSQL;NON_KEYWORDS=KEY,NEXT;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class MintAttestationStoreTest {
    @SpringBootConfiguration @EnableAutoConfiguration
    @EntityScan(basePackageClasses = MintAttestationIntentEntity.class)
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.cip113.repository",
            includeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = MintAttestationIntentRepository.class))
    @Import(MintAttestationStore.class)
    static class Config {}
    @Autowired MintAttestationStore store;
    @Autowired MintAttestationIntentRepository repository;
    @BeforeEach void prepare() {
        repository.deleteAll();
        var i = new MintAttestationIntentEntity();
        i.setWalletKelFloor("0"); i.setId("intent"); i.setSessionId("session"); i.setWalletAid("wallet"); i.setIssuerAid("issuer");
        i.setCredentialSaid("credential"); i.setFieldsJson("{}"); i.setDigest("digest"); i.setDocumentJson("{}");
        i.setPreimage("{}"); i.setStatus("PREPARED"); i.setExpiresAt(Instant.now().plusSeconds(600));
        store.create(i);
    }
    private void anchor() {
        String owner = store.claimAnchor("intent");
        store.saveAnchor("intent", owner, "a", "{}");
    }
    @Test void sendIsNotRepeatedAfterUncertainOutcome() {
        String first = store.claimAnchor("intent");
        assertTrue(store.beginDispatch("intent", first));
        store.releaseAnchor("intent", first);
        String second = store.claimAnchor("intent");
        assertFalse(store.beginDispatch("intent", second));
        assertThrows(RuntimeException.class, () -> store.saveAnchor("intent", first, "b", "{}"));
        store.saveAnchor("intent", second, "a", "{}");
        assertEquals("ANCHORED", store.get("intent").getStatus());
    }
    @Test void buildRequiresAnchorAndOnlyOneClaimCanPublish() {
        assertThrows(RuntimeException.class, () -> store.claimBuild("intent"));
        anchor();
        var first = store.claimBuild("intent");
        assertThrows(RuntimeException.class, () -> store.claimBuild("intent"));
        assertThrows(RuntimeException.class, () -> store.publishBuild("intent", "wrong", "80", "01".repeat(32)));
        assertEquals("80", store.publishBuild("intent", first.owner(), "80", "01".repeat(32)));
        var retry = store.claimBuild("intent");
        assertNull(retry.owner()); assertEquals("80", retry.cbor());
        assertThrows(RuntimeException.class, () -> store.publishBuild("intent", first.owner(), "81", "02".repeat(32)));
    }
    @Test void expiredBuildLeaseFencesOriginalWorkerAndCrashRetryGetsNewOwner() {
        anchor(); var first = store.claimBuild("intent");
        var i = repository.findById("intent").orElseThrow();
        i.setLeaseUntil(Instant.now().minusSeconds(1)); repository.saveAndFlush(i);
        var second = store.claimBuild("intent");
        assertNotEquals(first.owner(), second.owner());
        assertThrows(RuntimeException.class, () -> store.publishBuild("intent", first.owner(), "80", "01".repeat(32)));
        store.releaseBuild("intent", first.owner());
        assertEquals(second.owner(), store.get("intent").getClaimOwner());
        store.publishBuild("intent", second.owner(), "80", "01".repeat(32));
    }
    @Test void expiredDocumentCannotReturnEvenPreviouslyBuiltTransaction() {
        anchor(); var claim = store.claimBuild("intent"); store.publishBuild("intent", claim.owner(), "80", "01".repeat(32));
        var i = repository.findById("intent").orElseThrow(); i.setExpiresAt(Instant.now().minusSeconds(1)); repository.saveAndFlush(i);
        assertThrows(RuntimeException.class, () -> store.claimBuild("intent"));
        assertEquals("80", store.get("intent").getUnsignedCbor());
        assertEquals("{}", store.byDigest("digest").getPreimage());
    }
}
