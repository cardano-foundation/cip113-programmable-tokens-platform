package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict parser tests for the alpha.4 six-field protocol-params datum. */
class ProtocolParamsParserTest {

    private final ProtocolParamsParser parser = new ProtocolParamsParser();

    private static final Credential PLG = script("aaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102");
    private static final Credential ISSUANCE_LOGIC = script("e20ce7ce49d7687b78b105eddd8a0c2752a4b3b3806396f87bea1edf");
    private static final Credential TRANSFER = script("1d693164010d88f266caec71341429c408cd159ba70084f0cc42526d");
    private static final Credential THIRD_PARTY = script("70a4611a5bb093a0d7019b5a78b050489c0a00111b5bd52c75824546");
    private static final Credential UPGRADE = script("4861aca31fe0581ff2a16d180f26ac2b4feeb71ca5fd2a86b7927bb5");
    private static final Credential PENDING = script("5364c7fb24d0dbbf1fa32ad3d83ae87ee50de85bafc9ad1f59af033b");

    private static Credential script(String hash) {
        return Credential.fromScript(HexUtil.decodeHexString(hash));
    }

    private static CoreProtocolParamsDatum sample(Credential pending) {
        return new CoreProtocolParamsDatum(PLG, ISSUANCE_LOGIC, TRANSFER, THIRD_PARTY, UPGRADE, pending);
    }

    @Test
    @DisplayName("round-trips alpha.4 genesis params with pending upgrade None")
    void roundTripsGenesis() throws Exception {
        String hex = HexUtil.encodeHexString(sample(null).toPlutusData().serializeToBytes());
        var decoded = parser.parse(hex).orElseThrow();

        assertEquals(sample(null), decoded);
        assertEquals(PLG, decoded.plgCred());
        assertEquals(ISSUANCE_LOGIC, decoded.issuanceLogicCred());
        assertEquals(TRANSFER, decoded.transferCred());
        assertEquals(THIRD_PARTY, decoded.thirdPartyCred());
        assertEquals(UPGRADE, decoded.upgradeCred());
        assertNull(decoded.pendingUpgradeCred());
    }

    @Test
    @DisplayName("round-trips alpha.4 params with pending upgrade Some")
    void roundTripsPendingUpgrade() throws Exception {
        String hex = HexUtil.encodeHexString(sample(PENDING).toPlutusData().serializeToBytes());
        assertEquals(sample(PENDING), parser.parse(hex).orElseThrow());
    }

    @Test
    @DisplayName("refuses the legacy two-field datum")
    void refusesLegacyTwoFieldDatum() {
        String legacy = "d8799f581c2584c485b40f65f3659dc94d36ee4389c3f95349f41437cb9b422160"
                + "d87a9f581caaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102ffff";
        assertTrue(parser.parse(legacy).isEmpty());
    }

    @Test
    @DisplayName("refuses the previous five-field layout")
    void refusesPreviousFiveFieldLayout() throws Exception {
        var five = ConstrPlutusData.of(0,
                PlutusCredentialCodec.toPlutusData(PLG),
                PlutusCredentialCodec.toPlutusData(TRANSFER),
                PlutusCredentialCodec.toPlutusData(THIRD_PARTY),
                PlutusCredentialCodec.toPlutusData(UPGRADE),
                ConstrPlutusData.of(1));
        assertTrue(parser.parse(HexUtil.encodeHexString(five.serializeToBytes())).isEmpty());
    }

    @Test
    @DisplayName("refuses a six-field datum with the wrong constructor alternative")
    void refusesWrongConstructor() throws Exception {
        var wrongCtor = ConstrPlutusData.of(1,
                PlutusCredentialCodec.toPlutusData(PLG),
                PlutusCredentialCodec.toPlutusData(ISSUANCE_LOGIC),
                PlutusCredentialCodec.toPlutusData(TRANSFER),
                PlutusCredentialCodec.toPlutusData(THIRD_PARTY),
                PlutusCredentialCodec.toPlutusData(UPGRADE),
                ConstrPlutusData.of(1));
        assertTrue(parser.parse(HexUtil.encodeHexString(wrongCtor.serializeToBytes())).isEmpty());
    }

    @Test
    @DisplayName("returns empty rather than throwing, so one bad UTxO cannot halt the indexer")
    void refusesGarbageWithoutThrowing() {
        assertTrue(parser.parse("not hex at all").isEmpty());
        assertTrue(parser.parse("d87980").isEmpty());
    }
}
