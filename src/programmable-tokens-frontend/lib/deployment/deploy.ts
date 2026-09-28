/** Browser-only bootstrap planning, sequential funding and durable submission checkpoints. */
import {
  evoClient, previewChain, preprodChain, mainnetChain, paymentCredentialHash,
  EvoAddress, EvoTransaction, outputAssets,
  type PlutusBlueprint,
} from "@easy1staking/cip113-sdk-ts";
import {
  buildPlan, buildMultisigGenesis, buildStakeRegistrations, buildProtocolGenesis,
  buildReferenceScripts, awaitMultisigConfigUtxo, assembleDeploymentParams,
  selectSeedUtxos, plainWalletUtxos, resolveSeeds, availableFunding, refKey,
  type BootstrapPlan, type CeremonyStep, type CeremonyContext, type WalletUtxo,
} from "./ceremony";
import { deriveCoreDeployment, type DeploymentSeeds } from "./derive";
import { verifyPlanScripts, type VerificationResult } from "./verify";
import type { UpstreamPin } from "./blueprint";
import { resolveMultisig, type ResolvedMultisig } from "./multisig";
import type { CardanoNetwork } from "../utils/network";
import { transactionHash } from "../tx/hash";

function chainFor(network: CardanoNetwork) {
  return network === "mainnet" ? mainnetChain : network === "preprod" ? preprodChain : previewChain;
}
function blockfrostBaseUrl(network: CardanoNetwork): string {
  return process.env.NEXT_PUBLIC_BLOCKFROST_URL || `https://cardano-${network}.blockfrost.io/api/v0`;
}
function signingClient(network: CardanoNetwork, rawWalletApi: unknown) {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  if (!projectId) throw new Error("NEXT_PUBLIC_BLOCKFROST_API_KEY is not set.");
  const chain = chainFor(network);
  return { chain, client: evoClient(chain).withCip30(rawWalletApi as never)
    .withBlockfrost({ projectId, baseUrl: blockfrostBaseUrl(network) }) };
}
async function walletUtxos(ctx: CeremonyContext): Promise<readonly WalletUtxo[]> {
  return ctx.client.getUtxos(EvoAddress.fromBech32(ctx.changeAddress));
}
export interface WalletSeeds { seeds: DeploymentSeeds | null; usableCount: number }
export async function findWalletSeeds(network: CardanoNetwork, rawWalletApi: unknown, changeAddress: string): Promise<WalletSeeds> {
  const { client } = signingClient(network, rawWalletApi);
  const utxos = await client.getUtxos(EvoAddress.fromBech32(changeAddress));
  return { seeds: selectSeedUtxos(utxos), usableCount: plainWalletUtxos(utxos).length };
}

export interface PlanDeploymentInput {
  rawWalletApi: unknown;
  changeAddress: string;
  network: CardanoNetwork;
  blueprint: PlutusBlueprint;
  pin: UpstreamPin;
  multisig: ResolvedMultisig;
  maxInlineDatumBytes: number;
  alwaysFailNonce: string;
  seeds?: DeploymentSeeds;
  unfrackingEnabled?: boolean;
}
export interface CeremonyPlan {
  plan: BootstrapPlan;
  verification: VerificationResult;
  ctx: CeremonyContext;
  settings: DeploymentSettings;
  multisig: ResolvedMultisig;
}
export interface DeploymentSettings {
  network: CardanoNetwork;
  changeAddress: string;
  seeds: DeploymentSeeds;
  members: string[];
  threshold: number;
  maxInlineDatumBytes: number;
  alwaysFailNonce: string;
  unfrackingEnabled: boolean;
  blueprintSha256: string;
}
export function deployerCanAuthorise(changeAddress: string, members: readonly { keyHash: string }[]): boolean {
  const pkh = paymentCredentialHash(changeAddress).toLowerCase();
  return members.some((m) => m.keyHash.toLowerCase() === pkh);
}
function seedRefs(seeds: DeploymentSeeds) { return [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed]; }

