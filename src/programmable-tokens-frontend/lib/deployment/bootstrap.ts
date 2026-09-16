/**
 * Building the CIP-113 core bootstrap: SIX transactions, all built before any is signed.
 *
 * ## Where this came from, and what it is not
 *
 * Ported from the SDK's `test/harness/bootstrap.ts`, which is the only working alpha.4
 * bootstrap that exists. It is not importable — the published package ships `files:
 * ["dist","blueprints"]` and the harness lives under `test/` — so this is a port, not a
 * wrapper, and it was written against the harness's source rather than its behaviour. Every
 * constraint the harness records in a comment is a constraint it paid for on a devnet; the
 * ones that still apply are carried across here with their reasons, because a reason deleted
 * is a reason nobody can check.
 *
 * Giovanni ruled on 2026-09-15 that bootstrapping CIP-113 from the platform is in remit —
 * before that, this was parked on the SDK exporting a bootstrap entry point.
 *
 * ## SIX transactions, not four
 *
 * The ticket says four. Counting the harness's labels gives four; counting its `submitAndWait`
 * calls gives six. They are:
 *
 *   0. FRAGMENT  — three distinct seed UTxOs. The one-shot policies are parameterised by
 *                  specific outrefs, so a deployment needs three independent ones.
 *   1. MULTISIG  — the upgrade authority's config UTxO. FIRST, and the order is load-bearing:
 *                  see the tx1 comment.
 *   2. GENESIS   — the three one-shot mints and the three protocol-state outputs, carrying the
 *                  CIP-171 record under label 1984.
 *   3. PUBLISH   — seven reference scripts. Cannot be folded into GENESIS: a transaction
 *                  cannot reference a script it is itself creating.
 *   4. NOMINEE   — the wallet's own stake key, registered AND delegated to a DRep.
 *   5. REGISTER  — the six script stake credentials, each under the PUBLISH purpose.
 *
 * ## Nothing is submitted until everything builds AND evaluates
 *
 * The epic's ruling was "all-or-nothing in the sense of pre-flight". Evolution's
 * `chainResult()` makes that real rather than aspirational: it returns the built transaction's
 * pre-computed hash together with the UTxO set as it will be AFTER that transaction, so step
 * N+1 can be built — and script-evaluated, with the chained UTxOs passed to the evaluator as
 * `additionalUtxos` (which requires `passAdditionalUtxos: true`; it defaults to FALSE, and
 * without it the evaluator is handed inputs it has no way to resolve) — against outputs that do
 * not exist on chain yet. Every step is therefore
 * built and evaluated with real execution units before the wallet is asked for a signature.
 *
 * ⚠ ONE GAP IN THAT CLAIM, UNRESOLVED AND STATED RATHER THAN GLOSSED. Evolution passes the
 * evaluator its selected inputs and reference inputs; COLLATERAL is chosen separately and is
 * not among them. Three of the six steps carry redeemers and therefore collateral, and on a
 * chained build their collateral candidates are outputs of earlier, unsubmitted transactions.
 * Whether a provider-side evaluator tolerates that is not something this code can settle
 * offline — it is the first thing to watch on the preview run (T-044), not a proven property.
 *
 * It is still NOT atomic, and nothing can make it so. Six chained transactions cannot be
 * unwound once the fourth lands. What the pre-flight buys is that the failures which CAN be
 * caught offline — a mis-derived script, an underfunded output, a failing validator, an
 * unaffordable deployment — are caught while the cost of failing is zero.
 *
 * ## One deliberate divergence from the harness, and why
 *
 * The harness hands `availableUtxos: spendable(await client.getUtxos(address))` to every
 * build, which includes the seed UTxOs that later transactions must consume. Nothing stops
 * coin selection from spending seed #1 for fees in the multisig transaction and leaving the
 * genesis with no seed to consume; on a devnet wallet with many UTxOs it simply never
 * happened. Here the seeds are RESERVED — excluded from `availableUtxos` until the
 * transaction that is meant to consume them — because a chained build has a much smaller UTxO
 * set to choose from and the near-miss becomes a hit.
 */
import {
  Address as EvoAddress,
  Assets as EvoAssets,
  Bytes,
  Credential,
  Data,
  DRep,
  InlineDatum,
  Transaction as EvoTx,
  TransactionHash as EvoTransactionHash,
  UPLC,
} from "@evolution-sdk/evolution";
import {
  buildEvoScript,
  minUtxoAtLeast,
  mintAssetsFromMap,
  outputAssets,
  protocolParamsDatum,
  decodeMultisigScript,
  getInlineDatum,
  registryNodeDatum,
  REGISTRY_NODE_MIN_ADA,
  scriptAddress,
  stakingCredentialHash,
  stringToHex,
  rewardAddressFromKeyHash,
  voidData,
} from "@easy1staking/cip113-sdk-ts";
import type { DeploymentParams, PlutusBlueprint, TxInput } from "@easy1staking/cip113-sdk-ts";

import { buildCoreScriptSet, type CoreScriptSet, type DeploymentSeeds } from "./derive";
import { buildCoreProvenanceMetadatum, CIP171_METADATA_LABEL } from "./provenance";
import type { UpstreamPin } from "./blueprint";
import type { ResolvedMultisig } from "./multisig";
import type { MultiTxStep } from "../tx/multi-tx";

/** Lovelace parked in each of the three one-shot seed UTxOs. */
const SEED_LOVELACE = 5_000_000n;

/** Each published reference script output. The script body dominates its min-UTxO. */
const REF_SCRIPT_LOVELACE = 20_000_000n;

/**
 * The issuance CBOR datum carries ~700 bytes after the alpha.4 split, and min-UTxO scales with
 * serialised output size. Kept as the harness's measured floor rather than recomputed, because
 * `minUtxoAtLeast` raises it when the datum is larger and never lowers it.
 */
const ISSUANCE_OUTPUT_LOVELACE = 15_000_000n;

/**
 * Placeholder minting-logic hash, split out of the `issuance_mint` CBOR body.
 *
 * `issuance_mint` is parameterised per minting-logic hash — once per substandard — so a core
 * deployment cannot know its final form. It stores the CBOR either side of this placeholder
 * instead, and a registration splices the real hash in.
 */
const DUMMY_POLICY_ID = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";

/**
 * The reference-script publication order. APPENDED TO, NEVER INSERTED INTO.
 *
 * This array and the recorded output indices are one fact: the publish loop pays in this order
 * and `DeploymentParams` records the indices derived from it. A mismatch does not fail loudly —
 * it hands out a reference input carrying the WRONG script, and the transaction that uses it
 * dies at evaluation naming neither.
 */
