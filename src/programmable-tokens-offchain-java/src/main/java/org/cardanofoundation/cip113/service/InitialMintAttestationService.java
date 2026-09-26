package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.veridian.signify.cesr.Serder;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.entity.MintAttestationIntentEntity;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.cardanofoundation.cip113.service.module.context.RwaTokenContext;
import org.cardanofoundation.cip113.util.Cip68;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

/** Current-Veridian initial mint approval. Public requests cannot select internal bootstrap plans. */
@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InitialMintAttestationService {
    public static final String PROFILE = MintAttestationService.TX_HASH_PROFILE;
    public static final String PATH = "/rwa-token/create-attested";
    private final MintAttestationRequestVerifier verifier;
    private final MintAttestationService mints;
    private final MintAttestationStore mintStore;
    private final InitialMintAttestationStore store;
    private final MintAttestationTransport transport;
    private final ModuleHandlerFactory handlers;
    private final ProtocolDeploymentResolver protocols;
    private final ObjectMapper mapper;

    public record Prepare(String requestId, String sessionId, RwaTokenRegisterRequest registration) {}
    public record Action(String sessionId, String feePayerAddress) {}
    public record View(String intentId, String status, String signerAid, String digest, String seqNumber,
                       String documentUrl, Instant expiresAt, MintAttestationRequest fields,
                       String authorityStatus, RwaTokenRegisterRequest registration,
                       String submissionStatus, Map<String, String> transactionHashes,
                       String targetTxHash) {}

    public Map<String, String> config() {
        var config = new HashMap<>(mints.config()); config.put("profile", PROFILE); return config;
    }

    public View prepare(byte[] rawBody, HttpHeaders headers) throws Exception {
        if (rawBody == null || rawBody.length > 65_536) throw new IllegalArgumentException("Invalid creation request size");
        Prepare input = mapper.readValue(rawBody, Prepare.class);
        if (input.registration() == null) throw new IllegalArgumentException("registration is required");
        verifier.verifyAndConsume(PATH + "/prepare", rawBody, input.registration().getFeePayerAddress(), headers);
        if (input.requestId() == null || !input.requestId().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IllegalArgumentException("requestId must be a UUID retained for recovery");
        RwaTokenRegisterRequest registration = normalize(input.registration());
        var existing = store.find(input.requestId());
        if (existing.isPresent()) {
            var saved = existing.get();
            if (!Objects.equals(saved.getSessionId(), input.sessionId())
                    || !mapper.readTree(saved.getInitialRegistrationJson()).equals(mapper.readTree(mapper.writeValueAsBytes(registration))))
                throw new IllegalArgumentException("requestId already belongs to different frozen creation settings");
            if (Set.of("RELEASED", "ARCHIVED_EXPIRED").contains(saved.getStatus())) throw new ResponseStatusException(HttpStatus.GONE, "This attempt was released; use a new requestId");
            return view(saved);
        }
        var params = protocols.resolve(null);
        if (params == null) throw new IllegalStateException("Protocol deployment is unavailable");
        var handler = (RwaTokenModuleHandler) handlers.getHandler("rwa-token", RwaTokenContext.emptyContext());
        var plan = handler.planGenesis(registration, params);
        Instant expiresAt = Instant.now().plusSeconds(1800);
        // The preview transaction is rollback-only. This freezes the exact
        // registration CBOR before the remote signer sees its hash.
        var preview = store.preview(mapper.readValue(mapper.writeValueAsBytes(registration),
                RwaTokenRegisterRequest.class), params, plan, expiresAt);
        String asset = registration.getCip68Metadata() == null ? registration.getAssetName()
                : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), registration.getAssetName());
        var fields = mints.normalize(new MintAttestationRequest(input.sessionId(), config().get("network"),
                params.txHash(), plan.programmableTokenPolicyId(), asset, registration.getInitialMintQuantity(),
                registration.getFeePayerAddress(), registration.getRecipientAddress(), null));
        InitialMintTransactionValidator.validate(preview.prefix(), fields, registration,
                plan, params, null);
        Instant mintDeadline = mints.targetDeadline(Transaction.deserialize(
                HexUtil.decodeHexString(preview.prefix().registrationCborHex())));
        Instant genesisDeadline = mints.targetDeadline(Transaction.deserialize(
                HexUtil.decodeHexString(preview.prefix().genesisCborHex())));
        if (genesisDeadline.isBefore(mintDeadline)) mintDeadline = genesisDeadline;
        if (!mintDeadline.isAfter(Instant.now().plusSeconds(120)))
            throw new IllegalArgumentException("Initial mint transaction validity is too short for Veridian approval; prepare a new registration");
        var session = mints.boundSession(fields);
        var intent = new MintAttestationIntentEntity();
        intent.setId(input.requestId()); intent.setSessionId(input.sessionId());
        intent.setWalletAid(session.getAid()); intent.setIssuerAid(transport.issuerAid());
        intent.setCredentialSaid(session.getCredentialAid());
        intent.setExpiresAt(mintDeadline.minusSeconds(120).isBefore(expiresAt)
                ? mintDeadline.minusSeconds(120) : expiresAt);
        intent.setStatus("PREPARED"); intent.setFieldsJson(mapper.writeValueAsString(fields));
        intent.setInitialRegistrationJson(mapper.writeValueAsString(registration));
        intent.setInitialPlanJson(mapper.writeValueAsString(plan));
        intent.setInitialPrefixJson(mapper.writeValueAsString(preview.prefix()));
        intent.setInitialSnapshotJson(preview.snapshotJson());
        var document = MintTxHashPayload.signed(preview.prefix().registrationTxHash());
        intent.setDigest(MintTxHashPayload.digest(preview.prefix().registrationTxHash()));
        intent.setDocumentJson(Serder.dumps(document));
        intent.setPreimage(MintTxHashPayload.preimage(preview.prefix().registrationTxHash()));
        transport.prepareExchange(intent, document);
        return view(store.prepare(intent, plan));
    }

    public View anchor(String id, byte[] body, HttpHeaders headers) throws Exception {
        var intent = authenticate(id, "anchor", body, headers);
        requireActive(intent);
        if ("BUILT".equals(intent.getStatus())) return view(intent);
        requireSession(intent);
        if (Set.of("ANCHORED", "BUILDING").contains(intent.getStatus())) {
            MintAttestationStore.unexpired(intent); return view(intent);
        }
        String owner = mintStore.claimAnchor(id);
        try {
            var proof = transport.sendAndWait(intent, mintStore.beginDispatch(id, owner));
            mintStore.saveAnchor(id, owner, proof.sequence(), proof.eventJson());
            transport.acknowledge(proof.notificationId());
            return view(store.get(id));
        } finally { mintStore.releaseAnchor(id, owner); }
    }

    public RwaTokenModuleHandler.ChainBuildResult finalizeCreation(String id, byte[] body, HttpHeaders headers) throws Exception {
        var intent = authenticate(id, "finalize", body, headers);
        requireActive(intent);
        // A saved chain may already be partly submitted. Recovery never rechecks spent bootstrap inputs or expiry.
        if ("BUILT".equals(intent.getStatus())) return store.chain(intent);
        requireSession(intent);
        verifyAnchor(intent);
        var claim = store.claimBuild(id);
        if (claim.chain() != null) return claim.chain();
        try { return store.build(id, claim.owner()); }
        finally { mintStore.releaseBuild(id, claim.owner()); }
    }

    public RwaTokenModuleHandler.ChainBuildResult approveAndBuild(String id, byte[] body, HttpHeaders headers) throws Exception {
        var intent = authenticate(id, "approve-and-build", body, headers);
        requireActive(intent);
        // A completed chain remains recoverable after the approval expires.
        if ("BUILT".equals(intent.getStatus())) return store.chain(intent);
        requireSession(intent);
        if (!Set.of("ANCHORED", "BUILDING").contains(intent.getStatus())) {
            String owner = mintStore.claimAnchor(id);
            try {
                var proof = transport.sendAndWait(intent, mintStore.beginDispatch(id, owner));
                mintStore.saveAnchor(id, owner, proof.sequence(), proof.eventJson());
                transport.acknowledge(proof.notificationId());
            } finally { mintStore.releaseAnchor(id, owner); }
        }
        // Another request may have completed the chain while the Veridian wait was in progress.
        intent = store.get(id);
        if ("BUILT".equals(intent.getStatus())) return store.chain(intent);
        requireSession(intent);
        verifyAnchor(intent);
        var claim = store.claimBuild(id);
        if (claim.chain() != null) return claim.chain();
        try { return store.build(id, claim.owner()); }
        finally { mintStore.releaseBuild(id, claim.owner()); }
    }

    public View cancel(String id, byte[] body, HttpHeaders headers) throws Exception {
        authenticate(id, "cancel", body, headers);
        return view(store.release(id));
    }
    public View get(String id, String sessionId) throws Exception {
        var intent = store.get(id);
        if (!Objects.equals(sessionId, intent.getSessionId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creation intent not found");
        return view(intent);
    }
    public InitialMintAttestationStore.Recovery recovery(String id, String sessionId) throws Exception {
        var intent = store.get(id);
        if (!Objects.equals(sessionId, intent.getSessionId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Creation intent not found");
        return store.recovery(intent);
    }
    public InitialMintAttestationStore.Recovery archiveExpired(String id, byte[] body, HttpHeaders headers) throws Exception {
        authenticate(id, "archive-expired", body, headers);
        return store.archiveExpired(id);
    }
    private MintAttestationIntentEntity authenticate(String id, String action, byte[] body, HttpHeaders headers) throws Exception {
        if (body == null || body.length > 4096) throw new IllegalArgumentException("Invalid creation action");
        Action input = mapper.readValue(body, Action.class);
        var intent = store.get(id);
        var fields = mints.fields(intent);
        // The stored payer is authoritative even for ID-only retry/cancel actions.
        verifier.verifyAndConsume(PATH + "/" + id + "/" + action, body, fields.feePayerAddress(), headers);
        if (!Objects.equals(input.sessionId(), intent.getSessionId()) || input.feePayerAddress() == null
                || !Arrays.equals(new Address(input.feePayerAddress()).getBytes(), new Address(fields.feePayerAddress()).getBytes()))
            throw new IllegalArgumentException("Creation action differs from the saved wallet/session");
        return intent;
    }
    private static void requireActive(MintAttestationIntentEntity intent) {
        if ("ARCHIVED_EXPIRED".equals(intent.getStatus()))
            throw new ResponseStatusException(HttpStatus.GONE, "This expired creation was archived; prepare a new policy with a new request ID");
    }
    private void requireSession(MintAttestationIntentEntity intent) throws Exception {
        var session = mints.boundSession(mints.fields(intent));
        if (!Objects.equals(intent.getWalletAid(), session.getAid()) || !Objects.equals(intent.getCredentialSaid(), session.getCredentialAid()))
            throw new IllegalStateException("Veridian identity changed; prepare another creation intent");
    }
    void verifyAnchor(MintAttestationIntentEntity intent) throws Exception {
        if (!Set.of("ANCHORED", "BUILDING").contains(intent.getStatus())) throw new IllegalArgumentException("Initial mint has no verified KERI approval");
        MintAttestationStore.unexpired(intent);
        if (intent.getInitialPrefixJson() == null || intent.getInitialSnapshotJson() == null)
            throw new IllegalStateException("Saved creation has no frozen registration prefix");
        var prefix = mapper.readValue(intent.getInitialPrefixJson(), RwaTokenModuleHandler.ChainBuildResult.class);
        if (!mints.targetDeadline(Transaction.deserialize(HexUtil.decodeHexString(prefix.registrationCborHex())))
                .isAfter(Instant.now().plusSeconds(120)))
            throw new IllegalStateException("Frozen initial mint is near expiry; prepare a new registration");
        if (!mints.targetDeadline(Transaction.deserialize(HexUtil.decodeHexString(prefix.genesisCborHex())))
                .isAfter(Instant.now().plusSeconds(120)))
            throw new IllegalStateException("Frozen genesis is near expiry; prepare a new registration");
        if (!Objects.equals(intent.getDigest(), MintTxHashPayload.digest(prefix.registrationTxHash()))
                || !Objects.equals(intent.getDocumentJson(), Serder.dumps(MintTxHashPayload.signed(prefix.registrationTxHash()))))
            throw new IllegalStateException("Saved creation approval payload differs from frozen mint hash");
        var plan = mapper.readValue(intent.getInitialPlanJson(), RwaTokenModuleHandler.GenesisPlan.class);
        var registration = mapper.readValue(intent.getInitialRegistrationJson(), RwaTokenRegisterRequest.class);
        var params = protocols.resolve(mints.fields(intent).protocolTxHash());
        if (params == null || !Objects.equals(params.txHash(), mints.fields(intent).protocolTxHash()))
            throw new IllegalStateException("Frozen protocol deployment is unavailable");
        try {
            InitialMintTransactionValidator.validate(prefix, mints.fields(intent), registration,
                    plan, params, null);
        } catch (IllegalArgumentException changed) {
            throw new IllegalStateException("Frozen registration settings differ from the signed mint", changed);
        }
        if (intent.getSequenceNumber() == null || intent.getWalletKelFloor() == null
                || new BigInteger(intent.getSequenceNumber(), 16).compareTo(new BigInteger(intent.getWalletKelFloor(), 16)) <= 0
                || transport.verifiedEvent(intent.getWalletAid(), intent.getSequenceNumber(), intent.getDigest()) == null)
            throw new IllegalStateException("Verified initial-mint KERI event is unavailable");
    }
    private View view(MintAttestationIntentEntity i) throws Exception {
        String status = !Set.of("BUILT", "RELEASED", "ARCHIVED_EXPIRED").contains(i.getStatus()) && !i.getExpiresAt().isAfter(Instant.now())
                ? "EXPIRED" : i.getStatus();
        return new View(i.getId(), status, i.getWalletAid(), i.getDigest(), i.getSequenceNumber(),
                null, i.getExpiresAt(), mints.fields(i),
                "NOT_CHECKED", mapper.readValue(i.getInitialRegistrationJson(), RwaTokenRegisterRequest.class),
                "ARCHIVED_EXPIRED".equals(i.getStatus()) ? "ARCHIVED_EXPIRED" : "BUILT".equals(i.getStatus()) ? "UNKNOWN" : "NOT_BUILT", transactionHashes(i),
                i.getInitialPrefixJson() == null ? null : mapper.readValue(i.getInitialPrefixJson(),
                        RwaTokenModuleHandler.ChainBuildResult.class).registrationTxHash());
    }
    private Map<String, String> transactionHashes(MintAttestationIntentEntity i) throws Exception {
        if (!Set.of("BUILT", "ARCHIVED_EXPIRED").contains(i.getStatus())) return Map.of();
        var c = store.chain(i);
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("genesis", c.genesisTxHash()); hashes.put("addPowerUser", c.addPowerUserTxHash());
        hashes.put("cmtaProvenance", c.cmtaProvenanceTxHash()); hashes.put("issuanceProvenance", c.issuanceProvenanceTxHash());
        if (c.publishScriptsTxHash() != null) hashes.put("publishScripts", c.publishScriptsTxHash());
        hashes.put("registration", c.registrationTxHash());
        if (c.attestationTxHash() != null) hashes.put("attestation", c.attestationTxHash());
        if (c.registerTransferLogicTxHash() != null) hashes.put("registerTransferLogic", c.registerTransferLogicTxHash());
        if (c.registerThirdPartyTransferLogicTxHash() != null) hashes.put("registerThirdPartyTransferLogic", c.registerThirdPartyTransferLogicTxHash());
        return hashes;
    }
    static RwaTokenRegisterRequest normalize(RwaTokenRegisterRequest r) {
        if (!"rwa-token".equals(r.getModuleId()) || r.getAttestation() != null || r.getChainingTransactionCborHex() != null
                || r.getGlobalStatePolicyId() != null || r.getDenylistPolicyId() != null || r.getPowerUsersPolicyId() != null)
            throw new IllegalArgumentException("Initial mint requires a new CMTA registration without client-supplied chain or attestation");
        if (r.getInitialMintQuantity() == null || !r.getInitialMintQuantity().trim().matches("[0-9]{1,19}")
                || new BigInteger(r.getInitialMintQuantity().trim()).signum() <= 0
                || new BigInteger(r.getInitialMintQuantity().trim()).bitLength() > 63)
            throw new IllegalArgumentException("Initial mint approval requires a positive int64 supply");
        r.setInitialMintQuantity(new BigInteger(r.getInitialMintQuantity().trim()).toString()); r.setQuantity(null);
        r.setFeePayerAddress(new Address(r.getFeePayerAddress()).getAddress());
        r.setRecipientAddress(r.getRecipientAddress() == null || r.getRecipientAddress().isBlank()
                ? r.getFeePayerAddress() : new Address(r.getRecipientAddress()).getAddress());
        String asset = r.getAssetName() == null ? "" : r.getAssetName().trim().toLowerCase(Locale.ROOT);
        if (!asset.matches("(?:[0-9a-f]{2}){0,32}")) throw new IllegalArgumentException("Invalid asset name");
        r.setAssetName(asset);
        return r;
    }
}