/** Restoration re-derives the frozen plan; consumed seeds are resolved only for steps still to build. */
export async function planDeployment(input: PlanDeploymentInput, restoring = false): Promise<CeremonyPlan> {
  if (!input.seeds) throw new Error("Select three distinct wallet seed UTxOs, or prepare seeds first.");
  const refs = seedRefs(input.seeds);
  if (new Set(refs.map(refKey)).size !== 3) throw new Error("The three deployment seeds must be distinct.");
  const { chain, client } = signingClient(input.network, input.rawWalletApi);
  const ctx: CeremonyContext = { client, changeAddress: input.changeAddress, availableUtxos: [] };
  if (!restoring) resolveSeeds(await walletUtxos(ctx), refs);
  const plan = buildPlan({
    blueprint: input.blueprint, networkId: chain.id,
    seeds: { protocolParams: input.seeds.paramsSeed, issuance: input.seeds.issuanceSeed,
      upgradeMultisig: input.seeds.multisigSeed },
    alwaysFailNonce: input.alwaysFailNonce,
    maxInlineDatumBytes: BigInt(input.maxInlineDatumBytes),
    unfracking: input.unfrackingEnabled === false ? "disabled" : "enabled",
  });
  const ours = deriveCoreDeployment({ blueprint: input.blueprint, seeds: input.seeds,
    alwaysFailNonce: input.alwaysFailNonce, maxInlineDatumBytes: input.maxInlineDatumBytes,
    unfrackingEnabled: input.unfrackingEnabled });
  const verification = verifyPlanScripts(ours, plan);
  if (!verification.ok) throw new Error("Deployment script derivation failed; no transaction may be signed.");
  const settings: DeploymentSettings = {
    network: input.network, changeAddress: input.changeAddress,
    seeds: structuredClone(input.seeds), members: input.multisig.members.map((m) => m.keyHash),
    threshold: input.multisig.required, maxInlineDatumBytes: input.maxInlineDatumBytes,
    alwaysFailNonce: input.alwaysFailNonce, unfrackingEnabled: input.unfrackingEnabled !== false,
    blueprintSha256: input.pin.sha256,
  };
  return { plan, verification, ctx, settings, multisig: input.multisig };
}
export async function restoreDeployment(settings: DeploymentSettings, input: Pick<PlanDeploymentInput, "rawWalletApi" | "blueprint" | "pin" | "changeAddress" | "network">) {
  if (input.network !== settings.network || input.changeAddress !== settings.changeAddress) {
    throw new Error("Reconnect the original deploying wallet on the saved network to resume.");
  }
  if (input.pin.sha256 !== settings.blueprintSha256) throw new Error("Saved deployment uses a different blueprint. Restore the original application version.");
  return planDeployment({ ...input, ...settings, multisig: resolveMultisig(settings.members, settings.threshold) }, true);
}
export type ConfigUtxo = Awaited<ReturnType<typeof awaitMultisigConfigUtxo>>;
export async function readConfig(planned: CeremonyPlan): Promise<ConfigUtxo> {
  return awaitMultisigConfigUtxo({ plan: planned.plan, expectedTree: planned.multisig.tree,
    utxosAt: (address) => planned.ctx.client.getUtxos(EvoAddress.fromBech32(address)) });
}
export const DEPLOYMENT_STEPS = ["upgrade multisig", "register credentials", "protocol genesis", "reference scripts"] as const;
export type DeploymentStepLabel = typeof DEPLOYMENT_STEPS[number];

/** Called only after the predecessor is confirmed. Each call fetches fresh funding. */
export async function buildDeploymentStep(planned: CeremonyPlan, label: DeploymentStepLabel, config?: ConfigUtxo): Promise<CeremonyStep> {
  const utxos = await walletUtxos(planned.ctx);
  const refs = seedRefs(planned.settings.seeds);
  const ctx = { ...planned.ctx, availableUtxos: availableFunding(utxos, refs) };
  if (!ctx.availableUtxos.length) throw new Error("No unreserved plain ADA funding UTxO is available. Fund the wallet separately from the three reserved seeds.");
  switch (label) {
    case "upgrade multisig": {
      const [seedUtxo] = resolveSeeds(utxos, [planned.settings.seeds.multisigSeed]);
      return buildMultisigGenesis({ ctx, plan: planned.plan, seedUtxo, upgradeMultisigTree: planned.multisig.tree });
    }
    case "register credentials": return buildStakeRegistrations(ctx, planned.plan);
    case "protocol genesis": {
      const [protocolParamsSeedUtxo, issuanceSeedUtxo] = resolveSeeds(utxos, refs.slice(0, 2));
      if (!config) throw new Error("Read and verify the multisig config before building genesis.");
      return buildProtocolGenesis({ ctx, plan: planned.plan, protocolParamsSeedUtxo, issuanceSeedUtxo,
        upgradeMultisigConfigUtxo: config.utxo,
        upgradeAuthoritySigners: planned.multisig.members.map((m) => m.keyHash) });
    }
    case "reference scripts": return buildReferenceScripts({ ctx, plan: planned.plan,
      referenceScriptAddress: ctx.changeAddress, referenceScriptLovelace: 20_000_000n });
  }
}

