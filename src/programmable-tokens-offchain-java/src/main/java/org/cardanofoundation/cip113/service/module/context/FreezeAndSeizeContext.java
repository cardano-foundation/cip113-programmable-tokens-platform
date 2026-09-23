package org.cardanofoundation.cip113.service.module.context;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Context for freeze-and-seize module instances.
 *
 * Each freeze-and-seize deployment (e.g., each stablecoin) has its own:
 * - Programmable token policy ID
 * - Blacklist policy ID and bootstrap parameters
 * - Admin credentials for authorization
 *
 * This context must be provided when creating a FreezeAndSeizeHandler
 * to specify which stablecoin instance to operate on.
 */
@Getter
@Builder
@ToString
public class FreezeAndSeizeContext implements ModuleContext {

    private static final String MODULE_ID = "freeze-and-seize";

    /**
     * Issuer PKH (payment or staking), can be script
     */
    private final String issuerAdminPkh;

    /**
     * Hex-encoded asset name of the programmable token.
     * Used to differentiate the issuer admin script per token.
     */
    private final String assetName;

    /**
     * Blacklist Manager Payment PKH
     */
    private final String blacklistManagerPkh;

    /**
     * The utxo ref input used to initialize blacklist
     */
    private final TransactionInput blacklistInitTxInput;

    /**
     * The policy id of the blacklist node nft
     */
    private final String blacklistNodePolicyId;

    @Override
    public String getModuleId() {
        return MODULE_ID;
    }

    public static FreezeAndSeizeContext emptyContext() {
        return FreezeAndSeizeContext.builder().build();
    }

}
