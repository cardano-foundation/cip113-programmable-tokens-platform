package org.cardanofoundation.cip113.service.module;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.cardanofoundation.cip113.model.CmtaAttestation;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Checks the exact raw claim accepted by the pinned CMTA KYC validator. */
final class CmtaAttestationVerifier {
    private CmtaAttestationVerifier() {}

    record Verified(byte[] payload, byte[] signature, byte[] issuerVkey, long validUntilMs) {}

    static Verified verify(CmtaAttestation attestation, byte[] stakeHash, short credentialType,
                           String policyId, long networkId, List<String> trustedVkeys,
                           long nowMs, String party) {
        if (attestation == null) throw new IllegalArgumentException(party + " attestation is required");
        byte[] payload = hex(attestation.payloadHex(), 67, party + " payload");
        byte[] signature = hex(attestation.signatureHex(), 64, party + " signature");
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
        byte[] vkey;
        if (attestation.issuerVkeyHex() != null) {
            vkey = hex(attestation.issuerVkeyHex(), 32, party + " issuer vkey");
            if (trustedVkeys == null || trustedVkeys.stream().noneMatch(key ->
                    key != null && key.equalsIgnoreCase(attestation.issuerVkeyHex())))
                throw new IllegalArgumentException(party + " attestation issuer is not trusted by the live global state");
            if (!signatureVerifies(payload, signature, vkey))
                throw new IllegalArgumentException(party + " attestation signature is invalid");
        } else {
            var candidates = new TreeSet<String>();
            if (trustedVkeys != null) {
                for (String candidate : trustedVkeys) {
                    if (candidate != null && candidate.matches("(?i)[0-9a-f]{64}"))
                        candidates.add(candidate.toLowerCase(Locale.ROOT));
                }
            }
            vkey = null;
            for (String candidate : candidates) {
                byte[] possibleKey = HexUtil.decodeHexString(candidate);
                if (signatureVerifies(payload, signature, possibleKey)) {
                    vkey = possibleKey;
                    break;
                }
            }
            if (vkey == null)
                throw new IllegalArgumentException(party + " signature does not verify under any currently trusted entity");
        }
        return new Verified(payload, signature, vkey, expiry);
    }

    private static boolean signatureVerifies(byte[] payload, byte[] signature, byte[] vkey) {
        Ed25519PublicKeyParameters publicKey;
        try {
            publicKey = new Ed25519PublicKeyParameters(vkey, 0);
        } catch (IllegalArgumentException invalidPublicKey) {
            return false;
        }
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(payload, 0, payload.length);
        return verifier.verifySignature(signature);
    }

    private static byte[] hex(String value, int byteLength, String label) {
        if (value == null || !value.matches("(?i)[0-9a-f]{" + (byteLength * 2) + "}"))
            throw new IllegalArgumentException(label + " must be " + byteLength + " bytes of hex");
        return HexUtil.decodeHexString(value);
    }
}
