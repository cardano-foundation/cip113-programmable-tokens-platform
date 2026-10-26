package org.cardanofoundation.cip113.ccl;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.programmabletoken.cip113.SmartWalletKey;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;

import java.util.Objects;

/**
 * The platform's deployment record, described for cardano-client-lib.
 *
 * <p>A {@link Cip113Deployment} is protocol identity: every hash in it is compiled into the
 * transactions CCL builds. Nothing here is derived or defaulted except the two values the record
 * does not carry, both called out below. {@code DeploymentMapperTest} pins the result against
 * CCL's own {@code Cip113Deployments} for every public network.
 */
public final class DeploymentMapper {

    /**
     * Output index of the issuance-template UTxO in the genesis transaction.
     *
     * <p>The record does not carry it. Every v0.0.1 genesis (the SDK ceremony, CCL's and this
     * platform's devnet recipe) emits coordination UTxO #0, registry origin #1, template #2, and
     * CCL's built-in descriptors use the same value. Without it CCL would have to find the template
     * by its NFT holder, which not every backend can answer.
     */
    static final int ISSUANCE_TEMPLATE_OUTPUT_INDEX = 2;

    private DeploymentMapper() {}

    public static Cip113Deployment toDeployment(ProtocolBootstrapParams record, Network network) {
        Objects.requireNonNull(record, "record");
        String dispatcherCompiledAgainst = record.programmableLogicGlobal().unfrackingParameter();
        var builder = Cip113Deployment.builder()
                .network(network)
                .bootstrapTxHash(record.txHash())
                .paramsPolicy(record.protocolParams().policyId())
                .programmableLogicBaseHash(record.programmableLogicBase().scriptHash())
                .programmableLogicGlobalHash(record.programmableLogicGlobal().scriptHash())
                .issuanceLogicHash(record.issuanceLogic().scriptHash())
                .transferScriptHash(record.transfer().scriptHash())
                .thirdPartyScriptHash(record.thirdParty().scriptHash())
                .unfrackingScriptHash(record.unfracking().scriptHash())
                // The dispatcher can only unfrack if it was compiled against the real unfracking
                // script; the public deployments were compiled against the disabled sentinel.
                .unfrackingEnabled(dispatcherCompiledAgainst != null
                        && dispatcherCompiledAgainst.equalsIgnoreCase(record.unfracking().scriptHash()))
                .upgradeScriptHash(record.upgradeMultisig().scriptHash())
                .registryNodeCs(record.registry().scriptHash())
                .issuanceCborHexCs(record.issuance().policyId())
                .maxInlineDatumBytes(Math.toIntExact(record.maxInlineDatumBytes()))
                // The platform, its frontend and its indexer keep every holder's tokens at
                // (base script, stake credential); CCL must spend and sign for that wallet.
                .smartWalletKey(SmartWalletKey.STAKE)
                .issuanceTemplateRef(input(new TxInput(record.txHash(), ISSUANCE_TEMPLATE_OUTPUT_INDEX)));
        reference(builder, record.programmableLogicBase().scriptHash(), record.programmableBaseRefInput());
        reference(builder, record.programmableLogicGlobal().scriptHash(), record.programmableLogicGlobalRefInput());
        reference(builder, record.transfer().scriptHash(), record.transferRefInput());
        reference(builder, record.thirdParty().scriptHash(), record.thirdPartyRefInput());
        reference(builder, record.unfracking().scriptHash(), record.unfrackingRefInput());
        reference(builder, record.issuanceLogic().scriptHash(), record.issuanceLogicRefInput());
        reference(builder, record.upgradeMultisig().scriptHash(), record.upgradeMultisigRefInput());
        return builder.build();
    }

    private static void reference(Cip113Deployment.Cip113DeploymentBuilder builder, String scriptHash,
                                  TxInput where) {
        if (where != null) builder.referenceScript(scriptHash, input(where));
    }

    private static TransactionInput input(TxInput in) {
        return TransactionInput.builder().transactionId(in.txHash()).index(in.outputIndex()).build();
    }
}
