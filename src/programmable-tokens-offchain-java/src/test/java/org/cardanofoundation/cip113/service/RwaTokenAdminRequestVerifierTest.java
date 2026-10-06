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

    /**
     * ⛔ THE WINDOW HAS A HUMAN INSIDE IT. The frontend stamps `issued` BEFORE awaiting
     * `signData`, so the clock is already running while the user reads a prompt. A software wallet
     * signs in about a second; a hardware wallet puts a person, a scrolling confirmation and
     * sometimes an unlock inside the same span.
     *
     * <p>⚑ That is why the old five-minute window survived thousands of hot-wallet runs and then
     * answered 401 on mainnet for the same RWA tokenisation the first time a Ledger took its time
     * (2026-10-06). The fix is not "retry faster".
     */
    @Test
    void aHardwareWalletConfirmationHasTimeToFinish() throws Exception {
        long now = System.currentTimeMillis();
        // ⛔ SEVEN MINUTES, AND THE NUMBER IS THE POINT. It must sit OUTSIDE the old five-minute
        // window and INSIDE the new one, or this test passes whether the fix is present or not.
        // The first version used four minutes, which fits in both — it was green against the very
        // bug it was written for, and only a mutation back to 300_000 exposed that.
        long issued = now - 420_000;
        HttpHeaders slow = signedHeaders("", "GET", PATH,
                issued, issued + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS);
        assertDoesNotThrow(() -> verify("", PATH, adminHash, slow),
                "a four-minute hardware-wallet confirmation must still authenticate");

        // ⚠ AND THE WINDOW STILL ENDS. Past it, the refusal must still come.
        long old = now - RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS - 60_000;
        HttpHeaders tooOld = signedHeaders("", "GET", PATH,
                old, old + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS);
        assertStatus(HttpStatus.UNAUTHORIZED, () -> verify("", PATH, adminHash, tooOld));
    }

    /**
     * ⛔ EACH TIMING FAILURE MUST NAME ITSELF. All five window clauses used to collapse into
     * "request signature expired or not yet valid", so an operator could not tell a slow device
     * confirmation from a skewed clock from a client/server disagreement about the window length —
     * three different problems with three different fixes. On mainnet the response body carried no
     * message at all, and nothing logged it either.
     */
    @Test
    void eachTimingFailureSaysWhichOneItWas() throws Exception {
        long now = System.currentTimeMillis();

        // (a) clock ahead of the server — NTP on the signing machine
        long ahead = now + RwaTokenAdminRequestVerifier.MAX_CLOCK_AHEAD_MS + 60_000;
        var future = signedHeaders("", "GET", PATH, ahead,
                ahead + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS);
        assertReason(() -> verify("", PATH, adminHash, future), "FUTURE");

        // (b) aged out while the user approved on the device
        long old = now - RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS - 60_000;
        var aged = signedHeaders("", "GET", PATH, old,
                old + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS);
        assertReason(() -> verify("", PATH, adminHash, aged), "hardware wallet");

        // (c) the client asked for a longer window than this server allows — the failure mode of
        //     shipping the frontend half of this change without the backend half.
        var tooLong = signedHeaders("", "GET", PATH, now,
                now + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS + 60_000);
        assertReason(() -> verify("", PATH, adminHash, tooLong), "disagree about the window");
    }

    /** ⚑ A 30-SECOND FORWARD TOLERANCE WAS A TRIPWIRE, not tolerance: ordinary browser clock drift
     *  flipped this from working to failing with no code change and an opaque 401. */
    @Test
    void ordinaryClockDriftIsTolerated() throws Exception {
        long skewed = System.currentTimeMillis() + 45_000;   // refused before this change
        HttpHeaders headers = signedHeaders("", "GET", PATH, skewed,
                skewed + RwaTokenAdminRequestVerifier.REQUEST_WINDOW_MS);
        assertDoesNotThrow(() -> verify("", PATH, adminHash, headers),
                "45 seconds of forward clock drift is ordinary and must not read as a bad signature");
    }

    private static void assertReason(Runnable task, String mustContain) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, task::run);
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains(mustContain),
                "the refusal must say which timing check failed; expected it to mention '"
                + mustContain + "' but got: " + error.getReason());
    }

    private static void assertStatus(HttpStatus status, Runnable task) {
        ResponseStatusException error = assertThrows(ResponseStatusException.class, task::run);
        assertEquals(status, error.getStatusCode());
    }
}
