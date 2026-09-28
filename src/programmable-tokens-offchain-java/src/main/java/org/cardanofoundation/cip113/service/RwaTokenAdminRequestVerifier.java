package org.cardanofoundation.cip113.service;

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
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.RwaTokenAdminRequestNonceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;

/** CIP-30 signData authentication for off-chain CMTA admin API calls only. */
@Service
@RequiredArgsConstructor
public class RwaTokenAdminRequestVerifier {
    private final RwaTokenAdminRequestNonceRepository nonces;
    private final AppConfig.Network network;

    public static String canonicalBody(String payer, RwaTokenAllowlistService.MemberLeaf manual,
                                       java.util.List<RwaTokenAllowlistService.MemberLeaf> pending) {
        StringBuilder out = new StringBuilder("payer=").append(payer.toLowerCase(java.util.Locale.ROOT)).append('\n');
        out.append("manual=").append(manual == null ? "-" : tuple(manual)).append('\n');
        java.util.List<String> tuples = pending.stream().map(RwaTokenAdminRequestVerifier::tuple).sorted().toList();
        out.append("pending=").append(tuples.isEmpty() ? "-" : String.join(",", tuples)).append('\n');
        return out.toString();
    }

    private static String tuple(RwaTokenAllowlistService.MemberLeaf leaf) {
        if (leaf.credentialHash() == null || !leaf.credentialHash().matches("(?i)[0-9a-f]{56}")
                || (leaf.credentialType() != 0 && leaf.credentialType() != 1)
                || leaf.validUntilMs() < 0) throw bad("invalid member tuple");
        return leaf.credentialType() + ":" + leaf.credentialHash().toLowerCase(java.util.Locale.ROOT)
                + ":" + leaf.validUntilMs();
    }

    public static String payload(String network, String gsPolicy, String method, String path,
                                 String canonicalBody, String nonce, long issued, long expires) {
        if (!network.matches("[a-z0-9-]{1,24}") || !gsPolicy.matches("(?i)[0-9a-f]{56}")
                || !(method.equals("GET") || method.equals("POST"))
                || !path.matches("/rwa-token/[0-9a-f]{56}/(members|update-member-root-hash)")
                || !nonce.matches("[0-9a-f]{64}")) throw bad("invalid signed request fields");
        return "CMTA admin API v1\n"
                + "audience=" + network + ":" + gsPolicy.toLowerCase(java.util.Locale.ROOT) + "\n"
                + "method=" + method + "\n"
                + "path=" + path + "\n"
                + "body-sha256=" + sha256(canonicalBody.getBytes(StandardCharsets.UTF_8)) + "\n"
                + "nonce=" + nonce + "\n"
                + "issued=" + issued + "\n"
                + "expires=" + expires + "\n";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyAndConsume(String tokenPolicyId, String gsPolicyId, String adminHash,
                                 String method, String path, String canonicalBody, HttpHeaders headers) {
        RequestWindow window = requestWindow(headers);
        String expected = payload(network.getNetwork(), gsPolicyId, method, path,
                canonicalBody, window.nonce(), window.issued(), window.expires());
        byte[] address = verifySignedRequest(network.getNetwork(), expected, headers);
        if (!HexUtil.encodeHexString(Arrays.copyOfRange(address, 1, 29)).equalsIgnoreCase(adminHash)) {
            throw unauthorized("CIP-30 signer is not the live GS admin");
        }
        if (nonces.consume(window.nonce(), tokenPolicyId, adminHash.toLowerCase(java.util.Locale.ROOT),
                Instant.ofEpochMilli(window.expires())) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "admin request nonce was already used");
        }
    }

    record RequestWindow(String nonce, long issued, long expires) { }

    static RequestWindow requestWindow(HttpHeaders headers) {
        String nonce = required(headers, "X-CMTA-Nonce").toLowerCase(java.util.Locale.ROOT);
        if (!nonce.matches("[0-9a-f]{64}")) throw unauthorized("invalid request nonce");
        long issued = parseTime(required(headers, "X-CMTA-Issued"));
        long expires = parseTime(required(headers, "X-CMTA-Expires"));
        long now = System.currentTimeMillis();
        if (issued > now + 30_000L || issued < now - 300_000L
                || expires <= issued || expires > issued + 300_000L || expires < now) {
            throw unauthorized("request signature expired or not yet valid");
        }
        return new RequestWindow(nonce, issued, expires);
    }

