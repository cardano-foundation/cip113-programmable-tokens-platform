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

/**
 * The gate that runs BEFORE anything is submitted, now that the record cannot.
 *
 * ⛔ WHY THIS EXISTS AT ALL. The old invariant — "a plan that does not verify is never shown a
 * signature prompt" — worked because all five transactions, with every pre-computed hash,
 * existed before the first submission. Splitting the ceremony into two phases dissolves that:
 * at phase one there is no complete `DeploymentParams` to verify, because the config UTxO
 * outref and all seven `*RefInput`s belong to transactions that have not happened. Without a
 * replacement, phase one would be ungated and the one-shot seeds would be spent on a protocol
 * nobody had checked.
 *
 * ## What it checks instead, and why that is worth more than it sounds
 *
 * Two INDEPENDENT derivations of the same twelve values: this platform's `derive.ts`, and the
 * SDK's `planBootstrap`. They share a blueprint and nothing else — different code, different
 * repository, written months apart. Agreement means the parameterisation chain is right in both
 * or wrong in both in exactly the same way; disagreement means one of them is broken and
 * nothing may be spent.
 *
 * The full-record `verifyDeployment` still runs after phase two, on the assembled params. This
 * does not replace it — it covers the window the split opened.
 */
export function verifyPlanScripts(
  ours: {
    alwaysFailHash: string; issuanceCborHexPolicy: string; registryPolicy: string;
    paramsPolicy: string; programmableLogicBase: string; transfer: string; thirdParty: string;
    unfracking: string; issuanceLogic: string; programmableLogicGlobal: string;
    upgradeMultisig: string; unfrackingParameter: string;
  },
  plan: {
    scripts: Record<string, { hash: string }>;
    unfrackingParameter: string;
  },
): VerificationResult {
  const pairs: Array<[string, string, string]> = [
    ["always_fail", ours.alwaysFailHash, plan.scripts.alwaysFail?.hash],
    ["issuance_cbor_hex_mint", ours.issuanceCborHexPolicy, plan.scripts.issuanceCborHexMint?.hash],
    ["registry", ours.registryPolicy, plan.scripts.registry?.hash],
    ["protocol_params", ours.paramsPolicy, plan.scripts.protocolParams?.hash],
    ["programmable_logic_base", ours.programmableLogicBase, plan.scripts.programmableLogicBase?.hash],
    ["transfer", ours.transfer, plan.scripts.transfer?.hash],
    ["third_party", ours.thirdParty, plan.scripts.thirdParty?.hash],
    ["unfracking", ours.unfracking, plan.scripts.unfracking?.hash],
    ["issuance_logic", ours.issuanceLogic, plan.scripts.issuanceLogic?.hash],
    ["programmable_logic_global", ours.programmableLogicGlobal, plan.scripts.programmableLogicGlobal?.hash],
    ["upgrade_multisig", ours.upgradeMultisig, plan.scripts.upgradeMultisig?.hash],
    ["unfracking_parameter", ours.unfrackingParameter, plan.unfrackingParameter],
  ];

  const checks: DerivedCheck[] = [];
  const mismatches: DerivedCheck[] = [];
  for (const [name, mine, theirs] of pairs) {
    // A missing value is a MISMATCH, not a skip. Silently passing over a hash the SDK did not
    // produce would turn a structural change upstream into a check that quietly shrank.
    const ok = Boolean(mine) && Boolean(theirs) && mine.toLowerCase() === theirs.toLowerCase();
    const check: DerivedCheck = {
      name,
      derived: mine ?? "(absent)",
      deployed: theirs ?? "(absent)",
      matches: ok,
    };
    checks.push(check);
    if (!ok) mismatches.push(check);
  }

  if (checks.length !== pairs.length) {
    return { ok: false, checks, mismatches, error: "the cross-check list changed shape" };
  }
  return {
    ok: mismatches.length === 0,
    checks,
    mismatches,
    error:
      mismatches.length === 0
        ? undefined
        : `${mismatches.length} of ${checks.length} script hashes differ between this platform's ` +
          "derivation and the SDK's. One of the two is wrong and nothing may be submitted: the " +
          "seeds are one-shot, so a protocol deployed from a bad parameterisation cannot be redone " +
          "with the same inputs.",
  };
}
