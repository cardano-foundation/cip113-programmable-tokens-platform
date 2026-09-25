package org.cardanofoundation.cip113.service;

import id.veridian.signify.generated.keria.model.CompletedCredentialOperation;
import id.veridian.signify.generated.keria.model.CompletedExchangeOperation;
import id.veridian.signify.generated.keria.model.FailedCredentialOperation;
import id.veridian.signify.generated.keria.model.FailedExchangeOperation;
import id.veridian.signify.generated.keria.model.OperationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeriOperationCompletionTest {

    @Test
    void acceptsOnlyTheExpectedCompletedOperation() {
        assertDoesNotThrow(() -> KeriService.requireCompletedOperation(
                new CompletedCredentialOperation().name("credential.1"),
                CompletedCredentialOperation.class, "credential issuance"));
        assertDoesNotThrow(() -> KeriService.requireCompletedOperation(
                new CompletedExchangeOperation().name("exchange.1"),
                CompletedExchangeOperation.class, "credential grant"));
        assertThrows(RuntimeException.class, () -> KeriService.requireCompletedOperation(
                new CompletedCredentialOperation().name("credential.2"),
                CompletedExchangeOperation.class, "credential grant"));
    }

    @Test
    void rejectsFailedCredentialAndGrantOperationsWithStageAndReason() {
        var failedIssue = new FailedCredentialOperation().name("credential.3")
                .error(new OperationStatus().code(409).message("TEL rejected"));
        String issueMessage = assertThrows(RuntimeException.class,
                () -> KeriService.requireCompletedOperation(failedIssue,
                        CompletedCredentialOperation.class, "credential issuance")).getMessage();
        assertTrue(issueMessage.contains("credential issuance"));
        assertTrue(issueMessage.contains("TEL rejected"));

        var failedGrant = new FailedExchangeOperation().name("exchange.2")
                .error(new OperationStatus().code(503).message("recipient unavailable"));
        String grantMessage = assertThrows(RuntimeException.class,
                () -> KeriService.requireCompletedOperation(failedGrant,
                        CompletedExchangeOperation.class, "credential grant")).getMessage();
        assertTrue(grantMessage.contains("credential grant"));
        assertTrue(grantMessage.contains("503"));
        assertTrue(grantMessage.contains("recipient unavailable"));
    }

    @Test
    void rejectsFailedOperationsWithoutDetailsOrUnexpectedNull() {
        var failedGrant = new FailedExchangeOperation().name("exchange.3");
        assertTrue(assertThrows(RuntimeException.class,
                () -> KeriService.requireCompletedOperation(failedGrant,
                        CompletedExchangeOperation.class, "credential grant"))
                .getMessage().contains("no error details"));
        assertThrows(RuntimeException.class,
                () -> KeriService.requireCompletedOperation(null,
                        CompletedExchangeOperation.class, "credential grant"));
    }
}
