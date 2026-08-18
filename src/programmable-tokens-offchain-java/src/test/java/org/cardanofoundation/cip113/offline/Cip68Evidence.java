package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.Assertions;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decodes a built transaction back into the facts a CIP-68 reviewer actually cares about —
 * where each token landed, under which policy, with which CIP-67 label, and what the reference
 * token's inline datum says — and asserts them.
 *
 * <p>Everything here reads the <em>built transaction</em>, never the inputs that produced it, so
 * an assertion passing means the bytes that would go on chain are right, not merely that the
 * test computed the same value twice.
 */
@Slf4j
public final class Cip68Evidence {

    private Cip68Evidence() {
    }

    /** One token found on one output. */
    public record TokenAt(int outputIndex, String policyId, String assetNameHex, BigInteger quantity) {
        public Integer label() {
            return Cip68.readLabel(assetNameHex);
        }

        public String baseNameHex() {
            return Cip68.stripLabel(assetNameHex);
        }
    }

    /** Log every output of {@code tx}: address, address type, credentials, tokens, datum size. */
    public static void dumpOutputs(String label, Transaction tx) {
        var outputs = tx.getBody().getOutputs();
        log.info("[{}] {} outputs:", label, outputs.size());
        for (int i = 0; i < outputs.size(); i++) {
            var out = outputs.get(i);
            var addr = new Address(out.getAddress());
            var payment = addr.getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("-");
            var stake = addr.getDelegationCredentialHash().map(HexUtil::encodeHexString).orElse("-");
            log.info("[{}]   out[{}] type={} paymentCred={} stakeCred={} coin={} datum={} tokens={}",
                    label, i, addr.getAddressType(), payment, stake,
                    out.getValue().getCoin(),
                    out.getInlineDatum() == null ? "-" : "inline",
                    tokensOf(out, i));
        }
    }

    /** Every non-ADA token on {@code output}. */
    public static List<TokenAt> tokensOf(TransactionOutput output, int outputIndex) {
        var tokens = new ArrayList<TokenAt>();
        if (output.getValue().getMultiAssets() == null) {
            return tokens;
        }
        for (var ma : output.getValue().getMultiAssets()) {
            for (var asset : ma.getAssets()) {
                // Asset.getName() is "0x"-prefixed hex as built; normalise to bare hex.
                var nameHex = asset.getName().startsWith("0x") ? asset.getName().substring(2) : asset.getName();
                tokens.add(new TokenAt(outputIndex, ma.getPolicyId(), nameHex, asset.getValue()));
            }
        }
        return tokens;
    }

