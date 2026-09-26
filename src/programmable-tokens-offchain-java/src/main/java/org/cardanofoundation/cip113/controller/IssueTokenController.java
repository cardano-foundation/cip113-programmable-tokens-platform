package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.BurnTokenRequest;
import org.cardanofoundation.cip113.model.MintTokenRequest;
import org.cardanofoundation.cip113.model.RegisterTokenRequest;
import org.cardanofoundation.cip113.model.RegisterTokenResponse;
import org.cardanofoundation.cip113.service.TokenOperationsService;
import org.cardanofoundation.cip113.service.UnknownProtocolVersionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;

@RestController
@RequestMapping("${apiPrefix}/issue-token")
@RequiredArgsConstructor
@Slf4j
public class IssueTokenController {

    private final TokenOperationsService tokenOperationsService;
    private final BackendService bfBackendService;

    /** Read-only chain observation. Submission acceptance is never treated as confirmation. */
    @PostMapping("/chain-status")
    public ResponseEntity<?> chainStatus(@RequestBody Map<String, List<String>> body) {
        var requested = body == null ? null : body.get("txHashes");
        if (requested == null || requested.isEmpty() || requested.size() > 16)
            return ResponseEntity.badRequest().body(Map.of("error", "txHashes must contain 1 to 16 hashes"));
        var canonical = new ArrayList<String>(requested.size());
        var distinct = new HashSet<String>();
        for (String value : requested) {
            if (value == null || !value.matches("[0-9a-fA-F]{64}"))
                return ResponseEntity.badRequest().body(Map.of("error", "Each transaction hash must be 64 hex characters"));
            String hash = value.toLowerCase(Locale.ROOT);
            if (!distinct.add(hash))
                return ResponseEntity.badRequest().body(Map.of("error", "Duplicate transaction hashes are not allowed"));
            canonical.add(hash);
        }
        var observations = canonical.stream().map(this::observe).toList();
        return ResponseEntity.ok(Map.of("transactions", observations));
    }

    private Map<String, String> observe(String hash) {
        try {
            var result = bfBackendService.getTransactionService().getTransaction(hash);
            if (result == null) return observation(hash, "UNKNOWN", "NO_RESPONSE");
            if (!result.isSuccessful())
                return observation(hash, result.code() == 404 ? "NOT_INDEXED" : "UNKNOWN",
                        result.code() == 404 ? "NOT_FOUND" : "LOOKUP_FAILED");
            var tx = result.getValue();
            if (tx == null) return observation(hash, "UNKNOWN", "EMPTY_RESULT");
            if (!hash.equalsIgnoreCase(tx.getHash())) return observation(hash, "UNKNOWN", "HASH_MISMATCH");
            if (tx.getBlock() == null || tx.getBlock().isBlank())
                return observation(hash, "UNKNOWN", "MISSING_BLOCK");
            if (Boolean.TRUE.equals(tx.getValidContract())) return observation(hash, "CONFIRMED", "VALID_BLOCK");
            if (Boolean.FALSE.equals(tx.getValidContract())) return observation(hash, "INVALID", "INVALID_CONTRACT");
            return observation(hash, "UNKNOWN", "MISSING_VALIDITY");
        } catch (Exception e) {
            log.warn("chain-status: lookup failed for tx {}", hash, e);
            return observation(hash, "UNKNOWN", "LOOKUP_EXCEPTION");
        }
    }

    private static Map<String, String> observation(String hash, String status, String reason) {
        return Map.of("hash", hash, "status", status, "reason", reason);
    }

