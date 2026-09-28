package org.cardanofoundation.cip113.service;

import id.veridian.signify.cesr.Saider;
import id.veridian.signify.cesr.Serder;
import id.veridian.signify.cesr.util.CoreUtil;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class MintAttestationDocumentTest {
    @Test void storedPreimageIsByteForByteTheBlake3InputVeridianSaidifies() {
        var fields = new MintAttestationRequest("session", "preview", "01".repeat(32), "02".repeat(28),
                "", "9007199254740993", "payer", "recipient", "programmable-recipient");
        var payload = MintAttestationService.document(fields, "id", "E" + "a".repeat(43), Instant.parse("2026-09-25T10:00:00Z"));
        var signed = Saider.saidify(payload).sad();
        String digest = (String) signed.get("d");
        String preimage = MintAttestationService.preimage(signed);
        assertArrayEquals(new Saider(digest).getRaw(), CoreUtil.blake3_256(preimage.getBytes(StandardCharsets.UTF_8), 32));
        assertEquals(digest, Saider.saidify(signed).sad().get("d"));
        assertTrue(preimage.contains("\"d\":\"" + "#".repeat(44) + "\""));
        assertTrue(Serder.dumps(signed).contains("\"quantity\":\"9007199254740993\""));
        assertTrue(Serder.dumps(signed).contains("\"assetNameHex\":\"\""));
        var changed = new LinkedHashMap<>(signed); changed.put("recipient", "another-recipient");
        assertNotEquals(digest, Saider.saidify(changed).sad().get("d"));
        changed = new LinkedHashMap<>(signed); changed.put("quantity", "9007199254740994");
        assertNotEquals(digest, Saider.saidify(changed).sad().get("d"));
    }
}
