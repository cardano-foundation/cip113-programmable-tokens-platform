/**
 * The bootstrap as a CEREMONY: two phases, with the participants between them.
 *
 * Replaces the hand-port of the SDK's harness. That port existed only because the
 * SDK shipped `files: ["dist","blueprints"]` and its bootstrap lived under `test/`,
 * so it was readable and not importable. Since 0.11.0 it is exported, and since
 * 0.12.0 it is the only implementation that satisfies the protocol — which is why
 * the port is deleted rather than kept beside it. Two copies of protocol-critical
 * logic is the failure the repo constitution names by name.
 *
 * ## Why two phases, and why that is not a preference
 *
 * `protocol_params.mint` demands a withdraw-0 from `upgrade_cred`, and the handler
 * that runs finds its authority tree in the `upgrade_multisig` CONFIG UTXO among the
 * transaction's reference inputs. That UTxO is an output of the multisig genesis.
 * A reference input must exist when the transaction is submitted, so the genesis
 * cannot be submitted until the multisig genesis is on chain.
 *
 * We therefore BUILD the genesis after that confirmation too, rather than predicting
 * the output. Predicting is possible in principle and is a worse trade here: a builder
 * that reorders or merges outputs leaves a predicted index pointing at the wrong UTxO,
 * and the failure surfaces at submission. Reading it back and filtering by the config
 * NFT's policy is self-verifying — see `awaitMultisigConfigUtxo`.
 *
 * ## The order is a ledger rule, not a choice
 *
 *   seed -> multisig-genesis -> stake-registrations -> protocol-genesis -> reference-scripts
 *
 * Withdrawals are applied against reward accounts BEFORE certificates, so a credential
 * cannot be withdrawn from in the transaction that registers it. The genesis withdraws
 * from `upgrade_cred`; the registrations must already have landed.
 */

import { EvoAssets, EvoTransactionHash } from "@easy1staking/cip113-sdk-ts";
import {
  planBootstrap,
  buildSeedTx,
  buildMultisigGenesisTx,
  buildStakeRegistrationTx,
  buildProtocolGenesisTx,
  buildReferenceScriptsTx,
  selectBootstrapSeeds,
  assertMultisigConfigUtxo,
  assembleDeploymentParams,
  BOOTSTRAP_SEED_COUNT,
  type BootstrapPlan,
  type BootstrapConfig,
  type BootstrapBuildContext,
  type DeploymentParams,
  type SeedTxParams,
  type MultisigGenesisTxParams,
  type StakeRegistrationTxParams,
  type ProtocolGenesisTxParams,
  type ReferenceScriptsTxParams,
} from "@easy1staking/cip113-sdk-ts";

/**
 * A wallet UTxO, as the seed-selection code needs to read one.
 *
 * `scriptRef` and `assets` are here because a seed must be PLAIN: a UTxO carrying a reference
 * script or a native asset cannot be spent as a one-shot seed without dragging its payload
 * into the transaction that consumes it.
 */
export interface ChainUtxo {
  txHash: string;
  outputIndex: number;
}

/**
 * Three plain UTxOs to seed a deployment, newest last.
 *
 * ⛔ THEY MUST BE DISTINCT. `protocolParams` and `upgradeMultisig` are the same type and are
 * not interchangeable: one UTxO in both slots deploys perfectly well and makes the
 * upgrade-multisig check vacuous, because the verifier then passes whichever of the two fields
 * it happens to read.
 */
export function selectSeedUtxos(
  utxos: readonly unknown[],
  _changeAddress: string,
): { paramsSeed: ChainUtxo; issuanceSeed: ChainUtxo; multisigSeed: ChainUtxo } | null {
  const plain = utxos.filter(isPlainSeedCandidate).map(toChainUtxo);
  if (plain.length < BOOTSTRAP_SEED_COUNT) return null;
  const [a, b, c] = plain;
  return { paramsSeed: a, issuanceSeed: b, multisigSeed: c };
}

