package org.cardanofoundation.cip113.service.cose;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * The STRUCTURE of a CIP-30 {@code signData} result: a COSE_Sign1 (RFC 8152 §4.2) and its
 * Ed25519 COSE_Key. Shared by API authentication and KYC attestations.
 *
 * <p>Only shape is decided here. Header POLICY stays with each caller, because the two differ
 * on purpose: authentication demands a canonical protected map carrying the signed address,
 * while an attestation takes the protected header verbatim — the CMTA validator never parses
 * it, only bounds its length and covers it by the signature.
 *
 * <p>Every rejection is an {@link IllegalArgumentException}; callers map it to their own error.
 */
public record CoseSign1(byte[] protectedHeader, Map unprotected, byte[] payload, byte[] signature) {

    /** Decode a COSE_Sign1, optionally wrapped in CBOR tag 18. {@code payload} is null when the
     *  wallet returned a detached ({@code nil}) payload. */
    public static CoseSign1 decode(byte[] cbor) {
        DataItem item = decodeOne(cbor);
        if (!(item instanceof Array sign1) || sign1.getDataItems().size() != 4
                || (item.hasTag() && item.getTag().getValue() != 18)) throw new IllegalArgumentException("invalid COSE_Sign1");
        var parts = sign1.getDataItems();
        if (!(parts.get(0) instanceof ByteString protectedBytes)
                || !(parts.get(1) instanceof Map unprotected)
                || !(parts.get(3) instanceof ByteString signature)
                || signature.getBytes().length != 64) throw new IllegalArgumentException("invalid COSE_Sign1");
        byte[] payload;
        if (parts.get(2) instanceof ByteString p) payload = p.getBytes();
        else if (SimpleValue.NULL.equals(parts.get(2))) payload = null;
        else throw new IllegalArgumentException("invalid COSE_Sign1");
        return new CoseSign1(protectedBytes.getBytes(), unprotected, payload, signature.getBytes());
    }

    /**
     * The CIP-8 {@code hashed} flag from the unprotected header; absent means false.
     *
     * <p>Strict about the TYPE: a non-boolean is a malformed record rather than a hint, and
     * treating anything truthy as {@code true} would let an odd encoding pick the verification
     * path. Only the two CBOR simple values are accepted.
     */
    public boolean hashedFlag() {
        return hashedFlag(unprotected);
    }

    public static boolean hashedFlag(Map unprotected) {
        DataItem flag = unprotected.get(new UnicodeString("hashed"));
        if (flag == null || SimpleValue.FALSE.equals(flag)) return false;
        if (SimpleValue.TRUE.equals(flag)) return true;
        throw new IllegalArgumentException("COSE `hashed` header is not a boolean");
    }

    /** The bytes a CIP-30 wallet signs: {@code ["Signature1", protected, h'', signedPayload]}. */
    public static byte[] sigStructure(byte[] protectedHeader, byte[] signedPayload) {
        return encode(new Array()
                .add(new UnicodeString("Signature1"))
                .add(new ByteString(protectedHeader))
                .add(new ByteString(new byte[0]))
                .add(new ByteString(signedPayload)));
    }

    /** Ed25519-verify this signature over the Sig_structure wrapping {@code signedPayload}. */
    public boolean verifies(byte[] vkey, byte[] signedPayload) {
        Ed25519PublicKeyParameters key;
        try {
            key = new Ed25519PublicKeyParameters(vkey, 0);
        } catch (IllegalArgumentException invalidKey) {
            return false;
        }
        byte[] message = sigStructure(protectedHeader, signedPayload);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, key);
        verifier.update(message, 0, message.length);
        return verifier.verifySignature(signature);
    }

    /**
     * The 32-byte Ed25519 key of a CIP-30 COSE_Key ({@code {1: 1 OKP, 3: -8 EdDSA, -1: 6 Ed25519,
     * -2: x}}), refusing a non-canonical or duplicate-key map.
     */
    public static byte[] ed25519Key(byte[] cbor) {
        Map key = canonicalMap(cbor);
        if (number(key.get(new UnsignedInteger(1))) != 1
                || number(key.get(new UnsignedInteger(3))) != -8
                || number(key.get(new NegativeInteger(-1))) != 6
                || !(key.get(new NegativeInteger(-2)) instanceof ByteString x)
                || x.getBytes().length != 32) throw new IllegalArgumentException("unsupported COSE key");
        return x.getBytes();
    }

    /** Decode a map and require that re-encoding it reproduces the input exactly. */
    public static Map canonicalMap(byte[] cbor) {
        DataItem item = decodeOne(cbor);
        if (!(item instanceof Map map) || !Arrays.equals(cbor, encode(map)))
            throw new IllegalArgumentException("noncanonical or duplicate COSE map");
        return map;
    }

    public static long number(DataItem item) {
        if (!(item instanceof co.nstant.in.cbor.model.Number number)) throw new IllegalArgumentException("invalid COSE number");
        return number.getValue().longValueExact();
    }

    static DataItem decodeOne(byte[] cbor) {
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(cbor);
            DataItem result = new CborDecoder(input).decodeNext();
            if (result == null || input.available() != 0) throw new IllegalArgumentException("trailing or empty CBOR");
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid CBOR", e);
        }
    }

    static byte[] encode(DataItem item) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new CborEncoder(out).encode(item);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("CBOR encoding failed", e);
        }
    }
}
