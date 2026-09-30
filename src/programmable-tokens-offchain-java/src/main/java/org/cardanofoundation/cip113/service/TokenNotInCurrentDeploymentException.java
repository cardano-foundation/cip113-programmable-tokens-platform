package org.cardanofoundation.cip113.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A programmable token that belongs to a DIFFERENT protocol deployment than the one in use.
 *
 * <p><strong>Why this is a distinct error.</strong> A programmable token is bound to the
 * deployment it was minted under: its policy id is the hash of {@code issuance_mint}
 * parameterised by that deployment's protocol params. So a token minted under deployment A
 * cannot be repointed at deployment B — not by configuration, not by re-registration. Its
 * registry node lives in A's directory, and every credential derived for it (notably the
 * freeze-and-seize {@code transfer} script, which is a function of
 * {@code programmableLogicBase.scriptHash}) is A's.
 *
 * <p>⛔ WITHOUT THIS GUARD THE FAILURE IS A PHASE-2 SUBMISSION ERROR. Measured on preprod
 * 2026-09-30: a re-bootstrap replaced the deployment record, and transfers of tokens minted
 * under the previous one were built happily and rejected by the ledger with
 * {@code {"code":3141,"description":"rewards withdrawals must consume rewards in full"}} —
 * whose text names neither the token, the deployment, nor the actual cause (the withdrawal
 * credential derived from the NEW base was never registered). Hours went into that message.
 * Refusing at build time with the deployment named costs one request and explains itself.
 *
 * <p>400, not 500: the request is well formed and the server is healthy — the token simply is
 * not one this deployment can operate on. Answering 5xx would put a correctly rejected request
 * in front of alerting, which is the mistake {@link UnknownProtocolVersionException} documents.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
public class TokenNotInCurrentDeploymentException extends IllegalArgumentException {

    public TokenNotInCurrentDeploymentException(String message) {
        super(message);
    }
}
