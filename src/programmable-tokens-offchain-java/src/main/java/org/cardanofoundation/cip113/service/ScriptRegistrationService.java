package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.entity.KnownScriptRegistrationEntity;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service for checking and registering script stake addresses.
 * Script stake addresses must be registered on-chain before they can be used
 * with the "withdraw 0" trick for validator invocation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScriptRegistrationService {

    private final BFBackendService bfBackendService;
    private final QuickTxBuilder quickTxBuilder;
    private final AccountService accountService;
    private final CustomStakeRegistrationRepository stakeRegistrationRepository;
    private final KnownScriptRegistrationRepository knownScriptRegistrationRepository;

    /**
     * Check if a stake address is registered on-chain.
     *
     * @param stakeAddress The stake address to check (e.g., stake_test1...)
     * @return true if registered (active), false otherwise
     */
    public boolean isStakeAddressRegistered(String stakeAddress) {
        // Ask the LEDGER first, and only fall back to our own index.
        //
        // Neither source is sufficient alone, and each fails in the dangerous direction on its own:
        //
        //   - The index can simply not contain the certificate. It is populated from
        //     `sync-start-slot`, which is deliberately not genesis on every profile (mainnet starts
        //     at the block that minted the contract ref input), and it restarts empty whenever a
        //     devnet is reset or the database recreated. The dummy substandard's issue/transfer
        //     validators are protocol-GLOBAL and unparameterized, so they are registered exactly
        //     once per network — most likely long before whatever slot this deployment syncs from.
        //     Absence from the index therefore does not mean absence from the chain.
        //
        //   - The account endpoint is unreachable or errors transiently, and every such failure
        //     used to be flattened to `false`.
        //
        // "false" is the expensive answer to get wrong: every caller registers the credential when
        // it hears it, and re-registering an existing one is rejected outright with
        // StakeKeyAlreadyRegisteredDELEG ("Trying to re-register some already known credentials").
        // A wrong "true" merely surfaces as a withdrawal failure on a credential that was never
        // there, which the pre-registration step exists to prevent in the first place.
        //
        // Blockfrost's `active` IS the account's registration state, not its delegation state, and
        // a never-registered account 404s rather than reporting false — so a successful response is
        // authoritative in both directions and is taken as final.
        // Anything we have been told, first. This is the only source that can answer for a
        // credential registered before the indexed window and on a backend without an account
        // endpoint — which together is the normal situation for the protocol-global validators.
        try {
            if (knownScriptRegistrationRepository.existsById(stakeAddress)) {
                log.info("Stake address {} registered=true (known registrations)", stakeAddress);
                return true;
            }
        } catch (Exception e) {
            log.warn("Known-registration lookup failed for {}: {}", stakeAddress, e.getMessage());
        }

        try {
            var accountInfo = bfBackendService.getAccountService().getAccountInformation(stakeAddress);
            if (accountInfo.isSuccessful() && accountInfo.getValue() != null
                    && accountInfo.getValue().getActive() != null) {
                boolean active = accountInfo.getValue().getActive();
                log.info("Stake address {} registered={} (ledger)", stakeAddress, active);
                return active;
            }
            log.debug("Ledger could not answer for {} ({}), falling back to the indexed certificates",
                    stakeAddress, accountInfo.isSuccessful() ? "no account" : accountInfo.getResponse());
        } catch (Exception e) {
            log.warn("Ledger lookup failed for {} ({}), falling back to the indexed certificates",
                    stakeAddress, e.getMessage());
        }

        // Fallback: the most recent certificate for this address wins, so a later deregistration
        // correctly flips the answer back — something the account flag alone could not express.
        try {
            boolean isRegistered = stakeRegistrationRepository.findRegistrationsByStakeAddress(stakeAddress)
                    .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            log.info("Stake address {} registered={} (indexed certificates)", stakeAddress, isRegistered);
            return isRegistered;
        } catch (Exception e) {
            log.error("Error checking stake address registration for {}: {}", stakeAddress, e.getMessage());
            return false;
        }
    }

    /** Record that {@code stakeAddress} is registered, so no later build tries to register it
     *  again. Idempotent.
     *
     *  <p>{@code source} says how we learned it — {@code BUILT} when this platform built the
     *  registration, {@code LEDGER_REJECT} when a submit came back naming the credential as
     *  already known. The second case is what makes a 3145 self-correcting: the error carries the
     *  credential, so the next attempt can skip it instead of failing identically forever. */
    public void noteRegistered(String stakeAddress, String credential, String source) {
        try {
            knownScriptRegistrationRepository.save(KnownScriptRegistrationEntity.builder()
                    .stakeAddress(stakeAddress)
                    .credential(credential)
                    .source(source)
                    .notedAt(java.time.Instant.now())
                    .build());
            log.info("Noted stake address {} as registered (source={})", stakeAddress, source);
        } catch (Exception e) {
            log.error("Could not record {} as registered: {}", stakeAddress, e.getMessage());
        }
    }

    /**
     * Build a transaction to register one or more stake addresses.
     *
     * @param stakeAddresses List of stake addresses to register
     * @param feePayerAddress The address that will pay for the transaction
     * @return Transaction context with unsigned CBOR tx
     */
    public TransactionContext<Void> buildRegisterStakeAddressTransaction(
            List<String> stakeAddresses,
            String feePayerAddress) {

        try {
            if (stakeAddresses == null || stakeAddresses.isEmpty()) {
                return TransactionContext.error("No stake addresses provided");
            }

            log.info("Building stake address registration tx for {} addresses, fee payer: {}",
                    stakeAddresses.size(), feePayerAddress);

            // Get UTxOs for fee payer
            var feePayerUtxos = accountService.findAdaOnlyUtxo(feePayerAddress, 10_000_000L);
            if (feePayerUtxos.isEmpty()) {
                return TransactionContext.error("No UTxOs found for fee payer address");
            }

            // Build the registration transaction
            var tx = new Tx()
                    .from(feePayerAddress)
                    .withChangeAddress(feePayerAddress);

            // Add each stake address to register
            for (String stakeAddress : stakeAddresses) {
                log.debug("Adding stake address to register: {}", stakeAddress);
                tx.registerStakeAddress(stakeAddress);
            }

            // Build the unsigned transaction
            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(feePayerAddress)
                    .build();

            var unsignedCborHex = transaction.serializeToHex();
            log.info("Built stake registration tx: {}", unsignedCborHex);

            return TransactionContext.ok(unsignedCborHex);

        } catch (Exception e) {
            log.error("Error building stake address registration tx: {}", e.getMessage(), e);
            return TransactionContext.error("Failed to build registration transaction: " + e.getMessage());
        }
    }
}