    @PostMapping("/pre-register")
    public ResponseEntity<?> preRegisterToken(
            @RequestBody RegisterTokenRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("preRegisterToken request: {}, protocolTxHash: {}", request, protocolTxHash);

        try {
            var txContext = tokenOperationsService.preRegisterToken(request, protocolTxHash);

            if (txContext.isSuccessful()) {
                return ResponseEntity.ok(txContext);
            } else {
                return ResponseEntity.badRequest().body(txContext.error());
            }
        } catch (UnknownProtocolVersionException e) {
            // A bad protocolTxHash is a CLIENT error. Rethrown so
            // ProtocolExceptionHandler answers 400; the catch below would
            // flatten it into a 500 and put it in front of alerting.
            throw e;
        } catch (Exception e) {
            log.warn("error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    /** Submit a chain of pre-signed transactions sequentially with mempool chaining.
     *
     *  <p>Used by the rwa-token wizard (and any other module that builds
     *  multi-tx chains): the frontend signs an ordered list of CBORs in a single
     *  wallet popup via CIP-30 {@code signTxs} and POSTs them here. We submit each
     *  via the backend's {@code TransactionService} so the wallet's own submission
     *  backend isn't in the loop — cardano-submit-api / Blockfrost-on-local-node
     *  accept mempool-chained txs, so we don't need to wait for confirmation
     *  between submits.
     *
     *  <p>On the first failure we stop and return the partial list of accepted
     *  hashes plus the failure detail so the caller can decide whether to retry.
     *
     *  <p>Request body shape: {@code {"signedCborHexes": ["<cbor1>", "<cbor2>", …]}}
     *  Response: {@code {"txHashes": ["<hash1>", "<hash2>", …]}} on full success,
     *  {@code {"txHashes": ["<hash1>"], "error": "...", "failedIndex": 1}} on partial. */
    @PostMapping("/submit-chain")
    public ResponseEntity<?> submitChain(@RequestBody Map<String, List<String>> body) {
        var signedCborHexes = body == null ? null : body.get("signedCborHexes");
        if (signedCborHexes == null || signedCborHexes.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "signedCborHexes is required and non-empty"));
        }

        var txHashes = new ArrayList<String>();
        for (int i = 0; i < signedCborHexes.size(); i++) {
            var cborHex = signedCborHexes.get(i);
            byte[] signedBytes;
            try {
                signedBytes = HexUtil.decodeHexString(cborHex);
            } catch (UnknownProtocolVersionException e) {
                // A bad protocolTxHash is a CLIENT error. Rethrown so
                // ProtocolExceptionHandler answers 400; the catch below would
                // flatten it into a 500 and put it in front of alerting.
                throw e;
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "txHashes", txHashes,
                        "failedIndex", i,
                        "error", "invalid hex at index " + i + ": " + e.getMessage()));
            }

            String expectedHash;
            try {
                expectedHash = TransactionUtil.getTxHash(signedBytes);
            } catch (UnknownProtocolVersionException e) {
                // A bad protocolTxHash is a CLIENT error. Rethrown so
                // ProtocolExceptionHandler answers 400; the catch below would
                // flatten it into a 500 and put it in front of alerting.
                throw e;
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of(
                        "txHashes", txHashes,
                        "failedIndex", i,
                        "error", "could not derive tx hash at index " + i + ": " + e.getMessage()));
            }

            if (confirmed(expectedHash)) {
                txHashes.add(expectedHash);
                continue;
            }

