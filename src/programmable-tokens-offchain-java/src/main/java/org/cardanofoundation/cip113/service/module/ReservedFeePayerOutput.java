package org.cardanofoundation.cip113.service.module;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/** Plain fee-payer outputs of an attested mint that later chain transactions may spend. */
public final class ReservedFeePayerOutput {
    /** The prepared initial-mint registration pays exactly this to the fee payer for the certificate suffix. */
    public static final long REGISTRATION_RESERVE_LOVELACE = 60_000_000L;

    private ReservedFeePayerOutput() {}

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

    /** The single plain fee-payer output of exactly {@link #REGISTRATION_RESERVE_LOVELACE}; fails on none or several. */
    public static int registrationReserveIndex(Transaction registration, String registrationHash, String payer) {
        Integer reserved = null;
        for (int n = 0; n < registration.getBody().getOutputs().size(); n++) {
            var out = registration.getBody().getOutputs().get(n);
            if (out.getValue() != null && BigInteger.valueOf(REGISTRATION_RESERVE_LOVELACE).equals(out.getValue().getCoin())
                    && fundingOutput(registration, registrationHash, payer, REGISTRATION_RESERVE_LOVELACE, n) != null) {
                if (reserved != null) throw new IllegalArgumentException("Registration has multiple reserved fee-payer outputs");
                reserved = n;
            }
        }
        if (reserved == null) throw new IllegalArgumentException("Registration has no reserved fee-payer output");
        return reserved;
    }
}
