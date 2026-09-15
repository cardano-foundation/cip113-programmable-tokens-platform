/**
 * Verifying a deployment somebody else produced, and turning it into what this platform loads.
 *
 * The page cannot build the bootstrap transactions yet, but a deployment made by the SDK's
 * harness is still a deployment — and the two things the platform needs from it are both
 * doable here: prove the hashes, then emit the record.
 *
 * ## What "verify" means, precisely
 *
 * Not "the file parses" and not "the fields look like hashes". Every derivable script hash is
 * RE-DERIVED from the blueprint and the deployment's own parameters, and compared against what
 * the deployment claims. A record whose `transfer.scriptHash` was transcribed by hand, or
 * copied from a different deployment, fails here rather than at the first transfer.
 *
 * ## The conversion is one field, and that is worth stating
 *
 * A `protocol-bootstraps-{network}.json` entry IS a `DeploymentParams` plus
 * `schemaVersion: 3` — measured against the live preview pair, every shared key byte-identical
 * and `schemaVersion` the only addition. So this adds a field rather than mapping a shape, and
 * anything that looks like mapping here would mean the shapes had diverged.
 */
import { assertDeploymentScripts } from "@easy1staking/cip113-sdk-ts";
import type { PlutusBlueprint } from "@easy1staking/cip113-sdk-ts";

/** One hash the SDK re-derived and compared. */
export interface DerivedCheck {
  name: string;
  derived: string;
  deployed: string;
  matches: boolean;
}

export interface VerificationResult {
  ok: boolean;
  checks: DerivedCheck[];
  /** Only the failures, for a caller that wants to show just those. */
  mismatches: DerivedCheck[];
  /** Present when the SDK refused the input outright rather than reporting mismatches. */
  error?: string;
}

/**
 * Re-derive and compare. Never throws: a mismatch is an ANSWER the operator needs to see in
 * full, not an exception that hides the other twelve results behind the first bad one.
 */
export function verifyDeployment(
  blueprint: PlutusBlueprint,
  deployment: unknown,
): VerificationResult {
  try {
    const raw = assertDeploymentScripts(blueprint, deployment as never) ?? [];
    const checks: DerivedCheck[] = raw.map((c) => ({
      name: c.name,
      derived: c.derived,
      deployed: c.deployed,
      matches: c.derived === c.deployed,
    }));
    const mismatches = checks.filter((c) => !c.matches);
    return { ok: mismatches.length === 0 && checks.length > 0, checks, mismatches };
  } catch (e) {
    // DeploymentMismatchError carries the per-script comparisons; anything else is a refusal
    // (wrong shape, missing field) and has none.
    const withMismatches = e as { mismatches?: { name: string; derived: string; deployed: string }[] };
    if (Array.isArray(withMismatches.mismatches)) {
      const checks = withMismatches.mismatches.map((c) => ({ ...c, matches: false }));
      return { ok: false, checks, mismatches: checks, error: (e as Error).message };
    }
    return { ok: false, checks: [], mismatches: [], error: (e as Error).message };
  }
}

/**
 * The bootstrap record this platform loads.
 *
 * Refuses to emit one from a deployment that did not verify — an unverified record is exactly
 * the thing that gets committed and trusted later, and by then nobody re-checks it.
 */
export function toBootstrapRecord(
  deployment: Record<string, unknown>,
  verification: VerificationResult,
): Record<string, unknown>[] {
  if (!verification.ok) {
    throw new Error(
      "Refusing to emit a bootstrap record from a deployment that did not verify. " +
        (verification.mismatches.length > 0
          ? `${verification.mismatches.length} script hash(es) do not re-derive: ` +
            verification.mismatches.map((m) => m.name).join(", ")
          : verification.error ?? "no checks ran"),
    );
  }
  // schemaVersion first so the emitted file reads like the committed ones.
  return [{ schemaVersion: 3, ...deployment }];
}
