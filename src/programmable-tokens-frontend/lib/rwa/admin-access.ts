/**
 * Who may run global-state actions on an RWA token — the decision, kept dependency-free.
 *
 * ⛔ TWO INDEPENDENT GROUNDS, AND THE CAPABILITY ALONE IS THE WRONG TEST. Global-state actions are
 * gated on chain by `admin_credential_hash` in the global-state datum, NOT by the power-users list.
 * Mint, burn, blacklist and seize are the ones that check the caller's OWN power-user node as a
 * reference input.
 *
 * ⚑ THE ONE WALLET THAT COULD USE THE PANEL WAS THE ONE IT HID FROM. After a RotateAdmin the
 * incoming admin holds the credential and has NO power-user node, because
 * `buildAddPowerUserTransaction` can still only insert the first one — so `rwaTokenCapabilities` is
 * 0. `AdminPanel` and `GlobalStateSection` both tested the ADMIN capability, so the token appeared
 * in the list with no Global State tab and nothing to click.
 *
 * Those two predicates carried a comment warning they must not drift. They had not drifted from
 * each other; they were both wrong in the SAME way, which a drift check cannot see. Hence one
 * function, imported by both, and `lib/api/admin.ts` re-exports it so existing call sites are
 * unchanged.
 *
 * ⚠ Structural parameter type on purpose: this file must not import `@/types/api`, or it cannot be
 * compiled on its own and the behaviour goes back to being defended by source greps.
 */

/** Just enough of an admin token entry to decide. */
export interface RwaAccessSubject {
  readonly moduleId: string;
  readonly roles: readonly string[];
  /** Bitfield mirrored from the on-chain power-users list; absent or 0 means no node. */
  readonly rwaTokenCapabilities?: number | null;
}

/** The ADMIN bit of the BaFin power-user capabilities bitfield. */
export const RWA_ADMIN_CAPABILITY = 0b00001;

/**
 * True when this wallet may run global-state actions on this token.
 *
 * `ISSUER_ADMIN` is the CHAIN's answer — the backend grants it from the live global-state datum
 * (`AdminController.rwaRoles`) — and the capability is the power-user LIST's answer. Either is
 * sufficient; neither is required.
 */
export function canAdministerRwaGlobalState(token: RwaAccessSubject): boolean {
  if (token.moduleId !== "rwa-token") return false;
  if (token.roles.includes("ISSUER_ADMIN")) return true;
  return ((token.rwaTokenCapabilities ?? 0) & RWA_ADMIN_CAPABILITY) !== 0;
}
