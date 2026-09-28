package org.cardanofoundation.cip113.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaOobiVerifierTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void checksTheExactSchemaOobiAndSaid() throws Exception {
        startServer(200, "{\"$id\":\"expected-said\"}");
        assertDoesNotThrow(() -> new SchemaOobiVerifier(new ObjectMapper())
                .verify(baseUrl(), "expected-said"));
    }

    @Test
    void rejectsUnavailableOrWrongSchemaBeforeIssuance() throws Exception {
        startServer(503, "unavailable");
        IllegalStateException unavailable = assertThrows(IllegalStateException.class,
                () -> new SchemaOobiVerifier(new ObjectMapper()).verify(baseUrl(), "expected-said"));
        assertTrue(unavailable.getMessage().contains("HTTP 503"));

        server.stop(0);
        server = null;
        startServer(200, "{\"$id\":\"different-said\"}");
        IllegalStateException mismatch = assertThrows(IllegalStateException.class,
                () -> new SchemaOobiVerifier(new ObjectMapper()).verify(baseUrl(), "expected-said"));
        assertTrue(mismatch.getMessage().contains("different schema SAID"));
    }

    private void startServer(int status, String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/oobi/expected-said", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/oobi/";
    }
}
