package org.cardanofoundation.cip113.service;

import id.veridian.signify.cesr.Serder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MintTxHashPayloadTest {
    @Test void exactTwoFieldReconstructionProfile() {
        String hash = "12".repeat(32);
        assertEquals("{\"d\":\"" + "#".repeat(44) + "\",\"txHash\":\"" + hash + "\"}",
                MintTxHashPayload.preimage(hash));
        var signed = MintTxHashPayload.signed(hash);
        assertEquals("EJlHkxKfXRnbAPICZfzcSKTTAyZDMoA66pr2k9R9XTDX", signed.get("d"));
        assertEquals("{\"d\":\"" + signed.get("d") + "\",\"txHash\":\"" + hash + "\"}",
                Serder.dumps(signed));
        assertThrows(IllegalArgumentException.class, () -> MintTxHashPayload.digest("not-a-hash"));
    }
}
