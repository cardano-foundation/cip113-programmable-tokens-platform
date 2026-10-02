/**
 * Resuming a ceremony that stopped part-way, by asking the chain what actually landed.
 *
 * ⛔ WHY THIS EXISTS, AND IT IS NOT HYPOTHETICAL. The mainnet ceremony on 2026-10-02 submitted the
 * protocol genesis and then crashed before publishing the seven reference scripts. Four of the five
 * `BOOTSTRAP_STEPS` were on chain; the deployment was unusable, because every programmable
 * transaction reads those scripts.
 *
 * ⚑ THE ONE STEP THAT CAN BE RE-RUN, which is what makes recovery possible at all.
 * `buildReferenceScriptsTx` needs `plan.referenceScripts`, an address, a lovelace figure and wallet
 * UTxOs for funding. It consumes NO one-shot seed and nothing the genesis created, and it needs no
 * multisig witness — only the genesis did, and that is already on chain. So it can be rebuilt and
 * submitted at any later time, by the deployer alone.
 *
 * ⛔ BUT ONLY AGAINST THE SAME PARAMETERISATION, and that is the whole risk. The scripts are
 * compile-time parameterised; publishing a set derived from different inputs would hand the
 * deployment reference inputs carrying the WRONG scripts, which the SDK warns "does not fail
 * loudly". `planBootstrap(config)` is pure, so restoring the stored inputs re-derives the same
 * plan — but a promise of purity is not a proof that THESE inputs are the ones that built THAT
 * genesis.
 *
 * ⚑ SO THE PROOF IS A BYTE COMPARISON AGAINST THE CHAIN. The genesis published the protocol-params
 * datum, which commits to all five script credentials at once. `plan.datums.protocolParams` is the
 * same datum as the restored inputs derive it. If those two are byte-identical, the parameterisation
 * is provably the one that is already deployed — and if they differ, nothing should be submitted, no
 * matter how plausible the inputs looked.
 */
import { Bytes, AssetName, Data as EvoPlutusData } from "@evolution-sdk/evolution";

import type { BootstrapPlan } from "@easy1staking/cip113-sdk-ts";

/** What the chain says about a deployment, and whether step 5 may be published. */
export interface DeploymentOnChain {
  /** The protocol-params UTxO was found by its NFT at the derived address. */
  paramsUtxoFound: boolean;
  /** Byte comparison of the derived datum against the one on chain. `null` when not found. */
  paramsDatumMatches: boolean | null;
  onChainParamsDatumHex: string | null;
  derivedParamsDatumHex: string;
  /** UTxOs at always_fail's address that carry a reference script. */
  referenceScriptOutputs: number;
  /** How many the step publishes when it runs. */
  referenceScriptsExpected: number;
  /**
   * Reasons publishing must NOT proceed. Empty means every check passed.
   *
   * ⚑ A LIST, NOT A BOOLEAN. An operator mid-incident needs to know WHICH check failed; "not safe"
   * sends them looking at the wrong thing, and this is a path where the wrong thing costs 140 ADA
   * or a deployment that reads the wrong scripts.
   */
  blockers: string[];
}

/** Every asset unit (policyId + assetName, hex) a provider UTxO carries. */
function unitsOf(utxo: unknown): string[] {
  const map = (utxo as { assets?: { multiAsset?: { map?: Map<unknown, Map<unknown, bigint>> } } })
    ?.assets?.multiAsset?.map;
  if (!map || typeof map.entries !== "function") return [];
  const out: string[] = [];
  for (const [policyId, names] of map.entries()) {
    for (const [assetName] of names.entries()) {
      /**
       * ⚑ ONE BAD ENTRY MUST NOT BLIND THE WHOLE CHECK. `AssetName.toHex` and `Bytes.toHex` are
       * schema encoders and throw on anything they do not recognise. A single odd UTxO at the
       * address would otherwise abort the inspection — on a recovery path, where failing to read
       * the chain looks exactly like "nothing is there", which is the answer that invites a second
       * 140 ADA payment. Skip what cannot be read and keep going.
       */
      try {
        const policyHex = Bytes.toHex((policyId as { hash: Uint8Array }).hash);
        out.push(`${policyHex}${AssetName.toHex(assetName as never)}`);
      } catch {
        /* not an asset unit this can name — it is therefore not the NFT being looked for */
      }
    }
  }
  return out;
}

