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
            InitialMintTransactionValidator.validate(built, fields(cip68), registration(cip68), plan(), deployment(),
                    APPROVAL.signerAid(), sealDigest(built), MAGIC);
            InitialMintTransactionValidator.validate(built, fields(cip68), registration(cip68), plan(), deployment(),
                    APPROVAL.signerAid(), null, MAGIC);
        }
    }
    @Test void rejectsUnexpectedPolicyQuantityDestinationAndMissingAttestation() throws Exception {
        var extra = attestedRegistrationTx(BOOTSTRAP, false);
        extra.getBody().getMint().add(MultiAsset.builder().policyId("ff".repeat(28)).assets(List.of(Asset.builder().name("x").value(BigInteger.ONE).build())).build());
        assertThrows(IllegalArgumentException.class, () -> validate(extra, false));
        var quantity = attestedRegistrationTx(BOOTSTRAP, false); quantity.getBody().getMint().getFirst().getAssets().getFirst().setValue(BigInteger.ONE);
        assertThrows(IllegalArgumentException.class, () -> validate(quantity, false));
        var destination = attestedRegistrationTx(BOOTSTRAP, false); destination.getBody().getOutputs().get(1).setAddress(PAYER);
        assertThrows(IllegalArgumentException.class, () -> validate(destination, false));
        // No label 170 at all, and the retired child-profile ATTEST in place of ATTEST_TX.
        assertThrows(IllegalArgumentException.class, () -> validate(registrationTx(BOOTSTRAP, false), false));
        var inline = registrationTx(BOOTSTRAP, false); inline.setAuxiliaryData(child(inline).getAuxiliaryData());
        inline.getBody().setAuxiliaryDataHash(inline.getAuxiliaryData().getAuxiliaryDataHash());
        assertThrows(IllegalArgumentException.class, () -> validate(inline, false));
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validateRegistration(
                attestedRegistrationTx(BOOTSTRAP, false), fields(false), registration(false), DIRECTORY, PLB, "E" + "c".repeat(43)));
    }
    @Test void cip68ReferenceMetadataAndOwnerMustMatchFrozenRequest() throws Exception {
        var datum = attestedRegistrationTx(BOOTSTRAP, true); datum.getBody().getOutputs().getLast().setInlineDatum(Cip68.buildDatum(new org.cardanofoundation.cip113.model.Cip68Metadata("Changed", null, null, null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> validate(datum, true));
        var owner = attestedRegistrationTx(BOOTSTRAP, true); owner.getBody().getOutputs().getLast().setAddress(DEST);
        assertThrows(IllegalArgumentException.class, () -> validate(owner, true));
        var missing = attestedRegistrationTx(BOOTSTRAP, true); missing.getBody().getMint().getFirst().getAssets().removeLast();
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
                staleChain, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(), sealDigest(staleChain), MAGIC));
    }

    @Test void rejectsForeignSealChildTransactionAndCertificateNotSpendingReserve() throws Exception {
        var built = chain(false);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                built, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(),
                TxAttestationSeal.digest("ff".repeat(32), MAGIC), MAGIC));
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                built, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(),
                sealDigest(built), 764824073L));
        var legacy = legacyChain(false);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                legacy, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(), null, MAGIC));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        // The certificate transaction spends the registration's delivered-token output instead of its reserve.
        var cert = Transaction.deserialize(HexUtil.decodeHexString(built.registerTransferLogicCborHex()));
        cert.getBody().getInputs().getFirst().setIndex(1);
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(built);
        json.put("registerTransferLogicCborHex", cert.serializeToHex());
        json.put("registerTransferLogicTxHash", hash(cert));
        var wrongInput = mapper.treeToValue(json, org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult.class);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                wrongInput, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(), sealDigest(built), MAGIC));
        // Label 170 anywhere but the registration.
        var labelled = Transaction.deserialize(HexUtil.decodeHexString(built.registerTransferLogicCborHex()));
        labelled.setAuxiliaryData(child(labelled).getAuxiliaryData());
        labelled.getBody().setAuxiliaryDataHash(labelled.getAuxiliaryData().getAuxiliaryDataHash());
        json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(built);
        json.put("registerTransferLogicCborHex", labelled.serializeToHex());
        json.put("registerTransferLogicTxHash", hash(labelled));
        var extraLabel = mapper.treeToValue(json, org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult.class);
        assertThrows(IllegalArgumentException.class, () -> InitialMintTransactionValidator.validate(
                extraLabel, fields(false), registration(false), plan(), deployment(), APPROVAL.signerAid(), sealDigest(built), MAGIC));
    }

    private void validate(Transaction tx, boolean cip68) throws Exception {
        InitialMintTransactionValidator.validateRegistration(Transaction.deserialize(HexUtil.decodeHexString(tx.serializeToHex())), fields(cip68), registration(cip68), DIRECTORY, PLB, APPROVAL.signerAid());
    }
}
