package org.cardanofoundation.cip113.model.bootstrap;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One recorded deployment of the CIP-113 core protocol.
 *
 * <p>{@code protocol-bootstraps-{network}.json} is an append-only list of these. Every
 * script hash in the protocol is derived from a deployment's parameters, and every
 * programmable-token address is derived from its {@code programmable_logic_base} hash, so a
 * deployment record is what makes a set of on-chain addresses reconstructible.
 *
 * <h2>Schema versions</h2>
 *
 * A core upgrade that moves {@code programmable_logic_base}'s hash starts a new protocol:
 * existing token addresses belong to the old one and cannot be migrated, because the
 * in-place upgrade mechanism swaps delegates, not PLB. So records are versioned rather than
 * migrated, and {@link #schemaVersion()} says which shape a record is:
 *
 * <ul>
 *   <li><strong>1</strong> — the coordinator era. One delegate,
 *       {@code programmableLogicGlobalPrams}, and a 5-field protocol-params datum.
 *       No longer buildable against: {@code programmable_logic_global} does not exist in the
 *       current blueprint.</li>
 *   <li><strong>2</strong> — the validator split. Three delegates
 *       ({@code transferParams}, {@code thirdPartyParams}, {@code unfrackingParams}), a
 *       7-field datum, and {@code maxInlineDatumBytes}.</li>
 * </ul>
 *
 * <p>An older record is REFUSED at startup rather than read leniently — see
 * {@code ProtocolBootstrapService}. The alternative, accepting it and filling in blanks,
 * produces transactions that are well-formed and rejected on chain, which is a much harder
 * thing to diagnose than a refusal at boot naming the record and the reason.
 *
 * @param maxInlineDatumBytes the bound written into the params datum at deployment. Recorded
 *                            here as well because it is the one datum field that is a policy
 *                            choice rather than a hash, so a deployment record that omitted
 *                            it could not be replayed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProtocolBootstrapParams(Integer schemaVersion,
                                      ProtocolParams protocolParams,
                                      CoordinationParams coordinationParams,
                                      DelegateParams transferParams,
                                      DelegateParams thirdPartyParams,
                                      DelegateParams unfrackingParams,
                                      ProgrammableLogicBaseParams programmableLogicBaseParams,
                                      UpgradeMultisigParams upgradeMultisigParams,
                                      IssuanceParams issuanceParams,
                                      DirectoryMintParams directoryMintParams,
                                      DirectorySpendParams directorySpendParams,
                                      Long maxInlineDatumBytes,
                                      TxInput programmableBaseRefInput,
                                      TxInput transferRefInput,
                                      TxInput thirdPartyRefInput,
                                      TxInput unfrackingRefInput,
                                      String txHash) {

    /** The schema this build writes and is able to build transactions against. */
    public static final int CURRENT_SCHEMA_VERSION = 2;
}
