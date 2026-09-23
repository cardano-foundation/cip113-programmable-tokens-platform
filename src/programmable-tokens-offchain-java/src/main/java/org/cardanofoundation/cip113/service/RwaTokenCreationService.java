package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import org.cardanofoundation.cip113.model.RwaTokenRegisterRequest;
import org.cardanofoundation.cip113.model.TransactionContext;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.repository.RwaGenesisFundingReservationRepository;
import org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository;
import org.cardanofoundation.cip113.service.module.ModuleHandlerFactory;
import org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler;
import org.cardanofoundation.cip113.service.module.context.RwaTokenContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** A returned unsigned creation chain and all its database side effects commit together. */
@Service
@RequiredArgsConstructor
public class RwaTokenCreationService {
    private final ModuleHandlerFactory handlerFactory;
    private final UtxoProvider utxoProvider;
    private final RwaTokenRegistrationRepository registrations;
    private final RwaGenesisFundingReservationRepository fundingReservations;

    public static class BuildFailed extends RuntimeException {
        public BuildFailed(String message) { super(message); }
    }

    @Transactional
    public RwaTokenModuleHandler.ChainBuildResult buildChain(RwaTokenRegisterRequest request,
                                                               ProtocolBootstrapParams params) {
        var handler = (RwaTokenModuleHandler) handlerFactory.getHandler("rwa-token", RwaTokenContext.emptyContext());
        var result = handler.buildFullRegistrationChain(request, params);
        if (!result.isSuccessful() || result.metadata() == null)
            throw new BuildFailed(result.error() == null ? "chain build failed" : result.error());
        var meta = result.metadata();
        List<String> txs = new ArrayList<>();
        txs.add(meta.genesisCborHex());
        txs.add(meta.addPowerUserCborHex());
        if (meta.publishScriptsCborHex() != null) txs.add(meta.publishScriptsCborHex());
        txs.add(meta.registrationCborHex());
        if (meta.registerTransferLogicCborHex() != null) txs.add(meta.registerTransferLogicCborHex());
        if (meta.registerThirdPartyTransferLogicCborHex() != null)
            txs.add(meta.registerThirdPartyTransferLogicCborHex());
        reserveExternalWalletInputs(txs, request.getFeePayerAddress(), meta.globalStatePolicyId());
        return meta;
    }

    public record InitResult(String unsignedCborTx, TransactionContext.RegistrationResult metadata) {}

    @Transactional
    public InitResult init(RwaTokenRegisterRequest request, ProtocolBootstrapParams params) {
        var handler = (RwaTokenModuleHandler) handlerFactory.getHandler("rwa-token", RwaTokenContext.emptyContext());
        var result = handler.buildGlobalStateInitTransaction(request, params);
        if (!result.isSuccessful() || result.metadata() == null)
            throw new BuildFailed(result.error() == null ? "init failed" : result.error());
        String gsPolicy = registrations.findByProgrammableTokenPolicyId(result.metadata().policyId())
                .orElseThrow(() -> new BuildFailed("genesis registration row missing"))
                .getGlobalStatePolicyId();
        reserveExternalWalletInputs(List.of(result.unsignedCborTx()), request.getFeePayerAddress(), gsPolicy);
        return new InitResult(result.unsignedCborTx(), result.metadata());
    }

    private void reserveExternalWalletInputs(List<String> cborHexes, String payerAddress, String gsPolicy) {
        try {
        Set<String> currentPayerRefs = new HashSet<>();
        for (var utxo : utxoProvider.findAllCurrentUtxosFromBlockfrost(payerAddress)) {
            if (payerAddress.equals(utxo.getAddress()))
                currentPayerRefs.add(ref(utxo.getTxHash(), utxo.getOutputIndex()));
        }
        Set<String> previousOutputRefs = new HashSet<>();
        Set<String> walletRefs = new HashSet<>();
        for (String cborHex : cborHexes) {
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
            var body = tx.getBody();
            List<TransactionInput> spent = new ArrayList<>();
            if (body.getInputs() != null) spent.addAll(body.getInputs());
            if (body.getCollateral() != null) spent.addAll(body.getCollateral());
            for (TransactionInput input : spent) {
                String inputRef = ref(input.getTransactionId(), input.getIndex());
                if (previousOutputRefs.contains(inputRef)) continue;
                if (currentPayerRefs.contains(inputRef)) {
                    walletRefs.add(inputRef);
                    continue;
                }
                // External non-wallet inputs must resolve to a script credential in
                // independently indexed protocol state (e.g. the CIP-113 directory).
                var protocolUtxo = utxoProvider.findUtxo(input.getTransactionId(), input.getIndex())
                        .orElseThrow(() -> new BuildFailed("unknown external creation input: " + inputRef));
                byte[] addressBytes = new Address(protocolUtxo.getAddress()).getBytes();
                int addressType = (addressBytes[0] >>> 4) & 15;
                if (!(addressType == 1 || addressType == 3 || addressType == 5 || addressType == 7))
                    throw new BuildFailed("unexpected external wallet input in creation chain: " + inputRef);
            }
            String txHash = TransactionUtil.getTxHash(tx.serialize());
            for (int i = 0; i < body.getOutputs().size(); i++) previousOutputRefs.add(ref(txHash, i));
        }
        for (String inputRef : walletRefs.stream().sorted().toList()) {
            var existing = fundingReservations.findById(inputRef);
            if (existing.isPresent()) {
                if (!gsPolicy.equals(existing.get().getGlobalStatePolicyId()))
                    throw new BuildFailed("wallet funding input belongs to another creation attempt: " + inputRef);
            } else if (fundingReservations.claim(inputRef, gsPolicy) != 1) {
                throw new BuildFailed("wallet funding input was reserved concurrently: " + inputRef);
            }
        }
        } catch (BuildFailed e) {
            throw e;
        } catch (Exception e) {
            throw new BuildFailed("could not audit creation funding inputs: " + e.getMessage());
        }
    }

    private static String ref(String txHash, int index) {
        return txHash.toLowerCase(Locale.ROOT) + "#" + index;
    }
}
