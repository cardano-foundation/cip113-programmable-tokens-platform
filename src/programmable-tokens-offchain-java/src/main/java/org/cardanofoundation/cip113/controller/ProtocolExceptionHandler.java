package org.cardanofoundation.cip113.controller;

import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.service.ReferenceTokenNotTransferableException;
import org.cardanofoundation.cip113.service.TokenNotInCurrentDeploymentException;
import org.cardanofoundation.cip113.service.UnknownProtocolVersionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps an unresolvable {@code protocolTxHash} to 400 instead of 500.
 *
 * <p>Deliberately narrow. It handles exactly one exception type rather than becoming a
 * catch-all translator, because a blanket advice over this codebase would quietly convert
 * genuine server faults into 4xx and remove them from alerting — the opposite of the problem
 * being fixed here.
 */
@RestControllerAdvice
@Slf4j
public class ProtocolExceptionHandler {

    @ExceptionHandler(UnknownProtocolVersionException.class)
    public ResponseEntity<Map<String, String>> handleUnknownProtocolVersion(
            UnknownProtocolVersionException e) {
        // WARN, not ERROR: a client sending a stale hash is an expected condition on a
        // deployment that keeps a version history, and logging it at ERROR reintroduces the
        // false alert through the logging pipeline after removing it from the status code.
        log.warn("Rejected unresolvable protocolTxHash: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    /**
     * A token from another deployment is a 400 for the same reason: well-formed request, healthy
     * server, wrong token for this protocol version. WARN for the same reason too — with a
     * version history this is an expected caller mistake, and ERROR would put it in alerting.
     */
    @ExceptionHandler(TokenNotInCurrentDeploymentException.class)
    public ResponseEntity<Map<String, String>> handleTokenNotInCurrentDeployment(
            TokenNotInCurrentDeploymentException e) {
        log.warn("Rejected token from another protocol deployment: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }

    /** Moving a (100) reference token would erase its metadata; a 400, not a server fault. */
    @ExceptionHandler(ReferenceTokenNotTransferableException.class)
    public ResponseEntity<Map<String, String>> handleReferenceToken(
            ReferenceTokenNotTransferableException e) {
        log.warn("Rejected an operation that would erase CIP-68 metadata: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", e.getMessage()));
    }
}
