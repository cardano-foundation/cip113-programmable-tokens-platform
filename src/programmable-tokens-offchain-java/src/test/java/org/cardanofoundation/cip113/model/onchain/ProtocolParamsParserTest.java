package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreProtocolParamsDatum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The indexer's decoder for the protocol-params NFT datum.
 *
 * <h2>What these tests used to assert</h2>
 *
 * Both previous cases fed the decoder a <strong>two-field</strong> datum — the pre-v0.4.0
 * shape — and asserted the two-field record that came back. The current contract's datum
 * has five fields, so the decoder was being pinned to a shape the protocol had already
 * left, and the three credentials it dropped were the delegate credentials. (One of the
 * two cases also asserted values its own input did not contain, and was failing.)
 *
 * <p>The tests below pin the opposite property: the decoder round-trips the shape the
 * contract actually defines, and <em>refuses</em> anything else rather than returning a
 * partial answer. That refusal is the point — the next core revision reorders these fields
 * instead of appending to them, so a decoder that tolerates a wrong field count would keep
 * returning values, for the wrong roles.
 */
@Slf4j
class ProtocolParamsParserTest {

    private final ProtocolParamsParser parser = new ProtocolParamsParser();

    private static final String REGISTRY_NODE_CS = "2584c485b40f65f3659dc94d36ee4389c3f95349f41437cb9b422160";
    private static final Credential PROG_LOGIC = Credential.fromScript(
            HexUtil.decodeHexString("aaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102"));
    private static final Credential UNFRACKING = Credential.fromScript(
            HexUtil.decodeHexString("e20ce7ce49d7687b78b105eddd8a0c2752a4b3b3806396f87bea1edf"));
    private static final Credential TRANSFER = Credential.fromScript(
            HexUtil.decodeHexString("1d693164010d88f266caec71341429c408cd159ba70084f0cc42526d"));
    private static final Credential THIRD_PARTY = Credential.fromScript(
            HexUtil.decodeHexString("70a4611a5bb093a0d7019b5a78b050489c0a00111b5bd52c75824546"));
    private static final Credential UPGRADE = Credential.fromScript(
            HexUtil.decodeHexString("4861aca31fe0581ff2a16d180f26ac2b4feeb71ca5fd2a86b7927bb5"));

    private static CoreProtocolParamsDatum sample() {
        return new CoreProtocolParamsDatum(REGISTRY_NODE_CS, PROG_LOGIC, TRANSFER, THIRD_PARTY,
                UNFRACKING, UPGRADE, CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES);
    }

    @Test
    @DisplayName("round-trips a seven-field datum, preserving every delegate credential")
    void roundTrips() throws Exception {
        String hex = HexUtil.encodeHexString(sample().toPlutusData().serializeToBytes());

        var decoded = parser.parse(hex).orElseThrow(() -> new AssertionError("failed to decode " + hex));

        assertEquals(sample(), decoded);
        // Named individually as well as by record equality: a field-order bug in the codec
        // would round-trip perfectly and still be wrong on chain, so the roles are asserted
        // by name rather than only as a tuple.
        assertEquals(REGISTRY_NODE_CS, decoded.registryNodePolicyId());
        assertEquals(PROG_LOGIC, decoded.progLogicCred());
        assertEquals(UNFRACKING, decoded.unfrackingCred());
        assertEquals(TRANSFER, decoded.transferCred());
        assertEquals(THIRD_PARTY, decoded.thirdPartyCred());
        assertEquals(UPGRADE, decoded.upgradeCred());
        assertEquals(CoreProtocolParamsDatum.DEFAULT_MAX_INLINE_DATUM_BYTES, decoded.maxInlineDatumBytes());
    }

    /**
     * The exact datum the previous version of this test asserted success on. It is genuine
     * pre-v0.4.0 on-chain data, and it must now be refused: read positionally against the
     * current five-field layout it would report PLB's credential correctly and then run out
     * of fields, and a decoder that returned those first two anyway would be claiming the
     * protocol has no delegates rather than admitting it cannot read the datum.
     */
    @Test
    @DisplayName("refuses the pre-v0.4.0 two-field datum instead of half-decoding it")
    void refusesLegacyTwoFieldDatum() {
        String legacy = "d8799f581c2584c485b40f65f3659dc94d36ee4389c3f95349f41437cb9b422160"
                + "d87a9f581caaa513b0fcc01d635f8535d49f38acc33d4d6b62ee8732ca6e126102ffff";

        assertTrue(parser.parse(legacy).isEmpty(), "a two-field datum must not decode as seven fields");
    }

    /**
     * The layout this one replaced. It is not shorter by coincidence: the previous revision had
     * five fields AND ordered them differently — field 2 was {@code unfracking_cred} where it is
     * now {@code transfer_cred}. A decoder that accepted a five-field datum and read what it
     * could would therefore report the unfracking validator as the transfer delegate, with no
     * error anywhere. Refusing is what turns "this deployment predates the split" into something
     * a human sees.
     */
    @Test
    @DisplayName("refuses the previous five-field datum, whose field 2 meant something else")
    void refusesPreviousFiveFieldLayout() throws Exception {
        var five = ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(REGISTRY_NODE_CS)),
                PlutusCredentialCodec.toPlutusData(PROG_LOGIC),
                PlutusCredentialCodec.toPlutusData(UNFRACKING),
                PlutusCredentialCodec.toPlutusData(TRANSFER),
                PlutusCredentialCodec.toPlutusData(UPGRADE));

        assertTrue(parser.parse(HexUtil.encodeHexString(five.serializeToBytes())).isEmpty(),
                "a five-field datum must not decode as seven fields");
    }

    /**
     * A single-constructor record always has alternative 0, so anything else is a different type
     * that happens to carry seven compatible-looking fields.
     */
    @Test
    @DisplayName("refuses a datum with the wrong constructor alternative")
    void refusesWrongConstructor() throws Exception {
        var wrongCtor = ConstrPlutusData.of(1,
                BytesPlutusData.of(HexUtil.decodeHexString(REGISTRY_NODE_CS)),
                PlutusCredentialCodec.toPlutusData(PROG_LOGIC),
                PlutusCredentialCodec.toPlutusData(TRANSFER),
                PlutusCredentialCodec.toPlutusData(THIRD_PARTY),
                PlutusCredentialCodec.toPlutusData(UNFRACKING),
                PlutusCredentialCodec.toPlutusData(UPGRADE),
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(2048));

        assertTrue(parser.parse(HexUtil.encodeHexString(wrongCtor.serializeToBytes())).isEmpty());
    }

    @Test
    @DisplayName("returns empty rather than throwing, so one bad UTxO cannot halt the indexer")
    void refusesGarbageWithoutThrowing() {
        assertTrue(parser.parse("not hex at all").isEmpty());
        assertTrue(parser.parse("d87980").isEmpty(), "a zero-field constructor is not a params datum");
    }
}
