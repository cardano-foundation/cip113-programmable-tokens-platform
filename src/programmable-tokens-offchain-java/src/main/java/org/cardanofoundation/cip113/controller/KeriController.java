package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.AttestAnchorRequest;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.CredentialChainPublishRequest;
import org.cardanofoundation.cip113.model.keri.CredentialResponse;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.cardanofoundation.cip113.model.keri.OobiResponse;
import org.cardanofoundation.cip113.model.keri.SchemaListResponse;
import org.cardanofoundation.cip113.model.keri.SessionResponse;
import org.cardanofoundation.cip113.service.KeriService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Thin HTTP adapter for {@link KeriService}. Validates request shape, delegates business
 * logic, and maps service exceptions onto status codes.
 */
@RestController
@RequestMapping("${apiPrefix}/keri")
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class KeriController {

    private final KeriService keriService;

    // ── Signing entity key ─────────────────────────────────────────────────────

    @GetMapping("/signing-entity-vkey")
    public ResponseEntity<?> getSigningEntityVkey() {
        try {
            return ResponseEntity.ok(Map.of("vkeyHex", keriService.getSigningEntityVkey()));
        } catch (Exception e) {
            log.error("Failed to derive signing entity vkey", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── OOBI ──────────────────────────────────────────────────────────────────

    @GetMapping("/oobi")
    public ResponseEntity<OobiResponse> getOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) throws Exception {
        Optional<String> oobi = keriService.getOobi();
        return oobi.map(s -> ResponseEntity.ok(new OobiResponse(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/oobi/resolve")
    public ResponseEntity<Boolean> resolveOobi(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String oobi) throws Exception {
        boolean resolved = keriService.resolveOobi(sessionId, oobi);
        return resolved
                ? ResponseEntity.ok(true)
                : ResponseEntity.internalServerError().body(false);
    }

    // ── Schema discovery ──────────────────────────────────────────────────────

    @GetMapping("/schemas")
    public ResponseEntity<SchemaListResponse> getSchemas() {
        return ResponseEntity.ok(new SchemaListResponse(keriService.getSchemaList()));
    }

    @GetMapping("/available-roles")
    public ResponseEntity<?> getAvailableRoles() {
        return ResponseEntity.ok(Map.of("availableRoles", keriService.getAvailableRoles()));
    }

    // ── IPEX credential exchange ──────────────────────────────────────────────

    @GetMapping("/credential/present")
    public ResponseEntity<?> presentCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(value = "role", defaultValue = "USER") String roleName) throws Exception {
        try {
            CredentialResponse response = keriService.presentCredential(sessionId, roleName);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(409).body(Map.of("error", "Presentation cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                return ResponseEntity.status(408)
                        .body(Map.of("error", "No credential was received — the wallet did not respond in time."));
            }
            throw e;
        }
    }

    @PostMapping("/credential/cancel")
    public ResponseEntity<?> cancelPresentation(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-Session-Id header required"));
        }
        return ResponseEntity.ok(Map.of("cancelled", keriService.cancelPresentation(sessionId)));
    }

    @PostMapping("/credential/issue")
    public ResponseEntity<?> issueCredential(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "X-Session-Id header required"));
        }
        String firstName = body.get("firstName");
        String lastName = body.get("lastName");
        String email = body.get("email");
        if (firstName == null || lastName == null || email == null
                || firstName.isBlank() || lastName.isBlank() || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "firstName, lastName and email are required"));
        }

        try {
            CredentialResponse response = keriService.issueCredential(sessionId, firstName, lastName, email);
            return ResponseEntity.ok(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(409).body(Map.of("error", "Issuance cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                return ResponseEntity.status(408).body(
                        Map.of("error", "Wallet did not admit the credential in time."));
            }
            log.error("credential/issue failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("credential/issue failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Session state ─────────────────────────────────────────────────────────

    @GetMapping("/session")
    public ResponseEntity<SessionResponse> getSession(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        return ResponseEntity.ok(keriService.getSession(sessionId));
    }

    @PostMapping("/session/cardano-address")
    public ResponseEntity<?> storeCardanoAddress(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody Map<String, String> body) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        String cardanoAddress = body.get("cardanoAddress");
        if (cardanoAddress == null || cardanoAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "cardanoAddress is required"));
        }
        try {
            keriService.storeCardanoAddress(sessionId, cardanoAddress);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
    }

    // ── KYC proof generation ──────────────────────────────────────────────────

    @PostMapping("/kyc-proof/generate")
    public ResponseEntity<?> generateKycProof(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        try {
            KycProofResponse proof = keriService.generateKycProof(sessionId);
            return ResponseEntity.ok(proof);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("kyc-proof/generate failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── CIP-170 credential chain publishing & attestation ─────────────────────

    @PostMapping("/credential-chain/publish")
    public ResponseEntity<?> publishCredentialChain(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody CredentialChainPublishRequest request) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        try {
            return ResponseEntity.ok(keriService.publishCredentialChain(sessionId, request));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("credential-chain/publish failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/attest/request")
    public ResponseEntity<?> requestAttestation(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody AttestAnchorRequest request) {
        if (sessionId == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        }
        try {
            Cip170AttestationData attestation = keriService.requestAttestation(sessionId, request);
            return ResponseEntity.ok(attestation);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(401).body(Map.of("error", "Unknown session"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(409).body(Map.of("error", "Attestation request cancelled."));
        } catch (RuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Timed out")) {
                return ResponseEntity.status(408).body(
                        Map.of("error", "Wallet did not respond to anchor request in time."));
            }
            log.error("attest/request failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("attest/request failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
