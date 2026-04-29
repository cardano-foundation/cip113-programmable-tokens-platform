package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing KYC token registration data.
 * Links programmable tokens to their global state initialization.
 */
@Entity
@Table(name = "kyc_token_registration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KycTokenRegistrationEntity {

    /**
     * Policy ID of the programmable token (primary key).
     */
    @Id
    @Column(name = "programmable_token_policy_id", nullable = false, length = 56)
    private String programmableTokenPolicyId;

    /**
     * Public key hash of the issuer admin.
     */
    @Column(name = "issuer_admin_pkh", nullable = false, length = 56)
    private String issuerAdminPkh;

    /**
     * Foreign key to the global state init record.
     * The tel_policy_id column stores the global state minting policy ID.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tel_policy_id", referencedColumnName = "tel_node_policy_id",
            foreignKey = @ForeignKey(name = "fk_tel_init"))
    private GlobalStateInitEntity globalStateInit;
}