/**
 * ⛔ THE WALLET UTxO DOES NOT USE `txHash`/`outputIndex`, AND READING THOSE IS WHY THE PAGE
 * REPORTED AN EMPTY WALLET WITH 84 UTxOs IN IT.
 *
 * `client.getUtxos(address)` resolves to the PROVIDER's method — `ReadOnlyClientEffect extends
 * Provider.ProviderEffect` — so it returns Evolution `UTxO` objects, whose reference fields are
 * `transactionId` (a `TransactionHash`, not a hex string) and `index` (a **bigint**). The names
 * `txHash` and `outputIndex` belong to the record format the platform WRITES, not to anything the
 * chain hands back, and reading them off a provider UTxO yields `undefined` silently — no type
 * error, because the values crossed an `as` boundary on the way in.
 *
 * ⚑ The wallet has a second, unrelated `getUtxos()` that takes NO argument and returns CBOR
 * hex STRINGS. Either mistake produces "no usable UTxOs" from a funded wallet, which is why this
 * conversion is one function with one home rather than a field access at each call site.
 */
export function toChainUtxo(utxo: unknown): ChainUtxo {
  const u = utxo as { transactionId?: unknown; index?: unknown };
  // ⚑ toHex THROWS a ParseError on anything that is not a TransactionHash — it does not return
  // undefined — so the guard has to be a catch, not a value check. Measured: toHex(undefined),
  // toHex(null), toHex("abc") and toHex({hash}) all throw "TransactionHash.FromHex", which names
  // the SDK's internal schema and not the field the caller got wrong.
  let txHash: string;
  try {
    txHash = EvoTransactionHash.toHex(u.transactionId as never).toLowerCase();
  } catch {
    throw new Error(
      "A wallet UTxO carried no usable transaction id. Expected Evolution's `transactionId`; got " +
        JSON.stringify(u.transactionId) + ". (`txHash` is the field the platform WRITES, not one " +
        "the chain returns.)",
    );
  }
  const outputIndex = Number(u.index);
  if (!/^[0-9a-f]{64}$/.test(txHash)) {
    throw new Error("A wallet UTxO produced a malformed transaction id: " + txHash + ".");
  }
  if (!Number.isSafeInteger(outputIndex) || outputIndex < 0) {
    throw new Error(
      "A wallet UTxO carried no usable output index. Expected Evolution's `index`; got " +
        JSON.stringify(u.index) + ".",
    );
  }
  return { txHash, outputIndex };
}

/**
 * Whether a wallet UTxO can be spent as a one-shot seed.
 *
 * A seed must be PLAIN. A UTxO carrying a reference script or a native asset drags its payload
 * into the transaction that consumes it — and spending a reference-script output destroys
 * protocol infrastructure silently, which the SDK records as having already happened on preview.
 *
 * ⚑ ONE PREDICATE, USED BY BOTH THE SELECTION AND THE COUNT. They were separate and disagreed:
 * the selection filtered on `scriptRef` alone while the count also excluded native assets, so a
 * wallet could be told it had two usable UTxOs and then have a third selected anyway.
 */
export function isPlainSeedCandidate(utxo: unknown): boolean {
  const u = utxo as { scriptRef?: unknown; assets?: unknown };
  if (u.scriptRef) return false;
  // ⛔ getUnits DOES NOT VALIDATE ITS ARGUMENT. Measured: getUnits("not-an-assets-object") and
  // getUnits({}) both return ["lovelace"], so a wrong-shaped value reads as a clean ada-only
  // UTxO — the reassuring direction. Only undefined and null throw. So the shape is checked here
  // rather than relied upon, and anything unreadable counts as NOT plain.
  if (typeof u.assets !== "object" || u.assets === null) return false;
  try {
    return !EvoAssets.getUnits(u.assets as never).some((unit: string) => unit !== "lovelace");
  } catch {
    return false;
  }
}

export { BOOTSTRAP_SEED_COUNT };
export type { BootstrapPlan, DeploymentParams };

/**
 * The five steps, named as the operator sees them, split by who has to act.
 *
 * ⛔ THE SPLIT IS THE POINT. Phase one is the deployer alone; phase two needs every
 * declared participant. Anything in phase one that could be deferred to phase two
 * SHOULD be, because phase two happens with people waiting on a call — and anything
 * in phase two that could be done in phase one must not be, because phase one spends
 * the one-shot seeds and there is no going back from it.
 */
export const PHASE_ONE_STEPS = ["seed", "multisig-genesis", "stake-registrations"] as const;
export const PHASE_TWO_STEPS = ["protocol-genesis", "reference-scripts"] as const;

export interface CeremonyStep {
  /** Shown to the operator; also what an error names. */
  label: string;
  unsignedCbor: string;
}

