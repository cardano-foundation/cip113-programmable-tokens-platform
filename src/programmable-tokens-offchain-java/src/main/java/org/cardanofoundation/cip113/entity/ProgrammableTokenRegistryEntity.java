package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified registry mapping programmable token policy IDs to their module.
 * All modules insert here during token registration.
 */
@Entity
@Table(name = "programmable_token_registry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProgrammableTokenRegistryEntity {

    /**
     * Policy ID of the programmable token (primary key).
     */
    @Id
    @Column(name = "policy_id", nullable = false, length = 56)
    private String policyId;

    /**
     * Module identifier (e.g., "dummy", "freeze-and-seize").
     */
    @Column(name = "module_id", nullable = false, length = 50)
    private String moduleId;

    /**
     * Asset name in hex encoding.
     */
    @Column(name = "asset_name", nullable = false, length = 64)
    private String assetName;
}
