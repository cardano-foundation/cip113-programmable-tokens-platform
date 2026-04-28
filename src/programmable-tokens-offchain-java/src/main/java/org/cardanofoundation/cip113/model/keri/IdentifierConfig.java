package org.cardanofoundation.cip113.model.keri;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class IdentifierConfig {
    private String prefix;
    private String name;
    private String role;
}
