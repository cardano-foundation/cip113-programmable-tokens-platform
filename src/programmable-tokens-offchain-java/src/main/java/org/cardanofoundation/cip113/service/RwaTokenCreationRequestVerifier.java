package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.RwaTokenCreationRequestNonceRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.net.URI;
import java.util.Arrays;

/** Authorizes the public builder to reserve the fee payer's funding inputs. */
@Service
@ConditionalOnProperty(name = "rwaToken.enabled", havingValue = "true", matchIfMissing = true)
public class RwaTokenCreationRequestVerifier {
    private final RwaTokenCreationRequestNonceRepository nonces;
    private final AppConfig.Network network;
    private final String audience;

    public RwaTokenCreationRequestVerifier(RwaTokenCreationRequestNonceRepository nonces,
                                          AppConfig.Network network,
                                          @Value("${rwaToken.creationAudience}") String audience) {
        requireAudience(audience);
        this.nonces = nonces;
        this.network = network;
        this.audience = audience;
    }

    private static void requireAudience(String audience) {
        boolean valid = audience != null && audience.length() <= 200
                && audience.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*");
        if (valid) {
            try {
                URI url = URI.create(audience);
                valid = ("https".equals(url.getScheme()) || "http".equals(url.getScheme()))
                        && url.getHost() != null && !url.getHost().isBlank()
                        && url.getRawUserInfo() == null && url.getRawQuery() == null
                        && url.getRawFragment() == null && url.getRawPath() != null
                        && url.getRawPath().endsWith("/api/v1");
            } catch (IllegalArgumentException badUrl) {
                valid = false;
            }
        }
        if (!valid) throw new IllegalArgumentException(
                "Set RWA_TOKEN_CREATION_AUDIENCE (rwaToken.creationAudience) to this deployment's "
                        + "API URL: frontend NEXT_PUBLIC_API_BASE_URL without a trailing slash, "
                        + "followed by /api/v1. For local development use http://localhost:8080/api/v1");
    }

    public static String payload(String audience, String network, String path, byte[] rawBody,
                                 String nonce, long issued, long expires) {
        requireAudience(audience);
        if (network == null || !network.matches("[a-z0-9-]{1,24}")
                || !("/rwa-token/build-chain".equals(path) || "/rwa-token/init".equals(path))
                || rawBody == null || nonce == null || !nonce.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid signed creation request fields");
        }
        return "CMTA creation API v1\n"
                + "audience=" + audience + "\n"
                + "network=" + network + "\n"
                + "method=POST\n"
                + "path=" + path + "\n"
                + "body-sha256=" + RwaTokenAdminRequestVerifier.sha256(rawBody) + "\n"
                + "nonce=" + nonce + "\n"
                + "issued=" + issued + "\n"
                + "expires=" + expires + "\n";
    }

    /** Commit consumption independently, so a failed build cannot replay this authorization. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyCreationAndConsume(String path, byte[] rawBody, String feePayerAddress,
                                         HttpHeaders headers) {
        var window = RwaTokenAdminRequestVerifier.requestWindow(headers);
        String expected = payload(audience, network.getNetwork(), path, rawBody,
                window.nonce(), window.issued(), window.expires());
        byte[] signedAddress = RwaTokenAdminRequestVerifier.verifySignedRequest(
                network.getNetwork(), expected, headers);
        byte[] payer;
        try {
            payer = new Address(feePayerAddress).getBytes();
        } catch (Exception invalidAddress) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid feePayerAddress");
        }
        if (!Arrays.equals(payer, signedAddress)) {
            throw RwaTokenAdminRequestVerifier.unauthorized("CIP-30 signer address differs from feePayerAddress");
        }
        String payerHash = HexUtil.encodeHexString(Arrays.copyOfRange(signedAddress, 1, 29));
        if (nonces.consume(window.nonce(), payerHash, Instant.ofEpochMilli(window.expires())) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "creation request nonce was already used");
        }
    }
}
