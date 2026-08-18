package org.cardanofoundation.cip113.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CIP-68 metadata collected by the registration wizard.
 *
 * <p>These are the six fields the {@code token-details-step} form collects. They are carried
 * on the register/mint requests and end up as the inline datum of the {@code (100)} reference
 * token — see {@link org.cardanofoundation.cip113.util.Cip68#buildDatum}.
 *
 * <p>Only {@link #name()} is required; every other field is omitted from the datum map when
 * null or blank, which matches the TypeScript SDK's {@code buildCIP68FTDatum}.
 *
 * @param name        display name (required)
 * @param description free-text description
 * @param ticker      short symbol, e.g. {@code "MYTKN"}
 * @param decimals    number of decimal places; encoded as an integer, not a byte string
 * @param url         project or token URL
 * @param logo        URI of the token logo
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Cip68Metadata(
        String name,
        String description,
        String ticker,
        Integer decimals,
        String url,
        String logo) {
}
