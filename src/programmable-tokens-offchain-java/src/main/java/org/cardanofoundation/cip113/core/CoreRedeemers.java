package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * Every redeemer the CIP-113 <em>core</em> validators accept, built in one place.
 *
 * <p>These were previously written inline at each call site as raw
 * {@code ConstrPlutusData.of(1, BigIntPlutusData.of(a), BigIntPlutusData.of(b))} — a form
 * that says nothing about which type it encodes, which constructor index that is, or what
 * the two integers mean. Five substandard handlers each carried their own copies. The
 * result is that a redeemer change upstream is not a single edit but a search, and a
 * mis-ordered pair of {@code Int} fields is invisible in review.
 *
 * <p>Constructor indices below are read off
 * {@code src/main/resources/plutus.json}'s {@code definitions}, and every method names the
 * aiken type and constructor it encodes so the two can be checked against each other
 * without a decoder.
 *
 * <h2>Redeemers that carry positions, not values</h2>
 *
 * Several of these say <em>where to look</em> rather than what to do: {@code params_idx},
 * {@code wdrl_idx}, {@code registry_node_idx}, {@code outputs_start_idx}, {@code node_idx}.
 * A validator resolves the thing at that position and checks it, so pointing one slot away
 * fails with a complaint about whatever happened to be there. None of these positions is
 * the order a builder added things in — the ledger re-sorts reference inputs and keys
 * withdrawals by credential — so they must come from {@link CoreLayout}, which sees the
 * finished transaction, and never from a local count.
 */
public final class CoreRedeemers {

    private CoreRedeemers() {}

    // ── programmable_logic_base.spend ────────────────────────────────────────
    //
    // PLB runs once per programmable-token input and dispatches each spend to exactly ONE
    // of three withdraw-0 delegates. The redeemer both picks the arm and witnesses WHERE
    // that delegate's entry sits in the ledger-ordered withdrawal map, so PLB can resolve it
    // by index instead of scanning and comparing every entry — which matters because the
    // cost is paid per input.
    //
    // The witness is self-validating: a wrong index, or a wrong arm, resolves to some other
    // credential and fails the equality. A dishonest witness can only invalidate its own
    // transaction. That argument holds only while the three delegate credentials in the
    // params datum are pairwise distinct, which nothing on chain enforces — see
    // CoreProtocolParamsDatum#validateForDeployment.
    //
    // Both indices must be derived from the FINAL transaction, which is why they come from a
    // CoreLayout rather than being counted locally: params_idx is a position among the
    // ledger-sorted reference inputs, and wdrl_idx a position in the ledger-ordered
    // withdrawal map over every withdrawal the transaction carries, including the
    // substandard's and any the wallet adds.

    /**
     * {@code BaseSpendRedeemer::SpendViaTransfer { params_idx, wdrl_idx }} — constructor 0.
     * The ordinary path: this spend is authorised by the {@code transfer} validator.
     */
    public static PlutusData spendViaTransfer(int paramsIdx, int wdrlIdx) {
        return ConstrPlutusData.of(0, integer(paramsIdx), integer(wdrlIdx));
    }

    /**
     * {@code BaseSpendRedeemer::SpendViaThirdParty { params_idx, wdrl_idx }} — constructor 1.
     * The administrative path: seizure, clawback, freeze enforcement, burn.
     */
    public static PlutusData spendViaThirdParty(int paramsIdx, int wdrlIdx) {
        return ConstrPlutusData.of(1, integer(paramsIdx), integer(wdrlIdx));
    }

    /**
     * {@code BaseSpendRedeemer::SpendViaUnfracking { params_idx, wdrl_idx }} — constructor 2.
     * Holder-driven same-owner restructuring. No builder here uses it yet.
     */
    public static PlutusData spendViaUnfracking(int paramsIdx, int wdrlIdx) {
        return ConstrPlutusData.of(2, integer(paramsIdx), integer(wdrlIdx));
    }

    // ── transfer.withdraw ────────────────────────────────────────────────────

    /**
     * {@code TransferRedeemer { params_idx, proofs }} — constructor 0.
     *
     * <p>One registry proof per distinct spent policy, in ascending policy order. The
     * ordering is not cosmetic: the validator walks the spent policies and the proofs in
     * lockstep, so a proof list in any other order is checked against a policy it was not
     * meant for.
     *
     * <p>This replaces {@code ProgrammableLogicGlobalRedeemer::TransferAct}, which carried
     * the proof list alone. The leading {@code params_idx} makes the two encodings
     * incompatible, so there is no version of this transaction that satisfies both.
     */
    public static PlutusData transferRedeemer(int paramsIdx, List<PlutusData> registryProofs) {
        return ConstrPlutusData.of(0,
                integer(paramsIdx),
                ListPlutusData.of(registryProofs.toArray(PlutusData[]::new)));
    }

    // ── third_party.withdraw ─────────────────────────────────────────────────