    /** Every token on every output of {@code tx}, in output order. */
    public static List<TokenAt> allTokens(Transaction tx) {
        var all = new ArrayList<TokenAt>();
        var outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            all.addAll(tokensOf(outputs.get(i), i));
        }
        return all;
    }

    /** Every token of {@code policyId} across all outputs. */
    public static List<TokenAt> tokensOfPolicy(Transaction tx, String policyId) {
        return allTokens(tx).stream().filter(t -> policyId.equals(t.policyId())).toList();
    }

    /**
     * Assert an output sits at a programmable-logic-base address with an inline stake credential.
     *
     * <p>CIP-113 core's {@code no_escape} requires exactly this of every output holding a
     * programmable token: payment part must be the PLB <em>script</em> hash, and the stake part
     * must be present and embedded in the address (a Base address). A pointer address would also
     * "have" a delegation part but not inline, hence the explicit address-type check.
     */
    public static void assertProgrammableLogicBaseAddress(String label,
                                                          Transaction tx,
                                                          int outputIndex,
                                                          String plbScriptHash,
                                                          String expectedStakeCredHashHex) {
        var out = tx.getBody().getOutputs().get(outputIndex);
        var addr = new Address(out.getAddress());

        Assertions.assertEquals(AddressType.Base, addr.getAddressType(),
                "[" + label + "] out[" + outputIndex + "] must be a Base address so the stake"
                + " credential is INLINE (a pointer/enterprise address fails core's no_escape)");
        Assertions.assertTrue(addr.isScriptHashInPaymentPart(),
                "[" + label + "] out[" + outputIndex + "] payment part must be a script hash");
        Assertions.assertEquals(plbScriptHash,
                addr.getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse(null),
                "[" + label + "] out[" + outputIndex + "] payment credential must be the"
                + " programmable_logic_base script hash");
        if (expectedStakeCredHashHex != null) {
            Assertions.assertEquals(expectedStakeCredHashHex,
                    addr.getDelegationCredentialHash().map(HexUtil::encodeHexString).orElse(null),
                    "[" + label + "] out[" + outputIndex + "] stake credential mismatch");
        }
    }

    /**
     * Decode a CIP-68 reference-token inline datum back into the metadata it was built from.
     *
     * <p>Deliberately independent of {@link Cip68#buildDatum}: it walks the
     * {@code Constr 0 [map, version, extra]} shape by hand, so a change to the builder cannot
     * make the round-trip pass by construction.
     */
    public static Cip68Metadata decodeDatum(PlutusData datum) {
        Assertions.assertInstanceOf(ConstrPlutusData.class, datum,
                "CIP-68 datum must be a Constr");
        var constr = (ConstrPlutusData) datum;
        Assertions.assertEquals(0, constr.getAlternative(), "CIP-68 datum must be Constr 0");

        var fields = constr.getData().getPlutusDataList();
        Assertions.assertEquals(3, fields.size(),
                "CIP-68 datum must be Constr 0 [metadata, version, extra]");

        Assertions.assertInstanceOf(MapPlutusData.class, fields.get(0),
                "CIP-68 datum field 0 must be the metadata map");
        Assertions.assertEquals(BigInteger.ONE, ((BigIntPlutusData) fields.get(1)).getValue(),
                "CIP-68 version must be 1");
        Assertions.assertEquals(BigInteger.ONE, ((BigIntPlutusData) fields.get(2)).getValue(),
                "CIP-68 extra_plutus_data must be 1");

        var decoded = new LinkedHashMap<String, PlutusData>();
        for (Map.Entry<PlutusData, PlutusData> e : ((MapPlutusData) fields.get(0)).getMap().entrySet()) {
            Assertions.assertInstanceOf(BytesPlutusData.class, e.getKey(),
                    "CIP-68 metadata keys must be byte strings");
            decoded.put(new String(((BytesPlutusData) e.getKey()).getValue(), StandardCharsets.UTF_8),
                    e.getValue());
        }

        return new Cip68Metadata(
                text(decoded.get("name")),
                text(decoded.get("description")),
                text(decoded.get("ticker")),
                decoded.containsKey("decimals")
                        ? ((BigIntPlutusData) decoded.get("decimals")).getValue().intValue()
                        : null,
                text(decoded.get("url")),
                text(decoded.get("logo")));
    }

    /** Decode and assert the reference-token datum round-trips to {@code expected}. */
    public static void assertDatumRoundTrip(String label,
                                            Transaction tx,
                                            int outputIndex,
                                            Cip68Metadata expected) {
        var out = tx.getBody().getOutputs().get(outputIndex);
        Assertions.assertNotNull(out.getInlineDatum(),
                "[" + label + "] out[" + outputIndex + "] must carry an INLINE datum"
                + " (a datum hash would leave the metadata unresolvable)");

        var actual = decodeDatum(out.getInlineDatum());
        log.info("[{}] out[{}] decoded CIP-68 datum: name='{}' description='{}' ticker='{}'"
                 + " decimals={} url='{}' logo='{}'",
                label, outputIndex, actual.name(), actual.description(), actual.ticker(),
                actual.decimals(), actual.url(), actual.logo());

        Assertions.assertEquals(expected.name(), actual.name(), "[" + label + "] name round-trip");
        Assertions.assertEquals(expected.description(), actual.description(), "[" + label + "] description round-trip");
        Assertions.assertEquals(expected.ticker(), actual.ticker(), "[" + label + "] ticker round-trip");
        Assertions.assertEquals(expected.decimals(), actual.decimals(), "[" + label + "] decimals round-trip");
        Assertions.assertEquals(expected.url(), actual.url(), "[" + label + "] url round-trip");
        Assertions.assertEquals(expected.logo(), actual.logo(), "[" + label + "] logo round-trip");
    }

    private static String text(PlutusData data) {
        if (data == null) {
            return null;
        }
        return new String(((BytesPlutusData) data).getValue(), StandardCharsets.UTF_8);
    }
}
