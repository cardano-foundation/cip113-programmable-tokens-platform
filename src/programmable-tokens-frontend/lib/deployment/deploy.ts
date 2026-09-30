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
  isPlainSeedCandidate,
  assertCeremonyContext,
  resolveSeedUtxos,
  providerEvaluatorWithAdditionalUtxos,
  withoutRefs,
  type ChainUtxo,
} from "./ceremony";
import { deriveCoreDeployment, type DeploymentSeeds } from "./derive";
import { verifyDeployment, verifyPlanScripts, type VerificationResult } from "./verify";
import { EvoAddress, EvoAssets, EvoTransaction, outputAssets } from "@easy1staking/cip113-sdk-ts";

/**
 * Lovelace parked in each prepared seed — 25 apiece. Measured by Giovanni, 2026-09-30.
 *
 * ⛔ EQUAL, AND THAT IS THE POINT. This was 50/10/10, sized on the reasoning that each seed
 * part-funds the transaction consuming it and those transactions are not alike — protocol-genesis
 * mints two assets, carries a withdraw-0 and runs scripts, where multisig-genesis mints one NFT
 * into a small output. The reasoning was right about the FLOOR and wrong about what to optimise:
 * a 10 ADA seed covers the multisig genesis and leaves a remainder that lands BELOW min-UTxO, so
 * `build` refuses with *"Cannot create valid change … Available: 0 lovelace"* — a message that
 * names a funding problem on a wallet holding thousands. Giovanni split 25/25/25 by hand and the
 * ceremony built immediately.
 *
 * ⚑ SO THE CONSTRAINT IS ON THE LEFTOVER, NOT ON THE OUTPUTS. A seed must cover its transaction
 * AND leave change above min-UTxO (~0.97 ADA), and 25 clears both for every step with room to
 * spare. Equal amounts also make the arithmetic reproducible rather than positional, which removes
 * the trap the previous note had to warn about: `selectSeedUtxos` sorts candidates largest first
 * and assigns them in order, so unequal amounts silently re-targeted which seed got the headroom
 * whenever this array was reordered. Nothing here is positional any more.
 *
 * ⚠ 75 ADA total, up from 70, and all of it returns to the deployer except what the steps consume.
 *
 * ⚑ THE UNDERLYING NARROWNESS IS NOT FIXED BY THIS, only avoided: `planDeployment` funds from
 * `client.getUtxos(changeAddress)` — ONE address — and seed prep pays the seeds to that same
 * address, so the pool is whatever that one address holds beyond them. A wallet rich across other
 * addresses can still present a thin pool here. Widening the read, or forwarding
 * `onInsufficientChange` through the SDK's `buildOptions` (which today forwards only
 * changeAddress, availableUtxos and evaluator), are the real fixes.
 */
const SEED_PREP_LOVELACE: readonly bigint[] = [25_000_000n, 25_000_000n, 25_000_000n];
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

/**
 * The wallet's current UTxOs, through the SAME client every other path uses.
 *
 * Exported so callers do not build their own client: one built with a different provider or chain
 * reads a different UTxO set, and these functions hand each other outrefs.
 */
export async function readWalletUtxos(
  network: CardanoNetwork,
  rawWalletApi: unknown,
  changeAddress: string,
): Promise<readonly unknown[]> {
  const { client } = signingClient(network, rawWalletApi);
  return (await (
    client as { getUtxos: (a: unknown) => Promise<readonly unknown[]> }
  ).getUtxos(EvoAddress.fromBech32(changeAddress))) as readonly unknown[];
}

export interface WalletSeeds {
  /** Three distinct UTxOs fit to be one-shot seeds, or null when the wallet has no three. */
  seeds: DeploymentSeeds | null;
  /** How many wallet UTxOs could serve as a seed. Below three, the wallet needs preparing. */
  usableCount: number;
  /**
   * How many UTxOs the PROVIDER returned, before any filtering.
   *
   * ⚑ REPORTED SEPARATELY BECAUSE "0 usable" HAD TWO CAUSES AND ONE MESSAGE. A wallet the
   * provider cannot see at all and a wallet whose every UTxO carries an asset both rendered as
   * "0 UTxO(s) usable", and the remedies are opposites: fix the address or the Blockfrost key in
   * the first case, split the wallet in the second. Splitting a wallet the provider cannot see
   * accomplishes nothing and costs a transaction.
   */
  totalCount: number;
  /** Which address was queried, so a mismatch with the funded one is visible rather than inferred. */
  queriedAddress: string;
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
  // Evolution UTxO objects, NOT the platform's record shape — see toChainUtxo.
  const utxos = (await client.getUtxos(
    EvoAddress.fromBech32(changeAddress) as never,
  )) as unknown as readonly unknown[];
  const seeds = selectSeedUtxos(utxos, changeAddress);
  const usableCount = utxos.filter(isPlainSeedCandidate).length;
  return { seeds, usableCount, totalCount: utxos.length, queriedAddress: changeAddress };
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
  // One output per seed — equal amounts; see SEED_PREP_LOVELACE for why.
  for (const lovelace of SEED_PREP_LOVELACE) {
    tx = tx.payToAddress({ address: addressObj, assets: outputAssets(lovelace) });
  }
  /**
   * ⛔ A FILTER CAN EMPTY THE POOL OF A RICH WALLET, and Evolution's advice for that is wrong.
   * A seed candidate must be PLAIN — no reference script, no native assets — because whatever it
   * carries is dragged into the transaction that consumes it. A wallet whose every output holds a
   * token is therefore rich and unusable, and "add more funds" is exactly the wrong remedy. Only a
   * message that counts the pool can tell that apart from genuinely being broke.
   *
   * ⚠ NOT to be confused with "Cannot create valid change … Available: 0 lovelace", which is a
   * DIFFERENT failure: there the payment and fees are covered and the LEFTOVER is too small to
   * become a change output. See onInsufficientChange below.
   */
  const candidates = utxos.filter(isPlainSeedCandidate);
  if (candidates.length === 0) {
    const total = utxos.length;
    throw new Error(
      `None of this wallet's ${total} UTxO(s) can seed a deployment. A seed must be PLAIN — no ` +
        `native assets and no reference script — because whatever it carries would be dragged ` +
        `into the transaction that consumes it.\n` +
        `\n` +
        `  This is not a funding problem: adding ADA to outputs that already carry tokens will ` +
        `not help. Send yourself a few ada-only outputs, or consolidate, and try again.`,
    );
  }