/**
 * Everything the SDK's builders need that does not change between phases.
 *
 * ⛔ THE SDK'S OWN TYPE, not a structural copy of it. A hand-written interface that merely
 * LOOKS like the builder's parameter is how a required field goes missing silently: this
 * session already shipped one that declared two methods optional and turned a compile error
 * into a runtime one. Aliasing means a field added upstream fails here, at build.
 */
export type CeremonyContext = BootstrapBuildContext;

export function buildPlan(config: BootstrapConfig): BootstrapPlan {
  return planBootstrap(config);
}

export { selectBootstrapSeeds, assertMultisigConfigUtxo, assembleDeploymentParams };

/**
 * Phase one: the three transactions the deployer submits alone.
 *
 * Built together and submitted in order. They chain — the seed transaction's outputs
 * fund the two that follow — so they are built in one pass against the same UTxO set.
 */
export async function buildPhaseOne(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  /** False when the wallet already holds three usable seeds. */
  needsSeedTx: boolean;
  seedUtxo: MultisigGenesisTxParams["seedUtxo"];
  upgradeMultisigTree: MultisigGenesisTxParams["upgradeMultisigTree"];
  /** Where seed outputs are paid — the steps that consume them must be able to spend them. */
  ownerAddress: SeedTxParams["ownerAddress"];
  /** Lovelace per seed output. No default: each seed funds part of the transaction that
   *  consumes it, so the right figure depends on the chain and on what should be left over. */
  seedLovelace: SeedTxParams["seedLovelace"];
}): Promise<CeremonyStep[]> {
  const steps: CeremonyStep[] = [];

  if (params.needsSeedTx) {
    const seedParams: SeedTxParams = {
      ...params.ctx,
      ownerAddress: params.ownerAddress,
      seedLovelace: params.seedLovelace,
    };
    steps.push({ label: "seed UTxOs", unsignedCbor: await cborOf(await buildSeedTx(seedParams)) });
  }

  const multisigParams: MultisigGenesisTxParams = {
    ...params.ctx,
    plan: params.plan,
    seedUtxo: params.seedUtxo,
    upgradeMultisigTree: params.upgradeMultisigTree,
  };
  steps.push({
    label: "upgrade multisig",
    unsignedCbor: await cborOf(await buildMultisigGenesisTx(multisigParams)),
  });

  const regParams: StakeRegistrationTxParams = { ...params.ctx, plan: params.plan };
  steps.push({
    label: "register credentials",
    unsignedCbor: await cborOf(await buildStakeRegistrationTx(regParams)),
  });
  return steps;
}

/**
 * The genesis — the ONE transaction the participants sign.
 *
 * ⚑ `upgradeAuthoritySigners` CANNOT BE INFERRED and is therefore required. `MultisigScript`
 * has seven node kinds and only `Signature` names a key hash; `Script` names another
 * withdraw-0, `Before`/`After` a validity bound, and `AnyOf`/`AtLeast` leave a genuine choice
 * of branch. A walker would have to pick one, and picking is the caller's decision. The page
 * refuses a tree it cannot satisfy rather than guessing — see `cosignature-panel`.
 *
 * ⚠ Naming a signer is not having one. `addSigner` writes a `required_signers` entry; the
 * witness must still arrive at submission, or the ledger answers `MissingVKeyWitnessesUTXOW`,
 * which names the hash and not the reason.
 */
export async function buildProtocolGenesis(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  protocolParamsSeedUtxo: ProtocolGenesisTxParams["protocolParamsSeedUtxo"];
  issuanceSeedUtxo: ProtocolGenesisTxParams["issuanceSeedUtxo"];
  /** Read back off the chain and vetted — never reconstructed from a record. */
  upgradeMultisigConfigUtxo: ProtocolGenesisTxParams["upgradeMultisigConfigUtxo"];
  upgradeAuthoritySigners: ProtocolGenesisTxParams["upgradeAuthoritySigners"];
  provenancePin?: ProtocolGenesisTxParams["provenancePin"];
}): Promise<CeremonyStep> {
  const genesisParams: ProtocolGenesisTxParams = {
    ...params.ctx,
    plan: params.plan,
    protocolParamsSeedUtxo: params.protocolParamsSeedUtxo,
    issuanceSeedUtxo: params.issuanceSeedUtxo,
    upgradeMultisigConfigUtxo: params.upgradeMultisigConfigUtxo,
    upgradeAuthoritySigners: params.upgradeAuthoritySigners,
    provenancePin: params.provenancePin,
  };
  return {
    label: "protocol genesis",
    unsignedCbor: await cborOf(await buildProtocolGenesisTx(genesisParams)),
  };
}

