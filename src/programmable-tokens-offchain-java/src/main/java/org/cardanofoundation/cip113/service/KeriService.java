package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.SchemaConfig;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.AttestAnchorRequest;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.CredentialChainPublishRequest;
import org.cardanofoundation.cip113.model.Role;
import org.cardanofoundation.cip113.model.keri.CredentialResponse;
import org.cardanofoundation.cip113.model.keri.IdentifierConfig;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.cardanofoundation.cip113.model.keri.SchemaItem;
import org.cardanofoundation.cip113.model.keri.SessionResponse;
import org.cardanofoundation.cip113.repository.KycSessionRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.cardanofoundation.cip113.util.CESRStreamUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.cardanofoundation.cip113.util.IpexNotificationHelper;
import org.cardanofoundation.signify.app.Exchanging;
import org.cardanofoundation.signify.app.clienting.SignifyClient;
import org.cardanofoundation.signify.app.coring.Operation;
import org.cardanofoundation.signify.app.credentialing.credentials.CredentialData;
import org.cardanofoundation.signify.app.credentialing.credentials.IssueCredentialResult;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAdmitArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexAgreeArgs;
import org.cardanofoundation.signify.app.credentialing.ipex.IpexGrantArgs;
import org.cardanofoundation.signify.app.credentialing.registries.CreateRegistryArgs;
import org.cardanofoundation.signify.app.credentialing.registries.RegistryResult;
import org.cardanofoundation.signify.cesr.Saider;
import org.cardanofoundation.signify.cesr.util.Utils;
import org.cardanofoundation.signify.generated.keria.model.Credential;
import org.cardanofoundation.signify.generated.keria.model.ExchangeResource;
import org.cardanofoundation.signify.generated.keria.model.HabState;
import org.cardanofoundation.signify.generated.keria.model.KeyStateRecord;
import org.cardanofoundation.signify.generated.keria.model.OOBI;
import org.cardanofoundation.signify.generated.keria.model.Registry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encapsulates all KERI / IPEX / CIP-170 business logic. The matching controller
 * stays a thin HTTP adapter that maps method results and exceptions onto status
 * codes.
 *
 * Exception contract (controller maps these to HTTP status):
 * <ul>
 *     <li>{@link NoSuchElementException} → 404</li>
 *     <li>{@link IllegalArgumentException} → 400</li>
 *     <li>{@link IllegalStateException} → 400</li>
 *     <li>{@link InterruptedException} → 409 (caller cancelled)</li>
 *     <li>{@link RuntimeException} starting with {@code "Timed out"} → 408</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@Slf4j
public class KeriService {

    private static final Pattern OOBI_AID_PATTERN = Pattern.compile("/oobi/([^/]+)");
    private static final DateTimeFormatter KERI_DATETIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'");

    private final IdentifierConfig identifierConfig;
    private final SignifyClient client;
    private final KycSessionRepository kycSessionRepository;
    private final KycProofService kycProofService;
    private final SchemaConfig schemaConfig;
    private final ObjectMapper objectMapper;
    private final QuickTxBuilder quickTxBuilder;

    private final String identifierName;
    private final String registryName;
    private final String signingMnemonic;
    private final String network;

    private final ConcurrentHashMap<String, Thread> activePresentations = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private MpfTreeService mpfTreeService;

    @Autowired(required = false)
    private ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    public KeriService(
            IdentifierConfig identifierConfig,
            SignifyClient client,
            KycSessionRepository kycSessionRepository,
            KycProofService kycProofService,
            SchemaConfig schemaConfig,
            ObjectMapper objectMapper,
            QuickTxBuilder quickTxBuilder,
            @Value("${keri.identifier.name}") String identifierName,
            @Value("${keri.identifier.registry-name:kyc-registry}") String registryName,
            @Value("${keri.signing-mnemonic}") String signingMnemonic,
            @Value("${network:preview}") String network) {
        this.identifierConfig = identifierConfig;
        this.client = client;
        this.kycSessionRepository = kycSessionRepository;
        this.kycProofService = kycProofService;
        this.schemaConfig = schemaConfig;
        this.objectMapper = objectMapper;
        this.quickTxBuilder = quickTxBuilder;
        this.identifierName = identifierName;
        this.registryName = registryName;
        this.signingMnemonic = signingMnemonic;
        this.network = network;
    }

