package org.cardanofoundation.cip113.controller;

import org.cardanofoundation.cip113.service.UnknownProtocolVersionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The status translation itself, tested directly rather than through a Spring MVC context.
 *
 * <p>This is the piece that answers the {@code /admin/utxos} path, where the resolver is called
 * outside the controller's try block and the exception propagates uncaught. Before it, that
 * request returned 500 for what is purely a bad request parameter — which on a monitored
 * deployment does not merely mislead the caller, it raises a server-fault alert for a request
 * the server rejected entirely correctly.
 */
class ProtocolExceptionHandlerTest {

    private final ProtocolExceptionHandler handler = new ProtocolExceptionHandler();

    @Test
    void anUnresolvableProtocolVersionBecomesBadRequest() {
        var response = handler.handleUnknownProtocolVersion(
                new UnknownProtocolVersionException("Protocol version not found: abcd"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    /**
     * The message has to survive. Its whole value is telling the caller which of the two hashes
     * it sent was wrong, and a handler that answered a bare 400 would trade one unhelpful
     * response for another.
     */
    @Test
    void theResponseCarriesTheReason() {
        var response = handler.handleUnknownProtocolVersion(
                new UnknownProtocolVersionException("Protocol version not found: abcd"));

        assertNotNull(response.getBody());
        assertTrue(response.getBody().get("error").contains("abcd"),
                "the body must name the hash that failed to resolve");
    }

    /**
     * Guards the scope of the advice rather than its behaviour. A blanket handler over this
     * codebase would convert genuine server faults into 4xx and quietly remove them from
     * alerting — the exact failure being fixed here, in the opposite direction.
     */
    @Test
    void theAdviceIsNarrow() {
        assertNotNull(ProtocolExceptionHandler.class.getAnnotation(RestControllerAdvice.class));

        var handlers = ProtocolExceptionHandler.class.getDeclaredMethods();
        var handled = java.util.Arrays.stream(handlers)
                .filter(m -> m.getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class) != null)
                .toList();

        assertEquals(1, handled.size(), "this advice must handle exactly one exception type");
        assertEquals(UnknownProtocolVersionException.class,
                handled.get(0).getAnnotation(org.springframework.web.bind.annotation.ExceptionHandler.class)
                        .value()[0]);
    }
}