const REF_SCRIPT_ORDER = [
  "programmableLogicBase",
  "programmableLogicGlobal",
  "transfer",
  "thirdParty",
  "unfracking",
  "issuanceLogic",
  "upgradeMultisig",
] as const;

type RefScriptName = (typeof REF_SCRIPT_ORDER)[number];
const refIdx = (name: RefScriptName) => REF_SCRIPT_ORDER.indexOf(name);

/**
 * Publish handlers a bootstrap cannot proceed without.
 *
 * Registering a script stake credential emits a Conway RegCert, which runs the script under
 * the PUBLISH purpose. A script with no publish handler fails there with a bare "machine
 * terminated" and an empty trace list — undiagnosable unless something checked first.
 */
const REQUIRED_PUBLISH_HANDLERS = [
  "transfer.transfer.publish",
  "third_party.third_party.publish",
  "unfracking.unfracking.publish",
] as const;

/** Minimal structural view of an Evolution UTxO, so this module needs no private types. */
export interface ChainUtxo {
  transactionId: unknown;
  index: number | bigint;
  address: unknown;
  assets: unknown;
  scriptRef?: unknown;
}

interface ChainResult {
  readonly available: readonly ChainUtxo[];
  readonly txHash: string;
}

interface BuiltTx {
  /**
   * ⛔ The ONLY way to the bytes. A built transaction (Evolution's `SignBuilder`) has no
   * `toCBOR` and no `toCBORHex`; it exposes `toTransaction()`, and it is ASYNC. Audit r1
   * measured the earlier shape here — two optional methods, neither of which exists — failing
   * on every step for every wallet on every network. Declaring them optional is what turned a
   * compile error into a guaranteed runtime one, so this is required and exact.
   */
  toTransaction: () => Promise<unknown>;
  chainResult: () => ChainResult;
}

/** The subset of an Evolution signing client this module uses. */
export interface BootstrapClient {
  newTx: () => any;
  getUtxos: (address: unknown) => Promise<ChainUtxo[]>;
  getProtocolParameters: () => Promise<{ coinsPerUtxoByte: bigint }>;
}

export interface BuildBootstrapInput {
  client: BootstrapClient;
  /** 0 for every testnet, 1 for mainnet. Used only to form script addresses. */
  networkId: number;
  blueprint: PlutusBlueprint;
  pin: UpstreamPin;
  /** The deploying wallet. Its payment credential pays for everything; its stake key is the nominee. */
  changeAddress: string;
  multisig: ResolvedMultisig;
  maxInlineDatumBytes: number;
  alwaysFailNonce: string;
  /** False compiles the dispatcher against the disabled sentinel. The validator still deploys. */
  unfrackingEnabled?: boolean;
  /**
   * The three one-shot seed UTxOs, when the wallet already holds three that will do.
   *
   * Omit and the plan opens with a fragmentation transaction that creates them — one more
   * transaction, and its outputs do not exist on chain while the rest of the plan is built and
   * evaluated against them. Supplying real, already-confirmed UTxOs is therefore the better
   * path whenever it is available: it is one transaction shorter, and the first transaction in
   * the chain then has real inputs and real collateral candidates rather than predicted ones.
   *
   * They must be three DISTINCT UTxOs. `protocolParams.txInput` and `upgradeMultisig.txInput`
   * are the same type and are not interchangeable — one UTxO in both slots deploys perfectly
   * well and makes `assertDeploymentScripts` vacuous, because the upgrade-multisig check then
   * passes whichever of the two fields the verifier happens to read.
   */
  seeds?: DeploymentSeeds;
  /**
   * Whether a reward address is already registered on chain. REQUIRED, and deliberately not
   * defaulted.
   *
   * The nominee stake key belongs to the WALLET, not to the protocol, so it survives across
   * deployments and a second bootstrap from the same wallet legitimately finds it registered.
   * Guessing wrong is not cheap: a register certificate for an already-registered credential
   * is rejected by the ledger, and by then the four transactions before it have landed. The
   * harness could tolerate that by catching the submission error and retrying — a pre-flight
   * build has no such second chance, so the question must be answered before anything is
   * built.
   */
  isStakeRegistered: (rewardAddress: string) => Promise<boolean>;
  /** Passed through to every `build()`. Without one the provider evaluates. */
  evaluator?: unknown;
}

export interface BootstrapPlan {
  /** In submission order. Hand to `signAndSubmitSequence`. */
  steps: MultiTxStep[];
  /**
   * Complete, with every transaction hash pre-computed. Assertable BEFORE submission — which
   * is what lets the page verify a deployment it has not yet made.
   */
  deployment: DeploymentParams;
  seeds: DeploymentSeeds;
  /** What the deployment costs the wallet in total: outputs, deposits and fees. */
  totalCostLovelace: bigint;
  /** Balance the wallet held when the plan was built. */
  walletBalanceLovelace: bigint;
  /** True when the nominee stake key was already registered, so step 4 only delegates. */
  nomineeAlreadyRegistered: boolean;
}

const lovelaceOf = (assets: unknown): bigint => EvoAssets.lovelaceOf(assets as never);

const outRef = (u: ChainUtxo): TxInput => ({
  txHash: txHashHexOf(u),
  outputIndex: Number(u.index),
});

function txHashHexOf(u: ChainUtxo): string {
  // ⛔ `EvoTransactionHash.toHex`, NEVER `Bytes.toHex`. A transaction id is not a Uint8Array —
  // it is a tagged class wrapping one — so `Bytes.toHex` fails its type-side check and throws
  // `Uint8ArrayFromHex / Type side transformation failure`, a message naming nothing about
  // transaction hashes or wallets. Two lines below, `Bytes.toHex` IS correct, because there it
  // is handed a real byte slice. Audit r1 measured this: it threw on the first wallet UTxO,
  // before a single transaction was built.
  return EvoTransactionHash.toHex(u.transactionId as never);
}

const refKey = (r: { txHash: string; outputIndex: number }) => `${r.txHash}#${r.outputIndex}`;

function addressBech32Of(u: ChainUtxo): string {
  const a = u.address;
  if (typeof a === "string") return a;
  try {
    return EvoAddress.toBech32(a as never);
  } catch {
    return "";
  }
}

