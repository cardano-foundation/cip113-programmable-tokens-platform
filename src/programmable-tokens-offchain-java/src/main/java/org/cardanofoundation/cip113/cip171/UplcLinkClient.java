package org.cardanofoundation.cip113.cip171;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Looks a script hash up against uplc.link, the CIP-171 registry.
 *
 * <h2>What this buys</h2>
 *
 * A registry node records a token's transfer-logic hash but not the parameters behind it, so
 * freeze-and-seize cannot be identified locally: its transfer logic is parameterised on a
 * blacklist policy that appears in neither the node nor the registering transaction. A CIP-171
 * record does carry it — the published record names the {@code sourcePath} the scripts were
 * built from and the {@code providedParameters} applied to each.
 *
 * <h2>Trust, deliberately</h2>
 *
 * This takes uplc.link's answer at face value rather than re-deriving it. Verifying properly
 * would mean fetching the metadata, reassembling the 64-byte CBOR chunks, recompiling the named
 * source at the named commit and comparing hashes — which is the whole point of the registry,
 * and which it already publishes as {@code status: VERIFIED}. Trusting it is a deliberate
 * trade for now; if the answer ever becomes load-bearing for something that moves value,
 * re-derive it here instead. It is only ever used to LABEL a token, never to authorise anything.
 *
 * <h2>Network scoping, and the host that is NOT the API</h2>
 *
 * The registry is scoped by domain rather than by a parameter, and the SITE is not the API.
 * {@code preview.uplc.link} is a Next.js app whose {@code /api/registry?action=byHash} is an
 * internal proxy; the API it proxies onto is {@code preview-api.uplc.link}, route
 * {@code /api/v1/scripts/by-hash/{hash}}. Measured across networks: mainnet
 * {@code api.uplc.link}, preview {@code preview-api.uplc.link}, preprod
 * {@code preprod-api.uplc.link}; {@code mainnet-api.uplc.link} does not resolve.
 *
 * <p>This class was first written against the site's proxy path on the site's host, which
 * answers 404 for every lookup — indistinguishable from "no record published". Hence the
 * explicit path constant and a test that pins it: a wrong URL here fails silently and looks
 * exactly like an empty registry.
 */
@Component
@Slf4j
public class UplcLinkClient {

    private final WebClient webClient;
    private final boolean enabled;
    private final Duration timeout;

    /** The API route. {@code /api/registry?action=byHash} is the SITE's proxy, not this. */
    static String byHashPath(String scriptHash) {
        return "/api/v1/scripts/by-hash/" + scriptHash;
    }

    /**
     * Answers already seen. POSITIVES are kept forever — a CIP-171 record is immutable, so once
     * resolved a hash never changes.
     *
     * <p>⛔ NEGATIVES EXPIRE, AND THAT IS THE WHOLE POINT. This used to cache "not found" forever
     * on the reasoning that "a 404 is stable". It is not: uplc.link indexes records on a TIMER, so
     * a record published moments ago is genuinely absent now and present a few minutes later.
     * Memoising that absence meant the first lookup after a registration poisoned the cache for
     * the lifetime of the process, and every later retry answered from memory without ever asking
     * again — so a record that did arrive could never be picked up. A reconstruction that depends
     * on retrying was therefore guaranteed to fail.
     *
     * <p>A timeout or an unreachable host lands here too and is retryable for the same reason.
     */
    private record Negative(long atMillis) {}

    private final Map<String, JsonNode> positives = new ConcurrentHashMap<>();
    private final Map<String, Negative> negatives = new ConcurrentHashMap<>();

    /**
     * How long a "not found" is believed before asking again. Short by design: the cost of
     * re-asking is one HTTP call on a path that only runs when a row is missing, and the cost of
     * not re-asking is a record that never gets picked up.
     */
    private final Duration negativeTtl;

    public UplcLinkClient(WebClient.Builder builder,
                          @Value("${uplc-link.base-url:}") String baseUrl,
                          @Value("${uplc-link.enabled:false}") boolean enabled,
                          @Value("${uplc-link.timeout-ms:4000}") long timeoutMs,
                          @Value("${uplc-link.negative-ttl-ms:60000}") long negativeTtlMs) {
        this.negativeTtl = Duration.ofMillis(negativeTtlMs);
        this.enabled = enabled && baseUrl != null && !baseUrl.isBlank();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.webClient = this.enabled ? builder.baseUrl(baseUrl).build() : null;
        if (this.enabled) {
            log.info("uplc.link CIP-171 lookups enabled against {}", baseUrl);
        } else {
            log.info("uplc.link CIP-171 lookups disabled (uplc-link.enabled={}, base-url={})",
                    enabled, baseUrl);
        }
    }

    /**
     * @return the registry record for this script hash, or empty when unknown, disabled or
     *         unreachable. Never throws: a label is not worth failing an indexer over.
     */
    public Optional<JsonNode> byHash(String scriptHash) {
        if (scriptHash == null || scriptHash.isBlank()) {
            return Optional.empty();
        }
        var hash = scriptHash.toLowerCase();

        var hit = positives.get(hash);
        if (hit != null) {
            return Optional.of(hit);
        }

        var miss = negatives.get(hash);
        if (miss != null) {
            if (System.currentTimeMillis() - miss.atMillis() < negativeTtl.toMillis()) {
                return Optional.empty();
            }
            // Expired: fall through and ask again. The record may have been indexed since.
            negatives.remove(hash);
        }

        var fetched = fetch(hash);
        if (fetched.isPresent()) {
            positives.put(hash, fetched.get());
        } else {
            negatives.put(hash, new Negative(System.currentTimeMillis()));
            log.debug("uplc.link has no record for {} yet; will re-ask after {}ms", hash,
                    negativeTtl.toMillis());
        }
        return fetched;
    }

    /**
     * @return the record, or empty for unknown, unreachable, malformed or timed out. Never throws.
     *
     * <p>{@code protected} only so a test can substitute the transport and drive the CACHING rules
     * — which is where the behaviour that matters lives, not in the HTTP call.
     */
    protected Optional<JsonNode> fetch(String hash) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            JsonNode body = webClient.get()
                    .uri(byHashPath(hash))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(timeout)
                    .onErrorResume(e -> {
                        log.debug("uplc.link lookup failed for {}: {}", hash, e.toString());
                        return reactor.core.publisher.Mono.empty();
                    })
                    .block();

            if (body == null || body.hasNonNull("error") || !body.hasNonNull("sourcePath")) {
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (Exception e) {
            log.debug("uplc.link lookup threw for {}: {}", hash, e.toString());
            return Optional.empty();
        }
    }
}