export type StepStatus = "BUILT" | "SUBMITTING" | "CONFIRMED" | "INVALID";
export interface SavedStep extends CeremonyStep { txHash: string; status: StepStatus }
export interface DeploymentCheckpoint { version: 1; settings: DeploymentSettings; steps: SavedStep[] }
export const checkpointKey = (network: CardanoNetwork) => `cip113-bootstrap-v1:${network}`;
export interface CheckpointStorage { getItem(key: string): string | null; setItem(key: string, value: string): void }
export function saveCheckpoint(storage: CheckpointStorage, checkpoint: DeploymentCheckpoint): void {
  const key = checkpointKey(checkpoint.settings.network);
  const text = JSON.stringify(checkpoint);
  storage.setItem(key, text);
  if (storage.getItem(key) !== text) throw new Error("Could not save the deployment checkpoint. Submission stopped.");
}
export function readCheckpoint(storage: CheckpointStorage, network: CardanoNetwork): DeploymentCheckpoint | null {
  const raw = storage.getItem(checkpointKey(network));
  if (!raw) return null;
  const saved = JSON.parse(raw) as DeploymentCheckpoint;
  if (saved.version !== 1 || saved.settings?.network !== network || !Array.isArray(saved.steps) || saved.steps.length > 4) {
    throw new Error("Invalid deployment checkpoint. Keep it for diagnosis; do not start another deployment.");
  }
  const refs = seedRefs(saved.settings.seeds);
  if (new Set(refs.map(refKey)).size !== 3) throw new Error("Invalid saved seed references.");
  saved.steps.forEach((step, index) => {
    if (step.label !== DEPLOYMENT_STEPS[index] || !["BUILT", "SUBMITTING", "CONFIRMED", "INVALID"].includes(step.status) ||
        transactionHash(step.unsignedCbor) !== step.txHash ||
        (index < saved.steps.length - 1 && step.status !== "CONFIRMED")) {
      throw new Error("Deployment checkpoint transactions or order are invalid. Submission stopped.");
    }
  });
  return saved;
}
export function saveBuiltStep(checkpoint: DeploymentCheckpoint, step: CeremonyStep, persist: (value: DeploymentCheckpoint) => void): SavedStep {
  const existing = checkpoint.steps.find((s) => s.label === step.label);
  if (existing) {
    if (existing.unsignedCbor !== step.unsignedCbor) throw new Error("A frozen transaction cannot be replaced.");
    return existing;
  }
  if (DEPLOYMENT_STEPS[checkpoint.steps.length] !== step.label || checkpoint.steps.some((s) => s.status !== "CONFIRMED")) {
    throw new Error("Confirm the previous deployment step before building the next.");
  }
  const saved: SavedStep = { ...step, txHash: transactionHash(step.unsignedCbor), status: "BUILT" };
  checkpoint.steps.push(saved);
  persist(checkpoint);
  return saved;
}

