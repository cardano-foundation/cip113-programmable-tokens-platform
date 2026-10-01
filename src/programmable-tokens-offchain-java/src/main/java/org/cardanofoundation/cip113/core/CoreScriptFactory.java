package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.exception.CborSerializationException;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;
import org.cardanofoundation.cip113.service.RwaCip171ProvenanceService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies compile-time parameters to the CIP-113 core validators.
 *
 * <p>Parameter application is the most brittle part of consuming these contracts and the
 * least visible when it goes wrong. {@code AikenScriptUtil.applyParamToScript} performs no
 * arity check and no type check: pass four arguments where the validator wants three, or a
 * bare {@code ByteArray} where it wants a {@code Credential}, and you still get a
 * perfectly valid script — a <em>different</em> one, with a different hash and a different
 * policy id. Nothing fails until much later, as a registry lookup that finds no match or a
 * transaction the chain rejects for reasons that point nowhere near the real cause.
 *
 * <p>Upstream's next revision contains exactly that trap: {@code issuance_mint}'s fourth
 * parameter changes from {@code plg_stake_cred: Credential} to
 * {@code params_policy: PolicyId} — same arity, same position, {@code Constr1[bytes]}
 * becomes bare bytes. Applied the old way it would silently mint under the wrong policy.
 * {@code CoreBlueprintSurfaceTest} asserts the declared parameter types precisely so that
 * change is caught here rather than on chain.
 *
 * <p>So every parameter list lives in {@link #parametersFor} — one switch, one place — and
 * the wrapping conventions are named ({@link #scriptCredential}, {@link #policyId},
 * {@link #outputReference}) rather than being re-derived at each site from a
 * {@code ConstrPlutusData.of(1, ...)} whose meaning is not otherwise apparent.
 *
 * <p>Scripts are cached per deployment: a parameterised script depends only on the
 * blueprint and the full bootstrap record. Records with the same txHash but different
 * parameters must not share a cached script.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoreScriptFactory {

    private final CoreBlueprint blueprint;

    /** Full deployment record -> validator -> parameterised script. */
    private final Map<ProtocolBootstrapParams, Map<CoreValidator, PlutusScript>> cache = new ConcurrentHashMap<>();

    /**
     * The parameterised script for a core validator under a given deployment.
     *
     * @throws IllegalArgumentException for {@link CoreValidator#ISSUANCE_MINT}, whose
     *         parameters depend on a module's minting-logic credential and therefore
     *         cannot be derived from the bootstrap record alone — use
     *         {@link #issuanceMint}. Rejecting it here rather than returning a plausible
     *         wrong script is the whole point of this class.
     */
    public PlutusScript script(CoreValidator validator, ProtocolBootstrapParams bootstrap) {
        if (validator == CoreValidator.ISSUANCE_MINT) {
            throw new IllegalArgumentException(
                    "issuance_mint is parameterised by the module's minting-logic credential; "
                            + "call issuanceMint(bootstrap, mintingLogicScript) instead.");
        }
        if (validator == CoreValidator.ALWAYS_FAIL) {
            throw new IllegalArgumentException(
                    "always_fail is parameterised by a caller-chosen nonce, not by the bootstrap "
                            + "record; call alwaysFail(nonce) instead.");
        }
        return cache
                .computeIfAbsent(bootstrap, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(validator, v -> {
                    var script = apply(v, parametersFor(v, bootstrap));
                    if (v == CoreValidator.PROGRAMMABLE_LOGIC_GLOBAL) {
                        final String derived;
                        try {
                            derived = script.getPolicyId();
                        } catch (CborSerializationException e) {
                            throw new IllegalStateException("Could not hash the programmableLogicGlobal script", e);
                        }
                        if (!derived.equalsIgnoreCase(bootstrap.programmableLogicGlobal().scriptHash())) {
                            throw new IllegalStateException("Deployment " + bootstrap.txHash()
                                    + " records programmableLogicGlobal.scriptHash="
                                    + bootstrap.programmableLogicGlobal().scriptHash()
                                    + " but the recorded unfrackingParameter derives " + derived);
                        }
                    }
                    return script;
                });
    }

    /**
     * {@code issuance_mint} for a specific module.
     *
     * <p>Not cached: the module's minting-logic script varies per registered token,
     * so the cache key would be the pair, and the win over recomputing is not worth the
     * retention.
     */
    public PlutusScript issuanceMint(ProtocolBootstrapParams bootstrap, PlutusScript mintingLogicScript) {
        byte[] mintingLogicHash;
        try {
            mintingLogicHash = mintingLogicScript.getScriptHash();
        } catch (CborSerializationException e) {
            // The module's minting-logic script came from a blueprint we just parsed, so
            // failing to hash it means that blueprint is malformed, not that this call is wrong.
            throw new IllegalStateException("could not hash the module minting-logic script", e);
        }
        // alpha.4 moved the programmable-logic and registry checks into issuance_logic.
        // issuance_mint now dispatches to the datum-selected issuance credential and needs
        // only the per-token minting logic plus the protocol-params policy.
        var params = ListPlutusData.of(
                scriptCredential(mintingLogicHash),
                policyId(bootstrap.protocolParams().policyId()));
        var script = apply(CoreValidator.ISSUANCE_MINT, params);
        RwaCip171ProvenanceService.recordIssuance(blueprint.compiledCode(CoreValidator.ISSUANCE_MINT), params, script);
        return script;
    }

    /** {@code always_fail} under a caller-chosen nonce, which is its only parameter. */
    public PlutusScript alwaysFail(String nonce) {
        return apply(CoreValidator.ALWAYS_FAIL, ListPlutusData.of(BytesPlutusData.of(HexUtil.decodeHexString(nonce))));
    }

    /**
     * Every core validator's parameter list, in declaration order.
     *
     * <p>Read this against {@code src/main/resources/plutus.json}'s
     * {@code validators[].parameters} — the two must agree in arity, order AND wrapping.
     */
    private ListPlutusData parametersFor(CoreValidator validator, ProtocolBootstrapParams b) {
        return switch (validator) {

            // params_policy: PolicyId — a BARE policy id, deliberately not a Credential.
            // PLB is anchored to the protocol-params NFT rather than to a delegate's hash,
            // which is what makes delegates swappable without moving PLB's hash (and with it
            // every programmable token address).
            case PROGRAMMABLE_LOGIC_BASE -> ListPlutusData.of(
                    policyId(b.protocolParams().policyId()));

            // params_policy: PolicyId. Same anchor as PLB, for all three delegates.
            //
            // The deployment checks that the three independently compiled delegates end up
            // with different hashes; otherwise two dispatcher arms would collapse.
            case TRANSFER, THIRD_PARTY, UNFRACKING -> ListPlutusData.of(
                    scriptCredential(b.programmableLogicBase().scriptHash()),
                    policyId(b.registry().scriptHash()),
                    BigIntPlutusData.of(b.maxInlineDatumBytes()));

            // utxo_ref: OutputReference. The merged validator is both the one-shot policy and
            // the spend credential holding the live six-field protocol-params datum.
            case PROTOCOL_PARAMS -> ListPlutusData.of(
                    outputReference(b.protocolParams().txInput()));

            // utxo_ref: OutputReference, always_fail_hash: ByteArray.
            case ISSUANCE_CBOR_HEX_MINT -> ListPlutusData.of(
                    outputReference(b.issuance().txInput()),
                    BytesPlutusData.of(HexUtil.decodeHexString(b.issuance().alwaysFailScriptHash())));

            // utxo_ref: OutputReference, issuance_cbor_hex_cs: PolicyId, registry_spend_cred: Credential.
            //
            // NAMING TRAP: the bootstrap record calls the second value `issuanceScriptHash`,
            // which reads as the issuance_mint policy. It is not — it is the
            // issuance_cbor_hex_mint policy, the one-shot NFT whose datum carries the
            // issuance_mint template bytes. The bootstrap JSON sets it equal to
            // issuanceParams.scriptHash, and ProtocolScriptBuilderServiceHashDerivationTest
            // pins that value as issuance_cbor_hex_mint's own policy id, which is what makes
            // this reading provable rather than inferred.
            case REGISTRY -> ListPlutusData.of(
                    outputReference(b.registry().txInput()),
                    policyId(b.registry().issuanceScriptHash()));

            // ⛔ THE THIRD PARAMETER IS `unfrackingParameter`, NOT the unfracking script hash.
            //
            // The dispatcher records WHAT IT WAS COMPILED AGAINST: either the real unfracking hash,
            // or the 28-byte disabled sentinel (0000…0000). A deployment may legitimately deploy,
            // publish and record the real unfracking script while compiling the dispatcher against
            // the sentinel — which is exactly what preprod does — so `unfracking.scriptHash` is NOT
            // a substitute. `DispatcherParams`' own javadoc says so; this case used the substitute
            // anyway, which is the whole bug.
            //
            // ⚠ MEASURED ON PREPROD 2026-10-01, and it cost a long hunt. unfrackingParameter there
            // is the sentinel, so:
            //     dispatcher(transfer, thirdParty, unfracking.scriptHash) = a36376d3…   ← what we built
            //     dispatcher(transfer, thirdParty, unfrackingParameter)   = 24ef08d8…   ← the record,
            //                                                                             and what the
            //                                                                             bootstrap
            //                                                                             REGISTERED
            // A transfer withdraws-0 from the dispatcher, so it targeted an unregistered reward
            // account and the ledger answered 3141 — a message about rewards balances. ISSUING kept
            // working the whole time because the mint withdrawal set is (issuer_admin, issuanceLogic)
            // and contains no dispatcher. The TypeScript SDK was unaffected because it takes the
            // dispatcher hash straight from the deployment record instead of re-deriving it.
            //
            // ⚑ AND IT IS INVISIBLE ON DEVNET, which is why a 37-row devnet matrix passed over it:
            // devnet bootstraps with unfracking ENABLED, so unfrackingParameter EQUALS the unfracking
            // script hash there and the two spellings coincide. Only a deployment with unfracking
            // disabled can tell them apart.
            case PROGRAMMABLE_LOGIC_GLOBAL -> ListPlutusData.of(
                    policyId(b.transfer().scriptHash()),
                    policyId(b.thirdParty().scriptHash()),
                    policyId(requireUnfrackingParameter(b)));

            case ISSUANCE_LOGIC -> ListPlutusData.of(
                    scriptCredential(b.programmableLogicBase().scriptHash()),
                    policyId(b.registry().scriptHash()),
                    policyId(b.protocolParams().policyId()),
                    BigIntPlutusData.of(b.maxInlineDatumBytes()));

            case UPGRADE_MULTISIG -> ListPlutusData.of(
                    outputReference(b.upgradeMultisig().txInput()));

            case ISSUANCE_MINT, ALWAYS_FAIL -> throw new IllegalStateException(
                    "handled by dedicated methods: " + validator);
        };
    }


    private PlutusScript apply(CoreValidator validator, ListPlutusData parameters) {
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(parameters, blueprint.compiledCode(validator)),
                PlutusVersion.v3);
    }

    // ── parameter wrapping conventions ───────────────────────────────────────
    //
    // Named rather than inlined because the difference between them is invisible at a call
    // site and total in effect. A Credential is a tagged sum (constructor 1 = Script,
    // constructor 0 = VerificationKey); a PolicyId is the bare 28 bytes. Both are "a hash"
    // to a reader skimming the code, and swapping one for the other yields a working script
    // that is not the intended one.

    /** {@code Credential::Script(hash)} — constructor 1 of aiken's {@code Credential}. */
    private static PlutusData scriptCredential(String hexHash) {
        return ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(hexHash)));
    }

    /** {@code Credential::Script(hash)} from raw bytes. */
    private static PlutusData scriptCredential(byte[] hash) {
        return ConstrPlutusData.of(1, BytesPlutusData.of(hash));
    }

    /** A bare {@code PolicyId} / {@code ByteArray} — no constructor wrapping. */
    private static PlutusData policyId(String hexPolicyId) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hexPolicyId));
    }

    /** {@code OutputReference { transaction_id, output_index }} — constructor 0. */
    private static PlutusData outputReference(TxInput input) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(input.txHash())),
                BigIntPlutusData.of(input.outputIndex()));
    }

    /**
     * The dispatcher's third parameter, refusing rather than guessing when it is absent.
     *
     * <p>⛔ NO DEFAULT IS SAFE HERE. "Absent" could mean the deployment disabled unfracking (the
     * sentinel) or that the field was LOST in parsing — {@code DispatcherParams} exists because it
     * was being dropped silently by a mapper with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled.
     * Defaulting to the sentinel would be right often enough that nobody would ever check, and wrong
     * on exactly the deployments that enable unfracking. Falling back to
     * {@code unfracking.scriptHash} is what produced a 3141 on preprod.
     */
    private static String requireUnfrackingParameter(ProtocolBootstrapParams b) {
        var dispatcher = b.programmableLogicGlobal();
        var param = dispatcher == null ? null : dispatcher.unfrackingParameter();
        // Shape, not merely presence (from fix/cmta-preprod): a malformed value would otherwise
        // reach script parameterisation and fail there with no mention of the file it came from.
        if (param == null || !param.matches("(?i)[0-9a-f]{56}")) {
            throw new IllegalStateException(
                    "programmableLogicGlobal.unfrackingParameter is missing from the deployment "
                    + "record for protocol " + b.txHash() + ". The dispatcher cannot be compiled "
                    + "without it and it must not be guessed: the sentinel ("
                    + "0000…0000, unfracking disabled) and the real unfracking script hash produce "
                    + "DIFFERENT dispatcher hashes, and only one of them is the credential the "
                    + "bootstrap registered. Withdrawing from the wrong one is rejected as ledger "
                    + "code 3141. Add the field to protocol-bootstraps-<network>.json.");
        }
        return param;
    }
}
