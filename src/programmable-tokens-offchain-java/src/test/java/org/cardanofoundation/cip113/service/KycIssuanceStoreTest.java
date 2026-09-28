package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.KycIssuanceEntity;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.repository.KycIssuanceRepository;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = KycIssuanceStoreTest.Config.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:kyc_issuance;MODE=PostgreSQL;NON_KEYWORDS=KEY,NEXT;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
class KycIssuanceStoreTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = KycSessionEntity.class)
    @EnableJpaRepositories(basePackages = "org.cardanofoundation.cip113.repository",
            includeFilters = @ComponentScan.Filter(type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                    classes = {KycSessionRepository.class, KycIssuanceRepository.class}))
    @Import(KycIssuanceStore.class)
    static class Config {}
    @Autowired KycIssuanceStore store;
    @Autowired KycSessionRepository sessions;
    @Autowired KycIssuanceRepository issuances;

    @BeforeEach
    void setup() {
        issuances.deleteAll();
        sessions.deleteAll();
        sessions.saveAndFlush(KycSessionEntity.builder().sessionId("session").aid("wallet").build());
    }

    @Test
    void claimBlocksDuplicateIssueAndRecipientChange() {
        store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}");
        assertThrows(IllegalStateException.class,
                () -> store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}"));
        assertThrows(IllegalStateException.class,
                () -> store.applyResolvedOobi("session", "https://wallet2/oobi/", "wallet2"));
        assertEquals("wallet", sessions.findById("session").orElseThrow().getAid());
        assertNull(sessions.findById("session").orElseThrow().getCredentialAid());
    }

    @Test
    void replayKeepsCredentialAndGrantAndPromotesOnlyAfterMatchingAdmit() {
        store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{\"firstName\":\"A\"}");
        store.saveIssued("session", "credential", "op", "{}", "{}", "{}");
        assertNull(sessions.findById("session").orElseThrow().getCredentialAid());
        String builder = store.claimGrantBuild("session");
        store.saveGrant("session", builder, "grant", "{}", "[]", "atc", "0", "digest");
        String first = store.claimDelivery("session");
        assertThrows(IllegalStateException.class, () -> store.claimDelivery("session"));
        assertThrows(IllegalStateException.class, () -> store.accept("session", first, "other-grant"));
        assertNull(sessions.findById("session").orElseThrow().getCredentialAid());
        store.releaseDelivery("session", first);
        String second = store.claimDelivery("session");
        assertNotEquals(first, second);
        KycIssuanceEntity replay = store.find("session").orElseThrow();
        assertEquals("credential", replay.getCredentialSaid());
        assertEquals("grant", replay.getGrantSaid());
        store.accept("session", second, "grant");
        store.releaseDelivery("session", first);
        assertEquals("credential", sessions.findById("session").orElseThrow().getCredentialAid());
        assertEquals("ACCEPTED", store.find("session").orElseThrow().getStatus());
    }

    @Test
    void presentationCannotReplacePendingCredential() {
        store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}");
        assertThrows(IllegalStateException.class,
                () -> store.reservePresentation("session", "wallet", "schema"));
    }

    @Test
    void interruptedGrantBuildCanRetryWithoutIssuingAgain() {
        store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}");
        store.saveIssued("session", "credential", "op", "{}", "{}", "{}");
        String owner = store.claimGrantBuild("session");
        store.releaseGrantBuild("session", owner);
        assertEquals("ISSUED", store.find("session").orElseThrow().getStatus());
        assertNotEquals(owner, store.claimGrantBuild("session"));
        assertThrows(IllegalStateException.class,
                () -> store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}"));
    }

    @Test
    void staleSessionWriteCannotEraseAcceptedOrUpdatedFields() {
        KycSessionEntity stale = sessions.findById("session").orElseThrow();
        store.applyResolvedOobi("session", "https://wallet/oobi/", "wallet");
        stale.setCardanoAddress("stale-address");
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> sessions.saveAndFlush(stale));
        assertNull(sessions.findById("session").orElseThrow().getCardanoAddress());
    }

    @Test
    void acceptedCredentialCanBePresentedAgainButNotReplaced() {
        String owner = store.reservePresentation("session", "wallet", "schema");
        store.acceptPresented("session", owner, "wallet", "credential", "schema", "{}", 0);
        store.acceptPresented("session", null, "wallet", "credential", "schema", "{}", 0);
        assertEquals("credential", sessions.findById("session").orElseThrow().getCredentialAid());
        assertThrows(IllegalStateException.class,
                () -> store.acceptPresented("session", null, "wallet", "other", "schema", "{}", 0));
        assertThrows(IllegalStateException.class,
                () -> store.acceptPresented("session", null, "wallet", "credential", "other-schema", "{}", 0));
        assertThrows(IllegalStateException.class,
                () -> store.acceptPresented("session", null, "wallet", "credential", "schema", "{}", 1));
    }

    @Test
    void presentationReservationBlocksIssueAndReleasesOnFailure() {
        String owner = store.reservePresentation("session", "wallet", "schema");
        store.releasePresentation("session", "wrong-owner");
        assertEquals("PRESENTING", store.find("session").orElseThrow().getStatus());
        assertThrows(IllegalStateException.class,
                () -> store.acceptPresented("session", "wrong-owner", "wallet", "credential", "schema", "{}", 0));
        assertThrows(IllegalStateException.class,
                () -> store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}"));
        store.releasePresentation("session", owner);
        store.claim("session", "wallet", "issuer", "schema", "https://schema/oobi/", "{}");
    }
}
