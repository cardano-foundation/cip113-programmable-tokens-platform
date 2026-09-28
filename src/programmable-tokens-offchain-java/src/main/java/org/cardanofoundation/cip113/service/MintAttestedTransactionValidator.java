package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.metadata.MetadataMap;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.cardanofoundation.cip113.model.MintAttestationRequest;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;

/** Checks the serialized unsigned transaction before its CBOR is persisted or returned. */
public final class MintAttestedTransactionValidator {
    private MintAttestedTransactionValidator() {}

    public static String validateMint(String cbor, MintAttestationRequest intent) {
        return validate(cbor, intent, null);
    }

    public static String validate(String cbor, MintAttestationRequest intent,
                                  Cip170AttestationData attestation) {
        try {
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(cbor));
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

    public static String validateChild(String childCbor, String mintCbor, String payer,
                                       Cip170AttestationData attestation) {
        try {
            Transaction mint = Transaction.deserialize(HexUtil.decodeHexString(mintCbor));
            Transaction child = Transaction.deserialize(HexUtil.decodeHexString(childCbor));
            String mintHash = TransactionUtil.getTxHash(mint.serialize()).toLowerCase(Locale.ROOT);
            if (!MintTxHashPayload.digest(mintHash).equals(attestation.digest()))
                throw new IllegalArgumentException("CIP-170 digest does not identify the mint transaction");
            var body = child.getBody();
            if (body == null || body.getInputs() == null || body.getInputs().size() != 1)
                throw new IllegalArgumentException("CIP-170 child must have exactly one normal input");
            var input = body.getInputs().getFirst();
            if (!mintHash.equalsIgnoreCase(input.getTransactionId()) || input.getIndex() < 0
                    || input.getIndex() >= mint.getBody().getOutputs().size())
                throw new IllegalArgumentException("CIP-170 child does not spend its target mint");
            var output = mint.getBody().getOutputs().get(input.getIndex());
            if (!Arrays.equals(new Address(payer).getBytes(), new Address(output.getAddress()).getBytes())
                    || output.getInlineDatum() != null || output.getDatumHash() != null || output.getScriptRef() != null
                    || output.getValue() == null || output.getValue().getMultiAssets() != null
                    && !output.getValue().getMultiAssets().isEmpty())
                throw new IllegalArgumentException("CIP-170 child spends an ineligible mint output");
            if (body.getMint() != null && !body.getMint().isEmpty())
                throw new IllegalArgumentException("CIP-170 child must not mint assets");
            validateAttestation(child, attestation);
            if (child.getAuxiliaryData().getMetadata().keys().size() != 1)
                throw new IllegalArgumentException("CIP-170 child must contain only label 170");
            return TransactionUtil.getTxHash(child.serialize()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("could not validate CIP-170 child", ex); }
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
