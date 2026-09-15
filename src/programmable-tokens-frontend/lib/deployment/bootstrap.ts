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
 * `additionalUtxos` — against outputs that do not exist on chain yet. Every step is therefore
 * built and evaluated with real execution units before the wallet is asked for a signature.
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
  UPLC,
} from "@evolution-sdk/evolution";
import {
  buildEvoScript,
  minUtxoAtLeast,
  mintAssetsFromMap,
  outputAssets,
  protocolParamsDatum,
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
interface ChainUtxo {
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
  toCBOR?: () => string;
  toCBORHex?: () => string;
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
  const id = u.transactionId as { toString?: () => string } | string;
  if (typeof id === "string") return id;
  // Evolution models a transaction id as a branded byte array; its hex form is what every
  // outref in a DeploymentParams is written as.
  return Bytes.toHex(id as never);
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

function cborOf(built: BuiltTx, label: string): string {
  const hex = built.toCBORHex?.() ?? built.toCBOR?.();
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

  const changeAddressObj = EvoAddress.fromBech32(changeAddress);
  const buildOpts = (available: ChainUtxo[]) => ({
    changeAddress: changeAddressObj,
    evaluator: input.evaluator,
    availableUtxos: available,
  });

  const walletUtxos = await client.getUtxos(changeAddressObj);
  const walletBalanceLovelace = walletUtxos.reduce((s, u) => s + lovelaceOf(u.assets), 0n);
  const { coinsPerUtxoByte } = await client.getProtocolParameters();

  const steps: MultiTxStep[] = [];

  // ---- Tx 0: fragment into three distinct seed UTxOs ----------------------
  //
  // THREE, and they must be distinct. `protocolParams.txInput` and `upgradeMultisig.txInput`
  // are the same type and are NOT interchangeable: a deployment that used one UTxO for both
  // would deploy perfectly well and make `assertDeploymentScripts` vacuous, because the
  // upgrade-multisig check would pass whichever of the two fields the verifier read.
  let fragTx = client.newTx();
  for (let i = 0; i < 3; i++) {
    fragTx = fragTx.payToAddress({
      address: changeAddressObj,
      assets: outputAssets(SEED_LOVELACE),
    });
  }
  const fragBuilt: BuiltTx = await fragTx.build(buildOpts(ownSpendable(walletUtxos, changeAddress)));
  const fragChain = fragBuilt.chainResult();
  steps.push({ label: "1/6 seed UTxOs", unsignedCbor: cborOf(fragBuilt, "the seed transaction") });

  const created = fragChain.available
    .filter((u) => txHashHexOf(u) === fragChain.txHash)
    .sort((a, b) => Number(a.index) - Number(b.index));
  const seedUtxos = created.filter((u) => Number(u.index) < 3);
  if (seedUtxos.length !== 3) {
    throw new Error(
      `The seed transaction produced ${seedUtxos.length} of the 3 expected seed outputs. ` +
        `Every one-shot policy in the deployment is parameterised by one of them, so there is ` +
        `nothing to derive from.`,
    );
  }
  // Positional, so assert the position means what the derivation assumes. A change output
  // landing at index 0 would silently reassign every one-shot policy.
  seedUtxos.forEach((u, i) => {
    if (lovelaceOf(u.assets) !== SEED_LOVELACE) {
      throw new Error(
        `Seed output #${i} holds ${lovelaceOf(u.assets)} lovelace, expected exactly ` +
          `${SEED_LOVELACE}. The seed outputs are identified by position, so this is not the ` +
          `output the derivation would be parameterised by.`,
      );
    }
  });

  const seeds: DeploymentSeeds = {
    paramsSeed: outRef(seedUtxos[0]),
    issuanceSeed: outRef(seedUtxos[1]),
    multisigSeed: outRef(seedUtxos[2]),
  };
  const allSeedRefs = [seeds.paramsSeed, seeds.issuanceSeed, seeds.multisigSeed];

  // ---- Derive every core script from those seeds --------------------------
  const core = buildCoreScriptSet({
    blueprint,
    seeds,
    alwaysFailNonce,
    maxInlineDatumBytes,
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
  msTx = msTx.attachScript({ script: buildEvoScript(core.upgradeMultisig.compiledCode) });

  const msBuilt: BuiltTx = await msTx.build(
    buildOpts(ownSpendable(fragChain.available, changeAddress, allSeedRefs)),
  );
  const msChain = msBuilt.chainResult();
  steps.push({ label: "2/6 upgrade multisig", unsignedCbor: cborOf(msBuilt, "the multisig transaction") });

  // ---- The authority must be OPERABLE, not merely named --------------------
  //
  // The harness reads this UTxO back OFF THE CHAIN before writing the genesis datum that names
  // it. A pre-flight build cannot: the transaction has not been submitted. So the same question
  // is asked of the built transaction's own outputs — found STRUCTURALLY, by policy, the way
  // the validator finds it, rather than by matching the unit string we just constructed. A
  // lookup keyed on our own construction shares a blind spot with the code that constructed it.
  //
  // This is weaker than the harness's check by exactly one thing: it cannot detect a chain
  // that disagrees with the transaction we built. It is not weaker about the failure that
  // matters here — a config UTxO that is not where the validator will look for it.
  const multisigCandidates = msChain.available.filter(
    (u) =>
      txHashHexOf(u) === msChain.txHash &&
      addressBech32Of(u) === multisigAddr &&
      EvoAssets.getUnits(u.assets as never).some(
        (unit: string) => unit !== "lovelace" && unit.slice(0, 56) === core.upgradeMultisig.hash,
      ),
  );
  if (multisigCandidates.length !== 1) {
    throw new Error(
      `The multisig transaction creates ${multisigCandidates.length} outputs at ` +
        `${multisigAddr} carrying an asset of policy ${core.upgradeMultisig.hash}; expected ` +
        `exactly 1. The NFT is one-shot, so zero means the config output is not where the ` +
        `validator locks it. Refusing to build a genesis that names an authority whose config ` +
        `UTxO is not exactly one well-formed output — an unsatisfiable authority is a ` +
        `permanent brick with no repair path.`,
    );
  }
  const multisigUtxoRef = outRef(multisigCandidates[0]);

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
  steps.push({ label: "3/6 protocol genesis", unsignedCbor: cborOf(genBuilt, "the genesis transaction") });

  // The params UTxO is the FIRST output, and the record is keyed by that position.
  const PARAMS_OUTPUT_INDEX = 0;

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
  steps.push({ label: "4/6 reference scripts", unsignedCbor: cborOf(refBuilt, "the reference-script transaction") });

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

  const nomineeBuilt: BuiltTx = await nomineeTx.build(
    buildOpts(ownSpendable(refChain.available, changeAddress)),
  );
  const nomineeChain = nomineeBuilt.chainResult();
  steps.push({
    label: nomineeAlreadyRegistered ? "5/6 delegate nominee key" : "5/6 register nominee key",
    unsignedCbor: cborOf(nomineeBuilt, "the nominee transaction"),
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
  const regBuilt: BuiltTx = await regTx.build(
    buildOpts(ownSpendable(nomineeChain.available, changeAddress)),
  );
  const regChain = regBuilt.chainResult();
  steps.push({ label: "6/6 register credentials", unsignedCbor: cborOf(regBuilt, "the registration transaction") });

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
    programmableLogicGlobal: { scriptHash: core.programmableLogicGlobal.hash },
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

  return {
    steps,
    deployment,
    seeds,
    totalCostLovelace,
    walletBalanceLovelace,
    nomineeAlreadyRegistered,
  };
}