/**
 * The tail: publishing the seven reference scripts. Needs no participant.
 *
 * ⛔ `referenceScriptAddress` IS A LASTING DECISION AND HAS NO DEFAULT HERE, deliberately.
 * These outputs are protocol infrastructure for the life of the deployment — every
 * programmable transaction reads them. The SDK records the measurement behind the warning:
 * on preview, a wallet holding them alongside ordinary funds had two of four consumed by a
 * routine retry, and NOTHING ERRORED. Pay them somewhere coin selection will never reach.
 */
export async function buildReferenceScripts(params: {
  ctx: CeremonyContext;
  plan: BootstrapPlan;
  referenceScriptAddress: ReferenceScriptsTxParams["referenceScriptAddress"];
  /** Lovelace per output — min-UTxO scales with each script's size. */
  referenceScriptLovelace: ReferenceScriptsTxParams["referenceScriptLovelace"];
}): Promise<CeremonyStep> {
  const refParams: ReferenceScriptsTxParams = {
    ...params.ctx,
    plan: params.plan,
    referenceScriptAddress: params.referenceScriptAddress,
    referenceScriptLovelace: params.referenceScriptLovelace,
  };
  return {
    label: "reference scripts",
    unsignedCbor: await cborOf(await buildReferenceScriptsTx(refParams)),
  };
}

/**
 * Wait for the multisig config UTxO, and prove it is the right one.
 *
 * ⚑ POLLS FOR THE UTXO, NOT FOR THE TRANSACTION, and the difference is not stylistic.
 * We have to wait either way. Querying the multisig address and filtering by the config
 * NFT's policy answers BOTH questions in one mechanism — "has it confirmed" and "which
 * UTxO is it" — and it is self-verifying, where trusting a predicted output index is not.
 * `assertMultisigConfigUtxo` additionally refuses a decoy parked at the same address.
 *
 * There is no cross-deployment collision to worry about: `upgrade_multisig` is parameterised
 * by the one-shot seed, so every deployment has a different script address AND a different
 * config NFT policy.
 */
export async function awaitMultisigConfigUtxo(params: {
  plan: BootstrapPlan;
  expectedTree: unknown;
  /** Reads the UTxOs currently at an address. Injected so this stays testable. */
  utxosAt: (address: string) => Promise<readonly unknown[]>;
  /** Milliseconds between attempts. */
  intervalMs?: number;
  /** Gives up rather than polling forever — the operator is watching. */
  timeoutMs?: number;
  onAttempt?: (attempt: number) => void;
}): Promise<unknown> {
  const interval = params.intervalMs ?? 5_000;
  const timeout = params.timeoutMs ?? 10 * 60_000;
  const address = (params.plan as { addresses: { upgradeMultisig: string } }).addresses
    .upgradeMultisig;

  const started = Date.now();
  let attempt = 0;
  for (;;) {
    attempt += 1;
    params.onAttempt?.(attempt);
    const utxos = await params.utxosAt(address);
    if (utxos.length > 0) {
      try {
        // Throws until the config UTxO is genuinely there and well-formed. A partially
        // indexed address can return SOMETHING that is not it, so a non-empty answer is
        // not the same as a confirmed genesis.
        return assertMultisigConfigUtxo({
          plan: params.plan,
          utxosAtAddress: utxos,
          expectedTree: params.expectedTree,
        } as never);
      } catch {
        /* not there yet — keep waiting rather than failing the ceremony */
      }
    }
    if (Date.now() - started > timeout) {
      throw new Error(
        `The upgrade-multisig config UTxO has not appeared at ${address} after ` +
          `${Math.round(timeout / 60_000)} minutes. The multisig genesis may not have been ` +
          "submitted, may still be confirming, or the indexer may be behind. Nothing is lost " +
          "— check the transaction on chain and retry; the seeds are already spent either way.",
      );
    }
    await new Promise((r) => setTimeout(r, interval));
  }
}

/** Every builder returns a signable transaction; this is how it becomes hex. */
async function cborOf(built: unknown): Promise<string> {
  const tx = await (built as { toTransaction: () => Promise<unknown> }).toTransaction();
  const { EvoTransaction } = await import("@easy1staking/cip113-sdk-ts");
  return (EvoTransaction as { toCBORHex: (t: never) => string }).toCBORHex(tx as never);
}
