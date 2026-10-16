package org.cardanofoundation.cip113.service.module;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import org.cardanofoundation.cip113.model.CmtaAttestation;
import org.cardanofoundation.cip113.service.cose.CoseSign1;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CIP-30 KYC attestations (PR #29). REWRITTEN (named test edit approved 2026-10-15): the four
 * original tests encoded the retired raw-Ed25519 format, which the contract no longer accepts.
 * Each keeps a COSE equivalent covering the same binding or rejection:
 * <ul>
 *   <li>{@code discoversTrustedIssuerWithoutAKeyHint…} → {@link #issuerIsTheCoseKeyAndMustBeTrusted}</li>
 *   <li>{@code explicitIssuerNeverFallsBack…} → {@link #coseKeyNeverFallsBackToAnotherTrustedKey}</li>
 *   <li>{@code acceptsExactRawClaimAndSignature} → {@link #acceptsCip30ClaimUnhashedAndHashed}</li>
 *   <li>{@code rejectsEverySignedBindingAndMalformedEnvelope} → {@link #rejectsEverySignedBindingAndMalformedEnvelope}</li>
 * </ul>
 */
class CmtaAttestationVerifierTest {
    private static final byte[] SEED = new byte[32];
    private static final byte[] SUBJECT = new byte[28];
    private static final String POLICY = "ab".repeat(28);
    private static final long NOW = 1_700_000_000_000L;
    /** A wallet-style protected header: {1: -8, "address": h'60…'}. */
    private static final byte[] PROTECTED = HexUtil.decodeHexString(
            "a201276761646472657373581d6027e38d0e19e3434e33fbd001d3fe04b5b76763f88acd625e0d770b43");
    private static final Ed25519PrivateKeyParameters KEY;
    private static final Ed25519PrivateKeyParameters OTHER_KEY;
    private static final String VKEY;
    private static final String OTHER_VKEY;
    static {
        Arrays.fill(SEED, (byte) 7);
        Arrays.fill(SUBJECT, (byte) 9);
        KEY = new Ed25519PrivateKeyParameters(SEED, 0);
        OTHER_KEY = new Ed25519PrivateKeyParameters(new byte[32], 0);
        VKEY = HexUtil.encodeHexString(KEY.generatePublicKey().getEncoded());
        OTHER_VKEY = HexUtil.encodeHexString(OTHER_KEY.generatePublicKey().getEncoded());
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

    static byte[] cbor(DataItem item) throws Exception {
        var out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    static String coseKey(Ed25519PrivateKeyParameters key) throws Exception {
        Map map = new Map();
        map.put(new UnsignedInteger(1), new UnsignedInteger(1));
        map.put(new UnsignedInteger(3), new NegativeInteger(-8));
        map.put(new NegativeInteger(-1), new UnsignedInteger(6));
        map.put(new NegativeInteger(-2), new ByteString(key.generatePublicKey().getEncoded()));
        return HexUtil.encodeHexString(cbor(map));
    }

    /** What a CIP-30 wallet's signData returns, signed by {@code signer}, presenting {@code key}. */
    static CmtaAttestation signed(byte[] claim, boolean hashed, Ed25519PrivateKeyParameters signer,
                                  Ed25519PrivateKeyParameters key, byte[] protectedHeader,
                                  DataItem coseSign1PayloadOverride) throws Exception {
        byte[] signedBytes = hashed ? Blake2bUtil.blake2bHash224(claim) : claim;
        byte[] message = CoseSign1.sigStructure(protectedHeader, signedBytes);
        Ed25519Signer s = new Ed25519Signer();
        s.init(true, signer);
        s.update(message, 0, message.length);
        Map unprotected = new Map();
        if (hashed) unprotected.put(new UnicodeString("hashed"), SimpleValue.TRUE);
        Array sign1 = new Array()
                .add(new ByteString(protectedHeader))
                .add(unprotected)
                .add(coseSign1PayloadOverride != null ? coseSign1PayloadOverride : new ByteString(signedBytes))
                .add(new ByteString(s.generateSignature()));
        return new CmtaAttestation(HexUtil.encodeHexString(claim),
                HexUtil.encodeHexString(cbor(sign1)), coseKey(key));
    }

    static CmtaAttestation signed(byte[] claim) throws Exception {
        return signed(claim, false, KEY, KEY, PROTECTED, null);
    }

    private static CmtaAttestationVerifier.Verified verify(CmtaAttestation attestation) {
        return CmtaAttestationVerifier.verify(attestation, SUBJECT, (short) 0,
                POLICY, 0, List.of(VKEY), NOW, "receiver");
    }

    @Test
    void issuerIsTheCoseKeyAndMustBeTrusted() throws Exception {
        var att = signed(payload(System.currentTimeMillis() + 600_000));
        var keys = Arrays.asList("not hex", null, "00".repeat(32), OTHER_VKEY, VKEY.toUpperCase(), VKEY);
        var verified = CmtaAttestationVerifier.verify(att, SUBJECT, (short) 0, POLICY, 0, keys, NOW, "receiver");
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), verified.issuerVkey());
        // The issuer key reaches the redeemer as Cip30AttestationProof.issuer_vkey (field 4).
        var proof = RwaTokenModuleHandler.resolveTransferProof("receiver", SUBJECT,
                (short) 0, true, null, null, att, POLICY, 0, keys);
        var inner = (ConstrPlutusData) ((ConstrPlutusData) proof.data()).getData().getPlutusDataList().getFirst();
        assertArrayEquals(KEY.generatePublicKey().getEncoded(),
                ((BytesPlutusData) inner.getData().getPlutusDataList().get(4)).getValue());
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(att,
                SUBJECT, (short) 0, POLICY, 0, List.of(), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(att,
                SUBJECT, (short) 0, POLICY, 0, List.of(OTHER_VKEY), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(att,
                SUBJECT, (short) 0, POLICY, 0, Arrays.asList(null, "not hex"), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(att,
                new byte[28], (short) 0, POLICY, 0, List.of(VKEY), NOW, "receiver"));
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(att,
                SUBJECT, (short) 0, POLICY, 1, List.of(VKEY), NOW, "receiver"));
    }

    @Test
    void coseKeyNeverFallsBackToAnotherTrustedKey() throws Exception {
        var claim = payload(NOW + 600_000);
        // Signed by KEY but presenting OTHER's COSE_Key: rejected even though both are trusted.
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                signed(claim, false, KEY, OTHER_KEY, PROTECTED, null),
                SUBJECT, (short) 0, POLICY, 0, List.of(OTHER_VKEY, VKEY), NOW, "sender"));
        // A missing COSE_Key is not "find the key for me".
        var good = signed(claim);
        assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                new CmtaAttestation(good.payloadHex(), good.coseSign1Hex(), ""),
                SUBJECT, (short) 0, POLICY, 0, List.of(VKEY), NOW, "sender"));
        // A valid signature by a key that is simply not trusted fails on TRUST.
        var untrusted = signed(claim, false, OTHER_KEY, OTHER_KEY, PROTECTED, null);
        var e = assertThrows(IllegalArgumentException.class, () -> CmtaAttestationVerifier.verify(
                untrusted, SUBJECT, (short) 0, POLICY, 0, List.of(VKEY), NOW, "sender"));
        assertTrue(e.getMessage().contains("not a trusted entity"), e.getMessage());
    }

    @Test
    void acceptsCip30ClaimUnhashedAndHashed() throws Exception {
        var claim = payload(NOW + 600_000);
        var verified = verify(signed(claim));
        assertArrayEquals(claim, verified.payload());
        assertArrayEquals(PROTECTED, verified.protectedHeader());
        assertFalse(verified.hashed());
        assertEquals(NOW + 600_000, verified.validUntilMs());
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), verified.issuerVkey());
        // CIP-8 hashed (Ledger): the COSE payload is blake2b_224(claim); the proof still carries the claim.
        var hashed = verify(signed(claim, true, KEY, KEY, PROTECTED, null));
        assertTrue(hashed.hashed());
        assertArrayEquals(claim, hashed.payload());
        // Detached (nil) payload: the flag decides.
        assertTrue(verify(signed(claim, true, KEY, KEY, PROTECTED, SimpleValue.NULL)).hashed());
        assertFalse(verify(signed(claim, false, KEY, KEY, PROTECTED, SimpleValue.NULL)).hashed());

        var currentClaim = payload(System.currentTimeMillis() + 600_000);
        var current = signed(currentClaim, true, KEY, KEY, PROTECTED, null);
        var proof = RwaTokenModuleHandler.resolveTransferProof("receiver", SUBJECT,
                (short) 0, true, null, null, current, POLICY, 0, List.of(VKEY));
        var outer = (ConstrPlutusData) proof.data();
        assertEquals(1, outer.getAlternative(), "KycProof.Cip30Attestation is constructor 1");
        var inner = (ConstrPlutusData) outer.getData().getPlutusDataList().getFirst();
        assertEquals(0, inner.getAlternative());
        var fields = inner.getData().getPlutusDataList();
        assertEquals(5, fields.size());
        assertArrayEquals(PROTECTED, ((BytesPlutusData) fields.get(0)).getValue());
        assertArrayEquals(currentClaim, ((BytesPlutusData) fields.get(1)).getValue());
        assertEquals(1, ((ConstrPlutusData) fields.get(2)).getAlternative(), "hashed = True");
        assertEquals(64, ((BytesPlutusData) fields.get(3)).getValue().length);
        assertArrayEquals(KEY.generatePublicKey().getEncoded(), ((BytesPlutusData) fields.get(4)).getValue());
    }

    @Test
    void rejectsEverySignedBindingAndMalformedEnvelope() throws Exception {
        var valid = signed(payload(NOW + 600_000));
        byte[] tampered = HexUtil.decodeHexString(valid.coseSign1Hex());
        tampered[tampered.length - 1] ^= 1;   // last signature byte
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), HexUtil.encodeHexString(tampered), valid.coseKeyHex())));
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
                "d2" + valid.payloadHex(), valid.coseSign1Hex(), valid.coseKeyHex())));
        byte[] overflow = payload(NOW + 600_000); Arrays.fill(overflow, 29, 37, (byte) 0xff);
        assertThrows(IllegalArgumentException.class, () -> verify(signed(overflow)));
        // Signed over a DIFFERENT claim: the COSE payload matches neither the claim nor its hash.
        byte[] other = payload(NOW + 700_000);
        var mismatched = signed(other);
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), mismatched.coseSign1Hex(), mismatched.coseKeyHex())));
        // Protected-header bounds, as on chain: 1–255 bytes.
        assertThrows(IllegalArgumentException.class, () -> verify(
                signed(payload(NOW + 600_000), false, KEY, KEY, new byte[0], null)));
        assertThrows(IllegalArgumentException.class, () -> verify(
                signed(payload(NOW + 600_000), false, KEY, KEY, new byte[256], null)));
        // Oversized and garbage envelopes are refused before CBOR parsing completes.
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), "00".repeat(1025), valid.coseKeyHex())));
        assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), "84", valid.coseKeyHex())));
        // The retired raw-Ed25519 format is refused BY NAME.
        var raw = assertThrows(IllegalArgumentException.class, () -> verify(new CmtaAttestation(
                valid.payloadHex(), null, null, "00".repeat(64))));
        assertTrue(raw.getMessage().contains("CIP-30"), raw.getMessage());
        assertThrows(IllegalArgumentException.class, () -> RwaTokenModuleHandler.resolveTransferProof(
                "sender", SUBJECT, (short) 0, true, "80", NOW + 600_000,
                valid, POLICY, 0, List.of(VKEY)));
    }
}