/** The inline datum of a provider UTxO as hex, or null when it has none (or only a hash). */
export function inlineDatumHex(utxo: unknown): string | null {
  const d = (utxo as { datumOption?: { _tag?: string; data?: unknown } })?.datumOption;
  if (!d || d._tag === "DatumHash" || d.data === undefined) return null;
  try {
    const hex = EvoPlutusData.toCBORHex(d.data as never);
    return typeof hex === "string" ? hex.toLowerCase() : null;
  } catch {
    return null;
  }
}

/**
 * Asks the chain what landed, and decides whether the reference scripts may be published.
 *
 * `utxosAt` is injected rather than taken from a client so this is testable without a network —
 * the same shape `awaitMultisigConfigUtxo` already uses.
 */
export async function inspectDeployment(params: {
  plan: BootstrapPlan;
  utxosAt: (address: string) => Promise<readonly unknown[]>;
}): Promise<DeploymentOnChain> {
  const plan = params.plan as unknown as {
    addresses: { protocolParams: string; issuanceCborHex: string };
    assetUnits: { protocolParamsNft: string };
    datums: { protocolParams: unknown };
    referenceScripts: readonly unknown[];
  };

  const derivedParamsDatumHex = EvoPlutusData.toCBORHex(
    plan.datums.protocolParams as never,
  ).toLowerCase();
  const expected = plan.referenceScripts.length;

  const blockers: string[] = [];

  // ---- 1. is the genesis on chain, and is it OUR genesis? ----
  const wantUnit = plan.assetUnits.protocolParamsNft.toLowerCase();
  const paramsUtxos = await params.utxosAt(plan.addresses.protocolParams);
  const paramsUtxo = paramsUtxos.find((u) =>
    unitsOf(u).some((unit) => unit.toLowerCase() === wantUnit),
  );
  const onChainParamsDatumHex = paramsUtxo ? inlineDatumHex(paramsUtxo) : null;
  const paramsDatumMatches = paramsUtxo ? onChainParamsDatumHex === derivedParamsDatumHex : null;

  if (!paramsUtxo) {
    blockers.push(
      `No protocol-params UTxO carrying ${wantUnit.slice(0, 16)}… was found at ` +
        `${plan.addresses.protocolParams}. Either the protocol genesis never landed, the indexer ` +
        "is behind, or these inputs belong to a DIFFERENT deployment. Publishing reference scripts " +
        "for a genesis that is not on chain would strand them.",
    );
  } else if (!paramsDatumMatches) {
    blockers.push(
      "The protocol-params datum ON CHAIN does not match the one these inputs derive, so the " +
        "restored parameterisation is NOT the one this deployment was built from. Publishing now " +
        "would hand the protocol reference inputs carrying the WRONG scripts — which fails at " +
        "redeemer evaluation, not at submission.\n" +
        `  derived:  ${derivedParamsDatumHex}\n` +
        `  on chain: ${onChainParamsDatumHex ?? "(no inline datum)"}`,
    );
  }

  // ---- 2. are the reference scripts already there? ----
  const alwaysFailUtxos = await params.utxosAt(plan.addresses.issuanceCborHex);
  const referenceScriptOutputs = alwaysFailUtxos.filter(
    (u) => (u as { scriptRef?: unknown })?.scriptRef != null,
  ).length;

  if (referenceScriptOutputs >= expected) {
    blockers.push(
      `${referenceScriptOutputs} reference-script output(s) are ALREADY at ` +
        `${plan.addresses.issuanceCborHex}, and the step publishes ${expected}. They are already ` +
        "on chain — publishing again would lock another ~140 ADA at an address nothing can spend " +
        "from, for no benefit. Rebuild the deployment record from the existing transaction instead.",
    );
  } else if (referenceScriptOutputs > 0) {
    blockers.push(
      `${referenceScriptOutputs} of ${expected} reference-script outputs are already at ` +
        `${plan.addresses.issuanceCborHex}. A PARTIAL set is not something this step can repair: ` +
        "it publishes all seven in REFERENCE_SCRIPT_ORDER, and the deployment record derives every " +
        "reference-input index from that one array. Resolve this by hand before continuing.",
    );
  }

  return {
    paramsUtxoFound: paramsUtxo !== undefined,
    paramsDatumMatches,
    onChainParamsDatumHex,
    derivedParamsDatumHex,
    referenceScriptOutputs,
    referenceScriptsExpected: expected,
    blockers,
  };
}
