package org.cardanofoundation.cip113.model.onchain;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.List;

/**
 * Codec between Aiken's {@code cardano/address.Credential} on-chain shape and
 * cardano-client-lib's {@link Credential} value type.
 *
 * <p>On-chain: {@code VerificationKey(hash) = Constr(0, [hash])},
 * {@code Script(hash) = Constr(1, [hash])}. The constructor tag is part of the
 * value, not an encoding detail — {@code registry_mint} checks registry-node
 * re-emission by whole-record equality (lib/linked_list.ak,
 * {@code is_updated_directory_node}), and {@code unfracking_logic_script}
 * defaults to {@code empty_vkey = VerificationKey(#"")}. Serializing a hash as
 * the wrong variant (e.g. always emitting {@code Script}) makes every insert
 * touching that node fail equality on-chain, even though the hash bytes match.
 */
public final class PlutusCredentialCodec {

    /** {@code registry_node.empty_vkey = VerificationKey(#"")} — the "no credential set" sentinel. */
    public static final Credential EMPTY_VKEY = Credential.fromKey(new byte[0]);

    private PlutusCredentialCodec() {
    }

    /** Hex-encodes a credential's raw hash, discarding the constructor tag (for DB/API display). */
    public static String hex(Credential credential) {
        return HexUtil.encodeHexString(credential.getBytes());
    }

    public static PlutusData toPlutusData(Credential credential) {
        int alternative = credential.getType() == CredentialType.Key ? 0 : 1;
        return ConstrPlutusData.of(alternative, BytesPlutusData.of(credential.getBytes()));
    }

    /**
     * @param field     the credential's own field, expected to be a single-field constructor (tag 0 or 1)
     * @param fieldName used only for the exception message, to identify which RegistryNode field failed
     * @throws IllegalArgumentException if {@code field} is not a well-formed Credential — deliberately
     *                                   loud rather than defaulting to an empty/plausible value, since a
     *                                   quietly-wrong credential is exactly the bug this codec replaces.
     */
    public static Credential fromPlutusData(PlutusData field, String fieldName) {
        if (!(field instanceof ConstrPlutusData constr)) {
            throw new IllegalArgumentException(
                    "credential field '" + fieldName + "' is not a constructor: " + field);
        }
        List<PlutusData> data = constr.getData().getPlutusDataList();
        if (data.size() != 1 || !(data.get(0) instanceof BytesPlutusData bytes)) {
            throw new IllegalArgumentException(
                    "credential field '" + fieldName + "' must be a single-field constructor carrying a bytestring, got: " + field);
        }
        long alternative = constr.getAlternative();
        if (alternative == 0) {
            return Credential.fromKey(bytes.getValue());
        } else if (alternative == 1) {
            return Credential.fromScript(bytes.getValue());
        } else {
            throw new IllegalArgumentException(
                    "credential field '" + fieldName + "' has unexpected constructor alternative " + alternative + " (expected 0 or 1)");
        }
    }
}
