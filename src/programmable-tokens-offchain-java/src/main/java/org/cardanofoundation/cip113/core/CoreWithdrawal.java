package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

/**
 * One withdraw-0 invocation, bound together with the credential it will be keyed by.
 *
 * <p>Exists because a withdrawal has to be described twice for two different purposes and
 * the two descriptions must agree: the builder needs a bech32 reward address, and
 * {@link CoreLayout} needs the raw credential in order to place it in the ledger's ordering.
 * Deriving one from the other at the point of use is where they drift — a reward address is
 * network-specific and encodes the credential kind in a header byte, so sorting addresses is
 * not sorting credentials.
 *
 * <p>Keeping them in one value means a caller cannot pass the layout one credential and the
 * builder a different account, which is the mistake that produces a {@code wdrl_idx} that is
 * self-consistent and points at the wrong entry.
 *
 * @param credential    what the ledger keys this withdrawal by, and what the layout sorts on
 * @param rewardAddress the bech32 reward address the builder withdraws from
 * @param redeemer      the redeemer for this withdrawal's script
 */
public record CoreWithdrawal(Credential credential, String rewardAddress, PlutusData redeemer) {
}