  const built = await tx.build({
    changeAddress: addressObj,
    availableUtxos: candidates as never,
    passAdditionalUtxos: true,
    /**
     * ⛔ "Cannot create valid change … Available: 0 lovelace. Required: At least 969750 lovelace
     * for change output" — reported after seeding, and it is NOT a funding problem. The payment and
     * fees are covered; what fails is the LEFTOVER, which lands below the min-UTxO a change output
     * needs. Coin selection cannot always avoid it: the seeds are exact amounts, so whether the
     * remainder clears ~0.97 ADA is luck.
     *
     * ⚑ 'burn' IS BOUNDED, WHICH IS WHY IT IS SAFE HERE. It only applies when the leftover is
     * already below min-UTxO, so at most ~1 ADA becomes extra fee — and the alternative is a seed
     * preparation that fails outright on a wallet holding thousands. Deliberately NOT applied to
     * the ceremony transactions: this is repeatable housekeeping, and they are one-shot.
     */
    onInsufficientChange: "burn",
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
  /**
   * Whether the dispatcher permits unfracking. Default: yes.
   *
   * False compiles `programmable_logic_global` against the disabled sentinel. The unfracking
   * validator is still deployed, registered and published either way — only the value the
   * dispatcher was compiled against differs, and the deployment records both.
   */
  unfrackingEnabled?: boolean;
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

export interface CeremonyPlan {
  plan: BootstrapPlan;
  /** Two independent derivations agreeing. `ok === false` means nothing may be submitted. */
  verification: VerificationResult;
  /** Seed, multisig genesis, stake registrations — the deployer alone. */
  phaseOne: CeremonyStep[];
  /** Carried into phase two so the same client and UTxO set build both halves. */
  ctx: CeremonyContext;
  /**
   * The three seed UTxOs as objects, resolved from the same UTxO set the plan was built against.
   *
   * ⚑ RETURNED RATHER THAN RE-DERIVED BY THE CALLER. The page kept its own `seedUtxos` state for
   * this and never set it, so phase two passed `undefined` for both of protocol-genesis's seeds.
   * Carrying them on the plan makes that state unnecessary and keeps the objects tied to the
   * outrefs the plan is parameterised by.
   */
  seedUtxos: { protocolParams: unknown; issuance: unknown; upgradeMultisig: unknown };
}

export async function planDeployment(input: PlanDeploymentInput): Promise<CeremonyPlan> {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  void projectId;
  const { chain, client } = signingClient(input.network, input.rawWalletApi);

  const availableUtxos = (await (
    client as { getUtxos: (a: unknown) => Promise<readonly unknown[]> }
  ).getUtxos(EvoAddress.fromBech32(input.changeAddress))) as readonly never[];

  if (!input.seeds) {
    throw new Error(
      "Three distinct seed UTxOs are required before planning. Use the seed-preparation step " +
        "first: the alternative is a fragmentation transaction whose outputs do not exist on " +
        "chain while everything after it is built and evaluated against them.",
    );
  }

  const ctx: CeremonyContext = {
    client: client as never,
    // Reuses the provider's own evaluator and only changes one decision: it FORWARDS the
    // transaction's selected UTxOs as Blockfrost's additionalUtxoSet, which Evolution's
    // provider evaluator discards unless passAdditionalUtxos is set — and the SDK never sets it.
    // See providerEvaluatorWithAdditionalUtxos.
    evaluator: providerEvaluatorWithAdditionalUtxos(client) as never,
    // A bech32 STRING. The SDK declares `Address = string` and parses it itself; the parsed
    // object that used to be here satisfied `as never` and then failed the SDK's own guard at
    // whichever step ran first — see assertCeremonyContext.
    changeAddress: input.changeAddress,
    // ⛔ THE FUNDING SET, WITH THE SEEDS REMOVED. Not the raw wallet read — see the block below.
    availableUtxos: withoutRefs(availableUtxos, [
      input.seeds.paramsSeed,
      input.seeds.issuanceSeed,
      input.seeds.multisigSeed,
    ]) as never,
  };
  // Fail here, before the plan and before any seed is spent, rather than inside step 2.
  assertCeremonyContext(ctx, "plan deployment");

  // The builders SPEND these; the plan below is only parameterised by their outrefs. Resolved from
  // the RAW read, because the funding set above deliberately no longer contains them.
  const seedUtxos = resolveSeedUtxos(availableUtxos, input.seeds);

  /**
   * ⛔ WHY THE SEEDS ARE OUT OF THE FUNDING SET ABOVE.
   *
   * Phase one builds BOTH its transactions from one available set before either is submitted, so
   * whatever coin selection picks for stake-registrations is chosen in ignorance of what
   * multisig-genesis already claims. Measured on preview 2026-09-28: multisig-genesis consumed the
   * 50 ADA multisig seed and landed; stake-registrations, funded from the same set, was rejected at
   * submission — one transaction on chain, its partner unspendable, and the plan dead, because the
   * one-shot policy is a function of a seed that is now spent.
   *
   * ⚑ AND MAKING THE SEEDS THE LARGEST UTxOs MADE THIS LIKELIER, which is my own doing: seeds are
   * now the biggest outputs in the wallet and coin selection prefers big inputs. Both changes are
   * individually right and they collide.
   *
   * This adds NO requirement on the wallet. The seeds are handed to the builders that consume them
   * explicitly, so taking them out of the FUNDING pool only stops them being spent twice — it is
   * not the "reserve more UTxOs up front" constraint that was ruled out.
   */

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
        seedUtxo: seedUtxos.upgradeMultisig as never,
        upgradeMultisigTree: input.multisig.tree as never,
        ownerAddress: ctx.changeAddress,
        seedLovelace: DEFAULT_SEED_LOVELACE,
      })
    : [];

  return { plan, verification, phaseOne, ctx, seedUtxos };
}

/** Lovelace per seed output. Each seed funds part of the transaction that consumes it. */
export const DEFAULT_SEED_LOVELACE = 50_000_000n;

export { buildWithFreshUtxos, withoutOutputsOf, withoutRefs, providerEvaluatorWithAdditionalUtxos, awaitUtxosOf } from "./ceremony";
export { awaitMultisigConfigUtxo, buildProtocolGenesis, buildReferenceScripts, selectBootstrapSeeds, assembleDeploymentParams };
export type { MultisigConfigLocation } from "./ceremony";

/**
 * The block an indexer should intersect at: the one IMMEDIATELY BEFORE the genesis
 * transaction.
 *
 * Err early. Too early costs sync time; too late means the protocol-params UTxO is never
 * indexed, no deployment resolves, and every operation fails with a message that points at
 * configuration rather than at the sync window.
 */
/**
 * How many blocks deep a transaction is — 1 means "in the tip block".
 *
 * ⛔ DEPTH, NOT ELAPSED SECONDS, and the unit is the point. Blockfrost's evaluation endpoint can
 * work from an older ledger snapshot than its query endpoints, so a UTxO created a block or two
 * ago is invisible to `/utils/txs/evaluate/utxos` while `/addresses/.../utxos` already lists it.
 * That gap is measured in BLOCKS. A wall-clock countdown guesses at someone else's infrastructure
 * and silently under-waits whenever the chain is slow; preview alone varies enough for that to
 * matter. Counting blocks self-adjusts.
 *
 * Returns `null` while Blockfrost does not know the transaction at all, which is a different
 * state from "known but shallow" and the caller should say so rather than showing a depth of 0.
 */
export async function confirmationDepth(
  network: CardanoNetwork,
  txHash: string,
): Promise<{ depth: number; txBlockHeight: number; tipHeight: number } | null> {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  const headers = { project_id: projectId };
  const base = blockfrostBaseUrl(network);

  const txRes = await fetch(`${base}/txs/${txHash}`, { headers });
  if (txRes.status === 404) return null; // not indexed yet — not an error
  if (!txRes.ok) {
    throw new Error(`Could not read transaction ${txHash} from Blockfrost (${txRes.status}).`);
  }
  const { block_height: txBlockHeight } = (await txRes.json()) as { block_height: number };

  const tipRes = await fetch(`${base}/blocks/latest`, { headers });
  if (!tipRes.ok) {
    throw new Error(`Could not read the chain tip from Blockfrost (${tipRes.status}).`);
  }
  const { height: tipHeight } = (await tipRes.json()) as { height: number };

  return { depth: Math.max(0, tipHeight - txBlockHeight + 1), txBlockHeight, tipHeight };
}

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
