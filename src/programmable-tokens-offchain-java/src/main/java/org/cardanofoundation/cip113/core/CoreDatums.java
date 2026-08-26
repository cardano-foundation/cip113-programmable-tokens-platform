package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;

/**
 * Datums the core contracts read or expect on programmable-token outputs.
 *
 * <p>The two substantial core datums have their own homes: the protocol-params NFT datum
 * is {@link CoreProtocolParamsDatum}, and the registry-node datum is
 * {@code org.cardanofoundation.cip113.model.onchain.RegistryNode} — a typed model with
 * round-trip tests that predates this package. Both are part of the core surface, so both
 * are on the checklist when the core is upgraded, even though only one lives here.
 *
 * <p>What lives here is the one datum with no structure to model and every opportunity to
 * be confused with something else.
 */
public final class CoreDatums {

    private CoreDatums() {}

    /**
     * The inline datum an ordinary programmable-token output carries: a bare constructor 0
     * with no fields.
     *
     * <p>Until the coordinator was dissolved, the {@code programmable_logic_base} spend
     * redeemer encoded identically to this — both were written inline as
     * {@code ConstrPlutusData.of(0)} throughout the handlers, the same six characters standing
     * for a spend redeemer in one line and an output datum in the next. They were never the
     * same thing: one is consumed by PLB, the other is data attached to an output. They are no
     * longer even equal, because the spend redeemer is now
     * {@link CoreRedeemers#spendViaTransfer} and its siblings, carrying two indices, while this
     * stays empty. Naming them apart is what stopped the migration between those two shapes
     * from substituting one for the other.
     *
     * <p>Nothing in the core reads this datum; it exists because a PLB output needs
     * <em>some</em> datum, and upstream's guidance to substandards is explicit that the
     * default should be as small as possible — a seizure must reproduce a programmable
     * output's datum byte for byte, so every byte here is a byte of someone else's future
     * seizure transaction. The exception is CIP-68 reference tokens, whose metadata datum
     * is the point of the output.
     */
    public static PlutusData programmableTokenDatum() {
        return ConstrPlutusData.of(0);
    }
}
