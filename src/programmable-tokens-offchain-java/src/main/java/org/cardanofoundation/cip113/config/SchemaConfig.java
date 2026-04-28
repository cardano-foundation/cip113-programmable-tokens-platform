package org.cardanofoundation.cip113.config;

import lombok.Getter;
import lombok.Setter;
import org.cardanofoundation.cip113.model.Role;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "keri.schema")
@Getter
@Setter
public class SchemaConfig {

    private String baseUrl;
    private Map<String, SchemaEntry> schemas;

    public SchemaEntry getSchemaForRole(Role role) {
        if (schemas == null) return null;
        return schemas.get(role.name());
    }

    @Getter
    @Setter
    public static class SchemaEntry {
        private String said;
        private String label;
    }
}
