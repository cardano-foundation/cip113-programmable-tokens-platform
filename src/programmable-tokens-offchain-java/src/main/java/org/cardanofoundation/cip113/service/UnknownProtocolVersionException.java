package org.cardanofoundation.cip113.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A caller-supplied {@code protocolTxHash} that names no deployment this build can use.
 *
 * <p>Typed rather than a bare {@link IllegalArgumentException} for one reason: it is a
 * <strong>client</strong> error and must answer 400. It previously surfaced as 500 —
 * {@code ProtocolDeploymentResolver} threw {@code IllegalArgumentException} and no call site
 * translated it. On a monitored deployment that is worse than a cosmetic wrong status: 5xx is
 * what alerting counts, so a caller passing a stale or mistyped hash raises a server-fault
 * page for a request the server handled entirely correctly by rejecting.
 *
 * <p>The {@link ResponseStatus} annotation covers call sites that let it propagate;
 * {@code ProtocolExceptionHandler} gives it a structured body, and the handful of controllers
 * that wrap everything in {@code catch (Exception)} rethrow this type explicitly so it reaches
 * that handler rather than being flattened into a 500 with the rest.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class UnknownProtocolVersionException extends IllegalArgumentException {

    public UnknownProtocolVersionException(String message) {
        super(message);
    }
}