    /** Verifies the COSE envelope and that its key controls the signed payment address. */
    static byte[] verifySignedRequest(String network, String expected, HttpHeaders headers) {
        try {
            byte[] address = strictHex(required(headers, "X-CMTA-Address"), 57);
            byte[] signedAddress = verifyCose(required(headers, "X-CMTA-Signature"),
                    required(headers, "X-CMTA-Key"), expected.getBytes(StandardCharsets.UTF_8));
            if (!Arrays.equals(address, signedAddress)) throw unauthorized("signed wallet address differs from request");
            int addressType = (address[0] >>> 4) & 15;
            int networkId = address[0] & 15;
            int expectedNetworkId = "mainnet".equals(network) ? 1 : 0;
            boolean validLength = (addressType == 0 || addressType == 2) && address.length == 57
                    || addressType == 4 && address.length > 29 && address.length <= 57
                    || addressType == 6 && address.length == 29;
            if (!validLength || networkId != expectedNetworkId) {
                throw unauthorized("CIP-30 signature must use a payment-key address on this network");
            }
            byte[] pubkey = cosePublicKey(required(headers, "X-CMTA-Key"));
            Blake2bDigest digest = new Blake2bDigest(224);
            digest.update(pubkey, 0, pubkey.length);
            byte[] keyHash = new byte[28];
            digest.doFinal(keyHash, 0);
            if (!Arrays.equals(keyHash, Arrays.copyOfRange(address, 1, 29))) {
                throw unauthorized("CIP-30 key does not control the signed payment address");
            }
            return address;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw unauthorized("invalid CIP-30 signature");
        }
    }

    private static String required(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank() || value.length() > 16_384) throw unauthorized("missing admin authentication header");
        return value;
    }

    private static long parseTime(String value) {
        if (!value.matches("[0-9]{13}")) throw unauthorized("invalid admin request timestamp");
        return Long.parseLong(value);
    }

    private static byte[] strictHex(String value, int maxBytes) {
        if (value.length() % 2 != 0 || value.length() > maxBytes * 2 || !value.matches("(?i)[0-9a-f]+"))
            throw unauthorized("malformed authentication hex");
        return HexUtil.decodeHexString(value);
    }

    private static DataItem decodeOne(byte[] cbor) throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(cbor);
        DataItem result = new CborDecoder(input).decodeNext();
        if (result == null || input.available() != 0) throw new IllegalArgumentException("trailing or empty CBOR");
        return result;
    }

    private static byte[] encode(DataItem item) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(item);
        return out.toByteArray();
    }

    private static Map strictMap(byte[] cbor) throws Exception {
        DataItem item = decodeOne(cbor);
        if (!(item instanceof Map map) || !Arrays.equals(cbor, encode(map)))
            throw new IllegalArgumentException("noncanonical or duplicate COSE map");
        return map;
    }

    private static long number(DataItem item) {
        if (!(item instanceof co.nstant.in.cbor.model.Number number)) throw unauthorized("invalid COSE number");
        return number.getValue().longValueExact();
    }

    private static byte[] cosePublicKey(String keyHex) throws Exception {
        Map key = strictMap(strictHex(keyHex, 2048));
        if (number(key.get(new UnsignedInteger(1))) != 1
                || number(key.get(new UnsignedInteger(3))) != -8
                || number(key.get(new NegativeInteger(-1))) != 6
                || !(key.get(new NegativeInteger(-2)) instanceof ByteString x)
                || x.getBytes().length != 32) throw unauthorized("unsupported COSE key");
        return x.getBytes();
    }

    private static byte[] verifyCose(String signatureHex, String keyHex, byte[] expectedPayload) throws Exception {
        DataItem item = decodeOne(strictHex(signatureHex, 8192));
        if (!(item instanceof Array sign1) || sign1.getDataItems().size() != 4
                || (item.hasTag() && item.getTag().getValue() != 18)) throw unauthorized("invalid COSE_Sign1");
        var parts = sign1.getDataItems();
        if (!(parts.get(0) instanceof ByteString protectedBytes)
                || !(parts.get(1) instanceof Map unprotected)
                || !(parts.get(2) instanceof ByteString payload)
                || !(parts.get(3) instanceof ByteString signature)
                || signature.getBytes().length != 64
                || !Arrays.equals(payload.getBytes(), expectedPayload)) throw unauthorized("COSE payload mismatch");
        Map protectedMap = strictMap(protectedBytes.getBytes());
        if (number(protectedMap.get(new UnsignedInteger(1))) != -8
                || !(protectedMap.get(new UnicodeString("address")) instanceof ByteString signedAddress)
                || protectedMap.get(new UnsignedInteger(2)) != null
                || unprotected.get(new UnsignedInteger(2)) != null
                || unprotected.get(new UnicodeString("address")) != null
                || unprotected.get(new UnsignedInteger(1)) != null
                || protectedMap.get(new UnicodeString("hashed")) != null
                || (unprotected.get(new UnicodeString("hashed")) != null
                    && !SimpleValue.FALSE.equals(unprotected.get(new UnicodeString("hashed"))))) {
            throw unauthorized("unsupported COSE headers");
        }
        Array structure = new Array()
                .add(new UnicodeString("Signature1"))
                .add(new ByteString(protectedBytes.getBytes()))
                .add(new ByteString(new byte[0]))
                .add(new ByteString(expectedPayload));
        byte[] message = encode(structure);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, new Ed25519PublicKeyParameters(cosePublicKey(keyHex), 0));
        verifier.update(message, 0, message.length);
        if (!verifier.verifySignature(signature.getBytes())) throw unauthorized("invalid Ed25519 signature");
        return signedAddress.getBytes();
    }

    static String sha256(byte[] data) {
        try { return HexUtil.encodeHexString(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    static ResponseStatusException unauthorized(String reason) {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
