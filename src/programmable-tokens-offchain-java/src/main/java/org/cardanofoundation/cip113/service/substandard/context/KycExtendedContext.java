package org.cardanofoundation.cip113.service.substandard.context;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Context for KYC-Extended substandard instances.
 *
 * Mirrors {@link KycContext} but adds MPF root tracking — the local trie root
 * is the source of truth for proof issuance, while the on-chain root is what
 * the validator checks against.
 */
@Getter
@Builder
@ToString
public class KycExtendedContext implements SubstandardContext {

    private static final String SUBSTANDARD_ID = "kyc-extended";

    private final String issuerAdminPkh;

    private final String globalStatePolicyId;

    private final TransactionInput globalStateInitTxInput;

    /** Current on-chain root hash (64 hex chars). May be empty/zero before first sync. */
    private final String memberRootHashOnchain;

    /** Current local trie root hash (64 hex chars). Drives proof issuance. */
    private final String memberRootHashLocal;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    public static KycExtendedContext emptyContext() {
        return KycExtendedContext.builder().build();
    }
}
