package org.cardanofoundation.cip113.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.TransferTokenRequest;
import org.cardanofoundation.cip113.service.TokenOperationsService;
import org.cardanofoundation.cip113.service.ReferenceTokenNotTransferableException;
import org.cardanofoundation.cip113.service.TokenNotInCurrentDeploymentException;
import org.cardanofoundation.cip113.service.UnknownProtocolVersionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${apiPrefix}/transfer-token")
@RequiredArgsConstructor
@Slf4j
public class TransferTokenController {

    private final TokenOperationsService tokenOperationsService;

    @PostMapping("/transfer")
    public ResponseEntity<?> transfer(
            @RequestBody TransferTokenRequest transferTokenRequest,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("transferTokenRequest: {}, protocolTxHash: {}", transferTokenRequest, protocolTxHash);

        try {

            var transactionContext = tokenOperationsService.transferToken(transferTokenRequest, protocolTxHash);

            if (transactionContext.isSuccessful()) {
                return ResponseEntity.ok(transactionContext.unsignedCborTx());
            } else {
                return ResponseEntity.internalServerError().body(transactionContext.error());
            }

        } catch (ReferenceTokenNotTransferableException e) {
            // 400, for the same reason as the two below.
            throw e;
        } catch (TokenNotInCurrentDeploymentException e) {
            // Same reason as below: the generic catch would turn a correct 400 refusal into a
            // 500, which is what alerting counts.
            throw e;
        } catch (UnknownProtocolVersionException e) {
            // A bad protocolTxHash is a CLIENT error. Rethrown so
            // ProtocolExceptionHandler answers 400; the catch below would
            // flatten it into a 500 and put it in front of alerting.
            throw e;
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }


}
