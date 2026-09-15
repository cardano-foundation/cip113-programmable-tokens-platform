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

import { buildBootstrapPlan, type BootstrapPlan } from "./bootstrap";
import { verifyDeployment, type VerificationResult } from "./verify";
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

export async function planDeployment(input: PlanDeploymentInput): Promise<DeploymentPlan> {
  const projectId = process.env.NEXT_PUBLIC_BLOCKFROST_API_KEY || "";
  if (!projectId) {
    throw new Error(
      "NEXT_PUBLIC_BLOCKFROST_API_KEY is not set. A deployment has to read the wallet's UTxOs, " +
        "the protocol parameters and the nominee's registration state before it can build " +
        "anything.",
    );
  }

  const chain = chainFor(input.network);
  const client = evoClient(chain)
    .withCip30(input.rawWalletApi as never)
    .withBlockfrost({ projectId, baseUrl: blockfrostBaseUrl(input.network) });

  const plan = await buildBootstrapPlan({
    client: client as never,
    networkId: chain.id,
    blueprint: input.blueprint,
    pin: input.pin,
    changeAddress: input.changeAddress,
    multisig: input.multisig,
    maxInlineDatumBytes: input.maxInlineDatumBytes,
    alwaysFailNonce: input.alwaysFailNonce,
    isStakeRegistered: (rewardAddress) =>
      isStakeRegisteredViaBlockfrost(input.network, projectId, rewardAddress),
  });

  // The deployment the plan WILL produce, checked the way a finished one is checked.
  const verification = verifyDeployment(input.blueprint, plan.deployment);

  return { plan, verification };
}

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
