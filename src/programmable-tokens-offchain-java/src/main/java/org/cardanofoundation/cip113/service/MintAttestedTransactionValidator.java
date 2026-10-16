package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.metadata.MetadataList;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.MintAttestationRequest;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks the serialized unsigned transaction before its CBOR is persisted or returned. */
public final class MintAttestedTransactionValidator {
    private MintAttestedTransactionValidator() {}

    /**
     * CIP-170 v1.1: the mint itself carries exactly {@code 170: {t: ATTEST_TX, i: [expectedAid], v: {v: "1.1"}}}.
     * When {@code sealDigest} is given it must be the transaction seal over this mint's own ID.
     */
    public static String validateAttestTx(String cbor, MintAttestationRequest intent, String expectedAid,
                                          String sealDigest, long networkMagic) {
        try {
            byte[] bytes = HexUtil.decodeHexString(cbor);
            Transaction tx = Transaction.deserialize(bytes);
            checkMint(tx, intent);
            requireAttestTx(tx, expectedAid);
            String hash = TransactionUtil.getTxHash(bytes).toLowerCase(Locale.ROOT);
            if (!hash.equals(TransactionUtil.getTxHash(tx.serialize()).toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("mint body does not survive re-serialisation byte for byte");
            if (sealDigest != null && !TxAttestationSeal.digest(hash, networkMagic).equals(sealDigest))
                throw new IllegalArgumentException("CIP-170 transaction seal does not identify this mint");
            return hash;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("could not validate attested mint transaction", ex);
        }
    }

    public static String validate(String cbor, MintAttestationRequest intent,
                                  Cip170AttestationData attestation) {
        try {
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(cbor));
            checkMint(tx, intent);

            if (attestation == null && tx.getAuxiliaryData() != null
                    && tx.getAuxiliaryData().getMetadata() != null
                    && tx.getAuxiliaryData().getMetadata().get(BigInteger.valueOf(170)) != null)
                throw new IllegalArgumentException("Target mint must not contain its own CIP-170 attestation");
            if (attestation != null) validateAttestation(tx, attestation);
            return com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx.serialize())
                    .toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("could not validate attested mint transaction", ex);
        }
    }

    private static void checkMint(Transaction tx, MintAttestationRequest intent) {
        if (tx.getBody() == null || tx.getBody().getMint() == null)
            throw new IllegalArgumentException("mint body missing");
        BigInteger expected = new BigInteger(intent.quantity());
        if (expected.signum() <= 0) throw new IllegalArgumentException("mint quantity must be positive");
        var minted = tx.getBody().getMint();
        if (minted.size() != 1 || minted.getFirst().getAssets().size() != 1)
            throw new IllegalArgumentException("attested mint must contain exactly one asset");
        var policy = minted.getFirst();
        var asset = policy.getAssets().getFirst();
        if (!policy.getPolicyId().equalsIgnoreCase(intent.tokenPolicyId())
                || !Arrays.equals(asset.getNameAsBytes(), HexUtil.decodeHexString(intent.assetName()))
                || !expected.equals(asset.getValue()))
            throw new IllegalArgumentException("serialized mint differs from approved intent");

        byte[] destination = new Address(intent.programmableRecipientAddress()).getBytes();
        BigInteger delivered = BigInteger.ZERO;
        for (var output : tx.getBody().getOutputs()) {
            if (!Arrays.equals(destination, new Address(output.getAddress()).getBytes())) continue;
            if (output.getValue() == null || output.getValue().getMultiAssets() == null) continue;
            for (var outputPolicy : output.getValue().getMultiAssets()) {
                if (!outputPolicy.getPolicyId().equalsIgnoreCase(intent.tokenPolicyId())) continue;
                for (var outputAsset : outputPolicy.getAssets()) {
                    if (Arrays.equals(outputAsset.getNameAsBytes(), HexUtil.decodeHexString(intent.assetName())))
                        delivered = delivered.add(outputAsset.getValue());
                }
            }
        }
        if (delivered.compareTo(expected) < 0)
            throw new IllegalArgumentException("approved recipient does not receive minted quantity");
    }

    /** Exactly the ATTEST_TX record {@code i: [expectedAid]}, committed to by the body's auxiliary-data hash. */
    static void requireAttestTx(Transaction tx, String expectedAid) {
        var auxiliary = tx.getAuxiliaryData();
        if (expectedAid == null || expectedAid.isBlank())
            throw new IllegalArgumentException("ATTEST_TX requires the signer AID");
        if (auxiliary == null || auxiliary.getMetadata() == null
                || !Arrays.equals(tx.getBody().getAuxiliaryDataHash(), auxiliary.getAuxiliaryDataHash()))
            throw new IllegalArgumentException("CIP-170 metadata is absent from transaction body");
        if (!(auxiliary.getMetadata().get(BigInteger.valueOf(170)) instanceof MetadataMap root))
            throw new IllegalArgumentException("CIP-170 ATTEST_TX label missing");
        // Metadata maps are written in canonical CBOR key order, so compare key sets, not order.
        if (!Set.of("t", "i", "v").equals(root.keys().stream().map(Object::toString).collect(Collectors.toSet()))
                || !Cip170AttestationData.ATTEST_TX.equals(root.get("t"))
                || !(root.get("i") instanceof MetadataList signers)
                || signers.size() != 1
                || !expectedAid.equals(signers.getValueAt(0)))
            throw new IllegalArgumentException("CIP-170 ATTEST_TX differs from the approving identity");
        if (!(root.get("v") instanceof MetadataMap version)
                || !Set.of("v").equals(version.keys().stream().map(Object::toString).collect(Collectors.toSet()))
                || !"1.1".equals(version.get("v")))
            throw new IllegalArgumentException("unsupported CIP-170 ATTEST_TX version");
    }

    static void validateAttestation(Transaction tx, Cip170AttestationData attestation) {
            var auxiliary = tx.getAuxiliaryData();
            if (auxiliary == null || auxiliary.getMetadata() == null
                    || !Arrays.equals(tx.getBody().getAuxiliaryDataHash(), auxiliary.getAuxiliaryDataHash()))
                throw new IllegalArgumentException("CIP-170 metadata is absent from transaction body");
            Object label = auxiliary.getMetadata().get(BigInteger.valueOf(170));
            if (!(label instanceof MetadataMap root))
                throw new IllegalArgumentException("CIP-170 ATTEST label missing");
            if (!"ATTEST".equals(root.get("t"))
                    || !attestation.signerAid().equals(root.get("i"))
                    || !attestation.digest().equals(root.get("d"))
                    || !attestation.seqNumber().equals(root.get("s")))
                throw new IllegalArgumentException("CIP-170 ATTEST differs from verified KEL anchor");
            if (!(root.get("v") instanceof MetadataMap version)
                    || !"1.0".equals(version.get("v")))
                throw new IllegalArgumentException("unsupported CIP-170 metadata version");
    }
}
