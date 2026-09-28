package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.repository.KycIssuanceRepository;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.conversions.CardanoConverters;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.cardanofoundation.cip113.service.module.Cip170MintChildBuilder;

/** Current-Veridian profile: digest of a documented, retrievable off-chain SAID preimage. */
@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MintAttestationService {
    public static final String PROFILE = "cip113-mint-intent-said-json-v1";
    public static final String TX_HASH_PROFILE = "cip113-mint-txhash-said-json-v1";
    private final MintAttestationStore store;
    private final MintAttestationRequestVerifier verifier;
    private final MintAttestationTransport transport;
    private final KycSessionRepository sessions;
    private final KycIssuanceRepository issuances;
    private final ProtocolDeploymentResolver protocols;
    private final AppConfig.Network network;
    private final ObjectMapper mapper;
    private final ObjectProvider<TokenOperationsService> tokenOperations;
    private final Cip170MintChildBuilder childBuilder;
    private final CardanoConverters cardanoConverters;

    public record View(String intentId, String status, String signerAid, String digest, String seqNumber,
                       String documentUrl, Instant expiresAt, MintAttestationRequest fields,
                       String authorityStatus, String targetTxHash, String attestationTxHash) {}

    public record Chain(String mintCborHex, String attestationCborHex,
                        String mintTxHash, String attestationTxHash) {}

    public Map<String, String> config() { return Map.of("network", network.getNetwork(), "audience", verifier.audience(), "profile", TX_HASH_PROFILE); }

    public View prepare(byte[] rawBody, HttpHeaders headers) throws Exception {
        MintAttestationRequest raw = readRequest(rawBody);
        verifier.verifyAndConsume("/keri/mint-attestations/prepare", rawBody, raw.feePayerAddress(), headers);
        if (raw.requestId() == null || !raw.requestId().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("requestId must be a client-retained UUID");
        MintAttestationRequest fields = normalize(raw);
        var session = boundSession(fields);
        var existing = store.find(raw.requestId());
        if (existing.isPresent()) return existingPrepare(existing.get(), fields, session.getAid());
        Instant expires = Instant.now().plusSeconds(1800);
        var i = new MintAttestationIntentEntity();
        i.setId(raw.requestId()); i.setSessionId(fields.sessionId());
        i.setWalletAid(session.getAid()); i.setIssuerAid(transport.issuerAid());
        i.setCredentialSaid(session.getCredentialAid()); i.setFieldsJson(mapper.writeValueAsString(fields));
        i.setExpiresAt(expires); i.setStatus("PREPARED");
        String targetCbor = tokenOperations.getObject().buildMintDraft(fields);
        String targetHash = MintAttestedTransactionValidator.validateMint(targetCbor, fields);
        var target = Transaction.deserialize(HexUtil.decodeHexString(targetCbor));
        Instant targetDeadline = targetDeadline(target);
        if (!targetDeadline.isAfter(Instant.now().plusSeconds(120)))
            throw new IllegalArgumentException("Mint transaction validity is too short for Veridian approval; prepare a new mint");
        i.setExpiresAt(targetDeadline.minusSeconds(120).isBefore(expires)
                ? targetDeadline.minusSeconds(120) : expires);
        if (Cip170MintChildBuilder.fundingOutput(target, targetHash, fields.feePayerAddress(), 5_000_000L, null) == null)
            throw new IllegalArgumentException("Mint leaves no plain fee-payer output with 5 ADA to fund its CIP-170 child");
        Map<String, Object> document = MintTxHashPayload.signed(targetHash);
        i.setDigest((String) document.get("d")); i.setDocumentJson(Serder.dumps(document));
        i.setPreimage(MintTxHashPayload.preimage(targetHash));
        i.setUnsignedCbor(targetCbor); i.setTransactionHash(targetHash);
        transport.prepareExchange(i, document);
        try { return view(store.create(i)); }
        catch (RuntimeException duplicate) {
            return store.find(raw.requestId())
                    .map(saved -> {
                        try { return existingPrepare(saved, fields, session.getAid()); }
                        catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); }
                    }).orElseThrow(() -> duplicate);
        }
    }

    private View existingPrepare(MintAttestationIntentEntity saved, MintAttestationRequest fields, String walletAid) throws Exception {
        if (saved.getInitialRegistrationJson() != null || !fields.equals(fields(saved))
                || !Objects.equals(saved.getSessionId(), fields.sessionId())
                || !Objects.equals(saved.getWalletAid(), walletAid))
            throw new IllegalArgumentException("requestId already belongs to another mint request or Veridian identity");
        return view(saved);
    }

    public View anchor(String id, byte[] rawBody, HttpHeaders headers) throws Exception {
        MintAttestationRequest raw = readRequest(rawBody);
        verifier.verifyAndConsume("/keri/mint-attestations/" + id + "/anchor", rawBody, raw.feePayerAddress(), headers);
        var i = store.get(id);
        if (i.getInitialRegistrationJson() != null) throw new IllegalArgumentException("Initial mint intent requires creation anchor");
        MintAttestationRequest expected = fields(i);
        if (!expected.equals(normalize(raw))) throw new IllegalArgumentException("Mint signing request differs from prepared intent");
        var session = boundSession(expected);
        if (!Objects.equals(i.getWalletAid(), session.getAid()) || !Objects.equals(i.getCredentialSaid(), session.getCredentialAid()))
            throw new IllegalStateException("Veridian session identity or credential changed; prepare a new mint intent");
        if (Set.of("ANCHORED", "BUILDING", "BUILT").contains(i.getStatus())) return view(i);
        String owner = store.claimAnchor(id);
        try {
            var proof = transport.sendAndWait(i, store.beginDispatch(id, owner));
            store.saveAnchor(id, owner, proof.sequence(), proof.eventJson());
            transport.acknowledge(proof.notificationId());
            return view(store.get(id));
        } finally { store.releaseAnchor(id, owner); }
    }

    public Chain buildChain(String id, byte[] rawBody, HttpHeaders headers) throws Exception {
        MintAttestationRequest raw = readRequest(rawBody);
        verifier.verifyAndConsume("/keri/mint-attestations/" + id + "/build-chain",
                rawBody, raw.feePayerAddress(), headers);
        var i = store.get(id);
        if (!isTxHashProfile(i)) throw new IllegalArgumentException("This mint uses the legacy intent profile");
        MintAttestationRequest expected = fields(i);
        if (!expected.equals(normalize(raw)))
            throw new IllegalArgumentException("Mint signing request differs from the frozen transaction");
        if ("BUILT".equals(i.getStatus())) return chain(i);
        var session = boundSession(expected);
        if (!Objects.equals(i.getWalletAid(), session.getAid())
                || !Objects.equals(i.getCredentialSaid(), session.getCredentialAid()))
            throw new IllegalStateException("Veridian identity changed; prepare another mint");
        if (!"ANCHORED".equals(i.getStatus()) && !"BUILDING".equals(i.getStatus()))
            throw new IllegalArgumentException("Mint transaction has no verified Veridian approval");
        MintAttestationStore.unexpired(i);
        if (i.getWalletKelFloor() == null || i.getSequenceNumber() == null
                || new BigInteger(i.getSequenceNumber(), 16).compareTo(new BigInteger(i.getWalletKelFloor(), 16)) <= 0
                || transport.verifiedEvent(i.getWalletAid(), i.getSequenceNumber(), i.getDigest()) == null)
            throw new IllegalStateException("Verified mint KERI event is unavailable");
        if (!MintTxHashPayload.digest(i.getTransactionHash()).equals(i.getDigest()))
            throw new IllegalStateException("Saved mint digest differs from frozen transaction");
        MintAttestedTransactionValidator.validateMint(i.getUnsignedCbor(), expected);
        if (!targetDeadline(Transaction.deserialize(HexUtil.decodeHexString(i.getUnsignedCbor())))
                .isAfter(Instant.now().plusSeconds(120)))
            throw new IllegalStateException("Frozen mint transaction is near expiry; prepare a new mint intent");
        var claim = store.claimBuild(id);
        if (claim.owner() == null) return chain(store.get(id));
        try {
            var child = childBuilder.build(expected.feePayerAddress(), i.getUnsignedCbor(), attestation(i),
                    5_000_000L, null);
            String cbor = child.serializeToHex();
            String hash = MintAttestedTransactionValidator.validateChild(cbor, i.getUnsignedCbor(),
                    expected.feePayerAddress(), attestation(i));
            store.publishChild(id, claim.owner(), cbor, hash);
            return chain(store.get(id));
        } finally { store.releaseBuild(id, claim.owner()); }
    }

    private Chain chain(MintAttestationIntentEntity i) {
        if (!"BUILT".equals(i.getStatus()) || i.getAttestationCbor() == null)
            throw new IllegalStateException("Attested mint chain is not built");
        return new Chain(i.getUnsignedCbor(), i.getAttestationCbor(),
                i.getTransactionHash(), i.getAttestationTxHash());
    }

    Instant targetDeadline(Transaction target) {
        if (target.getBody() == null || target.getBody().getTtl() <= 0)
            throw new IllegalArgumentException("Mint transaction must have a finite validity deadline");
        return cardanoConverters.slot().slotToTime(target.getBody().getTtl()).toInstant(ZoneOffset.UTC);
    }

    /** A caller must still validate final transaction contents before publishing its CBOR. */
    public MintAttestationIntentEntity requireAnchored(String id, MintTokenRequest request,
                                                      ProtocolBootstrapParams deployment) throws Exception {
        var i = store.get(id);
        if (isTxHashProfile(i)) throw new IllegalArgumentException("Transaction-hash attestations use the chained mint endpoint");
        if (i.getInitialRegistrationJson() != null) throw new IllegalArgumentException("Initial mint intent requires creation finalize");
        if (!Set.of("ANCHORED", "BUILDING", "BUILT").contains(i.getStatus()))
            throw new IllegalArgumentException("Mint intent has no verified KERI anchor");
        MintAttestationStore.unexpired(i);
        var f = fields(i);
        var candidate = new MintAttestationRequest(f.sessionId(), network.getNetwork(), deployment.txHash(),
                request.tokenPolicyId(), request.assetName(), request.quantity(), request.feePayerAddress(), request.recipientAddress(), null);
        if (!f.equals(normalize(candidate)) || request.cip68Metadata() != null)
            throw new IllegalArgumentException("Mint request differs from the KERI-approved intent");
        if (!Objects.equals(i.getDigest(), Saider.saidify(mapper.readValue(i.getDocumentJson(),
                new TypeReference<LinkedHashMap<String, Object>>() {})).sad().get("d")))
            throw new IllegalStateException("Stored mint document digest mismatch");
        if (i.getWalletKelFloor() == null || new BigInteger(i.getSequenceNumber(), 16).compareTo(new BigInteger(i.getWalletKelFloor(), 16)) <= 0)
            throw new IllegalStateException("Mint anchor does not follow the recorded wallet KEL state");
        if (transport.verifiedEvent(i.getWalletAid(), i.getSequenceNumber(), i.getDigest()) == null)
            throw new IllegalStateException("Verified KERI event is not available; retry after KERIA synchronizes");
        return i;
    }

    public MintAttestationRequest fields(MintAttestationIntentEntity i) throws Exception {
        return mapper.readValue(i.getFieldsJson(), MintAttestationRequest.class);
    }
    public Cip170AttestationData attestation(MintAttestationIntentEntity i) {
        return new Cip170AttestationData(i.getWalletAid(), i.getDigest(), i.getSequenceNumber(), "1.0");
    }
    public MintAttestationStore.BuildClaim claimBuild(String id) { return store.claimBuild(id); }
    public String publishBuild(String id, String owner, String cbor) throws Exception {
        return store.publishBuild(id, owner, cbor, TransactionUtil.getTxHash(HexUtil.decodeHexString(cbor)));
    }
    public void releaseBuild(String id, String owner) { store.releaseBuild(id, owner); }
    public byte[] documentByDigest(String digest) {
        if (digest == null || !digest.matches("E[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid mint digest");
        return store.byDigest(digest).getDocumentJson().getBytes(StandardCharsets.UTF_8);
    }
    public byte[] preimageByDigest(String digest) {
        if (digest == null || !digest.matches("E[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid mint digest");
        return store.byDigest(digest).getPreimage().getBytes(StandardCharsets.UTF_8);
    }
    public View get(String id, String sessionId) throws Exception {
        var i = store.get(id);
        if (!Objects.equals(i.getSessionId(), sessionId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mint intent not found");
        return view(i);
    }
    private View view(MintAttestationIntentEntity i) throws Exception {
        return new View(i.getId(), i.getStatus(), i.getWalletAid(), i.getDigest(), i.getSequenceNumber(),
                isTxHashProfile(i) ? null : verifier.audience() + "/keri/mint-attestations/documents/" + i.getDigest(),
                i.getExpiresAt(), fields(i), "NOT_CHECKED", i.getTransactionHash(), i.getAttestationTxHash());
    }

    public boolean isTxHashProfile(MintAttestationIntentEntity i) {
        return i.getInitialRegistrationJson() == null && i.getDocumentJson() != null
                && !i.getDocumentJson().contains("\"profile\"");
    }

    private MintAttestationRequest readRequest(byte[] body) throws Exception {
        if (body == null || body.length > 16_384) throw new IllegalArgumentException("Invalid mint request size");
        return mapper.readValue(body, MintAttestationRequest.class);
    }
    KycSessionEntity boundSession(MintAttestationRequest f) {
        var session = sessions.findById(f.sessionId()).orElseThrow(() -> new IllegalArgumentException("Veridian session not found"));
        if (session.getAid() == null || !session.getAid().matches("[A-Za-z0-9_-]{44}")
                || session.getCredentialAid() == null || session.getCredentialAid().isBlank())
            throw new IllegalArgumentException("Connect Veridian and present or accept a credential first");
        var issuance = issuances.findById(f.sessionId());
        if (issuance.isPresent() && !"ACCEPTED".equals(issuance.get().getStatus()))
            throw new IllegalArgumentException("Complete credential presentation or issuance before attesting");
        try {
            String stored = session.getCardanoAddress();
            byte[] address = stored != null && stored.matches("(?:[0-9a-fA-F]{2})+")
                    ? HexUtil.decodeHexString(stored) : new Address(stored).getBytes();
            if (!Arrays.equals(address, new Address(f.feePayerAddress()).getBytes()))
                throw new IllegalArgumentException("Veridian session belongs to a different Cardano address");
        } catch (Exception e) { throw new IllegalArgumentException("Veridian session must be connected to the mint fee payer"); }
        return session;
    }

    MintAttestationRequest normalize(MintAttestationRequest r) {
        if (r == null || r.sessionId() == null || !r.sessionId().matches("[A-Za-z0-9_-]{1,128}")
                || !network.getNetwork().equals(r.network())) throw new IllegalArgumentException("Invalid mint session or network");
        String policy = hex(r.tokenPolicyId(), 56, 56, "policy");
        String asset = hex(r.assetName(), 0, 64, "asset name");
        if (r.quantity() == null || !r.quantity().matches("[0-9]{1,40}")) throw new IllegalArgumentException("Mint quantity must be a positive integer");
        BigInteger quantity = new BigInteger(r.quantity());
        if (quantity.signum() <= 0 || quantity.bitLength() > 63) throw new IllegalArgumentException("Mint quantity exceeds positive int64 range");
        String payer = r.feePayerAddress() == null ? "" : r.feePayerAddress().trim();
        String recipient = r.recipientAddress() == null || r.recipientAddress().isBlank() ? payer : r.recipientAddress().trim();
        var payerAddress = new Address(payer);
        var recipientAddress = new Address(recipient);
        int expectedNetwork = "mainnet".equals(network.getNetwork()) ? 1 : 0;
        if ((payerAddress.getBytes()[0] & 15) != expectedNetwork || (recipientAddress.getBytes()[0] & 15) != expectedNetwork)
            throw new IllegalArgumentException("Mint address network mismatch");
        var deployment = protocols.resolve(r.protocolTxHash() == null || r.protocolTxHash().isBlank() ? null : r.protocolTxHash());
        var destination = AddressProvider.getBaseAddress(Credential.fromScript(deployment.programmableLogicBase().scriptHash()),
                recipientAddress.getDelegationCredential().orElseThrow(() -> new IllegalArgumentException("Recipient must have a stake credential")),
                network.getCardanoNetwork()).getAddress();
        if (r.programmableRecipientAddress() != null && !r.programmableRecipientAddress().isBlank()
                && !Arrays.equals(new Address(destination).getBytes(), new Address(r.programmableRecipientAddress()).getBytes()))
            throw new IllegalArgumentException("Derived programmable recipient does not match request");
        return new MintAttestationRequest(r.sessionId(), network.getNetwork(), deployment.txHash(), policy, asset,
                quantity.toString(), payerAddress.getAddress(), recipientAddress.getAddress(), destination);
    }
    private static String hex(String value, int min, int max, String name) {
        if (value == null || value.length() < min || value.length() > max || !value.matches("(?:[a-fA-F0-9]{2})*"))
            throw new IllegalArgumentException("Invalid " + name);
        return value.toLowerCase(Locale.ROOT);
    }
    static Map<String, Object> document(MintAttestationRequest f, String id, String aid, Instant expires) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("i", aid); doc.put("d", ""); doc.put("profile", PROFILE); doc.put("operation", "MINT");
        doc.put("intent", id); doc.put("network", f.network()); doc.put("deployment", f.protocolTxHash());
        doc.put("policy", f.tokenPolicyId()); doc.put("assetNameHex", f.assetName()); doc.put("quantity", f.quantity());
        doc.put("feePayer", f.feePayerAddress()); doc.put("recipient", f.recipientAddress());
        doc.put("programmableRecipient", f.programmableRecipientAddress()); doc.put("expiresAt", expires.toString());
        return doc;
    }
    static String preimage(Map<String, Object> document) {
        var copy = new LinkedHashMap<>(document); copy.put("d", "#".repeat(44)); return Serder.dumps(copy);
    }
}
