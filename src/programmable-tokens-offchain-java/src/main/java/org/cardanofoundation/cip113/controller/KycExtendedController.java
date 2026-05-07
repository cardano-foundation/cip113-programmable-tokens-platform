package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycExtendedTokenRegistrationEntity;
import org.cardanofoundation.cip113.model.KycExtendedTokenSummary;
import org.cardanofoundation.cip113.repository.KycExtendedMemberLeafRepository;
import org.cardanofoundation.cip113.repository.KycExtendedTokenRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.scheduling.AdminSigningKeyProvider;
import org.cardanofoundation.cip113.service.MpfTreeService;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Public + admin endpoints for the kyc-extended substandard. */
@RestController
@RequestMapping("${apiPrefix}/kyc-extended")
@ConditionalOnProperty(name = "kycExtended.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KycExtendedController {

    private static final int LIST_CAP = 200;

    private final KycExtendedTokenRegistrationRepository registrationRepo;
    private final KycExtendedMemberLeafRepository memberLeafRepo;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;
    private final MpfTreeService mpfTreeService;
    private final AdminSigningKeyProvider adminSigningKeyProvider;

    /** Backend admin pkh + address — token registrations must use this pkh as
     *  {@code issuerAdminPkh} so the backend can autonomously sign root updates. */
    @GetMapping("/admin-pkh")
    public ResponseEntity<?> getAdminPkh() {
        if (!adminSigningKeyProvider.isAvailable()) {
            return ResponseEntity.status(503).body(Map.of("error", "admin signing key not configured"));
        }
        return ResponseEntity.ok(Map.of(
                "adminPkh", adminSigningKeyProvider.getAdminPkh(),
                "adminAddress", adminSigningKeyProvider.getAdminAddress()
        ));
    }

    @GetMapping("/tokens")
    public List<KycExtendedTokenSummary> listTokens() {
        return registrationRepo.findAllByOrderByLastRootUpdateAtDesc(PageRequest.of(0, LIST_CAP)).stream()
                .map(this::toSummary)
                .toList();
    }

    @GetMapping("/{policyId}/proofs/{memberPkh}")
    public ResponseEntity<?> getMemberProof(
            @PathVariable String policyId,
            @PathVariable String memberPkh) {
        try {
            byte[] pkhBytes = HexUtil.decodeHexString(memberPkh);
            long now = System.currentTimeMillis();

            // 404 not present, 410 expired, 425 not yet published — frontend hooks switch on these.
            var existing = memberLeafRepo.findByProgrammableTokenPolicyIdAndMemberPkh(policyId, memberPkh);
            if (existing.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "member not found in MPF tree"));
            }
            if (existing.get().getValidUntilMs() < now) {
                return ResponseEntity.status(410).body(Map.of("error", "member leaf has expired",
                        "validUntilMs", existing.get().getValidUntilMs()));
            }
            if (existing.get().getPublishedAt() == null) {
                return ResponseEntity.status(425).body(Map.of(
                        "error", "member added to local tree but on-chain publish is pending",
                        "addedAt", existing.get().getAddedAt().toEpochMilli(),
                        "memberPkh", memberPkh));
            }

            var view = mpfTreeService.inclusionProof(policyId, pkhBytes, now);
            if (view.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("error", "MPF proof unavailable"));
            }
            var v = view.get();
            return ResponseEntity.ok(Map.of(
                    "memberPkh", memberPkh,
                    "proofCborHex", HexUtil.encodeHexString(v.proofCbor()),
                    "validUntilMs", v.validUntilMs(),
                    "rootHashOnchain", HexUtil.encodeHexString(v.rootHashOnchain()),
                    "rootHashLocal", HexUtil.encodeHexString(v.rootHashLocal())
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid memberPkh hex"));
        } catch (Exception e) {
            log.error("getMemberProof failed for policy={} memberPkh={}", policyId, memberPkh, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin/test-only: upsert a member leaf manually. Regular users are added via the
     *  KERI auto-upsert hook in {@link org.cardanofoundation.cip113.service.KeriService}. */
    @PostMapping("/{policyId}/members")
    public ResponseEntity<?> upsertMember(
            @PathVariable String policyId,
            @RequestBody Map<String, Object> body) {
        if (!"kyc-extended".equals(programmableTokenRegistryRepository.findByPolicyId(policyId)
                .map(reg -> reg.getSubstandardId()).orElse(""))) {
            return ResponseEntity.badRequest().body(Map.of("error", "policyId is not a kyc-extended token"));
        }
        String boundAddress = (String) body.get("boundAddress");
        if (boundAddress == null || boundAddress.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "boundAddress is required"));
        }
        Object validUntilObj = body.get("validUntilMs");
        if (!(validUntilObj instanceof Number)) {
            return ResponseEntity.badRequest().body(Map.of("error", "validUntilMs is required (number)"));
        }
        long validUntilMs = ((Number) validUntilObj).longValue();
        String sessionId = (String) body.getOrDefault("kycSessionId", null);

        // Identity is the stake credential — see KycExtendedSubstandardHandler#buildTransferTransaction.
        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(boundAddress);
        if (pkh == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "could not derive stake credential hash from boundAddress (base address required)"));
        }
        try {
            mpfTreeService.putMember(policyId, pkh, validUntilMs, boundAddress, sessionId);
            byte[] localRoot = mpfTreeService.currentRoot(policyId);
            return ResponseEntity.ok(Map.of(
                    "memberPkh", HexUtil.encodeHexString(pkh),
                    "currentRootLocal", HexUtil.encodeHexString(localRoot)));
        } catch (Exception e) {
            log.error("upsertMember failed for policy={} address={}", policyId, boundAddress, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private KycExtendedTokenSummary toSummary(KycExtendedTokenRegistrationEntity reg) {
        var assetName = programmableTokenRegistryRepository.findByPolicyId(reg.getProgrammableTokenPolicyId())
                .map(p -> p.getAssetName())
                .orElse("");
        String displayName = decodeAssetNameSafely(assetName);
        long registeredAt = reg.getLastRootUpdateAt() != null
                ? reg.getLastRootUpdateAt().atOffset(ZoneOffset.UTC).toInstant().toEpochMilli()
                : 0L;
        return new KycExtendedTokenSummary(
                reg.getProgrammableTokenPolicyId(),
                assetName,
                displayName,
                null,
                registeredAt
        );
    }

    private String decodeAssetNameSafely(String hexAssetName) {
        if (hexAssetName == null || hexAssetName.isBlank()) return "<unnamed>";
        try {
            byte[] bytes = HexUtil.decodeHexString(hexAssetName);
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (s.isBlank()) return "<unnamed>";
            return s;
        } catch (Exception e) {
            return "<unnamed>";
        }
    }
}
