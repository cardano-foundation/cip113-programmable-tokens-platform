package org.cardanofoundation.cip113.entity;

/** Capabilities a power user can hold for a registered security-token.
 *  Mirrors the 5 booleans on the BaFin on-chain {@code PowerUser} record
 *  ({@code is_admin}, {@code can_mint}, {@code can_burn}, {@code can_pause},
 *  {@code can_force_transfer}) — see {@code lib/types/power_users.ak}.
 *
 *  <p>Note on absent capabilities: BaFin deliberately does NOT have separate
 *  Blacklister / Verifier roles — denylist mutations are gated directly by the
 *  admin credential in the GS datum, and KYC verification is delegated to the
 *  off-chain trusted entities listed in {@code trusted_entity_vkeys}. */
public enum SecurityTokenPowerUserCapability {
    ADMIN          (1 << 0),
    MINTER         (1 << 1),
    BURNER         (1 << 2),
    PAUSER         (1 << 3),
    /** Reserved for the deferred third-party-transfer flow; unused in v1. */
    FORCE_TRANSFER (1 << 4);

    private final int bit;

    SecurityTokenPowerUserCapability(int bit) {
        this.bit = bit;
    }

    public int bit() {
        return bit;
    }

    public boolean granted(int capabilities) {
        return (capabilities & bit) != 0;
    }

    public static int encode(SecurityTokenPowerUserCapability... caps) {
        int out = 0;
        for (SecurityTokenPowerUserCapability c : caps) out |= c.bit;
        return out;
    }
}
