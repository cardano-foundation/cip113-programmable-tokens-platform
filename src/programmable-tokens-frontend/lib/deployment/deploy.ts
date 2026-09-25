/**
 * Driving a core deployment from the browser: plan, verify, submit, record.
 *
 * `bootstrap.ts` knows how to BUILD the six transactions and nothing about where a wallet or a
 * chain comes from. This is the layer that supplies both — an Evolution signing client over
 * the connected CIP-30 wallet and Blockfrost — so the builder stays testable and this stays
 * thin.
 *
 * ## The plan is verified before it is offered for signature
 *
 * `buildBootstrapPlan` returns a complete `DeploymentParams`, transaction hashes included,
 * because every hash is pre-computed by the chained build. That makes a genuine pre-flight
 * possible: the plan is handed to the SAME verification an operator would run against a
 * finished deployment, re-deriving every script hash from the pinned blueprint. A plan that
 * does not verify is never shown a signature prompt.
 *
 * This is not circular. The derivation and the assertion come from opposite directions —
 * `createStandardScripts` applies parameters forward, `assertDeploymentScripts` re-derives from
 * the recorded `DeploymentParams` — and the second reads the fields the RECORD will carry, so
 * it catches a field written into the wrong slot, which is the failure the alpha.3 migration
 * actually shipped.
 */
import {
  evoClient,
  previewChain,
  preprodChain,
  mainnetChain,
  paymentCredentialHash,
} from "@easy1staking/cip113-sdk-ts";
import type { PlutusBlueprint } from "@easy1staking/cip113-sdk-ts";

import {
  buildPlan,
  buildPhaseOne,
  buildProtocolGenesis,
  buildReferenceScripts,
  awaitMultisigConfigUtxo,
  selectBootstrapSeeds,
  assembleDeploymentParams,
  type BootstrapPlan,
  type CeremonyStep,
  type CeremonyContext,
  selectSeedUtxos,
  type ChainUtxo,
} from "./ceremony";
import { deriveCoreDeployment, type DeploymentSeeds } from "./derive";
import { verifyDeployment, verifyPlanScripts, type VerificationResult } from "./verify";
import { EvoAddress, EvoAssets, EvoTransaction, outputAssets } from "@easy1staking/cip113-sdk-ts";

/** Lovelace parked in each prepared seed. Enough to be a useful input, small enough to be cheap. */
const SEED_PREP_LOVELACE = 5_000_000n;
import type { UpstreamPin } from "./blueprint";
import type { ResolvedMultisig } from "./multisig";
import type { CardanoNetwork } from "../utils/network";

function chainFor(network: CardanoNetwork) {
  switch (network) {
    case "mainnet":
      return mainnetChain;
    case "preprod":
      return preprodChain;
    case "preview":
      return previewChain;
  }
}

function blockfrostBaseUrl(network: CardanoNetwork): string {
  return (
    process.env.NEXT_PUBLIC_BLOCKFROST_URL || `https://cardano-${network}.blockfrost.io/api/v0`
  );
}

/**
 * Is this reward address registered RIGHT NOW?
 *
 * Not "has it ever been seen": Blockfrost keeps returning an account after it is deregistered,
 * with `active: false`, and a deregistered credential must be registered again. Reading
 * presence as registration would build a delegate-only transaction for a credential that has
 * no registration to delegate — and that failure lands after four transactions have already
 * been submitted.
 *
 * Fails CLOSED: a network error throws rather than guessing, because both guesses are wrong in
 * a way that only surfaces mid-deployment.
 */
async function isStakeRegisteredViaBlockfrost(
  network: CardanoNetwork,
  projectId: string,
  rewardAddress: string,
): Promise<boolean> {
  const res = await fetch(`${blockfrostBaseUrl(network)}/accounts/${rewardAddress}`, {
    headers: { project_id: projectId },
  });
  if (res.status === 404) return false;
  if (!res.ok) {
    throw new Error(
      `Could not determine whether ${rewardAddress} is already registered (Blockfrost ` +
        `returned ${res.status}). Refusing to guess: the wrong answer is only discovered ` +
        `after four transactions have been submitted.`,
    );
  }
  const body = (await res.json()) as { active?: boolean };
  return body.active === true;
}

