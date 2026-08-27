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
import { buildCip171RecordFromPin } from "@easy1staking/cip113-sdk-ts";
import type { UpstreamPin, PlutusBlueprint, Cip171Record, ParameterizedScript } from "@easy1staking/cip113-sdk-ts";
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
 * Expected number of scripts a freeze-and-seize registration record must cover.
 *
 * The plugin's register path parameterises four FES scripts — `issuerAdmin`, `transfer`,
 * `blacklistMint`, `blacklistSpend`. It also parameterises `issuanceMint`, which is legitimately
 * ABSENT from this record: that validator lives in the STANDARD blueprint, not the FES one, so it
 * cannot appear in a record whose `sourceUrl` and commit are FES's. `buildCip171RecordFromPin`
 * refuses any script absent from the pinned blueprint, so the gate enforces this rather than
 * relying on anyone remembering it.
 *
 * ⚠ **Pinned deliberately, so a human decides when it moves.** A record covering fewer scripts
 * than the deployment parameterised still encodes, still publishes and still VERIFIES — against
 * scripts whose parameters were never stated. That failure is invisible downstream, so a silent
 * drop from four to one must be impossible rather than merely unlikely.
 */
export const FES_EXPECTED_COVERAGE = 4;

/**
 * Whether the CIP-171 option can be offered as usable for this substandard.
 *
 * ⚠ **The predicate is "can we build the WHOLE record?", not "can we make the provenance claim?"**
 * Those came apart once already: provenance became resolvable while the parameterisation half was
 * still unobservable, and a gate testing only provenance would have flipped to true and shipped a
 * checkbox that attached a one-of-five record. A guard is only as good as the condition it tests.
 *
 * Currently false: capturing the parameterisations needs `freezeAndSeizeSubstandard` to forward an
 * `onParameterize` recorder, which is committed upstream but not yet in a published version. Flip
 * this when the dependency is bumped — and note the coverage assertion in `buildFesCip171Record`
 * is the real protection: even enabled early, a short record cannot be emitted.
 */
export function isCip171Available(_substandardId: string): boolean {
  return false;
}

export const CIP171_RECORDER_UNAVAILABLE =
  "Attaching provenance needs the SDK to report which arguments were applied to which script "
  + "during registration. That recorder is committed upstream but not in a published version yet, "
  + "so a record built now would cover one of the four parameterised scripts — and a short record "
  + "still verifies, against scripts whose parameters were never stated.";

/**
 * Assemble the record for a freeze-and-seize registration, or refuse.
 *
 * `recorded` must come from the SDK's `onParameterize` callback — DERIVED from the calls that
 * actually parameterised, never transcribed beside them. The map is keyed by the unapplied hash
 * and its values are in application order; both are properties of those calls and of nothing
 * else, so a hand-maintained second list agrees with the deployment right up until it does not.
 *
 * ⚠ Returns null rather than a partial record. Every refusal here is a case where emitting
 * something would produce a permanent public claim that verifies while being wrong.
 */
export function buildFesCip171Record(
  substandardId: string,
  servedValidators: readonly SubstandardValidator[] | null | undefined,
  recorded: readonly ParameterizedScript[]
): { record: Cip171Record } | { record: null; reason: string } {
  const source = getCip171Source(substandardId, servedValidators);
  if (!source.available) return { record: null, reason: source.reason };

  // The coverage assertion. This is what makes a silent one-of-four impossible: if the SDK in use
  // does not forward the recorder, or a future change stops parameterising one of them, we refuse
  // instead of publishing a record that omits it without saying so.
  if (recorded.length !== FES_EXPECTED_COVERAGE) {
    return {
      record: null,
      reason:
        `Expected ${FES_EXPECTED_COVERAGE} parameterised scripts for a freeze-and-seize `
        + `registration, recorded ${recorded.length}. A record covering fewer still verifies, `
        + `against scripts whose parameters were never stated, so it is not emitted.`,
    };
  }

  const distinct = new Set(recorded.map((r) => r.rawScriptHash)).size;
  if (distinct !== recorded.length) {
    return {
      record: null,
      reason:
        `Recorded ${recorded.length} parameterisations but only ${distinct} distinct raw script `
        + `hashes. Grouping by hash would collapse two genuinely different scripts.`,
    };
  }

  return { record: buildCip171RecordFromPin(source.blueprint, source.pin, recorded) };
}