/**
 * Wallet UTxOs this build may spend freely.
 *
 * Three exclusions, each for a failure that has actually happened somewhere:
 *
 * - **Not ours.** `chainResult().available` carries every output the previous transaction
 *   created, including the ones paid to SCRIPT addresses. Handing those to coin selection
 *   would invite it to try to spend protocol state it just created.
 * - **No reference script.** MEASURED by the harness on preview: after three deployments the
 *   wallet held 11 script-bearing UTxOs of 22, and coin selection is free to pick them.
 *   Spending one destroys that deployment's infrastructure AND drags the script's bytes into
 *   the transaction, which is what burst the 16,384-byte size cap.
 * - **Not reserved.** A seed a later transaction must consume is not available for fees now.
 */
function ownSpendable(
  all: readonly ChainUtxo[],
  ownAddress: string,
  reserved: readonly TxInput[] = [],
): ChainUtxo[] {
  const blocked = new Set(reserved.map(refKey));
  return all.filter(
    (u) =>
      !u.scriptRef &&
      addressBech32Of(u) === ownAddress &&
      !blocked.has(refKey(outRef(u))),
  );
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * The wallet's UTxOs, once the provider's view has stopped moving.
 *
 * A single read cannot tell a settled view from a stale one, and a stale one is not caught by
 * anything downstream: a build cannot detect that an input is already spent, so the staleness
 * survives all six builds and all six signatures and surfaces as code 3117, "unknown UTxO
 * references as inputs", on the FIRST submission — an error that names a UTxO and reads as a
 * builder bug. Two consecutive identical reads is the harness's proxy for "the indexer has
 * settled" and is cheap next to discarding six signatures.
 */
async function settledUtxos(client: BootstrapClient, address: unknown): Promise<ChainUtxo[]> {
  const fingerprint = (utxos: ChainUtxo[]) =>
    utxos.map((u) => refKey(outRef(u))).sort().join(",");
  let previous = await client.getUtxos(address);
  for (let i = 0; i < 10; i++) {
    await sleep(1_000);
    const current = await client.getUtxos(address);
    if (fingerprint(current) === fingerprint(previous)) return current;
    previous = current;
  }
  return previous;
}

/**
 * Retry a BUILD, never a submission.
 *
 * MEASURED by the harness: the transient fires inside Evolution's OWN `getProtocolParameters()`
 * while building a stake certificate — at `Stake.ts:64`, not at any call site of ours — and it
 * hit all three stake operations. `build()` is side-effect-free, so retrying it is safe; and in
 * a chained build a throw at step 5 discards the four transactions already built, so a single
 * provider blip otherwise costs the whole plan.
 */
async function retryBuild<T>(label: string, build: () => Promise<T>): Promise<T> {
  let last: unknown;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      return await build();
    } catch (e) {
      last = e;
      if (attempt < 3) await sleep(1_000 * attempt);
    }
  }
  throw new Error(`${label} failed after 3 attempts: ${(last as Error)?.message ?? last}`);
}

/**
 * Three wallet UTxOs fit to be one-shot seeds, or null if the wallet has no three.
 *
 * "Fit" is narrow on purpose: at the deploying wallet's own address, no reference script, no
 * native assets, and enough lovelace to be worth spending as an input. Native assets are
 * excluded because consuming such a UTxO drags its tokens into the genesis transaction, where
 * they would have to go somewhere — and `has_nft_strict` means "somewhere" cannot be the
 * config output.
 *
 * Largest first, so the seeds also help fund the transactions that consume them.
 */
export function selectSeedUtxos(
  utxos: readonly ChainUtxo[],
  ownAddress: string,
  minLovelace = SEED_LOVELACE,
): DeploymentSeeds | null {
  const usable = utxos
    .filter(
      (u) =>
        !u.scriptRef &&
        addressBech32Of(u) === ownAddress &&
        !EvoAssets.getUnits(u.assets as never).some((unit: string) => unit !== "lovelace") &&
        lovelaceOf(u.assets) >= minLovelace,
    )
    .sort((a, b) => (lovelaceOf(b.assets) > lovelaceOf(a.assets) ? 1 : -1));
  if (usable.length < 3) return null;
  return {
    paramsSeed: outRef(usable[0]),
    issuanceSeed: outRef(usable[1]),
    multisigSeed: outRef(usable[2]),
  };
}

/** The PlutusV3 script body hex (inner UPLC, no outer CBOR wrap). */
function scriptBodyHex(compiledCode: string): string {
  if (UPLC.getCborEncodingLevel(compiledCode) !== "double") return compiledCode;
  const raw = Bytes.fromHex(compiledCode);
  const additionalInfo = raw[0] & 0x1f;
  const headerLen =
    additionalInfo < 24 ? 1 : additionalInfo === 24 ? 2 : additionalInfo === 25 ? 3 : 5;
  return Bytes.toHex(raw.slice(headerLen));
}

function requirePublishHandlers(blueprint: PlutusBlueprint): void {
  const titles = blueprint.validators.map((v) => v.title);
  const missing = REQUIRED_PUBLISH_HANDLERS.filter((t) => !titles.includes(t));
  if (missing.length > 0) {
    throw new Error(
      `This blueprint cannot bootstrap a protocol: it lacks publish handler(s) ` +
        `${missing.join(", ")}, so registering the corresponding stake credential fails at ` +
        `script evaluation (purpose "publish") with no diagnostic. Blueprint: ` +
        `"${blueprint.preamble.title}" v${blueprint.preamble.version}.`,
    );
  }
}

async function cborOf(built: BuiltTx, label: string): Promise<string> {
  const hex = EvoTx.toCBORHex((await built.toTransaction()) as never);
  if (typeof hex !== "string" || hex.length === 0) {
    throw new Error(`Built ${label} but could not read its CBOR; nothing can be signed.`);
  }
  return hex;
}

/**
 * The `issuance_mint` body, cut either side of the placeholder minting-logic hash.
 *
 * A core deployment cannot know `issuance_mint`'s final form — it is parameterised per
 * minting-logic hash, once per substandard — so the genesis stores the CBOR either side of a
 * placeholder and a registration splices the real hash in.
 *
 * Exported because this is the one part of the bootstrap whose correctness is a measurable
 * property of the BLUEPRINT rather than of a chain, so it can be checked offline. Flat UPLC is
 * BIT-packed: a parameter is findable as a whole number of hex bytes only when it happens to
 * land on a byte boundary, and that alignment must be measured per artefact, never assumed.
 */
