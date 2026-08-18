package org.cardanofoundation.cip113.model;

import java.util.List;

public record Substandard(
        String id,
        /** Human-readable name for UI. Falls back to a capitalised {@code id} when no metadata.json is present. */
        String name,
        /** One-paragraph summary for UI. Empty when no metadata.json is present. */
        String description,
        List<SubstandardValidator> validators
) {
}
