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

    public AdminSigningKeyProvider(
            @Value("${keri.signing-mnemonic:}") String adminMnemonic,
            AppConfig.Network network) {
        if (adminMnemonic == null || adminMnemonic.isBlank()) {
            this.adminAccount = null;
            log.warn("keri.signing-mnemonic is not configured — kyc-extended root sync publishing is DISABLED");
        } else {
            this.adminAccount = Account.createFromMnemonic(network.getCardanoNetwork(), adminMnemonic);
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
