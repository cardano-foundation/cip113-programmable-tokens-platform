package org.cardanofoundation.cip113.service.keri;

import org.cardanofoundation.cip113.entity.KycSessionEntity;
import org.cardanofoundation.cip113.model.keri.KycProofResponse;

/** Per-substandard hook fired after a KYC attestation is generated for a session
 *  bound to a token policy. Lets each substandard plug its own onboarding side
 *  effect (e.g. inserting into its allowlist) without {@code KeriService} having
 *  to know about any substandard-specific service or repo. */
public interface TokenMembershipHook {

    /** Substandard id this hook handles (e.g. {@code "kyc-extended"},
     *  {@code "security-token"}). Must match the value stored on the bound
     *  programmable-token-registry row. */
    String substandardId();

    /** Called by {@code KeriService} immediately after {@code generateKycProof}
     *  has persisted the proof on the session. The hook is responsible for any
     *  per-substandard side effects (allowlist upsert, audit log, etc.).
     *  Implementations MUST be exception-safe — throwing here will be logged
     *  but will not roll back the proof generation. */
    void onProofGenerated(KycSessionEntity session, KycProofResponse proof);
}
