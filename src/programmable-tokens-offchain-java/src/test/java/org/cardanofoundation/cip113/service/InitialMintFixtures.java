package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.*;
import com.bloxbean.cardano.client.api.model.*;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.metadata.MetadataBuilder;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import org.cardanofoundation.cip113.model.*;
import org.cardanofoundation.cip113.model.bootstrap.*;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.cardanofoundation.cip113.util.Cip68;
import java.math.BigInteger;
import java.util.*;
import static org.mockito.Mockito.*;

final class InitialMintFixtures {
    static final String POLICY = "11".repeat(28), GS = "12".repeat(28), DIRECTORY = "13".repeat(28), PLB = "14".repeat(28);
    static final String ASSET = "746f6b656e", BOOTSTRAP = "15".repeat(32), DEPLOYMENT = "16".repeat(32), ADMIN = "17".repeat(28);
    static final String PAYER = AddressProvider.getBaseAddress(Credential.fromKey(ADMIN), Credential.fromKey("18".repeat(28)), Networks.preview()).getAddress();
    static final String DEST = AddressProvider.getBaseAddress(Credential.fromScript(PLB), Credential.fromKey("18".repeat(28)), Networks.preview()).getAddress();
    static final Cip170AttestationData APPROVAL = new Cip170AttestationData("E" + "a".repeat(43), "E" + "b".repeat(43), "1", "1.0");
    static final Cip68Metadata METADATA = new Cip68Metadata("Initial", "First mint", "INI", 0, null, null);
    static RwaTokenModuleHandler.GenesisPlan plan() { return new RwaTokenModuleHandler.GenesisPlan(GS, POLICY,
            List.of(Utxo.builder().txHash(BOOTSTRAP).outputIndex(0).address(PAYER).amount(List.of(Amount.ada(100))).build())); }
    static MintAttestationRequest fields(boolean cip68) { return new MintAttestationRequest("session", "preview", DEPLOYMENT,
            POLICY, cip68 ? Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), ASSET) : ASSET, "10", PAYER, PAYER, DEST); }
    static RwaTokenRegisterRequest registration(boolean cip68) { return RwaTokenRegisterRequest.builder().moduleId("rwa-token")
            .feePayerAddress(PAYER).recipientAddress(PAYER).assetName(ASSET).adminPubKeyHash(ADMIN)
            .initialMintQuantity("10").initialMintableAmount(100L).cip68Metadata(cip68 ? METADATA : null).build(); }
    static ProtocolBootstrapParams deployment() {
        var p = mock(ProtocolBootstrapParams.class); when(p.txHash()).thenReturn(DEPLOYMENT);
        var registry = mock(RegistryParams.class); when(registry.scriptHash()).thenReturn(DIRECTORY); when(p.registry()).thenReturn(registry); when(p.programmableLogicBase()).thenReturn(new ScriptParams(PLB)); return p;
    }
    static Transaction tx(String previousHash, boolean provenance) throws Exception {
        var body = TransactionBody.builder().inputs(List.of(TransactionInput.builder().transactionId(previousHash).index(0).build()))
                .outputs(new ArrayList<>(List.of(TransactionOutput.builder().address(PAYER).value(Value.builder().coin(BigInteger.valueOf(90_000_000)).build()).build())))
                .fee(BigInteger.valueOf(200_000)).ttl(999999999L).build();
        var tx = Transaction.builder().body(body).witnessSet(new TransactionWitnessSet()).isValid(true).build();
        if (provenance) { var m = MetadataBuilder.createMetadata(); m.put(1984L, MetadataBuilder.createMap().put("record", "test"));
            var aux = AuxiliaryData.builder().metadata(m).build(); tx.setAuxiliaryData(aux); body.setAuxiliaryDataHash(aux.getAuxiliaryDataHash()); }
        return tx;
    }
    static Transaction registrationTx(String previousHash, boolean cip68) throws Exception {
        var tx = tx(previousHash, false);
        String asset = fields(cip68).assetName();
        var securityAssets = new ArrayList<Asset>(); securityAssets.add(Asset.builder().name("0x" + asset).value(BigInteger.TEN).build());
        if (cip68) securityAssets.add(Asset.builder().name("0x" + Cip68.referenceNameFor(asset)).value(BigInteger.ONE).build());
        tx.getBody().setMint(new ArrayList<>(List.of(MultiAsset.builder().policyId(POLICY).assets(securityAssets).build(),
                MultiAsset.builder().policyId(DIRECTORY).assets(List.of(Asset.builder().name("0x" + POLICY).value(BigInteger.ONE).build())).build())));
        tx.getBody().getOutputs().add(TransactionOutput.builder().address(DEST).value(Value.builder().coin(BigInteger.valueOf(2_000_000))
                .multiAssets(List.of(MultiAsset.builder().policyId(POLICY).assets(List.of(securityAssets.getFirst())).build())).build()).build());
        if (cip68) tx.getBody().getOutputs().add(TransactionOutput.builder().address(AddressProvider.getBaseAddress(Credential.fromScript(PLB), Credential.fromKey(ADMIN), Networks.preview()).getAddress())
                .inlineDatum(Cip68.buildDatum(METADATA)).value(Value.builder().coin(BigInteger.valueOf(3_000_000))
                        .multiAssets(List.of(MultiAsset.builder().policyId(POLICY).assets(List.of(securityAssets.getLast())).build())).build()).build());
        return tx;
    }
    static Cip170AttestationData attestation(RwaTokenModuleHandler.ChainBuildResult chain) {
        return new Cip170AttestationData(APPROVAL.signerAid(), MintTxHashPayload.digest(chain.registrationTxHash()), "1", "1.0");
    }
    static Transaction child(Transaction registration) throws Exception {
        var child = tx(hash(registration), false);
        var a = attestationDigest(hash(registration));
        var m = MetadataBuilder.createMetadata(); m.put(170L, a);
        var aux = AuxiliaryData.builder().metadata(m).build();
        child.setAuxiliaryData(aux); child.getBody().setAuxiliaryDataHash(aux.getAuxiliaryDataHash());
        return child;
    }
    private static com.bloxbean.cardano.client.metadata.MetadataMap attestationDigest(String registrationHash) {
        return MetadataBuilder.createMap().put("t", "ATTEST").put("i", APPROVAL.signerAid())
                .put("d", MintTxHashPayload.digest(registrationHash)).put("s", "1")
                .put("v", MetadataBuilder.createMap().put("v", "1.0"));
    }
    static RwaTokenModuleHandler.ChainBuildResult chain(boolean cip68) throws Exception {
        var g = tx(BOOTSTRAP, false); var p = tx(hash(g), false); var c = tx(hash(p), true); var i = tx(hash(c), true);
        var r = registrationTx(hash(i), cip68); var child = child(r); var cert = tx(hash(child), false);
        return new RwaTokenModuleHandler.ChainBuildResult(g.serializeToHex(), p.serializeToHex(), c.serializeToHex(), i.serializeToHex(), null,
                r.serializeToHex(), child.serializeToHex(), cert.serializeToHex(), null, GS, POLICY, "19".repeat(28), "1a".repeat(28),
                hash(g), hash(p), hash(c), hash(i), null, hash(r), hash(child), hash(cert), null);
    }
    static String hash(Transaction tx) throws Exception { return TransactionUtil.getTxHash(tx.serialize()); }
}