/**
 * An Evolution signing client over the connected CIP-30 wallet.
 *
 * Built the same way in every entry point below, because a client built with a different
 * provider or chain reads a different UTxO set — and these functions hand each other outrefs.
 */
function signingClient(network: CardanoNetwork, rawWalletApi: unknown) {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  if (!projectId) {
    throw new Error(
      "NEXT_PUBLIC_BLOCKFROST_API_KEY is not set. A deployment has to read the wallet's UTxOs, " +
        "the protocol parameters and the nominee's registration state before it can build " +
        "anything.",
    );
  }
  const chain = chainFor(network);
  return {
    chain,
    client: evoClient(chain)
      .withCip30(rawWalletApi as never)
      .withBlockfrost({ projectId, baseUrl: blockfrostBaseUrl(network) }),
  };
}

export interface WalletSeeds {
  /** Three distinct UTxOs fit to be one-shot seeds, or null when the wallet has no three. */
  seeds: DeploymentSeeds | null;
  /** How many wallet UTxOs could serve as a seed. Below three, the wallet needs preparing. */
  usableCount: number;
}

/**
 * What the connected wallet can offer as one-shot seeds.
 *
 * Read rather than asked for. Three specific outrefs are not something an operator should have
 * to find and transcribe, and a transcription error here is not caught by anything: a wrong
 * outref is still a valid parameter, it simply parameterises the deployment against a UTxO the
 * transaction cannot consume, and the failure names an input rather than a typo.
 */
export async function findWalletSeeds(
  network: CardanoNetwork,
  rawWalletApi: unknown,
  changeAddress: string,
): Promise<WalletSeeds> {
  const { client } = signingClient(network, rawWalletApi);
  const utxos = (await client.getUtxos(
    EvoAddress.fromBech32(changeAddress) as never,
  )) as unknown as ChainUtxo[];
  const seeds = selectSeedUtxos(utxos, changeAddress);
  const usableCount = utxos.filter(
    (u) => !u.scriptRef && !EvoAssets.getUnits(u.assets as never).some((x: string) => x !== "lovelace"),
  ).length;
  return { seeds, usableCount };
}

/**
 * Split the wallet into three seed UTxOs, as one standalone transaction.
 *
 * For the wallet that holds a single large UTxO — the normal state of a freshly funded
 * deployer. Kept SEPARATE from the deployment rather than folded in as its first step: it is
 * ordinary, reversible housekeeping that can be repeated if it fails, whereas everything in the
 * plan proper is one-shot. Doing it first also means the deployment's first transaction spends
 * real confirmed UTxOs instead of predicted ones.
 */
export async function prepareSeedUtxos(
  network: CardanoNetwork,
  rawWalletApi: unknown,
  changeAddress: string,
  wallet: { signTx(tx: string, partial: boolean): Promise<string>; submitTx(tx: string): Promise<string> },
): Promise<string> {
  const { client } = signingClient(network, rawWalletApi);
  const addressObj = EvoAddress.fromBech32(changeAddress);
  const utxos = (await client.getUtxos(addressObj as never)) as unknown as ChainUtxo[];

  let tx = client.newTx();
  for (let i = 0; i < 3; i++) {
    tx = tx.payToAddress({ address: addressObj, assets: outputAssets(SEED_PREP_LOVELACE) });
  }
  const built = await tx.build({
    changeAddress: addressObj,
    availableUtxos: utxos.filter((u) => !u.scriptRef) as never,
    passAdditionalUtxos: true,
  });
  const cbor = EvoTransaction.toCBORHex((await built.toTransaction()) as never);
  return wallet.submitTx(await wallet.signTx(cbor, true));
}

