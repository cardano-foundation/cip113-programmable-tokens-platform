package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.model.Cip170AttestationData;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Builds a label-170 child that proves which mint transaction was attested. */
@Component
@RequiredArgsConstructor
public class Cip170MintChildBuilder {
    private final QuickTxBuilder quickTxBuilder;

    public Transaction build(String payer, String mintCbor, Cip170AttestationData attestation,
                             long minimumInputLovelace, Integer preferredOutputIndex) throws Exception {
        Transaction mint = Transaction.deserialize(HexUtil.decodeHexString(mintCbor));
        String mintHash = TransactionUtil.getTxHash(mint.serialize());
        Utxo funding = fundingOutput(mint, mintHash, payer, minimumInputLovelace, preferredOutputIndex);
        if (funding == null) throw new IllegalArgumentException(
                "Mint transaction has no plain fee-payer output with enough ADA for CIP-170 attestation");
        var tx = new Tx().from(payer).collectFrom(List.of(funding))
                .payToAddress(payer, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L))))
                .withChangeAddress(payer);
        MintAttestationMetadata.attach(tx, attestation);
        Transaction child = quickTxBuilder.compose(tx).feePayer(payer).mergeOutputs(false).build();
        var body = child.getBody();
        if (body == null || body.getInputs() == null || body.getInputs().size() != 1
                || !mintHash.equals(body.getInputs().getFirst().getTransactionId())
                || funding.getOutputIndex() != body.getInputs().getFirst().getIndex())
            throw new IllegalStateException("CIP-170 child must spend only the selected mint output");
        if (body.getMint() != null && !body.getMint().isEmpty())
            throw new IllegalStateException("CIP-170 child must not mint assets");
        if (body.getAuxiliaryDataHash() == null || child.getAuxiliaryData() == null
                || !Arrays.equals(body.getAuxiliaryDataHash(), child.getAuxiliaryData().getAuxiliaryDataHash()))
            throw new IllegalStateException("CIP-170 child has no matching auxiliary-data hash");
        return child;
    }

    public static Utxo fundingOutput(Transaction mint, String mintHash, String payer,
                                     long minimumInputLovelace, Integer preferredOutputIndex) {
        if (mint == null || mint.getBody() == null || mint.getBody().getOutputs() == null)
            throw new IllegalArgumentException("Mint transaction has no outputs");
        byte[] payerBytes = new Address(payer).getBytes();
        List<TransactionOutput> outputs = mint.getBody().getOutputs();
        for (int index = 0; index < outputs.size(); index++) {
            if (preferredOutputIndex != null && preferredOutputIndex != index) continue;
            TransactionOutput out = outputs.get(index);
            if (!Arrays.equals(payerBytes, new Address(out.getAddress()).getBytes())) continue;
            if (out.getScriptRef() != null || out.getInlineDatum() != null || out.getDatumHash() != null) continue;
            if (out.getValue() == null || out.getValue().getCoin() == null
                    || out.getValue().getCoin().compareTo(BigInteger.valueOf(minimumInputLovelace)) < 0
                    || out.getValue().getMultiAssets() != null && !out.getValue().getMultiAssets().isEmpty()) continue;
            return Utxo.builder().address(out.getAddress()).txHash(mintHash).outputIndex(index)
                    .amount(ValueUtil.toAmountList(out.getValue())).build();
        }
        return null;
    }
}