    // ── Signing entity key ─────────────────────────────────────────────────────

    public String getSigningEntityVkey() {
        Account entityAccount = Account.createFromMnemonic(networkInfo(), signingMnemonic);
        return HexUtil.encodeHexString(entityAccount.publicKeyBytes());
    }

    // ── OOBI ──────────────────────────────────────────────────────────────────

    public Optional<String> getOobi() throws Exception {
        Optional<OOBI> o = client.oobis().get(identifierConfig.getName(), null);
        if (o.isEmpty()) {
            return Optional.empty();
        }
        List<String> oobis = o.get().getOobis();
        if (oobis == null || oobis.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(oobis.get(0));
    }

    public boolean resolveOobi(String sessionId, String oobi) throws Exception {
        Object resolve = client.oobis().resolve(oobi, sessionId);
        var wait = client.operations().wait(Operation.fromObject(resolve));
        if (!wait.isDone()) {
            return false;
        }

        Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(oobi).getPath());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No AID found in OOBI URL: " + oobi);
        }
        String aid = matcher.group(1);
        client.contacts().get(aid);

        kycSessionRepository.save(KycSessionEntity.builder()
                .sessionId(sessionId)
                .oobi(oobi)
                .aid(aid)
                .build());
        return true;
    }

    // ── Schema discovery ──────────────────────────────────────────────────────

    public List<SchemaItem> getSchemaList() {
        if (schemaConfig.getSchemas() == null) {
            return List.of();
        }
        List<SchemaItem> schemas = new ArrayList<>();
        for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
            try {
                Role role = Role.fromString(entry.getKey());
                schemas.add(new SchemaItem(entry.getKey(), role.getValue(),
                        entry.getValue().getLabel(), entry.getValue().getSaid()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role name in schema config: {}", entry.getKey());
            }
        }
        schemas.sort(Comparator.comparingInt(SchemaItem::roleValue));
        return schemas;
    }

    public List<Map<String, Object>> getAvailableRoles() {
        if (schemaConfig.getSchemas() == null) {
            return List.of();
        }
        List<Map<String, Object>> roles = new ArrayList<>();
        for (Map.Entry<String, SchemaConfig.SchemaEntry> entry : schemaConfig.getSchemas().entrySet()) {
            try {
                Role role = Role.fromString(entry.getKey());
                roles.add(Map.of(
                        "role", entry.getKey(),
                        "roleValue", role.getValue(),
                        "label", entry.getValue().getLabel()));
            } catch (IllegalArgumentException e) {
                log.warn("Unknown role name in schema config: {}", entry.getKey());
            }
        }
        roles.sort(Comparator.comparingInt(r -> (int) r.get("roleValue")));
        return roles;
    }

    // ── IPEX credential exchange ──────────────────────────────────────────────

    public CredentialResponse presentCredential(String sessionId, String roleName) throws Exception {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));

        Role role = Role.fromString(roleName);
        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            throw new IllegalArgumentException("No schema configured for role: " + roleName);
        }

        String aid = kyc.getAid();
        activePresentations.put(sessionId, Thread.currentThread());
        try {
            // Build /ipex/apply directly via createExchangeMessage so oobiUrl lands at
            // exn.a.oobiUrl (where the wallet's getInlineSchemaOobiBase reads it).
            // signify-java's IpexApplyArgs would nest it under exn.a.a, which the wallet
            // treats as a credential filter attribute and silently drops.
            Map<String, Object> applyData = new LinkedHashMap<>();
            applyData.put("m", "");
            applyData.put("s", schemaEntry.getSaid());
            applyData.put("a", new LinkedHashMap<>());
            applyData.put("oobiUrl", schemaConfig.getBaseUrl());

            HabState hab = client.identifiers().get(identifierName)
                    .orElseThrow(() -> new IllegalStateException("Identifier not found: " + identifierName));
            Exchanging.ExchangeMessageResult applyResult = client.exchanges().createExchangeMessage(
                    hab, "/ipex/apply", applyData, new LinkedHashMap<>(),
                    aid, nowKeriTimestamp(), null);
            Object applyOp = client.ipex().submitApply(identifierName, applyResult.exn(),
                    applyResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(applyOp));

            log.info("Waiting for wallet to respond with an offer...");
            IpexNotificationHelper.Notification offerNote = IpexNotificationHelper.waitForNotification(client,
                    "/exn/ipex/offer");
            ExchangeResource offerResource = client.exchanges().get(offerNote.a.d)
                    .orElseThrow(() -> new IllegalStateException("Offer exchange not found: " + offerNote.a.d));
            String offerSaid = offerResource.getExn().getD();
            IpexNotificationHelper.markAndDelete(client, offerNote);

            IpexAgreeArgs agreeArgs = IpexAgreeArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .offerSaid(offerSaid)
                    .datetime(nowKeriTimestamp())
                    .build();
            Exchanging.ExchangeMessageResult agreeResult = client.ipex().agree(agreeArgs);
            Object agreeOp = client.ipex().submitAgree(identifierName, agreeResult.exn(),
                    agreeResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(agreeOp));

            IpexNotificationHelper.Notification grantNote = IpexNotificationHelper.waitForNotification(client,
                    "/exn/ipex/grant");
            ExchangeResource grantResource = client.exchanges().get(grantNote.a.d)
                    .orElseThrow(() -> new IllegalStateException("Grant exchange not found: " + grantNote.a.d));

            IpexAdmitArgs admitArgs = IpexAdmitArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .grantSaid(grantResource.getExn().getD())
                    .datetime(nowKeriTimestamp())
                    .message("")
                    .build();
            Exchanging.ExchangeMessageResult admit = client.ipex().admit(admitArgs);
            Object admitOp = client.ipex().submitAdmit(identifierName, admit.exn(), admit.sigs(),
                    agreeResult.atc(), Collections.singletonList(aid));
            client.operations().wait(Operation.fromObject(admitOp));
            IpexNotificationHelper.markAndDelete(client, grantNote);

            @SuppressWarnings("unchecked")
            Map<String, Object> acdc = (Map<String, Object>) grantResource.getExn().getE().get("acdc");
            @SuppressWarnings("unchecked")
            Map<String, Object> rawAttributes = (Map<String, Object>) acdc.get("a");
            Map<String, Object> userAttributes = new LinkedHashMap<>(rawAttributes);
            userAttributes.remove("i");

            kyc.setCredentialAid(acdc.get("d").toString());
            kyc.setCredentialSaid(schemaEntry.getSaid());
            try {
                kyc.setCredentialAttributes(objectMapper.writeValueAsString(userAttributes));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize credential attributes", e);
            }
            kyc.setCredentialRole(role.getValue());
            kycSessionRepository.save(kyc);

            return new CredentialResponse(role.name(), role.getValue(),
                    schemaEntry.getLabel(), userAttributes);
        } finally {
            activePresentations.remove(sessionId);
        }
    }

    public boolean cancelPresentation(String sessionId) {
        Thread t = activePresentations.get(sessionId);
        if (t == null) {
            return false;
        }
        t.interrupt();
        return true;
    }

    public CredentialResponse issueCredential(String sessionId,
                                              String firstName,
                                              String lastName,
                                              String email) throws Exception {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));

        Role role = Role.USER;
        SchemaConfig.SchemaEntry schemaEntry = schemaConfig.getSchemaForRole(role);
        if (schemaEntry == null) {
            throw new IllegalStateException("No USER schema configured");
        }

        String walletAid = kyc.getAid();
        String registrySaid = getOrCreateRegistrySaid();

        Map<String, Object> additionalProps = new LinkedHashMap<>();
        additionalProps.put("firstName", firstName);
        additionalProps.put("lastName", lastName);
        additionalProps.put("email", email);

        CredentialData credentialData = CredentialData.builder()
                .ri(registrySaid)
                .s(schemaEntry.getSaid())
                .a(CredentialData.CredentialSubject.builder()
                        .i(walletAid)
                        .dt(nowKeriTimestamp())
                        .additionalProperties(additionalProps)
                        .build())
                .build();

        IssueCredentialResult issueResult = client.credentials().issue(identifierName, credentialData);
        client.operations().wait(Operation.fromObject(issueResult.getOp()));

        String credentialSaid = issueResult.getAcdc().getKed().get("d").toString();
        log.info("Issued credential SAID={} for session={}", credentialSaid, sessionId);

        // Re-fetch the credential to obtain the anc attachment, which IssueCredentialResult does
        // not expose. includeCESR=true asks KERIA to return the CESR-encoded ancatc list.
        Credential issuedCredential = client.credentials().get(credentialSaid, true)
                .orElseThrow(() -> new IllegalStateException("Issued credential not found: " + credentialSaid));
        List<String> ancatc = issuedCredential.getAncatc();
        String ancAttachment = (ancatc != null && !ancatc.isEmpty()) ? ancatc.getFirst() : null;

        IpexGrantArgs grantArgs = IpexGrantArgs.builder()
                .senderName(identifierName)
                .recipient(walletAid)
                .datetime(nowKeriTimestamp())
                .acdc(issueResult.getAcdc())
                .iss(issueResult.getIss())
                .anc(issueResult.getAnc())
                .ancAttachment(ancAttachment)
                .build();
        Exchanging.ExchangeMessageResult grantResult = buildGrantExchange(grantArgs,
                schemaConfig.getBaseUrl(), schemaEntry.getSaid());
        Object grantOp = client.ipex().submitGrant(identifierName, grantResult.exn(),
                grantResult.sigs(), grantResult.atc(), Collections.singletonList(walletAid));
        client.operations().wait(Operation.fromObject(grantOp));

        log.info("IPEX grant submitted for credential SAID={}, waiting for wallet admit...", credentialSaid);

        // submitGrant only confirms KERIA queued the message; the wallet still has to
        // surface the credential and send back /ipex/admit once the user accepts.
        IpexNotificationHelper.Notification admitNote = IpexNotificationHelper.waitForNotification(
                client, "/exn/ipex/admit");
        IpexNotificationHelper.markAndDelete(client, admitNote);

        log.info("Wallet admitted credential SAID={}", credentialSaid);

        kyc.setCredentialAid(credentialSaid);
        kyc.setCredentialSaid(schemaEntry.getSaid());
        try {
            kyc.setCredentialAttributes(objectMapper.writeValueAsString(additionalProps));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize credential attributes", e);
        }
        kyc.setCredentialRole(role.getValue());
        kycSessionRepository.save(kyc);

        return new CredentialResponse(role.name(), role.getValue(),
                schemaEntry.getLabel(), additionalProps);
    }

    // ── Session state ─────────────────────────────────────────────────────────

    public SessionResponse getSession(String sessionId) {
        if (sessionId == null) {
            return SessionResponse.builder().exists(false).build();
        }
        Optional<KycSessionEntity> opt = kycSessionRepository.findById(sessionId);
        if (opt.isEmpty()) {
            return SessionResponse.builder().exists(false).build();
        }
        KycSessionEntity kyc = opt.get();
        boolean hasCredential = kyc.getCredentialAttributes() != null;
        boolean hasCardanoAddress = kyc.getCardanoAddress() != null;

        SessionResponse.SessionResponseBuilder builder = SessionResponse.builder()
                .exists(true)
                .hasCredential(hasCredential)
                .hasCardanoAddress(hasCardanoAddress);

        if (hasCredential) {
            builder.attributes(resolveAttributes(kyc));
            builder.credentialRole(kyc.getCredentialRole() != null ? kyc.getCredentialRole() : 0);
            if (kyc.getCredentialRole() != null) {
                try {
                    builder.credentialRoleName(Role.fromValue(kyc.getCredentialRole()).name());
                } catch (IllegalArgumentException e) {
                    builder.credentialRoleName("USER");
                }
            }
        }
        if (hasCardanoAddress) {
            builder.cardanoAddress(kyc.getCardanoAddress());
        }
        if (kyc.getKycProofPayload() != null) {
            builder.kycProofPayload(kyc.getKycProofPayload())
                    .kycProofSignature(kyc.getKycProofSignature())
                    .kycProofEntityVkey(kyc.getKycProofEntityVkey())
                    .kycProofValidUntil(kyc.getKycProofValidUntil());
        }
        return builder.build();
    }

    public void storeCardanoAddress(String sessionId, String cardanoAddress) {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        kyc.setCardanoAddress(cardanoAddress);
        kycSessionRepository.save(kyc);
    }

    /**
     * Bind the session to a kyc-extended programmable token policy. After binding, the next
     * KERI proof generation will auto-upsert the session's PKH into the per-policy MPF tree.
     *
     * @throws IllegalArgumentException if the policy is not a kyc-extended token
     */
    public void bindSessionToToken(String sessionId, String policyId) {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (programmableTokenRegistryRepository == null) {
            throw new IllegalStateException("kyc-extended discovery not available");
        }
        var reg = programmableTokenRegistryRepository.findByPolicyId(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Token not registered: " + policyId));
        if (!"kyc-extended".equals(reg.getSubstandardId())) {
            throw new IllegalArgumentException("Token substandard is not 'kyc-extended': " + reg.getSubstandardId());
        }
        kyc.setBoundTokenPolicyId(policyId);
        kycSessionRepository.save(kyc);
        log.info("Session {} bound to kyc-extended token {}", sessionId, policyId);
    }

    // ── KYC proof generation ──────────────────────────────────────────────────

    public KycProofResponse generateKycProof(String sessionId) {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));

        String userAddress = kyc.getCardanoAddress();
        if (userAddress == null || userAddress.isBlank()) {
            throw new IllegalStateException("No Cardano address on record — please connect your wallet first");
        }
        if (kyc.getCredentialRole() == null) {
            throw new IllegalStateException("No credential on record — please present a credential first");
        }

        KycProofResponse proof = kycProofService.generateProof(userAddress, kyc.getCredentialRole());

        kyc.setKycProofPayload(proof.payloadHex());
        kyc.setKycProofSignature(proof.signatureHex());
        kyc.setKycProofEntityVkey(proof.entityVkeyHex());
        kyc.setKycProofValidUntil(proof.validUntilPosixMs());
        kycSessionRepository.save(kyc);

        autoUpsertMpfMember(kyc, proof);

        return proof;
    }

    private void autoUpsertMpfMember(KycSessionEntity kyc, KycProofResponse proof) {
        String boundPolicyId = kyc.getBoundTokenPolicyId();
        if (boundPolicyId == null) return;
        if (kyc.getCardanoAddress() == null) return;
        if (mpfTreeService == null || programmableTokenRegistryRepository == null) return;

        var regOpt = programmableTokenRegistryRepository.findByPolicyId(boundPolicyId);
        if (regOpt.isEmpty() || !"kyc-extended".equals(regOpt.get().getSubstandardId())) return;

        // Identity in the kyc-extended MPF tree is the stake credential — the on-chain
        // transfer validator extracts witnesses from prog-token outputs' stake credential.
        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(kyc.getCardanoAddress());
        if (pkh == null) {
            log.warn("Cannot derive stake-cred PKH from address {} for auto-upsert (base address required)",
                    kyc.getCardanoAddress());
            return;
        }
        try {
            mpfTreeService.putMember(boundPolicyId, pkh, proof.validUntilPosixMs(),
                    kyc.getCardanoAddress(), kyc.getSessionId());
            log.info("Auto-upserted member into kyc-extended MPF tree: policy={}, sessionId={}",
                    boundPolicyId, kyc.getSessionId());
        } catch (Exception e) {
            log.warn("Failed to auto-upsert MPF member for policy {} session {}: {}",
                    boundPolicyId, kyc.getSessionId(), e.getMessage());
        }
    }

    // ── CIP-170 credential chain publishing & attestation ─────────────────────

    public String publishCredentialChain(String sessionId, CredentialChainPublishRequest request) throws Exception {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (kyc.getCredentialAid() == null) {
            throw new IllegalStateException("No credential on record — please present a credential first");
        }

        String credentialSaid = kyc.getCredentialAid();
        String cesrChain = fetchCredentialCesrChain(credentialSaid);
        if (cesrChain == null) {
            throw new IllegalArgumentException("Credential chain not found for SAID: " + credentialSaid);
        }

        List<Map<String, Object>> cesrData = CESRStreamUtil.parseCESRData(cesrChain);
        String strippedCesrChain = stripCesrChainToVcpIssAcdc(cesrData);
        byte[][] chunks = splitIntoChunks(strippedCesrChain.getBytes(), 64);

        MetadataList credentialChunks = MetadataBuilder.createList();
        for (byte[] chunk : chunks) {
            credentialChunks.add(chunk);
        }

        MetadataMap cip170Map = MetadataBuilder.createMap();
        cip170Map.put("t", "AUTH_BEGIN");
        cip170Map.put("i", kyc.getAid());
        cip170Map.put("s", kyc.getCredentialSaid());
        cip170Map.put("c", credentialChunks);

        MetadataMap versionMap = MetadataBuilder.createMap();
        versionMap.put("v", "1.0");
        versionMap.put("k", "KERI10JSON");
        versionMap.put("a", "ACDC10JSON");
        cip170Map.put("v", versionMap);

        Metadata metadata = MetadataBuilder.createMetadata();
        metadata.put(170L, cip170Map);

        Tx tx = new Tx()
                .from(request.feePayerAddress())
                .payToAddress(request.feePayerAddress(), Amount.ada(1))
                .attachMetadata(metadata)
                .withChangeAddress(request.feePayerAddress());

        Transaction transaction = quickTxBuilder.compose(tx)
                .feePayer(request.feePayerAddress())
                .mergeOutputs(true)
                .build();

        log.info("CIP-170 AUTH_BEGIN tx built for session={}, signer={}", sessionId, kyc.getAid());
        return transaction.serializeToHex();
    }

    public Cip170AttestationData requestAttestation(String sessionId, AttestAnchorRequest request) throws Exception {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));

        String userAid = kyc.getAid();
        if (userAid == null || userAid.isBlank()) {
            throw new IllegalStateException("No AID on record — please complete OOBI exchange first");
        }

        // Build payload and compute SAID.
        // CRITICAL: signify-java's createExchangeMessage (Exchanging.java:228) does
        // `attrs.put("i", recipient); attrs.putAll(payload)` BEFORE the wire send. If
        // we omit `i` from the payload here, our pre-computed SAID is for {d, unit, quantity}
        // but the SAID Veridian recomputes is for {i, d, unit, quantity} → mismatch →
        // wallet's processRemoteSignReq calls markNotification and silently drops the
        // request without surfacing UI. Inserting `i` first ourselves keeps the SAIDs in sync.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("i", userAid);
        payload.put("d", "");
        payload.put("unit", request.unit());
        payload.put("quantity", request.quantity());

        var saidifyResult = Saider.saidify(payload);
        Map<String, Object> ked = saidifyResult.sad();
        String digest = (String) ked.get("d");

        log.info("SAID computed for attestation: digest={}, unit={}, quantity={}",
                digest, request.unit(), request.quantity());

        HabState hab = client.identifiers().get(identifierName)
                .orElseThrow(() -> new IllegalStateException("Identifier not found: " + identifierName));

        client.exchanges().send(identifierName, "remotesign",
                hab, "/remotesign/ixn/req", ked, Map.of(), List.of(userAid));

        log.info("Remotesign ixn request sent to wallet AID={}, digest={}", userAid, digest);

        // KERIA prefixes inbound exn routes with "/exn/" when surfacing them as
        // notifications, so we accept both forms.
        IpexNotificationHelper.Notification refNote = IpexNotificationHelper.waitForNotification(client,
                "/remotesign/ixn/ref", "/exn/remotesign/ixn/ref");
        IpexNotificationHelper.markAndDelete(client, refNote);

        // signify-java's keyStates().query signature is (pre, sn) — the second arg is an
        // OPTIONAL hex sequence-number string; passing the AID there makes KERIA do
        // `int(aid, 16)` and crash. Use null for "latest state".
        String seqNumber = "<unknown>";
        Thread.sleep(2000); // let the new ixn settle in KERIA before querying
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Object queryOp = client.keyStates().query(userAid, null);
                client.operations().wait(Operation.fromObject(queryOp));
                Optional<KeyStateRecord> raw = client.keyStates().get(userAid);
                if (raw.isPresent() && raw.get().getS() != null) {
                    seqNumber = raw.get().getS();
                    break;
                }
                log.info("Key state for {} not available yet (attempt {}/5)", userAid, attempt);
            } catch (Exception ex) {
                log.warn("keyStates query attempt {}/5 failed: {} — retrying", attempt, ex.toString());
            }
            Thread.sleep(3000);
        }

        log.info("CIP-170 attestation anchored: signer={}, digest={}, seq={}", userAid, digest, seqNumber);
        return new Cip170AttestationData(userAid, digest, seqNumber, "1.0");
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private com.bloxbean.cardano.client.common.model.Network networkInfo() {
        return switch (network) {
            case "mainnet" -> Networks.mainnet();
            case "preprod" -> Networks.preprod();
            default -> Networks.preview();
        };
    }

    private String nowKeriTimestamp() {
        return KERI_DATETIME.format(LocalDateTime.now(ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveAttributes(KycSessionEntity kyc) {
        if (kyc.getCredentialAttributes() == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(kyc.getCredentialAttributes(), Map.class);
        } catch (Exception e) {
            log.warn("Failed to parse credential attributes for session={}", kyc.getSessionId(), e);
            return Map.of();
        }
    }

    private String getOrCreateRegistrySaid() throws Exception {
        List<Registry> registries = client.registries().list(identifierName);
        if (registries != null && !registries.isEmpty()) {
            return registries.getFirst().getRegk();
        }
        log.info("No credential registry found, creating '{}'", registryName);
        CreateRegistryArgs args = CreateRegistryArgs.builder()
                .name(identifierName)
                .registryName(registryName)
                .noBackers(true)
                .build();
        RegistryResult result = client.registries().create(args);
        @SuppressWarnings("unchecked")
        Map<String, Object> opMap = objectMapper.readValue(result.op(), Map.class);
        client.operations().wait(Operation.fromObject(opMap));
        return result.getRegser().getPre();
    }

    /**
     * Fetches the credential together with its full CESR chain (vcp + iss + acdc events with
     * attachments). The signify-java client's typed {@code Credential} model only exposes the
     * issuance and anchor events, but CIP-170 AUTH_BEGIN requires the registry inception event
     * too — so we hit KERIA directly with {@code Accept: application/json+cesr}.
     */
    private String fetchCredentialCesrChain(String credentialSaid) throws Exception {
        var response = client.fetch("/credentials/" + credentialSaid, "GET", null,
                Map.of("Accept", "application/json+cesr"));
        if (response.statusCode() == HttpURLConnection.HTTP_NOT_FOUND) {
            return null;
        }
        return response.body();
    }

    private Exchanging.ExchangeMessageResult buildGrantExchange(IpexGrantArgs args,
                                                                String schemaUrl,
                                                                String schemaSAID) throws Exception {
        HabState hab = client.identifiers().get(args.getSenderName())
                .orElseThrow(() -> new IllegalArgumentException("Identifier not found: " + args.getSenderName()));

        String acdcAtc = new String(Utils.serializeACDCAttachment(args.getIss()));
        String issAtc = new String(Utils.serializeIssExnAttachment(args.getAnc()));
        String ancAtc = args.getAncAttachment();

        Map<String, List<Object>> embeds = new LinkedHashMap<>();
        embeds.put("acdc", Arrays.asList(args.getAcdc(), acdcAtc));
        embeds.put("iss", Arrays.asList(args.getIss(), issAtc));
        embeds.put("anc", Arrays.asList(args.getAnc(), ancAtc));

        Map<String, Object> data = Map.of(
                "m", args.getMessage() != null ? args.getMessage() : "",
                "s", schemaSAID,
                "oobiUrl", schemaUrl);

        return client.exchanges().createExchangeMessage(
                hab, "/ipex/grant", data, embeds,
                args.getRecipient(), args.getDatetime(), args.getAgreeSaid());
    }

    private byte[][] splitIntoChunks(byte[] data, int chunkSize) {
        int numChunks = (data.length + chunkSize - 1) / chunkSize;
        byte[][] chunks = new byte[numChunks][];
        for (int i = 0; i < numChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            chunks[i] = Arrays.copyOfRange(data, start, end);
        }
        return chunks;
    }

    /**
     * Reduces a parsed CESR stream to the canonical AUTH_BEGIN event ordering:
     * registry inception (vcp), credential issuance (iss), then ACDC events.
     */
    @SuppressWarnings("unchecked")
    private String stripCesrChainToVcpIssAcdc(List<Map<String, Object>> cesrData) {
        List<Map<String, Object>> vcpEvents = new ArrayList<>();
        List<String> vcpAttachments = new ArrayList<>();
        List<Map<String, Object>> issEvents = new ArrayList<>();
        List<String> issAttachments = new ArrayList<>();
        List<Map<String, Object>> acdcEvents = new ArrayList<>();
        List<String> acdcAttachments = new ArrayList<>();

        for (Map<String, Object> eventData : cesrData) {
            Map<String, Object> event = (Map<String, Object>) eventData.get("event");
            Object eventTypeObj = event.get("t");
            if (eventTypeObj != null) {
                switch (eventTypeObj.toString()) {
                    case "vcp" -> {
                        vcpEvents.add(event);
                        vcpAttachments.add((String) eventData.get("atc"));
                    }
                    case "iss" -> {
                        issEvents.add(event);
                        issAttachments.add((String) eventData.get("atc"));
                    }
                    default -> {
                        // ignore other KEL/TEL events for AUTH_BEGIN
                    }
                }
            } else if (event.containsKey("s") && event.containsKey("a") && event.containsKey("i")
                    && event.get("s") != null) {
                // ACDC payload — has no "t" field
                acdcEvents.add(event);
                acdcAttachments.add("");
            }
        }

        List<Map<String, Object>> combinedEvents = new ArrayList<>();
        combinedEvents.addAll(vcpEvents);
        combinedEvents.addAll(issEvents);
        combinedEvents.addAll(acdcEvents);

        List<String> combinedAttachments = new ArrayList<>();
        combinedAttachments.addAll(vcpAttachments);
        combinedAttachments.addAll(issAttachments);
        combinedAttachments.addAll(acdcAttachments);

        return CESRStreamUtil.makeCESRStream(combinedEvents, combinedAttachments);
    }
}
