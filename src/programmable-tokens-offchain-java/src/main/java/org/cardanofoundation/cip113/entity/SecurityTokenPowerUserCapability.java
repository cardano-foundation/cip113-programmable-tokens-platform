package org.cardanofoundation.cip113.entity;

/** Capabilities a power user can hold for a registered security-token.
 *  Mirrors the 5 booleans on the BaFin on-chain {@code PowerUser} record
 *  ({@code is_admin}, {@code can_mint}, {@code can_burn}, {@code can_pause},
 *  {@code can_force_transfer}) — see {@code lib/types/power_users.ak}.
 *
 *  <p>Note on absent capabilities: BaFin deliberately does NOT have separate
 *  Blacklister / Verifier roles. KYC verification is delegated to the off-chain
 *  trusted entities listed in {@code trusted_entity_vkeys}; sanctions (denylist
 *  add/remove) are gated by {@link #ADMIN} — see the note on that constant. */
public enum SecurityTokenPowerUserCapability {
    /** {@code is_admin}. Since upstream fn-bafin-cardano-sc @7ae4ce3, holders of
     *  this flag may add to / remove from the denylist (signed by themselves),
     *  which is what lets a compliance role sanction wallets without holding the
     *  GS master admin key. The GS admin still owns the power-user list lifecycle
     *  and grants/revokes this flag. */
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
