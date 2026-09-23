package org.cardanofoundation.cip113.service.module;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.cardanofoundation.cip113.model.CmtaAttestation;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CmtaAttestationVerifierTest {
    private static final byte[] SEED = new byte[32];
    private static final byte[] SUBJECT = new byte[28];
    private static final String POLICY = "ab".repeat(28);
    private static final long NOW = 1_700_000_000_000L;
    private static final Ed25519PrivateKeyParameters KEY;
    private static final String VKEY;
    private static final String OTHER_VKEY;
    static {
        Arrays.fill(SEED, (byte) 7);
        Arrays.fill(SUBJECT, (byte) 9);
        KEY = new Ed25519PrivateKeyParameters(SEED, 0);
        VKEY = HexUtil.encodeHexString(KEY.generatePublicKey().getEncoded());
        OTHER_VKEY = HexUtil.encodeHexString(
                new Ed25519PrivateKeyParameters(new byte[32], 0).generatePublicKey().getEncoded());
    }

    private static byte[] payload(long expiry) {
        byte[] result = new byte[67];
        System.arraycopy(SUBJECT, 0, result, 0, 28);
        result[28] = 1;
        ByteBuffer.wrap(result, 29, 8).putLong(expiry);
        System.arraycopy(HexUtil.decodeHexString(POLICY), 0, result, 37, 28);
        result[65] = 0;
        result[66] = 0;
        return result;
    }

    private static CmtaAttestation signed(byte[] payload) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, KEY);
        signer.update(payload, 0, payload.length);
        return new CmtaAttestation(HexUtil.encodeHexString(payload),
                HexUtil.encodeHexString(signer.generateSignature()), VKEY);
    }

    private static CmtaAttestationVerifier.Verified verify(CmtaAttestation attestation) {
        return CmtaAttestationVerifier.verify(attestation, SUBJECT, (short) 0,
                POLICY, 0, List.of(VKEY), NOW, "receiver");
    }

    @Test
    void discoversTrustedIssuerWithoutAKeyHintAndUsesItInTheRedeemer() throws Exception {
        var signed = signed(payload(System.currentTimeMillis() + 600_000));
        var noHint = new CmtaAttestation(signed.payloadHex(), signed.signatureHex(), null);
        var keys = Arrays.asList("not hex", null, "00".repeat(32), OTHER_VKEY, VKEY.toUpperCase(), VKEY);
        var verified = CmtaAttestationVerifier.verify(noHint, SUBJECT, (short) 0,
                POLICY, 0, keys, NOW, "receiver");
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), verified.issuerVkey());
        var reordered = CmtaAttestationVerifier.verify(noHint, SUBJECT, (short) 0,
                POLICY, 0, List.of(VKEY, OTHER_VKEY), NOW, "receiver");
        assertArrayEquals(verified.issuerVkey(), reordered.issuerVkey());
        var proof = RwaTokenModuleHandler.resolveTransferProof("receiver", SUBJECT,
                (short) 0, true, null, null, noHint, POLICY, 0, keys);
        var inner = (ConstrPlutusData) ((ConstrPlutusData) proof.data()).getData().getPlutusDataList().getFirst();
        assertArrayEquals(KEY.generatePublicKey().getEncoded(),
                ((BytesPlutusData) inner.getData().getPlutusDataList().get(2)).getValue());
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(noHint,
                SUBJECT, (short) 0, POLICY, 0, List.of(), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(noHint,
                SUBJECT, (short) 0, POLICY, 0, List.of(OTHER_VKEY), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(noHint,
                SUBJECT, (short) 0, POLICY, 0, Arrays.asList(null, "not hex"), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(noHint,
                new byte[28], (short) 0, POLICY, 0, List.of(VKEY), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(noHint,
                SUBJECT, (short) 0, POLICY, 1, List.of(VKEY), NOW, "receiver"));
    }

    @Test
    void explicitIssuerNeverFallsBackToAnotherTrustedKey() {
        var signed = signed(payload(NOW + 600_000));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                new CmtaAttestation(signed.payloadHex(), signed.signatureHex(), OTHER_VKEY),
                SUBJECT, (short) 0, POLICY, 0, List.of(OTHER_VKEY, VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                new CmtaAttestation(signed.payloadHex(), signed.signatureHex(), ""),
                SUBJECT, (short) 0, POLICY, 0, List.of(VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                new CmtaAttestation(signed.payloadHex(), signed.signatureHex(), OTHER_VKEY),
                SUBJECT, (short) 0, POLICY, 0, List.of(VKEY), NOW, "sender"));
    }

    @Test
    void acceptsExactRawClaimAndSignature() throws Exception {
        var claim = payload(NOW + 600_000);
        var verified = verify(signed(claim));
        assertArrayEquals(claim, verified.payload());
        assertEquals(NOW + 600_000, verified.validUntilMs());
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), verified.issuerVkey());
        var currentClaim = payload(System.currentTimeMillis() + 600_000);
        var current = signed(currentClaim);
        var proof = RwaTokenModuleHandler.resolveTransferProof("receiver", SUBJECT,
                (short) 0, true, null, null, current, POLICY, 0, List.of(VKEY));
        var outer = (ConstrPlutusData) proof.data();
        assertEquals(0, outer.getAlternative());
        var inner = (ConstrPlutusData) outer.getData().getPlutusDataList().getFirst();
        assertEquals(0, inner.getAlternative());
        var fields = inner.getData().getPlutusDataList();
        assertEquals(3, fields.size());
        assertArrayEquals(currentClaim, ((BytesPlutusData) fields.get(0)).getValue());
        assertArrayEquals(HexUtil.decodeHexString(current.signatureHex()), ((BytesPlutusData) fields.get(1)).getValue());
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), ((BytesPlutusData) fields.get(2)).getValue());
    }

    @Test
    void rejectsEverySignedBindingAndMalformedEnvelope() {
        var valid = signed(payload(NOW + 600_000));
        byte[] badSignature = HexUtil.decodeHexString(valid.signatureHex());
        badSignature[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), HexUtil.encodeHexString(badSignature), VKEY)));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(valid,
                new byte[28], (short) 0, POLICY, 0, List.of(VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(valid,
                SUBJECT, (short) 1, POLICY, 0, List.of(VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(valid,
                SUBJECT, (short) 0, "cd".repeat(28), 0, List.of(VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(valid,
                SUBJECT, (short) 0, POLICY, 1, List.of(VKEY), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(valid,
                SUBJECT, (short) 0, POLICY, 0, List.of(), NOW, "sender"));
        assertThrows(IllegalArgumentException.class, () -> verify(signed(payload(NOW - 1))));
        byte[] zeroTier = payload(NOW + 600_000); zeroTier[28] = 0;
        assertThrows(IllegalArgumentException.class, () -> verify(signed(zeroTier)));
        assertThrows(IllegalArgumentException.class, () -> verify(signed(new byte[37])));
        assertThrows(IllegalArgumentException.class, () -> verify(signed(new byte[66])));
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                "d2" + valid.payloadHex(), valid.signatureHex(), VKEY)));
        byte[] overflow = payload(NOW + 600_000); Arrays.fill(overflow, 29, 37, (byte) 0xff);
        assertThrows(IllegalArgumentException.class, () -> verify(signed(overflow)));
        assertThrows(IllegalArgumentException.class, () -> RwaTokenModuleHandler.resolveTransferProof(
                "sender", SUBJECT, (short) 0, true, "80", NOW + 600_000,
                valid, POLICY, 0, List.of(VKEY)));
    }
}
