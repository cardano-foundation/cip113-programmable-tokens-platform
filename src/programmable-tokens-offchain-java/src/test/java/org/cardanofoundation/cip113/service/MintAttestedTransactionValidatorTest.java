package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.transaction.spec.*;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MintAttestedTransactionValidatorTest {
    private static final String POLICY = "11".repeat(28);
    private static final String ASSET = "746f6b656e";
    private static final String DEST = AddressProvider.getBaseAddress(
            Credential.fromScript(com.bloxbean.cardano.client.util.HexUtil.decodeHexString("22".repeat(28))),
            Credential.fromKey(com.bloxbean.cardano.client.util.HexUtil.decodeHexString("33".repeat(28))),
            Networks.preview()).getAddress();
    private static final Cip170AttestationData ATTESTATION =
            new Cip170AttestationData("E" + "a".repeat(43), "E" + "b".repeat(43), "1", "1.0");

    private static MintAttestationRequest intent(String destination, String quantity) {
        return new MintAttestationRequest("session", "preview", "44".repeat(32), POLICY, ASSET,
                quantity, DEST, DEST, destination);
    }

    private static String transaction(String outputAddress, BigInteger minted, BigInteger delivered,
                                      String digest) throws Exception {
        var asset = Asset.builder().name("0x" + ASSET).value(minted).build();
        var outputAsset = Asset.builder().name("0x" + ASSET).value(delivered).build();
        var output = TransactionOutput.builder().address(outputAddress)
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000))
                        .multiAssets(List.of(MultiAsset.builder().policyId(POLICY)
                                .assets(List.of(outputAsset)).build())).build()).build();
        var version = MetadataBuilder.createMap();
        version.put("v", "1.0");
        var attest = MetadataBuilder.createMap();
        attest.put("t", "ATTEST"); attest.put("i", ATTESTATION.signerAid());
        attest.put("d", digest); attest.put("s", ATTESTATION.seqNumber());
        attest.put("v", version);
        var metadata = MetadataBuilder.createMetadata();
        metadata.put(170L, attest);
        var auxiliary = AuxiliaryData.builder().metadata(metadata).build();
        var body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("55".repeat(32)).index(0).build()))
                .outputs(List.of(output)).fee(BigInteger.valueOf(200_000))
                .mint(List.of(MultiAsset.builder().policyId(POLICY).assets(List.of(asset)).build()))
                .auxiliaryDataHash(auxiliary.getAuxiliaryDataHash()).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet())
                .auxiliaryData(auxiliary).isValid(true).build().serializeToHex();
    }

    @Test void acceptsExactMintAndAttestation() throws Exception {
        assertEquals(64, MintAttestedTransactionValidator.validate(
                transaction(DEST, BigInteger.TEN, BigInteger.TEN, ATTESTATION.digest()),
                intent(DEST, "10"), ATTESTATION).length());
    }

    @Test void rejectsChangedMintDestinationAndDigest() throws Exception {
        String cbor = transaction(DEST, BigInteger.TEN, BigInteger.TEN, ATTESTATION.digest());
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validate(
                cbor, intent(DEST, "11"), ATTESTATION));
        String other = AddressProvider.getBaseAddress(
                Credential.fromScript(com.bloxbean.cardano.client.util.HexUtil.decodeHexString("22".repeat(28))),
                Credential.fromKey(com.bloxbean.cardano.client.util.HexUtil.decodeHexString("66".repeat(28))),
                Networks.preview()).getAddress();
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validate(
                transaction(other, BigInteger.TEN, BigInteger.TEN, ATTESTATION.digest()),
                intent(DEST, "10"), ATTESTATION));
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validate(
                transaction(DEST, BigInteger.TEN, BigInteger.TEN, "E" + "c".repeat(43)),
                intent(DEST, "10"), ATTESTATION));
    }
}
