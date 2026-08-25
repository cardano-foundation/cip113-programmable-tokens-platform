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
 * blueprint and the bootstrap record, both immutable for a given {@code txHash}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CoreScriptFactory {

    private final CoreBlueprint blueprint;

    /** deployment txHash -> validator -> parameterised script. */
    private final Map<String, Map<CoreValidator, PlutusScript>> cache = new ConcurrentHashMap<>();

    /**
     * The parameterised script for a core validator under a given deployment.
     *
     * @throws IllegalArgumentException for {@link CoreValidator#ISSUANCE_MINT}, whose
     *         parameters depend on a substandard's minting-logic credential and therefore
     *         cannot be derived from the bootstrap record alone — use
     *         {@link #issuanceMint}. Rejecting it here rather than returning a plausible
     *         wrong script is the whole point of this class.
     */
    public PlutusScript script(CoreValidator validator, ProtocolBootstrapParams bootstrap) {
        if (validator == CoreValidator.ISSUANCE_MINT) {
            throw new IllegalArgumentException(
                    "issuance_mint is parameterised by the substandard's minting-logic credential; "
                            + "call issuanceMint(bootstrap, mintingLogicScript) instead.");
        }
        if (validator == CoreValidator.ALWAYS_FAIL) {
            throw new IllegalArgumentException(
                    "always_fail is parameterised by a caller-chosen nonce, not by the bootstrap "
                            + "record; call alwaysFail(nonce) instead.");
        }
        return cache
                .computeIfAbsent(bootstrap.txHash(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(validator, v -> apply(v, parametersFor(v, bootstrap)));
    }

    /**
     * {@code issuance_mint} for a specific substandard.
     *
     * <p>Not cached: the substandard's minting-logic script varies per registered token,
     * so the cache key would be the pair, and the win over recomputing is not worth the
     * retention.
     */
    public PlutusScript issuanceMint(ProtocolBootstrapParams bootstrap, PlutusScript mintingLogicScript) {
        byte[] mintingLogicHash;
        try {
            mintingLogicHash = mintingLogicScript.getScriptHash();
        } catch (CborSerializationException e) {
            // The substandard's minting-logic script came from a blueprint we just parsed, so
            // failing to hash it means that blueprint is malformed, not that this call is wrong.
            throw new IllegalStateException("could not hash the substandard minting-logic script", e);
        }
        var params = ListPlutusData.of(
                // programmable_logic_base: Credential — PLB's hash, the payment credential
                // every programmable-token UTxO lives at.
                scriptCredential(bootstrap.programmableLogicBaseParams().scriptHash()),
                // registry_node_cs: PolicyId — the registry_mint policy, i.e. the NFT policy
                // that authenticates registry nodes.
                policyId(bootstrap.directoryMintParams().scriptHash()),
                // minting_logic_cred: Credential — the substandard's withdraw-0 minting logic.
                // This is what binds a token's policy id to its substandard: registry_mint
                // re-derives this policy id from the template and refuses a registration whose
                // declared minting_logic_script does not reproduce it.
                scriptCredential(mintingLogicHash),
                // params_policy: PolicyId — the protocol-params NFT policy, BARE, not wrapped
                // as a Credential.
                //
                // This parameter used to be `plg_stake_cred: Credential`, the coordinator's
                // credential. Same position, same arity, different type: the old form wrapped the
                // hash in Constr1 and this one does not. Applying the old shape to the new
                // validator would have compiled, run, and produced a working script under a
                // DIFFERENT policy id — mints would succeed and then fail registry checks for
                // reasons pointing nowhere near here. CoreBlueprintSurfaceTest asserts the
                // declared parameter TYPES for exactly this case.
                //
                // The change also follows the same logic as PLB's: anchoring on the params NFT
                // instead of a delegate's hash is what lets delegates be swapped in place.
                policyId(bootstrap.protocolParams().scriptHash())
        );
        return apply(CoreValidator.ISSUANCE_MINT, params);
    }

    /** {@code always_fail} under a caller-chosen nonce, which is its only parameter. */
    public PlutusScript alwaysFail(String nonce) {
        return apply(CoreValidator.ALWAYS_FAIL, ListPlutusData.of(BytesPlutusData.of(HexUtil.decodeHexString(nonce))));
    }

    /**
     * Every core validator's parameter list, in declaration order.
     *
     * <p>Read this against {@code src/core-contracts/plutus.json}'s
     * {@code validators[].parameters} — the two must agree in arity, order AND wrapping.
     */
    private ListPlutusData parametersFor(CoreValidator validator, ProtocolBootstrapParams b) {
        return switch (validator) {

            // params_policy: PolicyId — a BARE policy id, deliberately not a Credential.
            // PLB is anchored to the protocol-params NFT rather than to a delegate's hash,
            // which is what makes delegates swappable without moving PLB's hash (and with it
            // every programmable token address).
            case PROGRAMMABLE_LOGIC_BASE -> ListPlutusData.of(
                    policyId(b.programmableLogicBaseParams().protocolParamsPolicyId()));

            // params_policy: PolicyId. Same anchor as PLB, for all three delegates.
            //
            // That they take the same parameter is why the deployment must check they end up
            // with DIFFERENT hashes: PLB's dispatch is only meaningful while transfer_cred,
            // third_party_cred and unfracking_cred are pairwise distinct, and neither
            // protocol_params_mint nor coordination_spend enforces that on chain. They differ
            // here because their source differs, but a deployment that wired the same script
            // into two fields would collapse two dispatch arms into one silently.
            case TRANSFER, THIRD_PARTY, UNFRACKING -> ListPlutusData.of(
                    policyId(b.protocolParams().scriptHash()));

            // utxo_ref: OutputReference, always_fail_hash: ByteArray.
            //
            // The second parameter is now declared `coordination_addr_hash` (it was
            // `always_fail_hash`, a name left over from when the params NFT was locked at an
            // unspendable script). Only the NAME moved: since the upgradability work the NFT
            // has been locked at coordination_spend so it can be spent to rewrite the live
            // wiring, and the bootstrap record's `alwaysFailScriptHash` field already holds
            // that coordination hash. The field name in the bootstrap JSON is the stale one
            // now; renaming it is a deployment-record migration, not a contract change.
            case PROTOCOL_PARAMS_MINT -> ListPlutusData.of(
                    outputReference(b.protocolParams().txInput()),
                    BytesPlutusData.of(HexUtil.decodeHexString(b.protocolParams().alwaysFailScriptHash())));

            // utxo_ref: OutputReference, always_fail_hash: ByteArray.
            case ISSUANCE_CBOR_HEX_MINT -> ListPlutusData.of(
                    outputReference(b.issuanceParams().txInput()),
                    BytesPlutusData.of(HexUtil.decodeHexString(b.issuanceParams().alwaysFailScriptHash())));

            // utxo_ref: OutputReference, issuance_cbor_hex_cs: PolicyId, registry_spend_cred: Credential.
            //
            // NAMING TRAP: the bootstrap record calls the second value `issuanceScriptHash`,
            // which reads as the issuance_mint policy. It is not — it is the
            // issuance_cbor_hex_mint policy, the one-shot NFT whose datum carries the
            // issuance_mint template bytes. The bootstrap JSON sets it equal to
            // issuanceParams.scriptHash, and ProtocolScriptBuilderServiceHashDerivationTest
            // pins that value as issuance_cbor_hex_mint's own policy id, which is what makes
            // this reading provable rather than inferred.
            case REGISTRY_MINT -> ListPlutusData.of(
                    outputReference(b.directoryMintParams().txInput()),
                    policyId(b.directoryMintParams().issuanceScriptHash()),
                    scriptCredential(b.directorySpendParams().scriptHash()));

            // protocol_params_cs: PolicyId.
            case REGISTRY_SPEND -> ListPlutusData.of(
                    policyId(b.protocolParams().scriptHash()));

            // nonce: ByteArray — gives each deployment its own coordination address.
            case COORDINATION_SPEND -> ListPlutusData.of(
                    BytesPlutusData.of(HexUtil.decodeHexString(b.coordinationParams().nonce())));

            // signers: List<VerificationKeyHash>, threshold: Int.
            case UPGRADE_MULTISIG -> ListPlutusData.of(
                    ListPlutusData.of(b.upgradeMultisigParams().signers().stream()
                            .map(s -> (PlutusData) BytesPlutusData.of(HexUtil.decodeHexString(s)))
                            .toArray(PlutusData[]::new)),
                    BigIntPlutusData.of(b.upgradeMultisigParams().threshold()));

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
}
