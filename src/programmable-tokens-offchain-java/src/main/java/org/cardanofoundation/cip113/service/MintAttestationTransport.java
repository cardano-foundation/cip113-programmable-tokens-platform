package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.cesr.Serder;
import id.veridian.signify.generated.keria.model.Exn;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.keri.IdentifierConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.*;

/** Uses the deployment's authenticated KERIA verifier; never treats an EXN reply as KEL proof. */
@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class MintAttestationTransport {
    private final SignifyClient client;
    private final IdentifierConfig identifier;
    private final ObjectMapper mapper;
    public record Anchor(String sequence, String eventJson, String notificationId) {}

    public String issuerAid() { return identifier.getPrefix(); }

    public void prepareExchange(MintAttestationIntentEntity intent, Map<String, Object> document) throws Exception {
        var walletState = client.keyStates().get(intent.getWalletAid()).orElseThrow(() -> new IllegalStateException("Wallet key state unavailable; reconnect Veridian"));
        if (walletState.getS() == null || !walletState.getS().matches("[0-9a-f]{1,32}"))
            throw new IllegalStateException("Wallet key state has no valid sequence");
        intent.setWalletKelFloor(new BigInteger(walletState.getS(), 16).toString(16));
        var hab = client.identifiers().get(identifier.getName()).orElseThrow(() -> new IllegalStateException("Issuer identifier missing"));
        if (!Objects.equals(hab.getPrefix(), intent.getIssuerAid())) throw new IllegalStateException("Issuer identifier changed");
        var exchange = client.exchanges().createExchangeMessage(hab, "/remotesign/ixn/req", document,
                Map.of(), intent.getWalletAid(), null, null);
        intent.setRequestSaid((String) exchange.exn().getKed().get("d"));
        intent.setRequestJson(exchange.exn().getRaw());
        intent.setRequestSigs(mapper.writeValueAsString(exchange.sigs()));
        intent.setRequestAtc(exchange.atc());
    }

    public Anchor sendAndWait(MintAttestationIntentEntity intent, boolean dispatch) throws Exception {
        // The dispatch marker is committed before send. An uncertain send is never repeated.
        if (dispatch) {
            Map<String, Object> request = mapper.readValue(intent.getRequestJson(), new TypeReference<LinkedHashMap<String, Object>>() {});
            Serder serder = new Serder(request);
            if (!Objects.equals(intent.getRequestSaid(), serder.getKed().get("d"))) throw new IllegalStateException("Stored signing request corrupt");
            List<String> signatures = mapper.readValue(intent.getRequestSigs(), new TypeReference<List<String>>() {});
            client.exchanges().sendFromEvents(identifier.getName(), "remotesign", serder, signatures,
                    intent.getRequestAtc(), List.of(intent.getWalletAid()));
            log.info("Mint signing request submitted intent={} requestSaid={} walletAid={}",
                    intent.getId(), intent.getRequestSaid(), intent.getWalletAid());
        }
        for (int attempt = 0; attempt < 60; attempt++) {
            int start = 0;
            int total = Integer.MAX_VALUE;
            while (start < total) {
                var response = client.notifications().list(start, start + 24);
                total = Math.min(total, response.total());
                if (response.notes() == null || response.notes().isEmpty()) break;
                for (var note : response.notes()) {
                    if (note == null || note.getA() == null || note.getI() == null
                            || !("/exn/remotesign/ixn/ref".equals(note.getA().getR())
                              || "/remotesign/ixn/ref".equals(note.getA().getR()))
                            || note.getA().getD() == null) continue;
                    try {
                        var exchange = client.exchanges().get(note.getA().getD());
                        if (exchange.isEmpty()) continue;
                        String sequence = matchingSequence(exchange.get().getExn(), note.getA().getD(), intent);
                        if (sequence == null) continue;
                        String event = verifiedEvent(intent.getWalletAid(), sequence, intent.getDigest());
                        if (event != null) return new Anchor(sequence, event, note.getI());
                    } catch (RuntimeException badNotification) {
                        log.warn("Could not inspect remote-sign exchange said={}; retaining notification",
                                note.getA().getD(), badNotification);
                    }
                }
                if (response.end() < start) break;
                start = response.end() + 1;
            }
            if (attempt % 10 == 0) log.info("Waiting for verified mint anchor intent={} attempt={}/60", intent.getId(), attempt + 1);
            if (attempt < 59) Thread.sleep(2000);
        }
        throw new java.util.concurrent.TimeoutException("No verified KERI approval yet. Retry this signing request; the mint intent is unchanged.");
    }

    static String matchingSequence(Exn exn, String notificationSaid, MintAttestationIntentEntity intent) {
        if (exn == null || !"/remotesign/ixn/ref".equals(exn.getR())
                || !Objects.equals(notificationSaid, exn.getD()) || !Objects.equals(intent.getRequestSaid(), exn.getP())
                || !Objects.equals(intent.getWalletAid(), exn.getI()) || !Objects.equals(intent.getIssuerAid(), exn.getRp())
                || !(exn.getA() instanceof Map<?, ?> attrs) || !(attrs.get("sn") instanceof String sequence)
                || !sequence.matches("[0-9a-f]{1,32}")) return null;
        if (intent.getWalletKelFloor() == null || new BigInteger(sequence, 16).compareTo(new BigInteger(intent.getWalletKelFloor(), 16)) <= 0)
            return null;
        return new BigInteger(sequence, 16).toString(16);
    }

    public String verifiedEvent(String aid, String sequence, String digest) throws Exception {
        if (!aid.matches("[A-Za-z0-9_-]{44}") || !sequence.matches("[0-9a-f]{1,32}"))
            throw new IllegalArgumentException("Invalid KEL event reference");
        var events = client.fetch("/events?pre=" + aid, "GET", null);
        if (events.statusCode() == 404) return null;
        if (events.statusCode() != 200) throw new IllegalStateException("KERIA could not provide verified key events");
        var eventList = mapper.readValue(events.body(), new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> record : eventList) {
            if (matchesEvent(record, aid, sequence, digest)) return mapper.writeValueAsString(record);
        }
        // Request synchronization of this event, not a guess at the identifier's latest sequence.
        // Completion is observed on a later bounded polling iteration through the verified event log.
        client.keyStates().query(aid, sequence);
        return null;
    }

    static boolean matchesEvent(Map<String, Object> record, String aid, String sequence, String digest) {
        if (!(record.get("ked") instanceof Map<?, ?> event) || !"ixn".equals(event.get("t")) || !aid.equals(event.get("i"))
                || !(event.get("s") instanceof String seq) || !seq.matches("[0-9a-f]{1,32}")
                || !new BigInteger(sequence, 16).equals(new BigInteger(seq, 16))
                || !(event.get("a") instanceof List<?> seals)) return false;
        // KERIA /events enumerates its first-seen (accepted) KEL, not unverified escrows.
        return seals.stream().anyMatch(s -> s instanceof Map<?, ?> seal && seal.size() == 1 && digest.equals(seal.get("d")));
    }

    public void acknowledge(String noteId) {
        try { client.notifications().mark(noteId); client.notifications().delete(noteId); }
        catch (Exception e) { log.warn("Could not acknowledge mint signing notification id={}", noteId); }
    }
}
