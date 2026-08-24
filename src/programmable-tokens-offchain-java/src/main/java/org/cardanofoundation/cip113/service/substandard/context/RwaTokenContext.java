package org.cardanofoundation.cip113.service.substandard.context;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/** Runtime context for a rwa-token handler instance. Carries the policy ids
 *  the ported BaFin validators are parameterised on, plus the MPF root tracking
 *  fields used by the autonomous publisher. */
@Getter
@Builder
@ToString
public class RwaTokenContext implements SubstandardContext {

    private static final String SUBSTANDARD_ID = "rwa-token";

    private final String issuerAdminPkh;

    /** Policy id of the GlobalState NFT. */
    private final String globalStatePolicyId;

    /** Policy id of the denylist linked-list root NFT. */
    private final String denylistPolicyId;

    /** Policy id of the power-users linked-list root NFT. */
    private final String powerUsersPolicyId;

    /** Asset name of the RWA token, hex-encoded. Baked into several validators
     *  (transfer-logic, third-party-transfer-logic, global-state-spend). */
    private final String securityAssetNameHex;

    /** OutputReference consumed by the GlobalState mint validator to enforce one-shot
     *  initialisation. Captured at the genesis tx and stored for later script rebuilds. */
    private final TransactionInput globalStateInitTxInput;

    /** Whether the on-chain global-state datum currently has {@code requires_receiver_kyc=true}. */
    private final boolean requiresReceiverKyc;

    /** Current on-chain root hash (64 hex chars). May be empty/zero before first sync. */
    private final String memberRootHashOnchain;

    /** Current local trie root hash (64 hex chars). Drives proof issuance. */
    private final String memberRootHashLocal;

    @Override
    public String getSubstandardId() {
        return SUBSTANDARD_ID;
    }

    public static RwaTokenContext emptyContext() {
        return RwaTokenContext.builder().build();
    }
}
