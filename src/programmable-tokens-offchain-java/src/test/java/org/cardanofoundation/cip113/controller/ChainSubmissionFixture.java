package org.cardanofoundation.cip113.controller;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.util.HexUtil;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mock-only calls shared by the offline recovery tests. */
final class ChainSubmissionFixture {
    private ChainSubmissionFixture() {}

    static void accept(TransactionService service, String cbor, Result<String> result) throws Exception {
        when(service.submitTransaction(HexUtil.decodeHexString(cbor))).thenReturn(result);
    }

    static void acceptAny(TransactionService service, Result<String> result) throws Exception {
        when(service.submitTransaction(any(byte[].class))).thenReturn(result);
    }

    static void throwAny(TransactionService service, RuntimeException error) throws Exception {
        when(service.submitTransaction(any(byte[].class))).thenThrow(error);
    }

    static void verifyNever(TransactionService service, String cbor) throws Exception {
        verify(service, never()).submitTransaction(HexUtil.decodeHexString(cbor));
    }

    static void verifyNeverAny(TransactionService service) throws Exception {
        verify(service, never()).submitTransaction(any(byte[].class));
    }
}
