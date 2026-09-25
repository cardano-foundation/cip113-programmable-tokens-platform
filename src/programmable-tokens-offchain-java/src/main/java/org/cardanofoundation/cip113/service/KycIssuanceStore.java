package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.KycIssuanceEntity;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.repository.KycIssuanceRepository;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/** Short database transactions around external KERIA operations. */
@Service
public class KycIssuanceStore {
    private final KycSessionRepository sessions;
    private final KycIssuanceRepository issuances;

    public KycIssuanceStore(KycSessionRepository sessions, KycIssuanceRepository issuances) {
        this.sessions = sessions;
        this.issuances = issuances;
    }

    public Optional<KycIssuanceEntity> find(String sessionId) {
        return issuances.findById(sessionId);
    }

    @Transactional
    public void applyResolvedOobi(String sessionId, String oobi, String aid) {
        KycSessionEntity session = sessions.findLockedBySessionId(sessionId).orElse(null);
        if (session == null) {
            session = new KycSessionEntity();
            session.setSessionId(sessionId);
        } else if (session.getAid() != null && !session.getAid().equals(aid)
                && (issuances.existsById(sessionId) || session.getCredentialAid() != null)) {
            throw new IllegalStateException("This KYC session is bound to another Veridian profile. Start a new session.");
        }
        session.setAid(aid);
        session.setOobi(oobi);
        sessions.saveAndFlush(session);
    }

    @Transactional
    public KycIssuanceEntity claim(String sessionId, String walletAid, String issuerAid,
                                   String schemaSaid, String schemaOobiUrl, String attributesJson) {
        KycSessionEntity session = sessions.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (!walletAid.equals(session.getAid())) {
            throw new IllegalStateException("Veridian profile changed during issuance. Start a new session.");
        }
        if (session.getCredentialAid() != null) {
            throw new IllegalStateException("This session already has an accepted credential. Start a new session.");
        }
        if (issuances.existsById(sessionId)) {
            throw new IllegalStateException("This session already has an issued, pending, or presented credential. Use retry grant delivery or start a new session.");
        }
        KycIssuanceEntity issue = new KycIssuanceEntity();
        issue.setSessionId(sessionId);
        issue.setWalletAid(walletAid);
        issue.setIssuerAid(issuerAid);
        issue.setSchemaSaid(schemaSaid);
        issue.setSchemaOobiUrl(schemaOobiUrl);
        issue.setAttributesJson(attributesJson);
        issue.setStatus("ISSUING");
        return issuances.saveAndFlush(issue);
    }

    /** Blocks issuance while this session is exchanging a presented credential. */
    @Transactional
    public String reservePresentation(String sessionId, String walletAid, String schemaSaid) {
        KycSessionEntity session = sessions.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (!walletAid.equals(session.getAid())) {
            throw new IllegalStateException("Veridian profile changed during presentation");
        }
        if (session.getCredentialAid() != null) {
            return null; // A repeat presentation is checked against the accepted credential.
        }
        KycIssuanceEntity issue = issuances.findById(sessionId).orElse(null);
        if (issue != null) {
            throw new IllegalStateException("Credential issuance or presentation is already active for this session");
        }
        issue = new KycIssuanceEntity();
        issue.setSessionId(sessionId);
        issue.setWalletAid(walletAid);
        issue.setIssuerAid("presentation");
        issue.setSchemaSaid(schemaSaid);
        issue.setSchemaOobiUrl("presentation");
        issue.setAttributesJson("{}");
        String owner = UUID.randomUUID().toString();
        issue.setStatus("PRESENTING");
        issue.setDeliveryOwner(owner);
        issue.setDeliveryLeaseUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        issuances.saveAndFlush(issue);
        return owner;
    }

    @Transactional
    public void releasePresentation(String sessionId, String owner) {
        if (owner == null) return;
        sessions.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        KycIssuanceEntity issue = issuances.findById(sessionId).orElse(null);
        if (issue != null && "PRESENTING".equals(issue.getStatus())
                && owner.equals(issue.getDeliveryOwner())) {
            issuances.delete(issue);
            issuances.flush();
        }
    }

    @Transactional
    public void markUnknown(String sessionId) {
        KycIssuanceEntity issue = locked(sessionId);
        if ("ISSUING".equals(issue.getStatus())) {
            issue.setStatus("ISSUANCE_UNKNOWN");
            issuances.saveAndFlush(issue);
        }
    }

    @Transactional
    public void saveIssued(String sessionId, String credentialSaid, String operationName,
                           String acdc, String iss, String anc) {
        KycIssuanceEntity issue = locked(sessionId);
        if (!"ISSUING".equals(issue.getStatus())) {
            throw new IllegalStateException("Issuance state changed before its result could be saved");
        }
        issue.setCredentialSaid(credentialSaid);
        issue.setIssueOperationName(operationName);
        issue.setAcdcJson(acdc);
        issue.setIssJson(iss);
        issue.setAncJson(anc);
        issue.setStatus("ISSUED");
        issuances.saveAndFlush(issue);
    }

    @Transactional
    public String claimGrantBuild(String sessionId) {
        KycIssuanceEntity issue = locked(sessionId);
        if (!"ISSUED".equals(issue.getStatus())
                && !("BUILDING".equals(issue.getStatus()) && expired(issue))) {
            throw new IllegalStateException("Grant is already being prepared or is ready for delivery");
        }
        String owner = UUID.randomUUID().toString();
        issue.setStatus("BUILDING");
        issue.setDeliveryOwner(owner);
        issue.setDeliveryLeaseUntil(Instant.now().plus(4, ChronoUnit.MINUTES));
        issuances.saveAndFlush(issue);
        return owner;
    }

