package org.cardanofoundation.cip113.scheduling;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.config.AppConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the issuer admin signing key used to rotate the kyc-extended member root hash
 * on-chain. Reuses {@code keri.signing-mnemonic}.
 *
 * <p><b>SECURITY:</b> this key has full TEL authority (can also modify trusted entities,
 * pause transfers, etc.). Future hardening would carry a separate {@code root_hash_updater_pkh}
 * in the global-state datum so this key can be scoped to root updates only.
 */
@Component
@Slf4j
public class AdminSigningKeyProvider {

    private final Account adminAccount;

    /**
     * ⛔ REFUSES TO START when a feature that needs this key is enabled and the key is absent.
     *
     * <p>Until 2026-09-25 {@code application.yaml} carried a committed 24-word default, so this
     * constructor could never see a blank value in practice and the {@code log.warn} below was
     * effectively dead. Removing that default makes blank reachable, and the old behaviour —
     * warn once, set null, disable publishing — is the wrong shape for it: {@link MpfRootSyncJob}
     * then runs on schedule forever, detects a root difference, and declines to publish with one
     * WARN per token per cycle. <b>The protocol is silently not being maintained while the service
     * reports healthy.</b>
     *
     * <p>So the absence is fatal exactly when it matters, and names both ways out. A deployment
     * that genuinely has no admin key turns the consuming features off explicitly; it does not get
     * to leave them on and non-functional.
     */
    public AdminSigningKeyProvider(
            @Value("${keri.signing-mnemonic:}") String adminMnemonic,
            @Value("${kycExtended.enabled:true}") boolean kycExtendedEnabled,
            @Value("${rwaToken.enabled:true}") boolean rwaTokenEnabled,
            AppConfig.Network network) {
        if (adminMnemonic == null || adminMnemonic.isBlank()) {
            if (kycExtendedEnabled || rwaTokenEnabled) {
                throw new IllegalStateException(
                        "No admin signing key is configured, but a feature that needs one is enabled "
                        + "(kycExtended.enabled=" + kycExtendedEnabled
                        + ", rwaToken.enabled=" + rwaTokenEnabled + ").\n"
                        + "\n"
                        + "  This key SIGNS AND SUBMITS on-chain root-hash updates. Without it the\n"
                        + "  root-sync job runs, sees the root drift, and declines to publish — the\n"
                        + "  protocol stops being maintained while the service still reports healthy.\n"
                        + "  That is why this refuses to start rather than warning.\n"
                        + "\n"
                        + "  Either supply the key as KERI_SIGNING_MNEMONIC (24 words, from a Secret —\n"
                        + "  never a values file), or turn the consumers off explicitly with\n"
                        + "  KYC_EXTENDED_ENABLED=false and SECURITY_TOKEN_ENABLED=false.\n"
                        + "\n"
                        + "  NOTE: the default committed here before 2026-09-25 is PUBLIC and must be\n"
                        + "  treated as compromised. Supply a ROTATED key, not that one.");
            }
            this.adminAccount = null;
            log.warn("No admin signing key configured, and no feature needs one "
                    + "(kycExtended.enabled=false, rwaToken.enabled=false) — signing is unavailable.");
        } else {
            this.adminAccount = Account.createFromMnemonic(network.getCardanoNetwork(), adminMnemonic);
            log.info("Admin signing key loaded; paymentKeyHash={}", getAdminPkh());
        }
    }

    public boolean isAvailable() {
        return adminAccount != null;
    }

    public String getAdminAddress() {
        if (adminAccount == null) throw new IllegalStateException("Admin signing key not configured");
        return adminAccount.baseAddress();
    }

    public String getAdminPkh() {
        if (adminAccount == null) throw new IllegalStateException("Admin signing key not configured");
        return HexUtil.encodeHexString(
                new Address(adminAccount.baseAddress())
                        .getPaymentCredentialHash()
                        .orElseThrow(() -> new IllegalStateException("Cannot derive PKH from admin base address")));
    }

    public Transaction sign(Transaction unsignedTx) {
        if (adminAccount == null) throw new IllegalStateException("Admin signing key not configured");
        return adminAccount.sign(unsignedTx);
    }
}
