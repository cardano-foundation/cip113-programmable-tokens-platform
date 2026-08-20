package org.cardanofoundation.cip113.devnet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams;
import org.cardanofoundation.cip113.offline.BootstrapFixture;
import org.cardanofoundation.cip113.offline.HandlerFixtures;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The security-token (RWA) paths, driven against a REAL chain.
 *
 * <p>Everything else that covers this substandard stops at script evaluation. That is a
 * lot — it scores the validators with genuine ex-units — but it is not the ledger, and the
 * rules the ledger applies on its own are exactly the ones that bite after a user has
 * signed: whether a withdrawal's reward account is registered, whether a transaction fits,
 * whether fees and collateral balance, whether the witness set is complete. Those are
 * phase-1 rules; a Plutus evaluator never sees them.
 *
 * <p>So this test submits. Each transaction is signed with the admin key, sent to the node,
 * and waited for, and the next phase only runs once the previous one is on chain.
 *
 * <h2>How to run it</h2>
 * It is skipped unless {@code CARDANO_BACKEND_URL} points at a live backend, so an ordinary
 * {@code ./gradlew test} is unaffected. Against a Yaci DevKit devnet:
 *
 * <pre>
 *   yaci-devkit up --enable-yaci-store
 *   # fund the admin + alice addresses, then deploy the protocol:
 *   CARDANO_NETWORK_MAGIC=42 CARDANO_BACKEND_URL=http://localhost:9080/api/v1/ \
 *     ./gradlew test --tests '*DevnetFundingTest*' --tests '*PreviewProtocolDeploymentMintTest*'
 *   # then this, with the deployment's txHash:
 *   CARDANO_NETWORK_MAGIC=42 CARDANO_BACKEND_URL=http://localhost:9080/api/v1/ \
 *     CIP113_BOOTSTRAP_TXHASH=&lt;deploy txHash&gt; \
 *     ./gradlew test --tests '*DevnetSecurityTokenPathsTest*'
 * </pre>
 *
 * <p>The handler is constructed by hand rather than through Spring, exactly as the offline
 * tests do — the Spring context in this project does not currently start under H2. Only the
 * chain-facing seams differ: a real backend, a real UTxO provider, real signing, real
 * submission.
 */
