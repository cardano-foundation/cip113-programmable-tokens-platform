package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.Builder;

import java.util.Objects;

/**
 * On-chain {@code RegistryNode} (lib/registry_node.ak, v0.4.0) — 7 fields.
 *
 * <p>Field order is load-bearing: it is the CBOR constructor-field order consumed
 * on-chain, and {@code registry_mint} checks a covering node's re-emission by
 * <b>whole-record equality</b> (lib/linked_list.ak, {@code is_updated_directory_node}
 * / {@code is_field_updated_registry_node}). That includes the constructor tag
 * inside each {@link Credential} — an unset hook must round-trip as
 * {@code VerificationKey(#"")} ({@link PlutusCredentialCodec#EMPTY_VKEY}), not as a
 * {@code Script} of the same (empty) hash, or every insert touching that node is
 * rejected on-chain even though the bytes match.
 *
 * <pre>
 * 0 key                                ByteArray
 * 1 next                                ByteArray
 * 2 mintingLogicScript                  Credential
 * 3 transferLogicScript                 Credential
 * 4 thirdPartyTransferLogicScript       Credential
 * 5 unfrackingLogicScript               Credential  (new in v0.4.0)
 * 6 globalStatePolicyId (global_state_cs) ByteArray
 * </pre>
 */
@Builder(toBuilder = true)
public record RegistryNode(String key,
                            String next,
                            Credential mintingLogicScript,
                            Credential transferLogicScript,
                            Credential thirdPartyTransferLogicScript,
                            Credential unfrackingLogicScript,
                            String globalStatePolicyId) {

    /** {@code registry_node.empty_vkey = VerificationKey(#"")} */
    public static final Credential EMPTY_VKEY = PlutusCredentialCodec.EMPTY_VKEY;

    public RegistryNode {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(mintingLogicScript, "mintingLogicScript");
        Objects.requireNonNull(transferLogicScript, "transferLogicScript");
        Objects.requireNonNull(thirdPartyTransferLogicScript, "thirdPartyTransferLogicScript");
        Objects.requireNonNull(unfrackingLogicScript, "unfrackingLogicScript");
        Objects.requireNonNull(globalStatePolicyId, "globalStatePolicyId");
    }

    public PlutusData toPlutusData() {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(key)),
                BytesPlutusData.of(HexUtil.decodeHexString(next)),
                PlutusCredentialCodec.toPlutusData(mintingLogicScript),
                PlutusCredentialCodec.toPlutusData(transferLogicScript),
                PlutusCredentialCodec.toPlutusData(thirdPartyTransferLogicScript),
                PlutusCredentialCodec.toPlutusData(unfrackingLogicScript),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)));
    }
}
