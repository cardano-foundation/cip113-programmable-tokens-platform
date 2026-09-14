package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CoreRedeemersTest {

    private static final String POLICY_A = "11".repeat(28);
    private static final String POLICY_B = "22".repeat(28);

    @Test
    void baseSpendCarriesOnlyParamsAndDispatcherWithdrawalIndices() {
        assertConstr(CoreRedeemers.baseSpend(2, 4), 0, 2, 4);
    }

    @Test
    void dispatcherArmsUseTheAlpha4ConstructorOrder() {
        assertConstr(CoreRedeemers.dispatchTransfer(), 0);
        assertConstr(CoreRedeemers.dispatchThirdParty(), 1);
        assertConstr(CoreRedeemers.dispatchUnfracking(), 2);
    }

    @Test
    void delegateRedeemersHaveNoLegacyParamsIndex() {
        var proof = CoreRedeemers.tokenExists(7);
        var transfer = assertInstanceOf(ConstrPlutusData.class,
                CoreRedeemers.transferRedeemer(List.of(proof)));
        assertEquals(0, transfer.getAlternative());
        var fields = transfer.getData().getPlutusDataList();
        assertEquals(1, fields.size());
        var proofs = assertInstanceOf(ListPlutusData.class, fields.getFirst());
        assertEquals(List.of(proof), proofs.getPlutusDataList());

        assertConstr(CoreRedeemers.thirdPartyRedeemer(3, 8), 0, 3, 8);
        assertConstr(CoreRedeemers.unfrackingRedeemer(5, 9), 0, 5, 9);
    }

    @Test
    void issuancePolicyAndIssuanceLogicUseSeparateRedeemers() {
        assertConstr(CoreRedeemers.issuanceRedeemer(6), 0, 6);

        var redeemer = assertInstanceOf(MapPlutusData.class,
                CoreRedeemers.issuanceLogicRedeemer(List.of(
                        new CoreRedeemers.IssuanceEntry(POLICY_A, CoreRedeemers.mintProofRefInput(1)),
                        new CoreRedeemers.IssuanceEntry(POLICY_B, CoreRedeemers.mintProofOutputIndex(4)))));
        assertEquals(2, redeemer.getMap().size());

        var first = redeemer.getMap().entrySet().stream()
                .filter(entry -> POLICY_A.equals(hex((BytesPlutusData) entry.getKey())))
                .findFirst().orElseThrow();
        assertConstr(first.getValue(), 0, 1);

        var second = redeemer.getMap().entrySet().stream()
                .filter(entry -> POLICY_B.equals(hex((BytesPlutusData) entry.getKey())))
                .findFirst().orElseThrow();
        assertConstr(second.getValue(), 1, 4);
    }

    @Test
    void invalidIndicesAndIssuanceMapsFailBeforeTransactionConstruction() {
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.baseSpend(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.issuanceRedeemer(-1));
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.issuanceLogicRedeemer(List.of()));
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.issuanceLogicRedeemer(List.of(
                new CoreRedeemers.IssuanceEntry("not-a-policy", CoreRedeemers.mintProofRefInput(0)))));
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.issuanceLogicRedeemer(List.of(
                new CoreRedeemers.IssuanceEntry(POLICY_A, CoreRedeemers.mintProofRefInput(0)),
                new CoreRedeemers.IssuanceEntry(POLICY_A.toUpperCase(), CoreRedeemers.mintProofOutputIndex(0)))));
        assertThrows(IllegalArgumentException.class, () -> CoreRedeemers.issuanceLogicRedeemer(List.of(
                new CoreRedeemers.IssuanceEntry(POLICY_A, CoreRedeemers.dispatchUnfracking()))));
    }

    private static void assertConstr(Object data, int alternative, int... integers) {
        var constr = assertInstanceOf(ConstrPlutusData.class, data);
        assertEquals(alternative, constr.getAlternative());
        var fields = constr.getData().getPlutusDataList();
        assertEquals(integers.length, fields.size());
        for (int i = 0; i < integers.length; i++) {
            assertEquals(BigInteger.valueOf(integers[i]),
                    assertInstanceOf(BigIntPlutusData.class, fields.get(i)).getValue());
        }
    }

    private static String hex(BytesPlutusData bytes) {
        return java.util.HexFormat.of().formatHex(bytes.getValue());
    }
}
