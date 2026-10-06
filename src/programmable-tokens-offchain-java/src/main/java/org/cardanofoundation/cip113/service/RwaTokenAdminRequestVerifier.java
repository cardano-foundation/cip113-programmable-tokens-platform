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
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    /**
     * How long a signed request stays valid.
     *
     * ⛔ THIS WINDOW HAS A HUMAN INSIDE IT, AND THAT IS WHY IT WAS TOO SHORT. The frontend stamps
     * `issued` BEFORE calling `signData`, so the clock starts before the user is even shown the
     * prompt. A software wallet signs in about a second and the window never mattered — which is
     * exactly why this survived thousands of successful runs. A HARDWARE wallet puts a person, a
     * scrolling confirmation, and sometimes an unlock or a re-plug inside the same five minutes.
     *
     * ⚑ MEASURED CONSEQUENCE, mainnet, 2026-10-06: the same RWA tokenisation that had always
     * worked from a hot wallet, and worked once from a Ledger that morning, started answering 401
     * on `/rwa-token/build-chain`. Confirming fast enough was the difference, which is not a
     * property anyone should have to rely on.
     *
     * ⚠ WIDENING THIS IS NOT THE REPLAY CONTROL BEING RELAXED. Replay is prevented by
     * {@code nonces.consume(...)} — each nonce is single-use and the row carries the expiry, so a
     * captured signature cannot be used twice regardless of this window. What the window bounds is
     * how long an UNUSED captured signature stays spendable, and fifteen minutes of that is a
     * deliberate trade for a signing flow a person can actually complete.
     */
    static final long REQUEST_WINDOW_MS = 900_000L;      // 15 min: a human with a device in hand

    /**
     * How far ahead of the server a client's clock may be.
     *
     * ⛔ 30 SECONDS WAS NOT TOLERANCE, IT WAS A TRIPWIRE. Browser clocks drift, and an NTP
     * correction on either side flips this from working to failing with no code change and no
     * diagnosis — the symptom is an opaque 401 that looks identical to a bad signature. Two
     * minutes is still far tighter than the window itself, so it cannot be used to extend validity.
     */
    static final long MAX_CLOCK_AHEAD_MS = 120_000L;

    static RequestWindow requestWindow(HttpHeaders headers) {
        String nonce = required(headers, "X-CMTA-Nonce").toLowerCase(java.util.Locale.ROOT);
        if (!nonce.matches("[0-9a-f]{64}")) throw unauthorized("invalid request nonce");
        long issued = parseTime(required(headers, "X-CMTA-Issued"));
        long expires = parseTime(required(headers, "X-CMTA-Expires"));
        long now = System.currentTimeMillis();
        // ⚑ EACH CLAUSE GETS ITS OWN MESSAGE. All five used to collapse into "expired or not yet
        // valid", so an operator could not tell a slow device confirmation from a skewed clock from
        // a malformed window — three different problems with three different fixes, and the only
        // way to tell them apart was to read this source.
        if (issued > now + MAX_CLOCK_AHEAD_MS) {
            throw unauthorized("request was issued " + (issued - now) + "ms in the FUTURE; this "
                    + "machine's clock is ahead of the server by more than "
                    + MAX_CLOCK_AHEAD_MS + "ms — check NTP on the signing machine");
        }
        if (issued < now - REQUEST_WINDOW_MS) {
            throw unauthorized("request was signed " + (now - issued) + "ms ago, older than the "
                    + REQUEST_WINDOW_MS + "ms window — if you were confirming on a hardware "
                    + "wallet, the signature aged out while you approved it; retry and confirm "
                    + "without leaving the device idle");
        }
        if (expires <= issued || expires > issued + REQUEST_WINDOW_MS) {
            throw unauthorized("request window is malformed: issued=" + issued + " expires="
                    + expires + ", which is not a positive span of at most " + REQUEST_WINDOW_MS
                    + "ms — the client and this server disagree about the window length");
        }
        if (expires < now) {
            throw unauthorized("request signature expired " + (now - expires) + "ms ago");
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

    /**
     * The CIP-8 `hashed` flag from the unprotected header: absent means false.
     *
     * ⚠ Strict about the TYPE. A non-boolean here is a malformed record rather than a hint, and
     * silently treating anything truthy as `true` would let an odd encoding pick the verification
     * path. Only the two CBOR simple values are accepted.
     */
    private static boolean coseHashed(Map unprotected) {
        DataItem flag = unprotected.get(new UnicodeString("hashed"));
        if (flag == null || SimpleValue.FALSE.equals(flag)) return false;
        if (SimpleValue.TRUE.equals(flag)) return true;
        throw unauthorized("COSE `hashed` header is not a boolean");
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
                || signature.getBytes().length != 64) throw unauthorized("invalid COSE_Sign1");

        // ⛔ CIP-8 `hashed` MODE, WHICH THIS VERIFIER USED TO REJECT OUTRIGHT — a spec violation.
        // CIP-190: "Verifiers MUST inspect the unprotected header for `hashed` and, when its value
        // is true, perform this substitution before strict Ed25519 verification."
        //
        // CIP-8 requires the hash in two cases, neither of which we control: when the payload is
        // too large for the device's signing buffer, and when it contains characters the device
        // cannot display. Both are properties of the WALLET AND FIRMWARE, not of our request — we
        // cannot opt in or out. Refusing it meant any wallet that chose to hash failed 100% of the
        // time, with "unsupported COSE headers", which reads like a broken signature.
        //
        // ⚑ AND THE MALLEABILITY WORRY IS ANSWERED, not waved away. The flag sits in the
        // UNPROTECTED header, so it is not covered by the signature — the obvious question is
        // whether an attacker can flip it. CIP-190 settles it: "its malleability is not an
        // integrity surface: flipping the flag in transit changes which bytes the verifier
        // reconstructs as Sig_structure[3], so verification of an honest signature simply fails —
        // a denial-of-service-grade nuisance, never a forgery."
        //
        // That holds HERE specifically because the digest below is computed over OUR OWN
        // reconstructed `expectedPayload`, never over anything the caller supplied. There is also
        // no room for confusion between the two modes: a Blake2b-224 digest is 28 bytes and the
        // reconstructed payload is a few hundred bytes of ASCII, so one can never be read as the
        // other.
        byte[] digest = com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash224(expectedPayload);
        boolean flagged = coseHashed(unprotected);
        byte[] effectivePayload;
        if (Arrays.equals(payload.getBytes(), expectedPayload)) {
            effectivePayload = expectedPayload;                 // plain
        } else if (Arrays.equals(payload.getBytes(), digest)) {
            // ⛔ ACCEPTED EVEN WHEN THE FLAG IS ABSENT, deliberately. CIP-8 says a hashing producer
            // MUST set `hashed: true`, and not all of them do — the flag is an unprotected header
            // that some wallets drop. What identifies the mode unambiguously is the PAYLOAD
            // ITSELF: it either equals the bytes we reconstructed, or it equals their
            // Blake2b-224. There is no third reading, because a 28-byte digest cannot be
            // mistaken for a few hundred bytes of ASCII.
            //
            // ⚑ AND THIS COSTS NOTHING IN SECURITY. The digest is computed HERE, over the payload
            // THIS server reconstructed from the audience, network, path, body hash and window —
            // never over anything the caller supplied. A caller who sends a digest of some other
            // message simply fails both comparisons. Insisting on the flag would have turned a
            // sloppy-but-honest wallet into a 401 that reads like a forged signature.
            effectivePayload = digest;
            if (!flagged) {
                log.warn("CIP-8: wallet signed the Blake2b-224 digest but did not set "
                        + "`hashed: true` in the unprotected header — accepted, since the payload "
                        + "identifies the mode unambiguously, but the wallet is non-conformant");
            }
        } else {
            // ⛔ NAME THE DISCRIMINATOR. "COSE payload mismatch" alone cost a round trip to
            // mainnet: it is produced BOTH by hashed mode and by a genuinely different payload,
            // and on the deployed build the payload comparison ran BEFORE the hashed-header check,
            // so a hashed signature never reached the message that would have named it.
            throw unauthorized("COSE payload mismatch: the wallet signed "
                    + payload.getBytes().length + " bytes; this request reconstructs "
                    + expectedPayload.length + " bytes"
                    + (payload.getBytes().length == digest.length
                        ? " and its length matches a Blake2b-224 digest, but NOT the digest of what "
                          + "we reconstructed — so the wallet hashed a DIFFERENT payload"
                        : "")
                    + ". One of audience, network, path, body or the signing window differs from "
                    + "what was signed. Audience must equal the frontend's API base URL plus "
                    + "/api/v1, and network must match this deployment.");
        }
        Map protectedMap = strictMap(protectedBytes.getBytes());
        if (number(protectedMap.get(new UnsignedInteger(1))) != -8
                || !(protectedMap.get(new UnicodeString("address")) instanceof ByteString signedAddress)
                || protectedMap.get(new UnsignedInteger(2)) != null
                || unprotected.get(new UnsignedInteger(2)) != null
                || unprotected.get(new UnicodeString("address")) != null
                || unprotected.get(new UnsignedInteger(1)) != null
                // CIP-8 puts `hashed` in the UNPROTECTED header; one in the protected map is
                // not a conformant record, and accepting it there would mean two places to read
                // the same flag from.
                || protectedMap.get(new UnicodeString("hashed")) != null) {
            throw unauthorized("unsupported COSE headers");
        }
        Array structure = new Array()
                .add(new UnicodeString("Signature1"))
                .add(new ByteString(protectedBytes.getBytes()))
                .add(new ByteString(new byte[0]))
                // The digest in hashed mode, the payload otherwise — CIP-190's Sig_structure[3].
                .add(new ByteString(effectivePayload));
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

    /**
     * ⛔ LOG IT. Spring omits a ResponseStatusException's reason from the response body unless
     * `server.error.include-message` is set, and this class has sixteen distinct reasons. On
     * mainnet that meant an operator saw `{"status":401,"error":"Unauthorized"}` and nothing else,
     * while the server knew precisely which check failed. The reason was invisible on BOTH sides:
     * nothing logged it either.
     */
    static ResponseStatusException unauthorized(String reason) {
        log.warn("CMTA request authentication REFUSED: {}", reason);
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
    }

    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
