package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing Global State initialization data.
 * Stores the one-shot minting policy ID and bootstrap parameters for rebuilding
 * the global state scripts.
 */
@Entity
@Table(name = "kyc_tel_init", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tel_admin_tx_output", columnNames = {"admin_pkh", "tx_hash", "output_index"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GlobalStateInitEntity {

    /**
     * Policy ID of the global state NFT (= one-shot minting policy ID).
     */
    @Id
    @Column(name = "tel_node_policy_id", nullable = false, length = 56)
    private String globalStatePolicyId;

    /**
     * Public key hash of the admin who manages this global state.
     */
    @Column(name = "admin_pkh", nullable = false, length = 56)
    private String adminPkh;

    /**
     * Transaction hash of the bootstrap UTxO used to parameterize the one-shot policy.
     */
    @Column(name = "tx_hash", nullable = false, length = 64)
    private String txHash;

    /**
     * Output index of the bootstrap UTxO.
     */
    @Column(name = "output_index", nullable = false)
    private Integer outputIndex;
}
