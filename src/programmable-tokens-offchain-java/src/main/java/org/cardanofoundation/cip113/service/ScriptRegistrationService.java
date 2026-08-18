package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.bloxbean.cardano.yaci.core.model.certs.CertificateType;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
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

    /**
     * Check if a stake address is registered on-chain.
     *
     * @param stakeAddress The stake address to check (e.g., stake_test1...)
     * @return true if registered (active), false otherwise
     */
    public boolean isStakeAddressRegistered(String stakeAddress) {
        // Read the indexed certificate history, not Blockfrost's account endpoint.
        //
        // This used to ask `getAccountInformation(stakeAddress).getActive()`. That is the wrong
        // question for a SCRIPT stake credential: these accounts are registered solely so a
        // validator can be invoked by withdrawing 0, they never delegate to a pool, and they never
        // accrue rewards — so the account endpoint answers `active: false`, or 404s outright on
        // backends that only materialise accounts once they delegate. Every failure mode landed on
        // `return false`, i.e. "not registered".
        //
        // That is the dangerous direction to be wrong in. The SDK's freeze-and-seize path asks this
        // question and emits a registration certificate whenever the answer is false, so a
        // permanently-false answer meant every retry re-registered credentials that already
        // existed and was rejected with StakeKeyAlreadyRegisteredDELEG.
        //
        // The indexer already records every certificate. Take the most recent one for the address
        // (the query orders by slot then cert index) and treat the address as registered only if
        // that latest certificate is a registration — so a later deregistration correctly flips the
        // answer back, which the `active` flag could not express either.
        try {
            boolean isRegistered = stakeRegistrationRepository.findRegistrationsByStakeAddress(stakeAddress)
                    .map(r -> r.getType().equals(CertificateType.STAKE_REGISTRATION))
                    .orElse(false);
            log.info("Stake address {} is registered: {}", stakeAddress, isRegistered);
            return isRegistered;
        } catch (Exception e) {
            log.error("Error checking stake address registration for {}: {}", stakeAddress, e.getMessage());
            return false;
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
