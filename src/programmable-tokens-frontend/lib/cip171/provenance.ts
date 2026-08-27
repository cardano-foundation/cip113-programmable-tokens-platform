/**
 * CIP-171 provenance availability.
 *
 * A CIP-171 record is a permanent, public claim that named scripts were built from a named
 * commit with a named compiler. Unlike a file, a metadatum cannot be deleted. So this module
 * exists to answer one question honestly — *can we make that claim right now?* — and to refuse
 * when the answer is no, rather than emitting something plausible.
 *
 * ## Why this is currently unavailable
 *
 * A record needs six fields. Two of them the SDK reports to us directly (it is the only party
 * that knows them), and three we cannot obtain at all:
 *
 * | field             | source                                              | available |
 * |-------------------|-----------------------------------------------------|-----------|
 * | `rawScriptHash`   | `createFESScripts(bp, onParameterize)` callback      | yes       |
 * | `params`          | same callback                                        | yes       |
 * | `compilerVersion` | `blueprint.preamble.compiler.version`                | **no**    |
 * | `sourceUrl`       | the deploying system                                 | **no**    |
 * | `commitHash`      | the deploying system                                 | **no**    |
 * | `env`             | `""` unless built with `--env`                       | yes       |
 *
 * The backend *holds* the compiler version — its `plutus.json` for freeze-and-seize declares
 * `"compiler": {"name": "Aiken", "version": "v1.1.21+42babe5"}` — but `GET /substandards/{id}`
 * returns a lossy DTO, `Substandard(id, name, description, validators[])` where each validator
 * is `(title, script_bytes, script_hash)`. **The preamble is dropped at the API boundary**, and
 * with it the only in-band statement of which compiler produced the artefact.
 *
 * ⚠ Do not "fix" this by reading the version off the local toolchain. cip113-sdk-ts documents
 * why, and it is not a style preference: the blueprints in play were built by four different
 * Aiken versions, and a record naming your local one fails WORSE than an absent record — the
 * verifier rebuilds with the wrong compiler and reports the resulting mismatch as our defect.
 *
 * ⚠ Nor by hardcoding the repo and commit here. A copied repo/commit/compiler triple drifts from
 * the artefact silently, and silently-wrong provenance that still verifies is the failure this
 * whole feature has to avoid.
 *
 * ## What unblocks it
 *
 * Either of two changes, and the second is architecturally the right one:
 *
 * 1. **cip113-sdk-ts exposes its bundled pin.** Each `blueprints/**` directory ships an
 *    `UPSTREAM_PIN.json` carrying repo, path, commit, compiler and a `provenance` status. The
 *    package ships those files but its `exports` map does not expose them, so they are not
 *    importable. A `provenanceFromPin(substandardId)` helper would close this.
 * 2. **The backend serves provenance per substandard.** This is what cip113-sdk-ts's own
 *    `docs/provenance.md` prescribes — *"Publishing is the deploying system's job — the record's
 *    content (this repo, this commit, this compiler, these parameters) only exists at deploy
 *    time."* The SDK's pin describes what the SDK bundled; this platform must describe what it
 *    deploys. They coincide today, verified by comparing the pin's sha256 against this repo's
 *    committed `plutus.json` — but that is a fact about today, and the record is permanent.
 *
 * When either lands, `getCip171Provenance` is the only function that changes.
 */

/** The provenance status recorded in a blueprint pin. Only VERIFIED may be emitted. */
export type ProvenanceStatus = "VERIFIED" | "UNVERIFIED" | "UNKNOWN";

/** Everything a `Cip171Record` needs that does NOT come from the parameterisation callback. */
export interface Cip171Provenance {
  /** Git-clone-compatible repo URL. */
  sourceUrl: string;
  /** Raw git commit hash hex — 40 chars for SHA-1, 64 for SHA-256. */
  commitHash: string;
  /** Project directory INSIDE the repo, e.g. `src/substandards/freeze-and-seize`. Not the root. */
  sourcePath: string;
  /** Exact compiler version from the artefact's preamble, e.g. `v1.1.21+42babe5`. */
  compilerVersion: string;
  /** The `--env` value the script was built with. Empty string means none — never null. */
  env: string;
}

export type Cip171ProvenanceResult =
  | { available: true; provenance: Cip171Provenance }
  | { available: false; reason: string };

/**
 * Human-readable reason shown wherever the capability is offered but cannot be used.
 *
 * Names the CAUSE rather than the symptom: a reason that says "not available" is a dead end for
 * whoever reads it in three months, while one that names the DTO is a bug report they can act on.
 */
export const CIP171_UNAVAILABLE_REASON =
  "Provenance is not available to the browser yet. A CIP-171 record must name the compiler, "
  + "source repository and commit that produced the validators. The backend holds the compiler "
  + "version in the substandard's plutus.json preamble but drops it from GET /substandards/{id}, "
  + "which returns only (title, script_bytes, script_hash) — and it never serves the repo or "
  + "commit at all. Emitting a record without them, or guessing them from the local toolchain, "
  + "would publish a permanent and unverifiable claim.";

/**
 * Resolve provenance for a substandard, or explain why it cannot be resolved.
 *
 * ⚠ Returning `available: false` is a correct and expected outcome, not an error path to be
 * worked around. The caller must omit the record entirely rather than substituting defaults.
 *
 * ⚠ And when this is implemented against a pin: **refuse any pin whose `provenance` is not
 * `VERIFIED`**. `substandards/dummy/v0.1.0` is permanently `UNKNOWN` — its upstream repo and
 * commit are both null and no rebuild can manufacture them — so that blueprint must never be
 * emittable, and that is a permanent state rather than one a later fix clears.
 */
export function getCip171Provenance(_substandardId: string): Cip171ProvenanceResult {
  // No source of truth is reachable from the browser today. See the module doc: this returns
  // the single point that changes when the SDK exposes its pin, or the backend serves provenance.
  return { available: false, reason: CIP171_UNAVAILABLE_REASON };
}

/** Whether the CIP-171 option can be offered as usable for this substandard. */
export function isCip171Available(substandardId: string): boolean {
  return getCip171Provenance(substandardId).available;
}
