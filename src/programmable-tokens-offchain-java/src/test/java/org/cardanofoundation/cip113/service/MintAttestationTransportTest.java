package org.cardanofoundation.cip113.service;

import id.veridian.signify.generated.keria.model.Exn;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MintAttestationTransportTest {
    private MintAttestationIntentEntity intent() {
        var i = new MintAttestationIntentEntity(); i.setWalletKelFloor("9"); i.setWalletAid("wallet"); i.setIssuerAid("issuer"); i.setRequestSaid("request"); return i;
    }
    private Exn reply() { return new Exn().r("/remotesign/ixn/ref").d("reply").p("request").i("wallet").rp("issuer").a(Map.of("sn", "a")); }
    @Test void matchesTheRequestSenderReceiverRouteAndExplicitSequence() {
        assertEquals("a", MintAttestationTransport.matchingSequence(reply(), "reply", intent()));
        for (var bad : List.of(reply().p("old"), reply().i("other"), reply().rp("other"), reply().r("/ipex/admit"),
                reply().d("other"), reply().a(Map.of("sn", "<unknown>")), reply().a(Map.of("sn", 10)), reply().a(Map.of("sn", "9")), reply().a(Map.of("sn", "8"))))
            assertNull(MintAttestationTransport.matchingSequence(bad, "reply", intent()));
    }
    @Test void requiresSameAidSpecificEventAndDigestOnlySeal() {
        Map<String,Object> event = new LinkedHashMap<>(Map.of("t", "ixn", "i", "wallet", "s", "a", "a", List.of(Map.of("d", "digest"))));
        Map<String,Object> record = Map.of("ked", event, "atc", "verified-signatures");
        assertTrue(MintAttestationTransport.matchesEvent(record, "wallet", "a", "digest"));
        assertFalse(MintAttestationTransport.matchesEvent(record, "wallet", "b", "digest"));
        assertFalse(MintAttestationTransport.matchesEvent(record, "other", "a", "digest"));
        assertFalse(MintAttestationTransport.matchesEvent(record, "wallet", "a", "other"));
        event.put("a", List.of(Map.of("i", "credential", "s", "0", "d", "digest")));
        assertFalse(MintAttestationTransport.matchesEvent(record, "wallet", "a", "digest"));
    }
}
