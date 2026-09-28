package org.cardanofoundation.cip113.service;

import co.nstant.in.cbor.CborEncoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.RwaTokenCreationRequestNonceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RwaTokenCreationRequestVerifierTest {
    private static final String AUDIENCE = "https://issuer.example/api/v1";
    private static final String PATH = "/rwa-token/build-chain";
    private static final String NONCE = "01".repeat(32);
    private static final byte[] BODY = "{\"assetName\":\"42\",\"initialTrustedEntityVkeys\":[]}".getBytes(StandardCharsets.UTF_8);
    private final Ed25519PrivateKeyParameters privateKey = new Ed25519PrivateKeyParameters(new byte[32], 0);
    private final byte[] publicKey = privateKey.generatePublicKey().getEncoded();
    private final byte[] address = new byte[57];
    private final RwaTokenCreationRequestNonceRepository repo = mock(RwaTokenCreationRequestNonceRepository.class);
    private final RwaTokenCreationRequestVerifier verifier = new RwaTokenCreationRequestVerifier(
            repo, new AppConfig.Network("preview"), AUDIENCE);
    private long issued;
    private long expires;

    @BeforeEach
    void setup() {
        Blake2bDigest digest = new Blake2bDigest(224);
        digest.update(publicKey, 0, publicKey.length);
        byte[] hash = new byte[28];
        digest.doFinal(hash, 0);
        System.arraycopy(hash, 0, address, 1, 28);
        issued = System.currentTimeMillis();
        expires = issued + 300_000;
        when(repo.consume(any(), any(), any())).thenReturn(1);
    }

    @Test
    void bothCreationPathsAcceptOnlyTheirExactBodyAndConsumeNonce() throws Exception {
        for (String path : new String[]{PATH, "/rwa-token/init"}) {
            HttpHeaders headers = sign(payload(path), null);
            assertDoesNotThrow(() -> verify(path, BODY, address, headers));
            assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(path,
                    "{\"assetName\":\"43\",\"initialTrustedEntityVkeys\":[]}".getBytes(StandardCharsets.UTF_8), address, headers));
        }
        org.mockito.Mockito.verify(repo, times(2)).consume(eq(NONCE),
                eq(HexUtil.encodeHexString(Arrays.copyOfRange(address, 1, 29))), eq(Instant.ofEpochMilli(expires)));
    }

    @Test
    void rejectsCrossEndpointNetworkMethodAudienceAndDomainReplay() throws Exception {
        String correct = payload(PATH);
        for (String altered : new String[]{
                payload("/rwa-token/init"), correct.replace("network=preview", "network=preprod"),
                correct.replace("method=POST", "method=GET"),
                correct.replace("audience=" + AUDIENCE, "audience=https://other.example/api/v1"),
                correct.replace("CMTA creation API v1", "CMTA admin API v1")}) {
            HttpHeaders headers = sign(altered, null);
            assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, headers));
        }
        verifyNoInteractions(repo);
    }

    @Test
    void signatureCannotReserveOnAnotherDeploymentOnTheSameNetwork() throws Exception {
        HttpHeaders headers = sign(payload(PATH), null);
        assertDoesNotThrow(() -> verify(PATH, BODY, address, headers));
        var otherNonces = mock(RwaTokenCreationRequestNonceRepository.class);
        var otherDeployment = new RwaTokenCreationRequestVerifier(otherNonces,
                new AppConfig.Network("preview"), "https://other.example/api/v1");
        assertStatus(HttpStatus.UNAUTHORIZED, () -> otherDeployment.verifyCreationAndConsume(
                PATH, BODY, new Address(address).toBech32(), headers));
        verifyNoInteractions(otherNonces);
    }

    @Test
    void invalidDeploymentAudienceFailsDuringConstruction() {
        for (String invalid : new String[]{null, "", " ", " issuer", "issuer\nnetwork=mainnet", "é", "a".repeat(201),
                "preview", "cmta:preview", "https://issuer.example/api/v1?network=preview",
                "https://issuer.example/other", "https://user@issuer.example/api/v1"}) {
            assertThrows(IllegalArgumentException.class, () -> new RwaTokenCreationRequestVerifier(
                    repo, new AppConfig.Network("preview"), invalid));
        }
        assertDoesNotThrow(() -> new RwaTokenCreationRequestVerifier(repo,
                new AppConfig.Network("preview"), "http://localhost:8080/api/v1"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new RwaTokenCreationRequestVerifier(
                repo, new AppConfig.Network("preview"), "")).getMessage()
                .contains("RWA_TOKEN_CREATION_AUDIENCE"));
    }

    @Test
    void requiresExactPayerAddressIncludingStakeCredential() throws Exception {
        HttpHeaders headers = sign(payload(PATH), null);
        byte[] otherStake = address.clone();
        otherStake[56] = 1;
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, otherStake, headers));
        byte[] otherPayment = address.clone();
        otherPayment[1] ^= 1;
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, otherPayment, headers));
        verifyNoInteractions(repo);
    }

    @Test
    void rejectsMissingAuthenticationAndWrongAddressNetworkOrCredential() throws Exception {
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, new HttpHeaders()));
        address[0] = 1; // mainnet address on preview
        HttpHeaders wrongNetwork = sign(payload(PATH), null);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, wrongNetwork));
        address[0] = 0x10; // script payment credential
        HttpHeaders scriptAddress = sign(payload(PATH), null);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, scriptAddress));
        address[0] = 0;
        address[1] ^= 1; // signature key no longer controls payment credential
        HttpHeaders wrongKey = sign(payload(PATH), null);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, wrongKey));
        verifyNoInteractions(repo);
    }

    @Test
    void supportsCip8HashedFalseButRejectsTrueAndInvalidValues() throws Exception {
        assertDoesNotThrow(() -> verify(PATH, BODY, address, sign(payload(PATH), SimpleValue.FALSE)));
        for (DataItem invalid : new DataItem[]{SimpleValue.TRUE, new UnicodeString("false"), new UnsignedInteger(0)}) {
            HttpHeaders headers = sign(payload(PATH), invalid);
            assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, headers));
        }
        org.mockito.Mockito.verify(repo, times(1)).consume(any(), any(), any());
    }

    @Test
    void rejectsExpiredAndFutureSignaturesWithoutConsuming() throws Exception {
        issued = System.currentTimeMillis() - 400_000;
        expires = issued + 300_000;
        HttpHeaders expired = sign(payload(PATH), null);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, expired));
        issued = System.currentTimeMillis() + 60_000;
        expires = issued + 300_000;
        HttpHeaders future = sign(payload(PATH), null);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify(PATH, BODY, address, future));
        verifyNoInteractions(repo);
    }

    @Test
    void atomicConsumptionConflictRejectsReplay() throws Exception {
        when(repo.consume(any(), any(), any())).thenReturn(1, 0);
        HttpHeaders headers = sign(payload(PATH), null);
        assertDoesNotThrow(() -> verify(PATH, BODY, address, headers));
        assertStatus(HttpStatus.CONFLICT, () -> verify(PATH, BODY, address, headers));
    }

    private String payload(String path) {
        return RwaTokenCreationRequestVerifier.payload(AUDIENCE, "preview", path, BODY, NONCE, issued, expires);
    }

    private void verify(String path, byte[] body, byte[] payer, HttpHeaders headers) {
        verifier.verifyCreationAndConsume(path, body, new Address(payer).toBech32(), headers);
    }

    private HttpHeaders sign(String text, DataItem hashed) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] protectedBytes = encode(new Map()
                .put(new UnsignedInteger(1), new NegativeInteger(-8))
                .put(new UnicodeString("address"), new ByteString(address)));
        byte[] structure = encode(new Array().add(new UnicodeString("Signature1"))
                .add(new ByteString(protectedBytes)).add(new ByteString(new byte[0])).add(new ByteString(payload)));
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(structure, 0, structure.length);
        Map unprotected = new Map();
        if (hashed != null) unprotected.put(new UnicodeString("hashed"), hashed);
        byte[] sign1 = encode(new Array().add(new ByteString(protectedBytes)).add(unprotected)
                .add(new ByteString(payload)).add(new ByteString(signer.generateSignature())));
        byte[] key = encode(new Map().put(new UnsignedInteger(1), new UnsignedInteger(1))
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

    private static byte[] encode(DataItem value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CborEncoder(out).encode(value);
        return out.toByteArray();
    }

    private static void assertStatus(HttpStatus status, Runnable task) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, task::run);
        assertEquals(status, error.getStatusCode());
    }
}
