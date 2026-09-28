package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RwaTokenTransferProofRoutingTest {
    @Test
    void actualSelectedInputChangeControlsSenderRequirement() {
        // The wallet may own more tokens elsewhere, but a selected exact input
        // produces no change and needs no sender proof when only receiver KYC is on.
        assertFalse(RwaTokenModuleHandler.requiresTransferSenderProof(
                false, true, BigInteger.ZERO, false));
        assertTrue(RwaTokenModuleHandler.requiresTransferSenderProof(
                false, true, BigInteger.ONE, false));
        assertFalse(RwaTokenModuleHandler.requiresTransferSenderProof(
                false, true, BigInteger.ONE, true));
        assertTrue(RwaTokenModuleHandler.requiresTransferSenderProof(
                true, false, BigInteger.ZERO, true));
    }

    @Test
    void destinationActionsIncludeRecipientAndDistinctSenderChangeInOutputOrder() {
        var receiver = ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(11)));
        var sender = ConstrPlutusData.of(1, BigIntPlutusData.of(BigInteger.valueOf(22)));
        var full = RwaTokenModuleHandler.buildTransferDestinationActions(
                receiver, sender, BigInteger.ZERO, false, 3).getPlutusDataList();
        assertEquals(1, full.size());
        assertEquals(receiver.serializeToHex(), ((ConstrPlutusData) full.getFirst())
                .getData().getPlutusDataList().getFirst().serializeToHex());
        var partial = RwaTokenModuleHandler.buildTransferDestinationActions(
                receiver, sender, BigInteger.TEN, false, 3).getPlutusDataList();
        assertEquals(2, partial.size());
        assertEquals(receiver.serializeToHex(), ((ConstrPlutusData) partial.getFirst())
                .getData().getPlutusDataList().getFirst().serializeToHex());
        assertEquals(sender.serializeToHex(), ((ConstrPlutusData) partial.get(1))
                .getData().getPlutusDataList().getFirst().serializeToHex());
        var same = RwaTokenModuleHandler.buildTransferDestinationActions(
                receiver, sender, BigInteger.TEN, true, 3).getPlutusDataList();
        assertEquals(1, same.size(), "same full stake credential appears once, even with change");
    }
}
