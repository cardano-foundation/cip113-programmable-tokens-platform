package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.model.TransactionContent;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.service.TokenOperationsService;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssueTokenChainRecoveryTest {
    private final BackendService backend = mock(BackendService.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final IssueTokenController controller = new IssueTokenController(mock(TokenOperationsService.class), backend);

    private String cbor(int index) throws Exception {
        var body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("11".repeat(32)).index(index).build()))
                .fee(BigInteger.valueOf(200_000L)).build();
        return Transaction.builder().body(body).witnessSet(new TransactionWitnessSet())
                .isValid(true).build().serializeToHex();
    }

    private String hash(String cbor) { return TransactionUtil.getTxHash(HexUtil.decodeHexString(cbor)); }

    @SuppressWarnings("unchecked")
    private Result<TransactionContent> lookup(String hash, String block, Boolean valid) {
        Result<TransactionContent> result = mock(Result.class);
        var content = new TransactionContent();
        content.setHash(hash); content.setBlock(block); content.setValidContract(valid);
        when(result.isSuccessful()).thenReturn(true);
        when(result.getValue()).thenReturn(content);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Result<String> accepted(String hash) {
        Result<String> result = mock(Result.class);
        when(result.isSuccessful()).thenReturn(true);
        when(result.getValue()).thenReturn(hash);
        return result;
    }

    @Test void resumeSkipsOnlyConfirmedParentAndSubmitsSavedChild() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        String parent = cbor(0), child = cbor(1);
        var confirmedParent = lookup(hash(parent), "aa".repeat(32), true);
        when(transactions.getTransaction(hash(parent))).thenReturn(confirmedParent);
        when(transactions.getTransaction(hash(child))).thenReturn(null);
        var acceptedChild = accepted(hash(child));
        ChainSubmissionFixture.accept(transactions, child, acceptedChild);
        var response = controller.submitChain(Map.of("signedCborHexes", List.of(parent, child)));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(hash(parent), hash(child)), ((Map<?, ?>) response.getBody()).get("txHashes"));
        assertEquals(false, ((Map<?, ?>) response.getBody()).get("confirmed"));
        ChainSubmissionFixture.verifyNever(transactions, parent);
    }

    @Test void pendingParentIsUnknownUntilLaterConfirmed() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        String parent = cbor(0), child = cbor(1);
        Result<String> rejected = mock(Result.class);
        when(rejected.isSuccessful()).thenReturn(false);
        when(rejected.getResponse()).thenReturn("duplicate or pending");
        var confirmedParent = lookup(hash(parent), "aa".repeat(32), true);
        when(transactions.getTransaction(hash(parent))).thenReturn(null, null, confirmedParent);
        ChainSubmissionFixture.accept(transactions, parent, rejected);
        var acceptedChild = accepted(hash(child));
        ChainSubmissionFixture.accept(transactions, child, acceptedChild);
        var first = controller.submitChain(Map.of("signedCborHexes", List.of(parent, child)));
        assertEquals(400, first.getStatusCode().value());
        assertEquals(List.of(), ((Map<?, ?>) first.getBody()).get("txHashes"));
        var resumed = controller.submitChain(Map.of("signedCborHexes", List.of(parent, child)));
        assertEquals(200, resumed.getStatusCode().value());
        assertEquals(List.of(hash(parent), hash(child)), ((Map<?, ?>) resumed.getBody()).get("txHashes"));
        assertEquals(false, ((Map<?, ?>) resumed.getBody()).get("confirmed"));
    }

    @Test void invalidOrUnprovenLookupNeverAdvancesAndMismatchedSubmissionStops() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        String parent = cbor(0);
        Result<String> rejected = mock(Result.class);
        when(rejected.isSuccessful()).thenReturn(false);
        when(rejected.getResponse()).thenReturn("rejected");
        for (Boolean valid : new Boolean[]{false, null}) {
            reset(transactions);
            var unproven = lookup(hash(parent), "aa".repeat(32), valid);
            when(transactions.getTransaction(hash(parent))).thenReturn(unproven);
            ChainSubmissionFixture.acceptAny(transactions, rejected);
            assertEquals(400, controller.submitChain(Map.of("signedCborHexes", List.of(parent))).getStatusCode().value());
        }
        reset(transactions);
        when(transactions.getTransaction(hash(parent))).thenReturn(null);
        var wrong = accepted("ff".repeat(32));
        ChainSubmissionFixture.acceptAny(transactions, wrong);
        var mismatched = controller.submitChain(Map.of("signedCborHexes", List.of(parent)));
        assertEquals(400, mismatched.getStatusCode().value());
        assertEquals(List.of(), ((Map<?, ?>) mismatched.getBody()).get("txHashes"));
    }

    @Test void lostFullResponseReplaysWithoutResubmittingConfirmedTransactions() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        String parent = cbor(0), child = cbor(1);
        var parentProof = lookup(hash(parent), "aa".repeat(32), true);
        var childProof = lookup(hash(child), "bb".repeat(32), true);
        when(transactions.getTransaction(hash(parent))).thenReturn(parentProof);
        when(transactions.getTransaction(hash(child))).thenReturn(childProof);
        var response = controller.submitChain(Map.of("signedCborHexes", List.of(parent, child)));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(hash(parent), hash(child)), ((Map<?, ?>) response.getBody()).get("txHashes"));
        assertEquals(true, ((Map<?, ?>) response.getBody()).get("confirmed"));
        ChainSubmissionFixture.verifyNeverAny(transactions);
    }

    @Test void submitExceptionAfterAcceptanceContinuesOnlyWhenLookupConfirmsIt() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        String parent = cbor(0);
        var confirmedParent = lookup(hash(parent), "aa".repeat(32), true);
        when(transactions.getTransaction(hash(parent))).thenReturn(null, confirmedParent);
        ChainSubmissionFixture.throwAny(transactions, new IllegalStateException("response lost"));
        var response = controller.submitChain(Map.of("signedCborHexes", List.of(parent)));
        assertEquals(200, response.getStatusCode().value());
        assertEquals(List.of(hash(parent)), ((Map<?, ?>) response.getBody()).get("txHashes"));
    }

    @Test void chainStatusDistinguishesConfirmedInvalidNotIndexedAndUnknown() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        var hashes = List.of("11".repeat(32), "22".repeat(32), "33".repeat(32), "44".repeat(32));
        Result<TransactionContent> missing = mock(Result.class);
        when(missing.isSuccessful()).thenReturn(false);
        when(missing.code()).thenReturn(404);
        var confirmed = lookup(hashes.get(0), "aa".repeat(32), true);
        var invalid = lookup(hashes.get(1), "bb".repeat(32), false);
        var unknown = lookup(hashes.get(3), "cc".repeat(32), null);
        when(transactions.getTransaction(hashes.get(0))).thenReturn(confirmed);
        when(transactions.getTransaction(hashes.get(1))).thenReturn(invalid);
        when(transactions.getTransaction(hashes.get(2))).thenReturn(missing);
        when(transactions.getTransaction(hashes.get(3))).thenReturn(unknown);
        var response = controller.chainStatus(Map.of("txHashes", hashes));
        assertEquals(200, response.getStatusCode().value());
        var observations = (List<?>) ((Map<?, ?>) response.getBody()).get("transactions");
        assertEquals(List.of("CONFIRMED", "INVALID", "NOT_INDEXED", "UNKNOWN"), observations.stream()
                .map(row -> ((Map<?, ?>) row).get("status")).toList());
        assertEquals("MISSING_VALIDITY", ((Map<?, ?>) observations.get(3)).get("reason"));
    }

    @Test void chainStatusRejectsUnboundedMalformedAndDuplicateListsBeforeLookup() {
        for (var hashes : List.of(List.<String>of(), List.of("not-a-hash"),
                List.of("11".repeat(32), "11".repeat(32)),
                java.util.Collections.nCopies(17, "22".repeat(32)))) {
            assertEquals(400, controller.chainStatus(Map.of("txHashes", hashes)).getStatusCode().value());
        }
        verifyNoInteractions(backend);
    }

    @Test void chainStatusFailsClosedOnWrongHashProviderFailureAndMissingBlock() throws Exception {
        when(backend.getTransactionService()).thenReturn(transactions);
        var hashes = List.of("55".repeat(32), "66".repeat(32), "77".repeat(32), "88".repeat(32));
        var wrongHash = lookup("ff".repeat(32), "aa".repeat(32), true);
        var noBlock = lookup(hashes.get(1), null, true);
        Result<TransactionContent> providerFailure = mock(Result.class);
        when(providerFailure.isSuccessful()).thenReturn(false);
        when(providerFailure.code()).thenReturn(503);
        when(transactions.getTransaction(hashes.get(0))).thenReturn(wrongHash);
        when(transactions.getTransaction(hashes.get(1))).thenReturn(noBlock);
        when(transactions.getTransaction(hashes.get(2))).thenReturn(providerFailure);
        when(transactions.getTransaction(hashes.get(3))).thenThrow(new IllegalStateException("offline"));
        var response = controller.chainStatus(Map.of("txHashes", hashes));
        var observations = (List<?>) ((Map<?, ?>) response.getBody()).get("transactions");
        assertEquals(List.of("HASH_MISMATCH", "MISSING_BLOCK", "LOOKUP_FAILED", "LOOKUP_EXCEPTION"),
                observations.stream().map(row -> ((Map<?, ?>) row).get("reason")).toList());
        assertTrue(observations.stream().allMatch(row -> "UNKNOWN".equals(((Map<?, ?>) row).get("status"))));
    }
}