export function splitIssuanceMintCbor(core: CoreScriptSet): {
  cborPre: string;
  cborPost: string;
} {
  const dummy = core.builders.issuanceMint(DUMMY_POLICY_ID, core.protocolParams.hash);
  const parts = scriptBodyHex(dummy.compiledCode).split(DUMMY_POLICY_ID);
  if (parts.length !== 2) {
    // KEEP THIS A HARD FAILURE, never a warning. The splice reassembles the script from
    // `pre + <real hash> + post`, so it is correct only while the placeholder occurs EXACTLY
    // once. Zero means the parameter is no longer inlined where this assumes; two means the
    // splice would silently rewrite an unrelated run of bytes.
    throw new Error(
      `The placeholder minting-logic hash appears ${parts.length - 1} times in the ` +
        `issuance_mint body; expected exactly once. This blueprint does not inline that ` +
        `parameter on a byte boundary, so the issuance CBOR cannot be split around it.`,
    );
  }
  return { cborPre: parts[0], cborPost: parts[1] };
}

export async function buildBootstrapPlan(input: BuildBootstrapInput): Promise<BootstrapPlan> {
  const {
    client,
    networkId,
    blueprint,
    pin,
    changeAddress,
    multisig,
    maxInlineDatumBytes,
    alwaysFailNonce,
  } = input;

  requirePublishHandlers(blueprint);

  // ⛔ FAIL CLOSED ON THE BOUND. It reaches the page as free text, and `Number("")` is 0 — which
  // is not a rejected input, it is a DEPLOYED one: transfer, third_party, unfracking and
  // issuance_logic all parameterised with an inline-datum bound of zero. That builds, hashes,
  // verifies green (the same value feeds derivation and re-derivation), deploys, and then
  // rejects every programmable transfer carrying any inline datum, for the life of the protocol.
  if (!Number.isInteger(maxInlineDatumBytes) || maxInlineDatumBytes <= 0) {
    throw new Error(
      `maxInlineDatumBytes must be a positive whole number; got ${JSON.stringify(
        maxInlineDatumBytes,
      )}. It is baked into four scripts at compile time and cannot be changed afterwards.`,
    );
  }

  const changeAddressObj = EvoAddress.fromBech32(changeAddress);
  const buildOpts = (available: ChainUtxo[]) => ({
    changeAddress: changeAddressObj,
    evaluator: input.evaluator,
    availableUtxos: available,
    // ⛔ REQUIRED, and it DEFAULTS TO FALSE. Without it a provider-based evaluator is asked to
    // evaluate a transaction whose inputs are outputs of a transaction that has not been
    // submitted, and is given no way to resolve them. The whole chained pre-flight depends on
    // this one flag; audit r1 found the claim being made in a comment while the flag was unset.
    passAdditionalUtxos: true,
  });

  const walletUtxos = await settledUtxos(client, changeAddressObj);
  // ⛔ THE SAME POPULATION ON BOTH SIDES OF THE SUBTRACTION. `totalCostLovelace` below is
  // `spendable before - spendable after`, so this must be spendable-only too. Summing ALL
  // wallet UTxOs here and `ownSpendable` there was measured by audit r1 to overstate the cost
  // by 20 ADA for every reference-script UTxO the wallet already holds — the harness saw 11 of
  // them on preview after three deployments, so a ~200 ADA deployment reported ~420. It never
  // under-states, so it cannot cause an under-funded submission; it causes an operator to
  // abort a deployment they could afford.
  const spendableBefore = ownSpendable(walletUtxos, changeAddress);
  const walletBalanceLovelace = spendableBefore.reduce((s, u) => s + lovelaceOf(u.assets), 0n);
  const { coinsPerUtxoByte } = await client.getProtocolParameters();

  const steps: MultiTxStep[] = [];

  // ---- The three one-shot seeds -------------------------------------------
  //
  // Either the operator's three existing wallet UTxOs, or a fragmentation transaction that
  // creates them. The first is preferred and is one transaction shorter; the second is what a
  // freshly funded wallet holding a single UTxO needs.
  let seeds: DeploymentSeeds;
  let seedUtxos: ChainUtxo[];
  let availableAfterSeeds: ChainUtxo[];

  if (input.seeds) {
    seeds = input.seeds;
    const wanted = [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed];
    const distinct = new Set(wanted.map(refKey));
    if (distinct.size !== 3) {
      throw new Error(
        "The three seeds must be three DISTINCT UTxOs. Reusing one across two slots deploys " +
          "without complaint and makes the upgrade-multisig derivation check vacuous, because " +
          "it would then pass whichever of the two fields a verifier reads.",
      );
    }
    const byRef = new Map(walletUtxos.map((u) => [refKey(outRef(u)), u]));
    seedUtxos = wanted.map((ref) => {
      const found = byRef.get(refKey(ref));
      if (!found) {
        throw new Error(
          `Seed ${ref.txHash.slice(0, 12)}…#${ref.outputIndex} is not an unspent output of ` +
            `this wallet. A one-shot policy is parameterised by a UTxO the transaction must ` +
            `consume, so it has to exist and be spendable now.`,
        );
      }
      if (found.scriptRef) {
        throw new Error(
          `Seed ${ref.txHash.slice(0, 12)}…#${ref.outputIndex} carries a reference script. ` +
            `Spending it would destroy that script and drag its bytes into this transaction.`,
        );
      }
      if (EvoAssets.getUnits(found.assets as never).some((unit: string) => unit !== "lovelace")) {
        throw new Error(
          `Seed ${ref.txHash.slice(0, 12)}…#${ref.outputIndex} carries native assets. They ` +
            `would have to be paid somewhere by the transaction that consumes it, and the ` +
            `config output cannot take them — \`has_nft_strict\` is strict about the whole value.`,
        );
      }
      return found;
    });
    // The seeds stay out of coin selection until the transaction that consumes them.
    availableAfterSeeds = ownSpendable(walletUtxos, changeAddress, wanted);
  } else {
    let fragTx = client.newTx();
    for (let i = 0; i < 3; i++) {
      fragTx = fragTx.payToAddress({
        address: changeAddressObj,
        assets: outputAssets(SEED_LOVELACE),
      });
    }
    const fragBuilt: BuiltTx = await fragTx.build(buildOpts(spendableBefore));
    const fragChain = fragBuilt.chainResult();
    steps.push({ label: "seed UTxOs", unsignedCbor: await cborOf(fragBuilt, "the seed transaction") });

    const created = fragChain.available
      .filter((u) => txHashHexOf(u) === fragChain.txHash)
      .sort((a, b) => Number(a.index) - Number(b.index));
    seedUtxos = created.filter((u) => Number(u.index) < 3);
    if (seedUtxos.length !== 3) {
      throw new Error(
        `The seed transaction produced ${seedUtxos.length} of the 3 expected seed outputs. ` +
          `Every one-shot policy in the deployment is parameterised by one of them, so there ` +
          `is nothing to derive from.`,
      );
    }
    seedUtxos.forEach((u, i) => {
      // Evolution appends change after the explicit outputs and does not reorder them, so
      // `index < 3` already excludes change. These assertions are belt-and-braces against that
      // ordering ever changing — if it does, the failure is not an error, it is three one-shot
      // policies parameterised by the wrong outrefs.
      if (lovelaceOf(u.assets) !== SEED_LOVELACE) {
        throw new Error(
          `Seed output #${i} holds ${lovelaceOf(u.assets)} lovelace, expected exactly ` +
            `${SEED_LOVELACE}. The seed outputs are identified by position, so this is not ` +
            `the output the derivation would be parameterised by.`,
        );
      }
      if (EvoAssets.getUnits(u.assets as never).some((unit: string) => unit !== "lovelace")) {
        throw new Error(`Seed output #${i} carries native assets; a seed must hold lovelace only.`);
      }
      if (addressBech32Of(u) !== changeAddress) {
        throw new Error(`Seed output #${i} is not at the deploying wallet's address.`);
      }
    });
    seeds = {
      paramsSeed: outRef(seedUtxos[0]),
      issuanceSeed: outRef(seedUtxos[1]),
      multisigSeed: outRef(seedUtxos[2]),
    };
    availableAfterSeeds = ownSpendable(fragChain.available, changeAddress, [
      seeds.paramsSeed,
      seeds.issuanceSeed,
      seeds.multisigSeed,
    ]);
  }

  const allSeedRefs = [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed];

  // ---- Derive every core script from those seeds --------------------------
  const core = buildCoreScriptSet({
    blueprint,
    seeds,
    alwaysFailNonce,
    maxInlineDatumBytes,
    unfrackingEnabled: input.unfrackingEnabled,
  });

  // `issuance_mint` against a placeholder, so a later registration can splice in the real
  // minting-logic hash without re-deriving the script. Built from the SAME builders, after the
  // core set is sealed — see `buildCoreScriptSet`, which is why this does not reach the
  // CIP-171 record.
  const { cborPre, cborPost } = splitIssuanceMintCbor(core);

  // ---- Addresses, datums and asset units ----------------------------------
  const paramsAddr = scriptAddress(networkId, core.protocolParams.hash);
  const registryAddr = scriptAddress(networkId, core.registry.hash);
  const issuanceAddr = scriptAddress(networkId, core.alwaysFailHash);
  const multisigAddr = scriptAddress(networkId, core.upgradeMultisig.hash);

  const paramsNftUnit = core.protocolParams.hash + stringToHex("ProtocolParams");
  const registryNftUnit = core.registry.hash; // empty asset name
  const issuanceNftUnit = core.issuanceCborHexMint.hash + stringToHex("IssuanceCborHex");
  const multisigNftUnit = core.upgradeMultisig.hash + stringToHex("UpgradeMultisig");

  /**
   * SIX fields, written BY NAME and never as a positional spread.
   *
   * `issuanceLogicCred` was INSERTED at index 1 in alpha.4, displacing `transferCred` to 2.
   * Both are `Credential` — same constructor, same 28 bytes — so a datum in alpha.3's order
   * with two fields appended is still six fields long, still passes the arity check, still
   * passes `params_wellformed`, and hands `issuance_mint` the TRANSFER credential as its
   * issuance authority. Nothing at deploy time catches it.
   *
   * `pendingUpgradeCred` MUST be null at genesis: `params_wellformed(.., is_init: True)`
   * forbids a nomination baked into the genesis datum, and a `Some(..)` here does not deploy.
   */
  const paramsDatum = protocolParamsDatum({
    plgCred: { type: "script", hash: core.programmableLogicGlobal.hash },
    issuanceLogicCred: { type: "script", hash: core.issuanceLogic.hash },
    transferCred: { type: "script", hash: core.transfer.hash },
    thirdPartyCred: { type: "script", hash: core.thirdParty.hash },
    upgradeCred: { type: "script", hash: core.upgradeMultisig.hash },
    pendingUpgradeCred: null,
  });

  // Sentinel head of the registry linked list: key "", next 0xff*30, every delegate slot empty.
  const EMPTY_CRED = { type: "key" as const, hash: "" };
  const registryDatum = registryNodeDatum({
    key: "",
    next: "ff".repeat(30),
    mintingLogicScript: EMPTY_CRED,
    transferLogicScript: EMPTY_CRED,
    thirdPartyTransferLogicScript: EMPTY_CRED,
    unfrackingLogicScript: EMPTY_CRED,
    globalStateCs: "",
  });

  const issuanceDatum = Data.constr(0n, [Data.bytearray(cborPre), Data.bytearray(cborPost)]);

  // ---- Tx 1: the upgrade-multisig config UTxO, BEFORE the protocol genesis -
  //
  // THE ORDER IS THE POINT, AND IT IS NOT A STYLE CHOICE. The genesis datum names
  // `upgrade_cred = Script(upgrade_multisig)`, and that authority is only usable while its
  // config UTxO exists — the signer tree lives there, not in the script's parameters. Running
  // the multisig genesis AFTER the protocol genesis and failing would leave a protocol on
  // chain naming an authority whose config UTxO does not exist: upstream's documented one-way
  // brick, with no repair path, manufactured by transaction ordering rather than by any defect.
  //
  // The rails `upgrade_multisig.mint` enforces, all in this one output: the named UTxO is
  // consumed; exactly one "UpgradeMultisig" token of this policy is minted; an output is found
  // by `has_nft_strict`; the tree is well-formed, carries NO reference script, and sits at
  // `from_script(policy)`.
  const multisigAssets = new Map([[multisigNftUnit, 1n]]);
  // THE NFT AND NOTHING ELSE. `has_nft_strict` is strict about the WHOLE value: bundling any
  // other asset with the config NFT means the output is simply NOT FOUND by `list.expect_find`,
  // and the genesis fails saying nothing about bundling.
  const multisigLovelace = minUtxoAtLeast(2_000_000n, {
    address: multisigAddr,
    assets: outputAssets(0n, multisigAssets),
    datum: multisig.datum,
    coinsPerUtxoByte,
  });

  let msTx = client.newTx();
  msTx = msTx.collectFrom({ inputs: [seedUtxos[2]] });
  msTx = msTx.mintAssets({
    assets: mintAssetsFromMap(new Map([[multisigNftUnit, 1n]])),
    redeemer: voidData(),
  });
  msTx = msTx.payToAddress({
    address: EvoAddress.fromBech32(multisigAddr),
    assets: outputAssets(multisigLovelace, multisigAssets),
    datum: new InlineDatum.InlineDatum({ data: multisig.datum }),
    // NO `script:` — rail 4 requires `reference_script == None`. It is published in tx 3 like
    // every other one.
  });
  /**
   * A DECOY: a second, NFT-FREE output at the multisig address.
   *
   * ⛔ THIS IS WHAT MAKES THE GATE BELOW ABLE TO FAIL, and it is not decoration. The multisig
   * address is derived from a fresh one-shot seed, so without this the only thing there is the
   * output we just wrote — and a filter applied to a population of one, keyed on a unit this
   * same function constructed, passes whether or not it is right. The SDK harness measured
   * exactly that (audit r1, F-2): replacing its filter with "take anything at this address"
   * changed nothing, while its comment claimed the filter asked the validator's question.
   *
   * ⚑ It is also a REAL condition, not an invented one. Anyone may pay to a script address at
   * any time, and upstream's `upgrade_multisig.spend` contemplates it explicitly.
   *
   * ⚠ Safe against `upgrade_multisig.mint`: rail 3 uses `list.expect_find` over outputs, which
   * SKIPS a non-matching output rather than rejecting it, and rails 1/2/4 constrain the mint,
   * the token count and `nft_output` only. This output carries no NFT, so `has_nft_strict`
   * never matches it.
   *
   * ⚠ COST, stated because it is real money on mainnet: this output is permanently unspendable
   * (no datum, so the spend handler's `expect Some(old_tree)` fails) and costs one min-UTxO,
   * once, per deployment. That is the price of the gate below being a check rather than a
   * claim.
   */
  const decoyLovelace = minUtxoAtLeast(2_000_000n, {
    address: multisigAddr,
    assets: outputAssets(0n),
    coinsPerUtxoByte,
  });
  msTx = msTx.payToAddress({
    address: EvoAddress.fromBech32(multisigAddr),
    assets: outputAssets(decoyLovelace),
  });
  msTx = msTx.attachScript({ script: buildEvoScript(core.upgradeMultisig.compiledCode) });

  const msBuilt: BuiltTx = await msTx.build(buildOpts(availableAfterSeeds));
  const msChain = msBuilt.chainResult();
  steps.push({ label: "upgrade multisig", unsignedCbor: await cborOf(msBuilt, "the multisig transaction") });

  // ---- The authority must be OPERABLE, not merely named --------------------
  //
  // The harness reads this UTxO back OFF THE CHAIN before writing a genesis datum that names
  // it. A pre-flight build cannot: the transaction has not been submitted. So the same
  // questions are asked of the built transaction's own outputs.
  //
  // ⛔ WHAT THIS IS AND IS NOT. An earlier version of this block filtered by policy and
  // asserted the count was 1, with a comment claiming the lookup was therefore "structural,
  // the way the validator finds it". That was FALSE and both auditors found it: the address is
  // one-shot, so the population was the single output we had just written, and the policy
  // clause compared our own construction against itself. It could not fail. The decoy above
  // restores a population to discriminate within; the datum round-trip below is the part that
  // can actually catch a wrong authority.
  const atMultisigAddress = msChain.available.filter(
    (u) => txHashHexOf(u) === msChain.txHash && addressBech32Of(u) === multisigAddr,
  );
  if (atMultisigAddress.length < 2) {
    throw new Error(
      `The multisig transaction creates ${atMultisigAddress.length} output(s) at ` +
        `${multisigAddr}. It is meant to create two — the config UTxO and a decoy beside it — ` +
        `so that the check below has something to discriminate. With fewer, that check passes ` +
        `over a population of one and proves nothing.`,
    );
  }
  const multisigCandidates = atMultisigAddress.filter((u) =>
    EvoAssets.getUnits(u.assets as never).some(
      (unit: string) => unit !== "lovelace" && unit.slice(0, 56) === core.upgradeMultisig.hash,
    ),
  );
  if (multisigCandidates.length !== 1) {
    throw new Error(
      `${multisigCandidates.length} of the ${atMultisigAddress.length} outputs at ` +
        `${multisigAddr} carry an asset of policy ${core.upgradeMultisig.hash}; expected ` +
        `exactly 1. The NFT is one-shot, so zero means the config output is not where the ` +
        `validator locks it. Refusing to build a genesis naming an authority whose config UTxO ` +
        `is not exactly one well-formed output — an unsatisfiable authority is a permanent ` +
        `brick with no repair path.`,
    );
  }
  const configUtxo = multisigCandidates[0];

  // `has_nft_strict` is strict about the WHOLE value: bundling any other asset with the config
  // NFT means the output is simply NOT FOUND by `list.expect_find`, and the genesis then fails
  // saying nothing about bundling.
  const configUnits = EvoAssets.getUnits(configUtxo.assets as never).filter(
    (unit: string) => unit !== "lovelace",
  );
  if (configUnits.length !== 1) {
    throw new Error(
      `The config output carries ${configUnits.length} assets besides lovelace; ` +
        `\`has_nft_strict\` requires exactly one, and an output carrying more is not found at ` +
        `all rather than rejected with a reason.`,
    );
  }
  if (configUtxo.scriptRef) {
    throw new Error(
      "The config output carries a reference script; the mint's fourth rail requires " +
        "`reference_script == None`. It is published with the others instead.",
    );
  }

  // ⛔ THE ROUND TRIP IS THE REAL CHECK. The tree is read back out of the built output with the
  // DECODER and compared to what the operator asked for. Encoder and decoder are deliberately
  // asymmetric in the SDK — `multisigScriptDatum` enforces upstream's `well_formed`,
  // `decodeMultisigScript` enforces none of it — so this is two independent code paths agreeing
  // on one value, not a value agreeing with itself.
  const writtenTree = getInlineDatum(configUtxo as never);
  if (!writtenTree) {
    throw new Error(
      "The config output carries no inline datum. The signer tree IS the authority; without " +
        "it the credential is unsatisfiable and the protocol would be bricked at genesis.",
    );
  }
  const decodedTree = decodeMultisigScript(writtenTree);
  if (JSON.stringify(decodedTree) !== JSON.stringify(multisig.tree)) {
    throw new Error(
      `The authority written into the config output is not the one configured. Wanted ` +
        `${JSON.stringify(multisig.tree)}, the transaction carries ` +
        `${JSON.stringify(decodedTree)}. Refusing before the genesis rather than after it.`,
    );
  }

  const multisigUtxoRef = outRef(configUtxo);

  // ---- Tx 2: the one-shot mints and the protocol state --------------------
  //
  // The CIP-171 record rides this transaction. It is NOT required to: the registry's ingest
  // filters on `label == 1984` alone and associates by script hash at lookup time. It rides
  // here because one artefact is easier to inspect than three.
  let genTx = client.newTx();
  genTx = genTx.attachMetadata({
    label: CIP171_METADATA_LABEL,
    metadata: buildCoreProvenanceMetadatum({ pin, parameterizations: core.parameterizations }),
  });
  genTx = genTx.collectFrom({ inputs: [seedUtxos[0], seedUtxos[1]] });

  genTx = genTx.mintAssets({
    assets: mintAssetsFromMap(new Map([[registryNftUnit, 1n]])),
    redeemer: Data.constr(0n, []),
  });
  genTx = genTx.mintAssets({
    assets: mintAssetsFromMap(new Map([[paramsNftUnit, 1n]])),
    redeemer: Data.constr(1n, []),
  });
  genTx = genTx.mintAssets({
    assets: mintAssetsFromMap(new Map([[issuanceNftUnit, 1n]])),
    redeemer: Data.constr(2n, []),
  });

  // SOLVED, NOT FLAT. Evolution does not rescue an under-funded output: the shortfall survives
  // to submission and is reported as "insufficient Ada", which sends the reader to the wallet
  // balance rather than to the datum that grew.
  genTx = genTx.payToAddress({
    address: EvoAddress.fromBech32(paramsAddr),
    assets: outputAssets(
      minUtxoAtLeast(2_000_000n, {
        address: paramsAddr,
        assets: outputAssets(0n, new Map([[paramsNftUnit, 1n]])),
        datum: paramsDatum,
        coinsPerUtxoByte,
      }),
      new Map([[paramsNftUnit, 1n]]),
    ),
    datum: new InlineDatum.InlineDatum({ data: paramsDatum }),
  });
  genTx = genTx.payToAddress({
    address: EvoAddress.fromBech32(registryAddr),
    assets: outputAssets(REGISTRY_NODE_MIN_ADA, new Map([[registryNftUnit, 1n]])),
    datum: new InlineDatum.InlineDatum({ data: registryDatum }),
  });
  genTx = genTx.payToAddress({
    address: EvoAddress.fromBech32(issuanceAddr),
    assets: outputAssets(
      minUtxoAtLeast(ISSUANCE_OUTPUT_LOVELACE, {
        address: issuanceAddr,
        assets: outputAssets(0n, new Map([[issuanceNftUnit, 1n]])),
        datum: issuanceDatum,
        coinsPerUtxoByte,
      }),
      new Map([[issuanceNftUnit, 1n]]),
    ),
    datum: new InlineDatum.InlineDatum({ data: issuanceDatum }),
  });

  genTx = genTx.attachScript({ script: buildEvoScript(core.registry.compiledCode) });
  genTx = genTx.attachScript({ script: buildEvoScript(core.protocolParams.compiledCode) });
  genTx = genTx.attachScript({ script: buildEvoScript(core.issuanceCborHexMint.compiledCode) });

  const genBuilt: BuiltTx = await genTx.build(
    buildOpts(ownSpendable(msChain.available, changeAddress, allSeedRefs)),
  );
  const genChain = genBuilt.chainResult();
  steps.push({ label: "protocol genesis", unsignedCbor: await cborOf(genBuilt, "the genesis transaction") });

  // ⛔ LOCATED, NOT ASSUMED. This index goes into `protocolParams.utxo`, the field every
  // operation resolves the protocol through, so it is found by looking for the output that
  // actually carries the params NFT at the params address rather than by trusting that
  // `payToAddress` order maps to output order. It currently does — Evolution appends change
  // without sorting — but "currently does" is not a property to key a deployment on.
  const paramsOutputs = genChain.available.filter(
    (u) =>
      txHashHexOf(u) === genChain.txHash &&
      addressBech32Of(u) === paramsAddr &&
      EvoAssets.getUnits(u.assets as never).some((unit: string) => unit === paramsNftUnit),
  );
  if (paramsOutputs.length !== 1) {
    throw new Error(
      `The genesis transaction creates ${paramsOutputs.length} outputs carrying the ` +
        `protocol-params NFT at ${paramsAddr}; expected exactly 1.`,
    );
  }
  const PARAMS_OUTPUT_INDEX = Number(paramsOutputs[0].index);

  // ---- Tx 3: publish the seven reference scripts --------------------------
  let refTx = client.newTx();
  const byName: Record<RefScriptName, { compiledCode: string }> = {
    programmableLogicBase: core.programmableLogicBase,
    programmableLogicGlobal: core.programmableLogicGlobal,
    transfer: core.transfer,
    thirdParty: core.thirdParty,
    unfracking: core.unfracking,
    issuanceLogic: core.issuanceLogic,
    upgradeMultisig: core.upgradeMultisig,
  };
  for (const name of REF_SCRIPT_ORDER) {
    refTx = refTx.payToAddress({
      address: changeAddressObj,
      assets: outputAssets(REF_SCRIPT_LOVELACE),
      script: buildEvoScript(byName[name].compiledCode),
    });
  }
  const refBuilt: BuiltTx = await refTx.build(
    buildOpts(ownSpendable(genChain.available, changeAddress)),
  );
  const refChain = refBuilt.chainResult();
  steps.push({ label: "reference scripts", unsignedCbor: await cborOf(refBuilt, "the reference-script transaction") });

  // ---- Tx 4: the nominee stake key, REGISTERED **AND** DELEGATED ----------
  //
  // THE POSTCONDITION IS BOTH, and it is a fact about the domain rather than about the
  // certificate. Under alpha.4 this key is not the upgrade authority — the multisig is — but a
  // handover nominee promotes itself by presenting its OWN withdraw-0, and Conway rejects a
  // withdrawal from an UNDELEGATED credential with code 3150 ("credentials that do not engage
  // in on-chain governance") EVEN AT ZERO. Registration alone was never enough.
  //
  // So on the already-registered path the delegation is still submitted: re-delegating an
  // already-delegated credential is idempotent and costs one transaction, while not delegating
  // an undelegated one costs the handover.
  const nomineeKeyHash = stakingCredentialHash(changeAddress);
  const nomineeRewardAddress = rewardAddressFromKeyHash(networkId, nomineeKeyHash);
  const nomineeAlreadyRegistered = await input.isStakeRegistered(nomineeRewardAddress);

  const nomineeCredential = Credential.makeKeyHash(Bytes.fromHex(nomineeKeyHash));
  // AlwaysAbstain is the neutral choice: it engages with governance without casting an opinion.
  const drep = new DRep.AlwaysAbstainDRep({});
  let nomineeTx = client.newTx();
  nomineeTx = nomineeAlreadyRegistered
    ? nomineeTx.delegateToDRep({ stakeCredential: nomineeCredential, drep })
    : nomineeTx.registerAndDelegateTo({ stakeCredential: nomineeCredential, drep });

  const nomineeBuilt: BuiltTx = await retryBuild("Building the nominee transaction", () =>
    nomineeTx.build(buildOpts(ownSpendable(refChain.available, changeAddress))),
  );
  const nomineeChain = nomineeBuilt.chainResult();
  steps.push({
    label: nomineeAlreadyRegistered ? "delegate nominee key" : "register nominee key",
    unsignedCbor: await cborOf(nomineeBuilt, "the nominee transaction"),
  });

  // ---- Tx 5: register the six script stake credentials --------------------
  //
  // SIX, and every one of them is a withdraw-0 validator: the three delegates, the dispatcher
  // that routes to them, `issuance_logic` (which rides every mint and burn) and
  // `upgrade_multisig` (every upgrade authorisation). An unregistered one fails the same
  // undiagnosable way — code 3141, "rewards withdrawals must consume rewards in full", which
  // reads as a balance problem and is really a missing certificate.
  //
  // `registerStake` + `attachScript` + `voidData()`, NEVER `registerAndDelegateTo`: a combined
  // certificate is a Conway `vote_reg_deleg_cert`, which arrives at the publish handler as a
  // DIFFERENT `Certificate` constructor, and these handlers admit `RegisterCredential` and
  // nothing else. The DRep delegation the nominee key needs is not missing here by oversight —
  // a script credential cannot have one.
  let regTx = client.newTx();
  for (const delegate of [
    core.programmableLogicGlobal,
    core.transfer,
    core.thirdParty,
    core.unfracking,
    core.issuanceLogic,
    core.upgradeMultisig,
  ]) {
    regTx = regTx.registerStake({
      stakeCredential: Credential.makeScriptHash(Bytes.fromHex(delegate.hash)),
      redeemer: voidData(),
    });
    regTx = regTx.attachScript({ script: buildEvoScript(delegate.compiledCode) });
  }
  const regBuilt: BuiltTx = await retryBuild("Building the registration transaction", () =>
    regTx.build(buildOpts(ownSpendable(nomineeChain.available, changeAddress))),
  );
  const regChain = regBuilt.chainResult();
  steps.push({ label: "register credentials", unsignedCbor: await cborOf(regBuilt, "the registration transaction") });

  // ---- What it costs ------------------------------------------------------
  //
  // Measured, not estimated: the wallet's own lovelace before, minus what it still holds after
  // the last transaction in the chain. That covers outputs, deposits AND fees without needing
  // to know any of them — and it is the number an operator has to be shown before signing.
  const remaining = ownSpendable(regChain.available, changeAddress).reduce(
    (s, u) => s + lovelaceOf(u.assets),
    0n,
  );
  const totalCostLovelace = walletBalanceLovelace - remaining;

  const deployment: DeploymentParams = {
    txHash: genChain.txHash,
    protocolParams: {
      txInput: seeds.paramsSeed,
      // One value: the minting policy id AND the address payment credential.
      policyId: core.protocolParams.hash,
      utxo: { txHash: genChain.txHash, outputIndex: PARAMS_OUTPUT_INDEX },
    },
    programmableLogicBase: { scriptHash: core.programmableLogicBase.hash },
    transfer: { scriptHash: core.transfer.hash },
    thirdParty: { scriptHash: core.thirdParty.hash },
    unfracking: { scriptHash: core.unfracking.hash },
    programmableLogicGlobal: {
      scriptHash: core.programmableLogicGlobal.hash,
      // ⛔ THE VALUE THE DISPATCHER WAS COMPILED AGAINST, which is NOT always the unfracking hash
      // recorded below. When unfracking is disabled this is the sentinel, while `unfracking`
      // still carries the real deployed script — both are true and neither implies the other.
      // Re-deriving the dispatcher from the real hash in that case yields a hash that is not on
      // chain, which is why this is written down rather than inferred.
      unfrackingParameter: core.unfrackingParameter,
    },
    maxInlineDatumBytes,
    issuanceLogic: { scriptHash: core.issuanceLogic.hash },
    upgradeMultisig: {
      scriptHash: core.upgradeMultisig.hash,
      // multisigSeed, and NOT paramsSeed. Same type as protocolParams.txInput and not
      // interchangeable with it — one value in both slots makes the derivation check vacuous.
      txInput: seeds.multisigSeed,
      // MUTABLE STATE: a signer rotation spends and recreates this UTxO, so the record goes
      // stale. Located structurally in the built transaction, not assumed from an output index.
      utxo: multisigUtxoRef,
    },
    // THE RECORD MUST SAY WHAT THE DATUM SAYS. The genesis datum above writes
    // `upgradeCred: Script(upgradeMultisig)`. Nothing derives one from the other and nothing
    // checks them against each other on chain, which is exactly why they are written together.
    upgradeAuthority: { type: "script", hash: core.upgradeMultisig.hash },
    issuance: {
      txInput: seeds.issuanceSeed,
      policyId: core.issuanceCborHexMint.hash,
      alwaysFailScriptHash: core.alwaysFailHash,
    },
    registry: {
      txInput: seeds.paramsSeed,
      issuanceScriptHash: core.issuanceCborHexMint.hash,
      scriptHash: core.registry.hash,
    },
    programmableBaseRefInput: { txHash: refChain.txHash, outputIndex: refIdx("programmableLogicBase") },
    programmableLogicGlobalRefInput: { txHash: refChain.txHash, outputIndex: refIdx("programmableLogicGlobal") },
    transferRefInput: { txHash: refChain.txHash, outputIndex: refIdx("transfer") },
    thirdPartyRefInput: { txHash: refChain.txHash, outputIndex: refIdx("thirdParty") },
    unfrackingRefInput: { txHash: refChain.txHash, outputIndex: refIdx("unfracking") },
    issuanceLogicRefInput: { txHash: refChain.txHash, outputIndex: refIdx("issuanceLogic") },
    upgradeMultisigRefInput: { txHash: refChain.txHash, outputIndex: refIdx("upgradeMultisig") },
  } as DeploymentParams;

  // Numbered here rather than at each push: the plan is FIVE transactions when the operator
  // supplies seeds and SIX when it has to make them, so a hardcoded "n/6" would be wrong in
  // exactly the case that is now the common one.
  const numbered = steps.map((s, i) => ({ ...s, label: `${i + 1}/${steps.length} ${s.label}` }));

  return {
    steps: numbered,
    deployment,
    seeds,
    totalCostLovelace,
    walletBalanceLovelace,
    nomineeAlreadyRegistered,
  };
}