            try {
                var submitResult = bfBackendService.getTransactionService().submitTransaction(signedBytes);
                if (submitResult == null || !submitResult.isSuccessful()) {
                    var reason = submitResult != null ? submitResult.getResponse() : "null result";
                    if (confirmed(expectedHash)) {
                        txHashes.add(expectedHash);
                        continue;
                    }
                    log.warn("submit-chain: tx {} (index {}) rejected: {}", expectedHash, i, reason);
                    return ResponseEntity.badRequest().body(Map.of(
                            "txHashes", txHashes,
                            "failedIndex", i,
                            "failedTxHash", expectedHash,
                            "error", "submission status UNKNOWN at index " + i + ": " + reason));
                }
                // Blockfrost returns the tx hash on success; treat it as authoritative
                // but cross-check against ours just in case of an SDK quirk.
                var returned = submitResult.getValue();
                if (returned != null && !returned.equalsIgnoreCase(expectedHash)) {
                    log.warn("submit-chain: backend returned hash {} but we derived {} for index {}",
                            returned, expectedHash, i);
                    if (!confirmed(expectedHash))
                        return ResponseEntity.badRequest().body(Map.of(
                                "txHashes", txHashes, "failedIndex", i, "failedTxHash", expectedHash,
                                "error", "submission status UNKNOWN: backend returned a different transaction hash"));
                }
                txHashes.add(expectedHash);
                log.info("submit-chain: submitted tx {}/{} hash={}", i + 1, signedCborHexes.size(), expectedHash);
            } catch (UnknownProtocolVersionException e) {
                // A bad protocolTxHash is a CLIENT error. Rethrown so
                // ProtocolExceptionHandler answers 400; the catch below would
                // flatten it into a 500 and put it in front of alerting.
                throw e;
            } catch (Exception e) {
                if (confirmed(expectedHash)) {
                    txHashes.add(expectedHash);
                    continue;
                }
                log.error("submit-chain: exception submitting tx {} (index {})", expectedHash, i, e);
                return ResponseEntity.badRequest().body(Map.of(
                        "txHashes", txHashes,
                        "failedIndex", i,
                        "failedTxHash", expectedHash,
                        "error", "submission status UNKNOWN at index " + i + ": " + e.getMessage()));
            }
        }

        // Acceptance by a submit endpoint is not block confirmation. The caller
        // must retain the complete signed chain until every exact hash is valid
        // in a block, including the final CIP-170 child transaction.
        boolean allConfirmed = txHashes.stream().allMatch(this::confirmed);
        return ResponseEntity.ok(Map.of("txHashes", txHashes, "confirmed", allConfirmed));
    }

    /** Only a successfully applied transaction in a block permits skipping saved signed bytes. */
    private boolean confirmed(String expectedHash) {
        try {
            var result = bfBackendService.getTransactionService().getTransaction(expectedHash);
            if (result == null || !result.isSuccessful() || result.getValue() == null) return false;
            var tx = result.getValue();
            return expectedHash.equalsIgnoreCase(tx.getHash())
                    && tx.getBlock() != null && !tx.getBlock().isBlank()
                    && Boolean.TRUE.equals(tx.getValidContract());
        } catch (Exception unavailable) {
            log.debug("submit-chain: transaction {} is not confirmed by the configured backend", expectedHash, unavailable);
            return false;
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(
            @RequestBody RegisterTokenRequest registerTokenRequest,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("registerTokenRequest: {}, protocolTxHash: {}", registerTokenRequest, protocolTxHash);

        try {

            var result = tokenOperationsService.registerToken(registerTokenRequest, protocolTxHash);

            if (result.isSuccessful()) {
                return ResponseEntity.ok(new RegisterTokenResponse(result.metadata().policyId(), result.unsignedCborTx()));
            } else {
                return ResponseEntity.badRequest().body(result.error());
            }

        } catch (UnknownProtocolVersionException e) {
            // A bad protocolTxHash is a CLIENT error. Rethrown so
            // ProtocolExceptionHandler answers 400; the catch below would
            // flatten it into a 500 and put it in front of alerting.
            throw e;
        } catch (Exception e) {
            log.warn("error", e);
            return ResponseEntity.internalServerError().build();
        }
    }


    @PostMapping("/mint")
    public ResponseEntity<?> mint(@RequestBody MintTokenRequest mintTokenRequest,
                                  @RequestParam(required = false) String protocolTxHash) {

        try {

            var transactionContext = tokenOperationsService.mintToken(mintTokenRequest, protocolTxHash);

            if (transactionContext.isSuccessful()) {
                return ResponseEntity.ok(transactionContext.unsignedCborTx());
            } else {
                return ResponseEntity.internalServerError().body(transactionContext.error());
            }


        } catch (UnknownProtocolVersionException e) {
            // A bad protocolTxHash is a CLIENT error. Rethrown so
            // ProtocolExceptionHandler answers 400; the catch below would
            // flatten it into a 500 and put it in front of alerting.
            throw e;
        } catch (Exception e) {
            log.warn("error", e);
            return ResponseEntity.internalServerError().body(e.getMessage());
        }
    }

    @PostMapping("/burn")
    public ResponseEntity<String> burnToken(
            @RequestBody BurnTokenRequest request,
            @RequestParam(required = false) String protocolTxHash) {

        log.info("POST /issue-token/burn - policyId: {}, assetName: {}, quantity: {}, utxo: {}#{}",
                request.tokenPolicyId(), request.assetName(), request.quantity(),
                request.utxoTxHash(), request.utxoOutputIndex());

        try {
            // Call burn-specific method that preserves UTxO information
            var txContext = tokenOperationsService.burnToken(request, protocolTxHash);

            if (!txContext.isSuccessful()) {
                log.error("Burn transaction build failed: {}", txContext.error());
                return ResponseEntity.badRequest().body(txContext.error());
            }

            log.info("Burn transaction built successfully");
            return ResponseEntity.ok(txContext.unsignedCborTx());

        } catch (UnknownProtocolVersionException e) {
            // A bad protocolTxHash is a CLIENT error. Rethrown so
            // ProtocolExceptionHandler answers 400; the catch below would
            // flatten it into a 500 and put it in front of alerting.
            throw e;
        } catch (Exception e) {
            log.error("Failed to build burn transaction", e);
            return ResponseEntity.internalServerError()
                    .body("Failed to build burn transaction: " + e.getMessage());
        }
    }


}
