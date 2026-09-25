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
import org.cardanofoundation.cip113.entity.KycIssuanceEntity;
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
import org.cardanofoundation.cip113.service.keri.TokenMembershipHook;
import org.cardanofoundation.cip113.util.CESRStreamUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.cardanofoundation.cip113.util.IpexNotificationHelper;
import id.veridian.signify.app.Exchanging;
import id.veridian.signify.app.clienting.SignifyClient;
import id.veridian.signify.app.credentialing.credentials.CredentialData;
import id.veridian.signify.app.credentialing.credentials.IssueCredentialResult;
import id.veridian.signify.app.credentialing.ipex.IpexAdmitArgs;
import id.veridian.signify.app.credentialing.ipex.IpexAgreeArgs;
import id.veridian.signify.app.credentialing.ipex.IpexGrantArgs;
import id.veridian.signify.app.credentialing.registries.CreateRegistryArgs;
import id.veridian.signify.app.credentialing.registries.RegistryResult;
import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;
import id.veridian.signify.cesr.util.Utils;
import id.veridian.signify.generated.keria.model.CompletedOperation;
import id.veridian.signify.generated.keria.model.CompletedCredentialOperation;
import id.veridian.signify.generated.keria.model.CompletedExchangeOperation;
import id.veridian.signify.generated.keria.model.Credential;
import id.veridian.signify.generated.keria.model.ExchangeResource;
import id.veridian.signify.generated.keria.model.FailedOperation;
import id.veridian.signify.generated.keria.model.HabState;
import id.veridian.signify.generated.keria.model.KeyStateRecord;
import id.veridian.signify.generated.keria.model.OOBI;
import id.veridian.signify.generated.keria.model.Operation;
import id.veridian.signify.generated.keria.model.Registry;
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
    private final SchemaOobiVerifier schemaOobiVerifier;
    private final ObjectMapper objectMapper;
    private final QuickTxBuilder quickTxBuilder;

    private final String identifierName;
    private final String registryName;
    private final String signingMnemonic;
    private final String network;

    private final ConcurrentHashMap<String, Thread> activePresentations = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    private final KycIssuanceStore issuanceStore;

    /** Per-module membership hooks keyed by {@link TokenMembershipHook#moduleId()}.
     *  Populated by Spring with every {@link TokenMembershipHook} bean on the classpath, so
     *  adding or removing a module's hook is a self-contained, no-edit change here. */
    private final Map<String, TokenMembershipHook> membershipHooks;

    public KeriService(
            IdentifierConfig identifierConfig,
            SignifyClient client,
            KycSessionRepository kycSessionRepository,
            KycProofService kycProofService,
            SchemaConfig schemaConfig,
            KycIssuanceStore issuanceStore,
            ObjectMapper objectMapper,
            QuickTxBuilder quickTxBuilder,
            List<TokenMembershipHook> hooks,
            @Value("${keri.identifier.name}") String identifierName,
            @Value("${keri.identifier.registry-name:kyc-registry}") String registryName,
            @Value("${keri.signing-mnemonic}") String signingMnemonic,
            @Value("${network:preview}") String network) {
        this.identifierConfig = identifierConfig;
        this.client = client;
        this.kycSessionRepository = kycSessionRepository;
        this.kycProofService = kycProofService;
        this.schemaConfig = schemaConfig;
        this.issuanceStore = issuanceStore;
        this.schemaOobiVerifier = new SchemaOobiVerifier(objectMapper);
        this.objectMapper = objectMapper;
        this.quickTxBuilder = quickTxBuilder;
        this.membershipHooks = hooks.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        TokenMembershipHook::moduleId, h -> h));
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
        var resolve = client.oobis().resolve(oobi, sessionId);
        var wait = client.operations().wait(resolve);
        // signify 0.1.2-d92f263 replaced the untyped Operation.isDone() flag with distinct
        // Completed*/Pending* operation types, so completion is now a type test.
        if (!(wait instanceof CompletedOperation)) {
            return false;
        }

        Matcher matcher = OOBI_AID_PATTERN.matcher(URI.create(oobi).getPath());
        if (!matcher.find()) {
            throw new IllegalArgumentException("No AID found in OOBI URL: " + oobi);
        }
        String aid = matcher.group(1);
        client.contacts().get(aid);

        issuanceStore.applyResolvedOobi(sessionId, oobi, aid);
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
        String presentationOwner = issuanceStore.reservePresentation(sessionId, aid, schemaEntry.getSaid());
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
            var applyOp = client.ipex().submitApply(identifierName, applyResult.exn(),
                    applyResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(applyOp);

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
            var agreeOp = client.ipex().submitAgree(identifierName, agreeResult.exn(),
                    agreeResult.sigs(), Collections.singletonList(aid));
            client.operations().wait(agreeOp);

            IpexNotificationHelper.Notification grantNote = IpexNotificationHelper.waitForNotification(client,
                    "/exn/ipex/grant");
            ExchangeResource grantResource = client.exchanges().get(grantNote.a.d)
                    .orElseThrow(() -> new IllegalStateException("Grant exchange not found: " + grantNote.a.d));

            @SuppressWarnings("unchecked")
            Map<String, Object> acdc = (Map<String, Object>) grantResource.getExn().getE().get("acdc");
            if (acdc == null || !schemaEntry.getSaid().equals(acdc.get("s"))) {
                throw new IllegalStateException("Presented credential schema does not match the requested role");
            }

            IpexAdmitArgs admitArgs = IpexAdmitArgs.builder()
                    .senderName(identifierName)
                    .recipient(aid)
                    .grantSaid(grantResource.getExn().getD())
                    .datetime(nowKeriTimestamp())
                    .message("")
                    .build();
            Exchanging.ExchangeMessageResult admit = client.ipex().admit(admitArgs);
            var admitOp = client.ipex().submitAdmit(identifierName, admit.exn(), admit.sigs(),
                    agreeResult.atc(), Collections.singletonList(aid));
            client.operations().wait(admitOp);
            IpexNotificationHelper.markAndDelete(client, grantNote);

            @SuppressWarnings("unchecked")
            Map<String, Object> rawAttributes = (Map<String, Object>) acdc.get("a");
            Map<String, Object> userAttributes = new LinkedHashMap<>(rawAttributes);
            userAttributes.remove("i");

            issuanceStore.acceptPresented(sessionId, presentationOwner, aid, acdc.get("d").toString(),
                    schemaEntry.getSaid(), objectMapper.writeValueAsString(userAttributes), role.getValue());

            return new CredentialResponse(role.name(), role.getValue(),
                    schemaEntry.getLabel(), userAttributes);
        } finally {
            issuanceStore.releasePresentation(sessionId, presentationOwner);
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
        if (walletAid == null || walletAid.isBlank()) {
            throw new IllegalStateException("Resolve the Veridian profile OOBI before issuing a credential");
        }
        log.info("Checking IPEX issuance prerequisites walletAid={} schemaSaid={}",
                walletAid, schemaEntry.getSaid());
        var contact = client.contacts().get(walletAid);
        if (contact.isEmpty() || !walletAid.equals(contact.get().getId())) {
            log.warn("IPEX issuance preflight failed: backend contact missing walletAid={}", walletAid);
            throw new IllegalStateException("Veridian profile connection is missing on the backend. Reconnect its OOBI before issuing.");
        }
        // This is the exact URL embedded in the grant. Check it before creating
        // an irreversible credential so a stale schema host cannot cause a silent wait.
        try {
            schemaOobiVerifier.verify(schemaConfig.getBaseUrl(), schemaEntry.getSaid());
        } catch (IllegalStateException e) {
            log.warn("IPEX issuance preflight failed: schema OOBI unavailable schemaSaid={} reason={}",
                    schemaEntry.getSaid(), e.getMessage());
            throw e;
        }
        String issuerAid = client.identifiers().get(identifierName)
                .orElseThrow(() -> new IllegalStateException("KERI issuer identifier is unavailable"))
                .getPrefix();
        if (issuerAid == null || issuerAid.isBlank()) {
            throw new IllegalStateException("KERI issuer identifier has no AID");
        }
        log.info("Starting IPEX issuance issuerAid={} walletAid={} schemaSaid={}",
                issuerAid, walletAid, schemaEntry.getSaid());
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

        issuanceStore.claim(sessionId, walletAid, issuerAid, schemaEntry.getSaid(), schemaConfig.getBaseUrl(),
                objectMapper.writeValueAsString(additionalProps));
        IssueCredentialResult issueResult;
        try {
            issueResult = client.credentials().issue(identifierName, credentialData);
            // Signify has already POSTed to KERIA when issue returns. Persist everything
            // needed to recover before waiting on the asynchronous KERIA operation.
            issuanceStore.saveIssued(sessionId,
                    issueResult.getAcdc().getKed().get("d").toString(),
                    issueResult.getOp().getName(),
                    issueResult.getAcdc().getRaw(), issueResult.getIss().getRaw(),
                    issueResult.getAnc().getRaw());
        } catch (Exception e) {
            issuanceStore.markUnknown(sessionId);
            throw new IllegalStateException("Credential issuance outcome is uncertain. Do not issue again; ask the operator to reconcile this session.", e);
        }
        return prepareAndDeliverGrant(sessionId, issueResult);
    }

    /** Reuses the server-side credential and the original signed grant. */
    public CredentialResponse retryGrantDelivery(String sessionId) throws Exception {
        KycIssuanceEntity issue = issuanceStore.find(sessionId)
                .orElseThrow(() -> new NoSuchElementException("No issued credential is available for this session"));
        if ("ACCEPTED".equals(issue.getStatus())) {
            return responseFromIssue(issue);
        }
        if ("ISSUING".equals(issue.getStatus()) || "ISSUANCE_UNKNOWN".equals(issue.getStatus())) {
            throw new IllegalStateException("Credential issuance outcome is uncertain. An operator must reconcile it before retrying.");
        }
        if (issue.getGrantRaw() == null) {
            if (!"ISSUED".equals(issue.getStatus())
                    && !"BUILDING".equals(issue.getStatus())) {
                throw new IllegalStateException("Credential grant is not ready for delivery");
            }
            return prepareAndDeliverGrant(sessionId, null);
        }
        return deliverGrant(sessionId);
    }

    private CredentialResponse prepareAndDeliverGrant(String sessionId, IssueCredentialResult issuedResult) throws Exception {
        String buildOwner = issuanceStore.claimGrantBuild(sessionId);
        try {
        KycIssuanceEntity issue = issuanceStore.find(sessionId).orElseThrow();
        requireCurrentIssuer(issue);
        if (issuedResult == null) {
            issuedResult = IssueCredentialResult.builder()
                    .acdc(serder(issue.getAcdcJson()))
                    .iss(serder(issue.getIssJson()))
                    .anc(serder(issue.getAncJson()))
                    .build();
        }
        var operation = issuedResult.getOp() != null ? issuedResult.getOp()
                : client.operations().get(issue.getIssueOperationName())
                    .orElseThrow(() -> new IllegalStateException("KERIA credential issuance operation is unavailable"));
        requireCompletedOperation(client.operations().wait(operation),
                CompletedCredentialOperation.class, "credential issuance");
        String credentialSaid = issue.getCredentialSaid();
        log.info("Issued credential SAID={} for session={}", credentialSaid, sessionId);

        // The JSON representation includes ancatc. KERIA's CESR representation is a raw
        // stream, which Signify's typed credentials().get method cannot deserialize.
        Credential issuedCredential = client.credentials().get(credentialSaid, false)
                .orElseThrow(() -> new IllegalStateException(
                        "Issued credential anchor attachment retrieval failed: credential not found " + credentialSaid));
        String ancAttachment = requireAnchorAttachment(issuedCredential, credentialSaid);

        IpexGrantArgs grantArgs = IpexGrantArgs.builder()
                .senderName(identifierName)
                .recipient(issue.getWalletAid())
                .datetime(nowKeriTimestamp())
                .acdc(issuedResult.getAcdc())
                .iss(issuedResult.getIss())
                .anc(issuedResult.getAnc())
                .ancAttachment(ancAttachment)
                .build();
        var signingEstablishment = currentEstablishment();
        Exchanging.ExchangeMessageResult grantResult = buildGrantExchange(grantArgs,
                issue.getSchemaOobiUrl(), issue.getSchemaSaid());
        Object grantSaidValue = grantResult.exn().getKed().get("d");
        if (!(grantSaidValue instanceof String grantSaid) || grantSaid.isBlank()) {
            throw new IllegalStateException("Submitted IPEX grant has no exchange SAID");
        }

        var establishment = currentEstablishment();
        if (!signingEstablishment.getS().equals(establishment.getS())
                || !signingEstablishment.getD().equals(establishment.getD())) {
            throw new IllegalStateException("Issuer key state rotated while signing the grant; operator recovery is required");
        }
        issuanceStore.saveGrant(sessionId, buildOwner, grantSaid, grantResult.exn().getRaw(),
                objectMapper.writeValueAsString(grantResult.sigs()), grantResult.atc(),
                establishment.getS(), establishment.getD());
        return deliverGrant(sessionId);
        } finally {
            issuanceStore.releaseGrantBuild(sessionId, buildOwner);
        }
    }

    private CredentialResponse deliverGrant(String sessionId) throws Exception {
        String owner = issuanceStore.claimDelivery(sessionId);
        KycIssuanceEntity issue = issuanceStore.find(sessionId).orElseThrow();
        String grantSaid = issue.getGrantSaid();
        try {
            requireCurrentIssuer(issue);
            var establishment = currentEstablishment();
            if (!issue.getSigningEstablishmentSeq().equals(establishment.getS())
                    || !issue.getSigningEstablishmentDigest().equals(establishment.getD())) {
                throw new IllegalStateException("Issuer key state changed since this grant was signed. Operator recovery is required.");
            }
            Serder grant = serder(issue.getGrantRaw());
            if (!issue.getGrantRaw().equals(grant.getRaw())
                    || !grantSaid.equals(grant.getKed().get("d"))) {
                throw new IllegalStateException("Stored signed grant is invalid; operator recovery is required");
            }
            @SuppressWarnings("unchecked")
            List<String> sigs = objectMapper.readValue(issue.getGrantSigs(), List.class);
            var grantOp = client.ipex().submitGrant(identifierName, grant,
                    sigs, issue.getGrantAtc(), Collections.singletonList(issue.getWalletAid()));
            requireCompletedOperation(client.operations().wait(grantOp),
                    CompletedExchangeOperation.class, "credential grant");
            log.info("IPEX grant submitted credentialSaid={} grantSaid={} issuerAid={} walletAid={}; waiting for admit",
                    issue.getCredentialSaid(), grantSaid, issue.getIssuerAid(), issue.getWalletAid());
            IpexNotificationHelper.Notification admitNote = IpexNotificationHelper.waitForAdmit(
                    client, grantSaid, issue.getWalletAid(), issue.getIssuerAid());
            issuanceStore.accept(sessionId, owner, grantSaid);
            try {
                IpexNotificationHelper.markAndDelete(client, admitNote);
            } catch (Exception e) {
                log.warn("Could not clear matching admit notification grantSaid={}", grantSaid, e);
            }
            return responseFromIssue(issue);
        } finally {
            issuanceStore.releaseDelivery(sessionId, owner);
        }
    }

    private id.veridian.signify.generated.keria.model.StateEERecord currentEstablishment() throws Exception {
        HabState hab = client.identifiers().get(identifierName)
                .orElseThrow(() -> new IllegalStateException("KERI issuer identifier is unavailable"));
        if (hab.getState() == null || hab.getState().getEe() == null) {
            throw new IllegalStateException("Issuer establishment key state is unavailable");
        }
        return hab.getState().getEe();
    }

    private void requireCurrentIssuer(KycIssuanceEntity issue) throws Exception {
        HabState hab = client.identifiers().get(identifierName)
                .orElseThrow(() -> new IllegalStateException("KERI issuer identifier is unavailable"));
        if (!issue.getIssuerAid().equals(hab.getPrefix())) {
            throw new IllegalStateException("Issuer AID changed since credential issuance. Operator recovery is required.");
        }
    }

    private Serder serder(String raw) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> ked = objectMapper.readValue(raw, LinkedHashMap.class);
        return new Serder(ked);
    }

    private CredentialResponse responseFromIssue(KycIssuanceEntity issue) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = objectMapper.readValue(issue.getAttributesJson(), LinkedHashMap.class);
        SchemaConfig.SchemaEntry schema = schemaConfig.getSchemaForRole(Role.USER);
        return new CredentialResponse("USER", Role.USER.getValue(),
                schema == null ? "User" : schema.getLabel(), attributes);
    }

    static void requireCompletedOperation(Operation result, Class<? extends Operation> expected,
                                          String stage) {
        if (result instanceof FailedOperation failed) {
            var error = failed.getError();
            String detail = error == null ? "no error details" :
                    "code " + error.getCode() + ": " + error.getMessage();
            throw new RuntimeException("KERIA " + stage + " operation " + result.getName()
                    + " failed (" + detail + "). Check issuer KERIA processing and recipient routing.");
        }
        if (result == null || !expected.isInstance(result)) {
            throw new RuntimeException("KERIA " + stage + " did not return a completed "
                    + expected.getSimpleName() + " operation");
        }
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

        issuanceStore.find(sessionId).ifPresent(issue -> builder
                .issuanceStatus(issue.getStatus())
                .canRetryGrant("READY".equals(issue.getStatus())
                        || "ISSUED".equals(issue.getStatus())
                        || (("WAITING".equals(issue.getStatus()) || "BUILDING".equals(issue.getStatus()))
                                && issue.getDeliveryLeaseUntil() != null
                                && issue.getDeliveryLeaseUntil().isBefore(java.time.Instant.now()))));

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

    /** Bind a verified session to a module with a KERI membership hook. */
    public void bindSessionToToken(String sessionId, String policyId) {
        KycSessionEntity kyc = kycSessionRepository.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
        if (programmableTokenRegistryRepository == null) {
            throw new IllegalStateException("token discovery not available");
        }
        var reg = programmableTokenRegistryRepository.findByPolicyId(policyId)
                .orElseThrow(() -> new IllegalArgumentException("Token not registered: " + policyId));
        if (!"kyc-extended".equals(reg.getModuleId()) && !"rwa-token".equals(reg.getModuleId())) {
            throw new IllegalArgumentException("Token module has no KERI membership hook: " + reg.getModuleId());
        }
        kyc.setBoundTokenPolicyId(policyId);
        kycSessionRepository.save(kyc);
        log.info("Session {} bound to {} token {}", sessionId, reg.getModuleId(), policyId);
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

        dispatchMembershipHook(kyc, proof);

        return proof;
    }

    /** Look up the bound policy's module and forward the proof to the matching
     *  {@link TokenMembershipHook}. Module-specific side effects (e.g. MPF
     *  allowlist upsert) live entirely on the hook implementation, so {@code KeriService}
     *  remains module-agnostic. */
    private void dispatchMembershipHook(KycSessionEntity kyc, KycProofResponse proof) {
        String boundPolicyId = kyc.getBoundTokenPolicyId();
        if (boundPolicyId == null) return;
        if (programmableTokenRegistryRepository == null) return;

        var regOpt = programmableTokenRegistryRepository.findByPolicyId(boundPolicyId);
        if (regOpt.isEmpty()) return;
        String moduleId = regOpt.get().getModuleId();

        TokenMembershipHook hook = membershipHooks.get(moduleId);
        if (hook == null) return; // no hook registered for this module — silently skip

        try {
            hook.onProofGenerated(kyc, proof);
        } catch (Exception e) {
            log.warn("Membership hook for module '{}' threw for policy {} session {}: {}",
                    moduleId, boundPolicyId, kyc.getSessionId(), e.getMessage());
            if ("rwa-token".equals(moduleId)) {
                throw new IllegalStateException("CMTA membership staging failed: " + e.getMessage(), e);
            }
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
                var queryOp = client.keyStates().query(userAid, null);
                client.operations().wait(queryOp);
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
        // signify 0.1.2-d92f263: RegistryResult.op() is already a typed RegistryOperation,
        // so the JSON round-trip through a Map that the old untyped API needed is gone.
        client.operations().wait(result.op());
        return result.regser().getPre();
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

    static String requireAnchorAttachment(Credential credential, String credentialSaid) {
        List<String> attachments = credential == null ? null : credential.getAncatc();
        if (attachments == null || attachments.isEmpty()
                || attachments.getFirst() == null || attachments.getFirst().isBlank()) {
            throw new IllegalStateException("Issued credential anchor attachment retrieval failed for "
                    + credentialSaid + "; grant was not submitted");
        }
        return attachments.getFirst();
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
