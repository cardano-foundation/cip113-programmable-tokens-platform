package org.cardanofoundation.cip113.service;

import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.repository.MintAttestationIntentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import jakarta.persistence.EntityManager;

/** Each mutation commits before its result may be used for network I/O. */
@Service
@RequiredArgsConstructor
public class MintAttestationStore {
    private final MintAttestationIntentRepository repository;
    private final EntityManager entityManager;
    public record BuildClaim(String owner, String cbor) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MintAttestationIntentEntity create(MintAttestationIntentEntity intent) {
        // save() uses merge for an assigned ID and could replace another request in a race.
        entityManager.persist(intent);
        entityManager.flush();
        return intent;
    }
    public Optional<MintAttestationIntentEntity> find(String id) { return repository.findById(id); }
    public MintAttestationIntentEntity get(String id) {
        return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mint intent not found"));
    }
    public MintAttestationIntentEntity byDigest(String digest) {
        return repository.findAllByDigestOrderByIdAsc(digest).stream()
                .filter(i -> i.getInitialRegistrationJson() == null && i.getDocumentJson() != null
                        && !i.getDocumentJson().contains("\"txHash\""))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mint document not found"));
    }
    private MintAttestationIntentEntity locked(String id) {
        return repository.findLockedById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mint intent not found"));
    }
    static void unexpired(MintAttestationIntentEntity i) {
        if (!i.getExpiresAt().isAfter(Instant.now())) throw new ResponseStatusException(HttpStatus.GONE, "Mint intent expired; request a new attestation");
    }
    private String claim(MintAttestationIntentEntity i, String status) {
        unexpired(i);
        if (i.getClaimOwner() != null && i.getLeaseUntil() != null && i.getLeaseUntil().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mint intent already processing");
        String owner = UUID.randomUUID().toString();
        i.setClaimOwner(owner); i.setLeaseUntil(Instant.now().plusSeconds(240)); i.setStatus(status);
        return owner;
    }
    private static void owner(MintAttestationIntentEntity i, String owner, String status) {
        if (!Objects.equals(owner, i.getClaimOwner()) || !status.equals(i.getStatus())
                || i.getLeaseUntil() == null || !i.getLeaseUntil().isAfter(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mint intent processing claim expired");
    }
    private static void clearClaim(MintAttestationIntentEntity i) { i.setClaimOwner(null); i.setLeaseUntil(null); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String claimAnchor(String id) {
        var i = locked(id);
        if (!"PREPARED".equals(i.getStatus()) && !"ANCHORING".equals(i.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mint intent is already anchored");
        return claim(i, "ANCHORING");
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean beginDispatch(String id, String owner) {
        var i = locked(id); owner(i, owner, "ANCHORING");
        if (i.isDispatchStarted()) return false;
        i.setDispatchStarted(true);
        return true;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAnchor(String id, String owner, String sequence, String kelEvent) {
        var i = locked(id); owner(i, owner, "ANCHORING");
        i.setSequenceNumber(sequence); i.setKelEventJson(kelEvent); i.setStatus("ANCHORED"); clearClaim(i);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseAnchor(String id, String owner) {
        var i = locked(id);
        if (Objects.equals(owner, i.getClaimOwner()) && "ANCHORING".equals(i.getStatus())) {
            i.setStatus("PREPARED"); clearClaim(i);
        }
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BuildClaim claimBuild(String id) {
        var i = locked(id);
        unexpired(i);
        // Never build a replacement for a previously published intent.
        if ("BUILT".equals(i.getStatus())) return new BuildClaim(null,
                i.getAttestationCbor() != null ? i.getAttestationCbor() : i.getUnsignedCbor());
        if (!"ANCHORED".equals(i.getStatus()) && !"BUILDING".equals(i.getStatus()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mint intent has no verified KERI anchor");
        return new BuildClaim(claim(i, "BUILDING"), null);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String publishBuild(String id, String owner, String cbor, String transactionHash) {
        var i = locked(id); owner(i, owner, "BUILDING");
        if (cbor == null || !cbor.matches("(?:[a-fA-F0-9]{2})+") || transactionHash == null || !transactionHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid built transaction");
        i.setUnsignedCbor(cbor); i.setTransactionHash(transactionHash); i.setStatus("BUILT"); clearClaim(i);
        return cbor;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String publishChild(String id, String owner, String cbor, String transactionHash) {
        var i = locked(id); owner(i, owner, "BUILDING");
        if (i.getUnsignedCbor() == null || i.getTransactionHash() == null
                || cbor == null || !cbor.matches("(?:[a-fA-F0-9]{2})+")
                || transactionHash == null || !transactionHash.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid mint attestation child");
        i.setAttestationCbor(cbor); i.setAttestationTxHash(transactionHash);
        i.setStatus("BUILT"); clearClaim(i);
        return cbor;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseBuild(String id, String owner) {
        var i = locked(id);
        if (Objects.equals(owner, i.getClaimOwner()) && "BUILDING".equals(i.getStatus())) {
            i.setStatus("ANCHORED"); clearClaim(i);
        }
    }
}
