package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Plan Addendum I17: one passing transaction, then one negative case per clause. */
class GsTxSelfCheckTest {
    static final String GS_POLICY = "aa".repeat(28);
    static final String GS_NAME = "476c6f62616c5374617465";
    static final String GS_ADDR = "addr_test1wqgsaddress";
    static final String LOGIC = "stake_test1logic";
    static final String SIGNER = "bb".repeat(28);
    static final TransactionInput GS_IN = TransactionInput.builder().transactionId("01".repeat(32)).index(0).build();
    static final TransactionInput FUND_IN = TransactionInput.builder().transactionId("02".repeat(32)).index(1).build();
    static final PlutusData DATUM = ConstrPlutusData.of(0, BigIntPlutusData.of(7));
    static final PlutusData ACTION = ConstrPlutusData.of(0, BigIntPlutusData.of(0), ConstrPlutusData.of(6, ConstrPlutusData.of(1)));

    static Value gsValue() {
        return Value.builder().coin(BigInteger.valueOf(2_000_000)).multiAssets(new ArrayList<>(List.of(
                MultiAsset.builder().policyId(GS_POLICY).assets(new ArrayList<>(List.of(
                        new Asset("0x" + GS_NAME, BigInteger.ONE)))).build()))).build();
    }

    static Transaction good() {
        var gsOut = TransactionOutput.builder().address(GS_ADDR).value(gsValue()).inlineDatum(DATUM).build();
        var change = TransactionOutput.builder().address("addr_test1change")
                .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).build();
        var body = TransactionBody.builder()
                .inputs(new ArrayList<>(List.of(GS_IN, FUND_IN)))
                .outputs(new ArrayList<>(List.of(gsOut, change)))
                .withdrawals(new ArrayList<>(List.of(new Withdrawal(LOGIC, BigInteger.ZERO))))
                .requiredSigners(new ArrayList<>(List.of(HexUtil.decodeHexString(SIGNER))))
                .fee(BigInteger.valueOf(200_000)).build();
        var ws = new TransactionWitnessSet();
        ws.setRedeemers(new ArrayList<>(List.of(
                Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.ZERO).data(ConstrPlutusData.of(0))
                        .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build(),
                Redeemer.builder().tag(RedeemerTag.Reward).index(BigInteger.ZERO).data(ACTION)
                        .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build())));
        return Transaction.builder().body(body).witnessSet(ws).build();
    }

    static GsTxSelfCheck.Expectation expectation() {
        return new GsTxSelfCheck.Expectation(GS_IN, GS_ADDR, GS_POLICY, GS_NAME, DATUM, Map.of(LOGIC, ACTION),
                Set.of(), Set.of(), Set.of(), Set.of(SIGNER));
    }

    static void rejects(String clause, Consumer<Transaction> mutate) {
        var tx = good();
        mutate.accept(tx);
        var e = assertThrows(IllegalStateException.class, () -> GsTxSelfCheck.verify(tx, expectation()), clause);
        assertTrue(e.getMessage().startsWith("GlobalState self-check failed"), e.getMessage());
    }

    @Test
    void acceptsExactlyTheIntendedChange() {
        assertDoesNotThrow(() -> GsTxSelfCheck.verify(good(), expectation()));
    }

    @Test
    void rejectsEachClause() {
        rejects("GS input missing", tx -> tx.getBody().getInputs().removeFirst());
        rejects("GS output moved", tx -> tx.getBody().getOutputs().getFirst().setAddress("addr_test1elsewhere"));
        rejects("GS output gains a ref script", tx -> tx.getBody().getOutputs().getFirst().setScriptRef(new byte[]{1}));
        rejects("GS output gains an asset", tx -> tx.getBody().getOutputs().getFirst().getValue().getMultiAssets()
                .add(MultiAsset.builder().policyId("cc".repeat(28)).assets(new ArrayList<>(List.of(
                        new Asset("0x01", BigInteger.ONE)))).build()));
        rejects("NFT duplicated", tx -> tx.getBody().getOutputs().add(TransactionOutput.builder()
                .address(GS_ADDR).value(gsValue()).inlineDatum(DATUM).build()));
        rejects("different datum", tx -> tx.getBody().getOutputs().getFirst().setInlineDatum(ConstrPlutusData.of(0, BigIntPlutusData.of(8))));
        rejects("logic withdrawal missing", tx -> tx.getBody().getWithdrawals().clear());
        rejects("extra withdrawal", tx -> tx.getBody().getWithdrawals().add(new Withdrawal("stake_test1other", BigInteger.ZERO)));
        rejects("different action", tx -> tx.getWitnessSet().getRedeemers().get(1)
                .setData(ConstrPlutusData.of(0, BigIntPlutusData.of(0), ConstrPlutusData.of(7, ConstrPlutusData.of(1)))));
        rejects("certificate rides along", tx -> tx.getBody().setCerts(new ArrayList<>(List.of(
                new com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration(
                        com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential.fromKeyHash(new byte[28]))))));
        rejects("unexpected mint", tx -> tx.getBody().setMint(new ArrayList<>(List.of(MultiAsset.builder()
                .policyId("dd".repeat(28)).assets(new ArrayList<>(List.of(new Asset("0x01", BigInteger.ONE)))).build()))));
        rejects("unexpected script input", tx -> tx.getWitnessSet().getRedeemers().add(
                Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.ONE).data(ConstrPlutusData.of(0))
                        .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build()));
        rejects("extra signer", tx -> tx.getBody().getRequiredSigners().add(HexUtil.decodeHexString("ee".repeat(28))));
        rejects("GS output not at the redeemer's output index", tx -> {
            var outs = tx.getBody().getOutputs();
            outs.add(0, outs.remove(1));   // the continuing GS output moves to index 1; the redeemer still says 0
        });
    }
}
