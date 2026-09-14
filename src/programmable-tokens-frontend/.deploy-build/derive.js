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
import { createStandardScripts } from "@easy1staking/cip113-sdk-ts";
const hash = (s) => s.hash;
export function deriveCoreDeployment(input) {
    const { blueprint, seeds, maxInlineDatumBytes } = input;
    if (!input.alwaysFailNonce && !input.alwaysFailHash) {
        throw new Error("deriveCoreDeployment needs alwaysFailNonce (new deployment) or alwaysFailHash " +
            "(reproducing an existing one). Neither was given, and always_fail's hash is the " +
            "root of issuance_cbor_hex_mint and therefore of the registry policy.");
    }
    const parameterizations = [];
    const scripts = createStandardScripts(blueprint, (event) => parameterizations.push({
        title: event.title,
        rawScriptHash: event.rawScriptHash,
        appliedScriptHash: event.appliedScriptHash,
        params: event.params,
    }));
    // always_fail -> issuance_cbor_hex_mint -> registry
    const alwaysFailHash = input.alwaysFailHash ?? hash(scripts.alwaysFail(input.alwaysFailNonce));
    const issuanceCborHexPolicy = hash(scripts.issuanceCborHexMint(seeds.issuanceSeed, alwaysFailHash));
    const registryPolicy = hash(scripts.registry(seeds.paramsSeed, issuanceCborHexPolicy));
    // protocol_params -> programmable_logic_base -> the withdraw-0 delegates
    const paramsPolicy = hash(scripts.protocolParams(seeds.paramsSeed));
    const programmableLogicBase = hash(scripts.programmableLogicBase(paramsPolicy));
    const transfer = hash(scripts.transfer(programmableLogicBase, registryPolicy, maxInlineDatumBytes));
    const thirdParty = hash(scripts.thirdParty(programmableLogicBase, registryPolicy, maxInlineDatumBytes));
    const unfracking = hash(scripts.unfracking(programmableLogicBase, registryPolicy, maxInlineDatumBytes));
    // Named arguments, deliberately: registryPolicy then paramsPolicy. See the header.
    const issuanceLogic = hash(scripts.issuanceLogic(programmableLogicBase, registryPolicy, paramsPolicy, maxInlineDatumBytes));
    const programmableLogicGlobal = hash(scripts.programmableLogicGlobal(transfer, thirdParty, unfracking));
    const upgradeMultisig = hash(scripts.upgradeMultisig(seeds.multisigSeed));
    return {
        alwaysFailHash,
        issuanceCborHexPolicy,
        registryPolicy,
        paramsPolicy,
        programmableLogicBase,
        transfer,
        thirdParty,
        unfracking,
        issuanceLogic,
        programmableLogicGlobal,
        upgradeMultisig,
        parameterizations,
    };
}
