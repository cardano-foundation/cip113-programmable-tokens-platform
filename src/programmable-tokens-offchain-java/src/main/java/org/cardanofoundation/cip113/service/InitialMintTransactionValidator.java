package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.model.MintAttestationRequest;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.cardanofoundation.cip113.service.module.ReservedFeePayerOutput;
import org.cardanofoundation.cip113.util.Cip68;
import java.math.BigInteger;
import java.util.*;

/**
 * First-mint registration allows its directory NFT and optional CIP-68 pair, nothing else. The registration
 * carries the CIP-170 v1.1 ATTEST_TX record; no other transaction in the chain carries label 170.
 */
public final class InitialMintTransactionValidator {
    private InitialMintTransactionValidator() {}

    /**
     * @param signerAid    AID named by the registration's ATTEST_TX record (always required)
     * @param sealDigest   transaction seal anchored for the registration, or null before it is computed
     * @param networkMagic network magic the seal was computed for
     */
    public static void validate(RwaTokenModuleHandler.ChainBuildResult result, MintAttestationRequest fields,
            RwaTokenRegisterRequest registration, RwaTokenModuleHandler.GenesisPlan plan,
            ProtocolBootstrapParams deployment, String signerAid, String sealDigest, long networkMagic) throws Exception {
        if (!Objects.equals(result.programmableTokenPolicyId(), fields.tokenPolicyId())
                || !Objects.equals(plan.programmableTokenPolicyId(), fields.tokenPolicyId())
                || !Objects.equals(plan.globalStatePolicyId(), result.globalStatePolicyId()))
            throw new IllegalArgumentException("Final creation policy differs from approved policy");
        List<Transaction> chain = new ArrayList<>();
        add(chain, result.genesisCborHex(), result.genesisTxHash());
        add(chain, result.addPowerUserCborHex(), result.addPowerUserTxHash());
        add(chain, result.cmtaProvenanceCborHex(), result.cmtaProvenanceTxHash());
        add(chain, result.issuanceProvenanceCborHex(), result.issuanceProvenanceTxHash());
        if (result.publishScriptsCborHex() != null) add(chain, result.publishScriptsCborHex(), result.publishScriptsTxHash());
        add(chain, result.registrationCborHex(), result.registrationTxHash());
        int registrationIndex = chain.size() - 1;
        if (result.attestationCborHex() != null || result.attestationTxHash() != null)
            throw new IllegalArgumentException("ATTEST_TX creation must not contain a CIP-170 child transaction");
        if (result.registerTransferLogicCborHex() != null) add(chain, result.registerTransferLogicCborHex(), result.registerTransferLogicTxHash());
        if (result.registerThirdPartyTransferLogicCborHex() != null)
            add(chain, result.registerThirdPartyTransferLogicCborHex(), result.registerThirdPartyTransferLogicTxHash());
        var bootstrap = plan.funding().getFirst();
        if (chain.getFirst().getBody().getInputs().stream().noneMatch(input -> input.getTransactionId().equals(bootstrap.getTxHash())
                && input.getIndex() == bootstrap.getOutputIndex())) throw new IllegalArgumentException("Genesis does not spend pinned bootstrap");
        for (int n = 1; n < chain.size(); n++) {
            var previous = chain.get(n - 1);
            String predecessor = TransactionUtil.getTxHash(previous.serialize());
            if (chain.get(n).getBody().getInputs().stream().noneMatch(input -> input.getTransactionId().equals(predecessor)
                    && input.getIndex() >= 0 && input.getIndex() < previous.getBody().getOutputs().size()))
                throw new IllegalArgumentException("Final creation chain does not spend predecessor " + (n - 1));
        }
        for (int n : List.of(2, 3)) {
            var aux = chain.get(n).getAuxiliaryData();
            if (aux == null || aux.getMetadata() == null || aux.getMetadata().get(BigInteger.valueOf(1984)) == null
                    || !Arrays.equals(aux.getAuxiliaryDataHash(), chain.get(n).getBody().getAuxiliaryDataHash()))
                throw new IllegalArgumentException("Final creation chain lost required CIP-171 record");
        }
        for (int n = 0; n < chain.size(); n++) {
            var aux = chain.get(n).getAuxiliaryData();
            if (n != registrationIndex && aux != null && aux.getMetadata() != null
                    && aux.getMetadata().get(BigInteger.valueOf(170)) != null)
                throw new IllegalArgumentException("Initial mint attestation must be on the registration only");
        }
        Transaction mint = chain.get(registrationIndex);
        if (sealDigest != null && !TxAttestationSeal.digest(result.registrationTxHash(), networkMagic).equals(sealDigest))
            throw new IllegalArgumentException("CIP-170 transaction seal does not bind the frozen registration");
        int reserved = ReservedFeePayerOutput.registrationReserveIndex(mint, result.registrationTxHash(), fields.feePayerAddress());
        if (registrationIndex + 1 < chain.size()) {
            var spent = chain.get(registrationIndex + 1).getBody().getInputs().stream()
                    .filter(input -> input.getTransactionId().equals(result.registrationTxHash())).toList();
            if (spent.size() != 1 || spent.getFirst().getIndex() != reserved)
                throw new IllegalArgumentException("Certificate transaction must spend only the registration's reserved output");
        }
        validateRegistration(Transaction.deserialize(HexUtil.decodeHexString(result.registrationCborHex())), fields,
                registration, deployment.registry().scriptHash(), deployment.programmableLogicBase().scriptHash(), signerAid);
    }
    static void validateRegistration(Transaction tx, MintAttestationRequest fields, RwaTokenRegisterRequest registration,
            String directoryPolicy, String programmableLogicHash, String signerAid) throws Exception {
        String policy = fields.tokenPolicyId();
        Map<String, BigInteger> expectedMint = new HashMap<>();
        expectedMint.put(directoryPolicy + ":" + policy, BigInteger.ONE);
        expectedMint.put(policy + ":" + fields.assetName(), new BigInteger(fields.quantity()));
        String referenceName = registration.getCip68Metadata() == null ? null : Cip68.referenceNameFor(fields.assetName());
        if (referenceName != null) expectedMint.put(policy + ":" + referenceName, BigInteger.ONE);
        Map<String, BigInteger> actualMint = new HashMap<>();
        if (tx.getBody() == null || tx.getBody().getMint() == null) throw new IllegalArgumentException("Initial mint body absent");
        for (var ma : tx.getBody().getMint()) for (var asset : ma.getAssets()) {
            String key = ma.getPolicyId() + ":" + HexUtil.encodeHexString(asset.getNameAsBytes());
            if (actualMint.put(key, asset.getValue()) != null) throw new IllegalArgumentException("Duplicate mint asset");
        }
        if (!expectedMint.equals(actualMint)) throw new IllegalArgumentException("Initial mint assets differ from approved creation");
        BigInteger delivered = BigInteger.ZERO;
        BigInteger referenceDelivered = BigInteger.ZERO;
        String referenceAddress = referenceName == null ? null : AddressProvider.getBaseAddress(
                Credential.fromScript(programmableLogicHash), Credential.fromKey(HexUtil.decodeHexString(registration.getAdminPubKeyHash())),
                new Address(fields.programmableRecipientAddress()).getNetwork()).getAddress();
        String referenceDatum = referenceName == null ? null : Cip68.buildDatum(registration.getCip68Metadata()).serializeToHex();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (output.getValue() == null || output.getValue().getMultiAssets() == null) continue;
            for (var ma : output.getValue().getMultiAssets()) {
                if (!policy.equals(ma.getPolicyId())) continue;
                for (var asset : ma.getAssets()) {
                    String name = HexUtil.encodeHexString(asset.getNameAsBytes());
                    if (name.equals(fields.assetName())) {
                        if (!sameAddress(output.getAddress(), fields.programmableRecipientAddress()))
                            throw new IllegalArgumentException("Initial mint goes to a different recipient");
                        delivered = delivered.add(asset.getValue());
                    } else if (name.equals(referenceName)) {
                        if (!sameAddress(output.getAddress(), referenceAddress) || output.getInlineDatum() == null
                                || !referenceDatum.equals(output.getInlineDatum().serializeToHex()))
                            throw new IllegalArgumentException("CIP-68 reference output differs from approved metadata/admin");
                        referenceDelivered = referenceDelivered.add(asset.getValue());
                    } else throw new IllegalArgumentException("Unexpected security-policy asset in initial output");
                }
            }
        }
        if (!delivered.equals(new BigInteger(fields.quantity())) || referenceName != null && !BigInteger.ONE.equals(referenceDelivered))
            throw new IllegalArgumentException("Initial mint output quantities differ from approval");
        if (tx.getBody().getTtl() <= 0) throw new IllegalArgumentException("Initial mint must have finite expiry");
        MintAttestedTransactionValidator.requireAttestTx(tx, signerAid);
    }
    private static boolean sameAddress(String a, String b) { return Arrays.equals(new Address(a).getBytes(), new Address(b).getBytes()); }
    private static void add(List<Transaction> chain, String cbor, String hash) throws Exception {
        if (cbor == null || hash == null) throw new IllegalArgumentException("Required creation transaction missing");
        var tx = Transaction.deserialize(HexUtil.decodeHexString(cbor));
        if (!hash.equals(TransactionUtil.getTxHash(tx.serialize()))) throw new IllegalArgumentException("Creation transaction hash differs from its CBOR");
        chain.add(tx);
    }
}
