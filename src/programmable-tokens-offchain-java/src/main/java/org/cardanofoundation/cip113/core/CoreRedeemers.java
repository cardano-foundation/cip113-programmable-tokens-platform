package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.MapPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;

/** Canonical alpha.4 encoders for the CIP-113 core redeemers. */
public final class CoreRedeemers {

    private CoreRedeemers() {
    }

    /** {@code BaseSpendRedeemer { params_idx, wdrl_idx }}. wdrl_idx targets PLG. */
    public static PlutusData baseSpend(int paramsIdx, int plgWithdrawalIdx) {
        requireIndex("params_idx", paramsIdx);
        requireIndex("wdrl_idx", plgWithdrawalIdx);
        return ConstrPlutusData.of(0, integer(paramsIdx), integer(plgWithdrawalIdx));
    }

    /** Dispatcher arm {@code PlgAct::Transfer}. */
    public static PlutusData dispatchTransfer() {
        return ConstrPlutusData.of(0);
    }

    /** Dispatcher arm {@code PlgAct::ThirdParty}. */
    public static PlutusData dispatchThirdParty() {
        return ConstrPlutusData.of(1);
    }

    /** Dispatcher arm {@code PlgAct::Unfracking}. */
    public static PlutusData dispatchUnfracking() {
        return ConstrPlutusData.of(2);
    }

    /** {@code TransferRedeemer { proofs }}; delegates no longer carry params_idx. */
    public static PlutusData transferRedeemer(List<PlutusData> registryProofs) {
        return ConstrPlutusData.of(0,
                ListPlutusData.of(registryProofs.toArray(PlutusData[]::new)));
    }

    /** {@code ThirdPartyRedeemer { registry_node_idx, outputs_start_idx }}. */
    public static PlutusData thirdPartyRedeemer(int registryNodeIdx, int outputsStartIdx) {
        requireIndex("registry_node_idx", registryNodeIdx);
        requireIndex("outputs_start_idx", outputsStartIdx);
        return ConstrPlutusData.of(0, integer(registryNodeIdx), integer(outputsStartIdx));
    }

    /** {@code UnfrackingRedeemer { registry_node_idx, outputs_start_idx }}. */
    public static PlutusData unfrackingRedeemer(int registryNodeIdx, int outputsStartIdx) {
        requireIndex("registry_node_idx", registryNodeIdx);
        requireIndex("outputs_start_idx", outputsStartIdx);
        return ConstrPlutusData.of(0, integer(registryNodeIdx), integer(outputsStartIdx));
    }

    public static PlutusData tokenExists(int nodeIdx) {
        requireIndex("node_idx", nodeIdx);
        return ConstrPlutusData.of(0, integer(nodeIdx));
    }

    public static PlutusData tokenDoesNotExist(int coveringNodeIdx) {
        requireIndex("node_idx", coveringNodeIdx);
        return ConstrPlutusData.of(1, integer(coveringNodeIdx));
    }

    /** A proof value used inside issuance_logic's policy-to-proof map. */
    public static PlutusData mintProofRefInput(int refInputIdx) {
        requireIndex("registry reference-input index", refInputIdx);
        return ConstrPlutusData.of(0, integer(refInputIdx));
    }

    /** A proof value used inside issuance_logic's policy-to-proof map. */
    public static PlutusData mintProofOutputIndex(int outputIdx) {
        requireIndex("registry output index", outputIdx);
        return ConstrPlutusData.of(1, integer(outputIdx));
    }

    /** Permanent issuance policy redeemer: only the params reference-input index. */
    public static PlutusData issuanceRedeemer(int paramsIdx) {
        requireIndex("params_idx", paramsIdx);
        return ConstrPlutusData.of(0, integer(paramsIdx));
    }

    public record IssuanceEntry(String policyId, PlutusData proof) {
    }

    /** Replaceable issuance_logic withdraw redeemer: map(policy id -&gt; registry proof). */
    public static PlutusData issuanceLogicRedeemer(List<IssuanceEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("issuance_logic redeemer must cover at least one policy");
        }
        var seen = new HashSet<String>();
        var map = new MapPlutusData();
        for (var entry : entries) {
            String policy = entry.policyId() == null ? null : entry.policyId().toLowerCase();
            if (policy == null || !policy.matches("[0-9a-f]{56}")) {
                throw new IllegalArgumentException("not a 28-byte policy id: " + entry.policyId());
            }
            if (!seen.add(policy)) {
                throw new IllegalArgumentException("duplicate issuance policy: " + policy);
            }
            if (!(entry.proof() instanceof ConstrPlutusData proof)
                    || (proof.getAlternative() != 0 && proof.getAlternative() != 1)
                    || proof.getData().getPlutusDataList().size() != 1) {
                throw new IllegalArgumentException("invalid registry proof for issuance policy " + policy);
            }
            map.put(BytesPlutusData.of(HexUtil.decodeHexString(policy)), entry.proof());
        }
        return map;
    }

    public static PlutusData registryInit() {
        return ConstrPlutusData.of(0);
    }

    public static PlutusData registryInsert(String key, byte[] mintingLogicScriptHash) {
        return ConstrPlutusData.of(1,
                BytesPlutusData.of(HexUtil.decodeHexString(key)),
                ConstrPlutusData.of(1, BytesPlutusData.of(mintingLogicScriptHash)));
    }

    /** The registry spend branch is untyped in alpha.4. */
    public static PlutusData registrySpend() {
        return ConstrPlutusData.of(0);
    }

    public enum ProtocolParamsAct {
        PROTOCOL_UPGRADE(0), NOMINATE_AUTHORITY(1), PROMOTE_AUTHORITY(2);

        private final int constructor;

        ProtocolParamsAct(int constructor) {
            this.constructor = constructor;
        }
    }

    public static PlutusData protocolParams(ProtocolParamsAct act) {
        if (act == null) throw new IllegalArgumentException("protocol params act is required");
        return ConstrPlutusData.of(act.constructor);
    }

    private static BigIntPlutusData integer(int value) {
        return BigIntPlutusData.of(BigInteger.valueOf(value));
    }

    private static void requireIndex(String name, int value) {
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative: " + value);
    }
}
