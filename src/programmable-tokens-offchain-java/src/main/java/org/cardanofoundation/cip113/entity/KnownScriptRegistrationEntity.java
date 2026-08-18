package org.cardanofoundation.cip113.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** A script stake credential this deployment knows to be registered.
 *
 *  <p>Exists because neither available source can answer reliably: the account endpoint is not
 *  implemented by every backend, and our indexed certificates cover a window of history rather
 *  than all of it. See V16 for the measurements. */
@Entity
@Table(name = "known_script_registration")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnownScriptRegistrationEntity {

    @Id
    @Column(name = "stake_address", nullable = false, length = 255)
    private String stakeAddress;

    @Column(name = "credential", length = 56)
    private String credential;

    /** {@code BUILT} — this platform built the registration; {@code LEDGER_REJECT} — a submit
     *  came back naming the credential as already known. */
    @Column(name = "source", nullable = false, length = 32)
    private String source;

    /** TRUE = known registered, honoured by the registration check. FALSE = this platform built
     *  a certificate for it and is awaiting the outcome — evidence that the credential is ours to
     *  confirm, and nothing more. See V17. */
    @Column(name = "registered", nullable = false)
    @Builder.Default
    private boolean registered = true;

    @Column(name = "noted_at", nullable = false)
    @Builder.Default
    private Instant notedAt = Instant.now();
}
