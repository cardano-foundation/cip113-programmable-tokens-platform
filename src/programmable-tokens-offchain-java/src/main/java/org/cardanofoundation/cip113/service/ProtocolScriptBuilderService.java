package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.core.CoreScriptFactory;
import org.cardanofoundation.cip113.core.CoreValidator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.springframework.stereotype.Service;

/**
 * Names the core scripts the way the existing callers ask for them.
 *
 * <p>This used to <em>be</em> the parameterisation layer: eight near-identical methods,
 * each with its own blueprint-title literal, its own parameter list, its own
 * {@code Optional} unwrap and its own try/catch around a debug log. All of that now lives
 * in {@link CoreScriptFactory}, where the parameter lists sit together in one switch and
 * can be read against the blueprint as a table. What remains here is the vocabulary:
 * {@code getParameterizedDirectoryMintScript} and friends are called from every
 * substandard handler, and renaming them is a separate change from moving the knowledge
 * out of them.
 *
 * <p>Kept deliberately thin. New code should depend on {@link CoreScriptFactory} directly;
 * this class exists so that migrating the handlers can be done one at a time, with the
 * script hashes proven unchanged at every step by
 * {@code ProtocolScriptBuilderServiceHashDerivationTest}.
 *
 * <p>Note the vocabulary itself predates the contracts it describes: "directory" is the
 * old name for what upstream now calls the registry, so {@code DirectoryMint} is
 * {@code registry_mint} and {@code DirectorySpend} is {@code registry_spend}. The mapping
 * below is the only place that has to be known.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolScriptBuilderService {

    private final CoreScriptFactory coreScripts;

    /** {@code registry_mint} — the registry-node NFT policy. */
    public PlutusScript getParameterizedDirectoryMintScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.REGISTRY_MINT, protocolParams);
    }

    /** {@code registry_spend} — spends registry nodes (insert, in-place update). */
    public PlutusScript getParameterizedDirectorySpendScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.REGISTRY_SPEND, protocolParams);
    }

    /**
     * {@code issuance_mint} for one substandard — its policy id is the registered token's
     * identity, because the substandard's minting-logic credential is baked into it.
     */
    public PlutusScript getParameterizedIssuanceMintScript(ProtocolBootstrapParams protocolParams,
                                                           PlutusScript substandardIssueScript) {
        return coreScripts.issuanceMint(protocolParams, substandardIssueScript);
    }

    /** {@code programmable_logic_base} — the payment credential of every programmable address. */
    public PlutusScript getParameterizedProgrammableLogicBaseScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.PROGRAMMABLE_LOGIC_BASE, protocolParams);
    }

    /**
     * {@code transfer} — the withdraw-0 validator every ordinary transfer invokes.
     *
     * <p>This replaces {@code getParameterizedProgrammableLogicGlobalScript}. The rename is
     * not cosmetic and the method was deliberately not kept under the old name: the
     * coordinator it used to return authorised transfers, seizures AND unfracking, and this
     * one authorises transfers only. Every former caller has to say which of the three it
     * meant, and a compile error at each call site is how that question gets asked.
     */
    public PlutusScript getParameterizedTransferScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.TRANSFER, protocolParams);
    }

    /**
     * {@code third_party} — the withdraw-0 validator authorising seizure, clawback, freeze
     * enforcement and burn. Previously the {@code ThirdPartyAct} arm inside the coordinator;
     * now its own script, which a transfer transaction never loads.
     */
    public PlutusScript getParameterizedThirdPartyScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.THIRD_PARTY, protocolParams);
    }

    /** {@code unfracking} — holder-driven same-owner restructuring. Deployed, not yet exposed. */
    public PlutusScript getParameterizedUnfrackingScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.UNFRACKING, protocolParams);
    }

    /** {@code always_fail} under a caller-chosen nonce. */
    public PlutusScript getParameterizedAlwaysFailScript(String nonce) {
        return coreScripts.alwaysFail(nonce);
    }

    /** {@code protocol_params_mint} — the one-shot protocol-params NFT policy. */
    public PlutusScript getParameterizedProtocolParamsMintScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.PROTOCOL_PARAMS_MINT, protocolParams);
    }

    /** {@code issuance_cbor_hex_mint} — the one-shot NFT carrying the issuance template bytes. */
    public PlutusScript getParameterizedIssuanceCborHexMintScript(ProtocolBootstrapParams protocolParams) {
        return coreScripts.script(CoreValidator.ISSUANCE_CBOR_HEX_MINT, protocolParams);
    }
}
