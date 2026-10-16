package org.cardanofoundation.cip113.service.cose;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.Tag;
import co.nstant.in.cbor.model.UnicodeString;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared COSE structure against GENUINE CIP-30 vectors from the pinned contract,
 * cpt-rwa-ch-cmta-reference @346f9bf {@code lib/kyc/verify.ak} ({@code cip30_unit_valid},
 * {@code cip30_unit_hashed_valid}; public TEST seed). The hashed vector is independently
 * confirmed upstream against Emurgo's cardano-message-signing 1.1.0. If these verify here, the
 * Sig_structure this platform rebuilds is byte-identical to the one the validator rebuilds.
 */
class CoseSign1Test {
    static final byte[] PROTECTED = HexUtil.decodeHexString(
            "a201276761646472657373581d6027e38d0e19e3434e33fbd001d3fe04b5b76763f88acd625e0d770b43");
    static final byte[] CLAIM = HexUtil.decodeHexString(
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa010000000005f5e100"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb0100");
    static final byte[] UNHASHED_SIG = HexUtil.decodeHexString(
            "b98e5f45c6b54500cf64d816928f8bd81ec095d85c12e642c4204325df16f069"
            + "43e6d8a1ae82796f4dd91fe71caa31e7e2cf5356b7a96d7ba1a1bd10482b2b06");
    static final byte[] HASHED_SIG = HexUtil.decodeHexString(
            "2a51077b605a1e9610a3aede92105263bb225d843a584d5a14412c234c273f30"
            + "97fecc734ee44150c688bf59fc0a35ccd4c42de2e140a12cda572475e5faf60a");
    static final byte[] ISSUER_VKEY = HexUtil.decodeHexString(
            "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8");

    static byte[] cbor(DataItem item) throws Exception {
        var out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    static byte[] sign1(byte[] payload, byte[] sig, boolean hashedFlag, boolean tag18) throws Exception {
        Map unprotected = new Map();
        if (hashedFlag) unprotected.put(new UnicodeString("hashed"), SimpleValue.TRUE);
        Array a = new Array().add(new ByteString(PROTECTED)).add(unprotected)
                .add(payload == null ? SimpleValue.NULL : new ByteString(payload)).add(new ByteString(sig));
        if (tag18) a.setTag(new Tag(18));
        return cbor(a);
    }

    @Test
    void genuineUnhashedVectorVerifies() throws Exception {
        var s = CoseSign1.decode(sign1(CLAIM, UNHASHED_SIG, false, false));
        assertArrayEquals(PROTECTED, s.protectedHeader());
        assertArrayEquals(CLAIM, s.payload());
        assertFalse(s.hashedFlag());
        assertTrue(s.verifies(ISSUER_VKEY, CLAIM));
        assertFalse(s.verifies(ISSUER_VKEY, Blake2bUtil.blake2bHash224(CLAIM)), "the mode is part of what was signed");
    }

    @Test
    void genuineHashedVectorVerifiesOverBlake2b224() throws Exception {
        byte[] digest = Blake2bUtil.blake2bHash224(CLAIM);
        var s = CoseSign1.decode(sign1(digest, HASHED_SIG, true, true));
        assertTrue(s.hashedFlag());
        assertTrue(s.verifies(ISSUER_VKEY, digest));
        assertFalse(s.verifies(ISSUER_VKEY, CLAIM));
    }

    @Test
    void tamperingAnyPartFailsVerification() throws Exception {
        byte[] badSig = UNHASHED_SIG.clone();
        badSig[0] ^= 1;
        assertFalse(CoseSign1.decode(sign1(CLAIM, badSig, false, false)).verifies(ISSUER_VKEY, CLAIM));
        byte[] badClaim = CLAIM.clone();
        badClaim[28] = 2;
        assertFalse(CoseSign1.decode(sign1(CLAIM, UNHASHED_SIG, false, false)).verifies(ISSUER_VKEY, badClaim));
        byte[] otherKey = ISSUER_VKEY.clone();
        otherKey[0] ^= 1;
        assertFalse(CoseSign1.decode(sign1(CLAIM, UNHASHED_SIG, false, false)).verifies(otherKey, CLAIM));
    }

    @Test
    void detachedPayloadAndShapeRules() throws Exception {
        assertNull(CoseSign1.decode(sign1(null, UNHASHED_SIG, false, false)).payload());
        assertThrows(IllegalArgumentException.class, () -> CoseSign1.decode(sign1(CLAIM, new byte[63], false, false)));
        Array three = new Array().add(new ByteString(PROTECTED)).add(new Map()).add(new ByteString(CLAIM));
        assertThrows(IllegalArgumentException.class, () -> CoseSign1.decode(cbor(three)));
        Array wrongTag = (Array) new Array().add(new ByteString(PROTECTED)).add(new Map())
                .add(new ByteString(CLAIM)).add(new ByteString(UNHASHED_SIG));
        wrongTag.setTag(new Tag(17));
        assertThrows(IllegalArgumentException.class, () -> CoseSign1.decode(cbor(wrongTag)));
        Map badFlag = new Map();
        badFlag.put(new UnicodeString("hashed"), new UnicodeString("yes"));
        Array a = new Array().add(new ByteString(PROTECTED)).add(badFlag)
                .add(new ByteString(CLAIM)).add(new ByteString(UNHASHED_SIG));
        assertThrows(IllegalArgumentException.class, () -> CoseSign1.decode(cbor(a)).hashedFlag());
        assertThrows(IllegalArgumentException.class, () -> CoseSign1.decode(HexUtil.decodeHexString("8400")));
    }
}
