/**
 * Forward derivation of a NEW CIP-113 core deployment.
 *
 * The SDK's `buildDeploymentScripts` derives scripts from an EXISTING
 * `DeploymentParams` — it is the verification direction, and it asserts against hashes that
 * are already known. Bootstrapping is the other direction: nothing is known yet, and every
 * hash follows from a handful of operator choices. That is what this does.
 *
 * ## The inputs a deployer actually chooses
 *
 * Three one-shot seeds, not one. The live preview deployment consumes three outputs of a
 * single funding transaction — `#0` for protocol-params AND registry, `#1` for issuance,
 * `#2` for the upgrade multisig — so "pick a UTxO" is wrong and would produce a deployment
 * whose registry and issuance collide.
 *
 * ## Order is forced by the dependency graph
 *
 *   always_fail(nonce)                            -> alwaysFailHash
 *   issuance_cbor_hex_mint(issuanceSeed, ^)       -> issuanceCborHexPolicy
 *   registry(paramsSeed, ^)                       -> registryPolicy
 *   protocol_params(paramsSeed)                   -> paramsPolicy
 *   programmable_logic_base(paramsPolicy)         -> plb
 *   transfer / third_party / unfracking(plb, registryPolicy, maxInline)
 *   issuance_logic(plb, registryPolicy, paramsPolicy, maxInline)
 *   programmable_logic_global(transfer, thirdParty, unfracking)   -- LAST
 *   upgrade_multisig(multisigSeed)                -- independent
 *
 * ⛔ `issuanceLogic` takes `registryPolicy` then `paramsPolicy` — two adjacent PolicyIds, same
 * type, same length, both `string`. The SDK's own header warns that swapping them yields a
 * script that builds, hashes and deploys, and nothing before the ledger will say so. They are
 * passed here from named fields for exactly that reason; do not inline them.
 *
 * Every parameterisation is recorded, because that record IS the CIP-171 payload.
 */
import { createStandardScripts, UNFRACKING_DISABLED } from "@easy1staking/cip113-sdk-ts";
import type { PlutusBlueprint, PlutusScript, TxInput, StandardScripts } from "@easy1staking/cip113-sdk-ts";

export interface DeploymentSeeds {
  /** Consumed by protocol_params AND registry. */
  paramsSeed: TxInput;
  /** Consumed by issuance_cbor_hex_mint. */
  issuanceSeed: TxInput;
  /** Consumed by upgrade_multisig. */
  multisigSeed: TxInput;
}

export interface DeriveCoreDeploymentInput {
  blueprint: PlutusBlueprint;
  seeds: DeploymentSeeds;
  /**
   * `always_fail` is parameterised on an operator-chosen nonce. Supply the NONCE for a new
   * deployment; supply `alwaysFailHash` instead only when reproducing an existing one whose
   * nonce was never recorded (the bootstrap record stores the hash, not the nonce).
   */
  alwaysFailNonce?: string;
  alwaysFailHash?: string;
  /** Baked into transfer, third_party, unfracking and issuance_logic. Live preview uses 1024. */
  maxInlineDatumBytes: number;
  /**
   * Whether the dispatcher is compiled to permit unfracking at all. Default: yes.
   *
   * ⛔ THIS IS A DEPLOYMENT CHOICE THAT CANNOT BE CHANGED WITHOUT REPLACING THE DISPATCHER.
   * `programmable_logic_global` takes the unfracking hash as a COMPILE-TIME parameter, so
   * disabling it means compiling the dispatcher against {@link UNFRACKING_DISABLED} — a 28-byte
   * sentinel no script can hash to — and the dispatcher's own hash moves accordingly.
   *
   * The unfracking VALIDATOR is still built, deployed, registered and published either way. Only
   * the value the dispatcher was compiled against differs, which is why the deployment has to
   * record both: see {@link CoreScriptSet.unfrackingParameter}.
   *
   * Reversible later, at a price: recompile the dispatcher with the real hash, publish it as a
   * reference script, and `PROTOCOL_UPGRADE` the params datum's `plg_cred` to point at it. No new
   * unfracking deployment, no registry node touched, no token reissued.
   */
  unfrackingEnabled?: boolean;
}

/** One applied parameterisation, in the shape CIP-171 publishes. */
export interface ParameterizationRecord {
  title: string;
  rawScriptHash: string;
  appliedScriptHash: string;
  params: unknown[];
}

export interface DerivedCoreDeployment {
  alwaysFailHash: string;
  issuanceCborHexPolicy: string;
  registryPolicy: string;
  paramsPolicy: string;
  programmableLogicBase: string;
  transfer: string;
  thirdParty: string;
  unfracking: string;
  issuanceLogic: string;
  programmableLogicGlobal: string;
  upgradeMultisig: string;
  /** The unfracking hash the dispatcher was compiled against — real hash or sentinel. */
  unfrackingParameter: string;
  /** Feeds the CIP-171 record; empty means provenance cannot be published. */
  parameterizations: ParameterizationRecord[];
}

const hash = (s: { hash: string }) => s.hash;

/**
 * Every core script of a deployment, with bodies — not just hashes.
 *
 * Deriving hashes and building the bootstrap transactions are the same derivation asked for
 * two different projections of one answer, so they run through ONE function. Two functions
 * that each walked the dependency graph could disagree about the order of `issuanceLogic`'s
 * two adjacent PolicyIds, and the deployment would then be internally consistent with
 * whichever one the page happened to display.
 *
 * `alwaysFail` is a hash only: the issuance NFT is paid TO its address and never spent from
 * it, so nothing ever needs its body — and a deployment being reproduced from a bootstrap
 * record has only the hash to go on.
 */
