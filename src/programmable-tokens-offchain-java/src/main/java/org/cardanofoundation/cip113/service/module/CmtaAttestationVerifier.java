package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.CmtaAttestation;
import org.cardanofoundation.cip113.service.cose.CoseSign1;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Checks a CIP-30 KYC attestation exactly as the PR #29 CMTA validator will
 * ({@code lib/kyc/verify.ak} {@code verify_cip30_attestation_proof}), so a proof that passes
 * here does not fail on chain for a reason this check could have named.
 *
 * <p>Nothing extracted from the COSE envelope is trusted: the signature is verified over the
 * Sig_structure rebuilt from OUR claim (or its Blake2b-224 in hashed mode), so a wrong extraction
 * can only fail closed — the same property the validator relies on.
 */
final class CmtaAttestationVerifier {
    private CmtaAttestationVerifier() {}

    /** Upper bound on the protected header, as on chain ({@code max_protected_header_length}). */
    static final int MAX_PROTECTED_HEADER_LENGTH = 255;
    /** Size cap on each COSE hex field before CBOR parsing: real wallets produce ~500 hex chars. */
    static final int MAX_COSE_HEX_LENGTH = 2048;

    /** The parts the validator's {@code Cip30AttestationProof} carries. */
    record Verified(byte[] payload, byte[] protectedHeader, boolean hashed, byte[] signature,
                    byte[] issuerVkey, long validUntilMs) {}

    static Verified verify(CmtaAttestation attestation, byte[] stakeHash, short credentialType,
                           String policyId, long networkId, List<String> trustedVkeys,
                           long nowMs, String party) {
        if (attestation == null) throw new IllegalArgumentException(party + " attestation is required");
        if ((attestation.coseSign1Hex() == null || attestation.coseSign1Hex().isBlank())
                && attestation.signatureHex() != null && !attestation.signatureHex().isBlank())
            throw new IllegalArgumentException(party + " attestation is a raw Ed25519 signature, which the "
                    + "contract no longer accepts: sign the claim with CIP-30 signData and send its "
                    + "COSE_Sign1 and COSE_Key");
        byte[] payload = hex(attestation.payloadHex(), 67, party + " payload");
        if (stakeHash.length != 28 || !Arrays.equals(stakeHash, Arrays.copyOfRange(payload, 0, 28)))
            throw new IllegalArgumentException(party + " attestation subject does not match the stake credential");
        if ((payload[28] & 0xff) == 0)
            throw new IllegalArgumentException(party + " attestation has invalid KYC tier");
        long expiry;
        try {
            expiry = new BigInteger(1, Arrays.copyOfRange(payload, 29, 37)).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(party + " attestation expiry is out of range", e);
        }
        if (expiry <= nowMs)
            throw new IllegalArgumentException(party + " attestation has expired");
        if (!Arrays.equals(hex(policyId, 28, "token policy"), Arrays.copyOfRange(payload, 37, 65)))
            throw new IllegalArgumentException(party + " attestation is for a different token");
        if (networkId < 0 || networkId > 255 || (payload[65] & 0xff) != networkId)
            throw new IllegalArgumentException(party + " attestation is for a different network");
        if ((payload[66] & 0xff) != credentialType)
            throw new IllegalArgumentException(party + " attestation credential type does not match the address");

        CoseSign1 sign1;
        byte[] vkey;
        try {
            sign1 = CoseSign1.decode(boundedHex(attestation.coseSign1Hex(), party + " COSE_Sign1"));
            vkey = CoseSign1.ed25519Key(boundedHex(attestation.coseKeyHex(), party + " COSE_Key"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(party + " attestation: " + e.getMessage(), e);
        }
        int headerLength = sign1.protectedHeader().length;
        if (headerLength < 1 || headerLength > MAX_PROTECTED_HEADER_LENGTH)
            throw new IllegalArgumentException(party + " attestation protected header is " + headerLength
                    + " bytes; the contract accepts 1–" + MAX_PROTECTED_HEADER_LENGTH);

        // Which bytes were signed. The COSE payload, when present, must be the claim or its
        // Blake2b-224 — and that, not the unprotected (unsigned) flag, decides the mode, exactly
        // as the auth verifier does. A detached payload falls back to the flag.
        byte[] digest = Blake2bUtil.blake2bHash224(payload);
        boolean hashed;
        if (sign1.payload() == null) {
            hashed = flag(sign1, party);
        } else if (Arrays.equals(sign1.payload(), payload)) {
            hashed = false;
        } else if (Arrays.equals(sign1.payload(), digest)) {
            hashed = true;
        } else {
            throw new IllegalArgumentException(party + " attestation was signed over a different claim ("
                    + sign1.payload().length + " signed bytes match neither this 67-byte claim nor its "
                    + "Blake2b-224); sign the current claim again");
        }
        if (!sign1.verifies(vkey, hashed ? digest : payload))
            throw new IllegalArgumentException(party + " attestation signature is invalid");
        String vkeyHex = HexUtil.encodeHexString(vkey);
        if (trustedVkeys == null || trustedVkeys.stream().noneMatch(key -> key != null && key.equalsIgnoreCase(vkeyHex)))
            throw new IllegalArgumentException(party + " attestation was signed by " + vkeyHex
                    + ", which is not a trusted entity in the live global state");
        return new Verified(payload, sign1.protectedHeader(), hashed, sign1.signature(), vkey, expiry);
    }

    private static boolean flag(CoseSign1 sign1, String party) {
        try {
            return sign1.hashedFlag();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(party + " attestation: " + e.getMessage(), e);
        }
    }

    private static byte[] boundedHex(String value, String label) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(label + " is required");
        String v = value.trim();
        if (v.length() > MAX_COSE_HEX_LENGTH || v.length() % 2 != 0 || !v.matches("(?i)[0-9a-f]+"))
            throw new IllegalArgumentException(label + " must be at most " + MAX_COSE_HEX_LENGTH + " hex characters");
        return HexUtil.decodeHexString(v);
    }

    private static byte[] hex(String value, int byteLength, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{" + (byteLength * 2) + "}"))
            throw new IllegalArgumentException(label + " must be " + byteLength + " bytes of hex");
        return HexUtil.decodeHexString(value);
    }
}
