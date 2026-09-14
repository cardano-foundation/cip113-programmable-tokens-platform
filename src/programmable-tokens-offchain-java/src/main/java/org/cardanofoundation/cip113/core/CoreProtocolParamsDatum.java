package org.cardanofoundation.cip113.core;

import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.onchain.PlutusCredentialCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The alpha.4 protocol-params NFT inline datum.
 *
 * <p>Field order is load-bearing. Alpha.4 inserted {@code issuance_logic_cred} at index 1,
 * displacing transfer and third-party, and appended the optional pending authority:
 *
 * <pre>
 *   0 plg_cred
 *   1 issuance_logic_cred
 *   2 transfer_cred
 *   3 third_party_cred
 *   4 upgrade_cred
 *   5 pending_upgrade_cred : Option&lt;Credential&gt;
 * </pre>
 */
public record CoreProtocolParamsDatum(
        Credential plgCred,
        Credential issuanceLogicCred,
        Credential transferCred,
        Credential thirdPartyCred,
        Credential upgradeCred,
        Credential pendingUpgradeCred) {

    public static final int FIELD_COUNT = 6;
    public static final String TOKEN_NAME = "ProtocolParams";

    /** Deployment choice baked into transfer, third-party, unfracking and issuance-logic. */
    public static final long DEFAULT_MAX_INLINE_DATUM_BYTES = 1024L;

    public static CoreProtocolParamsDatum fromHex(String inlineDatumHex) {
        try {
            return from(PlutusData.deserialize(HexUtil.decodeHexString(inlineDatumHex)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not a decodable protocol-params datum: " + inlineDatumHex, e);
        }
    }

    /** Decode strictly; an older positional shape must never be adapted. */
    public static CoreProtocolParamsDatum from(PlutusData datum) {
        if (!(datum instanceof ConstrPlutusData constr) || constr.getAlternative() != 0) {
            throw new IllegalArgumentException("protocol-params datum is not constructor 0: " + datum);
        }
        List<PlutusData> f = constr.getData().getPlutusDataList();
        if (f.size() != FIELD_COUNT) {
            throw new IllegalArgumentException(
                    "protocol-params datum has " + f.size() + " fields, expected " + FIELD_COUNT
                            + ". Alpha.4 inserted issuance_logic_cred at index 1; an older datum "
                            + "cannot be positionally adapted.");
        }
        return new CoreProtocolParamsDatum(
                PlutusCredentialCodec.fromPlutusData(f.get(0), "plg_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(1), "issuance_logic_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(2), "transfer_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(3), "third_party_cred"),
                PlutusCredentialCodec.fromPlutusData(f.get(4), "upgrade_cred"),
                decodePending(f.get(5)));
    }

    private static Credential decodePending(PlutusData data) {
        if (!(data instanceof ConstrPlutusData option)) {
            throw new IllegalArgumentException("pending_upgrade_cred is not an Option constructor");
        }
        List<PlutusData> fields = option.getData().getPlutusDataList();
        if (option.getAlternative() == 1 && fields.isEmpty()) return null;
        if (option.getAlternative() == 0 && fields.size() == 1) {
            return PlutusCredentialCodec.fromPlutusData(fields.getFirst(), "pending_upgrade_cred");
        }
        throw new IllegalArgumentException("pending_upgrade_cred is neither None nor Some(Credential)");
    }

    public PlutusData toPlutusData() {
        PlutusData pending = pendingUpgradeCred == null
                ? ConstrPlutusData.of(1)
                : ConstrPlutusData.of(0, PlutusCredentialCodec.toPlutusData(pendingUpgradeCred));
        return ConstrPlutusData.of(0,
                PlutusCredentialCodec.toPlutusData(plgCred),
                PlutusCredentialCodec.toPlutusData(issuanceLogicCred),
                PlutusCredentialCodec.toPlutusData(transferCred),
                PlutusCredentialCodec.toPlutusData(thirdPartyCred),
                PlutusCredentialCodec.toPlutusData(upgradeCred),
                pending);
    }

    /** Refuse credentials that can never occur in a reward-account map. */
    public void validateForDeployment() {
        List<String> problems = new ArrayList<>();
        Map<String, Credential> credentials = new LinkedHashMap<>();
        credentials.put("plg_cred", plgCred);
        credentials.put("issuance_logic_cred", issuanceLogicCred);
        credentials.put("transfer_cred", transferCred);
        credentials.put("third_party_cred", thirdPartyCred);
        credentials.put("upgrade_cred", upgradeCred);
        if (pendingUpgradeCred != null) credentials.put("pending_upgrade_cred", pendingUpgradeCred);

        credentials.forEach((name, credential) -> {
            int length = credential == null || credential.getBytes() == null
                    ? -1 : credential.getBytes().length;
            if (length != 28) {
                problems.add(name + " must be a 28-byte hash, got "
                        + (length < 0 ? "null" : length + " bytes"));
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "refusing to deploy an unsound alpha.4 protocol-params datum:\n  - "
                            + String.join("\n  - ", problems));
        }
    }

    public String plgCredHex() {
        return PlutusCredentialCodec.hex(plgCred);
    }

    public String issuanceLogicCredHex() {
        return PlutusCredentialCodec.hex(issuanceLogicCred);
    }

    public String transferCredHex() {
        return PlutusCredentialCodec.hex(transferCred);
    }

    public String thirdPartyCredHex() {
        return PlutusCredentialCodec.hex(thirdPartyCred);
    }

    public String upgradeCredHex() {
        return PlutusCredentialCodec.hex(upgradeCred);
    }

    public String pendingUpgradeCredHex() {
        return pendingUpgradeCred == null ? null : PlutusCredentialCodec.hex(pendingUpgradeCred);
    }
}
