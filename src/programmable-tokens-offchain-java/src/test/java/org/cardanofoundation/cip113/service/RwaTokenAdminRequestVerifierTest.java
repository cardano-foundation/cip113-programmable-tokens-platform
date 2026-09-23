package org.cardanofoundation.cip113.service;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.util.HexUtil;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.RwaTokenAdminRequestNonceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RwaTokenAdminRequestVerifierTest {
    private static final String TOKEN = "ab".repeat(28);
    private static final String GS = "cd".repeat(28);
    private static final String PATH = "/rwa-token/" + TOKEN + "/members";
    private static final String NONCE = "01".repeat(32);
    private final Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(new byte[32], 0);
    private final byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    private final byte[] address = new byte[29];
    private final RwaTokenAdminRequestNonceRepository repo = mock(RwaTokenAdminRequestNonceRepository.class);
    private final RwaTokenAdminRequestVerifier verifier;
    private String adminHash;

    RwaTokenAdminRequestVerifierTest() {
        AppConfig.Network network = new AppConfig.Network("preview");
        verifier = new RwaTokenAdminRequestVerifier(repo, network);
        address[0] = 0x60;
        Blake2bDigest digest = new Blake2bDigest(224);
        digest.update(publicKey, 0, publicKey.length);
        byte[] hash = new byte[28];
        digest.doFinal(hash, 0);
        System.arraycopy(hash, 0, address, 1, 28);
        adminHash = HexUtil.encodeHexString(hash);
    }

    @BeforeEach
    void allowFreshNonce() {
        when(repo.consume(any(), any(), any(), any())).thenReturn(1);
    }

    private static byte[] encode(DataItem value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(value);
        return out.toByteArray();
    }

    private HttpHeaders signedHeaders(String body, String method, String path,
                                      long issued, long expires) throws Exception {
        byte[] payload = RwaTokenAdminRequestVerifier.payload("preview", GS, method, path,
                body, NONCE, issued, expires).getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes = encode(new Map()
                .put(new UnsignedInteger(1), new NegativeInteger(-8))
                .put(new UnicodeString("address"), new ByteString(address)));
        byte[] structure = encode(new Array()
                .add(new UnicodeString("Signature1"))
                .add(new ByteString(protectedBytes))
                .add(new ByteString(new byte[0]))
                .add(new ByteString(payload)));
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(structure, 0, structure.length);
        byte[] signature = signer.generateSignature();
        byte[] sign1 = encode(new Array()
                .add(new ByteString(protectedBytes))
                .add(new Map())
                .add(new ByteString(payload))
                .add(new ByteString(signature)));
        byte[] key = encode(new Map()
                .put(new UnsignedInteger(1), new UnsignedInteger(1))
                .put(new UnsignedInteger(3), new NegativeInteger(-8))
                .put(new NegativeInteger(-1), new UnsignedInteger(6))
                .put(new NegativeInteger(-2), new ByteString(publicKey)));
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-CMTA-Address", HexUtil.encodeHexString(address));
        headers.set("X-CMTA-Nonce", NONCE);
        headers.set("X-CMTA-Issued", String.valueOf(issued));
        headers.set("X-CMTA-Expires", String.valueOf(expires));
        headers.set("X-CMTA-Signature", HexUtil.encodeHexString(sign1));
        headers.set("X-CMTA-Key", HexUtil.encodeHexString(key));
        return headers;
    }

    private void verify(String body, String path, String admin, HttpHeaders headers) {
        verifier.verifyAndConsume(TOKEN, GS, admin, "GET", path, body, headers);
    }

    @Test
    void validCip30SignatureAuthenticatesOnlyExactRequest() throws Exception {
        long issued = System.currentTimeMillis();
        HttpHeaders headers = signedHeaders("", "GET", PATH, issued, issued + 300_000);
        assertDoesNotThrow(() -> verify("", PATH, adminHash, headers));
        org.mockito.Mockito.verify(repo).consume(eq(NONCE), eq(TOKEN), eq(adminHash), any());
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify("changed", PATH, adminHash, headers));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify("", PATH.replace("members", "update-member-root-hash"), adminHash, headers));
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify("", PATH, "ff".repeat(28), headers));
    }

    @Test
    void expiredAndReplayedSignaturesAreRejected() throws Exception {
        long issued = System.currentTimeMillis();
        HttpHeaders headers = signedHeaders("", "GET", PATH, issued, issued + 300_000);
        when(repo.consume(any(), any(), any(), any())).thenReturn(0);
        assertStatus(HttpStatus.CONFLICT, () -> verify("", PATH, adminHash, headers));
        HttpHeaders expired = signedHeaders("", "GET", PATH, issued - 400_000, issued - 100_000);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify("", PATH, adminHash, expired));
    }

    private static void assertStatus(HttpStatus status, Runnable task) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, task::run);
        assertEquals(status, error.getStatusCode());
    }
}
