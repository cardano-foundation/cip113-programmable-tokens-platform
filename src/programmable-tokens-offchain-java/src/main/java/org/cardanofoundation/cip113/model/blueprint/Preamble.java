package org.cardanofoundation.cip113.model.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The CIP-57 blueprint preamble, carried through the API instead of dropped at it.
 *
 * <p>{@link Plutus} used to be {@code record Plutus(List<Validator> validators)}. Combined with
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} that silently discarded the preamble when
 * plutus.json was read, so {@code GET /protocol/blueprint} served validators with no provenance
 * at all — and the loss was invisible, because the frontend's own type declares {@code preamble}
 * optional and substituted a {@code {title: "unknown", version: "0.0.0"}} placeholder.
 *
 * <p>That was survivable until the SDK began asserting the protocol version it was handed. It
 * then rejected the served blueprint with <em>Blueprint "unknown v0.0.0" targets an EARLIER
 * CIP-113 protocol version</em> while reporting that every required validator title was present
 * — a version failure with correct validators, which is exactly what a dropped preamble looks
 * like from the far side.
 *
 * <p>{@code compiler} is included rather than just title and version: the SDK's provenance gate
 * matches {@code preamble.compiler} against its pin, so a preamble carrying only the two fields
 * that fixed the first error would fail the next one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Preamble(
        String title,
        String description,
        String version,
        String plutusVersion,
        Compiler compiler,
        String license) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Compiler(String name, String version) {
    }
}
