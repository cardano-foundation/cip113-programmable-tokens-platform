package org.cardanofoundation.cip113.cip171;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UplcLinkRequestTest {
    @Test void matchesIndependentSdkSixFieldEncodingAnd64ByteChunks() {
        var parameters = new LinkedHashMap<String, List<String>>();
        // Deliberately reverse insertion order: the reference sorts the raw hashes.
        parameters.put("bb".repeat(28), List.of("d8799f4101ff"));
        parameters.put("aa".repeat(28), List.of("182a", "581c" + "ab".repeat(28)));
        var request = UplcLinkRequest.builder().compilerType(CompilerType.AIKEN)
                .sourceUrl("https://github.com/example/contracts").commitHash("12".repeat(20))
                .sourcePath("contracts").compilerVersion("v1.1.23+8949565").environment("preview")
                .parameters(parameters).build();
        // Generated independently by installed @easy1staking/cip113-sdk-ts buildCip171Metadatum.
        var expected = "d8799f582468747470733a2f2f6769746875622e636f6d2f6578616d706c652f636f6e747261637473"
                + "54121212121212121212121212121212121212121249636f6e7472616374734f76312e312e32332b38393439353635"
                + "4770726576696577a2581caaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "9f42182a581e581cababababababababababababababababababababababababababababff"
                + "581cbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb9f46d8799f4101ffffff";
        assertEquals(expected, request.toPlutusData().serializeToHex());
        assertEquals(expected, String.join("", request.toCborChunks()));
        var chunks = request.toCborBytesChunks(64);
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length > 0 && chunks.get(i).length <= 64);
            if (i < chunks.size() - 1) assertEquals(64, chunks.get(i).length);
            assertEquals(HexUtil.encodeHexString(chunks.get(i)), request.toCborChunks().get(i));
            assertArrayEquals(chunks.get(i), (byte[]) request.toMetadataChunkList().getValueAt(i));
        }
        assertThrows(IllegalArgumentException.class, () -> request.toCborChunks(0));
        assertThrows(IllegalArgumentException.class, () -> request.toCborBytesChunks(65));
    }
}
