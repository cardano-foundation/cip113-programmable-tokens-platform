package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.service.ScriptRegistrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for script stake address registration operations.
 *
 * <p>Script stake addresses must be registered on-chain before they can be used
 * with the "withdraw 0" trick for validator invocation.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /check - Check if a stake address is registered</li>
 *   <li>POST /register - Build a transaction to register stake addresses</li>
 * </ul>
 */
@RestController
@RequestMapping("${apiPrefix}/script-registration")
@RequiredArgsConstructor
@Slf4j
public class ScriptRegistrationController {

    private final ScriptRegistrationService scriptRegistrationService;
    private final AppConfig.Network network;

    /**
     * Response for stake address registration check.
     */
    public record CheckRegistrationResponse(String stakeAddress, boolean isRegistered) {}

    /**
     * Request to register stake addresses.
     */
    public record RegisterStakeAddressRequest(
            List<String> stakeAddresses,
            String feePayerAddress
    ) {}

    /**
     * Response for stake address registration transaction.
     */
    public record RegisterStakeAddressResponse(String unsignedTx) {}

    /** Record that a script stake credential is ALREADY registered, so no later build tries to
     *  register it again.
     *
     *  <p>This exists because the question has no reliable oracle here. The account endpoint is
     *  absent on some backends (a devkit devnet 404s even on {@code /blocks/latest}), and our
     *  indexed certificates only cover history back to {@code sync-start-slot} — measured on one
     *  devnet, back to slot 119969749 against a tip of 120386215. The protocol-global validators
     *  are registered ONCE per network, on the first registration anyone performed, which is
     *  normally older than that window. So the platform can be permanently unable to see a
     *  credential that plainly exists, and every attempt dies with:
     *
     *  <pre>3145 — Trying to re-register some already known credentials.</pre>
     *
     *  <p>That error names the credential, which is what makes this recoverable: post it here and
     *  retry. Accepts either {@code stakeAddress} (a reward address) or {@code knownCredential}
     *  (the script hash exactly as the ledger reports it).
     *  Body: {@code { stakeAddress? , knownCredential? }} */
    @PostMapping("/known")
    public ResponseEntity<?> noteKnownRegistration(@RequestBody Map<String, Object> body) {
        try {
            String stakeAddress = (String) body.get("stakeAddress");
            String credential = (String) body.get("knownCredential");

            if ((stakeAddress == null || stakeAddress.isBlank())
                    && (credential == null || credential.isBlank())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "one of stakeAddress or knownCredential is required"));
            }

            if (stakeAddress == null || stakeAddress.isBlank()) {
                // The ledger reports a bare script hash; turn it into the reward address every
                // caller actually looks up. Script credential, hence getRewardAddress over a
                // script Credential rather than a key one — the two produce different addresses
                // for the same hash, and only one of them is ever queried.
                stakeAddress = AddressProvider.getRewardAddress(
                        Credential.fromScript(HexUtil.decodeHexString(credential)),
                        network.getCardanoNetwork()).getAddress();
            }

            scriptRegistrationService.noteRegistered(stakeAddress, credential, "LEDGER_REJECT");
            return ResponseEntity.ok(Map.of("stakeAddress", stakeAddress, "registered", true));
        } catch (Exception e) {
            log.error("Error recording known registration", e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Check if a stake address is registered on-chain.
     *
     * @param stakeAddress The stake address to check (e.g., stake_test1...)
     * @return Registration status
     */
    @GetMapping("/check")
    public ResponseEntity<CheckRegistrationResponse> checkRegistration(
            @RequestParam String stakeAddress) {

        log.info("GET /script-registration/check - stakeAddress: {}", stakeAddress);

        try {
            boolean isRegistered = scriptRegistrationService.isStakeAddressRegistered(stakeAddress);
            return ResponseEntity.ok(new CheckRegistrationResponse(stakeAddress, isRegistered));

        } catch (Exception e) {
            log.error("Error checking stake address registration", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Build a transaction to register one or more stake addresses.
     * The returned transaction must be signed and submitted by the caller.
     *
     * @param request The registration request containing stake addresses and fee payer
     * @return Unsigned CBOR transaction hex
     */
    @PostMapping("/register")
    public ResponseEntity<?> buildRegisterTransaction(
            @RequestBody RegisterStakeAddressRequest request) {

        log.info("POST /script-registration/register - addresses: {}, feePayer: {}",
                request.stakeAddresses(), request.feePayerAddress());

        try {
            if (request.stakeAddresses() == null || request.stakeAddresses().isEmpty()) {
                return ResponseEntity.badRequest().body("No stake addresses provided");
            }

            if (request.feePayerAddress() == null || request.feePayerAddress().isBlank()) {
                return ResponseEntity.badRequest().body("Fee payer address is required");
            }

            var txContext = scriptRegistrationService.buildRegisterStakeAddressTransaction(
                    request.stakeAddresses(),
                    request.feePayerAddress());

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(new RegisterStakeAddressResponse(txContext.unsignedCborTx()));
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }

        } catch (Exception e) {
            log.error("Error building stake address registration transaction", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }
}
