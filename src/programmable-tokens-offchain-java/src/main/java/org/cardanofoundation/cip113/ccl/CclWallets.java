package org.cardanofoundation.cip113.ccl;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.programmabletoken.cip113.SmartWalletAddress;

/**
 * The platform's smart-wallet convention, expressed for cardano-client-lib.
 *
 * <p>The platform keeps every holder's programmable tokens at {@code PLB + stake credential}; the
 * frontend, the indexer and every legacy builder agree on that. {@link DeploymentMapper} describes
 * the platform's deployments to CCL with {@code SmartWalletKey.STAKE}, so CCL spends from, pays to
 * and requires the signature of that same wallet.
 */
public final class CclWallets {

    private CclWallets() {}

    /** The holder's smart wallet as the platform derives it: base script + stake credential. */
    public static Address smartWallet(Cip113Deployment deployment, String holderAddress) {
        return SmartWalletAddress.ofStakeCredential(deployment, new Address(holderAddress));
    }

    /**
     * Why a burn of {@code txHash#outputIndex} by {@code holder} must be refused, or {@code null}.
     *
     * <p>CCL's {@code burn} is an owner burn: it spends the burner's own smart wallet. Burning a
     * UTxO in anyone else's wallet is a third-party burn ({@code thirdPartyBurn}), which only a
     * module whose third-party logic authorises it uses; the dummy module does not. (The message
     * still names "CCL gap #12" because a test asserts it.)
     */
    public static String refuseForeignBurn(Cip113Deployment deployment, UtxoSupplier utxos, String holder,
                                           String txHash, int outputIndex) {
        var utxo = utxos.getTxOutput(txHash, outputIndex);
        if (utxo.isEmpty())
            return "UTxO " + txHash + "#" + outputIndex + " not found";
        var wallet = smartWallet(deployment, holder).toBech32();
        if (!wallet.equals(utxo.get().getAddress()))
            return "UTxO " + txHash + "#" + outputIndex + " is not in the burner's own smart wallet " + wallet
                    + ": burning another holder's tokens is a third-party burn (CCL gap #12)";
        return null;
    }
}
