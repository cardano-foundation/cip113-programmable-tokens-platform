package org.cardanofoundation.cip113.service.substandard.context;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Context for KYC substandard instances.
 *
 * Each KYC deployment has its own:
 * - Programmable token policy ID
 * - Global state policy ID (= global_state_cs in registry)
 * - Bootstrap parameters for rebuilding global state scripts
 * - Admin credentials for authorization
 *
 * This context must be provided when creating a KycSubstandardHandler
 * to specify which token instance to operate on.
 */
@Getter
@Builder
@ToString
public class KycContext implements SubstandardContext {

    private static final String SUBSTANDARD_ID = "kyc";

    /**
     * Issuer PKH (admin who can mint/burn tokens)
     */
    private final String issuerAdminPkh;

    /**
     * Policy ID of the global state NFT (= one-shot minting policy ID).
     * This is the same value stored in global_state_cs in the registry node.
     */
    private final String globalStatePolicyId;

    /**
     * The UTxO ref used to parameterize the global state one-shot minting policy.
     * Needed to rebuild global state scripts for add/remove operations.
     */
    private final TransactionInput globalStateInitTxInput;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    public static KycContext emptyContext() {
        return KycContext.builder().build();
    }
}