    @Transactional
    public void saveGrant(String sessionId, String owner, String said, String raw,
                          String sigs, String atc, String estSeq, String estDigest) {
        KycIssuanceEntity issue = locked(sessionId);
        requireOwner(issue, owner);
        issue.setGrantSaid(said);
        issue.setGrantRaw(raw);
        issue.setGrantSigs(sigs);
        issue.setGrantAtc(atc);
        issue.setSigningEstablishmentSeq(estSeq);
        issue.setSigningEstablishmentDigest(estDigest);
        issue.setStatus("READY");
        issue.setDeliveryOwner(null);
        issue.setDeliveryLeaseUntil(null);
        issuances.saveAndFlush(issue);
    }

    @Transactional
    public void releaseGrantBuild(String sessionId, String owner) {
        KycIssuanceEntity issue = locked(sessionId);
        if (owner.equals(issue.getDeliveryOwner()) && "BUILDING".equals(issue.getStatus())) {
            issue.setStatus("ISSUED");
            issue.setDeliveryOwner(null);
            issue.setDeliveryLeaseUntil(null);
            issuances.saveAndFlush(issue);
        }
    }

    @Transactional
    public String claimDelivery(String sessionId) {
        KycIssuanceEntity issue = locked(sessionId);
        if (!"READY".equals(issue.getStatus())
                && !("WAITING".equals(issue.getStatus()) && expired(issue))) {
            throw new IllegalStateException("Grant delivery is already running or cannot be retried");
        }
        String owner = UUID.randomUUID().toString();
        issue.setStatus("WAITING");
        issue.setDeliveryOwner(owner);
        issue.setDeliveryLeaseUntil(Instant.now().plus(4, ChronoUnit.MINUTES));
        issuances.saveAndFlush(issue);
        return owner;
    }

    @Transactional
    public void releaseDelivery(String sessionId, String owner) {
        KycIssuanceEntity issue = locked(sessionId);
        if (owner.equals(issue.getDeliveryOwner()) && "WAITING".equals(issue.getStatus())) {
            issue.setStatus("READY");
            issue.setDeliveryOwner(null);
            issue.setDeliveryLeaseUntil(null);
            issuances.saveAndFlush(issue);
        }
    }

    @Transactional
    public void accept(String sessionId, String owner, String grantSaid) {
        KycSessionEntity session = sessions.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        KycIssuanceEntity issue = locked(sessionId);
        requireOwner(issue, owner);
        if (!"WAITING".equals(issue.getStatus()) || !grantSaid.equals(issue.getGrantSaid())
                || !issue.getWalletAid().equals(session.getAid())) {
            throw new IllegalStateException("Admit no longer matches the session's pending grant");
        }
        if (session.getCredentialAid() != null && !session.getCredentialAid().equals(issue.getCredentialSaid())) {
            throw new IllegalStateException("Session already has another accepted credential");
        }
        session.setCredentialAid(issue.getCredentialSaid());
        session.setCredentialSaid(issue.getSchemaSaid());
        session.setCredentialAttributes(issue.getAttributesJson());
        session.setCredentialRole(0);
        issue.setStatus("ACCEPTED");
        issue.setDeliveryOwner(null);
        issue.setDeliveryLeaseUntil(null);
        sessions.saveAndFlush(session);
        issuances.saveAndFlush(issue);
    }

    @Transactional
    public void acceptPresented(String sessionId, String owner, String walletAid, String credentialSaid,
                                String schemaSaid, String attributesJson, int role) {
        KycSessionEntity session = sessions.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        Optional<KycIssuanceEntity> issuance = issuances.findById(sessionId);
        boolean reserved = owner != null && issuance.isPresent()
                && "PRESENTING".equals(issuance.get().getStatus())
                && owner.equals(issuance.get().getDeliveryOwner());
        if (!walletAid.equals(session.getAid())
                || (session.getCredentialAid() == null && !reserved)
                || (session.getCredentialAid() != null && issuance.isPresent()
                    && !"ACCEPTED".equals(issuance.get().getStatus()))
                || (session.getCredentialAid() != null
                    && (!session.getCredentialAid().equals(credentialSaid)
                        || !schemaSaid.equals(session.getCredentialSaid())
                        || session.getCredentialRole() == null
                        || session.getCredentialRole() != role))) {
            throw new IllegalStateException("Session identity or credential changed during presentation. Start a new session.");
        }
        session.setCredentialAid(credentialSaid);
        session.setCredentialSaid(schemaSaid);
        session.setCredentialAttributes(attributesJson);
        session.setCredentialRole(role);
        sessions.saveAndFlush(session);
        if (reserved) {
            issuances.delete(issuance.orElseThrow());
            issuances.flush();
        }
    }

    private KycIssuanceEntity locked(String sessionId) {
        return issuances.findLockedBySessionId(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Issuance not found for session: " + sessionId));
    }

    private static boolean expired(KycIssuanceEntity issue) {
        return issue.getDeliveryLeaseUntil() != null && issue.getDeliveryLeaseUntil().isBefore(Instant.now());
    }

    private static void requireOwner(KycIssuanceEntity issue, String owner) {
        if (!owner.equals(issue.getDeliveryOwner())) {
            throw new IllegalStateException("Another delivery attempt owns this grant");
        }
    }
}