    /**
     * {@code ThirdPartyRedeemer { params_idx, registry_node_idx, outputs_start_idx }} —
     * constructor 0, on the standalone {@code third_party} validator.
     *
     * @param paramsIdx        position of the protocol-params UTxO among the ledger-sorted
     *                         reference inputs
     * @param registryNodeIdx  position of the subject policy's registry node, likewise
     * @param outputsStartIdx  index of the first paired continuation output. Every acted-on
     *                         PLB input is paired positionally with an output from here
     *                         onwards, and each pair must agree byte-for-byte on address,
     *                         datum and reference script — the seizure moves tokens, not
     *                         ownership metadata.
     *
     * <p>Previously this was the {@code ThirdPartyAct} arm of the coordinator's redeemer.
     * The two extra fields are unchanged; what moved is which validator's withdrawal carries
     * them, and PLB must now be told to expect that validator via
     * {@link #spendViaThirdParty}.
     */
    public static PlutusData thirdPartyRedeemer(int paramsIdx, int registryNodeIdx, int outputsStartIdx) {
        return ConstrPlutusData.of(0, integer(paramsIdx), integer(registryNodeIdx), integer(outputsStartIdx));
    }

    // ── unfracking.withdraw ──────────────────────────────────────────────────

    /**
     * {@code UnfrackingRedeemer { params_idx, registry_node_idx, outputs_start_idx }} —
     * constructor 0.
     *
     * <p>Reached directly from PLB via {@link #spendViaUnfracking}; the coordinator's
     * {@code UnfrackingAct} hop no longer exists. An unfracking transaction never loads the
     * transfer reference script at all.
     */
    public static PlutusData unfrackingRedeemer(int paramsIdx, int registryNodeIdx, int outputsStartIdx) {
        return ConstrPlutusData.of(0, integer(paramsIdx), integer(registryNodeIdx), integer(outputsStartIdx));
    }

    // ── registry proofs (carried inside the transfer redeemer) ───────────────

    /** {@code RegistryProof::TokenExists { node_idx }} — constructor 0. */
    public static PlutusData tokenExists(int nodeIdx) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(nodeIdx)));
    }

    /**
     * {@code RegistryProof::TokenDoesNotExist { node_idx }} — constructor 1.
     *
     * <p>The node at {@code node_idx} is the <em>covering</em> node: the linked-list entry
     * whose key/next span brackets the absent policy, which is what proves absence. It is
     * not the policy's own node, because there isn't one.
     */
    public static PlutusData tokenDoesNotExist(int coveringNodeIdx) {
        return ConstrPlutusData.of(1, BigIntPlutusData.of(BigInteger.valueOf(coveringNodeIdx)));
    }

    // ── issuance_mint.mint ───────────────────────────────────────────────────

    /**
     * {@code MintingRegistryProof::RefInput { index }} — constructor 0. For a mint of a
     * policy that is already registered: the registry node is a reference input.
     */
    public static PlutusData mintProofRefInput(int refInputIdx) {
        return ConstrPlutusData.of(0, BigIntPlutusData.of(BigInteger.valueOf(refInputIdx)));
    }

    /**
     * {@code MintingRegistryProof::OutputIndex { index }} — constructor 1. For a FIRST mint,
     * where the registry node is being created by this same transaction and so appears as an
     * output rather than a reference input.
     */
    public static PlutusData mintProofOutputIndex(int outputIdx) {
        return ConstrPlutusData.of(1, BigIntPlutusData.of(BigInteger.valueOf(outputIdx)));
    }

    // ── registry_mint.mint ───────────────────────────────────────────────────

    /** {@code RegistryRedeemer::RegistryInit} — constructor 0, no fields. */
    public static PlutusData registryInit() {
        return ConstrPlutusData.of(0);
    }

    /**
     * {@code RegistryRedeemer::RegistryInsert { key, minting_logic_script }} — constructor 1.
     *
     * @param key  the new policy id being registered
     * @param mintingLogicScriptHash  the substandard's withdraw-0 minting-logic credential
     *
     * <p>{@code registry_mint} does three things with this pair, which is why passing the
     * wrong credential fails in a way that looks unrelated: it requires the new node's datum
     * to carry the same credential, it re-derives the issuance policy id from the template
     * parameterised with it and requires that to equal {@code key}, and it requires that
     * credential's withdraw-0 to be present in the transaction.
     *
     * <p>A caller-selected {@code mode} field existed between upstream's {@code #52} and
     * re-audit {@code R-06}; it is gone, and both register-only and register-and-mint flows
     * remain valid.
     */
    public static PlutusData registryInsert(String key, byte[] mintingLogicScriptHash) {
        return ConstrPlutusData.of(1,
                BytesPlutusData.of(HexUtil.decodeHexString(key)),
                scriptCredential(mintingLogicScriptHash));
    }

    // ── registry_spend.spend ─────────────────────────────────────────────────

    /**
     * {@code registry_spend} declares an untyped redeemer and ignores it, on both the insert
     * path and the in-place node-update path. Named rather than inlined so that a future
     * revision giving it a real redeemer has one place to change.
     */
    public static PlutusData registrySpend() {
        return ConstrPlutusData.of(0);
    }

    // ── coordination_spend.spend ─────────────────────────────────────────────

    /**
     * {@code coordination_spend} also ignores its redeemer: authorisation is trampolined to
     * the upgrade credential named in the datum being spent, and every structural rail is
     * checked against the inputs and outputs rather than announced by the caller.
     */
    public static PlutusData coordinationSpend() {
        return ConstrPlutusData.of(0);
    }

    // ── shared encodings ─────────────────────────────────────────────────────

    private static PlutusData integer(int value) {
        return BigIntPlutusData.of(BigInteger.valueOf(value));
    }

    /** {@code Credential::Script(hash)} — constructor 1 of aiken's {@code Credential}. */
    private static PlutusData scriptCredential(byte[] hash) {
        return ConstrPlutusData.of(1, BytesPlutusData.of(hash));
    }
}
