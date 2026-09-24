package org.cardanofoundation.cip113.model.bootstrap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One CIP-113 0.0.1 deployment, in the same shape as SDK 0.9.x
 * {@code DeploymentParams}.
 *
 * <p>This development platform deliberately supports only the current contract surface.
 * Older deployment records are not adapted: a script hash is protocol identity, and
 * filling removed limbs with plausible values creates transactions for a different
 * protocol rather than compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProtocolBootstrapParams(
        Integer schemaVersion,
        String txHash,
        ProtocolParams protocolParams,
        ScriptParams programmableLogicBase,
        ScriptParams transfer,
        ScriptParams thirdParty,
        ScriptParams unfracking,
        ScriptParams programmableLogicGlobal,
        Long maxInlineDatumBytes,
        UpgradeMultisigParams upgradeMultisig,
        TxInput upgradeMultisigRefInput,
        CredentialParams upgradeAuthority,
        ScriptParams issuanceLogic,
        TxInput issuanceLogicRefInput,
        IssuanceParams issuance,
        RegistryParams registry,
        TxInput programmableBaseRefInput,
        TxInput programmableLogicGlobalRefInput,
        TxInput transferRefInput,
        TxInput thirdPartyRefInput,
        TxInput unfrackingRefInput) {

    public static final int CURRENT_SCHEMA_VERSION = 3;
}
