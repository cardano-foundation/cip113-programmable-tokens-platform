/**
 * Which of the six withdraw-0 credentials a bootstrap must register are already registered?
 *
 * Asked when resuming a ceremony that was interrupted after phase one. Stake registration is the
 * flakiest part of a bootstrap, so this is also the question most likely to be asked while the
 * answer is unavailable.
 *
 * ## ⛔ THREE ANSWERS, AND "UNKNOWN" MUST NEVER COLLAPSE INTO ONE OF THE OTHER TWO
 *
 * Both wrong answers are expensive, in different directions, which is why neither is an acceptable
 * default:
 *
 *   - A wrong "unregistered" makes the resume re-register. The ledger refuses the whole transaction
 *     with `StakeKeyAlreadyRegisteredDELEG` ("Trying to re-register some already known
 *     credentials") — so one bad answer costs the step, and the deposits of the credentials that
 *     WOULD have registered alongside it.
 *   - A wrong "registered" makes the resume skip a certificate that was never there. That surfaces
 *     much later as a withdrawal failing on an unregistered account — in the genesis, after more
 *     has been spent.
 *
 * So a failure is reported as `unknown` and the operator decides. The backend takes the same view
 * from the other side: `ScriptRegistrationService` asks the LEDGER first and falls back to its own
 * index, because "absence from the index does not mean absence from the chain" — its index starts
 * at `sync-start-slot`, which on a fresh bootstrap is after these registrations.
 *
 * ## ⚠ THIS PROBE IS NOT READ-ONLY
 *
 * `GET /script-registration/check` calls `noteRegistrationAttempted` when it answers "no", by
 * design — the freeze-and-seize path relies on that note existing before a later attestation can be
 * confirmed. Probing six credentials therefore writes up to six notes. Harmless here and worth
 * knowing: this is not a query you can treat as free.
 *
 * ## ⚠ AND IT NEEDS THE BACKEND RUNNING
 *
 * On a network with no deployment recorded, `ProtocolBootstrapService` refuses to start unless
 * `cip113.allow-no-deployment=true`. That is exactly the state a first bootstrap is in, so a resume
 * on preprod or mainnet needs that flag set. Every credential then answers `unknown`, which is
 * correct rather than convenient: the ceremony can still proceed on the operator's judgement.
 */

import { rewardAddress } from "@easy1staking/cip113-sdk-ts";
import { getCardanoNetwork, type CardanoNetwork } from "../utils/network";

/**
 * How one credential is asked about — supplied by the caller, with NO default.
 *
 * ⛔ REQUIRED, NOT INJECTABLE-WITH-A-DEFAULT, and that is the design. What is worth testing in this
 * file is what happens when the answer does NOT arrive, and a module that reaches the network itself
 * cannot be made to fail on demand. Keeping the transport out means this file holds only the
 * three-state rule — which is the part that must never guess — and the page says where answers come
 * from (`GET /script-registration/check`). A default would quietly become the thing everyone uses
 * and the tested path would stop being the real one.
 */
export type RegistrationChecker = (stakeAddress: string) => Promise<{ isRegistered?: boolean } | undefined>;

/** Mainnet is network id 1; every testnet is 0. */
function networkId(network: CardanoNetwork): number {
  return network === "mainnet" ? 1 : 0;
}

export type RegistrationState = "registered" | "unregistered" | "unknown";

export interface CredentialStatus {
  /** The name from the SDK's `STAKE_REGISTRATION_ORDER`. */
  name: string;
  scriptHash: string;
  stakeAddress: string;
  state: RegistrationState;
  /** Why it is unknown, for an operator who has to decide what to do about it. */
  detail?: string;
}

export interface RegistrationProbe {
  credentials: readonly CredentialStatus[];
  /** True only when every credential answered, in either direction. */
  complete: boolean;
  registered: number;
  unknown: number;
}

/**
 * Probe the six credentials named by a plan.
 *
 * Takes the plan's `stakeCredentialScripts` — already in `STAKE_REGISTRATION_ORDER` — so the
 * ordering comes from the SDK rather than from a list restated here. That order moved once already
 * (`stake-registrations` went from last to third at 0.12.0) without the strings changing.
 */
export async function probeRegistrations(
  stakeCredentialScripts: readonly { hash: string }[],
  names: readonly string[],
  check: RegistrationChecker,
  network: CardanoNetwork = getCardanoNetwork(),
): Promise<RegistrationProbe> {
  const nid = networkId(network);
  const credentials = await Promise.all(
    stakeCredentialScripts.map(async (script, i): Promise<CredentialStatus> => {
      const name = names[i] ?? `credential ${i}`;
      let stakeAddress: string;
      try {
        stakeAddress = rewardAddress(nid, script.hash);
      } catch (e) {
        // A hash we cannot turn into a reward address is a derivation problem, not a chain one.
        return {
          name, scriptHash: script.hash, stakeAddress: "",
          state: "unknown",
          detail: `could not derive a reward address: ${(e as Error).message}`,
        };
      }
      try {
        const res = await check(stakeAddress);
        // ⛔ A MISSING FIELD IS NOT A FALSE. An older or errored backend returning `{}` would
        // otherwise read as "not registered" and send the resume off to re-register everything.
        if (typeof res?.isRegistered !== "boolean") {
          return {
            name, scriptHash: script.hash, stakeAddress,
            state: "unknown",
            detail: "the backend answered without an isRegistered field",
          };
        }
        return {
          name, scriptHash: script.hash, stakeAddress,
          state: res.isRegistered ? "registered" : "unregistered",
        };
      } catch (e) {
        return {
          name, scriptHash: script.hash, stakeAddress,
          state: "unknown",
          detail: (e as Error).message,
        };
      }
    }),
  );
  return {
    credentials,
    complete: credentials.every((c) => c.state !== "unknown"),
    registered: credentials.filter((c) => c.state === "registered").length,
    unknown: credentials.filter((c) => c.state === "unknown").length,
  };
}

/**
 * Can the registration step be SKIPPED on resume?
 *
 * Only when every credential is known to be registered. Anything else — one unregistered, one
 * unknown — means the step still has work, and the operator is told which.
 */
export function registrationsComplete(probe: RegistrationProbe | null): boolean {
  return !!probe && probe.credentials.length > 0 && probe.credentials.every((c) => c.state === "registered");
}