export interface PlanDeploymentInput {
  /** The raw CIP-30 API from `wallet.enable()`, as `useWallet().rawApi` provides it. */
  rawWalletApi: unknown;
  /** The wallet's change address, bech32. Pays for everything; its stake key is the nominee. */
  changeAddress: string;
  network: CardanoNetwork;
  blueprint: PlutusBlueprint;
  pin: UpstreamPin;
  multisig: ResolvedMultisig;
  maxInlineDatumBytes: number;
  alwaysFailNonce: string;
  /** Three existing wallet UTxOs, as chain references. */
  seeds?: DeploymentSeeds;
  /** The same three as resolved UTxO objects — the builders need the whole output, not a ref. */
  seedUtxos?: { protocolParams: unknown; issuance: unknown; upgradeMultisig: unknown };
  /**
   * Whether the dispatcher permits unfracking. Default: yes.
   *
   * False compiles `programmable_logic_global` against the disabled sentinel. The unfracking
   * validator is still deployed, registered and published either way — only the value the
   * dispatcher was compiled against differs, and the deployment records both.
   */
  unfrackingEnabled?: boolean;
  /** Add the ~1 ADA self-output the miner needs to the last transaction. Build-time only. */
  mineable?: boolean;
}

/**
 * Can the wallet in front of us ever satisfy the authority it is about to install?
 *
 * The harness refuses outright unless the config tree is the bootstrapping wallet's own key.
 * That is the right rule for a test fixture and the WRONG one here: Giovanni's stated scenario
 * is a designated deployer installing an authority held by other people, so the deployer's key
 * legitimately may not appear.
 *
 * But it is the difference between a deliberate handover and upstream's documented ONE-WAY
 * BRICK — a transposed hex pair in a member list produces a protocol whose upgrade credential
 * nobody can satisfy, and NOTHING else catches it: `upgrade_multisig` is parameterised by
 * `utxo_ref` alone, so the signer tree is not in the script hash and hash verification is blind
 * to it. So it is surfaced and must be acknowledged, never silently allowed and never refused.
 */
export function deployerCanAuthorise(
  changeAddress: string,
  members: readonly { keyHash: string }[],
): boolean {
  const pkh = paymentCredentialHash(changeAddress).toLowerCase();
  return members.some((m) => m.keyHash.toLowerCase() === pkh);
}

export interface DeploymentPlan {
  plan: BootstrapPlan;
  /** Re-derived from the pinned blueprint. `ok === false` means nothing may be signed. */
  verification: VerificationResult;
}

/*
 * `applyMinedStep` lived here and has been REMOVED with the mining feature (T-058).
 *
 * It repointed every `*RefInput` to the mined transaction's hash, which was correct only
 * because the mined step was always the LAST one — the reference-script transaction, published
 * last precisely so its hash could move without invalidating anything chained onto it. Mining
 * the genesis instead, as was briefly proposed, would have repointed all seven reference
 * inputs at the genesis while the scripts themselves sat in a later transaction, and
 * verification would still have passed: it re-derives script hashes from parameters and knows
 * nothing about where outputs live. The mining code itself is untouched under `lib/mining/`
 * and `/ops/mine-check`, ready to be re-wired once upstream says which transaction should
 * carry a low hash and why.
 */

export interface CeremonyPlan {
  plan: BootstrapPlan;
  /** Two independent derivations agreeing. `ok === false` means nothing may be submitted. */
  verification: VerificationResult;
  /** Seed, multisig genesis, stake registrations — the deployer alone. */
  phaseOne: CeremonyStep[];
  /** Carried into phase two so the same client and UTxO set build both halves. */
  ctx: CeremonyContext;
}

