package org.cardanofoundation.cip113.devnet;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.model.TxContentUtxoOutputs;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.programmabletoken.cip113.Cip113Deployment;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.bootstrap.CredentialParams;
import org.cardanofoundation.cip113.model.bootstrap.DispatcherParams;
import org.cardanofoundation.cip113.model.bootstrap.IssuanceParams;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolParams;
import org.cardanofoundation.cip113.model.bootstrap.RegistryParams;
import org.cardanofoundation.cip113.model.bootstrap.ScriptParams;
import org.cardanofoundation.cip113.model.bootstrap.TxInput;
import org.cardanofoundation.cip113.model.bootstrap.UpgradeMultisigParams;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deploys CIP-113 core v0.0.1 onto a fresh devnet in three transactions, and describes the result
 * twice: as the platform's {@link ProtocolBootstrapParams} record and as CCL's
 * {@link Cip113Deployment}.
 *
 * <p>Ported from cardano-client-lib's integration-test bootstrap
 * ({@code programmable-token/src/it/.../cip113/tx/Cip113Bootstrap.java}): CCL ships no deployment
 * API, and the platform's {@code protocol-bootstraps-devnet.json} is empty, so without this there
 * is no devnet protocol to build against. The two blueprints are byte-identical (sha256
 * {@code b6c8cb09…}), so the recipe applies unchanged.
 *
 * <p>Two deliberate differences from CCL's recipe, both to make devnet look like the public
 * networks rather than like a test fixture:
 * <ul>
 *   <li>the dispatcher is compiled against the disabled-unfracking sentinel
 *       ({@link DispatcherParams#UNFRACKING_DISABLED}), as on Preview, Preprod and Mainnet — a
 *       devnet with unfracking enabled is exactly what hid the Preprod dispatcher bug described in
 *       {@code CoreScriptFactory};</li>
 *   <li>{@code maxInlineDatumBytes} is 1024, as on the public deployments.</li>
 * </ul>
 */
@Slf4j
final class DevnetProtocolGenesis {

    private static final String ALWAYS_FAIL_NONCE =
            "fa5b084bbdc0336c1e3c086617d99cf6ecff1a190116784a0dd54aeca948e8fe";
    /** 28-byte marker the issuance template is compiled with, then split on. */
    private static final String DUMMY_POLICY_ID =
            "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeef";
    /** {@code registry_node.sentinel_next_key}: 30 bytes, so it can never be a real key. */
    private static final String SENTINEL_NEXT_KEY =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
    private static final long MAX_INLINE_DATUM_BYTES = 1024L;
    private static final String TEMPLATE_ASSET = "IssuanceCborHex";

    private final Map<String, String> compiledCode = new LinkedHashMap<>();

    DevnetProtocolGenesis() {
        try (var in = DevnetProtocolGenesis.class.getClassLoader().getResourceAsStream("plutus.json")) {
            JsonNode blueprint = new ObjectMapper().readTree(in);
            blueprint.get("validators").forEach(v ->
                    compiledCode.put(v.get("title").asText(), v.get("compiledCode").asText()));
        } catch (Exception e) {
            throw new IllegalStateException("Could not read the bundled CIP-113 blueprint", e);
        }
    }

    /** One deployment, described for both builders. */
    record Deployed(ProtocolBootstrapParams record, Cip113Deployment deployment,
                    List<PlutusScript> appliedScripts) {}

    Deployed deploy(BackendService backend, Account admin, Network network) throws Exception {
        var utxoSupplier = new DefaultUtxoSupplier(backend.getUtxoService());

        List<Utxo> seeds = utxoSupplier.getAll(admin.baseAddress()).stream()
                .filter(u -> lovelaceOf(u).compareTo(BigInteger.valueOf(100_000_000L)) > 0)
                .limit(2)
                .toList();
        if (seeds.size() < 2) {
            throw new IllegalStateException("Genesis needs two UTxOs over 100 ADA at "
                    + admin.baseAddress() + " to seed the one-shot policies, found " + seeds.size());
        }
        Utxo utxo1 = seeds.get(0);
        Utxo utxo2 = seeds.get(1);
        PlutusData utxo1Ref = outputReference(utxo1);
        PlutusData utxo2Ref = outputReference(utxo2);

        // ---- parameters, in dependency order ------------------------------------------------
        PlutusScript alwaysFail = apply("always_fail.always_fail.spend",
                BytesPlutusData.of(HexUtil.decodeHexString(ALWAYS_FAIL_NONCE)));
        PlutusScript issuanceCborHexMint = apply("issuance_cbor_hex_mint.issuance_cbor_hex_mint.mint",
                utxo2Ref, BytesPlutusData.of(alwaysFail.getScriptHash()));
        PlutusScript registry = apply("registry.registry.mint",
                utxo2Ref, BytesPlutusData.of(issuanceCborHexMint.getScriptHash()));
        PlutusScript protocolParams = apply("protocol_params.protocol_params.mint", utxo2Ref);
        BytesPlutusData paramsPolicy = BytesPlutusData.of(protocolParams.getScriptHash());

        PlutusScript plb = apply("programmable_logic_base.programmable_logic_base.spend", paramsPolicy);
        PlutusData plbCred = scriptCredential(plb);
        BytesPlutusData registryCs = BytesPlutusData.of(registry.getScriptHash());
        BigIntPlutusData bound = BigIntPlutusData.of(MAX_INLINE_DATUM_BYTES);
        PlutusScript transfer = apply("transfer.transfer.withdraw", plbCred, registryCs, bound);
        PlutusScript thirdParty = apply("third_party.third_party.withdraw", plbCred, registryCs, bound);
        PlutusScript unfracking = apply("unfracking.unfracking.withdraw", plbCred, registryCs, bound);
        PlutusScript issuanceLogic = apply("issuance_logic.issuance_logic.withdraw",
                plbCred, registryCs, paramsPolicy, bound);
        PlutusScript plg = apply("programmable_logic_global.programmable_logic_global.withdraw",
                BytesPlutusData.of(transfer.getScriptHash()),
                BytesPlutusData.of(thirdParty.getScriptHash()),
                BytesPlutusData.of(HexUtil.decodeHexString(DispatcherParams.UNFRACKING_DISABLED)));
        PlutusScript upgradeMultisig = apply("upgrade_multisig.upgrade_multisig.mint", utxo1Ref);

        PlutusData issuanceTemplateDatum = buildIssuanceTemplate(protocolParams);
        byte[] adminKeyHash = AddressProvider.getPaymentCredentialHash(new Address(admin.baseAddress()))
                .orElseThrow();

        PlutusData coordinationDatum = ConstrPlutusData.of(0,
                scriptCredential(plg),
                scriptCredential(issuanceLogic),
                scriptCredential(transfer),
                scriptCredential(thirdParty),
                scriptCredential(upgradeMultisig),
                ConstrPlutusData.of(1));                        // pending_upgrade_cred: None
        PlutusData originNodeDatum = ConstrPlutusData.of(0,
                BytesPlutusData.of(new byte[0]),
                BytesPlutusData.of(HexUtil.decodeHexString(SENTINEL_NEXT_KEY)),
                emptyVkey(), emptyVkey(), emptyVkey(), emptyVkey(),
                BytesPlutusData.of(new byte[0]));
        PlutusData upgradeTree = ConstrPlutusData.of(0, BytesPlutusData.of(adminKeyHash));

        Address coordinationAddress = AddressProvider.getEntAddress(protocolParams, network);
        Address registryAddress = AddressProvider.getEntAddress(registry, network);
        Address alwaysFailAddress = AddressProvider.getEntAddress(alwaysFail, network);
        Address upgradeConfigAddress = AddressProvider.getEntAddress(upgradeMultisig, network);

        // ---- tx 1: upgrade authority + reward-account registrations --------------------------
        Tx authority = new Tx()
                .collectFrom(List.of(utxo1))
                .mintAsset(upgradeMultisig, new Asset(nameHex("UpgradeMultisig"), BigInteger.ONE),
                        ConstrPlutusData.of(0))
                .payToContract(upgradeConfigAddress.getAddress(),
                        List.of(Amount.ada(5), asset(upgradeMultisig, "UpgradeMultisig")),
                        upgradeTree)
                .from(admin.baseAddress());
        for (PlutusScript withdrawn : List.of(plg, transfer, thirdParty, unfracking, issuanceLogic,
                upgradeMultisig)) {
            authority.registerStakeAddress(AddressProvider.getRewardAddress(withdrawn, network).toBech32());
        }
        String authorityTx = submit(backend, admin, authority, "upgrade authority", null);
        Utxo upgradeConfig = utxoSupplier.getAll(upgradeConfigAddress.getAddress()).stream()
                .filter(u -> u.getTxHash().equals(authorityTx))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("upgrade-authority config UTxO from "
                        + authorityTx + " not visible"));

        // ---- tx 2: protocol genesis ------------------------------------------------------------
        Tx genesis = new Tx()
                .collectFrom(List.of(utxo2))
                .readFrom(upgradeConfig)
                .mintAsset(registry, new Asset("", BigInteger.ONE), ConstrPlutusData.of(0))
                .mintAsset(protocolParams, new Asset(nameHex("ProtocolParams"), BigInteger.ONE),
                        ConstrPlutusData.of(1))
                .mintAsset(issuanceCborHexMint, new Asset(nameHex(TEMPLATE_ASSET), BigInteger.ONE),
                        ConstrPlutusData.of(2))
                // The initial authority activates itself (upstream #143).
                .withdraw(AddressProvider.getRewardAddress(upgradeMultisig, network).toBech32(),
                        BigInteger.ZERO, ConstrPlutusData.of(0))
                .attachRewardValidator(upgradeMultisig)
                .payToContract(coordinationAddress.getAddress(),
                        List.of(Amount.ada(5), asset(protocolParams, "ProtocolParams")),
                        coordinationDatum)
                .payToContract(registryAddress.getAddress(),
                        List.of(Amount.ada(5), Amount.builder()
                                .unit(registry.getPolicyId()).quantity(BigInteger.ONE).build()),
                        originNodeDatum)
                .payToContract(alwaysFailAddress.getAddress(),
                        List.of(Amount.lovelace(templateLovelace(backend, alwaysFailAddress,
                                        issuanceCborHexMint, issuanceTemplateDatum)),
                                asset(issuanceCborHexMint, TEMPLATE_ASSET)),
                        issuanceTemplateDatum)
                .from(admin.baseAddress());
        String bootstrapTx = submit(backend, admin, genesis, "protocol genesis", adminKeyHash);

        // ---- tx 3: reference scripts, parked at always_fail so coin selection never spends them
        List<PlutusScript> referenceScripts = List.of(plb, plg, transfer, thirdParty, unfracking,
                issuanceLogic, upgradeMultisig);
        Tx publish = new Tx().from(admin.baseAddress());
        for (PlutusScript script : referenceScripts) {
            publish.payToAddress(alwaysFailAddress.getAddress(), Amount.ada(25), script);
        }
        String referenceTx = submit(backend, admin, publish, "reference scripts", null);

        // ---- where everything landed, read back from chain rather than assumed ---------------
        Map<String, TxInput> refByHash = new LinkedHashMap<>();
        for (TxContentUtxoOutputs output : outputsOf(backend, referenceTx)) {
            String hash = output.getReferenceScriptHash();
            if (hash != null && !hash.isEmpty())
                refByHash.put(hash, new TxInput(referenceTx, output.getOutputIndex()));
        }
        String templateUnit = issuanceCborHexMint.getPolicyId()
                + HexUtil.encodeHexString(TEMPLATE_ASSET.getBytes(StandardCharsets.UTF_8));
        String paramsUnit = protocolParams.getPolicyId()
                + HexUtil.encodeHexString("ProtocolParams".getBytes(StandardCharsets.UTF_8));
        Integer templateIdx = null, paramsIdx = null;
        for (TxContentUtxoOutputs output : outputsOf(backend, bootstrapTx)) {
            if (output.getAmount().stream().anyMatch(a -> templateUnit.equals(a.getUnit())))
                templateIdx = output.getOutputIndex();
            if (output.getAmount().stream().anyMatch(a -> paramsUnit.equals(a.getUnit())))
                paramsIdx = output.getOutputIndex();
        }
        if (templateIdx == null || paramsIdx == null)
            throw new IllegalStateException("genesis outputs not found in " + bootstrapTx);

        var record = new ProtocolBootstrapParams(
                ProtocolBootstrapParams.CURRENT_SCHEMA_VERSION,
                bootstrapTx,
                new ProtocolParams(TxInput.from(utxo2), protocolParams.getPolicyId(),
                        new TxInput(bootstrapTx, paramsIdx)),
                new ScriptParams(plb.getPolicyId()),
                new ScriptParams(transfer.getPolicyId()),
                new ScriptParams(thirdParty.getPolicyId()),
                new ScriptParams(unfracking.getPolicyId()),
                new DispatcherParams(plg.getPolicyId(), DispatcherParams.UNFRACKING_DISABLED),
                MAX_INLINE_DATUM_BYTES,
                new UpgradeMultisigParams(upgradeMultisig.getPolicyId(), TxInput.from(utxo1),
                        TxInput.from(upgradeConfig)),
                refByHash.get(upgradeMultisig.getPolicyId()),
                new CredentialParams("script", upgradeMultisig.getPolicyId()),
                new ScriptParams(issuanceLogic.getPolicyId()),
                refByHash.get(issuanceLogic.getPolicyId()),
                new IssuanceParams(TxInput.from(utxo2), issuanceCborHexMint.getPolicyId(),
                        alwaysFail.getPolicyId()),
                new RegistryParams(TxInput.from(utxo2), issuanceCborHexMint.getPolicyId(),
                        registry.getPolicyId()),
                refByHash.get(plb.getPolicyId()),
                refByHash.get(plg.getPolicyId()),
                refByHash.get(transfer.getPolicyId()),
                refByHash.get(thirdParty.getPolicyId()),
                refByHash.get(unfracking.getPolicyId()));

        Cip113Deployment.Cip113DeploymentBuilder deployment = Cip113Deployment.builder()
                .network(network)
                .bootstrapTxHash(bootstrapTx)
                .paramsPolicy(protocolParams.getPolicyId())
                .programmableLogicBaseHash(plb.getPolicyId())
                .programmableLogicGlobalHash(plg.getPolicyId())
                .issuanceLogicHash(issuanceLogic.getPolicyId())
                .transferScriptHash(transfer.getPolicyId())
                .thirdPartyScriptHash(thirdParty.getPolicyId())
                .unfrackingScriptHash(unfracking.getPolicyId())
                .unfrackingEnabled(false)
                .upgradeScriptHash(upgradeMultisig.getPolicyId())
                .registryNodeCs(registry.getPolicyId())
                .issuanceCborHexCs(issuanceCborHexMint.getPolicyId())
                .maxInlineDatumBytes((int) MAX_INLINE_DATUM_BYTES)
                // The platform keys smart wallets by the holder's stake credential.
                .smartWalletKey(com.bloxbean.cardano.client.programmabletoken.cip113.SmartWalletKey.STAKE)
                .issuanceTemplateRef(TransactionInput.builder()
                        .transactionId(bootstrapTx).index(templateIdx).build());
        refByHash.forEach((hash, in) -> deployment.referenceScript(hash,
                TransactionInput.builder().transactionId(in.txHash()).index(in.outputIndex()).build()));

        log.info("[devnet genesis] authority {} genesis {} reference scripts {}",
                authorityTx, bootstrapTx, referenceTx);
        return new Deployed(record, deployment.build(),
                List.of(alwaysFail, protocolParams, plb, plg, transfer, thirdParty, unfracking,
                        issuanceLogic, upgradeMultisig, issuanceCborHexMint, registry));
    }

    // ------------------------------------------------------------------------ helpers

    private static String submit(BackendService backend, Account admin, Tx tx, String what,
                                 byte[] requiredSigner) {
        QuickTxBuilder.TxContext context = new QuickTxBuilder(backend)
                .compose(tx)
                .feePayer(admin.baseAddress())
                .withSigner(SignerProviders.signerFrom(admin))
                .withTxEvaluator(new AikenTransactionEvaluator(backend))
                .mergeOutputs(false);
        if (requiredSigner != null) context = context.withRequiredSigners(requiredSigner);
        Result<String> result = context.completeAndWait(log::info);
        if (!result.isSuccessful())
            throw new IllegalStateException("devnet genesis " + what + " failed: " + result.getResponse());
        return result.getValue();
    }

    private static List<TxContentUtxoOutputs> outputsOf(BackendService backend, String txHash)
            throws Exception {
        var result = backend.getTransactionService().getTransactionUtxos(txHash);
        if (!result.isSuccessful())
            throw new IllegalStateException("Could not read " + txHash + ": " + result.getResponse());
        return result.getValue().getOutputs();
    }

    /** Ledger-exact min-UTxO for the template output, rounded up to whole ADA plus one. */
    private static BigInteger templateLovelace(BackendService backend, Address address,
                                               PlutusScript policy, PlutusData datum) throws Exception {
        var output = TransactionOutput.builder()
                .address(address.getAddress())
                .value(Value.builder()
                        .coin(MinAdaCalculator.DUMMY_COIN_VAL)
                        .multiAssets(List.of(MultiAsset.builder()
                                .policyId(policy.getPolicyId())
                                .assets(List.of(new Asset(nameHex(TEMPLATE_ASSET), BigInteger.ONE)))
                                .build()))
                        .build())
                .inlineDatum(datum)
                .build();
        BigInteger minAda = new MinAdaCalculator(
                backend.getEpochService().getProtocolParameters().getValue()).calculateMinAda(output);
        BigInteger oneAda = BigInteger.valueOf(1_000_000L);
        return minAda.add(oneAda).add(oneAda).subtract(BigInteger.ONE).divide(oneAda).multiply(oneAda);
    }

    /**
     * {@code issuance_mint} compiled with a marker credential and split on it. Every token's
     * policy id is {@code blake2b_224(0x03 ‖ prefix ‖ credentialHash ‖ postfix)}, so the split is
     * checked against a real application before it goes on chain.
     */
    private PlutusData buildIssuanceTemplate(PlutusScript protocolParamsMint) throws Exception {
        String body = HexUtil.encodeHexString(issuanceMint(DUMMY_POLICY_ID, protocolParamsMint)
                .serializeScriptBody());
        String[] parts = body.split(DUMMY_POLICY_ID);
        if (parts.length != 2 || parts[1].isEmpty())
            throw new IllegalStateException("issuance template marker must split the script in two");
        String probe = "0123456789abcdef0123456789abcdef0123456789abcdef01234567";
        String derived = HexUtil.encodeHexString(Blake2bUtil.blake2bHash224(
                HexUtil.decodeHexString("03" + parts[0] + probe + parts[1])));
        if (!derived.equals(issuanceMint(probe, protocolParamsMint).getPolicyId()))
            throw new IllegalStateException("issuance template does not reproduce issuance_mint's policy id");
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(parts[0])),
                BytesPlutusData.of(HexUtil.decodeHexString(parts[1])));
    }

    private PlutusScript issuanceMint(String credentialHash, PlutusScript protocolParamsMint) throws Exception {
        return apply("issuance_mint.issuance_mint.mint",
                ConstrPlutusData.of(1, BytesPlutusData.of(HexUtil.decodeHexString(credentialHash))),
                BytesPlutusData.of(protocolParamsMint.getScriptHash()));
    }

    private PlutusScript apply(String title, PlutusData... params) {
        String code = compiledCode.get(title);
        if (code == null) throw new IllegalStateException("blueprint has no validator titled " + title);
        return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                AikenScriptUtil.applyParamToScript(ListPlutusData.of(params), code), PlutusVersion.v3);
    }

    private static ConstrPlutusData scriptCredential(PlutusScript script) throws Exception {
        return ConstrPlutusData.of(1, BytesPlutusData.of(script.getScriptHash()));
    }

    private static ConstrPlutusData emptyVkey() {
        return ConstrPlutusData.of(0, BytesPlutusData.of(new byte[0]));
    }

    /** V3 {@code OutputReference}: the transaction id is a bare byte string, not wrapped. */
    private static PlutusData outputReference(Utxo utxo) {
        return ConstrPlutusData.of(0,
                BytesPlutusData.of(HexUtil.decodeHexString(utxo.getTxHash())),
                BigIntPlutusData.of(utxo.getOutputIndex()));
    }

    private static String nameHex(String assetName) {
        return "0x" + HexUtil.encodeHexString(assetName.getBytes(StandardCharsets.UTF_8));
    }

    private static Amount asset(PlutusScript policy, String assetName) throws Exception {
        return Amount.asset(policy.getPolicyId(), assetName, BigInteger.ONE);
    }

    private static BigInteger lovelaceOf(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst().orElse(BigInteger.ZERO);
    }
}
