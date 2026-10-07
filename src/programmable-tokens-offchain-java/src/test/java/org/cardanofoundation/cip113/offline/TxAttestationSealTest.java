package org.cardanofoundation.cip113.offline;

import id.veridian.signify.cesr.Serder;
import org.cardanofoundation.cip113.service.MintTxHashPayload;
import org.cardanofoundation.cip113.service.TxAttestationSeal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** CIP-170 v1.1 "Transaction seal" test vectors, reproduced byte for byte. */
class TxAttestationSealTest {
    private static final String HASH = "4b1c6f3e3c0a6c5e2f9d7a8b1e0c4d5f6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3";

    @Test void reproducesCip170MainnetVector() {
        assertEquals("{\"d\":\"" + "#".repeat(44) + "\",\"t\":\"cardano-tx-attest\",\"n\":764824073,\"txHash\":\"" + HASH + "\"}",
                TxAttestationSeal.preimage(HASH, 764824073L));
        var signed = TxAttestationSeal.signed(HASH, 764824073L);
        assertEquals("EOm0xWcPpijf-XF1T_cA8LcDm-99_MdNtZhCjPk4xC2_", signed.get("d"));
        assertEquals("{\"d\":\"EOm0xWcPpijf-XF1T_cA8LcDm-99_MdNtZhCjPk4xC2_\",\"t\":\"cardano-tx-attest\",\"n\":764824073,\"txHash\":\""
                + HASH + "\"}", Serder.dumps(signed));
    }

    @Test void reproducesCip170PreprodVectorAndNormalisesHexCase() {
        assertEquals("EIe4UUF0iPy-cZCdXIO7o7FcJhcYqZh-Gdq_Ya2z-azj", TxAttestationSeal.digest(HASH.toUpperCase(), 1L));
    }

    @Test void networkMagicFailsClosed() {
        assertEquals(764824073L, TxAttestationSeal.magic("mainnet"));
        assertEquals(1L, TxAttestationSeal.magic("preprod"));
        assertEquals(2L, TxAttestationSeal.magic("preview"));
        assertEquals(42L, TxAttestationSeal.magic("devnet"));
        assertThrows(IllegalArgumentException.class, () -> TxAttestationSeal.magic("sanchonet"));
        assertThrows(IllegalArgumentException.class, () -> TxAttestationSeal.magic(null));
        assertThrows(IllegalArgumentException.class, () -> TxAttestationSeal.digest("not-a-hash", 1L));
    }

    @Test void recognisesOnlyTheExactSealDocument() {
        assertTrue(TxAttestationSeal.isSealDocument(Serder.dumps(TxAttestationSeal.signed(HASH, 1L))));
        assertFalse(TxAttestationSeal.isSealDocument(Serder.dumps(MintTxHashPayload.signed(HASH))));
        assertFalse(TxAttestationSeal.isSealDocument("{\"d\":\"x\",\"t\":\"other\",\"n\":1,\"txHash\":\"" + HASH + "\"}"));
        assertFalse(TxAttestationSeal.isSealDocument("{\"t\":\"cardano-tx-attest\",\"d\":\"x\",\"n\":1,\"txHash\":\"" + HASH + "\"}"));
        assertFalse(TxAttestationSeal.isSealDocument("{\"profile\":\"cip113-mint-intent-said-json-v1\"}"));
        assertFalse(TxAttestationSeal.isSealDocument(null));
        assertFalse(TxAttestationSeal.isSealDocument("not json"));
        assertTrue(MintTxHashPayload.isDocument(Serder.dumps(MintTxHashPayload.signed(HASH))));
        assertFalse(MintTxHashPayload.isDocument(Serder.dumps(TxAttestationSeal.signed(HASH, 1L))));
        assertFalse(MintTxHashPayload.isDocument("{\"i\":\"x\",\"d\":\"\",\"profile\":\"cip113-mint-intent-said-json-v1\"}"));
    }
}
