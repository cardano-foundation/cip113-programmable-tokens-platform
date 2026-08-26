package org.cardanofoundation.cip113.service;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.SubstandardValidator;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.util.Cip68;
import org.springframework.stereotype.Service;

import java.math.BigInteger;

/** Loads the ported BaFin validators from {@code rwa-token/plutus.json} and
 *  applies their script parameters. One method per validator.
 *
 *  <p>Validator naming mirrors the upstream Aiken module structure verbatim; refer
 *  to the rwa-token entry in {@code contracts-pin.json} for the upstream repository and commit.
 *
 *  <h2>Prefer {@link #resolveScripts} over the individual builders</h2>
 *  At the current pin the validators are cross-parameterised: several take the
 *  <em>script hash</em> of another validator in the same set, which in turn takes the
 *  policy id of a third. Building one script correctly therefore means building most
 *  of the set, in a specific order — and getting that order wrong does not fail
 *  loudly, it silently produces a different hash and therefore a different address,
 *  policy id or reward account than the one the chain already knows.
 *
 *  <p>{@link #resolveScripts} does that derivation once, in the only order that
 *  type-checks. The single-validator methods below remain public for the genesis
 *  path, which must build the chain incrementally because each policy id it needs is
 *  produced by the step before it. Everywhere else, ask for the bundle. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RwaTokenScriptBuilderService {

    private static final String SUBSTANDARD_ID = "rwa-token";

    /** Asset name from the ported {@code lib/constants.ak}. */
    public static final String GLOBAL_STATE_ASSET_NAME_HEX = "476c6f62616c5374617465"; // "GlobalState"

    private final SubstandardService substandardService;
    private final ProtocolScriptBuilderService protocolScriptBuilderService;

    // ── The resolved set ─────────────────────────────────────────────────────

    /** Every parameterised rwa-token script for one token, plus the derived
     *  identifiers other layers ask for. Produced by {@link #resolveScripts}.
     *
     *  <p>{@code issuanceScript} is the CIP-113 {@code issuance_mint} for this token;
     *  its policy id IS the programmable-token policy id, and is also the
     *  {@code expected_issuance_policy_id} compile-time parameter of the transfer,
     *  third-party and authority validators. */
    public record RwaTokenScripts(
            PlutusScript mintingLogicProxy,
            PlutusScript mintingAuthority,
            PlutusScript transferLogic,
            PlutusScript thirdPartyTransferLogic,
            PlutusScript globalStateSpend,
            PlutusScript denylistSpend,
            PlutusScript powerUsersSpend,
            PlutusScript issuanceScript,
            String programmableTokenPolicyId) {

        public String mintingLogicProxyHashHex() {
            return hashHex(mintingLogicProxy);
        }

        public String mintingAuthorityHashHex() {
            return hashHex(mintingAuthority);
        }
    }

    /**
     * Script hash as lower-case hex.
     *
     * <p>{@code getScriptHash()} declares {@code CborSerializationException} because a
     * {@code PlutusScript} can in general hold bytes that do not serialise. These
     * scripts cannot: every one came from {@code applyParameters}, which already
     * round-tripped the CBOR to produce them. The checked exception is therefore
     * unreachable here, and wrapping it keeps it from spreading through call sites that
     * have nothing to say about it.
     */
    private static String hashHex(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException("could not hash a parameterised rwa-token script", e);
        }
    }

    /**
     * The CIP-68 {@code (100)} reference-NFT asset name for a security asset, or the
     * security asset name itself when the token is not CIP-68.
     *
     * <p>That fallback is the contract's own documented way to switch CIP-68 OFF: the
     * authority's allowlist matches the security-asset arm first, so an equal
     * {@code reference_asset_name} makes the reference arm unreachable and no second name
     * can ever be minted under the issuance policy. Passing something arbitrary instead
     * would leave a second, unreachable-by-design name that the contract would
     * nonetheless accept at quantity 1.
     */
    public static String referenceAssetNameFor(String securityAssetNameHex) {
        return Cip68.hasLabel(securityAssetNameHex)
                ? Cip68.referenceNameFor(securityAssetNameHex)
                : securityAssetNameHex;
    }

    /** Policy id of a script, with the same unreachable-checked-exception reasoning as
     *  {@link #hashHex}. */
    private static String policyId(PlutusScript script) {
        try {
            return script.getPolicyId();
        } catch (Exception e) {
            throw new IllegalStateException("could not derive the policy id of a parameterised rwa-token script", e);
        }
    }

    /**
     * Derive the complete script set for one token.
     *
     * <p>The order below is forced by the parameter graph and is the whole reason this
     * method exists:
     *
     * <ol>
     *   <li>{@code minting_logic_script} (the permanent CIP-113 proxy) takes only
     *       {@code global_state_policy_id}, so it can be built first. That is a change
     *       from the previous pin, where it took four parameters — and it is what
     *       breaks the cycle: its hash is needed to derive the issuance policy id,
     *       which is needed by almost everything else.</li>
     *   <li>{@code issuance_mint} is parameterised on the proxy, giving
     *       {@code expected_issuance_policy_id} (= the programmable-token policy id).</li>
     *   <li>{@code power_users_validator} needs only the two policy ids, giving
     *       {@code power_user_list_script_hash}.</li>
     *   <li>{@code denylist_validator} needs only the denylist policy id, giving
     *       {@code denylist_script_hash}.</li>
     *   <li>Everything else consumes those four derived hashes.</li>
     * </ol>
     *
     * <p>Note what is NOT an input here: the minting <em>authority</em>'s hash. The
     * proxy does not name the authority at compile time — it reads
     * {@code GlobalStateDatum.minting_script_credential_hash} at run time, which is
     * exactly what makes the authority rotatable. The authority, conversely, is
     * parameterised on the proxy ({@code minting_logic_script_credential_hash}), so the
     * dependency runs one way only and a rotation never changes the proxy, the
     * issuance policy id, or the token's identity.
     */
    public RwaTokenScripts resolveScripts(String securityAssetNameHex,
                                               String globalStatePolicyId,
                                               String powerUsersPolicyId,
                                               String denylistPolicyId,
                                               ProtocolBootstrapParams protocolParams) {
        String registryPolicyId = protocolParams.directoryMintParams().scriptHash();
        // programmable_logic_base's hash is deliberately NOT read here any more. The
        // 2026-08-21 upstream dropped it from all three validators that took it: CIP-113's
        // base layer already confines the token to that credential, so re-asserting it in
        // the substandard was redundant. Reintroducing it would silently change three
        // script hashes.

        PlutusScript mintingLogicProxy = buildMintingLogicScript(globalStatePolicyId);
        PlutusScript issuanceScript =
                protocolScriptBuilderService.getParameterizedIssuanceMintScript(protocolParams, mintingLogicProxy);
        String expectedIssuancePolicyId = policyId(issuanceScript);

        PlutusScript powerUsersSpend = buildPowerUsersSpendScript(globalStatePolicyId, powerUsersPolicyId);
        String powerUserListScriptHash = hashHex(powerUsersSpend);

        PlutusScript denylistSpend = buildDenylistSpendScript(denylistPolicyId);
        String denylistScriptHash = hashHex(denylistSpend);

        PlutusScript transferLogic = buildTransferLogicScript(
                securityAssetNameHex, globalStatePolicyId,
                expectedIssuancePolicyId, denylistScriptHash);

        PlutusScript thirdPartyTransferLogic = buildThirdPartyTransferLogicScript(
                securityAssetNameHex, globalStatePolicyId,
                expectedIssuancePolicyId, denylistScriptHash, powerUserListScriptHash);

        PlutusScript mintingAuthority = buildMintingAuthorityScript(
                securityAssetNameHex, globalStatePolicyId, registryPolicyId, powerUsersPolicyId,
                hashHex(mintingLogicProxy),
                expectedIssuancePolicyId, denylistScriptHash, powerUserListScriptHash,
                referenceAssetNameFor(securityAssetNameHex));

        PlutusScript globalStateSpend = buildGlobalStateSpendScript(
                securityAssetNameHex, expectedIssuancePolicyId, globalStatePolicyId, powerUserListScriptHash);

        return new RwaTokenScripts(
                mintingLogicProxy, mintingAuthority, transferLogic, thirdPartyTransferLogic,
                globalStateSpend, denylistSpend, powerUsersSpend, issuanceScript, expectedIssuancePolicyId);
    }

    // ── Global state ─────────────────────────────────────────────────────────

    public PlutusScript buildGlobalStateMintScript(TransactionInput bootstrapTxInput) {
        SubstandardValidator contract = getContract("global_state.global_state_mint_validator.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(bootstrapTxInput.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(bootstrapTxInput.getIndex()))
        );
        return applyParameters(contract, params, "global_state_mint");
    }

    /** @param powerUserListScriptHash hash of {@link #buildPowerUsersSpendScript}. Added
     *  at the current pin: the {@code PauseTransfers} branch resolves the authorising
     *  power-user node by address rather than trusting a caller-named index, so it must
     *  know which script holds the list. */
    public PlutusScript buildGlobalStateSpendScript(String securityAssetNameHex,
                                                    String issuancePolicyId,
                                                    String globalStatePolicyId,
                                                    String powerUserListScriptHash) {
        SubstandardValidator contract = getContract("global_state.global_state_spend_validator.spend");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(issuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUserListScriptHash))
        );
        return applyParameters(contract, params, "global_state_spend");
    }

    // ── Denylist ─────────────────────────────────────────────────────────────

    /** @param powerUserListScriptHash hash of {@link #buildPowerUsersSpendScript}.
     *  Added at the current pin — so the denylist POLICY ID differs from the previous
     *  pin's even for otherwise identical inputs. */
    public PlutusScript buildDenylistMintScript(String globalStatePolicyId,
                                                TransactionInput initInputOutRef,
                                                String powerUserListScriptHash) {
        SubstandardValidator contract = getContract("denylist.mint.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUserListScriptHash))
        );
        return applyParameters(contract, params, "denylist_mint");
    }

    public PlutusScript buildDenylistSpendScript(String denylistLinkedListPolicyId) {
        SubstandardValidator contract = getContract("denylist.denylist_validator.spend");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(denylistLinkedListPolicyId))
        );
        return applyParameters(contract, params, "denylist_spend");
    }

    // ── Power users ──────────────────────────────────────────────────────────

    public PlutusScript buildPowerUsersMintScript(String globalStatePolicyId,
                                                  TransactionInput initInputOutRef) {
        SubstandardValidator contract = getContract("power_users.mint.mint");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                outputReferenceData(initInputOutRef)
        );
        return applyParameters(contract, params, "power_users_mint");
    }

    public PlutusScript buildPowerUsersSpendScript(String globalStatePolicyId,
                                                   String powerUsersLinkedListPolicyId) {
        SubstandardValidator contract = getContract("power_users.power_users_validator.spend");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersLinkedListPolicyId))
        );
        return applyParameters(contract, params, "power_users_spend");
    }

    // ── Minting proxy (CIP-113 issuance gate) ────────────────────────────────

    /**
     * The permanent, CIP-113-registered minting PROXY.
     *
     * <p>Its hash is frozen into the registry node's {@code minting_logic_script} field
     * at insert and can never be changed — {@code registry_spend} refuses to update that
     * field. Because the programmable-token policy id is derived from it, the proxy's
     * hash IS the token's identity, so anything that changed its parameters would mint a
     * different token.
     *
     * <p>It therefore takes exactly one parameter and contains no business rules. It
     * checks only that whichever script {@code GlobalStateDatum.minting_script_credential_hash}
     * currently names is also withdrawing in this transaction, and delegates every
     * mint/burn decision to it. That indirection is the upgrade path: rotating the
     * authority (see {@link #buildMintingAuthorityScript}) changes the rules without
     * touching the registry, the proxy, or the token's identity.
     *
     * <p>Consequence for every caller: a mint, burn or registration must now carry
     * <em>two</em> withdrawals — this proxy's and the current authority's — and both
     * reward accounts must be registered on chain.
     */
    public PlutusScript buildMintingLogicScript(String globalStatePolicyId) {
        SubstandardValidator contract = getContract("minting_logic_script.minting_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId))
        );
        return applyParameters(contract, params, "minting_logic");
    }

    // ── Minting authority (swappable rules behind the proxy) ─────────────────

    /**
     * The swappable minting authority named by
     * {@code GlobalStateDatum.minting_script_credential_hash}.
     *
     * <p>This is where the mint/burn rules actually live: KYC, denylist absence,
     * power-user roles, supply cap, and the CIP-113 registry-node identity check. The
     * proxy hands it nothing pre-verified — it re-resolves and re-checks every input
     * from its own redeemer.
     *
     * <p>It is parameterised on {@code minting_logic_script_credential_hash} (the proxy)
     * so it can assert that the registry node it was pointed at really is THIS token's:
     * the node names the proxy, not the authority, so checking against its own hash
     * would never match. Without that assertion a redeemer could name an unrelated
     * decoy node, making every check vacuous while a real mint proceeded unchecked.
     *
     * <p>Rotating to a different authority is an admin action
     * ({@code RotateMintingScript}); the deployment requirement, NOT enforced on chain,
     * is that the replacement genuinely decides whether a mint is allowed. Pointing the
     * field at any other withdraw-capable script in the deployment silently disables
     * every mint control — {@code transfer_logic_script} is the worst case, since its
     * withdraw-0 needs no signature at all. Verify after every rotation with a smoke
     * test that a mint lacking a power-user signature is REJECTED.
     */
    /** {@code plb_script_hash} is GONE as of the 2026-08-21 upstream (nine parameters, not
     *  ten). The destination checks no longer assert the programmable-logic base
     *  themselves — CIP-113's own base layer already confines the token there, so
     *  re-asserting it here bought nothing and cost a parameter. */
    public PlutusScript buildMintingAuthorityScript(String securityAssetNameHex,
                                                    String globalStatePolicyId,
                                                    String registryPolicyId,
                                                    String powerUsersLinkedListPolicyId,
                                                    String mintingLogicScriptCredentialHash,
                                                    String expectedIssuancePolicyId,
                                                    String denylistScriptHash,
                                                    String powerUserListScriptHash,
                                                    String referenceAssetNameHex) {
        SubstandardValidator contract = getContract("minting_authority.minting_authority_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(registryPolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUsersLinkedListPolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(mintingLogicScriptCredentialHash)),
                BytesPlutusData.of(HexUtil.decodeHexString(expectedIssuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(denylistScriptHash)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUserListScriptHash)),
                BytesPlutusData.of(HexUtil.decodeHexString(referenceAssetNameHex))
        );
        return applyParameters(contract, params, "minting_authority");
    }

    // ── Transfer logic (sender + optional receiver KYC + denylist) ───────────

    /** @param expectedIssuancePolicyId the token's own policy id, pinned at compile time
     *  rather than derived from a caller-named registry node — that derivation was the
     *  hole a redeemer could point at an attacker-chosen node to police the wrong policy.
     *  @param denylistScriptHash hash of {@link #buildDenylistSpendScript}, so covering
     *  nodes are authenticated by address rather than by policy id alone.
     *  <p>Four parameters as of the 2026-08-21 upstream: {@code registry_policy_id} and
     *  {@code plb_script_hash} were dropped for the same reasons as on the third-party
     *  validator. */
    public PlutusScript buildTransferLogicScript(String securityAssetNameHex,
                                                 String globalStatePolicyId,
                                                 String expectedIssuancePolicyId,
                                                 String denylistScriptHash) {
        SubstandardValidator contract = getContract("transfer_logic_script.transfer_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(expectedIssuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(denylistScriptHash))
        );
        return applyParameters(contract, params, "transfer_logic");
    }

    // ── Third-party transfer logic (power-user seizure / forced transfer) ────

    /**
     * The substandard's dedicated third-party transfer validator, wired into
     * {@code RegistryNode.third_party_transfer_logic_script} (index 4) by
     * {@code RwaTokenSubstandardHandler.buildRegistrationTransaction}.
     *
     * <p>Five parameters as of the 2026-08-21 upstream, down from eight.
     * {@code power_users_linked_list_policy_id}, {@code registry_policy_id} and
     * {@code plb_script_hash} are all gone: the validator no longer reads the CIP-113
     * registry node at all (the base layer already authenticates whichever node invoked
     * this withdraw-0, and registry field 4 is a FREE field a decoy node could name too),
     * and it no longer re-asserts the programmable-logic base. Every remaining parameter is
     * 28-byte hex except the asset name, so a wrong ORDER still compiles and silently
     * yields a script nobody can satisfy — keep it aligned with the .ak file.
     */
    public PlutusScript buildThirdPartyTransferLogicScript(String securityAssetNameHex,
                                                           String globalStatePolicyId,
                                                           String expectedIssuancePolicyId,
                                                           String denylistScriptHash,
                                                           String powerUserListScriptHash) {
        SubstandardValidator contract =
                getContract("third_party_transfer_logic_script.third_party_transfer_logic_validator.withdraw");
        ListPlutusData params = ListPlutusData.of(
                BytesPlutusData.of(HexUtil.decodeHexString(securityAssetNameHex)),
                BytesPlutusData.of(HexUtil.decodeHexString(globalStatePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(expectedIssuancePolicyId)),
                BytesPlutusData.of(HexUtil.decodeHexString(denylistScriptHash)),
                BytesPlutusData.of(HexUtil.decodeHexString(powerUserListScriptHash))
        );
        return applyParameters(contract, params, "third_party_transfer_logic");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Encode an OutputReference as Aiken sees it: Constr 0 [bytes txid, int idx]. */
    private static ConstrPlutusData outputReferenceData(TransactionInput in) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(in.getTransactionId())),
                BigIntPlutusData.of(BigInteger.valueOf(in.getIndex())));
    }

    private SubstandardValidator getContract(String contractPath) {
        return substandardService.getSubstandardValidator(SUBSTANDARD_ID, contractPath)
                .orElseThrow(() -> new IllegalStateException(
                        "rwa-token contract not found: " + contractPath));
    }

    private PlutusScript applyParameters(SubstandardValidator contract,
                                         ListPlutusData params,
                                         String scriptName) {
        try {
            String parameterizedCode = AikenScriptUtil.applyParamToScript(params, contract.scriptBytes());
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    parameterizedCode, PlutusVersion.v3);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build rwa-token " + scriptName + " script", e);
        }
    }
}
