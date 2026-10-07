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

    /** A mint whose label 170 is the given record (null = no auxiliary data). */
    private static String attestTxMint(com.bloxbean.cardano.client.metadata.MetadataMap record) throws Exception {
        var asset = Asset.builder().name("0x" + ASSET).value(BigInteger.TEN).build();
        var output = TransactionOutput.builder().address(DEST)
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000))
                        .multiAssets(List.of(MultiAsset.builder().policyId(POLICY).assets(List.of(asset)).build())).build()).build();
        var body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("55".repeat(32)).index(0).build()))
                .outputs(List.of(output)).fee(BigInteger.valueOf(200_000))
                .mint(List.of(MultiAsset.builder().policyId(POLICY).assets(List.of(asset)).build())).build();
        var tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true).build();
        if (record != null) {
            var metadata = MetadataBuilder.createMetadata();
            metadata.put(170L, record);
            var auxiliary = AuxiliaryData.builder().metadata(metadata).build();
            tx.setAuxiliaryData(auxiliary); body.setAuxiliaryDataHash(auxiliary.getAuxiliaryDataHash());
        }
        return tx.serializeToHex();
    }
    private static com.bloxbean.cardano.client.metadata.MetadataMap record(String t, String aid, String version) {
        var v = MetadataBuilder.createMap(); v.put("v", version);
        var r = MetadataBuilder.createMap(); r.put("t", t); r.put("i", aid); r.put("v", v);
        return r;
    }

    @Test void acceptsExactAttestTxMintAndItsSeal() throws Exception {
        String aid = ATTESTATION.signerAid();
        String cbor = attestTxMint(record("ATTEST_TX", aid, "1.1"));
        String hash = MintAttestedTransactionValidator.validateAttestTx(cbor, intent(DEST, "10"), aid, null, 2L);
        assertEquals(hash, MintAttestedTransactionValidator.validateAttestTx(cbor, intent(DEST, "10"), aid,
                TxAttestationSeal.digest(hash, 2L), 2L));
    }

    @Test void rejectsAttestTxForAnotherSignerShapeVersionOrSeal() throws Exception {
        String aid = ATTESTATION.signerAid();
        var intent = intent(DEST, "10");
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(null), intent, aid, null, 2L));
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(record("ATTEST_TX", "E" + "c".repeat(43), "1.1")), intent, aid, null, 2L));
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(record("ATTEST", aid, "1.1")), intent, aid, null, 2L));
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(record("ATTEST_TX", aid, "1.0")), intent, aid, null, 2L));
        var withDigest = record("ATTEST_TX", aid, "1.1"); withDigest.put("d", ATTESTATION.digest());
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(withDigest), intent, aid, null, 2L));
        var withSequence = record("ATTEST_TX", aid, "1.1"); withSequence.put("s", "1");
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                attestTxMint(withSequence), intent, aid, null, 2L));
        String cbor = attestTxMint(record("ATTEST_TX", aid, "1.1"));
        String hash = MintAttestedTransactionValidator.validateAttestTx(cbor, intent, aid, null, 2L);
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                cbor, intent, aid, TxAttestationSeal.digest(hash, 764824073L), 2L));
        assertThrows(IllegalArgumentException.class, () -> MintAttestedTransactionValidator.validateAttestTx(
                cbor, intent, aid, TxAttestationSeal.digest("ff".repeat(32), 2L), 2L));
    }
}