/** A 404 proves only that the provider cannot see the transaction yet. */
export async function checkDeploymentTransaction(network: CardanoNetwork, txHash: string): Promise<"CONFIRMED" | "UNKNOWN" | "INVALID"> {
  const res = await fetch(`${blockfrostBaseUrl(network)}/txs/${txHash}`, {
    headers: { project_id: process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "" }, cache: "no-store",
  });
  if (res.status === 404) return "UNKNOWN";
  if (!res.ok) throw new Error(`Could not check ${txHash}: Blockfrost ${res.status}.`);
  const tx = await res.json() as { hash?: string; block?: string; valid_contract?: boolean };
  if (tx.hash !== txHash || !tx.block || !/^[0-9a-f]{64}$/.test(tx.block)) throw new Error("Provider did not identify the exact transaction in a block.");
  if (tx.valid_contract === false) return "INVALID";
  if (tx.valid_contract !== true) throw new Error("Provider did not confirm successful ledger execution.");
  return "CONFIRMED";
}
export async function reconcileCheckpoint(checkpoint: DeploymentCheckpoint, persist: (value: DeploymentCheckpoint) => void,
  check: (hash: string) => Promise<"CONFIRMED" | "UNKNOWN" | "INVALID">): Promise<void> {
  for (const step of checkpoint.steps) {
    if (step.status === "BUILT") continue;
    const status = await check(step.txHash);
    step.status = status === "UNKNOWN" ? "SUBMITTING" : status;
    persist(checkpoint);
    if (status !== "CONFIRMED") throw new Error(`${step.label}: ${step.txHash} is ${status}. Check confirmation again; no transaction was resubmitted.`);
  }
}
export async function submitSavedStep(step: SavedStep, options: {
  wallet: { signTx(tx: string, partial: boolean): Promise<string>; submitTx(tx: string): Promise<string> };
  persist: () => void;
  check: (hash: string) => Promise<"CONFIRMED" | "UNKNOWN" | "INVALID">;
  signingCbor?: string;
  wait?: () => Promise<void>;
  attempts?: number;
}): Promise<void> {
  if (step.status === "INVALID") throw new Error("The saved transaction failed on chain. Stop this ceremony.");
  if (step.status === "BUILT") {
    const source = options.signingCbor ?? step.unsignedCbor;
    if (transactionHash(source) !== step.txHash) throw new Error("The transaction body changed before signing.");
    // Persist again before signing, including when a prior storage write failed.
    options.persist();
    const signed = await options.wallet.signTx(source, true);
    if (transactionHash(signed) !== step.txHash) throw new Error("Wallet changed the frozen transaction body.");
    // This is the crash boundary: a reload from here MUST reconcile, never replay.
    step.status = "SUBMITTING";
    options.persist();
    const returnedHash = await options.wallet.submitTx(signed);
    if (returnedHash.toLowerCase() !== step.txHash) throw new Error("Wallet returned a different transaction hash; check the saved hash before continuing.");
  }
  for (let attempt = 0; attempt < (options.attempts ?? 60); attempt++) {
    const state = await options.check(step.txHash);
    if (state !== "UNKNOWN") {
      step.status = state;
      options.persist();
      if (state === "INVALID") throw new Error("The transaction was included with failed script execution. Stop this ceremony.");
      return;
    }
    if (step.status === "CONFIRMED") { step.status = "SUBMITTING"; options.persist(); }
    if (attempt + 1 < (options.attempts ?? 60)) await (options.wait?.() ?? new Promise((r) => setTimeout(r, 5000)));
  }
  throw new Error(`Confirmation is unknown for ${step.txHash}. Use Check confirmation; do not resubmit.`);
}

/** Optional preparation uses its own durable record before any one-shot plan exists. */
export async function prepareSeedUtxos(network: CardanoNetwork, rawWalletApi: unknown, changeAddress: string,
  wallet: { signTx(tx: string, partial: boolean): Promise<string>; submitTx(tx: string): Promise<string> }): Promise<string> {
  const { client } = signingClient(network, rawWalletApi);
  const key = `cip113-bootstrap-seeds:${network}:${changeAddress}`;
  const raw = sessionStorage.getItem(key);
  let step: SavedStep;
  if (raw) {
    step = JSON.parse(raw) as SavedStep;
    if (transactionHash(step.unsignedCbor) !== step.txHash || !["BUILT", "SUBMITTING", "CONFIRMED", "INVALID"].includes(step.status)) throw new Error("Invalid saved seed preparation.");
  } else {
    const address = EvoAddress.fromBech32(changeAddress);
    const utxos = plainWalletUtxos(await client.getUtxos(address));
    let tx = client.newTx();
    for (let i = 0; i < 3; i++) tx = tx.payToAddress({ address, assets: outputAssets(5_000_000n) });
    const built = await tx.build({ changeAddress: address, availableUtxos: utxos, passAdditionalUtxos: true });
    const cbor = EvoTransaction.toCBORHex(await built.toTransaction());
    step = { label: "prepare seeds", unsignedCbor: cbor, txHash: transactionHash(cbor), status: "BUILT" };
  }
  const persist = () => {
    const json = JSON.stringify(step); sessionStorage.setItem(key, json);
    if (sessionStorage.getItem(key) !== json) throw new Error("Cannot save seed preparation; submission stopped.");
  };
  await submitSavedStep(step, { wallet, persist, check: (hash) => checkDeploymentTransaction(network, hash) });
  sessionStorage.removeItem(key);
  return step.txHash;
}
export { assembleDeploymentParams };

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
