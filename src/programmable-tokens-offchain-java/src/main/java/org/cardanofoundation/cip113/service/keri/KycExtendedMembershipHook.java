package org.cardanofoundation.cip113.service.keri;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;
import org.cardanofoundation.cip113.service.MpfTreeService;
import org.cardanofoundation.cip113.util.AddressUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@link TokenMembershipHook} for the {@code kyc-extended} substandard.
 *  Auto-upserts the verified user's stake credential into the per-policy MPF
 *  allowlist tree. The hook is gated by {@code kycExtended.enabled} so removing
 *  the substandard entirely (config flag flip + deleting these files) leaves
 *  {@code KeriService} untouched. */
@Component
@ConditionalOnProperty(name = "kycExtended.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class KycExtendedMembershipHook implements TokenMembershipHook {

    private final MpfTreeService mpfTreeService;

    @Override
    public String substandardId() {
        return "kyc-extended";
    }

    @Override
    public void onProofGenerated(KycSessionEntity session, KycProofResponse proof) {
        if (session.getCardanoAddress() == null) return;

        // Identity in the kyc-extended MPF tree is the stake credential — the on-chain
        // transfer validator extracts witnesses from prog-token outputs' stake credential.
        byte[] pkh = AddressUtil.extractStakeCredHashFromAddress(session.getCardanoAddress());
        if (pkh == null) {
            log.warn("Cannot derive stake-cred PKH from address {} for kyc-extended auto-upsert (base address required)",
                    session.getCardanoAddress());
            return;
        }
        try {
            mpfTreeService.putMember(session.getBoundTokenPolicyId(), pkh, proof.validUntilPosixMs(),
                    session.getCardanoAddress(), session.getSessionId());
            log.info("Auto-upserted member into kyc-extended MPF tree: policy={}, sessionId={}",
                    session.getBoundTokenPolicyId(), session.getSessionId());
        } catch (Exception e) {
            log.warn("Failed to auto-upsert kyc-extended MPF member for policy {} session {}: {}",
                    session.getBoundTokenPolicyId(), session.getSessionId(), e.getMessage());
        }
    }
}
