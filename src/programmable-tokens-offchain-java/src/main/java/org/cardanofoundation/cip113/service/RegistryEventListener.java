package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.yaci.store.utxo.domain.AddressUtxoEvent;
import com.easy1staking.cardano.model.AssetType;
import com.easy1staking.util.Pair;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.entity.ProtocolParamsEntity;
import org.cardanofoundation.cip113.entity.RegistryNodeEntity;
import org.cardanofoundation.cip113.model.onchain.PlutusCredentialCodec;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class RegistryEventListener {

    private final RegistryService registryService;
    private final RegistryNodeParser registryNodeParser;
    private final ProtocolParamsService protocolParamsService;
    private final SubstandardResolver substandardResolver;
    private final ProgrammableTokenRegistryRepository programmableTokenRegistryRepository;

    private static final int POLICY_ID_HEX_LENGTH = 56;

    /** CIP-67 label 100 — the reference token, which holds metadata rather than value. */
    private static final String CIP67_REFERENCE_LABEL = "000643b0";

    @EventListener
    public void processEvent(AddressUtxoEvent addressUtxoEvent) {
        log.debug("Processing AddressUtxoEvent for registry nodes");

        // Get all protocol params to know all registryNodePolicyIds
        // FIXME: this is super slow, list of protocol params should be updated on an event based and kept in memory
        List<ProtocolParamsEntity> allProtocolParams = protocolParamsService.getAll();
        if (allProtocolParams.isEmpty()) {
            log.debug("No protocol params loaded yet, skipping registry indexing");
            return;
        }

        // Create map of registryNodePolicyId -> ProtocolParamsEntity for quick lookup
        Map<String, ProtocolParamsEntity> policyIdToProtocolParams = allProtocolParams.stream()
                .collect(Collectors.toMap(ProtocolParamsEntity::getRegistryNodePolicyId, Function.identity()));

        var directoryNftPolicyIds = policyIdToProtocolParams.keySet()
                .stream()
                .map(AssetType::fromUnit)
                .map(AssetType::policyId)
                .toList();

        log.debug("Monitoring {} registry policy IDs: {}",
                policyIdToProtocolParams.size(),
                String.join(", ", policyIdToProtocolParams.keySet()));

        var slot = addressUtxoEvent.getEventMetadata().getSlot();
        var blockHeight = addressUtxoEvent.getEventMetadata().getBlock();

        // Registration mints the token and writes its registry node in ONE transaction, so the
        // asset name is available here without a second lookup. Collected per transaction
        // because the event may carry several.
        // OUTPUTS only. TxInputOutput.getInputs() is a List<TxInput> -- a (txHash, index)
        // reference with no amounts -- so an input's policies are not visible from this event
        // without resolving each one against the utxo store. Whatever the registering
        // transaction produced is all we get to see here.
        Map<String, List<String>> unitsByTxHash = addressUtxoEvent.getTxInputOutputs().stream()
                .collect(Collectors.toMap(
                        txIo -> txIo.getTxHash(),
                        txIo -> txIo.getOutputs().stream()
                                .filter(o -> o.getAmounts() != null)
                                .flatMap(o -> o.getAmounts().stream())
                                .map(a -> a.getUnit())
                                .filter(u -> u != null)
                                .distinct()
                                .toList(),
                        (a, b) -> a));

        // Process each transaction's outputs
        addressUtxoEvent.getTxInputOutputs()
                .stream()
                .flatMap(txInputOutputs -> txInputOutputs.getOutputs().stream())
                .flatMap(output -> {
                    if (output.getInlineDatum() != null) {
                        return output.getAmounts()
                                .stream()
                                .filter(amt -> amt.getQuantity().equals(BigInteger.ONE) && directoryNftPolicyIds.contains(AssetType.fromUnit(amt.getUnit()).policyId()))
                                .map(amt -> new Pair<>(output, amt));
                    } else {
                        return Stream.empty();
                    }
                })
                .forEach(pair -> {
                    var output = pair.first();
                    var amt = AssetType.fromUnit(pair.second().getUnit());
                    String txHash = output.getTxHash();

                    var protocolParams = policyIdToProtocolParams.get(amt.policyId());

                    log.info("Found registry node UTxO: txHash={}, slot={}, protocolParamsId={}",
                            txHash, slot, protocolParams.getId());

                    // Parse inline datum to RegistryNode
                    registryNodeParser.parse(output.getInlineDatum())
                            .ifPresentOrElse(registryNode -> {

                                        log.info("registryNode: {}", registryNode);

                                        // Create entity
                                        RegistryNodeEntity entity = RegistryNodeEntity.builder()
                                                .key(registryNode.key())
                                                .next(registryNode.next())
                                                .mintingLogicScript(PlutusCredentialCodec.hex(registryNode.mintingLogicScript()))
                                                .transferLogicScript(PlutusCredentialCodec.hex(registryNode.transferLogicScript()))
                                                .thirdPartyTransferLogicScript(PlutusCredentialCodec.hex(registryNode.thirdPartyTransferLogicScript()))
                                                .unfrackingLogicScript(PlutusCredentialCodec.hex(registryNode.unfrackingLogicScript()))
                                                .globalStatePolicyId(registryNode.globalStatePolicyId())
                                                .protocolParams(protocolParams)
                                                .txHash(txHash)
                                                .slot(slot)
                                                .blockHeight(blockHeight)
                                                .isDeleted(false)
                                                .build();

                                        // Insert into append-only log
                                        registryService.insert(entity);
                                        log.info("Successfully inserted registry node state: key={}, next={}, slot={}, tx={}",
                                                registryNode.key(), registryNode.next(), slot, txHash);

                                        // Check for deleted nodes between this node and its next pointer
                                        // If the smart contract skipped nodes (current -> next), those nodes were deleted
                                        registryService.deleteOrphanedNodes(
                                                registryNode.key(),
                                                registryNode.next(),
                                                protocolParams.getId(),
                                                slot,
                                                blockHeight,
                                                txHash
                                        );

                                        indexProgrammableToken(entity, protocolParams,
                                                unitsByTxHash.getOrDefault(txHash, List.of()));
                                    },
                                    () -> log.error("Failed to parse registry node from txHash={}", txHash)
                            );
                });
    }

    /**
     * Index the token itself, so it is discoverable without the registration callback.
     *
     * <p>{@code programmable_token_registry} is what {@code GET /token-context/{policyId}}
     * answers from, and it used to be written ONLY by {@code POST /token-context/register} --
     * a callback the registering frontend makes to whichever backend it was pointed at. A token
     * minted against one deployment was therefore invisible to every other, including the public
     * indexer, and no amount of re-syncing helped because the row was never derived from chain
     * in the first place.
     *
     * <p>It is derived here instead. The callback is left in place: it still arrives first for
     * locally-built registrations, and it carries the freeze-and-seize init details that no
     * registry node holds. This is the backstop that makes a wiped database rebuild the answer
     * from the chain rather than from whoever happened to call.
     *
     * <p>Writes nothing when the substandard cannot be identified. A row with an unknown
     * substandard would turn an honest 404 into a 200 the SDK cannot route.
     */
    private void indexProgrammableToken(RegistryNodeEntity node,
                                        ProtocolParamsEntity protocolParams,
                                        List<String> unitsInTx) {
        var policyId = node.getKey();
        if (policyId == null || policyId.isBlank()) {
            return;
        }
        if (programmableTokenRegistryRepository.existsByPolicyId(policyId)) {
            return;
        }

        // Every policy the transaction touched, as candidate second-parameters for a substandard
        // whose registry node carries no global state of its own.
        var candidatePolicyIds = unitsInTx.stream()
                .filter(unit -> unit.length() >= POLICY_ID_HEX_LENGTH)
                .map(unit -> unit.substring(0, POLICY_ID_HEX_LENGTH))
                .distinct()
                .toList();

        var substandardId = substandardResolver.resolve(
                protocolParams.getProgLogicScriptHash(),
                node.getGlobalStatePolicyId(),
                node.getTransferLogicScript(),
                candidatePolicyIds);

        if (substandardId.isEmpty()) {
            log.info("Registry node {} indexed, but no substandard reproduces its transfer logic "
                            + "{} (globalState={}) -- not adding a token-context row. rwa-token is "
                            + "expected here: its transfer logic takes a denylist hash no registry "
                            + "node carries.",
                    policyId, node.getTransferLogicScript(), node.getGlobalStatePolicyId());
            return;
        }

        // The asset name as the chain carries it: the unit is policyId || assetNameHex.
        //
        // A CIP-68 registration mints TWO assets under one policy: the (100) reference token
        // holding the metadata, and the (222) user token holding the value. Taking whichever
        // appeared first stored the reference name roughly half the time, which is not the name
        // anybody means by "the token". Prefer anything that is not the reference token.
        var ownAssetNames = unitsInTx.stream()
                .filter(unit -> unit.length() > policyId.length() && unit.startsWith(policyId))
                .map(unit -> unit.substring(policyId.length()))
                .distinct()
                .toList();
        var assetNameHex = ownAssetNames.stream()
                .filter(name -> !name.toLowerCase().startsWith(CIP67_REFERENCE_LABEL))
                .findFirst()
                .or(() -> ownAssetNames.stream().findFirst())
                .orElse("");

        programmableTokenRegistryRepository.save(ProgrammableTokenRegistryEntity.builder()
                .policyId(policyId)
                .substandardId(substandardId.get())
                .assetName(assetNameHex)
                .build());

        log.info("Indexed programmable token from chain: policyId={}, substandardId={}, assetName={}",
                policyId, substandardId.get(), assetNameHex.isEmpty() ? "(not in this tx)" : assetNameHex);
    }
}