export async function planDeployment(input: PlanDeploymentInput): Promise<CeremonyPlan> {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  void projectId;
  const { chain, client } = signingClient(input.network, input.rawWalletApi);

  const availableUtxos = (await (
    client as { getUtxos: (a: unknown) => Promise<readonly unknown[]> }
  ).getUtxos(EvoAddress.fromBech32(input.changeAddress))) as readonly never[];

  const ctx: CeremonyContext = {
    client: client as never,
    changeAddress: EvoAddress.fromBech32(input.changeAddress) as never,
    availableUtxos,
  };

  if (!input.seeds) {
    throw new Error(
      "Three distinct seed UTxOs are required before planning. Use the seed-preparation step " +
        "first: the alternative is a fragmentation transaction whose outputs do not exist on " +
        "chain while everything after it is built and evaluated against them.",
    );
  }

  const plan = buildPlan({
    blueprint: input.blueprint,
    networkId: chain.id,
    seeds: {
      protocolParams: input.seeds.paramsSeed as never,
      issuance: input.seeds.issuanceSeed as never,
      upgradeMultisig: input.seeds.multisigSeed as never,
    },
    alwaysFailNonce: input.alwaysFailNonce,
    maxInlineDatumBytes: BigInt(input.maxInlineDatumBytes),
    unfracking: input.unfrackingEnabled === false ? "disabled" : "enabled",
  } as never);

  // ⛔ THE GATE THAT REPLACES "nothing is signed until the plan verifies". Our derivation
  // against the SDK's, before a single seed is spent. See verifyPlanScripts for why agreement
  // between two independent implementations is worth more than either alone.
  const ours = deriveCoreDeployment({
    blueprint: input.blueprint,
    seeds: input.seeds,
    alwaysFailNonce: input.alwaysFailNonce,
    maxInlineDatumBytes: input.maxInlineDatumBytes,
    unfrackingEnabled: input.unfrackingEnabled,
  });
  const verification = verifyPlanScripts(ours, plan as never);

  const phaseOne = verification.ok
    ? await buildPhaseOne({
        ctx,
        plan,
        needsSeedTx: false,
        seedUtxo: input.seedUtxos?.upgradeMultisig as never,
        upgradeMultisigTree: input.multisig.tree as never,
        ownerAddress: ctx.changeAddress,
        seedLovelace: DEFAULT_SEED_LOVELACE,
      })
    : [];

  return { plan, verification, phaseOne, ctx };
}

/** Lovelace per seed output. Each seed funds part of the transaction that consumes it. */
export const DEFAULT_SEED_LOVELACE = 10_000_000n;

export { awaitMultisigConfigUtxo, buildProtocolGenesis, buildReferenceScripts, selectBootstrapSeeds, assembleDeploymentParams };

/**
 * The block an indexer should intersect at: the one IMMEDIATELY BEFORE the genesis
 * transaction.
 *
 * Err early. Too early costs sync time; too late means the protocol-params UTxO is never
 * indexed, no deployment resolves, and every operation fails with a message that points at
 * configuration rather than at the sync window.
 */
export async function previousBlockOf(
  network: CardanoNetwork,
  txHash: string,
): Promise<{ hash: string; slot: number }> {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  const headers = { project_id: projectId };
  const base = blockfrostBaseUrl(network);

  const txRes = await fetch(`${base}/txs/${txHash}`, { headers });
  if (!txRes.ok) {
    throw new Error(`Blockfrost does not know transaction ${txHash} yet (${txRes.status}).`);
  }
  const { block } = (await txRes.json()) as { block: string };

  const blockRes = await fetch(`${base}/blocks/${block}/previous?count=1`, { headers });
  if (!blockRes.ok) {
    throw new Error(`Could not read the block before ${block} (${blockRes.status}).`);
  }
  const [previous] = (await blockRes.json()) as { hash: string; slot: number }[];
  if (!previous) {
    throw new Error(`Block ${block} reports no predecessor, which cannot be right.`);
  }
  return { hash: previous.hash, slot: previous.slot };
}