export interface CoreScriptSet {
  alwaysFailHash: string;
  issuanceCborHexMint: PlutusScript;
  registry: PlutusScript;
  protocolParams: PlutusScript;
  programmableLogicBase: PlutusScript;
  transfer: PlutusScript;
  thirdParty: PlutusScript;
  unfracking: PlutusScript;
  issuanceLogic: PlutusScript;
  programmableLogicGlobal: PlutusScript;
  upgradeMultisig: PlutusScript;
  /**
   * The unfracking hash `programmable_logic_global` was COMPILED AGAINST — the real
   * `unfracking.hash`, or {@link UNFRACKING_DISABLED}.
   *
   * ⛔ NOT INFERABLE FROM THE DEPLOYMENT, which is the whole reason it is recorded. Both values
   * are legitimately present in a deployment where unfracking is disabled: the real script is
   * deployed, registered and published, so `unfracking.scriptHash` derives and verifies on its
   * own — and re-deriving the dispatcher from THAT value would produce a different dispatcher
   * hash than the one on chain. Which of the two was used is a fact that has to be written down.
   */
  unfrackingParameter: string;
  /**
   * The raw builders, for the one script a CORE deployment does not deploy: `issuance_mint`
   * is parameterised per minting-logic hash, so the instance a bootstrap needs is a dummy
   * whose CBOR is split around a placeholder. See `bootstrap.ts`.
   */
  builders: StandardScripts;
  parameterizations: ParameterizationRecord[];
}

export function buildCoreScriptSet(input: DeriveCoreDeploymentInput): CoreScriptSet {
  const { blueprint, seeds, maxInlineDatumBytes } = input;

  if (!input.alwaysFailNonce && !input.alwaysFailHash) {
    throw new Error(
      "deriveCoreDeployment needs alwaysFailNonce (new deployment) or alwaysFailHash " +
        "(reproducing an existing one). Neither was given, and always_fail's hash is the " +
        "root of issuance_cbor_hex_mint and therefore of the registry policy.",
    );
  }

  const parameterizations: ParameterizationRecord[] = [];
  // Sealed once the CORE set is complete. The CIP-171 record IS this list, and it must
  // describe the scripts this deployment runs. A caller that later parameterises
  // `issuance_mint` off the same builders (the bootstrap does, for the CBOR splice) would
  // otherwise append a script belonging to a substandard registration, not to this
  // deployment — silently, and only visible as an extra entry in a published record.
  let sealed = false;
  const scripts = createStandardScripts(blueprint, (event) => {
    if (sealed) return;
    parameterizations.push({
      title: event.title,
      rawScriptHash: event.rawScriptHash,
      appliedScriptHash: event.appliedScriptHash,
      params: event.params as unknown[],
    });
  });

  // always_fail -> issuance_cbor_hex_mint -> registry
  const alwaysFailHash =
    input.alwaysFailHash ?? hash(scripts.alwaysFail(input.alwaysFailNonce!));
  const issuanceCborHexMint = scripts.issuanceCborHexMint(seeds.issuanceSeed, alwaysFailHash);
  const registry = scripts.registry(seeds.paramsSeed, issuanceCborHexMint.hash);

  // protocol_params -> programmable_logic_base -> the withdraw-0 delegates
  const protocolParams = scripts.protocolParams(seeds.paramsSeed);
  const programmableLogicBase = scripts.programmableLogicBase(protocolParams.hash);

  const transfer = scripts.transfer(programmableLogicBase.hash, registry.hash, maxInlineDatumBytes);
  const thirdParty = scripts.thirdParty(programmableLogicBase.hash, registry.hash, maxInlineDatumBytes);
  const unfracking = scripts.unfracking(programmableLogicBase.hash, registry.hash, maxInlineDatumBytes);

  // Named arguments, deliberately: registryPolicy then paramsPolicy. See the header.
  const issuanceLogic = scripts.issuanceLogic(
    programmableLogicBase.hash,
    registry.hash,
    protocolParams.hash,
    maxInlineDatumBytes,
  );

  // ⛔ The dispatcher is compiled against the PARAMETER, which is the sentinel when unfracking is
  // disabled — never against `unfracking.hash` in that case. Passing the real hash here would
  // produce a dispatcher that does not match the one the deployment records.
  const unfrackingParameter =
    input.unfrackingEnabled === false ? UNFRACKING_DISABLED : unfracking.hash;

  const programmableLogicGlobal = scripts.programmableLogicGlobal(
    transfer.hash,
    thirdParty.hash,
    unfrackingParameter,
  );

  const upgradeMultisig = scripts.upgradeMultisig(seeds.multisigSeed);

  sealed = true;
  return {
    alwaysFailHash,
    issuanceCborHexMint,
    registry,
    protocolParams,
    programmableLogicBase,
    transfer,
    thirdParty,
    unfracking,
    issuanceLogic,
    programmableLogicGlobal,
    upgradeMultisig,
    unfrackingParameter,
    builders: scripts,
    parameterizations,
  };
}

export function deriveCoreDeployment(input: DeriveCoreDeploymentInput): DerivedCoreDeployment {
  const s = buildCoreScriptSet(input);
  return {
    alwaysFailHash: s.alwaysFailHash,
    issuanceCborHexPolicy: s.issuanceCborHexMint.hash,
    registryPolicy: s.registry.hash,
    paramsPolicy: s.protocolParams.hash,
    programmableLogicBase: s.programmableLogicBase.hash,
    transfer: s.transfer.hash,
    thirdParty: s.thirdParty.hash,
    unfracking: s.unfracking.hash,
    issuanceLogic: s.issuanceLogic.hash,
    programmableLogicGlobal: s.programmableLogicGlobal.hash,
    upgradeMultisig: s.upgradeMultisig.hash,
    unfrackingParameter: s.unfrackingParameter,
    parameterizations: s.parameterizations,
  };
}
