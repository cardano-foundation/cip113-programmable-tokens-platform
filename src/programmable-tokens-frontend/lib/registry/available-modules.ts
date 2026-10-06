/**
 * Which substandards a given network is allowed to offer.
 *
 * ## Why this is keyed on the NETWORK and not only on the FLOW_* flags
 *
 * `FLOW_DUMMY_ENABLED` and friends already exist and are read at runtime by
 * `app/api/config/route.ts`, so a mainnet deployment could hide a module by setting three
 * environment variables. That is the right mechanism for an operator changing their mind, and the
 * wrong one for a rule that must hold:
 *
 *   - it is three variables, in a ConfigMap, that have to be remembered at every redeploy and in
 *     every new environment — and the failure mode of forgetting is that MAINNET offers a module
 *     labelled "Not a product";
 *   - they default to ENABLED, so absence of configuration is permission;
 *   - and `components/register/steps/select-module-step.tsx` falls back to the build-time list
 *     whenever `/api/config` does not answer, so a failed fetch re-exposes whatever the flags were
 *     hiding.
 *
 * ⇒ So the network allowlist is a FLOOR, applied in addition to the flags, at every layer that
 * decides what to show. The flags can still narrow it; nothing can widen it without a rebuild.
 * That matches how the network itself is configured — `NEXT_PUBLIC_NETWORK` is inlined at build
 * time (see `lib/utils/network.ts`), so "mainnet offers only CMTA · eWpG" is a property of the
 * image rather than of its environment.
 *
 * ## The rule, Giovanni 2026-10-06
 *
 * Mainnet offers the BaFin/CMTAT security standard (`rwa-token`) and nothing else. `dummy` is a
 * template ("Not a product"), `freeze-and-seize` has not been through the same review, and `kyc` /
 * `kyc-extended` are already disabled by default everywhere. Every other network is unchanged —
 * the testnets are where the other modules are exercised.
 *
 * ⚠ TAKES THE NETWORK AS AN ARGUMENT, deliberately. Reading `getCardanoNetwork()` in here would
 * make the rule untestable without a rebuild per case and would pull `lib/utils/network.ts` into
 * every compilation that wants the allowlist. The impure read stays at the call sites.
 */

/** Our own network names. Kept structural rather than imported so this module stays dependency-free. */
export type NetworkName = "preview" | "preprod" | "mainnet" | "devnet";

/**
 * The only substandard mainnet may offer.
 *
 * ⛔ ADDING AN ID HERE IS A PRODUCTION RELEASE, not a configuration change. It says a module is
 * fit to be registered against real value by whoever walks in.
 */
export const MAINNET_MODULES: readonly string[] = ["rwa-token"];

/**
 * True when `network` may offer `moduleId` at all.
 *
 * Networks other than mainnet are unrestricted here; their modules are still subject to the
 * FLOW_* flags and to each flow's own default.
 */
export function isModuleAllowedOnNetwork(network: NetworkName, moduleId: string): boolean {
  if (network !== "mainnet") return true;
  return MAINNET_MODULES.includes(moduleId);
}

/** `ids` narrowed to what `network` may offer, in the order given. */
export function allowedModules<T extends string>(
  network: NetworkName,
  ids: readonly T[]
): readonly T[] {
  return ids.filter((id) => isModuleAllowedOnNetwork(network, id));
}
