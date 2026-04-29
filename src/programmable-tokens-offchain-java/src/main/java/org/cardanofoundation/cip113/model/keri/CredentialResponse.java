package org.cardanofoundation.cip113.model.keri;

import java.util.Map;

public record CredentialResponse(
        String role,
        int roleValue,
        String label,
        Map<String, Object> attributes) {}
