package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import org.cardanofoundation.cip113.config.AppConfig;
import org.cardanofoundation.cip113.repository.MintAttestationNonceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;

@Service
@ConditionalOnProperty(name = "keri.enabled", havingValue = "true")
public class MintAttestationRequestVerifier {
    private final MintAttestationNonceRepository nonces;
    private final AppConfig.Network network;
    private final String audience;

    public MintAttestationRequestVerifier(MintAttestationNonceRepository nonces, AppConfig.Network network,
            @Value("${keri.mintAttestationAudience:${rwaToken.creationAudience:}}") String audience) {
        this.nonces = nonces;
        this.network = network;
        this.audience = audience;
    }

    public String audience() {
        try {
            URI uri = URI.create(audience);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().endsWith("/api/v1")) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Configure keri.mintAttestationAudience as this deployment's absolute API URL ending /api/v1");
        }
        return audience;
    }

    public static String payload(String audience, String network, String path, byte[] rawBody,
                                 String nonce, long issued, long expires) {
        if (audience == null || audience.contains("\n") || network == null || !network.matches("[a-z0-9-]{1,24}")
                || path == null || !path.matches("(?:/keri/mint-attestations/(prepare|[a-f0-9-]{36}/(?:anchor|build-chain))|/rwa-token/create-attested/(prepare|[a-f0-9-]{36}/(?:anchor|finalize|cancel|approve-and-build)))")
                || rawBody == null || nonce == null || !nonce.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid signed mint attestation request");
        return "CIP-170 mint API v1\n"
                + "audience=" + audience + "\nnetwork=" + network + "\nmethod=POST\npath=" + path
                + "\nbody-sha256=" + RwaTokenAdminRequestVerifier.sha256(rawBody)
                + "\nnonce=" + nonce + "\nissued=" + issued + "\nexpires=" + expires + "\n";
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyAndConsume(String path, byte[] body, String feePayerAddress, HttpHeaders headers) {
        var window = RwaTokenAdminRequestVerifier.requestWindow(headers);
        byte[] signer = RwaTokenAdminRequestVerifier.verifySignedRequest(network.getNetwork(),
                payload(audience(), network.getNetwork(), path, body, window.nonce(), window.issued(), window.expires()), headers);
        byte[] payer;
        try { payer = new Address(feePayerAddress).getBytes(); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid fee payer address"); }
        if (!Arrays.equals(signer, payer))
            throw RwaTokenAdminRequestVerifier.unauthorized("Mint attestation signer differs from fee payer");
        if (nonces.consume(window.nonce(), Instant.ofEpochMilli(window.expires())) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mint attestation request nonce already used");
    }
}
