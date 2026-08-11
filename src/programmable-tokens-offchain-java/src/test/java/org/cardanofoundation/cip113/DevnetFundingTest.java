package org.cardanofoundation.cip113;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.Tx;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

/**
 * Funds {@code adminAccount} from the Yaci DevKit genesis account.
 *
 * <p>The yaci-cli topup admin API is broken in the reeve devnet image (it answers
 * {@code {"status":false,"message":"Topup failed"}}), but yaci-store does index the
 * genesis UTxOs — so an ordinary transfer from the well-known devkit account works.
 * Run this after every {@code docker restart reeve-indexing-example-yaci-cli-1}.
 */
@Slf4j
public class DevnetFundingTest extends AbstractPreviewTest {

    /** Well-known Yaci DevKit genesis mnemonic; account 0 / index 0 holds 10 000 ADA. */
    private static final String DEVKIT_GENESIS_MNEMONIC =
            "test test test test test test test test test test test test test test test test test test test test test test test sauce";

    @Test
    public void fundAdmin() throws Exception {
        var genesis = Account.createFromMnemonic(network, DEVKIT_GENESIS_MNEMONIC, 0, 0);
        log.info("funding {} from {}", adminAccount.baseAddress(), genesis.baseAddress());

        // Three fat UTxOs: the deployment pins two of them as one-shot inputs and needs
        // ~160 ADA across the pair; the third covers the wallet-split fallback path.
        var tx = new Tx()
                .from(genesis.baseAddress())
                .payToAddress(adminAccount.baseAddress(), Amount.ada(1000))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(1000))
                .payToAddress(adminAccount.baseAddress(), Amount.ada(1000))
                .withChangeAddress(genesis.baseAddress());

        var result = quickTxBuilder.compose(tx)
                .withSigner(SignerProviders.signerFrom(genesis))
                .mergeOutputs(false)
                .completeAndWait();

        log.info("funding result: {}", result);
        Assertions.assertTrue(result.isSuccessful(), "funding tx failed: " + result.getResponse());

        var utxos = bfBackendService.getUtxoService().getUtxos(adminAccount.baseAddress(), 100, 1);
        Assertions.assertTrue(utxos.isSuccessful(), "utxo query failed: " + utxos.getResponse());
        var total = utxos.getValue().stream()
                .flatMap(u -> u.getAmount().stream())
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
        log.info("admin now holds {} lovelace across {} utxos", total, utxos.getValue().size());
        Assertions.assertTrue(total.compareTo(Amount.ada(2000).getQuantity()) >= 0,
                "expected >=2000 ADA at the admin address, got " + total);
    }
}
