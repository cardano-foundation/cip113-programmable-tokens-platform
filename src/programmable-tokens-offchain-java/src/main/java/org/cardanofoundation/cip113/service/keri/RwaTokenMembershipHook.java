package org.cardanofoundation.cip113.service.keri;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.cardanofoundation.cip113.service.RwaTokenAllowlistService;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@link TokenMembershipHook} for the {@code rwa-token} module.
 *  Auto-upserts the verified user's stake credential into the per-policy MPF
 *  allowlist tree. */
@Component
@ConditionalOnProperty(name = "rwaToken.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class RwaTokenMembershipHook implements TokenMembershipHook {

    private final RwaTokenAllowlistService allowlistService;

    @Override
    public String moduleId() {
        return "rwa-token";
    }

    @Override
    public void onProofGenerated(KycSessionEntity session, KycProofResponse proof) {
        if (session.getCardanoAddress() == null)
            throw new IllegalStateException("No bound Cardano address for CMTA membership staging");

        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(session.getCardanoAddress());
        if (pkh == null) {
            log.warn("Cannot derive stake-cred PKH from address {} for rwa-token auto-upsert (base address required)",
                    session.getCardanoAddress());
            throw new IllegalStateException("A base address with a stake credential is required for CMTA membership");
        }
        Short credentialType = AddressUtil.extractStakeCredentialTypeFromAddress(session.getCardanoAddress());
        if (credentialType == null) {
            log.warn("Cannot determine the stake credential FORM (key vs script) of address {} for "
                     + "rwa-token auto-upsert. It is the first byte of the MPF leaf key, so "
                     + "enrolling with a guess would create a leaf the holder cannot prove against.",
                    session.getCardanoAddress());
            throw new IllegalStateException("Could not determine the stake credential type for CMTA membership");
        }
        try {
            allowlistService.putMember(session.getBoundTokenPolicyId(), pkh, credentialType,
                    proof.validUntilPosixMs(), session.getCardanoAddress(), session.getSessionId());
            log.info("Auto-upserted member into rwa-token MPF tree: policy={}, sessionId={}",
                    session.getBoundTokenPolicyId(), session.getSessionId());
        } catch (Exception e) {
            throw new IllegalStateException("Verified KYC could not be staged for CMTA membership", e);
        }
    }
}
