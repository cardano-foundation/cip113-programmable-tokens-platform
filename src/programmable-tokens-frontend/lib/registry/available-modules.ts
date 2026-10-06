/**
 * Which substandards a given network is allowed to offer, and the decisions that follow from it.
 *
 * ## Why the rule is keyed on the NETWORK and not only on the FLOW_* flags
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
 *   - and several call sites fall back to the build-time list when `/api/config` does not answer,
 *     so a failed fetch would re-expose whatever the flags were hiding.
 *
 * ⇒ So the network allowlist is a FLOOR, applied in addition to the flags. The flags can still
 * narrow it; nothing can widen it without a rebuild. That matches how the network itself is
 * configured — `NEXT_PUBLIC_NETWORK` is inlined at build time (see `lib/utils/network.ts`), so
 * "mainnet offers only the RWA token" is a property of the image rather than of its environment.
 *
 * ## The rule, Giovanni 2026-10-06
 *
 * Mainnet offers **"RWA Token (German & Swiss profiles)"** (`rwa-token`) and nothing else —
 * his words: "everything else must be hidden". `dummy` is a template ("Not a product"),
 * `freeze-and-seize` has not been through the same review, and `kyc` / `kyc-extended` are already
 * disabled by default everywhere. Other networks are unchanged; the testnets are where the rest
 * is exercised.
 *
 * ## ⚑ EVERY DECISION THE RULE FEEDS LIVES IN THIS FILE, and that is the point
 *
 * An earlier revision kept the rule here and spread the decisions across four call sites, each
 * defended only by a regex over its own source text. An adversarial review then built three
 * mutations that KEPT the asserted text and broke the behaviour, with the whole suite green and a
 * mainnet build serving `{"dummy":true,"freeze-and-seize":true}` — one of them simply moved the
 * gated expression into an unused field and returned the ungated one.
 *
 * ⇒ The repair is not a better regex. `gateFlowFlags` and `isFlowOffered` below ARE the decisions
 * the route handler and the picker used to make inline; those call sites are now one-line
 * delegations, and the logic is covered by behaviour tests in `test-module-availability.js`.
 * A decision that only source text defends is not defended.
 *
 * ⚠ TAKES THE NETWORK AS AN ARGUMENT, deliberately. Reading `getCardanoNetwork()` in here would
 * make the rule untestable without a rebuild per case and would pull `lib/utils/network.ts` into
 * every compilation that wants the allowlist. The impure read stays at the call sites.
 */

/** Our own network names. Kept structural rather than imported so this module stays dependency-free. */
export type NetworkName = "preview" | "preprod" | "mainnet" | "devnet";

/**
 * The only substandard mainnet may offer: "RWA Token (German & Swiss profiles)".
 *
 * ⛔ ADDING AN ID HERE IS A PRODUCTION RELEASE, not a configuration change. It says a module is
 * fit to be registered against real value by whoever walks in.
 *
 * Frozen because `readonly` is erased at runtime: without this, any importer could push an id in.
 */
export const MAINNET_MODULES: readonly string[] = Object.freeze(["rwa-token"]);

/** The networks whose module set is deliberately unrestricted at this layer. */
const UNRESTRICTED_NETWORKS: readonly string[] = Object.freeze(["preview", "preprod", "devnet"]);

/**
 * True when `network` may offer `moduleId` at all.
 *
 * ⛔ AN UNRECOGNISED NETWORK REFUSES EVERYTHING. The obvious spelling — `if (network !==
 * "mainnet") return true` — is fail-OPEN: every typo, every empty string, `"Mainnet"` with a
 * capital, and any network added later all grant permission. It is safe only while
 * `getCardanoNetwork()` is the sole argument source, and that is a coupling no future call site
 * can see. Refusing is loud (the picker shows "No modules available") and cannot leak a module.
 */
export function isModuleAllowedOnNetwork(network: NetworkName, moduleId: string): boolean {
  if (network === "mainnet") return MAINNET_MODULES.includes(moduleId);
  if (UNRESTRICTED_NETWORKS.includes(network)) return true;
  return false;
}

/** `ids` narrowed to what `network` may offer, in the order given. */
export function allowedModules<T extends string>(
  network: NetworkName,
  ids: readonly T[]
): readonly T[] {
  return ids.filter((id) => isModuleAllowedOnNetwork(network, id));
}

/**
 * The runtime answer `/api/config` returns: the operator's flags INTERSECTED with the allowlist.
 *
 * Never a replacement — a flag may narrow the set, so `false` from either side is `false`.
 */
export function gateFlowFlags(
  network: NetworkName,
  flags: Readonly<Record<string, boolean>>
): Record<string, boolean> {
  return Object.fromEntries(
    Object.entries(flags).map(([id, enabled]) => [
      id,
      enabled && isModuleAllowedOnNetwork(network, id),
    ])
  );
}

/**
 * Whether one registered flow is offered, given the runtime response (which may be absent).
 *
 * ⛔ THE ALLOWLIST IS CHECKED BEFORE THE RUNTIME VALUE IS READ. The picker asks for flows with
 * their build-time `enabled` deliberately bypassed and then lets the response decide, so a
 * response from an older image, a cached one, or a hand-rolled proxy would otherwise be enough to
 * put a hidden module on screen.
 *
 * ⚑ AND AN UNKNOWN FLOW FALLS BACK TO `enabled`, WHICH HAS ALREADY BEEN GATED. `/api/config`
 * answers for a fixed set of five ids; a sixth flow registered later is absent from it, takes this
 * branch, and `enabled` came from `isFlowEnabled`, which asks the allowlist first. Fail-closed.
 */
export function isFlowOffered(
  network: NetworkName,
  flow: { readonly id: string; readonly enabled: boolean },
  runtimeFlags?: Readonly<Record<string, boolean | undefined>> | null
): boolean {
  if (!isModuleAllowedOnNetwork(network, flow.id)) return false;
  const runtime = runtimeFlags?.[flow.id];
  return runtime !== undefined ? runtime : flow.enabled;
}
