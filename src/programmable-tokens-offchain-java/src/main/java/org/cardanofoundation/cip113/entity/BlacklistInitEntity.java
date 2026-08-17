package org.cardanofoundation.cip113.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Entity representing freeze-and-seize blacklist initialization data.
 * Used to build FreezeAndSeizeContext for compliance operations.
 */
@Entity
@Table(name = "freeze_and_seize_blacklist_init", uniqueConstraints = {
    @UniqueConstraint(name = "uk_admin_tx_output", columnNames = {"admin_pkh", "tx_hash", "output_index"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlacklistInitEntity {

    /**
     * Policy ID of the blacklist node NFTs (primary key).
     */
    @Id
    @Column(name = "blacklist_node_policy_id", nullable = false, length = 56)
    private String blacklistNodePolicyId;

    /**
     * Public key hash of the admin who manages this blacklist.
     */
    @Column(name = "admin_pkh", nullable = false, length = 56)
    private String adminPkh;

    /**
     * Transaction hash where the blacklist was initialized.
     */
    @Column(name = "tx_hash", nullable = false, length = 64)
    private String txHash;

    /**
     * Output index of the blacklist init UTxO.
     */
    @Column(name = "output_index", nullable = false)
    private Integer outputIndex;

    /**
     * Whether this init registered the {@code issuer_admin} credential for a CIP-68
     * (CIP-67-labelled) asset name.
     *
     * <p>This transaction is the only place {@code issuer_admin}'s reward account is registered,
     * and the registration that follows withdraws-0 from it. {@code issuer_admin} is parameterized
     * by the asset name, and a CIP-68 registration uses the labelled name — so if the two disagree
     * about CIP-68 they resolve different reward addresses and the registration is rejected with
     * {@code WithdrawalsNotInRewardsCERTS}, after the user has already paid for this transaction.
     *
     * <p>{@code null} means the row pre-dates this column: the cross-check then has no evidence
     * and stays silent rather than rejecting a registration that may well be correct.
     */
    @Column(name = "cip68_enabled")
    private Boolean cip68Enabled;
}
