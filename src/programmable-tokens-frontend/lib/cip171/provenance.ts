/**
 * CIP-171 provenance availability.
 *
 * A CIP-171 record is a permanent, public claim that named scripts were built from a named
 * commit with a named compiler. Unlike a file, a metadatum cannot be deleted. So this module
 * answers one question — *can we make that claim, about the artefact we are actually
 * parameterising?* — and refuses when it cannot, rather than emitting something plausible.
 *
 * ## Where the pieces come from
 *
 * | field                          | source                                          |
 * |--------------------------------|-------------------------------------------------|
 * | `rawScriptHash`, `params`      | the SDK's `onParameterize` recorder             |
 * | `sourceUrl`, `commitHash`, …   | the SDK's bundled `UPSTREAM_PIN.json`           |
 * | `compilerVersion`              | the bundled blueprint's `preamble.compiler`     |
 *
 * ⚠ **Not from the backend, and that is the constraint this module exists around.**
 * `GET /substandards/{id}` returns `(id, name, description, validators[])` where each validator
 * is `(title, script_bytes, script_hash)`. The preamble is dropped at the API boundary, taking
 * the compiler version with it — so the served blueprint cannot supply provenance and cannot
 * even satisfy the SDK's own gate, which requires `preamble.compiler` to match the pin.
 *
 * ## So we use the SDK's bundled blueprint — and prove it is the same artefact first
 *
 * The record must describe the scripts actually deployed, which are parameterised from the
 * BACKEND's blueprint. Using the SDK's bundled copy for provenance is only legitimate if the two
 * are the same artefact. **That is checked here, not assumed** — every served validator must
 * match a bundled one on title, compiled code and hash, and the counts must agree.
 *
 * ⛔ The obvious alternative check is unavailable: the pin records a `sha256` of `plutus.json`
 * *as a file*, and the browser never sees the file — only the lossy DTO above. That digest
 * cannot be reproduced from `(title, script_bytes, script_hash)`, so validator-by-validator
 * equivalence is the strongest comparison available on this side.
 *
 * ⛔ And the two shortcuts are refused deliberately. Reading the compiler from the local
 * toolchain fails *worse* than emitting nothing — four Aiken versions built the blueprints in
 * play, and a wrong one makes the verifier rebuild against the wrong compiler and report the
 * mismatch as our defect. Copying the repo/commit/compiler triple into this repo creates a
 * second copy that drifts silently: on the day the SDK ships a new blueprint the copy still
 * encodes, still publishes, and still verifies — against the wrong source.
 *
 * ## What is still NOT checked here, stated rather than implied
 *
 * **That the pinned commit is still reachable upstream.** `provenance: VERIFIED` is a claim;
 * reachability is a fact, and they can diverge — this very pin previously named a commit a
 * squash-merge had orphaned, and the registry failed with `reference is not a tree` while the
 * field still read VERIFIED. Reachability needs the network and is asserted before publishing,
 * not here.
 */
import fesPin from "@easy1staking/cip113-sdk-ts/blueprints/substandards/freeze-and-seize/v0.1.0/UPSTREAM_PIN.json";
import fesBlueprint from "@easy1staking/cip113-sdk-ts/blueprints/substandards/freeze-and-seize/v0.1.0/plutus.json";
import type { UpstreamPin } from "@easy1staking/cip113-sdk-ts";
import type { PlutusBlueprint } from "@easy1staking/cip113-sdk-ts";
import type { SubstandardValidator } from "@/types/protocol";

/** Bundled provenance sources, by substandard id. */
const BUNDLED: Record<string, { blueprint: unknown; pin: unknown } | undefined> = {
  "freeze-and-seize": { blueprint: fesBlueprint, pin: fesPin },
};

export type Cip171SourceResult =
  | { available: true; blueprint: PlutusBlueprint; pin: UpstreamPin }
  | { available: false; reason: string };

/** Shown wherever the capability is offered but cannot be used. Names the cause, not the symptom. */
export const CIP171_UNAVAILABLE_REASON =
  "CIP-171 provenance is not available for this substandard. A record must name the source "
  + "commit and compiler that produced the validators, and can only be published when the "
  + "bundled provenance is VERIFIED and provably describes the same scripts this deployment "
  + "parameterises.";

function reason(detail: string): { available: false; reason: string } {
  return { available: false, reason: `${CIP171_UNAVAILABLE_REASON} ${detail}` };
}

/**
 * Resolve the provenance sources for a substandard, or explain why they cannot be used.
 *
 * `servedValidators` is what the backend returned for this substandard. It is not used to build
 * the record — it is used to prove the bundled blueprint describes the same scripts.
 *
 * ⚠ `available: false` is a correct outcome, not an error path to work around. The caller must
 * omit the record entirely rather than substitute defaults.
 */
