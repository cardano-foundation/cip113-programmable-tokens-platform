package org.cardanofoundation.cip113.standard;

import com.bloxbean.cardano.client.metadata.Metadata;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.metadata.exception.MetadataSerializationException;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.cip171.CompilerType;
import org.cardanofoundation.cip113.cip171.UplcLinkRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the CIP-171 verification metadata (label 1984) attached to the protocol
 * bootstrap mint tx. The metadata claims that the deployed script hashes were
 * produced from {@link #SOURCE_URL} at {@link #COMMIT_HASH} by Aiken
 * {@link #COMPILER_VERSION}, with the per-script parameters supplied by the caller.
 *
 * The commit referenced here MUST contain the exact source that produces the
 * deployed plutus.json. If the on-chain repo has uncommitted/unpushed changes,
 * verifiers will fail to reproduce and the record becomes orphaned.
 */
@Slf4j
@UtilityClass
public class Cip171BootstrapMetadata {

    private static final String SOURCE_URL = "https://github.com/cardano-foundation/cip113-programmable-tokens";
    // TODO: bump after the matching plutus.json is committed + pushed.
    private static final String COMMIT_HASH = "31204e9ddf5840ef8d6991d0c37108df31bb1cd0";
    private static final String SOURCE_PATH = "";
    private static final String COMPILER_VERSION = "v1.1.21";

    // Tx bodies are capped at 16_384 bytes on mainnet/testnets; leave headroom for
    // inputs, outputs, witnesses, redeemers etc. This is a safety net, not a hard limit.
    private static final int METADATA_SIZE_LIMIT = 12_000;

    public static Metadata build(Map<byte[], List<PlutusData>> scriptHashToParams) throws MetadataSerializationException {
        var hexed = new LinkedHashMap<String, List<String>>();
        scriptHashToParams.forEach((hash, params) -> {
            var hexParams = params.stream().map(PlutusData::serializeToHex).toList();
            hexed.put(HexUtil.encodeHexString(hash), hexParams);
        });

        var chunkedList = UplcLinkRequest.builder()
                .compilerType(CompilerType.AIKEN)
                .sourceUrl(SOURCE_URL)
                .commitHash(COMMIT_HASH)
                .sourcePath(SOURCE_PATH)
                .compilerVersion(COMPILER_VERSION)
                .parameters(hexed)
                .build()
                .toMetadataChunkList();

        var metadata = MetadataBuilder.createMetadata().put(1984L, chunkedList);
        var sizeBytes = metadata.serialize().length;
        log.info("CIP-171 metadata: {} bytes, {} script entries", sizeBytes, hexed.size());
        if (sizeBytes > METADATA_SIZE_LIMIT) {
            throw new IllegalStateException(
                    "CIP-171 metadata is %d bytes (> %d limit) — too big to attach to bootstrap tx"
                            .formatted(sizeBytes, METADATA_SIZE_LIMIT));
        }
        return metadata;
    }
}
