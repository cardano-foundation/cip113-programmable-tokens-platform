package org.cardanofoundation.cip113.model.blueprint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One validator entry of a blueprint.
 *
 * <p>{@code hash} is carried for the same reason as {@link Preamble}: it is present in
 * plutus.json and the frontend's type declares it, but the record used to stop at
 * {@code (title, compiledCode)} — so every served validator arrived with {@code hash}
 * undefined and consumers silently read nothing where they expected a script hash.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Validator(String title, String compiledCode, String hash) {
}