export function getCip171Source(
  substandardId: string,
  servedValidators: readonly SubstandardValidator[] | null | undefined
): Cip171SourceResult {
  const bundled = BUNDLED[substandardId];
  if (!bundled) {
    return reason(`No provenance is bundled for "${substandardId}".`);
  }

  const pin = bundled.pin as UpstreamPin;
  const blueprint = bundled.blueprint as PlutusBlueprint;

  // The SDK re-checks this and refuses too. Stated here as well because a guard enforced
  // elsewhere still needs its reason where someone would try to route around it: a pin that is
  // not VERIFIED names a source nobody can reproduce, and dummy/v0.1.0 is permanently UNKNOWN —
  // its upstream repo and commit are both null, so no rebuild can ever manufacture them.
  if (pin.provenance !== "VERIFIED") {
    return reason(`Its bundled pin is "${pin.provenance}", not VERIFIED.`);
  }

  if (!servedValidators || servedValidators.length === 0) {
    return reason("The deployed blueprint has not been loaded yet.");
  }

  // Prove the bundled artefact IS the deployed one. Without this the record would be
  // well-formed, verifiable, and about the wrong scripts — the worst outcome in this area,
  // because nothing downstream can detect it.
  const bundledByTitle = new Map(blueprint.validators.map((v) => [v.title, v]));
  if (bundledByTitle.size !== servedValidators.length) {
    return reason(
      `The deployed blueprint has ${servedValidators.length} validators and the bundled one has `
        + `${bundledByTitle.size}; they are different artefacts.`
    );
  }
  for (const served of servedValidators) {
    const b = bundledByTitle.get(served.title);
    if (!b) {
      return reason(`The bundled blueprint has no validator titled "${served.title}".`);
    }
    if (b.compiledCode !== served.script_bytes || b.hash !== served.script_hash) {
      return reason(
        `Validator "${served.title}" differs between the deployed and bundled blueprints; the `
          + `bundled provenance describes a different build.`
      );
    }
  }

  return { available: true, blueprint, pin };
}

/**
 * ⛔ THE REMAINING BLOCKER, and it is no longer about provenance.
 *
 * Provenance is now obtainable — the pin and the bundled blueprint are both here and the
 * equivalence check above proves they describe the deployed scripts. What is missing is the
 * OTHER half of the record: which arguments were applied to which raw script.
 *
 * That must be DERIVED from the calls that actually parameterised, never transcribed beside
 * them. `createFESScripts(blueprint, onParameterize)` reports exactly that — but
 * `freezeAndSeizeSubstandard({ blueprint, deployment })` does not accept a recorder and calls
 * `createFESScripts(config.blueprint)` internally with none, so the registration path's
 * parameterisations are unobservable from here.
 *
 * Its register path parameterises FIVE scripts — `issuerAdmin`, `transfer`, `blacklistMint`,
 * `blacklistSpend`, and `issuanceMint` from the standard scripts. This app separately calls
 * `createFESScripts` to pre-compute the blacklist policy id, so driving that call with a
 * recorder would capture ONE of the five and silently under-report the rest.
 *
 * ⚠ A record covering 1 of 5 is not a partial success. It encodes, publishes and VERIFIES —
 * against scripts whose parameters were never stated. The SDK's own docs warn that
 * freeze-and-seize once shipped covering 3 of 4 because a fixture missed `blacklist_spend`, and
 * that reconstructing the list by hand agrees with the deployment right up until it does not.
 *
 * ⇒ Unblocked by one additive change in cip113-sdk-ts: forward an optional `onParameterize`
 * from `freezeAndSeizeSubstandard(config)` into its `createFESScripts` call. Then this returns
 * true and the record is derived rather than assembled.
 */
export const CIP171_RECORDER_UNAVAILABLE =
  "The registration path's parameterisations cannot be observed: freezeAndSeizeSubstandard does "
  + "not forward an onParameterize recorder to createFESScripts, so only one of its five "
  + "parameterised scripts would be captured. A record covering one of five still verifies, "
  + "against scripts whose parameters were never stated.";

/**
 * Whether the CIP-171 option can be offered as usable at all for this substandard.
 *
 * ⚠ Deliberately false while the recorder is unavailable, even though provenance now resolves.
 * Enabling on provenance alone would produce a checkbox that ticks, submits, and attaches an
 * under-covered record — the silently-wrong outcome this whole module exists to prevent.
 */
export function isCip171Available(_substandardId: string): boolean {
  return false;
}