@Slf4j
@EnabledIfEnvironmentVariable(named = "CARDANO_BACKEND_URL", matches = ".+")
public class DevnetSecurityTokenPathsTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String BACKEND_URL = System.getenv("CARDANO_BACKEND_URL");
    private static final String BACKEND_KEY =
            System.getenv().getOrDefault("CARDANO_BACKEND_KEY", "dummy");
    private static final String BOOTSTRAP_TXHASH = System.getenv("CIP113_BOOTSTRAP_TXHASH");

    private static final String BASE_ASSET_NAME_HEX =
            HexUtil.encodeHexString("DevnetRwa".getBytes());

    private static final org.cardanofoundation.cip113.model.Cip68Metadata METADATA =
            new org.cardanofoundation.cip113.model.Cip68Metadata(
                    "Devnet RWA Token",
                    "A security token registered and minted against a live devnet",
                    "DEVRWA",
                    6,
                    "https://example.invalid/devnet-rwa",
                    "ipfs://bafkreialsoinvalidbutwellformedlookinglogohash");

    /** In-memory stand-in for the registration table, as the offline tests use. */
    private static org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository
            registrationRepository(java.util.Map<String,
                    org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity> rows) {
        var repo = org.mockito.Mockito.mock(
                org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository.class);
        org.mockito.Mockito.when(repo.save(org.mockito.Mockito.any())).thenAnswer(inv -> {
            var entity = (org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity)
                    inv.getArgument(0);
            rows.put(entity.getProgrammableTokenPolicyId(), entity);
            return entity;
        });
        org.mockito.Mockito.when(repo.findByProgrammableTokenPolicyId(org.mockito.Mockito.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(rows.get((String) inv.getArgument(0))));
        org.mockito.Mockito.when(repo.claimCip68ReferenceMint(org.mockito.Mockito.anyString()))
                .thenAnswer(inv -> {
                    var row = rows.get((String) inv.getArgument(0));
                    if (row == null || row.isCip68ReferenceMinted()) return 0;
                    row.setCip68ReferenceMinted(true);
                    return 1;
                });
        return repo;
    }

    /**
     * Time&nbsp;&harr;&nbsp;slot converters built from THIS devnet's own genesis.
     *
     * <p>These decide {@code validTo}, and every security-token path now sets one. A devnet's
     * genesis is written when the cluster is created, so its {@code systemStart} is minutes
     * old and its slots are 1s — nothing like any public network. Using a public network's era
     * history here maps "now" to a slot far beyond the node's horizon and the ledger rejects
     * the transaction at submit with {@code TimeTranslationPastHorizon}, after signing. Script
     * evaluation never sees it: evaluation does not translate time.
     *
     * <p>{@code ClasspathConversionsFactory} cannot help — it has no {@code DEV} branch and
     * throws {@code Unsupported network type: DEV} — and a checked-in genesis would be wrong
     * the moment the cluster is recreated. So the converters are assembled directly from the
     * running cluster's genesis files, which is also what a production devnet deployment would
     * have to do.
     */
    private static org.cardanofoundation.conversions.CardanoConverters devnetConverters() throws Exception {
        var clusterDir = System.getenv().getOrDefault("YACI_CLUSTER_DIR",
                System.getProperty("user.home") + "/.yaci-cli/local-clusters/default/node/genesis");
        var byron = java.nio.file.Path.of(clusterDir, "byron-genesis.json").toUri().toURL();
        var shelley = java.nio.file.Path.of(clusterDir, "shelley-genesis.json").toUri().toURL();

        var paths = new org.cardanofoundation.conversions.domain.GenesisPaths(
                org.cardanofoundation.conversions.domain.NetworkType.DEV, byron, shelley);
        var config = new org.cardanofoundation.conversions.ConversionsConfig(
                org.cardanofoundation.conversions.domain.NetworkType.DEV, paths);
        var eraHistory = org.cardanofoundation.conversions.StaticEraHistoryFactory.create(paths);
        var genesis = new org.cardanofoundation.conversions.GenesisConfig(config, eraHistory, OBJECT_MAPPER);

        var slot = new org.cardanofoundation.conversions.converters.SlotConversions(genesis);
        return new org.cardanofoundation.conversions.CardanoConverters(
                config, genesis,
                new org.cardanofoundation.conversions.converters.EpochConversions(genesis, slot),
                slot,
                new org.cardanofoundation.conversions.converters.TimeConversions(genesis, slot),
                new org.cardanofoundation.conversions.converters.EraConversions(genesis, slot));
    }

    /**
     * A processor that scores scripts with the local Aiken evaluator but submits to the real
     * node. Mirrors {@code YaciConfiguration}: evaluation must see the chain's not-yet-submitted
     * outputs, submission must reach the ledger.
     */
    private static com.bloxbean.cardano.client.api.TransactionProcessor localEvaluatingProcessor(
            BFBackendService backend,
            com.bloxbean.cardano.client.api.UtxoSupplier utxoSupplier,
            com.bloxbean.cardano.client.api.ProtocolParamsSupplier paramsSupplier,
            com.bloxbean.cardano.client.api.ScriptSupplier scriptSupplier) throws Exception {
        // A slot config, or the evaluator assumes MAINNET and rejects every transaction with a
        // validity bound before scoring it: SlotTooFarInThePast { oldest_allowed: 4492800 }.
        // Devnet slots are 1s from zero, anchored at the cluster's genesis systemStart.
        var evaluator = new com.bloxbean.cardano.aiken.AikenTransactionEvaluator(
                utxoSupplier, paramsSupplier, scriptSupplier,
                new com.bloxbean.cardano.client.common.model.SlotConfig(
                        1000, 0L, devnetSystemStartMs()));
        return new com.bloxbean.cardano.client.api.TransactionProcessor() {
            @Override
            public com.bloxbean.cardano.client.api.model.Result<
                    java.util.List<com.bloxbean.cardano.client.api.model.EvaluationResult>> evaluateTx(
                    byte[] cbor, java.util.Set<com.bloxbean.cardano.client.api.model.Utxo> inputUtxos)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                com.bloxbean.cardano.client.api.model.Result<
                        java.util.List<com.bloxbean.cardano.client.api.model.EvaluationResult>> result;
                try {
                    result = evaluator.evaluateTx(cbor, inputUtxos);
                } catch (RuntimeException | com.bloxbean.cardano.client.api.exception.ApiException e) {
                    if (System.getenv("CIP113_DUMP_ON_EVAL_FAILURE") != null) {
                        log.info("[dump] evaluation threw: {}", e.toString());
                        dumpTxShape(cbor);
                    }
                    throw e;
                }
                // DIAGNOSTIC ONLY (CIP113_DUMP_ON_EVAL_FAILURE=1): when evaluation fails,
                // dump the transaction's actual redeemer/input/reference-input shape. An
                // index error inside a validator is invisible without it — the failure names
                // a redeemer, not the field it walked off.
                if ((result == null || !result.isSuccessful())
                        && System.getenv("CIP113_DUMP_ON_EVAL_FAILURE") != null) {
                    dumpTxShape(cbor);
                }
                return result;
            }

            @Override
            public com.bloxbean.cardano.client.api.model.Result<String> submitTransaction(byte[] cborBytes)
                    throws com.bloxbean.cardano.client.api.exception.ApiException {
                return backend.getTransactionService().submitTransaction(cborBytes);
            }
        };
    }

    /** Block until the indexer can resolve {@code policyId+assetName} as a UTxO. */
    private void awaitUtxo(org.cardanofoundation.cip113.service.UtxoProvider utxoProvider,
                           String policyId, String assetNameHex, String what) throws Exception {
        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (utxoProvider.findUtxoByAsset(policyId, assetNameHex).isPresent()) {
                log.info("[devnet] {} is queryable", what);
                return;
            }
            Thread.sleep(1_000);
        }
        Assertions.fail("[devnet] " + what + " (" + policyId + assetNameHex
                + ") never became queryable — the indexer did not catch up");
    }

    /**
     * A {@link org.cardanofoundation.cip113.service.UtxoProvider} whose paginated lookups
     * are 0-based.
     *
     * <p>yaci-store's Blockfrost-shaped API pages from ZERO, while Blockfrost itself pages
     * from one. {@code findUtxoByAsset} asks for {@code page=1}, which on yaci-store is the
     * SECOND page and comes back empty — so the GlobalState NFT is never found and every
     * mint, burn and admin action fails with "GS NFT not found on chain", even though the
     * UTxO is plainly there.
     *
     * <p>Adapted HERE rather than in {@code UtxoProvider} on purpose: the 1-based call is
     * correct against real Blockfrost, which is what preview and preprod use. Which
     * convention applies is a property of the deployed backend, not of the platform, so
     * choosing one globally on the strength of one devnet would break the others. See the
     * port write-up — it needs a decision about how devnet backends are addressed.
     */
    private static org.cardanofoundation.cip113.service.UtxoProvider zeroBasedPagingUtxoProvider(
            BFBackendService backend) {
        return new org.cardanofoundation.cip113.service.UtxoProvider(backend, null) {
            @Override
            public java.util.Optional<com.bloxbean.cardano.client.api.model.Utxo> findUtxoByAsset(
                    String policyId, String assetNameHex) {
                try {
                    String unit = policyId + assetNameHex.toLowerCase();
                    var addresses = backend.getAssetService().getAssetAddresses(unit, 1, 0);
                    if (!addresses.isSuccessful() || addresses.getValue() == null
                            || addresses.getValue().isEmpty()) {
                        return java.util.Optional.empty();
                    }
                    String address = addresses.getValue().getFirst().getAddress();
                    var utxos = backend.getUtxoService().getUtxos(address, unit, 100, 0);
                    if (!utxos.isSuccessful() || utxos.getValue() == null) {
                        return java.util.Optional.empty();
                    }
                    return utxos.getValue().stream()
                            .filter(u -> u.getAmount().stream()
                                    .anyMatch(a -> unit.equalsIgnoreCase(a.getUnit())))
                            .findFirst();
                } catch (Exception e) {
                    return java.util.Optional.empty();
                }
            }
        };
    }

    /** Print the redeemers, inputs and reference inputs of a transaction that failed to evaluate. */
    private static void dumpTxShape(byte[] cbor) {
        try {
            var tx = Transaction.deserialize(cbor);
            var body = tx.getBody();
            var inputs = new java.util.ArrayList<>(body.getInputs());
            inputs.sort(java.util.Comparator
                    .comparing(com.bloxbean.cardano.client.transaction.spec.TransactionInput::getTransactionId)
                    .thenComparingInt(com.bloxbean.cardano.client.transaction.spec.TransactionInput::getIndex));
            log.info("[dump] INPUTS (ledger-sorted):");
            for (int i = 0; i < inputs.size(); i++) {
                log.info("[dump]   [{}] {}#{}", i, inputs.get(i).getTransactionId(), inputs.get(i).getIndex());
            }
            var refs = body.getReferenceInputs() == null
                    ? java.util.List.<com.bloxbean.cardano.client.transaction.spec.TransactionInput>of()
                    : new java.util.ArrayList<>(body.getReferenceInputs());
            var sortedRefs = new java.util.ArrayList<>(refs);
            sortedRefs.sort(java.util.Comparator
                    .comparing(com.bloxbean.cardano.client.transaction.spec.TransactionInput::getTransactionId)
                    .thenComparingInt(com.bloxbean.cardano.client.transaction.spec.TransactionInput::getIndex));
            log.info("[dump] REFERENCE INPUTS (ledger-sorted):");
            for (int i = 0; i < sortedRefs.size(); i++) {
                log.info("[dump]   [{}] {}#{}", i, sortedRefs.get(i).getTransactionId(), sortedRefs.get(i).getIndex());
            }
            var rs = tx.getWitnessSet() == null ? null : tx.getWitnessSet().getRedeemers();
            log.info("[dump] REDEEMERS ({}):", rs == null ? 0 : rs.size());
            if (rs != null) {
                for (var r : rs) {
                    log.info("[dump]   tag={} index={} data={}", r.getTag(), r.getIndex(),
                            r.getData() != null ? r.getData().serializeToHex() : "null");
                }
            }
            log.info("[dump] OUTPUTS ({}):", body.getOutputs().size());
            for (int i = 0; i < body.getOutputs().size(); i++) {
                var o = body.getOutputs().get(i);
                log.info("[dump]   [{}] addr={}... coin={} assets={} datum={}", i,
                        o.getAddress().substring(0, Math.min(24, o.getAddress().length())),
                        o.getValue().getCoin(),
                        o.getValue().getMultiAssets(),
                        o.getInlineDatum() != null ? "inline" : (o.getDatumHash() != null ? "hash" : "none"));
            }
            var w = body.getWithdrawals();
            log.info("[dump] WITHDRAWALS ({}):", w == null ? 0 : w.size());
            if (w != null) {
                for (var wd : w) {
                    var addr = new Address(wd.getRewardAddress());
                    log.info("[dump]   {} -> credHash={}", wd.getRewardAddress(),
                            addr.getDelegationCredentialHash().map(HexUtil::encodeHexString)
                                    .orElse(addr.getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("?")));
                }
            }
            log.info("[dump] MINT: {}", body.getMint());
        } catch (Exception e) {
            log.warn("[dump] could not dump tx shape: {}", e.toString());
        }
    }

    /** The running cluster's genesis {@code systemStart}, in epoch milliseconds. */
    private static long devnetSystemStartMs() throws Exception {
        var dir = System.getenv().getOrDefault("YACI_CLUSTER_DIR",
                System.getProperty("user.home") + "/.yaci-cli/local-clusters/default/node/genesis");
        var shelley = OBJECT_MAPPER.readTree(
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of(dir, "shelley-genesis.json")));
        return java.time.Instant.parse(shelley.get("systemStart").asText()).toEpochMilli();
    }

    /** How long to wait for a transaction to show up on chain before giving up. */
    private static final long CONFIRM_TIMEOUT_MS = 120_000;

    @Test
    public void securityTokenPathsWorkOnChain() throws Exception {
        Assertions.assertNotNull(BOOTSTRAP_TXHASH,
                "CIP113_BOOTSTRAP_TXHASH must name the deployed protocol bootstrap — run "
                + "PreviewProtocolDeploymentMintTest against this devnet first and pass its txHash");

        var backend = new BFBackendService(BACKEND_URL, BACKEND_KEY);
        var params = loadBootstrap(BOOTSTRAP_TXHASH);
        log.info("[devnet] using protocol bootstrap {}", params.txHash());

        // The registration orchestrator builds several transactions back-to-back without
        // submitting, feeding each one's outputs to the next through this supplier — so the
        // builder must read through it, exactly as the production bean does.
        var hybridUtxoSupplier =
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(backend.getUtxoService());
        var protocolParamsSupplier =
                new com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier(backend.getEpochService());
        // Same wrapping as YaciConfiguration: the hybrid layer answers for scripts published
        // by a transaction that is built but not yet submitted, and delegates everything else
        // to the backend. A null delegate is enough for the registration chain (its reference
        // scripts are still pending) but not for a later mint, which must resolve them from
        // the chain.
        var hybridScriptSupplier = new org.cardanofoundation.cip113.service.HybridScriptSupplier(
                new com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier(backend.getScriptService()));
        var utxoProvider = zeroBasedPagingUtxoProvider(backend);
        var registrations = new java.util.HashMap<String,
                org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity>();

        var handler = new org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler(
                new org.cardanofoundation.cip113.service.SecurityTokenScriptBuilderService(
                        HandlerFixtures.substandardService(),
                        HandlerFixtures.protocolScriptBuilderService()),
                HandlerFixtures.protocolScriptBuilderService(),
                org.mockito.Mockito.mock(org.cardanofoundation.cip113.service.SecurityTokenAllowlistService.class),
                registrationRepository(registrations),
                org.mockito.Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenDenylistEntryRepository.class),
                org.mockito.Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenPowerUserRepository.class),
                org.mockito.Mockito.mock(org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository.class),
                utxoProvider,
                new org.cardanofoundation.cip113.service.AccountService(utxoProvider),
                // Evaluate LOCALLY, submit REMOTELY — the same split YaciConfiguration wires in
                // production. The chain builds several transactions before any of them is
                // submitted, so a remote evaluator cannot resolve the pending outputs: it
                // rejects the second transaction with "Unknown transaction input (missing from
                // UTxO set)". The Aiken evaluator reads through the hybrid supplier, which
                // knows about them.
                new QuickTxBuilder(hybridUtxoSupplier, protocolParamsSupplier, hybridScriptSupplier,
                        localEvaluatingProcessor(backend, hybridUtxoSupplier, protocolParamsSupplier,
                                hybridScriptSupplier)),
                protocolParamsSupplier,
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new org.cardanofoundation.cip113.model.onchain.RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                hybridUtxoSupplier,
                hybridScriptSupplier,
                org.mockito.Mockito.mock(org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository.class),
                devnetConverters());

        String adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var request = org.cardanofoundation.cip113.model.SecurityTokenRegisterRequest.builder()
                .substandardId("security-token")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity("0")
                .initialMintQuantity("1000")
                .cip68Metadata(METADATA)
                .adminPubKeyHash(adminPkh)
                .initialMintableAmount(1_000_000L)
                .bootstrapPowerUserPkh(adminPkh)
                .bootstrapPowerUserCapabilities(255)
                .bootstrapPowerUserLabel("admin")
                .requiresReceiverKyc(false)
                .requiresSenderKyc(false)
                .build();

        var chainResult = handler.buildFullRegistrationChain(request, params);
        Assertions.assertTrue(chainResult.isSuccessful(),
                "registration chain build failed: " + chainResult.error());
        var built = chainResult.metadata();
        log.info("[devnet] chain built for prog-token policy {}", built.programmableTokenPolicyId());

        // Order matters: publishScripts must land BEFORE the registration, which reads its
        // outputs as reference inputs.
        var stages = new LinkedHashMap<String, String>();
        stages.put("genesis", built.genesisCborHex());
        stages.put("addPowerUser", built.addPowerUserCborHex());
        if (built.publishScriptsCborHex() != null) stages.put("publishScripts", built.publishScriptsCborHex());
        stages.put("registration", built.registrationCborHex());
        if (built.registerTransferLogicCborHex() != null) {
            stages.put("registerTransferLogic", built.registerTransferLogicCborHex());
        }
        if (built.registerThirdPartyTransferLogicCborHex() != null) {
            stages.put("registerThirdPartyTransferLogic", built.registerThirdPartyTransferLogicCborHex());
        }

        for (var stage : stages.entrySet()) {
            submitAndAwait(backend, stage.getKey(), stage.getValue());
        }

        // The reward accounts the mint/burn withdrawals depend on exist only because the
        // stages above landed. This is the assertion script evaluation cannot make.
        log.info("[devnet] registration chain confirmed on chain");

        // Confirming the TRANSACTION is not the same as its outputs being queryable. The
        // builders locate the GlobalState by (policy, asset name) through the indexer, which
        // trails the node — so wait for the UTxO itself, not just the tx that made it.
        awaitUtxo(utxoProvider, built.globalStatePolicyId(),
                org.cardanofoundation.cip113.service.SecurityTokenScriptBuilderService.GLOBAL_STATE_ASSET_NAME_HEX,
                "GlobalState NFT");

        var reg = registrations.get(built.programmableTokenPolicyId());
        Assertions.assertNotNull(reg, "the chain must have persisted a registration row");
        Assertions.assertTrue(reg.isCip68ReferenceMinted(),
                "the registration carried the CIP-68 (100) reference NFT");

        // ── an ordinary mint, on chain ──
        var mint = handler.buildMintTransaction(
                new org.cardanofoundation.cip113.model.MintTokenRequest(
                        BootstrapFixture.ADMIN.baseAddress(),
                        built.programmableTokenPolicyId(),
                        reg.getSecurityAssetNameHex(),
                        "250",
                        BootstrapFixture.ALICE.baseAddress(),
                        null,
                        null),
                params);
        Assertions.assertTrue(mint.isSuccessful(), "mint build failed: " + mint.error());
        submitAndAwait(backend, "mint", mint.unsignedCborTx());

        // ── a burn, on chain ──
        // Locate a token UTxO the registration's first mint produced. The burn needs a
        // specific (txHash, index), and it must be the (333) user token — never the (100),
        // which is metadata and has no supply to retire.
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(built.registrationCborHex()));
        var regTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(regTx.serialize());
        String userAssetName = reg.getSecurityAssetNameHex();
        Integer userOutputIndex = null;
        var outs = regTx.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            boolean carries = outs.get(i).getValue().getMultiAssets().stream()
                    .anyMatch(ma -> ma.getPolicyId().equals(built.programmableTokenPolicyId())
                            && ma.getAssets().stream().anyMatch(as ->
                                    HexUtil.encodeHexString(as.getNameAsBytes()).equalsIgnoreCase(userAssetName)));
            if (carries) { userOutputIndex = i; break; }
        }
        Assertions.assertNotNull(userOutputIndex, "the registration minted no user token output");

        var burn = handler.buildBurnTransaction(
                new org.cardanofoundation.cip113.model.BurnTokenRequest(
                        BootstrapFixture.ADMIN.baseAddress(),
                        built.programmableTokenPolicyId(),
                        userAssetName,
                        "400",                       // partial, so a continuation output is produced
                        regTxHash,
                        userOutputIndex),
                params);
        Assertions.assertTrue(burn.isSuccessful(), "burn build failed: " + burn.error());
        submitAndAwait(backend, "burn", burn.unsignedCborTx());

        log.info("[devnet] ALL SECURITY-TOKEN PATHS CONFIRMED ON CHAIN for policy {}",
                built.programmableTokenPolicyId());
    }

    /** Sign with the admin key, submit, and wait until the node reports the transaction. */
    private void submitAndAwait(BFBackendService backend, String label, String cborHex) throws Exception {
        var tx = Transaction.deserialize(HexUtil.decodeHexString(cborHex));
        int size = tx.serialize().length;
        // Payment always; stake only when the transaction asks for it.
        //
        // The burn declares BOTH the burner's payment key (the authority's can_burn check
        // matches it against the power-user node) and their stake key (prog-logic-global's
        // collect_input_assets checks it on the token UTxO). A real wallet signs for both
        // when signing as itself; a raw Account does not unless asked, and the ledger
        // rejects the difference with MissingVKeyWitnessesUTXOW — a phase-1 rule, invisible
        // to script evaluation.
        //
        // Signing everything with both keys instead is wrong the other way: the builder
        // sized the fee for the witnesses it declared, so an extra one makes the transaction
        // bigger than the fee covers and the ledger rejects it with FeeTooSmallUTxO.
        byte[] adminStakeHash = new Address(BootstrapFixture.ADMIN.stakeAddress())
                .getDelegationCredentialHash().orElseThrow();
        boolean needsStakeSig = tx.getBody().getRequiredSigners() != null
                && tx.getBody().getRequiredSigners().stream()
                        .anyMatch(rs -> java.util.Arrays.equals(rs, adminStakeHash));
        var signed = BootstrapFixture.ADMIN.sign(tx);
        if (needsStakeSig) {
            signed = BootstrapFixture.ADMIN.signWithStakeKey(signed);
        }
        byte[] bytes = signed.serialize();

        var result = backend.getTransactionService().submitTransaction(bytes);
        Assertions.assertTrue(result.isSuccessful(),
                "[" + label + "] SUBMIT REJECTED by the ledger (" + size + " bytes): "
                + result.getResponse()
                + " — this is a phase-1 rule that script evaluation cannot check");
        String txHash = result.getValue();
        log.info("[devnet] {} submitted: {} ({} bytes)", label, txHash, size);

        long deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                var found = backend.getTransactionService().getTransaction(txHash);
                if (found.isSuccessful() && found.getValue() != null) {
                    log.info("[devnet] {} confirmed on chain", label);
                    return;
                }
            } catch (Exception ignored) {
                // not indexed yet
            }
            Thread.sleep(1_000);
        }
        Assertions.fail("[" + label + "] " + txHash + " was accepted but never appeared on chain");
    }

    /** Load the deployed bootstrap params for {@code txHash} from the devnet resource file. */
    private ProtocolBootstrapParams loadBootstrap(String txHash) throws Exception {
        var path = java.nio.file.Path.of("src/main/resources/protocol-bootstraps-devnet.json");
        var all = OBJECT_MAPPER.readValue(java.nio.file.Files.readAllBytes(path),
                new com.fasterxml.jackson.core.type.TypeReference<List<ProtocolBootstrapParams>>() {});
        return all.stream()
                .filter(p -> txHash.equals(p.txHash()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no deployment with txHash " + txHash + " in " + path
                        + " — deploy the protocol to this devnet first"));
    }
}
