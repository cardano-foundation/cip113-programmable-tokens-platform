package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Checks the same schema OOBI URL that is sent to the Veridian wallet. */
final class SchemaOobiVerifier {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    SchemaOobiVerifier(ObjectMapper objectMapper) {
        this(HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build(), objectMapper);
    }

    SchemaOobiVerifier(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    void verify(String baseUrl, String schemaSaid) throws InterruptedException {
        if (baseUrl == null || !baseUrl.endsWith("/") || schemaSaid == null || schemaSaid.isBlank()) {
            throw new IllegalStateException("KERI schema OOBI configuration is incomplete");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl + schemaSaid);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("KERI schema OOBI URL is invalid", e);
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IllegalStateException("KERI schema OOBI must be an absolute HTTP(S) URL");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("KERI schema OOBI is unavailable (HTTP "
                        + response.statusCode() + "): " + uri);
            }
            JsonNode schema = objectMapper.readTree(response.body());
            if (schema == null || !schemaSaid.equals(schema.path("$id").asText())) {
                throw new IllegalStateException("KERI schema OOBI returned a different schema SAID: " + uri);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("KERI schema OOBI returned invalid JSON: " + uri, e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("KERI schema OOBI could not be reached: " + uri, e);
        }
    }
}
