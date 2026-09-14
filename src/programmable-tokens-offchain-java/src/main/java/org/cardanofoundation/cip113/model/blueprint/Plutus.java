package org.cardanofoundation.cip113.model.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A CIP-57 blueprint as served by {@code GET /protocol/blueprint}.
 *
 * <p>The {@code preamble} is load-bearing, not decoration: consumers identify which protocol
 * version the validators belong to by reading it. See {@link Preamble}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plutus(Preamble preamble, List<Validator> validators) {
}
