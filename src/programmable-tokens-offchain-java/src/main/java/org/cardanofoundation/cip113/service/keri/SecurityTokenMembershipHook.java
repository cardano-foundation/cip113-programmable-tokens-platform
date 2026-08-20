package org.cardanofoundation.cip113.service.keri;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.cardanofoundation.cip113.service.SecurityTokenAllowlistService;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@link TokenMembershipHook} for the {@code security-token} substandard.
 *  Auto-upserts the verified user's stake credential into the per-policy MPF
 *  allowlist tree. */
@Component
@ConditionalOnProperty(name = "securityToken.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityTokenMembershipHook implements TokenMembershipHook {

    private final SecurityTokenAllowlistService allowlistService;

    @Override
    public String substandardId() {
        return "security-token";
    }

    @Override
    public void onProofGenerated(KycSessionEntity session, KycProofResponse proof) {
        if (session.getCardanoAddress() == null) return;

        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(session.getCardanoAddress());
        if (pkh == null) {
            log.warn("Cannot derive stake-cred PKH from address {} for security-token auto-upsert (base address required)",
                    session.getCardanoAddress());
            return;
        }
        Short credentialType = AddressUtil.extractStakeCredentialTypeFromAddress(session.getCardanoAddress());
        if (credentialType == null) {
            log.warn("Cannot determine the stake credential FORM (key vs script) of address {} for "
                     + "security-token auto-upsert. It is the first byte of the MPF leaf key, so "
                     + "enrolling with a guess would create a leaf the holder cannot prove against.",
                    session.getCardanoAddress());
            return;
        }
        try {
            allowlistService.putMember(session.getBoundTokenPolicyId(), pkh, credentialType,
                    proof.validUntilPosixMs(), session.getCardanoAddress(), session.getSessionId());
            log.info("Auto-upserted member into security-token MPF tree: policy={}, sessionId={}",
                    session.getBoundTokenPolicyId(), session.getSessionId());
        } catch (Exception e) {
            log.warn("Failed to auto-upsert security-token MPF member for policy {} session {}: {}",
                    session.getBoundTokenPolicyId(), session.getSessionId(), e.getMessage());
        }
    }
}
