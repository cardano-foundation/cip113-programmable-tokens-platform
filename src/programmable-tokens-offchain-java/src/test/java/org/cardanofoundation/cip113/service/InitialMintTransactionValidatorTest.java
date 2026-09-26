package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.util.HexUtil;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.cardanofoundation.cip113.service.InitialMintFixtures.*;

class InitialMintTransactionValidatorTest {
    @Test void validatesCompleteChainForPlainAndCip68InitialMint() throws Exception {
        for (boolean cip68 : List.of(false, true)) {
            var built = chain(cip68);
            InitialMintTransactionValidator.validate(built, fields(cip68), registration(cip68), plan(), deployment(), attestation(built));
        }
    }
    @Test void rejectsUnexpectedPolicyQuantityDestinationAndMissingAttestation() throws Exception {
        var extra = registrationTx(BOOTSTRAP, false);
        extra.getBody().getMint().add(MultiAsset.builder().policyId("ff".repeat(28)).assets(List.of(Asset.builder().name("x").value(BigInteger.ONE).build())).build());
        assertThrows(IllegalArgumentException.class, () -> validate(extra, false));
        var quantity = registrationTx(BOOTSTRAP, false); quantity.getBody().getMint().getFirst().getAssets().getFirst().setValue(BigInteger.ONE);
        assertThrows(IllegalArgumentException.class, () -> validate(quantity, false));
        var destination = registrationTx(BOOTSTRAP, false); destination.getBody().getOutputs().get(1).setAddress(PAYER);
        assertThrows(IllegalArgumentException.class, () -> validate(destination, false));
        var inline = registrationTx(BOOTSTRAP, false); inline.setAuxiliaryData(child(inline).getAuxiliaryData());
        inline.getBody().setAuxiliaryDataHash(inline.getAuxiliaryData().getAuxiliaryDataHash());
        assertThrows(IllegalArgumentException.class, () -> validate(inline, false));
    }
    @Test void cip68ReferenceMetadataAndOwnerMustMatchFrozenRequest() throws Exception {
        var datum = registrationTx(BOOTSTRAP, true); datum.getBody().getOutputs().getLast().setInlineDatum(Cip68.buildDatum(new org.cardanofoundation.cip113.model.Cip68Metadata("Changed", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> validate(datum, true));
        var owner = registrationTx(BOOTSTRAP, true); owner.getBody().getOutputs().getLast().setAddress(DEST);
        assertThrows(IllegalArgumentException.class, () -> validate(owner, true));
        var missing = registrationTx(BOOTSTRAP, true); missing.getBody().getMint().getFirst().getAssets().removeLast();
        assertThrows(IllegalArgumentException.class, () -> validate(missing, true));
    }
    @Test void rejectsDownstreamTransactionStillPointingToPreAttestationHash() throws Exception {
        var original = chain(false);
        var changed = Transaction.deserialize(HexUtil.decodeHexString(original.registrationCborHex()));
        changed.getBody().setFee(changed.getBody().getFee().add(BigInteger.ONE));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode json = mapper.valueToTree(original);
        json.put("registrationCborHex", changed.serializeToHex()); json.put("registrationTxHash", hash(changed));
        var staleChain = mapper.treeToValue(json, org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult.class);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                staleChain, fields(false), registration(false), plan(), deployment(), attestation(staleChain)));
    }

    @Test void rejectsDigestForAnotherMintAndChildWithExtraFundingInput() throws Exception {
        var built = chain(false);
        var wrong = new org.cardanofoundation.cip113.model.Cip170AttestationData(
                APPROVAL.signerAid(), MintTxHashPayload.digest("ff".repeat(32)), "1", "1.0");
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                built, fields(false), registration(false), plan(), deployment(), wrong));
        var child = Transaction.deserialize(HexUtil.decodeHexString(built.attestationCborHex()));
        child.getBody().getInputs().add(TransactionInput.builder().transactionId("ee".repeat(32)).index(0).build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(built);
        json.put("attestationCborHex", child.serializeToHex());
        json.put("attestationTxHash", hash(child));
        var extra = mapper.treeToValue(json, org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult.class);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                extra, fields(false), registration(false), plan(), deployment(), attestation(extra)));
    }

    private void validate(Transaction tx, boolean cip68) throws Exception {
        InitialMintTransactionValidator.validateRegistration(Transaction.deserialize(HexUtil.decodeHexString(tx.serializeToHex())), fields(cip68), registration(cip68), DIRECTORY, PLB, APPROVAL);
    }
}
