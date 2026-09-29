package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.cardanofoundation.cip113.service.UtxoProvider;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RwaTokenDenylistPrecheckTest {
    private static final String POLICY = "11".repeat(28);
    private static final byte[] STAKE = java.util.HexFormat.of().parseHex("22".repeat(28));
    private static final String NODE_NAME = java.util.HexFormat.of().formatHex(
            "Node".getBytes(StandardCharsets.UTF_8)) + "22".repeat(28);

    private static Utxo nft(String unit) {
        return Utxo.builder().amount(List.of(Amount.builder()
                .unit(unit).quantity(BigInteger.ONE).build())).build();
    }

    private static String datum(ConstrPlutusData data, ConstrPlutusData link) {
        return ConstrPlutusData.of(0, data, link).serializeToHex();
    }

    private static Utxo element(String unit, String datum) {
        return Utxo.builder().txHash("aa".repeat(32)).outputIndex(0).address("list-address")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)),
                        Amount.builder().unit(unit).quantity(BigInteger.ONE).build()))
                .inlineDatum(datum).build();
    }

    @Test
    void unrelatedDonationsAreIgnoredButMalformedAuthenticatedElementsFail() {
        var rootDatum = datum(ConstrPlutusData.of(0, ConstrPlutusData.of(0)), ConstrPlutusData.of(1));
        var root = element(POLICY, rootDatum);
        var donation = Utxo.builder().address("list-address")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000))))
                .inlineDatum("deadbeef").build();
        assertEquals(1, RwaTokenModuleHandler.parseDenylist(
                POLICY, "list-address", List.of(donation, root)).size());

        var malformed = element(POLICY + NODE_NAME,
                datum(ConstrPlutusData.of(1, ConstrPlutusData.of(0, ConstrPlutusData.of(0))),
                        ConstrPlutusData.of(0)));
        assertThrows(RuntimeException.class, () -> RwaTokenModuleHandler.parseDenylist(
                POLICY, "list-address", List.of(root, malformed)));
        assertThrows(RuntimeException.class, () -> RwaTokenModuleHandler.parseDenylist(
                POLICY, "list-address", List.of(root, element(POLICY, rootDatum))));

        var rootToMissing = element(POLICY, datum(
                ConstrPlutusData.of(0, ConstrPlutusData.of(0)),
                ConstrPlutusData.of(0, BytesPlutusData.of(STAKE))));
        assertThrows(RuntimeException.class, () -> RwaTokenModuleHandler.parseDenylist(
                POLICY, "list-address", List.of(rootToMissing)));
    }

    @Test
    void exactPolicyAndStakeNodeAreRequired() {
        var root = nft(POLICY);
        var wrongPolicy = nft("33".repeat(28) + NODE_NAME);
        var wrongStake = nft(POLICY + NODE_NAME.replace("22", "44"));
        assertEquals(1, RwaTokenModuleHandler.countDenylistNft(List.of(root), POLICY, ""));
        assertFalse(RwaTokenModuleHandler.hasDenylistNode(List.of(root, wrongPolicy, wrongStake), POLICY, STAKE));
        assertTrue(RwaTokenModuleHandler.hasDenylistNode(
                List.of(root, nft(POLICY + NODE_NAME)), POLICY, STAKE));
        assertEquals(0, RwaTokenModuleHandler.countDenylistNft(List.of(wrongPolicy), POLICY, ""));
    }

    @Test
    void completeAddressLookupFindsNodeAfterFirstPageAndFailsOnLaterPageError() throws Exception {
        var backend = mock(BFBackendService.class);
        var service = mock(UtxoService.class);
        when(backend.getUtxoService()).thenReturn(service);
        var provider = new UtxoProvider(backend, null, null);
        List<Utxo> firstPage = new ArrayList<>();
        firstPage.add(nft(POLICY));
        for (int i = 1; i < 100; i++) firstPage.add(nft("55".repeat(28) + String.format("%02x", i)));
        when(service.getUtxos("denylist-address", 100, 1))
                .thenReturn(Result.success("ok").withValue(firstPage));
        when(service.getUtxos("denylist-address", 100, 2))
                .thenReturn(Result.success("ok").withValue(List.of(nft(POLICY + NODE_NAME))));

        var all = provider.findAllCurrentUtxosFromBlockfrost("denylist-address");
        assertEquals(101, all.size());
        assertTrue(RwaTokenModuleHandler.hasDenylistNode(all, POLICY, STAKE));

        when(service.getUtxos("denylist-address", 100, 2)).thenReturn(Result.error("page two unavailable"));
        assertThrows(IllegalStateException.class,
                () -> provider.findAllCurrentUtxosFromBlockfrost("denylist-address"));
    }
}
