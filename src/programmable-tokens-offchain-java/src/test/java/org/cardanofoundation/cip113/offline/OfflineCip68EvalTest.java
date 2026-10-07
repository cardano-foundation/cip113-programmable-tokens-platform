package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.cip113.entity.ProgrammableTokenRegistryEntity;
import org.cardanofoundation.cip113.model.Cip68Metadata;
import org.cardanofoundation.cip113.model.DummyRegisterRequest;
import org.cardanofoundation.cip113.model.onchain.RegistryNodeParser;
import org.cardanofoundation.cip113.repository.CustomStakeRegistrationRepository;
import org.cardanofoundation.cip113.repository.ProgrammableTokenRegistryRepository;
import org.cardanofoundation.cip113.service.AccountService;
import org.cardanofoundation.cip113.service.module.DummyModuleHandler;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Offline build + real phase-2 evaluation of the CIP-68 registration paths, driving the
 * <em>production</em> module handlers against a virtually-submitted protocol bootstrap.
 *
 * <p>Nothing is re-implemented here: the handler under test is the same
 * {@code DummyModuleHandler} the REST layer uses, constructed by hand with offline
 * collaborators (see {@link HandlerFixtures}). The handler never sets an explicit tx evaluator,
 * so the {@code TransactionProcessor} injected into its {@link QuickTxBuilder} <em>is</em> the
 * evaluator — and ours runs the real aiken/uplc machine. A script that traps therefore fails the
 * build, rather than being papered over by {@code YaciConfiguration}'s ceiling fallback (which is
 * a Spring bean these tests never construct; see
 * {@link OfflineChain#CEILING_FALLBACK_MEM}).
 */
@Slf4j
public class OfflineCip68EvalTest {

    /** 12-byte base name, well inside the 28-byte budget the 4-byte CIP-67 label leaves. */
    private static final String BASE_ASSET_NAME_HEX =
            HexUtil.encodeHexString("OfflineToken".getBytes(StandardCharsets.UTF_8));

    /**
     * A well-formed 28-byte policy id standing in for a previously-initialised blacklist root.
     * freeze-and-seize's transfer script is parameterised by it; nothing in the registration
     * transaction dereferences it on chain, so a fixed value is sufficient here.
     */
    private static final String BLACKLIST_NODE_POLICY_ID =
            "b1acc115700000000000000000000000000000000000000000000000";

    private static final Cip68Metadata METADATA = new Cip68Metadata(
            "Offline Token",
            "A token registered and evaluated entirely offline",
            "OFFTK",
            6,
            "https://example.invalid/offline-token",
            "ipfs://bafkreialsoinvalidbutwellformedlookinglogohash");

    @Test
    public void initialMintAttestationPreservesPlainRegistrationChainAndEvaluates() throws Exception {
        rwaTokenChain(null, 1_000_000L, "1000", BootstrapFixture.ALICE.baseAddress(), false, false, false, true);
    }

    @Test
    public void initialMintAttestationPreservesCip68PairAndEvaluates() throws Exception {
        rwaTokenChain(METADATA, 1_000_000L, "1000", BootstrapFixture.ALICE.baseAddress(), false, false, false, true);
    }

    @Test
    public void bootstrapIsVirtuallySubmittableAndSpendable() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        log.info("bootstrap outputs ({} total):", boot.outputs().size());
        for (var utxo : boot.outputs()) {
            log.info("  [{}] {} amounts={} inlineDatum={} refScript={}",
                    utxo.getOutputIndex(), utxo.getAddress(), utxo.getAmount(),
                    utxo.getInlineDatum() == null ? "-" : utxo.getInlineDatum().length() / 2 + "B",
                    utxo.getReferenceScriptHash() == null ? "-" : utxo.getReferenceScriptHash());
        }

        Assertions.assertNotNull(boot.coordinationUtxo(), "coordination utxo must exist after submit");
        Assertions.assertNotNull(boot.registryOriginUtxo(), "registry origin node utxo must exist");
        Assertions.assertNotNull(boot.issuanceCborHexUtxo(), "issuance template utxo must exist");

        // Schema v3 records protocol state explicitly; consumers must follow this coordinate
        // instead of assuming a bootstrap output position.
        Assertions.assertEquals(boot.coordinationUtxo().getTxHash(),
                boot.params().protocolParams().utxo().txHash());
        Assertions.assertEquals(boot.coordinationUtxo().getOutputIndex(),
                boot.params().protocolParams().utxo().outputIndex());
        Assertions.assertEquals(2, boot.issuanceCborHexUtxo().getOutputIndex(),
                "the SDK alpha.4 state transaction emits issuance CBOR after params and registry");

        Assertions.assertNotEquals(boot.params().protocolParams().txInput(),
                boot.params().upgradeMultisig().txInput(),
                "protocol params and upgrade multisig require distinct one-shot inputs");
        Assertions.assertNotNull(boot.plgRefUtxo());
        Assertions.assertNotNull(boot.issuanceLogicRefUtxo());
        Assertions.assertNotNull(boot.upgradeMultisigRefUtxo());

        var adminUtxos = chain.utxosAt(BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertFalse(adminUtxos.isEmpty(), "admin must retain change across the split bootstrap");
    }

    // ------------------------------------------------------------------ dummy module

    @Test
    public void dummyRegistrationWithCip68MintsThePairAndEvaluates() throws Exception {
        var result = runDummyRegistration(METADATA, "1000000");
        var tx = result.transaction();
        var label = "dummy/cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var policyId = result.issuancePolicyId();
        var tokens = Cip68Evidence.tokensOfPolicy(tx, policyId);
        log.info("[{}] issuance policy {} carries {} token(s):", label, policyId, tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] policy={} name={} label={} qty={}",
                    label, t.outputIndex(), t.policyId(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "CIP-68 registration must mint a PAIR under the issuance policy");

        var userToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_FT).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (333) user token found"));
        var refToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        // 1_000_000 is fungible, so the label must be (333) and NOT (222).
        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "a supply of 1000000 is fungible and must take the (333) label");
        Assertions.assertEquals(new BigInteger("1000000"), userToken.quantity());
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, userToken.baseNameHex(),
                "the user token's base name must survive labelling unchanged");

        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, refToken.baseNameHex(),
                "reference and user token must share a base name or the pair is unresolvable");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        // Both outputs must sit at a programmable_logic_base address with an INLINE stake
        // credential — core's no_escape applies to the reference token too, since it is itself
        // a programmable token.
        var plb = result.plbScriptHash();
        var recipientStake = stakeCredHex(BootstrapFixture.ALICE.baseAddress());
        var issuerStake = stakeCredHex(BootstrapFixture.ADMIN.baseAddress());

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, userToken.outputIndex(), plb, recipientStake);
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, refToken.outputIndex(), plb, issuerStake);

        // The reference token goes to the ISSUER, not the recipient — that is what lets the
        // issuer spend it later to rewrite the metadata.
        Assertions.assertNotEquals(userToken.outputIndex(), refToken.outputIndex(),
                "user and reference token must occupy different outputs");

        Cip68Evidence.assertDatumRoundTrip(label, tx, refToken.outputIndex(), METADATA);

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    /**
     * The {@code (222)} decision, pinned where it is actually made.
     *
     * <p>Registering exactly one unit under {@code dummy} does <em>not</em> yield a {@code (222)}
     * token, and that is deliberate. {@code (222)} asserts non-fungibility — one unit, forever —
     * and nothing in {@code validators/transfer.ak} caps lifetime supply: {@code issue} is
     * {@code redeemer == 100} and {@code buildMintTransaction} will mint more of the same name
     * tomorrow. A {@code (222)} here would be a promise the module cannot keep, and no
     * off-chain guard can keep it either, since whoever holds the minting authority can build the
     * transaction without this backend. So {@code dummy} and {@code freeze-and-seize} are always
     * {@code (333)}; {@code rwa-token} is the only one that may claim {@code (222)}, because
     * its GlobalState {@code mintable_amount} is a real on-chain ceiling.
     */
    @Test
    public void dummyRegistrationAtQuantityOneIsStillFungibleBecauseSupplyIsUncapped() throws Exception {
        var result = runDummyRegistration(METADATA, "1");
        var tx = result.transaction();
        var label = "dummy/cip68-qty1";

        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        var userToken = tokens.stream().filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();

        log.info("[{}] user token name={} label={} qty={}",
                label, userToken.assetNameHex(), userToken.label(), userToken.quantity());

        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "dummy cannot cap lifetime supply, so even a one-unit registration must take (333)");
        Assertions.assertNotEquals(Cip68.LABEL_NFT, userToken.label().intValue(),
                "a (222) here would be a non-fungibility claim the module cannot enforce");
        Assertions.assertEquals(BigInteger.ONE, userToken.quantity());
    }

    /**
     * H5: the later mint must be bound to the asset the registry recorded.
     *
     * <p>{@code issue} constrains no asset name whatsoever, so without this binding a caller who
     * registered {@code (333)Foo} could mint bare {@code Foo}, a differently-labelled sibling, or
     * a {@code (100)Foo} carrying a datum of their choosing — all under the registered policy id,
     * and all indistinguishable from the real token to any wallet that trusts the policy.
     */
    @Test
    public void dummyMintIsBoundToTheRegisteredAssetName() throws Exception {
        var fixture = dummyMintFixture(METADATA, "1000000");
        var registeredName = fixture.registeredAssetName();
        Assertions.assertTrue(Cip68.hasLabel(registeredName),
                "precondition: a CIP-68 registration must have recorded a LABELLED name");

        // (a) the bare, unlabelled base name — the wizard's input, and the obvious wrong guess
        assertMintRefused(fixture, BASE_ASSET_NAME_HEX, "does not match the registered");

        // (b) a different labelled sibling under the same policy
        assertMintRefused(fixture, Cip68.labeledAssetName(444, BASE_ASSET_NAME_HEX),
                "does not match the registered");

        // (c) an entirely unrelated name
        assertMintRefused(fixture, HexUtil.encodeHexString("Impostor".getBytes(StandardCharsets.UTF_8)),
                "does not match the registered");

        // (d) the (100) reference token itself — the one that breaks the pair irrecoverably
        assertMintRefused(fixture, Cip68.referenceNameFor(registeredName), "");

        // The control: the registered name is accepted, so the guard is a binding and not a wall.
        var ok = fixture.handler().buildMintTransaction(
                mintRequest(fixture.policyId(), registeredName), fixture.bootParams());
        Assertions.assertTrue(ok.isSuccessful(),
                "the REGISTERED name must still mint: " + ok.error());
    }

    @Test
    public void dummyMintIncludesOptionalCip170LabelOnlyWhenApproved() throws Exception {
        var fixture = dummyMintFixture(METADATA, "1000000");
        var ordinary = fixture.handler().buildMintTransaction(
                mintRequest(fixture.policyId(), fixture.registeredAssetName()), fixture.bootParams());
        Assertions.assertTrue(ordinary.isSuccessful(), ordinary.error());
        var ordinaryTx = Transaction.deserialize(HexUtil.decodeHexString(ordinary.unsignedCborTx()));
        Assertions.assertTrue(ordinaryTx.getAuxiliaryData() == null
                        || ordinaryTx.getAuxiliaryData().getMetadata() == null
                        || ordinaryTx.getAuxiliaryData().getMetadata().get(BigInteger.valueOf(170)) == null,
                "ordinary mint must have no ATTEST label");

        var attestation = new org.cardanofoundation.cip113.model.Cip170AttestationData(
                "E" + "a".repeat(43), "E" + "b".repeat(43), "1", "1.0");
        var approved = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(), fixture.policyId(), fixture.registeredAssetName(),
                "500", BootstrapFixture.ALICE.baseAddress(), attestation);
        var result = fixture.handler().buildMintTransaction(approved, fixture.bootParams());
        Assertions.assertTrue(result.isSuccessful(), result.error());
        var tx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
        var label = tx.getAuxiliaryData().getMetadata().get(BigInteger.valueOf(170));
        Assertions.assertInstanceOf(com.bloxbean.cardano.client.metadata.MetadataMap.class, label);
        var attest = (com.bloxbean.cardano.client.metadata.MetadataMap) label;
        Assertions.assertEquals("ATTEST", attest.get("t"));
        Assertions.assertEquals(attestation.digest(), attest.get("d"));
        Assertions.assertEquals(attestation.signerAid(), attest.get("i"));
    }

    /** A (100) name must be refused by the ordinary mint endpoint whatever else is true. */
    @Test
    public void dummyMintRefusesTheReferenceTokenName() throws Exception {
        var fixture = dummyMintFixture(METADATA, "1000000");
        var referenceName = Cip68.referenceNameFor(fixture.registeredAssetName());

        // Pretend the registry itself named the (100) — i.e. defeat guard (a) entirely — so this
        // asserts the dedicated (100) guard rather than the name-equality one.
        fixture.setRegisteredAssetName(referenceName);

        var result = fixture.handler().buildMintTransaction(
                mintRequest(fixture.policyId(), referenceName), fixture.bootParams());
        Assertions.assertFalse(result.isSuccessful(),
                "minting a second (100) must be refused even if the registry names it");
        Assertions.assertNull(result.unsignedCborTx(), "a refusal must not return a transaction");
        Assertions.assertTrue(String.valueOf(result.error()).contains("(100)"),
                "the error must name the reference token, got: " + result.error());
    }

    /** M6: transferring a (100) would rebuild it with Constr(0) and erase the metadata. */
    @Test
    public void dummyTransferRefusesTheReferenceToken() throws Exception {
        var fixture = dummyMintFixture(METADATA, "1000000");
        var referenceName = Cip68.referenceNameFor(fixture.registeredAssetName());

        var request = new org.cardanofoundation.cip113.model.TransferTokenRequest(
                BootstrapFixture.ALICE.baseAddress(),
                fixture.policyId() + referenceName,
                "1",
                BootstrapFixture.ADMIN.baseAddress(),
                null, null, null, null, null, null, null);

        var result = fixture.handler().buildTransferTransaction(request, fixture.bootParams());
        Assertions.assertFalse(result.isSuccessful(),
                "transferring the (100) reference token must be refused");
        String error = String.valueOf(result.error());
        Assertions.assertTrue(error.contains("(100)"),
                "the error must name the reference token, got: " + error);
        Assertions.assertTrue(error.contains("datum"),
                "the error must explain that the datum would be erased, got: " + error);
    }

    /** M9: oversized metadata is refused at the handler, not truncated and not silently shipped. */
    @Test
    public void dummyRegistrationRefusesOversizedMetadata() throws Exception {
        // ~2 KB of description against ~1.4 KB of headroom on the tightest measured path.
        var huge = new Cip68Metadata("Offline Token", "d".repeat(2048), "OFFTK", 6, null, null);
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var handler = dummyHandler(chain, boot, Mockito.mock(ProgrammableTokenRegistryRepository.class));

        var result = handler.buildRegistrationTransaction(
                DummyRegisterRequest.builder()
                        .moduleId("dummy")
                        .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                        .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                        .assetName(BASE_ASSET_NAME_HEX)
                        .quantity("1000000")
                        .cip68Metadata(huge)
                        .build(),
                boot.params());

        Assertions.assertFalse(result.isSuccessful(), "oversized metadata must be refused");
        Assertions.assertNull(result.unsignedCborTx(),
                "a refusal must not hand back a transaction the ledger would reject");
        String error = String.valueOf(result.error());
        log.info("[dummy/oversized] refused with: {}", error);
        Assertions.assertTrue(error.contains("description") || error.contains("too large"),
                "the error must name the metadata as the cause, got: " + error);
        // The refusal must NOT be a quiet truncation dressed up as success.
        Assertions.assertFalse(error.isBlank(), "a refusal must carry an explanation");
    }

    /** L11: a blank base name yields a label-only asset name that no CIP-68 reader accepts. */
    @Test
    public void dummyRegistrationRefusesABlankAssetName() throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var handler = dummyHandler(chain, boot, Mockito.mock(ProgrammableTokenRegistryRepository.class));

        var result = handler.buildRegistrationTransaction(
                DummyRegisterRequest.builder()
                        .moduleId("dummy")
                        .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                        .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                        .assetName("")
                        .quantity("1000000")
                        .cip68Metadata(METADATA)
                        .build(),
                boot.params());

        Assertions.assertFalse(result.isSuccessful(), "a blank base asset name must be refused");
        Assertions.assertNull(result.unsignedCborTx(), "a refusal must not return a transaction");
    }

    @Test
    public void dummyRegistrationWithoutCip68IsUnchanged() throws Exception {
        var result = runDummyRegistration(null, "1000000");
        var tx = result.transaction();
        var label = "dummy/no-cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = chainOf(result).reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, result.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(1, tokens.size(),
                "without CIP-68 metadata exactly one asset must mint — no reference token");
        var only = tokens.getFirst();
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, only.assetNameHex(),
                "the asset name must be the bare base name, unlabelled");
        Assertions.assertNull(only.label(),
                "the non-CIP-68 path must not attach a CIP-67 label");

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, only.outputIndex(),
                result.plbScriptHash(), stakeCredHex(BootstrapFixture.ALICE.baseAddress()));

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    // ------------------------------------------------------------------ freeze-and-seize

    @Test
    public void freezeAndSeizeRegistrationWithCip68MintsThePairAndEvaluates() throws Exception {
        var withCip68 = runFreezeAndSeizeRegistration(METADATA, "1000000");
        var tx = withCip68.transaction();
        var label = "freeze-and-seize/cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = withCip68.chain().reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, withCip68.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, withCip68.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "CIP-68 registration must mint a PAIR under the issuance policy");

        var userToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_FT).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (333) user token found"));
        var refToken = tokens.stream().filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "a supply of 1000000 is fungible and must take the (333) label");
        Assertions.assertEquals(new BigInteger("1000000"), userToken.quantity());
        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        var plb = withCip68.plbScriptHash();
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, userToken.outputIndex(), plb,
                stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, refToken.outputIndex(), plb,
                stakeCredHex(BootstrapFixture.ADMIN.baseAddress()));

        Cip68Evidence.assertDatumRoundTrip(label, tx, refToken.outputIndex(), METADATA);

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);

        // The crux for this module: issuer_admin is parameterised by the asset NAME, so the
        // CIP-67 label participates in the script hash and therefore in the issuance policy id.
        var withoutCip68 = runFreezeAndSeizeRegistration(null, "1000000");
        log.info("[freeze-and-seize] policy WITH cip68    = {}", withCip68.issuancePolicyId());
        log.info("[freeze-and-seize] policy WITHOUT cip68 = {}", withoutCip68.issuancePolicyId());
        Assertions.assertNotEquals(withCip68.issuancePolicyId(), withoutCip68.issuancePolicyId(),
                "freeze-and-seize bakes the asset name into issuer_admin, so labelling the name"
                + " MUST change the issuance policy id — equal ids would mean the label never"
                + " reached the script parameter");
    }

    @Test
    public void freezeAndSeizeRegistrationWithoutCip68IsUnchanged() throws Exception {
        var result = runFreezeAndSeizeRegistration(null, "1000000");
        var tx = result.transaction();
        var label = "freeze-and-seize/no-cip68";

        Cip68Evidence.dumpOutputs(label, tx);
        int evaluated = result.chain().reportAndCheckRedeemers(label, tx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated");

        var tokens = Cip68Evidence.tokensOfPolicy(tx, result.issuancePolicyId());
        log.info("[{}] issuance policy {} carries {} token(s):", label, result.issuancePolicyId(), tokens.size());
        for (var t : tokens) {
            log.info("[{}]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(1, tokens.size(),
                "without CIP-68 metadata exactly one asset must mint — no reference token");
        var only = tokens.getFirst();
        Assertions.assertEquals(BASE_ASSET_NAME_HEX, only.assetNameHex(),
                "the asset name must be the bare base name, unlabelled");
        Assertions.assertNull(only.label(), "the non-CIP-68 path must not attach a CIP-67 label");

        Cip68Evidence.assertProgrammableLogicBaseAddress(label, tx, only.outputIndex(),
                result.plbScriptHash(), stakeCredHex(BootstrapFixture.ALICE.baseAddress()));

        int size = tx.serialize().length;
        Assertions.assertTrue(size < 16384, "tx exceeds 16384 bytes: " + size);
    }

    // ------------------------------------------------------------------ rwa-token

    /**
     * No reference-script output this platform publishes may be Ada-only.
     *
     * <p>CIP-113's {@code get_protocol_params_ref} scans the reference inputs with
     * {@code assets.peek_first}, which reads the head of a value's token list. On an
     * Ada-only value that is {@code head_list([])} — it ABORTS rather than reporting
     * "no match" — so the scan dies on the first token-less reference input instead of
     * walking past it to the params NFT. A CIP-33 reference-script output is exactly
     * such a value, and the ledger orders reference inputs by output reference, which
     * no off-chain code controls. Observed on chain, ledger-sorted, for a burn:
     *
     * <pre>
     *   0  458a1eee…#0  ADA-ONLY  (minting_authority ref script)  &lt;- aborts here
     *   1  458a1eee…#1  ADA-ONLY  (global_state spend ref script)
     *   2  8862d2ca…#0  ProtocolParams NFT                        &lt;- never reached
     * </pre>
     *
     * <p>Every output we publish therefore carries a marker asset. This test pins that,
     * because the failure it prevents is invisible locally: the offline evaluator sees
     * the transaction's SERIALIZED reference-input order while the ledger sorts, so a
     * regression here evaluates green and only fails after submit.
     *
     * <p>Delete this test when the pinned CIP-113 core carries the upstream fix
     * ({@code fix/082-tokenless-ref-input}, PR #103) and the markers are removed.
     */
    @Test
    public void publishedReferenceScriptOutputsAreNeverAdaOnly() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());

        Assertions.assertNotNull(built.publishScriptsCborHex(),
                "a registration carrying a first mint must publish reference scripts");

        int checked = 0;
        for (var entry : java.util.List.of(
                java.util.Map.entry("publishScripts", built.publishScriptsCborHex()),
                java.util.Map.entry("third-party cert", built.registerThirdPartyTransferLogicCborHex()))) {
            if (entry.getValue() == null) continue;
            var tx = Transaction.deserialize(HexUtil.decodeHexString(entry.getValue()));
            for (int i = 0; i < tx.getBody().getOutputs().size(); i++) {
                var out = tx.getBody().getOutputs().get(i);
                if (out.getScriptRef() == null) continue;   // only CIP-33 outputs matter
                var ma = out.getValue().getMultiAssets();
                Assertions.assertTrue(ma != null && !ma.isEmpty(),
                        entry.getKey() + " output " + i + " carries a reference script but no "
                        + "token. An Ada-only reference input aborts CIP-113's protocol-params "
                        + "scan whenever it sorts ahead of the params NFT, which transaction "
                        + "hashes decide. Give it a marker asset.");
                checked++;
            }
        }
        Assertions.assertTrue(checked >= 2,
                "expected at least the two published per-token scripts, found " + checked);
        log.info("[rwa-token] {} reference-script outputs checked, all carry a marker", checked);
        Assertions.assertNotNull(reg.getRefScriptsTxHash());
    }

    /**
     * The burn transaction has to fit under the ledger's 16 384-byte limit.
     *
     * <p>It needs four validators. Inline they are 19 385 bytes — 7211 minting_logic + 6269
     * third_party_transfer_logic + 4183 global_state spend + 1722 issuance_mint — and the
     * first burn ever attempted was rejected at 21 480. None of the four is droppable:
     * {@code ThirdPartyAct} requires the withdrawal keyed on registry-node slot 4,
     * minting_logic gates {@code can_burn}, GlobalState must be spent to decrement the
     * supply, and issuance_mint is what actually burns. So three of them are read from the
     * reference scripts the registration published, leaving only issuance_mint inline.
     *
     * <p>This test exists because that claim is arithmetic until something measures it. It
     * drives a real registration-with-first-mint chain (the only path that publishes the
     * reference scripts), then builds a real burn against the resulting token UTxO and
     * measures the serialized transaction.
     */
    @Test
    public void rwaTokenBurnFitsUnderMaxTxSize() throws Exception {
        // Mint to the ADMIN, not Alice: the burner must be a power user holding both
        // can_burn (minting_logic) and can_force_transfer (third_party_transfer_logic),
        // and the bootstrap power user is the admin.
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());
        var label = "rwa-token";

        // The publish step is what makes the burn buildable at all — assert it ran and was
        // recorded, otherwise the burn's own precondition would be what fails and this test
        // would prove nothing about size.
        Assertions.assertNotNull(built.publishScriptsCborHex(),
                "a registration carrying a first mint must publish reference scripts");
        Assertions.assertNotNull(reg.getRefScriptsTxHash(),
                "the published reference scripts must be recorded on the registration row");
        Assertions.assertNotNull(reg.getThirdPartyTransferLogicRefIndex(),
                "third_party_transfer_logic must be among the published reference scripts");

        // Locate the token UTxO the registration's first mint produced.
        var policyId = built.programmableTokenPolicyId();
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(built.registrationCborHex()));
        var regTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(regTx.serialize());
        var userToken = Cip68Evidence.tokensOfPolicy(regTx, policyId).stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the registration minted no user token"));
        log.info("[{}/burn] burning from {}:{} (asset {} qty {})",
                label, regTxHash, userToken.outputIndex(), userToken.assetNameHex(),
                userToken.quantity());

        var burnRequest = new org.cardanofoundation.cip113.model.BurnTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId,
                reg.getSecurityAssetNameHex(),
                "400",                                  // partial: exercises the continuation output
                regTxHash,
                userToken.outputIndex());

        var burnResult = handler.buildBurnTransaction(burnRequest, boot.params());
        Assertions.assertTrue(burnResult.isSuccessful(),
                "burn build failed: " + burnResult.error());

        var burnTx = Transaction.deserialize(HexUtil.decodeHexString(burnResult.unsignedCborTx()));
        int size = burnTx.serialize().length;
        log.info("[{}/burn] serialized size = {} bytes (limit 16384; was 21480 with all four "
                + "validators inline)", label, size);

        int evaluated = chain.reportAndCheckRedeemers(label + "/burn", burnTx);
        Assertions.assertTrue(evaluated > 0,
                "no redeemer was genuinely evaluated on the burn — the scripts did not run, so "
                + "the size figure would not mean anything");

        Assertions.assertTrue(size < 16384,
                "burn exceeds the 16384-byte ledger limit at " + size + " bytes");
    }
    /**
     * An ordinary transfer validates.
     *
     * <p>This path had NO offline coverage until the 2026-08-21 upstream move, which is
     * exactly the wrong combination: {@code transfer_logic_script} lost two compile-time
     * parameters ({@code registry_policy_id}, {@code plb_script_hash}) AND its redeemer lost
     * its leading {@code registry_node_ref_input_index}. A wrong parameter list yields a
     * script hash nobody can satisfy; a wrong redeemer shape fails to decode. Neither is
     * visible until something builds a real transfer and evaluates it.
     *
     * <p>Alice receives the registration's first mint and sends part of it to the admin,
     * which also exercises the change output — so the validator sees TWO destinations and
     * the destination-action list has to line up with them in output order.
     */
    @Test
    public void rwaTokenTransferEvaluates() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), /*rewardAccountsRegistered=*/ true);
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());
        var policyId = built.programmableTokenPolicyId();
        var label = "rwa-token";

        // Alice holds the token but no ADA — the bootstrap only funds the admin — and the
        // transfer needs a fee-paying input at the SENDER's address.
        chain.seedAda("offline-transfer-alice-funding", BootstrapFixture.ALICE.baseAddress(), 7, 100);

        var request = new org.cardanofoundation.cip113.model.TransferTokenRequest(
                BootstrapFixture.ALICE.baseAddress(),
                policyId + reg.getSecurityAssetNameHex(),
                "400",                                  // partial: forces a change output
                BootstrapFixture.ADMIN.baseAddress(),
                null, null, null, null, null, null, null);

        var result = handler.buildTransferTransaction(request, boot.params());
        Assertions.assertTrue(result.isSuccessful(), "transfer build failed: " + result.error());

        var tx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
        int evaluated = chain.reportAndCheckRedeemers(label + "/transfer", tx);
        Assertions.assertTrue(evaluated > 0,
                "no redeemer was genuinely evaluated on the transfer — transfer_logic and "
                + "prog-logic-global did not run, so this proves nothing");
        Assertions.assertTrue(tx.serialize().length < 16384,
                "transfer exceeds the 16384-byte ledger limit");
    }

    /** A valid MPF inclusion proof cannot override a live denylist node. This
     *  must fail before script evaluation, with the affected party identified. */
    @Test
    public void rwaTokenTransferNamesDenylistedRecipientDespiteMembership() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), true, true);
        var chain = st.chain();
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);
        byte[] aliceStake = HexUtil.decodeHexString(stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        var membership = st.allowlist().inclusionProof(policyId, aliceStake, (short) 0,
                System.currentTimeMillis()).orElseThrow();
        String proof = HexUtil.encodeHexString(membership.proofCbor());

        var add = st.handler().buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.AddToBlacklistRequest(
                        policyId, reg.getSecurityAssetNameHex(),
                        BootstrapFixture.ALICE.baseAddress(), BootstrapFixture.ADMIN.baseAddress()),
                st.boot().params());
        Assertions.assertTrue(add.isSuccessful(), "denylist add failed: " + add.error());
        chain.submit(Transaction.deserialize(HexUtil.decodeHexString(add.unsignedCborTx())));
        chain.seedAda("offline-denylisted-transfer-funding", BootstrapFixture.ALICE.baseAddress(), 7, 100);

        var deniedRecipient = st.handler().buildTransferTransaction(
                new org.cardanofoundation.cip113.model.TransferTokenRequest(
                        BootstrapFixture.ALICE.baseAddress(), policyId + reg.getSecurityAssetNameHex(),
                        "400", BootstrapFixture.ALICE.baseAddress(), null, null, null,
                        proof, membership.validUntilMs(), proof, membership.validUntilMs()),
                st.boot().params());
        Assertions.assertFalse(deniedRecipient.isSuccessful());
        Assertions.assertTrue(String.valueOf(deniedRecipient.error()).contains("Recipient is on this token's denylist"),
                "expected recipient denylist error before KYC or evaluation: " + deniedRecipient.error());

    }

    /** The denylist remains effective when both KYC gates are disabled. */
    @Test
    public void rwaTokenTransferNamesDenylistedSenderWithoutKyc() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), true);
        var chain = st.chain();
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);
        var add = st.handler().buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.AddToBlacklistRequest(
                        policyId, reg.getSecurityAssetNameHex(),
                        BootstrapFixture.ALICE.baseAddress(), BootstrapFixture.ADMIN.baseAddress()),
                st.boot().params());
        Assertions.assertTrue(add.isSuccessful(), "denylist add failed: " + add.error());
        chain.submit(Transaction.deserialize(HexUtil.decodeHexString(add.unsignedCborTx())));
        chain.seedAda("offline-denylisted-sender-funding", BootstrapFixture.ALICE.baseAddress(), 7, 100);

        var deniedSender = st.handler().buildTransferTransaction(
                new org.cardanofoundation.cip113.model.TransferTokenRequest(
                        BootstrapFixture.ALICE.baseAddress(), policyId + reg.getSecurityAssetNameHex(),
                        "400", BootstrapFixture.ADMIN.baseAddress(), null, null, null,
                        null, null, null, null),
                st.boot().params());
        Assertions.assertFalse(deniedSender.isSuccessful());
        Assertions.assertTrue(String.valueOf(deniedSender.error()).contains("Sender is on this token's denylist"),
                "expected sender denylist error with receiver KYC off: " + deniedSender.error());
    }

    /**
     * Adding and removing a denylist entry both validate.
     *
     * <p>The denylist SPEND validator changed at the 2026-08-21 upstream — it now resolves
     * its own input and requires it to carry the list policy — so its hash moved, and its
     * hash is threaded into three other validators as {@code denylist_script_hash}. That
     * makes it worth an end-to-end check rather than an assumption.
     */
    @Test
    public void rwaTokenDenylistAddAndRemoveEvaluate() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var policyId = st.built().programmableTokenPolicyId();
        var label = "rwa-token";

        var targetStake = stakeCredHex(BootstrapFixture.ALICE.baseAddress());

        var add = handler.buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.AddToBlacklistRequest(
                        policyId,
                        st.registrations().get(policyId).getSecurityAssetNameHex(),
                        BootstrapFixture.ALICE.baseAddress(),
                        BootstrapFixture.ADMIN.baseAddress()),
                boot.params());
        Assertions.assertTrue(add.isSuccessful(),
                "denylist add build failed for stake " + targetStake + ": " + add.error());
        var addTx = Transaction.deserialize(HexUtil.decodeHexString(add.unsignedCborTx()));
        Assertions.assertTrue(chain.reportAndCheckRedeemers(label + "/denylist-add", addTx) > 0,
                "no redeemer was evaluated on the denylist add");

        chain.submit(addTx);

        var remove = handler.buildRemoveFromBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.RemoveFromBlacklistRequest(
                        policyId,
                        st.registrations().get(policyId).getSecurityAssetNameHex(),
                        BootstrapFixture.ALICE.baseAddress(),
                        BootstrapFixture.ADMIN.baseAddress()),
                boot.params());
        Assertions.assertTrue(remove.isSuccessful(), "denylist remove build failed: " + remove.error());
        var removeTx = Transaction.deserialize(HexUtil.decodeHexString(remove.unsignedCborTx()));
        Assertions.assertTrue(chain.reportAndCheckRedeemers(label + "/denylist-remove", removeTx) > 0,
                "no redeemer was evaluated on denylist remove");
        chain.submit(removeTx);
        Assertions.assertTrue(chain.findUtxoByUnit(st.registrations().get(policyId).getDenylistPolicyId()
                + HexUtil.encodeHexString("Node".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + targetStake).isEmpty(), "removed denylist NFT remains unspent");
    }

    /** Exercise insertion and removal at every list position with the real scripts. */
    @Test
    public void rwaTokenDenylistMaintainsSortedLinksAcrossMutations() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);
        var donatedAddress = chain.findUtxoByUnit(reg.getDenylistPolicyId()).orElseThrow().getAddress();
        chain.seedUtxo(com.bloxbean.cardano.client.api.model.Utxo.builder()
                .txHash("ab".repeat(32)).outputIndex(7).address(donatedAddress)
                .amount(List.of(com.bloxbean.cardano.client.api.model.Amount.lovelace(
                        BigInteger.valueOf(2_000_000))))
                .inlineDatum("deadbeef").build());
        var entries = new java.util.ArrayList<java.util.Map.Entry<String, String>>();
        for (int account = 1; account <= 4; account++) {
            String address = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                    BootstrapFixture.NETWORK, BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC,
                    account, 0).baseAddress();
            entries.add(java.util.Map.entry(stakeCredHex(address), address));
        }
        entries.sort(java.util.Map.Entry.comparingByKey());
        var unauthorized = st.handler().buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.AddToBlacklistRequest(policyId, reg.getSecurityAssetNameHex(),
                        entries.get(0).getValue(), BootstrapFixture.ALICE.baseAddress()), st.boot().params());
        Assertions.assertFalse(unauthorized.isSuccessful());
        Assertions.assertTrue(String.valueOf(unauthorized.error()).contains("ADMIN capability"));
        var presentKeys = new java.util.TreeSet<String>();
        int[] addOrder = {1, 3, 0, 2}; // first, tail, before first, between nodes
        for (int index : addOrder) {
            var entry = entries.get(index);
            var result = st.handler().buildAddToBlacklistTransaction(
                    new org.cardanofoundation.cip113.service.module.capabilities
                            .BlacklistManageable.AddToBlacklistRequest(policyId,
                            reg.getSecurityAssetNameHex(), entry.getValue(), BootstrapFixture.ADMIN.baseAddress()),
                    st.boot().params());
            Assertions.assertTrue(result.isSuccessful(), "add " + index + ": " + result.error());
            var tx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
            Assertions.assertTrue(chain.reportAndCheckRedeemers("denylist/add-" + index, tx) > 0);
            chain.submit(tx);
            presentKeys.add(entry.getKey());
            assertDenylistLinks(chain, reg.getDenylistPolicyId(), List.copyOf(presentKeys));
        }

        var duplicate = st.handler().buildAddToBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.AddToBlacklistRequest(policyId, reg.getSecurityAssetNameHex(),
                        entries.get(1).getValue(), BootstrapFixture.ADMIN.baseAddress()), st.boot().params());
        Assertions.assertFalse(duplicate.isSuccessful());
        Assertions.assertTrue(String.valueOf(duplicate.error()).contains("already on this token's denylist"));

        int[] removeOrder = {2, 0, 3, 1}; // middle, first, tail, final
        for (int index : removeOrder) {
            var entry = entries.get(index);
            var result = st.handler().buildRemoveFromBlacklistTransaction(
                    new org.cardanofoundation.cip113.service.module.capabilities
                            .BlacklistManageable.RemoveFromBlacklistRequest(policyId,
                            reg.getSecurityAssetNameHex(), entry.getValue(), BootstrapFixture.ADMIN.baseAddress()),
                    st.boot().params());
            Assertions.assertTrue(result.isSuccessful(), "remove " + index + ": " + result.error());
            var tx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
            Assertions.assertTrue(chain.reportAndCheckRedeemers("denylist/remove-" + index, tx) > 0);
            chain.submit(tx);
            presentKeys.remove(entry.getKey());
            assertDenylistLinks(chain, reg.getDenylistPolicyId(), List.copyOf(presentKeys));
            Assertions.assertTrue(chain.findUtxoByUnit(reg.getDenylistPolicyId()
                    + HexUtil.encodeHexString("Node".getBytes(StandardCharsets.UTF_8))
                    + entry.getKey()).isEmpty(), "removed NFT remains unspent");
        }

        var absent = st.handler().buildRemoveFromBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.RemoveFromBlacklistRequest(policyId, reg.getSecurityAssetNameHex(),
                        entries.get(0).getValue(), BootstrapFixture.ADMIN.baseAddress()), st.boot().params());
        Assertions.assertFalse(absent.isSuccessful());
        Assertions.assertTrue(String.valueOf(absent.error()).contains("not on this token's denylist"));
    }

    private static void assertDenylistLinks(OfflineChain chain, String policyId, List<String> expectedKeys)
            throws Exception {
        String nodePrefix = HexUtil.encodeHexString("Node".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i <= expectedKeys.size(); i++) {
            String unit = policyId + (i == 0 ? "" : nodePrefix + expectedKeys.get(i - 1));
            var utxo = chain.findUtxoByUnit(unit).orElseThrow();
            var element = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData)
                    com.bloxbean.cardano.client.plutus.spec.PlutusData.deserialize(
                            HexUtil.decodeHexString(utxo.getInlineDatum()));
            var link = (com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData)
                    element.getData().getPlutusDataList().get(1);
            String expectedNext = i == expectedKeys.size() ? null : expectedKeys.get(i);
            if (expectedNext == null) {
                Assertions.assertEquals(1, link.getAlternative(), "tail must have no link");
            } else {
                Assertions.assertEquals(0, link.getAlternative(), "predecessor must have a link");
                var next = (com.bloxbean.cardano.client.plutus.spec.BytesPlutusData)
                        link.getData().getPlutusDataList().getFirst();
                Assertions.assertEquals(expectedNext, HexUtil.encodeHexString(next.getValue()));
            }
        }
    }

    @Test
    public void rwaTokenTransferAfterDenylistRemovalUsesSeparateCoveringNodes() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), true);
        var chain = st.chain();
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);
        String alice = stakeCredHex(BootstrapFixture.ALICE.baseAddress());
        String admin = stakeCredHex(BootstrapFixture.ADMIN.baseAddress());
        String lower = alice.compareTo(admin) < 0 ? alice : admin;
        String upper = alice.compareTo(admin) < 0 ? admin : alice;
        java.util.Map.Entry<String, String> beforeBoth = null;
        java.util.Map.Entry<String, String> between = null;
        java.util.Map.Entry<String, String> toRemove = null;
        for (int account = 2; account < 100; account++) {
            String address = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                    BootstrapFixture.NETWORK, BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC,
                    account, 0).baseAddress();
            String key = stakeCredHex(address);
            if (beforeBoth == null && key.compareTo(lower) < 0) beforeBoth = java.util.Map.entry(key, address);
            else if (between == null && key.compareTo(lower) > 0 && key.compareTo(upper) < 0)
                between = java.util.Map.entry(key, address);
            else if (toRemove == null && !key.equals(alice) && !key.equals(admin))
                toRemove = java.util.Map.entry(key, address);
            if (beforeBoth != null && between != null && toRemove != null) break;
        }
        Assertions.assertNotNull(beforeBoth);
        Assertions.assertNotNull(between);
        Assertions.assertNotNull(toRemove);
        for (var entry : List.of(beforeBoth, between, toRemove)) {
            var add = st.handler().buildAddToBlacklistTransaction(
                    new org.cardanofoundation.cip113.service.module.capabilities
                            .BlacklistManageable.AddToBlacklistRequest(policyId, reg.getSecurityAssetNameHex(),
                            entry.getValue(), BootstrapFixture.ADMIN.baseAddress()), st.boot().params());
            Assertions.assertTrue(add.isSuccessful(), "add failed: " + add.error());
            var tx = Transaction.deserialize(HexUtil.decodeHexString(add.unsignedCborTx()));
            Assertions.assertTrue(chain.reportAndCheckRedeemers("denylist/transfer-setup-add", tx) > 0);
            chain.submit(tx);
        }
        var remove = st.handler().buildRemoveFromBlacklistTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities
                        .BlacklistManageable.RemoveFromBlacklistRequest(policyId, reg.getSecurityAssetNameHex(),
                        toRemove.getValue(), BootstrapFixture.ADMIN.baseAddress()), st.boot().params());
        Assertions.assertTrue(remove.isSuccessful(), "remove failed: " + remove.error());
        var removeTx = Transaction.deserialize(HexUtil.decodeHexString(remove.unsignedCborTx()));
        Assertions.assertTrue(chain.reportAndCheckRedeemers("denylist/transfer-setup-remove", removeTx) > 0);
        chain.submit(removeTx);

        chain.seedAda("offline-transfer-populated-denylist", BootstrapFixture.ALICE.baseAddress(), 8, 100);
        var transfer = st.handler().buildTransferTransaction(
                new org.cardanofoundation.cip113.model.TransferTokenRequest(
                        BootstrapFixture.ALICE.baseAddress(), policyId + reg.getSecurityAssetNameHex(),
                        "400", BootstrapFixture.ADMIN.baseAddress(), null, null, null,
                        null, null, null, null), st.boot().params());
        Assertions.assertTrue(transfer.isSuccessful(), "allowed transfer failed: " + transfer.error());
        var transferTx = Transaction.deserialize(HexUtil.decodeHexString(transfer.unsignedCborTx()));
        Assertions.assertTrue(chain.reportAndCheckRedeemers("denylist/transfer-after-removal", transferTx) > 0);
    }

    /**
     * The standalone member-root-hash publisher validates.
     *
     * <p>{@code UpdateMemberRootHash} is reachable two ways — as one of the thirteen
     * {@code GlobalStateSpendAction}s through the update chain, and through this dedicated
     * builder, which is what the KYC sync path calls. They are different code, so covering
     * the chain does not cover this.
     */
    @Test
    public void rwaTokenUpdateMemberRootHashEvaluates() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var policyId = st.built().programmableTokenPolicyId();

        var adminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        byte[] newRoot = HexUtil.decodeHexString(
                "1111111111111111111111111111111111111111111111111111111111111111");

        var result = st.handler().buildUpdateMemberRootHashTransaction(
                policyId, newRoot, BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertTrue(result.isSuccessful(),
                "update-member-root-hash build failed: " + result.error());
        var tx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
        Assertions.assertTrue(
                chain.reportAndCheckRedeemers("rwa-token/member-root-hash", tx) > 0,
                "no redeemer was evaluated on the member-root-hash update");
    }

    @Test
    public void rwaTokenSeededFirstMintBuildsBeforeGenesisIsOnChain() throws Exception {
        var st = rwaTokenChain(null, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), false, true);
        var tx = Transaction.deserialize(HexUtil.decodeHexString(st.built().registrationCborHex()));
        Assertions.assertTrue(st.chain().reportAndCheckRedeemers("rwa-token/seeded-registration", tx) > 0,
                "seeded first-mint registration must evaluate against its chained genesis root");
        byte[] aliceStake = new Address(BootstrapFixture.ALICE.baseAddress())
                .getDelegationCredentialHash().orElseThrow();
        Assertions.assertTrue(st.allowlist().inclusionProof(st.built().programmableTokenPolicyId(),
                aliceStake, (short) 0, System.currentTimeMillis()).isPresent(),
                "once genesis is live, the ordinary proof path must find the exact saved snapshot");
    }

    @Test
    public void rwaTokenEarlierUnsignedChainStillWorksAfterRetry() throws Exception {
        var st = rwaTokenChain(null, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress(), false, true, true);
        byte[] aliceStake = new Address(BootstrapFixture.ALICE.baseAddress())
                .getDelegationCredentialHash().orElseThrow();
        Assertions.assertTrue(st.allowlist().inclusionProof(st.built().programmableTokenPolicyId(),
                aliceStake, (short) 0, System.currentTimeMillis()).isPresent(),
                "the earlier chain must retain its proof if it lands after a retry is built");
    }

    /**
     * The admin global-state actions validate.
     *
     * <p>{@code global_state_spend_validator}'s hash moved at the 2026-08-21 upstream, and
     * every admin action spends the GlobalState UTxO through it. The redeemer shape did not
     * change — all thirteen {@code GlobalStateSpendAction} constructors kept their order —
     * but "the shape is unchanged" is a claim about the type, not about whether the
     * validator still accepts what we build.
     *
     * <p>Three representative actions, chained: each transaction spends the previous one's
     * GlobalState output, which is the property that makes a multi-field admin edit possible
     * at all (the validator forbids batching, so N field changes are N transactions).
     */
    @Test
    public void rwaTokenGlobalStateAdminActionsEvaluate() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var policyId = st.built().programmableTokenPolicyId();
        var label = "rwa-token";

        var adminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());

        java.util.function.BiFunction<String, Object[], org.cardanofoundation.cip113.service
                .module.RwaTokenModuleHandler.GsChangeSpec> spec =
                (action, f) -> new org.cardanofoundation.cip113.service.module
                        .RwaTokenModuleHandler.GsChangeSpec(
                        action,
                        (Boolean) f[0], (String) f[1], null, null, null, null, null,
                        (Boolean) f[2], (Boolean) f[3], null, null, null);

        var changes = java.util.List.of(
                spec.apply("ModifySecurityInfo", new Object[]{null, "44deadbeef", null, null}),
                spec.apply("SetRequiresSenderKyc", new Object[]{null, null, Boolean.TRUE, null}),
                spec.apply("PauseTransfers", new Object[]{Boolean.TRUE, null, null, null}));

        var result = handler.buildGlobalStateUpdateChain(
                policyId, changes, BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertTrue(result.isSuccessful(),
                "global-state update chain build failed: " + result.error());
        Assertions.assertEquals(3, result.metadata().size(),
                "one transaction per action — the validator forbids batching field changes");

        int totalEvaluated = 0;
        for (int i = 0; i < result.metadata().size(); i++) {
            var tx = Transaction.deserialize(HexUtil.decodeHexString(result.metadata().get(i)));
            totalEvaluated += chain.reportAndCheckRedeemers(label + "/global-state[" + i + "]", tx);
        }
        Assertions.assertTrue(totalEvaluated >= 3,
                "each chained admin transaction must have run the global-state spend validator; "
                + "only " + totalEvaluated + " redeemers were genuinely evaluated");
    }

    /**
     * T-093 — a REAL RotateAdmin transaction, built by cardano-client-lib, evaluated offline, and
     * exported so the frontend's CIP-21 stack can be measured against bytes it has never seen.
     *
     * <p>Two things this proves that nothing else in the suite does.
     *
     * <p><b>The dual-signature branch is satisfiable.</b> {@code global_state.ak} calls
     * {@code must_be_signed_by_credential} TWICE on this branch — the outgoing admin from the INPUT
     * datum's {@code admin_credential_hash}, and the incoming {@code new_admin_credential_hash} —
     * and both come from the body's {@code required_signers}. Evaluating the redeemer here runs the
     * real validator against a real script context, so a green result means the branch accepts a
     * transaction shaped the way the builder shapes it. Before this, the only evidence was reading
     * the builder.
     *
     * <p><b>It is the only CCL-encoded transaction of this shape anywhere.</b> Every fixture the
     * frontend's canonicaliser and CIP-21 walker have ever seen is EVOLUTION-built
     * ({@code test-fixtures/real-preview-txs.json} is the bootstrap ceremony). This one carries
     * {@code required_signers} with two entries — a Conway set that never appeared in a ceremony
     * transaction — plus redeemers, collateral and an inline datum, written by cbor-java. The hex
     * is dumped to {@code build/} for the frontend side of T-093; it is NOT asserted here, because
     * what CIP-21 makes of it is the frontend's question.
     *
     * <p>⚠ The keys are {@code BootstrapFixture}'s test keys, so the exported fixture carries no
     * operator material — which is what the epic requires of anything committed to this PUBLIC
     * repository.
     */
    @Test
    public void rwaTokenRotateAdminEvaluatesAndIsExportedForCip21() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var policyId = st.built().programmableTokenPolicyId();

        var outgoingAdminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        // The incoming admin is a DIFFERENT real key, not a literal: required_signers must end up
        // with two distinct entries or the dual-signature property is vacuous.
        var incomingAdminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ALICE.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        Assertions.assertNotEquals(outgoingAdminPkh, incomingAdminPkh,
                "the two admins must differ, or this test proves nothing about dual signing");

        var rotate = new org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.GsChangeSpec(
                "RotateAdmin",
                null, null, null, null, null, null, null, null, null, null,
                incomingAdminPkh,   // newAdminCredentialHashHex
                null);

        var result = handler.buildGlobalStateUpdateChain(
                policyId, java.util.List.of(rotate),
                BootstrapFixture.ADMIN.baseAddress(), outgoingAdminPkh, boot.params());
        Assertions.assertTrue(result.isSuccessful(),
                "RotateAdmin build failed: " + result.error());
        Assertions.assertEquals(1, result.metadata().size(),
                "a rotation is ONE transaction — the one-change rule the epic makes structural");

        var unsignedHex = result.metadata().get(0);
        var tx = Transaction.deserialize(HexUtil.decodeHexString(unsignedHex));

        // ⛔ BOTH CREDENTIALS MUST BE DECLARED. Passing only the fee payer means
        // new_admin_credential_hash never reaches extra_signatories and the branch cannot validate.
        var declared = tx.getBody().getRequiredSigners().stream()
                .map(HexUtil::encodeHexString)
                .map(String::toLowerCase)
                .toList();
        Assertions.assertTrue(declared.contains(outgoingAdminPkh.toLowerCase()),
                "required_signers is missing the OUTGOING admin: " + declared);
        Assertions.assertTrue(declared.contains(incomingAdminPkh.toLowerCase()),
                "required_signers is missing the INCOMING admin: " + declared);
        Assertions.assertEquals(2, declared.size(),
                "fee payer and outgoing admin are the same wallet here, so exactly two are expected; "
                + "got " + declared);

        // The validator itself, not our reading of it.
        int evaluated = chain.reportAndCheckRedeemers("rwa-token/rotate-admin", tx);
        Assertions.assertTrue(evaluated >= 1,
                "the global-state spend validator did not actually run, so the dual-signature "
                + "branch is unproven; redeemers evaluated: " + evaluated);

        // Export for the frontend half of T-093. build/ is not committed; the epic says the
        // committed fixture is built from throwaway keys, and these are exactly that.
        var out = java.nio.file.Path.of("build", "rotate-admin-unsigned.hex");
        java.nio.file.Files.createDirectories(out.getParent());
        java.nio.file.Files.writeString(out, unsignedHex + System.lineSeparator());
        System.out.println("[T-093] RotateAdmin unsigned CBOR -> " + out.toAbsolutePath()
                + " (" + unsignedHex.length() + " hex chars)");
        System.out.println("[T-093] required_signers = " + declared);
        System.out.println("[T-093] outgoing=" + outgoingAdminPkh + " incoming=" + incomingAdminPkh);
    }

    /**
     * T-098 — a rotation is a chain of EXACTLY one, and the refusal is proven in both shapes.
     *
     * <p>RotateAdmin is the only global-state action needing a second signature, collected out of
     * band from the incoming admin. In a chain, every transaction after the first depends on the
     * previous one's hash, so a witness gathered remotely can only ever cover {@code tx[0]} — the
     * remote party cannot even see the rest.
     *
     * <p>⛔ THE SECOND CASE IS THE POINT. A rule phrased as "RotateAdmin may not appear alongside
     * any OTHER change" is satisfied by {@code [RotateAdmin, RotateAdmin]}, which has exactly the
     * defect the rule exists to prevent. That is why the implementation tests
     * {@code changes.size() != 1} rather than comparing actions, and why this test asserts the
     * two-rotation case explicitly rather than trusting the single-mixed case to cover it.
     */
    @Test
    public void rwaTokenRotateAdminMustBeTheOnlyChangeInItsChain() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var boot = st.boot();
        var handler = st.handler();
        var policyId = st.built().programmableTokenPolicyId();

        var adminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        var incomingPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ALICE.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());

        java.util.function.Function<String, org.cardanofoundation.cip113.service.module
                .RwaTokenModuleHandler.GsChangeSpec> rotate = pkh ->
                new org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.GsChangeSpec(
                        "RotateAdmin", null, null, null, null, null, null, null, null, null, null,
                        pkh, null);
        var pause = new org.cardanofoundation.cip113.service.module
                .RwaTokenModuleHandler.GsChangeSpec(
                "PauseTransfers", Boolean.TRUE, null, null, null, null, null, null, null, null,
                null, null, null);

        // (a) a rotation mixed with another change
        var mixed = handler.buildGlobalStateUpdateChain(
                policyId, java.util.List.of(rotate.apply(incomingPkh), pause),
                BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertFalse(mixed.isSuccessful(),
                "a rotation batched with another change must be refused");
        Assertions.assertTrue(mixed.error().contains("RotateAdmin must be the only change"),
                "the refusal must name the rule; got: " + mixed.error());
        Assertions.assertTrue(mixed.error().contains("PauseTransfers"),
                "the refusal must name what else was in the chain; got: " + mixed.error());

        // (b) ⛔ TWO rotations — the case a difference check would permit.
        var twice = handler.buildGlobalStateUpdateChain(
                policyId, java.util.List.of(rotate.apply(incomingPkh), rotate.apply(adminPkh)),
                BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertFalse(twice.isSuccessful(),
                "two rotations in one chain must be refused — each later body depends on the "
                + "previous transaction's hash, so one out-of-band witness cannot cover both");
        Assertions.assertTrue(twice.error().contains("RotateAdmin must be the only change"),
                "the refusal must name the rule; got: " + twice.error());

        // (c) and a lone rotation still builds, or the rule has eaten the feature.
        var lone = handler.buildGlobalStateUpdateChain(
                policyId, java.util.List.of(rotate.apply(incomingPkh)),
                BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertTrue(lone.isSuccessful(),
                "a lone rotation must still build: " + lone.error());
        Assertions.assertEquals(1, lone.metadata().size());

        // (d) a multi-change chain WITHOUT a rotation is untouched — the rule is scoped.
        var noRotate = handler.buildGlobalStateUpdateChain(
                policyId, java.util.List.of(pause, pause),
                BootstrapFixture.ADMIN.baseAddress(), adminPkh, boot.params());
        Assertions.assertTrue(noRotate.isSuccessful(),
                "batching is still allowed for actions that need no second signature: "
                + noRotate.error());
    }

    /**
     * T-101 end to end — a SECOND power user is added, and the real validator accepts it.
     *
     * <p>This is the half {@code PowerUserInsertionTest} deliberately could not cover: it tests the
     * ordering decision in isolation, and the thing that actually had to work is a built transaction
     * the validator accepts. The offline harness can do it — {@code rwaTokenUtxoProvider} already
     * serves {@code findAllCurrentUtxosFromBlockfrost} and {@code findUtxosByPolicy}, which is what
     * the list walk and the anchor poll need, so no extension was required after all.
     *
     * <p>⛔ WHAT A GREEN RESULT HERE MEANS, AND WHAT IT DOES NOT. The redeemer is evaluated by Aiken
     * against a real script context, so the power-users mint and spend validators genuinely accept
     * the spliced list. It does NOT prove a hardware wallet will sign it (T-095) and it does not
     * submit anything (T-102). It does retire the reason T-102 was carrying this scope.
     *
     * <p>The first insertion is the one the old code could do. The SECOND is the one that was
     * impossible, and it is the one a rotated-in admin needs: mint, burn and pause each check the
     * caller's own node as a reference input, so without it a rotation is a dead end.
     */
    @Test
    public void rwaTokenSecondPowerUserInsertionEvaluates() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var handler = st.handler();
        var policyId = st.built().programmableTokenPolicyId();

        // Two distinct additional power users, derived so their keys differ from the admin's.
        var second = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                BootstrapFixture.NETWORK, BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC, 2, 0);
        var third = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                BootstrapFixture.NETWORK, BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC, 3, 0);

        java.util.function.Function<com.bloxbean.cardano.client.account.Account, String> pkhOf =
                acct -> HexUtil.encodeHexString(
                        new com.bloxbean.cardano.client.address.Address(acct.baseAddress())
                                .getPaymentCredentialHash().orElseThrow());

        var secondPkh = pkhOf.apply(second);
        var thirdPkh = pkhOf.apply(third);
        Assertions.assertNotEquals(secondPkh, thirdPkh);

        var puPolicy = st.registrations().get(policyId).getPowerUsersPolicyId();

        // ⚑ GENESIS ALREADY ADDED ONE. buildFullRegistrationChain grants the bootstrap admin ALL
        // capabilities as part of registration, so the list is root → Node(admin) before this test
        // touches it — which means EVERY insertion below is already a "second or later" one, the
        // case the v1 limitation blocked. Expectations are tracked as a sorted set rather than
        // assumed, because the chain is key-ascending and these keys are derived, not chosen.
        var adminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        var expected = new java.util.ArrayList<String>(List.of(adminPkh));
        expected.sort(String::compareTo);
        assertDenylistLinks(chain, puPolicy, List.copyOf(expected));

        // ── insertion #1 (the list's SECOND node) ──
        var first = handler.buildAddPowerUserTransaction(
                policyId, secondPkh, 0b00001 /* ADMIN */, BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertTrue(first.isSuccessful(),
                "the first power-user insertion must still work: " + first.error());
        var firstTx = Transaction.deserialize(HexUtil.decodeHexString(first.unsignedCborTx()));
        Assertions.assertTrue(
                chain.reportAndCheckRedeemers("rwa-token/add-power-user[1]", firstTx) > 0,
                "the power-users validators did not run for the first insertion");
        chain.submit(firstTx);
        expected.add(secondPkh);
        expected.sort(String::compareTo);
        assertDenylistLinks(chain, puPolicy, List.copyOf(expected));

        // ── insertion #2 (the list's THIRD node) ──
        var secondAdd = handler.buildAddPowerUserTransaction(
                policyId, thirdPkh, 0b00010 /* MINTER */, BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertTrue(secondAdd.isSuccessful(),
                "a SECOND power-user insertion must build — this is what the v1 limitation blocked, "
                + "and what a rotated-in admin needs: " + secondAdd.error());
        var secondTx = Transaction.deserialize(HexUtil.decodeHexString(secondAdd.unsignedCborTx()));
        Assertions.assertTrue(
                chain.reportAndCheckRedeemers("rwa-token/add-power-user[2]", secondTx) > 0,
                "the power-users validators did not run for the second insertion, so acceptance is "
                + "unproven");
        chain.submit(secondTx);

        // ⛔ AND THE SPLICE MUST NOT HAVE TRUNCATED THE LIST. The old code hardcoded the new node's
        // link to None; inserting mid-list that way drops everything after the anchor from the chain
        // while its NFT stays on chain. assertDenylistLinks walks root → … → tail and insists every
        // expected key is reachable IN ORDER, which is exactly the property that would break.
        //
        // Reusing the denylist assertion is deliberate: both lists are the same
        // linkedListElement(payload, link) structure, and a second copy of this walk would be a
        // second place for it to be subtly wrong.
        expected.add(thirdPkh);
        expected.sort(String::compareTo);   // the chain is key-ascending
        assertDenylistLinks(chain, puPolicy, List.copyOf(expected));

        // And a third insertion, so "any position" is not just "one more than before".
        var third3 = com.bloxbean.cardano.client.account.Account.createFromMnemonic(
                BootstrapFixture.NETWORK, BootstrapFixture.OFFLINE_DERIVATION_MNEMONIC, 4, 0);
        var thirdAddPkh = pkhOf.apply(third3);
        var thirdAdd = handler.buildAddPowerUserTransaction(
                policyId, thirdAddPkh, 0b00100, BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertTrue(thirdAdd.isSuccessful(), "third insertion: " + thirdAdd.error());
        var thirdTx = Transaction.deserialize(HexUtil.decodeHexString(thirdAdd.unsignedCborTx()));
        Assertions.assertTrue(
                chain.reportAndCheckRedeemers("rwa-token/add-power-user[3]", thirdTx) > 0);
        chain.submit(thirdTx);
        expected.add(thirdAddPkh);
        expected.sort(String::compareTo);
        assertDenylistLinks(chain, puPolicy, List.copyOf(expected));

        // ⛔ A DUPLICATE IS REFUSED, not silently added twice.
        var dup = handler.buildAddPowerUserTransaction(
                policyId, secondPkh, 0b00001, BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertFalse(dup.isSuccessful(), "adding an existing power user must be refused");
        Assertions.assertTrue(String.valueOf(dup.error()).contains("already a power user"),
                "the refusal must say why; got: " + dup.error());
    }

    /**
     * A seizure actually validates on chain.
     *
     * <p>The RWA token's regulatory force-transfer path is CIP-113's
     * {@code ThirdPartyAct} branch, gated by this module's
     * {@code third_party_transfer_logic_script} and the {@code can_force_transfer}
     * power-user role. The platform advertised no seize capability at all for this
     * module until now — {@code ComplianceOperationsService} answered every request
     * with "does not support seize operations" — so this is the first coverage the path
     * has, and it drives the real builder rather than asserting on shape.
     *
     * <p>Alice receives the first mint; the ADMIN (the bootstrap power user, and the only
     * holder of {@code can_force_transfer}) seizes it to itself.
     */
    @Test
    public void rwaTokenSeizeEvaluates() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());
        var label = "rwa-token";

        Assertions.assertNotNull(reg.getThirdPartyTransferLogicRefIndex(),
                "third_party_transfer_logic must be published — it does not fit inline");

        var policyId = built.programmableTokenPolicyId();
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(built.registrationCborHex()));
        var regTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(regTx.serialize());
        var userToken = Cip68Evidence.tokensOfPolicy(regTx, policyId).stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the registration minted no user token"));

        log.info("[{}/seize] seizing {}#{} (asset {} qty {}) from Alice to the admin",
                label, regTxHash, userToken.outputIndex(), userToken.assetNameHex(),
                userToken.quantity());

        var seizeRequest = new org.cardanofoundation.cip113.service.module.capabilities
                .Seizeable.SeizeRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId + reg.getSecurityAssetNameHex(),
                regTxHash,
                userToken.outputIndex(),
                BootstrapFixture.ADMIN.baseAddress());

        var result = handler.buildSeizeTransaction(seizeRequest, boot.params());
        Assertions.assertTrue(result.isSuccessful(), "seize build failed: " + result.error());

        var seizeTx = Transaction.deserialize(HexUtil.decodeHexString(result.unsignedCborTx()));
        int size = seizeTx.serialize().length;
        log.info("[{}/seize] serialized size = {} bytes", label, size);

        int evaluated = chain.reportAndCheckRedeemers(label + "/seize", seizeTx);
        Assertions.assertTrue(evaluated > 0,
                "no redeemer was genuinely evaluated on the seize — the third-party transfer "
                + "validator and prog-logic-global did not run, so this proves nothing");
        Assertions.assertTrue(size < 16384,
                "seize exceeds the 16384-byte ledger limit at " + size + " bytes");
    }

    /**
     * A seizure by someone who is not a power user at all is refused before it is built.
     *
     * <p>NAMED FOR WHAT IT ACTUALLY COVERS. Alice holds no power-user node, so the builder
     * refuses at the node lookup and never reaches the {@code can_force_transfer} bit
     * check. The capability gate itself — a registered power user who holds some roles but
     * NOT {@code can_force_transfer} — is covered on chain by
     * {@code force_transfer_rejected_without_can_force_transfer} in
     * {@code validators/third_party_transfer_logic_script.ak}, and is not exercised here:
     * doing so needs a second power-user node in the fixture with a restricted capability
     * bitfield, which this chain does not build.
     *
     * <p>The off-chain refusal still matters on its own terms: without it the operator
     * signs a transaction the chain will reject, and learns why only at submit.
     */
    @Test
    public void rwaTokenSeizeRefusedForNonPowerUser() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress());
        var boot = st.boot();
        var handler = st.handler();
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());

        var policyId = built.programmableTokenPolicyId();
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(built.registrationCborHex()));
        var regTxHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil
                .getTxHash(regTx.serialize());
        var userToken = Cip68Evidence.tokensOfPolicy(regTx, policyId).stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();

        var seizeRequest = new org.cardanofoundation.cip113.service.module.capabilities
                .Seizeable.SeizeRequest(
                BootstrapFixture.ALICE.baseAddress(),          // not a power user
                policyId + reg.getSecurityAssetNameHex(),
                regTxHash,
                userToken.outputIndex(),
                BootstrapFixture.ADMIN.baseAddress());

        var result = handler.buildSeizeTransaction(seizeRequest, boot.params());
        Assertions.assertFalse(result.isSuccessful(),
                "a caller who is not a power user must not get a signable seizure");
        Assertions.assertTrue(result.error() != null && result.error().contains("power user"),
                "the refusal must name the missing power-user node, not some unrelated "
                + "precondition — it read: " + result.error());
        log.info("[rwa-token/seize] refused as expected: {}", result.error());
    }

    /**
     * The CIP-68 pair is minted by the REGISTRATION, and ordinary mints stay single-asset.
     *
     * <p>This moved. The reference NFT used to be completed by the first ordinary mint,
     * because the old contract rejected a second asset name at registration and did not
     * check for one on the mint path. The current contract inverts that:
     * `only_permitted_assets_minted` admits the second name on `RegisterMint` ONLY, and
     * `MintBurn` passes `reference_mint_allowed = False`.
     *
     * <p>Tying creation to registration is what makes the NFT once-only — registration is
     * structurally a once-only event — and once-only is what lets it be exempt from the
     * supply cap and from the denylist and KYC gates.
     */
    @Test
    public void rwaTokenStructuralChainPublishesBothProvenanceRecords() throws Exception {
        var st = rwaTokenChain(null, 1_000_000L);
        Assertions.assertNotNull(st.built().cmtaProvenanceCborHex());
        Assertions.assertNotNull(st.built().issuanceProvenanceCborHex());
        Assertions.assertNull(st.built().publishScriptsCborHex());
    }

    @Test
    public void rwaTokenChainAndCip68Mint() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000", BootstrapFixture.ALICE.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var registrations = st.registrations();
        var built = st.built();
        var label = "rwa-token";
        var policyId = built.programmableTokenPolicyId();

        // ── the registration: this is where the CIP-68 pair is created ──
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(built.registrationCborHex()));
        Cip68Evidence.dumpOutputs(label + "/registration", regTx);
        var tokens = Cip68Evidence.tokensOfPolicy(regTx, policyId);
        for (var t : tokens) {
            log.info("[{}/registration]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }
        Assertions.assertEquals(2, tokens.size(),
                "the registration must mint the CIP-68 pair: user token + (100) reference");

        var userToken = tokens.stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no user token found"));
        var refToken = tokens.stream()
                .filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "the genesis cap of 1000000 is fungible, so the user token must carry (333)");
        Assertions.assertEquals(new BigInteger("1000"), userToken.quantity());
        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token, and the contract pins it at 1");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        // Output layout is load-bearing: the GS spend redeemer hardcodes
        // global_state_output_index = 3 and the new registry node sits at 2, so the
        // reference NFT must be APPENDED at 4 rather than inserted ahead of either.
        Assertions.assertEquals(0, userToken.outputIndex(), "user token must be output 0");
        Assertions.assertEquals(4, refToken.outputIndex(),
                "the reference NFT must be APPENDED at output 4 — inserting it earlier would"
                + " shift the registry node off 2 or the GlobalState off 3");

        // The user token sits at its recipient's stake credential, as always.
        //
        // The REFERENCE NFT does not, and this changed at the 2026-08-21 upstream.
        // `reference_nft_output_is_pinned` used to require a programmable-logic-base payment
        // credential plus any inline stake credential; it now requires
        // `credential_hash(owner) == admin_credential_hash` — the stake half must hash to the
        // credential GlobalState names as admin, i.e. the one that SIGNS, which for a base
        // address is the PAYMENT key hash and not the delegation one. Asserting the old
        // delegation hash here would pass only for a builder the chain rejects.
        //
        // (The PLB payment credential is no longer demanded by this validator either. We
        // still use it, because the reference NFT is a token of this policy and CIP-113
        // confines those to the base — so the assertion below is now pinning OUR choice
        // rather than the module's requirement.)
        var plb = boot.params().programmableLogicBase().scriptHash();
        var adminCredentialHash = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/registration", regTx,
                userToken.outputIndex(), plb, stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/registration", regTx,
                refToken.outputIndex(), plb, adminCredentialHash);
        // …and the same output must carry NONE of the RWA token, the other half of the
        // tightened check.
        Assertions.assertEquals(0,
                Cip68Evidence.tokensOfPolicy(regTx, policyId).stream()
                        .filter(t -> t.outputIndex() == refToken.outputIndex())
                        .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                        .count(),
                "the reference-NFT output must hold no RWA token — "
                + "reference_nft_output_is_pinned now requires quantity_of(security) == 0");
        Cip68Evidence.assertDatumRoundTrip(label + "/registration", regTx, refToken.outputIndex(), METADATA);

        Assertions.assertTrue(registrations.get(policyId).isCip68ReferenceMinted(),
                "the row must record that the reference NFT was created, so nothing tries again");

        // ── an ordinary mint afterwards: single asset, and it must simply work ──
        var secondMint = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId,
                registrations.get(policyId).getSecurityAssetNameHex(),
                "500",
                BootstrapFixture.ALICE.baseAddress(),
                null,
                null);
        var secondResult = handler.buildMintTransaction(secondMint, boot.params());
        Assertions.assertTrue(secondResult.isSuccessful(),
                "an ordinary mint of a CIP-68 token must work — the stored metadata is the record "
                + "of an NFT that already exists, not a request to mint another: "
                + secondResult.error());
        var secondTx = Transaction.deserialize(HexUtil.decodeHexString(secondResult.unsignedCborTx()));
        Assertions.assertTrue(chain.reportAndCheckRedeemers(label + "/mint2", secondTx) > 0,
                "no redeemer was genuinely evaluated on the ordinary mint");
        Assertions.assertEquals(1, Cip68Evidence.tokensOfPolicy(secondTx, policyId).size(),
                "an ordinary mint must carry ONE asset name under the issuance policy — the "
                + "authority's MintBurn branch admits no second name");

        // ── and asking a mint to create a reference NFT must be refused, with the rule ──
        var askForRef = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId,
                registrations.get(policyId).getSecurityAssetNameHex(),
                "500",
                BootstrapFixture.ALICE.baseAddress(),
                null,
                METADATA);
        var refused = handler.buildMintTransaction(askForRef, boot.params());
        Assertions.assertFalse(refused.isSuccessful(),
                "an ordinary mint must never carry the (100) — the contract rejects it");
        Assertions.assertTrue(refused.error().contains("REGISTRATION"),
                "the refusal must point at the registration path, got: " + refused.error());
    }
    /**
     * An ordinary mint of a CIP-68 token needs no chain lookup at all.
     *
     * <p>This used to be the hardest case in the file: the mint path had to decide whether
     * the (100) still needed minting, and answering it from the chain meant an indexer
     * outage could not be distinguished from "not minted yet" — so it refused rather than
     * guess. The question no longer arises. The reference NFT is created by the
     * registration and by nothing else, so a later mint neither asks nor cares, and the
     * stored metadata is the record of an NFT that already exists.
     */
    @Test
    public void rwaTokenRefusesToGuessWhenTheReferenceLookupFails() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress());
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);

        // The row still carries the metadata forever, which is exactly the condition that
        // used to trigger the lookup.
        Assertions.assertNotNull(reg.getCip68MetadataJson(),
                "precondition: the registration row keeps the metadata");

        var mint = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, reg.getSecurityAssetNameHex(), "250", null),
                st.boot().params());
        Assertions.assertTrue(mint.isSuccessful(),
                "an ordinary mint must not consult the chain about the reference NFT: "
                + mint.error());

        var mintTx = Transaction.deserialize(HexUtil.decodeHexString(mint.unsignedCborTx()));
        Assertions.assertTrue(st.chain().reportAndCheckRedeemers("rwa-token/no-lookup", mintTx) > 0,
                "no redeemer was genuinely evaluated");
        Assertions.assertEquals(1, Cip68Evidence.tokensOfPolicy(mintTx, policyId).size(),
                "one asset name under the issuance policy");
    }
    /**
     * The reference NFT can be created exactly once, and the registration is the only thing
     * that can create it.
     *
     * <p>This replaces a compare-and-set race that no longer exists. When the (100) was
     * completed by an ordinary mint, two concurrent mints could each hand out a signable
     * transaction, so the handler had to claim the right to mint it with a conditional
     * UPDATE. The contract now admits the second asset name on `RegisterMint` only, so
     * once-only follows from registration being once-only — the race is gone by
     * construction rather than by locking.
     */
    @Test
    public void rwaTokenReferenceMintIsClaimedByCompareAndSet() throws Exception {
        // WITH a first mint: the reference NFT rides on RegisterMint, so a CIP-68 token
        // registered structurally would have no (100) at all.
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000", BootstrapFixture.ALICE.baseAddress());
        var policyId = st.built().programmableTokenPolicyId();
        var reg = st.registrations().get(policyId);

        Assertions.assertTrue(reg.isCip68ReferenceMinted(),
                "the registration created the reference NFT, so the row must say so");

        // Every later attempt to create one is refused by the rule, not by a lock.
        for (int attempt = 1; attempt <= 2; attempt++) {
            var again = st.handler().buildMintTransaction(
                    mintTokenRequest(policyId, reg.getSecurityAssetNameHex(), "10", METADATA),
                    st.boot().params());
            Assertions.assertFalse(again.isSuccessful(),
                    "attempt " + attempt + " must be refused — exactly one (100) may ever exist");
            Assertions.assertTrue(again.error().contains("REGISTRATION"),
                    "the refusal must name where the reference NFT is actually minted, got: "
                    + again.error());
        }
    }
    /**
     * A cap of 1 bounds the amount OUTSTANDING, not lifetime issuance — a burn restores the
     * allowance — so the token is fungible and must carry (333), never (222).
     */
    @Test
    public void rwaTokenAtACapOfOneIsStillFungible() throws Exception {
        // A first mint of 1 against a cap of 1: exactly the boundary. A CIP-68 token must be
        // registered WITH a mint (the (100) rides on RegisterMint), and a cap of 1 leaves no
        // room for a second, so the label is read off the registration itself.
        var st = rwaTokenChain(METADATA, 1L, "1", BootstrapFixture.ALICE.baseAddress());
        var policyId = st.built().programmableTokenPolicyId();
        var registeredName = st.registrations().get(policyId).getSecurityAssetNameHex();

        log.info("[rwa-token/cap-1] registered asset name = {} (label {})",
                registeredName, Cip68.readLabel(registeredName));

        Assertions.assertEquals(Integer.valueOf(Cip68.LABEL_FT), Cip68.readLabel(registeredName),
                "a cap of 1 bounds the amount OUTSTANDING, not lifetime issuance — a burn restores "
                + "the allowance, so the token is fungible and must carry (333)");
        Assertions.assertEquals(Cip68.labeledAssetName(Cip68.LABEL_FT, BASE_ASSET_NAME_HEX),
                registeredName,
                "the on-chain name must be exactly the (333)-labelled base name");

        // And the label really did reach the chain, not just the database row: read the asset
        // name back off the evaluated registration transaction.
        var regTx = Transaction.deserialize(HexUtil.decodeHexString(st.built().registrationCborHex()));
        var minted = Cip68Evidence.tokensOfPolicy(regTx, policyId);
        var user = minted.stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no user token minted"));
        Assertions.assertEquals(Integer.valueOf(Cip68.LABEL_FT), user.label(),
                "the minted asset name must carry (333) on chain, not just in the DB row");
        Assertions.assertEquals(BigInteger.ONE, user.quantity(),
                "a cap of 1 permits exactly one unit outstanding");
    }

    // ------------------------------------------------------ freeze-and-seize regressions

    /**
     * M7: the transfer input selector must accept a UTxO holding exactly one unit.
     *
     * <p>The selector asked {@code quantity > 1}, which is not the question. The question is
     * "does this UTxO carry any of the token being moved", and a UTxO holding one unit does. With
     * {@code > 1} a freshly registered one-unit token — or the last unit of any holding — was
     * invisible, so the builder collected no inputs and reported {@code "Not enough funds"} for a
     * balance the wallet could plainly see.
     *
     * <p>The assertion is deliberately about <em>which</em> error appears. This offline fixture
     * has no initialised blacklist, so the transfer cannot complete either way; what changed is
     * that it now gets <em>past</em> the selector and fails at the blacklist proof instead. That
     * is a precise discriminator between the two behaviours, and it does not require standing up
     * a blacklist linked list to observe.
     */
    @Test
    public void freezeAndSeizeTransferSelectsAQuantityOneHolding() throws Exception {
        var fes = runFreezeAndSeizeRegistration(METADATA, "1");
        fes.chain().submit(fes.transaction());

        var tokens = Cip68Evidence.tokensOfPolicy(fes.transaction(), fes.issuancePolicyId());
        var userToken = tokens.stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();
        Assertions.assertEquals(BigInteger.ONE, userToken.quantity(),
                "precondition: the holding under test must be exactly one unit");

        fes.handler().setContext(
                org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                        .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                        .assetName(userToken.assetNameHex())
                        .build());

        var result = fes.handler().buildTransferTransaction(
                new org.cardanofoundation.cip113.model.TransferTokenRequest(
                        BootstrapFixture.ALICE.baseAddress(),
                        fes.issuancePolicyId() + userToken.assetNameHex(),
                        "1",
                        BootstrapFixture.ADMIN.baseAddress(),
                        null, null, null, null, null, null, null),
                fes.bootParams());

        String error = String.valueOf(result.error());
        log.info("[freeze-and-seize/transfer-qty1] successful={} error={}", result.isSuccessful(), error);
        Assertions.assertFalse(error.contains("Not enough funds"),
                "a one-unit holding must be visible to the input selector; got: " + error);
    }

    /** M6: transferring the (100) would rebuild it with Constr(0) and erase the metadata. */
    @Test
    public void freezeAndSeizeTransferRefusesTheReferenceToken() throws Exception {
        var fes = runFreezeAndSeizeRegistration(METADATA, "1000000");
        var tokens = Cip68Evidence.tokensOfPolicy(fes.transaction(), fes.issuancePolicyId());
        var refToken = tokens.stream()
                .filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();

        fes.handler().setContext(
                org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                        .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                        .build());

        var result = fes.handler().buildTransferTransaction(
                new org.cardanofoundation.cip113.model.TransferTokenRequest(
                        BootstrapFixture.ADMIN.baseAddress(),
                        fes.issuancePolicyId() + refToken.assetNameHex(),
                        "1",
                        BootstrapFixture.ALICE.baseAddress(),
                        null, null, null, null, null, null, null),
                fes.bootParams());

        Assertions.assertFalse(result.isSuccessful(), "transferring the (100) must be refused");
        String error = String.valueOf(result.error());
        Assertions.assertTrue(error.contains("(100)"),
                "the error must name the reference token, got: " + error);
        Assertions.assertTrue(error.contains("metadata"),
                "the error must explain the metadata would be erased, got: " + error);
    }

    /**
     * M8: seizing a (100) can never validate, so it must be refused rather than built.
     *
     * <p>{@code issuer_admin} is parameterised by the asset name. Registration parameterised it by
     * the USER-token name; a seizure keyed on the {@code (100)} name derives a different script, a
     * different reward credential, and a withdraw-0 from an account that was never registered.
     */
    @Test
    public void freezeAndSeizeSeizureRefusesTheReferenceToken() throws Exception {
        var fes = runFreezeAndSeizeRegistration(METADATA, "1000000");
        fes.chain().submit(fes.transaction());

        var tokens = Cip68Evidence.tokensOfPolicy(fes.transaction(), fes.issuancePolicyId());
        var refToken = tokens.stream()
                .filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow();

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();
        fes.handler().setContext(
                org.cardanofoundation.cip113.service.module.context.FreezeAndSeizeContext.builder()
                        .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                        .issuerAdminPkh(adminPkh)
                        .build());

        var result = fes.handler().buildSeizeTransaction(
                new org.cardanofoundation.cip113.service.module.capabilities.Seizeable.SeizeRequest(
                        BootstrapFixture.ADMIN.baseAddress(),
                        fes.issuancePolicyId() + refToken.assetNameHex(),
                        fes.transaction().getBody().getInputs().getFirst().getTransactionId(),
                        0,
                        BootstrapFixture.ADMIN.baseAddress()),
                fes.bootParams());

        Assertions.assertFalse(result.isSuccessful(), "seizing the (100) must be refused");
        String error = String.valueOf(result.error());
        Assertions.assertTrue(error.contains("(100)"),
                "the error must name the reference token, got: " + error);
        Assertions.assertTrue(error.contains("issuer_admin"),
                "the error must explain the reward-credential mismatch, got: " + error);
    }

    /**
     * M10: the blacklist init and the registration must agree about CIP-68, and disagreeing must
     * be caught <em>before</em> the transaction is built.
     *
     * <p>The init is the only place {@code issuer_admin}'s reward account is registered, and this
     * registration withdraws-0 from it. {@code issuer_admin} is parameterized by the asset name,
     * so the labelled and unlabelled forms are different credentials at different reward
     * addresses. A mismatch is rejected on chain with {@code WithdrawalsNotInRewardsCERTS} —
     * after the user has signed and paid for the init, whose deposit is then gone.
     */
    @Test
    public void freezeAndSeizeRegistrationRefusesACip68MismatchWithTheBlacklistInit() throws Exception {
        // Registration WITH CIP-68, init recorded WITHOUT.
        var a = attemptFreezeAndSeizeRegistration(METADATA, "1000000", Boolean.FALSE);
        Assertions.assertFalse(a.context().isSuccessful(), "a CIP-68 mismatch must be refused");
        Assertions.assertNull(a.context().unsignedCborTx(),
                "the refusal must come BEFORE building — no CBOR may be returned");
        Assertions.assertTrue(String.valueOf(a.context().error()).contains("CIP-68 mismatch"),
                "error must name the mismatch, got: " + a.context().error());

        // ...and the reverse: registration WITHOUT, init recorded WITH.
        var b = attemptFreezeAndSeizeRegistration(null, "1000000", Boolean.TRUE);
        Assertions.assertFalse(b.context().isSuccessful(), "the reverse mismatch must also be refused");
        Assertions.assertNull(b.context().unsignedCborTx(), "no CBOR on a refusal");
        Assertions.assertTrue(String.valueOf(b.context().error()).contains("CIP-68 mismatch"),
                "error must name the mismatch, got: " + b.context().error());

        // A row predating the column has no evidence either way, so the check must stay SILENT
        // rather than block a registration that is very likely fine.
        var legacy = attemptFreezeAndSeizeRegistration(METADATA, "1000000", null);
        Assertions.assertTrue(legacy.context().isSuccessful(),
                "a null flag means 'no evidence' and must not block: " + legacy.context().error());
    }

    // ------------------------------------------------------------------ plumbing

    private record DummyResult(OfflineChain chain,
                               Transaction transaction,
                               String issuancePolicyId,
                               String plbScriptHash) {
    }

    private static OfflineChain chainOf(DummyResult result) {
        return result.chain();
    }

    /**
     * Drive the real {@link org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler}
     * through one registration on a fresh offline protocol.
     *
     * <p>Unlike dummy, this handler reaches the chain through {@code UtxoProvider} rather than a
     * yaci-store repository, so that is the seam the offline chain is spliced into.
     */
    private FesResult runFreezeAndSeizeRegistration(Cip68Metadata metadata, String quantity) throws Exception {
        // The blacklist init that preceded this registration was, by construction, built with the
        // same CIP-68 setting — that is the only combination that can ever validate on chain.
        var attempt = attemptFreezeAndSeizeRegistration(metadata, quantity, metadata != null);
        Assertions.assertTrue(attempt.context().isSuccessful(),
                "freeze-and-seize registration build failed: " + attempt.context().error());
        Assertions.assertNotNull(attempt.context().unsignedCborTx(), "no cbor returned");

        var tx = Transaction.deserialize(HexUtil.decodeHexString(attempt.context().unsignedCborTx()));
        var policyId = attempt.context().metadata().policyId();
        log.info("freeze-and-seize registration built: policy={} cip68={} quantity={}",
                policyId, metadata != null, quantity);

        return new FesResult(attempt.chain(), tx, policyId,
                attempt.boot().params().programmableLogicBase().scriptHash(),
                attempt.handler(), attempt.boot().params());
    }

    private record FesAttempt(
            OfflineChain chain,
            BootstrapFixture.Bootstrapped boot,
            org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler handler,
            org.cardanofoundation.cip113.model.TransactionContext<
                    org.cardanofoundation.cip113.model.TransactionContext.RegistrationResult> context) {
    }

    /**
     * Build one freeze-and-seize registration and hand back the raw result, asserting nothing.
     *
     * @param initCip68Enabled what the blacklist-init row records — deliberately separable from
     *                         {@code metadata}, because the whole point of the cross-check under
     *                         test is that these two can disagree
     */
    private FesAttempt attemptFreezeAndSeizeRegistration(Cip68Metadata metadata,
                                                         String quantity,
                                                         Boolean initCip68Enabled) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier()
                        .getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findIssuanceCborHexUtxo(Mockito.any()))
                .thenReturn(java.util.Optional.of(boot.issuanceCborHexUtxo()));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));

        var moduleService = HandlerFixtures.moduleService();

        var handler = new org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                Mockito.mock(org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser.class),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxoProvider),
                moduleService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(moduleService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(
                        Mockito.mock(com.bloxbean.cardano.client.backend.api.UtxoService.class)),
                Mockito.mock(org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository.class),
                blacklistInitRepository(initCip68Enabled),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                Mockito.mock(CustomStakeRegistrationRepository.class),
                registrationsAllRegistered(),
                utxoProvider,
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class));

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var request = org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                .moduleId("freeze-and-seize")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity(quantity)
                .cip68Metadata(metadata)
                .adminPubKeyHash(adminPkh)
                .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                .build();

        return new FesAttempt(chain, boot, handler,
                handler.buildRegistrationTransaction(request, boot.params()));
    }

    private record FesResult(OfflineChain chain,
                             Transaction transaction,
                             String issuancePolicyId,
                             String plbScriptHash,
                             org.cardanofoundation.cip113.service.module.FreezeAndSeizeHandler handler,
                             org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams bootParams) {
    }

    /**
     * The blacklist-init row the registration reads.
     *
     * <p>A <em>real</em> {@link org.cardanofoundation.cip113.entity.BlacklistInitEntity}, not a
     * mock. That matters: a Mockito mock returns {@code false} — not {@code null} — from a
     * {@code Boolean} getter, so a mocked row silently claims "this init was built WITHOUT
     * CIP-68" and would make the cross-check under test fire on every CIP-68 registration. A row
     * that lies about its own contents is not a useful fixture.
     *
     * @param cip68Enabled the recorded flag; {@code null} models a row written before the column
     *                     existed, where the cross-check must stay silent for want of evidence
     */
    private static org.cardanofoundation.cip113.repository.BlacklistInitRepository blacklistInitRepository(
            Boolean cip68Enabled) {
        var repo = Mockito.mock(org.cardanofoundation.cip113.repository.BlacklistInitRepository.class);
        var row = org.cardanofoundation.cip113.entity.BlacklistInitEntity.builder()
                .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                .adminPkh("00".repeat(28))
                .txHash("00".repeat(32))
                .outputIndex(0)
                .cip68Enabled(cip68Enabled)
                .build();
        Mockito.when(repo.findByBlacklistNodePolicyId(Mockito.anyString()))
                .thenReturn(java.util.Optional.of(row));
        return repo;
    }

    // ------------------------------------------------------------ rwa-token fixtures

    /**
     * The CIP-113 in-place upgrade path evaluates.
     *
     * <p>{@code UpgradeRegistryNode} is the reason the minting proxy exists: the registry
     * node's {@code minting_logic_script} is frozen at insert, so re-pointing the transfer
     * rules is the ONLY way they can ever change, and the proxy is what delegates that
     * decision to the rotatable authority.
     *
     * <p>A genuine upgrade needs a contract revision that changes a transfer-logic script
     * body — until one lands the derived hashes equal the node's, so the builder refuses
     * as a no-op. This asserts the ENCODING against a real evaluator anyway, via the
     * no-op seam, because the alternative is shipping the path with nothing evaluated
     * behind it. The validator checks the frozen fields rather than that the mutable ones
     * moved, so a no-op rewrite exercises every check that matters: both withdrawals, the
     * authentication of the spent and continuing nodes, the unfracking and
     * {@code global_state_cs} re-assertions, the reference-input GlobalState read, the
     * admin signature, and the nothing-minted rule.
     */
    @Test
    public void rwaTokenRegistryNodeUpgradeEvaluates() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress());
        var policyId = st.built().programmableTokenPolicyId();

        // The ordinary entry point must refuse: nothing has changed to upgrade to.
        var noOp = st.handler().buildUpgradeRegistryNodeTransaction(
                policyId, BootstrapFixture.ADMIN.baseAddress(), st.boot().params());
        Assertions.assertFalse(noOp.isSuccessful(),
                "a rewrite to the same scripts must be refused, not charged for");
        Assertions.assertTrue(noOp.error().contains("nothing to upgrade"),
                "the refusal must say WHY, got: " + noOp.error());

        // …and the encoding must nonetheless be a transaction the chain accepts.
        var upgrade = st.handler().buildUpgradeRegistryNodeTransaction(
                policyId, BootstrapFixture.ADMIN.baseAddress(), st.boot().params(), true);
        Assertions.assertTrue(upgrade.isSuccessful(),
                "registry-node upgrade build failed: " + upgrade.error());

        var tx = Transaction.deserialize(HexUtil.decodeHexString(upgrade.unsignedCborTx()));
        int evaluated = st.chain().reportAndCheckRedeemers("rwa-token/upgradeRegistryNode", tx);
        Assertions.assertTrue(evaluated > 0,
                "no redeemer was genuinely evaluated on the registry-node upgrade — the "
                + "ex-units would be a ceiling fallback, which proves nothing about the scripts");

        // Both minting withdrawals must be present: the proxy is what registry_spend's
        // update branch keys on, and the proxy in turn demands the authority.
        var withdrawals = tx.getBody().getWithdrawals();
        Assertions.assertNotNull(withdrawals, "upgrade carries no withdrawals at all");
        Assertions.assertEquals(2, withdrawals.size(),
                "upgrade must withdraw 0 from BOTH the minting proxy and the authority; "
                + "found " + withdrawals.size());

        Assertions.assertTrue(tx.serialize().length < 16384,
                "upgrade tx exceeds the ledger limit at " + tx.serialize().length + " bytes");
    }

    /**
     * The genesis transaction must register BOTH minting reward accounts.
     *
     * <p>Every mint, burn and registration withdraws 0 from the permanent minting PROXY and
     * from the rotatable AUTHORITY it delegates to. A withdrawal from an unregistered reward
     * account is rejected in phase 1 with {@code WithdrawalsNotInRewardsCERTS} — <em>after</em>
     * the user has signed — and script evaluation cannot catch it, because reward-account
     * existence is a ledger rule rather than a Plutus one. The validators run and succeed
     * either way, so every other test in this file would still pass with a missing certificate.
     *
     * <p>That makes this the one invariant on the whole minting path that only a real chain
     * would otherwise reveal. Asserting it against the built CBOR turns it into a build-time
     * check: the certificate is either in the transaction or it is not.
     *
     * <p>Two certificates, not one, is the specific regression to guard. Before the
     * proxy/authority split there was a single minting reward account, and the genesis path
     * registered exactly one — so "it worked before" is not evidence that the second one is
     * there now.
     */
    @Test
    public void rwaTokenGenesisRegistersBothMintingRewardAccounts() throws Exception {
        var st = rwaTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ALICE.baseAddress());
        var reg = st.registrations().get(st.built().programmableTokenPolicyId());

        var scripts = new org.cardanofoundation.cip113.service.RwaTokenScriptBuilderService(
                HandlerFixtures.moduleService(),
                HandlerFixtures.protocolScriptBuilderService())
                .resolveScripts(
                        reg.getSecurityAssetNameHex(),
                        reg.getGlobalStatePolicyId(),
                        reg.getPowerUsersPolicyId(),
                        reg.getDenylistPolicyId(),
                        st.boot().params());

        var genesis = Transaction.deserialize(HexUtil.decodeHexString(st.built().genesisCborHex()));
        var certs = genesis.getBody().getCerts();
        Assertions.assertNotNull(certs, "genesis carries no certificates at all");

        // Collect the script-hash stake credentials the genesis actually registers, whichever
        // certificate shape they arrived in. The builder emits the legacy StakeRegistration and
        // a preBalanceTx hook may rewrite it to a Conway RegCert; both register the credential,
        // so matching on shape rather than on the credential would make this test a description
        // of the current implementation instead of the invariant.
        var registered = new java.util.HashSet<String>();
        for (var cert : certs) {
            com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential cred = null;
            if (cert instanceof com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration sr) {
                cred = sr.getStakeCredential();
            } else if (cert instanceof com.bloxbean.cardano.client.transaction.spec.cert.RegCert rc) {
                cred = rc.getStakeCredential();
            }
            if (cred != null && cred.getType()
                    == com.bloxbean.cardano.client.transaction.spec.cert.StakeCredType.SCRIPTHASH) {
                registered.add(HexUtil.encodeHexString(cred.getHash()));
            }
        }

        String proxyHash = scripts.mintingLogicProxyHashHex();
        String authorityHash = scripts.mintingAuthorityHashHex();
        log.info("[rwa-token/genesis] registers script stake credentials {} (proxy={}, authority={})",
                registered, proxyHash, authorityHash);

        Assertions.assertTrue(registered.contains(proxyHash),
                "genesis does not register the minting PROXY reward account " + proxyHash
                + " — every mint, burn and registration withdraws 0 from it, and the transaction "
                + "would be rejected phase-1 at submit with WithdrawalsNotInRewardsCERTS after "
                + "the user had already signed. Registered: " + registered);
        Assertions.assertTrue(registered.contains(authorityHash),
                "genesis does not register the minting AUTHORITY reward account " + authorityHash
                + " — the proxy requires the authority's withdraw-0 in the same transaction, so "
                + "this fails at submit exactly like the proxy's would, and no amount of script "
                + "evaluation reveals it. Registered: " + registered);
        Assertions.assertNotEquals(proxyHash, authorityHash,
                "proxy and authority must be different scripts; if they collapsed to one hash "
                + "the delegation is a no-op and the rotation path is meaningless");
    }

    /**
     * A rwa-token protocol whose genesis → AddPowerUser → registration chain has been built,
     * evaluated and virtually submitted, ready for a mint.
     *
     * <p>{@code utxoProvider} and {@code registrationRepository} are exposed because the H3 cases
     * re-stub them mid-test: the whole finding is about what happens when the chain lookup gives a
     * different answer from the one an immediately-consistent in-memory chain can produce.
     */
    /** A stake-registration repository that reports every reward account as registered.
     *
     *  <p>Reward-account existence is a LEDGER rule, not a Plutus one, so it is invisible to
     *  script evaluation and cannot be exercised offline. What these tests are for is the
     *  script side; the registration certs are built for real by the chain under test. */
    private static CustomStakeRegistrationRepository registeredStakeRepository() {
        var repo = Mockito.mock(CustomStakeRegistrationRepository.class);
        var entity = new com.bloxbean.cardano.yaci.store.staking.storage.impl.model.StakeRegistrationEntity();
        entity.setType(com.bloxbean.cardano.yaci.core.model.certs.CertificateType.STAKE_REGISTRATION);
        Mockito.when(repo.findRegistrationsByStakeAddress(Mockito.anyString()))
                .thenReturn(java.util.Optional.of(entity));
        return repo;
    }

    /** Power-user mirror that grants ADMIN to the bootstrap admin and nobody else. */
    private static org.cardanofoundation.cip113.repository.RwaTokenPowerUserRepository
            adminOnlyPowerUserRepository() {
        var repo = Mockito.mock(
                org.cardanofoundation.cip113.repository.RwaTokenPowerUserRepository.class);
        String adminPkh = HexUtil.encodeHexString(
                new com.bloxbean.cardano.client.address.Address(BootstrapFixture.ADMIN.baseAddress())
                        .getPaymentCredentialHash().orElseThrow());
        Mockito.when(repo.findByProgrammableTokenPolicyIdAndPowerUserPkh(
                        Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    if (!adminPkh.equalsIgnoreCase(inv.getArgument(1))) {
                        return java.util.Optional.empty();
                    }
                    var e = new org.cardanofoundation.cip113.entity.RwaTokenPowerUserEntity();
                    e.setProgrammableTokenPolicyId(inv.getArgument(0));
                    e.setPowerUserPkh(adminPkh);
                    // Every capability, matching the bootstrap power user the genesis mints.
                    e.setCapabilities(31);
                    return java.util.Optional.of(e);
                });
        return repo;
    }

    private record RwaTokenChain(
            OfflineChain chain,
            BootstrapFixture.Bootstrapped boot,
            org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler handler,
            java.util.Map<String, org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity>
                    registrations,
            org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult
                    built,
            org.cardanofoundation.cip113.service.UtxoProvider utxoProvider,
            org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository
                    registrationRepository,
            org.cardanofoundation.cip113.service.RwaTokenAllowlistService allowlist) {
    }

    /**
     * A {@link org.cardanofoundation.cip113.service.UtxoProvider} backed by the offline chain.
     *
     * <p>{@code assetPresence} is answered from the same chain as {@code findUtxoByAsset}, so the
     * default behaviour matches production on a healthy backend: a hit is {@code PRESENT}, a miss
     * is {@code ABSENT}. It never returns {@code UNKNOWN} on its own — an in-memory chain has no
     * failure mode — which is precisely why the H3 test re-stubs it.
     */
    private static org.cardanofoundation.cip113.service.UtxoProvider rwaTokenUtxoProvider(
            OfflineChain chain, BootstrapFixture.Bootstrapped boot) {
        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier().getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findIssuanceCborHexUtxo(Mockito.any()))
                .thenReturn(java.util.Optional.of(boot.issuanceCborHexUtxo()));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        Mockito.when(utxoProvider.findUtxosFromBlockfrost(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        Mockito.when(utxoProvider.findAllCurrentUtxosFromBlockfrost(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
        // Linked-list anchors (denylist, power users) are found BY POLICY, not by unit —
        // their asset name is the empty root name — so the mutation builders need this or
        // they cannot see the roots the genesis transaction minted.
        Mockito.when(utxoProvider.findUtxosByPolicy(Mockito.anyString()))
                .thenAnswer(inv -> chain.findUtxosByPolicy(inv.getArgument(0)));
        Mockito.when(utxoProvider.findUtxoByAsset(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    String unit = inv.getArgument(0) + (String) inv.getArgument(1);
                    return chain.findUtxoByUnit(unit);
                });
        Mockito.when(utxoProvider.assetPresence(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> {
                    String unit = inv.getArgument(0) + (String) inv.getArgument(1);
                    return chain.findUtxoByUnit(unit).isPresent()
                            ? org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.PRESENT
                            : org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.ABSENT;
                });
        return utxoProvider;
    }

    /**
     * An in-memory {@code RwaTokenRegistrationRepository}.
     *
     * <p>{@code claimCip68ReferenceMint} implements the real compare-and-set semantics against the
     * map — set the flag and return 1 only if it was clear, otherwise return 0 and change nothing.
     * A mock that returned Mockito's default {@code 0} would make every reference mint look like a
     * lost race; one that always returned 1 would never exercise the guard.
     */
    private static org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository
            rwaTokenRegistrationRepository(
                    java.util.Map<String,
                            org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity> registrations) {
        var repo = Mockito.mock(
                org.cardanofoundation.cip113.repository.RwaTokenRegistrationRepository.class);
        Mockito.when(repo.save(Mockito.any())).thenAnswer(inv -> {
            var entity = (org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity)
                    inv.getArgument(0);
            registrations.put(entity.getProgrammableTokenPolicyId(), entity);
            return entity;
        });
        Mockito.when(repo.findByProgrammableTokenPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(registrations.get((String) inv.getArgument(0))));
        Mockito.when(repo.existsByProgrammableTokenPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> registrations.containsKey((String) inv.getArgument(0)));
        Mockito.when(repo.existsByBootstrapTxHashAndBootstrapOutputIndex(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> registrations.values().stream().anyMatch(row ->
                        inv.getArgument(0).equals(row.getBootstrapTxHash())
                                && inv.getArgument(1).equals(row.getBootstrapOutputIndex())));
        Mockito.when(repo.claimCip68ReferenceMint(Mockito.anyString())).thenAnswer(inv -> {
            var row = registrations.get((String) inv.getArgument(0));
            if (row == null || row.isCip68ReferenceMinted()) {
                return 0;
            }
            row.setCip68ReferenceMinted(true);
            return 1;
        });
        return repo;
    }

    /**
     * Build, evaluate and virtually submit the whole rwa-token registration chain.
     *
     * @param metadata             CIP-68 metadata, or null for a non-CIP-68 registration
     * @param initialMintableAmount the GlobalState cap — note this does NOT choose the label
     */
    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount)
            throws Exception {
        return rwaTokenChain(metadata, initialMintableAmount, "0",
                BootstrapFixture.ALICE.baseAddress());
    }

    /**
     * @param initialMintQuantity supply minted BY the registration transaction. Anything above
     *        zero switches the chain onto its mint path, which is what causes the publish
     *        transaction to run — and therefore the only way to get reference scripts recorded
     *        against the registration row. A burn cannot be built without them.
     * @param recipientAddress    who receives that first mint. The burner has to be a power
     *        user holding both can_burn and can_force_transfer, so a burn test must send the
     *        supply to the admin rather than to Alice.
     */
    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount,
                                                  String initialMintQuantity, String recipientAddress)
            throws Exception {
        return rwaTokenChain(metadata, initialMintableAmount, initialMintQuantity,
                recipientAddress, /*rewardAccountsRegistered=*/ false);
    }

    /** @param rewardAccountsRegistered when true, the stake-registration repository reports
     *  every reward account as already registered. Needed by paths whose PRECONDITION is a
     *  registered account (the transfer refuses outright without one) and harmful to paths
     *  that assert genesis creates those accounts — hence a parameter rather than a default. */
    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount,
                                                  String initialMintQuantity, String recipientAddress,
                                                  boolean rewardAccountsRegistered)
            throws Exception {
        return rwaTokenChain(metadata, initialMintableAmount, initialMintQuantity,
                recipientAddress, rewardAccountsRegistered, false);
    }

    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount,
                                                  String initialMintQuantity, String recipientAddress,
                                                  boolean rewardAccountsRegistered,
                                                  boolean seedReceiverKyc)
            throws Exception {
        return rwaTokenChain(metadata, initialMintableAmount, initialMintQuantity,
                recipientAddress, rewardAccountsRegistered, seedReceiverKyc, false);
    }

    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount,
                                                  String initialMintQuantity, String recipientAddress,
                                                  boolean rewardAccountsRegistered,
                                                  boolean seedReceiverKyc,
                                                  boolean submitEarlierAttempt)
            throws Exception {
        return rwaTokenChain(metadata, initialMintableAmount, initialMintQuantity, recipientAddress,
                rewardAccountsRegistered, seedReceiverKyc, submitEarlierAttempt, false);
    }

    private RwaTokenChain rwaTokenChain(Cip68Metadata metadata, long initialMintableAmount,
            String initialMintQuantity, String recipientAddress, boolean rewardAccountsRegistered,
            boolean seedReceiverKyc, boolean submitEarlierAttempt, boolean attestInitialMint) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var label = "rwa-token";

        var registrations = new java.util.HashMap<String,
                org.cardanofoundation.cip113.entity.RwaTokenRegistrationEntity>();

        var utxoProvider = rwaTokenUtxoProvider(chain, boot);
        var registrationRepository = rwaTokenRegistrationRepository(registrations);
        var reservationRepository = Mockito.mock(org.cardanofoundation.cip113.repository.RwaGenesisReservationRepository.class);
        var fundingReservationRepository = Mockito.mock(org.cardanofoundation.cip113.repository.RwaGenesisFundingReservationRepository.class);
        var fundingReservations = new java.util.HashMap<String, String>();
        Mockito.when(fundingReservationRepository.existsById(Mockito.anyString()))
                .thenAnswer(inv -> fundingReservations.containsKey(inv.getArgument(0)));
        Mockito.when(fundingReservationRepository.claim(Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(inv -> fundingReservations.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null ? 1 : 0);
        Mockito.when(fundingReservationRepository.findById(Mockito.anyString()))
                .thenAnswer(inv -> {
                    String ref = inv.getArgument(0);
                    String gs = fundingReservations.get(ref);
                    if (gs == null) return java.util.Optional.empty();
                    var row = new org.cardanofoundation.cip113.entity.RwaGenesisFundingReservationEntity();
                    row.setInputRef(ref);
                    row.setGlobalStatePolicyId(gs);
                    return java.util.Optional.of(row);
                });
        var reservations = new java.util.HashMap<String, String>();
        Mockito.when(reservationRepository.existsByBootstrapTxHashAndBootstrapOutputIndex(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> reservations.containsValue(inv.getArgument(0) + "#" + inv.getArgument(1)));
        Mockito.when(reservationRepository.claim(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> {
                    String gs = inv.getArgument(0);
                    String ref = inv.getArgument(1) + "#" + inv.getArgument(2);
                    if (reservations.containsKey(gs) || reservations.containsValue(ref)) return 0;
                    reservations.put(gs, ref);
                    return 1;
                });
        org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.ChainBuildResult firstBuilt = null;
        if (seedReceiverKyc) {
            chain.seedAda("rwa-retry-fresh-funding", BootstrapFixture.ADMIN.baseAddress(), 0, 200);
        }
        var allowlist = Mockito.mock(org.cardanofoundation.cip113.service.RwaTokenAllowlistService.class);
        if (seedReceiverKyc) {
            var snapshotRepo = Mockito.mock(org.cardanofoundation.cip113.repository.RwaTokenMemberRootSnapshotRepository.class);
            var snapshots = new java.util.HashMap<String, org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity>();
            Mockito.when(snapshotRepo.findByProgrammableTokenPolicyIdAndRootHash(Mockito.anyString(), Mockito.anyString()))
                    .thenAnswer(inv -> java.util.Optional.ofNullable(snapshots.get(
                            inv.getArgument(0) + ":" + inv.getArgument(1))));
            Mockito.when(snapshotRepo.save(Mockito.any())).thenAnswer(inv -> {
                var snapshot = (org.cardanofoundation.cip113.entity.RwaTokenMemberRootSnapshotEntity) inv.getArgument(0);
                snapshots.put(snapshot.getProgrammableTokenPolicyId() + ":" + snapshot.getRootHash(), snapshot);
                return snapshot;
            });
            allowlist = new org.cardanofoundation.cip113.service.RwaTokenAllowlistService(
                    registrationRepository,
                    Mockito.mock(org.cardanofoundation.cip113.repository.RwaTokenMemberLeafRepository.class),
                    snapshotRepo, utxoProvider, HandlerFixtures.OBJECT_MAPPER, HandlerFixtures.NETWORK);
        }

        // The chain orchestrator builds genesis → AddPowerUser → registration back-to-back with
        // nothing submitted in between, handing each transaction's outputs to this supplier so
        // the next one can spend them. Production wires the QuickTxBuilder over the very same
        // object, so the test must too.
        var hybridUtxoSupplier =
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService());

        var handler = new org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler(
                new org.cardanofoundation.cip113.service.RwaTokenScriptBuilderService(
                        HandlerFixtures.moduleService(),
                        HandlerFixtures.protocolScriptBuilderService()),
                HandlerFixtures.protocolScriptBuilderService(),
                allowlist,
                registrationRepository,
                reservationRepository,
                fundingReservationRepository,
                Mockito.mock(org.cardanofoundation.cip113.repository.RwaTokenDenylistEntryRepository.class),
                // The power-user DB MIRROR, stubbed for the admin only.
                //
                // Denylist mutations gate on it off-chain ("is this signer an ADMIN power
                // user?") so the on-chain `is_admin` failure does not surface as an opaque
                // script error. In a live deployment genesis writes that row; the offline
                // chain has no database, so a bare mock refuses every mutation before a
                // transaction is ever built. Scoped to the ADMIN's pkh rather than blanket-
                // true, so a test that expects a NON-admin to be refused still gets its
                // refusal.
                adminOnlyPowerUserRepository(),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                utxoProvider,
                new AccountService(utxoProvider),
                chain.quickTxBuilderOver(hybridUtxoSupplier),
                chain.protocolParamsSupplier(),
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                hybridUtxoSupplier,
                // Reference scripts published by the chain's publishScripts phase live only in
                // an unsubmitted output, so the evaluator resolves them through this rather
                // than the backend — without it the app falls back to fabricated ex-units.
                new org.cardanofoundation.cip113.service.HybridScriptSupplier(chain.scriptSupplier()),
                // OPT-IN, and deliberately not the default. Genesis SKIPS emitting the
                // minting proxy/authority registration certs when it believes those accounts
                // already exist (see the two `AlreadyRegistered` checks in the handler), so a
                // blanket "everything is registered" stub silently deletes the certs that
                // rwaTokenGenesisRegistersBothMintingRewardAccounts exists to assert —
                // it turned a passing invariant test red, which is the stub lying rather than
                // the code breaking. Only the paths that need a PRE-existing reward account
                // (transfer) ask for it.
                rewardAccountsRegistered ? registeredStakeRepository()
                                         : Mockito.mock(CustomStakeRegistrationRepository.class),
                // A real converter, not a mock. Every mint now clamps its validity upper
                // bound through cardanoConverters.time().toSlot(); an unstubbed mock returns
                // null from time() and the whole mint path dies with an NPE that looks
                // nothing like the CIP-68 behaviour these tests exist to pin down. PREVIEW's
                // era history is the closest public analogue to the devnet fixture, and the
                // exact slot is immaterial here — validTo does not affect what the evaluator
                // scores or what the outputs decode to.
                org.cardanofoundation.conversions.ClasspathConversionsFactory.createConverters(
                        org.cardanofoundation.conversions.domain.NetworkType.PREVIEW));

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var registerRequest = org.cardanofoundation.cip113.model.RwaTokenRegisterRequest.builder()
                .moduleId("rwa-token")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(recipientAddress)
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity("0")                     // overwritten by initialMintQuantity below
                .initialMintQuantity(initialMintQuantity)
                .cip68Metadata(metadata)
                .adminPubKeyHash(adminPkh)
                .initialMintableAmount(initialMintableAmount)
                .bootstrapPowerUserPkh(adminPkh)
                .bootstrapPowerUserCapabilities(255)
                .bootstrapPowerUserLabel("admin")
                .requiresReceiverKyc(seedReceiverKyc)
                .requiresSenderKyc(false)
                .seedRecipientInAllowlistAtGenesis(seedReceiverKyc)
                .build();

        var frozenRequest = HandlerFixtures.OBJECT_MAPPER.readValue(
                HandlerFixtures.OBJECT_MAPPER.writeValueAsString(registerRequest),
                org.cardanofoundation.cip113.model.RwaTokenRegisterRequest.class);
        org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.GenesisPlan approvedPlan = null;
        // CIP-170 v1.1: the prepared registration names its KERI signer in label 170 (ATTEST_TX).
        var approval = org.cardanofoundation.cip113.model.Cip170AttestationData.attestTx("E" + "a".repeat(43));
        if (attestInitialMint) {
            approvedPlan = handler.planGenesis(registerRequest, boot.params());
            Assertions.assertTrue(registrations.isEmpty(), "planning must not create registration state");
            Assertions.assertTrue(reservations.isEmpty(), "planning has no reservation side effects");
            var bootstrap = approvedPlan.funding().getFirst();
            reservationRepository.claim(approvedPlan.globalStatePolicyId(), bootstrap.getTxHash(), bootstrap.getOutputIndex());
            var savedReservation = new org.cardanofoundation.cip113.entity.RwaGenesisReservationEntity();
            savedReservation.setGlobalStatePolicyId(approvedPlan.globalStatePolicyId());
            savedReservation.setBootstrapTxHash(bootstrap.getTxHash()); savedReservation.setBootstrapOutputIndex(bootstrap.getOutputIndex());
            Mockito.when(reservationRepository.findById(approvedPlan.globalStatePolicyId()))
                    .thenReturn(java.util.Optional.of(savedReservation));
            for (var funding : approvedPlan.funding()) fundingReservationRepository.claim(
                    funding.getTxHash() + "#" + funding.getOutputIndex(), approvedPlan.globalStatePolicyId());
            if (metadata == null) {
                // An attested attempt must never choose another bootstrap when its pinned state differs.
                var wrongPolicy = new org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.GenesisPlan(
                        approvedPlan.globalStatePolicyId(), "ff".repeat(28), approvedPlan.funding());
                handler.usePreparedGenesis(wrongPolicy, approval, java.time.Instant.now().plusSeconds(1800));
                var rejectedPolicy = handler.buildFullRegistrationChain(registerRequest, boot.params());
                Assertions.assertFalse(rejectedPolicy.isSuccessful());
                Assertions.assertTrue(registrations.isEmpty(), "policy mismatch must not create registration state");
                String fundingRef = bootstrap.getTxHash() + "#" + bootstrap.getOutputIndex();
                fundingReservations.put(fundingRef, "ee".repeat(28));
                handler.usePreparedGenesis(approvedPlan, approval, java.time.Instant.now().plusSeconds(1800));
                var rejectedOwner = handler.buildFullRegistrationChain(registerRequest, boot.params());
                Assertions.assertFalse(rejectedOwner.isSuccessful());
                Assertions.assertTrue(registrations.isEmpty(), "foreign reservation must not create registration state");
                fundingReservations.put(fundingRef, approvedPlan.globalStatePolicyId());
            }
            handler.usePreparedGenesis(approvedPlan, approval, java.time.Instant.now().plusSeconds(1800));
        }
        var chainResult = handler.buildFullRegistrationChain(registerRequest, boot.params());
        Assertions.assertTrue(chainResult.isSuccessful(),
                "rwa-token registration chain build failed: " + chainResult.error());

        if (seedReceiverKyc) {
            // The first chain was built but abandoned. A fresh seed expiry changes
            // the genesis root while the previous snapshot and registration row remain.
            Thread.sleep(5);
            var firstRootTx = chainResult.metadata().genesisCborHex();
            firstBuilt = chainResult.metadata();
            String firstPolicy = chainResult.metadata().programmableTokenPolicyId();
            var prior = registrations.get(firstPolicy);
            String priorJson = HandlerFixtures.OBJECT_MAPPER.writeValueAsString(prior);
            var directUnspent = utxoProvider.findUtxosFromBlockfrost(BootstrapFixture.ADMIN.baseAddress());
            Assertions.assertTrue(directUnspent.stream().anyMatch(u ->
                            u.getTxHash().equalsIgnoreCase(prior.getBootstrapTxHash())
                                    && u.getOutputIndex() == prior.getBootstrapOutputIndex()),
                    "abandoned genesis bootstrap " + prior.getBootstrapTxHash() + "#" + prior.getBootstrapOutputIndex()
                    + " must still be unspent in the offline backend: "
                            + directUnspent.stream().map(u -> u.getTxHash() + "#" + u.getOutputIndex()).toList());
            chainResult = handler.buildFullRegistrationChain(registerRequest, boot.params());
            Assertions.assertTrue(chainResult.isSuccessful(),
                    "retry after an unsubmitted seeded chain failed: " + chainResult.error());
            Assertions.assertNotEquals(firstRootTx, chainResult.metadata().genesisCborHex(),
                    "retry must build a new exact genesis snapshot");
            Assertions.assertNotEquals(firstPolicy, chainResult.metadata().programmableTokenPolicyId(),
                    "retry must use a fresh bootstrap and policy");
            Assertions.assertEquals(priorJson,
                    HandlerFixtures.OBJECT_MAPPER.writeValueAsString(registrations.get(firstPolicy)),
                    "retry must preserve the previous attempt's metadata");
            int persistedAttempts = registrations.size();
            Mockito.when(reservationRepository.claim(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                    .thenReturn(0);
            var raced = handler.buildFullRegistrationChain(registerRequest, boot.params());
            Assertions.assertFalse(raced.isSuccessful(), "a concurrent claim must not create another registration");
            Assertions.assertTrue(String.valueOf(raced.error()).contains("reserved"));
            Assertions.assertEquals(persistedAttempts, registrations.size());
            Mockito.when(reservationRepository.existsByBootstrapTxHashAndBootstrapOutputIndex(
                    Mockito.anyString(), Mockito.anyInt())).thenReturn(true);
            var exhausted = handler.buildFullRegistrationChain(registerRequest, boot.params());
            Assertions.assertFalse(exhausted.isSuccessful(), "no eligible bootstrap must fail before persistence");
            Assertions.assertTrue(String.valueOf(exhausted.error()).contains("Fund a fresh ADA-only UTxO"));
            Assertions.assertEquals(persistedAttempts, registrations.size());
            if (submitEarlierAttempt) {
                chainResult = org.cardanofoundation.cip113.model.TransactionContext.ok(
                        firstBuilt.genesisCborHex(), firstBuilt);
            }
        }

        var built = chainResult.metadata();
        if (attestInitialMint) {
            long magic = org.cardanofoundation.cip113.service.TxAttestationSeal.magic("devnet");
            String seal = org.cardanofoundation.cip113.service.TxAttestationSeal.digest(built.registrationTxHash(), magic);
            built = handler.completeInitialMintChain(built, BootstrapFixture.ADMIN.baseAddress(), boot.params());
            Assertions.assertNull(built.attestationCborHex(), "an ATTEST_TX creation has no CIP-170 child");
            chainResult = org.cardanofoundation.cip113.model.TransactionContext.ok(built.genesisCborHex(), built);
            String approvedAsset = metadata == null ? BASE_ASSET_NAME_HEX
                    : Cip68.labeledAssetName(Cip68.uncappedUserTokenLabel(), BASE_ASSET_NAME_HEX);
            String destination = AddressProvider.getBaseAddress(
                    Credential.fromScript(boot.params().programmableLogicBase().scriptHash()),
                    new Address(recipientAddress).getDelegationCredential().orElseThrow(), HandlerFixtures.NETWORK.getCardanoNetwork()).getAddress();
            var fields = new org.cardanofoundation.cip113.model.MintAttestationRequest("session", "dev", boot.params().txHash(),
                    approvedPlan.programmableTokenPolicyId(), approvedAsset, initialMintQuantity,
                    BootstrapFixture.ADMIN.baseAddress(), recipientAddress, destination);
            org.cardanofoundation.cip113.service.InitialMintTransactionValidator.validate(
                    built, fields, frozenRequest, approvedPlan, boot.params(), approval.signerAid(), seal, magic);
        }
        // Exercise the same final-chain funding audit the controller runs after the
        // builder. It must recognize chained outputs and protocol inputs while
        // reserving every external wallet input used by later phases.
        var finalHandler = Mockito.mock(org.cardanofoundation.cip113.service.module.RwaTokenModuleHandler.class);
        Mockito.when(finalHandler.buildFullRegistrationChain(Mockito.any(), Mockito.any()))
                .thenReturn(org.cardanofoundation.cip113.model.TransactionContext.ok(
                        built.genesisCborHex(), built));
        var factory = Mockito.mock(org.cardanofoundation.cip113.service.module.ModuleHandlerFactory.class);
        Mockito.when(factory.getHandler(Mockito.eq("rwa-token"), Mockito.any())).thenReturn(finalHandler);
        var creation = new org.cardanofoundation.cip113.service.RwaTokenCreationService(
                factory, utxoProvider, registrationRepository, fundingReservationRepository);
        Assertions.assertEquals(built, creation.buildChain(registerRequest, boot.params()));
        log.info("[{}] chain built: globalStatePolicy={} progTokenPolicy={} denylistPolicy={}",
                label, built.globalStatePolicyId(), built.programmableTokenPolicyId(),
                built.denylistPolicyId());

        // Virtually submit the chain in order, so each tx sees the previous one's outputs.
        if (seedReceiverKyc) {
            byte[] recipientStake = new Address(recipientAddress).getDelegationCredentialHash().orElseThrow();
            var seededAllowlist = allowlist;
            String builtPolicy = built.programmableTokenPolicyId();
            Assertions.assertThrows(IllegalStateException.class,
                    () -> seededAllowlist.inclusionProof(builtPolicy, recipientStake,
                            (short) 0, System.currentTimeMillis()),
                    "public proof lookup must not trust an unsubmitted genesis snapshot");
        }
        var stages = new java.util.LinkedHashMap<String, String>();
        stages.put("genesis", built.genesisCborHex());
        stages.put("addPowerUser", built.addPowerUserCborHex());
        stages.put("cmtaProvenance", built.cmtaProvenanceCborHex());
        stages.put("issuanceProvenance", built.issuanceProvenanceCborHex());
        var cmtaTx = Transaction.deserialize(HexUtil.decodeHexString(built.cmtaProvenanceCborHex()));
        var issuanceTx = Transaction.deserialize(HexUtil.decodeHexString(built.issuanceProvenanceCborHex()));
        Assertions.assertEquals(built.addPowerUserTxHash(),
                cmtaTx.getBody().getInputs().getFirst().getTransactionId());
        Assertions.assertEquals(built.cmtaProvenanceTxHash(),
                issuanceTx.getBody().getInputs().getFirst().getTransactionId());
        // Present only on the mint path, and it must be submitted BEFORE the registration —
        // the registration reads its outputs as reference inputs.
        if (built.publishScriptsCborHex() != null) {
            stages.put("publishScripts", built.publishScriptsCborHex());
        }
        stages.put("registration", built.registrationCborHex());
        if (built.attestationCborHex() != null) {
            stages.put("attestation", built.attestationCborHex());
            Assertions.assertEquals(built.registrationTxHash(),
                    Transaction.deserialize(HexUtil.decodeHexString(built.attestationCborHex()))
                            .getBody().getInputs().getFirst().getTransactionId());
        }
        if (built.registerTransferLogicCborHex() != null) {
            stages.put("registerTransferLogic", built.registerTransferLogicCborHex());
        }
        if (built.registerThirdPartyTransferLogicCborHex() != null) {
            stages.put("registerThirdPartyTransferLogic",
                    built.registerThirdPartyTransferLogicCborHex());
        }

        for (var stage : stages.entrySet()) {
            Assertions.assertNotNull(stage.getValue(), stage.getKey() + " cbor missing from chain result");
            var stageTx = Transaction.deserialize(HexUtil.decodeHexString(stage.getValue()));
            if (stage.getKey().endsWith("Provenance")) {
                Assertions.assertNotNull(stageTx.getBody().getAuxiliaryDataHash(),
                        stage.getKey() + " must commit to label-1984 metadata");
            }
            int evaluated = chain.reportAndCheckRedeemers(label + "/" + stage.getKey(), stageTx);
            log.info("[{}/{}] {} redeemer(s) genuinely evaluated, size={} bytes",
                    label, stage.getKey(), evaluated, stageTx.serialize().length);

            // registerTransferLogic's ONLY redeemer is the Cert one injected in postBalanceTx
            // with hand-picked ex-units, so it legitimately has nothing the evaluator could
            // measure. Every other stage must have really run its scripts.
            if (!"registerTransferLogic".equals(stage.getKey())
                    && !"registerThirdPartyTransferLogic".equals(stage.getKey())
                    && !"publishScripts".equals(stage.getKey())
                    && !"attestation".equals(stage.getKey())
                    && !stage.getKey().endsWith("Provenance")) {
                Assertions.assertTrue(evaluated > 0,
                        stage.getKey() + " produced no genuinely evaluated redeemer");
            }
            Assertions.assertTrue(stageTx.serialize().length < 16384,
                    stage.getKey() + " exceeds 16384 bytes");
            chain.submit(stageTx);
        }

        return new RwaTokenChain(chain, boot, handler, registrations, built,
                utxoProvider, registrationRepository, allowlist);
    }

    /** A rwa-token mint request against the offline fixture's admin/recipient pair. */
    private static org.cardanofoundation.cip113.model.MintTokenRequest mintTokenRequest(
            String policyId, String assetName, String quantity, Cip68Metadata metadata) {
        return new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId,
                assetName,
                quantity,
                BootstrapFixture.ALICE.baseAddress(),
                null,
                metadata);
    }

    private static String stakeCredHex(String baseAddress) {
        return new Address(baseAddress).getDelegationCredentialHash()
                .map(HexUtil::encodeHexString)
                .orElseThrow();
    }

    /**
     * Bootstrap a fresh offline protocol, then drive the real {@link DummyModuleHandler}
     * through one registration on top of it.
     *
     * <p>A fresh chain per call is deliberate: the dummy module's issuance policy does NOT
     * depend on the asset name, so two registrations on one chain would collide on the handler's
     * "already registered" guard.
     */
    /** The real {@link DummyModuleHandler}, spliced onto an offline chain. */
    private static DummyModuleHandler dummyHandler(OfflineChain chain,
                                                        BootstrapFixture.Bootstrapped boot,
                                                        ProgrammableTokenRegistryRepository registry)
            throws Exception {
        var registrySpendHash = HexUtil.encodeHexString(boot.registrySpend().getScriptHash());
        var registryAddress = boot.registryOriginUtxo().getAddress();
        var issuanceUtxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(issuanceUtxoProvider.findIssuanceCborHexUtxo(Mockito.any()))
                .thenReturn(java.util.Optional.of(boot.issuanceCborHexUtxo()));
        return new DummyModuleHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, registryAddress, registrySpendHash),
                issuanceUtxoProvider,
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.moduleService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                registry,
                Mockito.mock(CustomStakeRegistrationRepository.class),
                registrationsAllRegistered());
    }

    /**
     * A dummy registration that has been <em>virtually submitted</em>, so the registry node and
     * the minted tokens are on chain and a follow-up mint can actually be built.
     *
     * <p>Backed by a real in-memory registry row rather than a bare mock, because the mint guard
     * under test reads exactly that row.
     */
    private static final class DummyMintFixture {
        private final OfflineChain chain;
        private final BootstrapFixture.Bootstrapped boot;
        private final DummyModuleHandler handler;
        private final String policyId;
        private final java.util.Map<String, ProgrammableTokenRegistryEntity> rows;
        private String registeredAssetName;

        DummyMintFixture(OfflineChain chain, BootstrapFixture.Bootstrapped boot,
                         DummyModuleHandler handler, String policyId, String registeredAssetName,
                         java.util.Map<String, ProgrammableTokenRegistryEntity> rows) {
            this.chain = chain;
            this.boot = boot;
            this.handler = handler;
            this.policyId = policyId;
            this.registeredAssetName = registeredAssetName;
            this.rows = rows;
        }

        /**
         * Add a registry row for some OTHER policy id, as a database that has seen more than one
         * token would have. Used by the H5 case, where the request names one registered policy
         * while the transaction derives a different one.
         */
        void addRegistryRow(String otherPolicyId, String assetName) {
            rows.put(otherPolicyId, ProgrammableTokenRegistryEntity.builder()
                    .policyId(otherPolicyId)
                    .moduleId("dummy")
                    .assetName(assetName)
                    .build());
        }

        DummyModuleHandler handler() {
            return handler;
        }

        String policyId() {
            return policyId;
        }

        String registeredAssetName() {
            return registeredAssetName;
        }

        void setRegisteredAssetName(String name) {
            this.registeredAssetName = name;
        }

        org.cardanofoundation.cip113.model.bootstrap.ProtocolBootstrapParams bootParams() {
            return boot.params();
        }

        OfflineChain chain() {
            return chain;
        }
    }

    private DummyMintFixture dummyMintFixture(Cip68Metadata metadata, String quantity) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var rows = new java.util.HashMap<String, ProgrammableTokenRegistryEntity>();
        var registry = Mockito.mock(ProgrammableTokenRegistryRepository.class);
        Mockito.when(registry.save(Mockito.any())).thenAnswer(inv -> {
            var entity = (ProgrammableTokenRegistryEntity) inv.getArgument(0);
            rows.put(entity.getPolicyId(), entity);
            return entity;
        });
        Mockito.when(registry.findByPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(rows.get((String) inv.getArgument(0))));

        var handler = dummyHandler(chain, boot, registry);

        var context = handler.buildRegistrationTransaction(
                DummyRegisterRequest.builder()
                        .moduleId("dummy")
                        .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                        .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                        .assetName(BASE_ASSET_NAME_HEX)
                        .quantity(quantity)
                        .cip68Metadata(metadata)
                        .build(),
                boot.params());
        Assertions.assertTrue(context.isSuccessful(),
                "fixture registration failed: " + context.error());

        // Virtually submit, so the registry node and the tokens are visible to the mint.
        chain.submit(Transaction.deserialize(HexUtil.decodeHexString(context.unsignedCborTx())));

        var policyId = context.metadata().policyId();
        var row = rows.get(policyId);
        Assertions.assertNotNull(row, "registration must have written a registry row");

        // The fixture's mutable name is what the guard reads, so a test can rewrite it.
        var fixture = new DummyMintFixture(chain, boot, handler, policyId, row.getAssetName(), rows);
        Mockito.when(registry.findByPolicyId(Mockito.eq(policyId)))
                .thenAnswer(inv -> java.util.Optional.of(ProgrammableTokenRegistryEntity.builder()
                        .policyId(policyId)
                        .moduleId("dummy")
                        .assetName(fixture.registeredAssetName())
                        .build()));
        return fixture;
    }

    private static org.cardanofoundation.cip113.model.MintTokenRequest mintRequest(String policyId,
                                                                                   String assetName) {
        return new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                policyId,
                assetName,
                "500",
                BootstrapFixture.ALICE.baseAddress(),
                null);
    }

    private static void assertMintRefused(DummyMintFixture fixture, String assetName, String expectedFragment) {
        var result = fixture.handler().buildMintTransaction(
                mintRequest(fixture.policyId(), assetName), fixture.bootParams());
        Assertions.assertFalse(result.isSuccessful(),
                "minting '" + assetName + "' must be refused — it is not the registered asset");
        Assertions.assertNull(result.unsignedCborTx(),
                "a refusal must not return a transaction for '" + assetName + "'");
        if (!expectedFragment.isEmpty()) {
            Assertions.assertTrue(String.valueOf(result.error()).contains(expectedFragment),
                    "error for '" + assetName + "' must contain '" + expectedFragment
                    + "', got: " + result.error());
        }
    }

    private DummyResult runDummyRegistration(Cip68Metadata metadata, String quantity) throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);

        var handler = dummyHandler(chain, boot, Mockito.mock(ProgrammableTokenRegistryRepository.class));

        var request = DummyRegisterRequest.builder()
                .moduleId("dummy")
                .feePayerAddress(BootstrapFixture.ADMIN.baseAddress())
                .recipientAddress(BootstrapFixture.ALICE.baseAddress())
                .assetName(BASE_ASSET_NAME_HEX)
                .quantity(quantity)
                .cip68Metadata(metadata)
                .build();

        var context = handler.buildRegistrationTransaction(request, boot.params());

        Assertions.assertTrue(context.isSuccessful(),
                "dummy registration build failed: " + context.error());
        Assertions.assertNotNull(context.unsignedCborTx(), "no cbor returned");

        var tx = Transaction.deserialize(HexUtil.decodeHexString(context.unsignedCborTx()));
        var policyId = context.metadata().policyId();
        log.info("dummy registration built: policy={} cip68={} quantity={}",
                policyId, metadata != null, quantity);

        return new DummyResult(chain, tx, policyId,
                boot.params().programmableLogicBase().scriptHash());
    }

    /**
     * A {@link org.cardanofoundation.cip113.service.ScriptRegistrationService} that answers "yes,
     * registered" for every credential.
     *
     * <p>⛔ WHY A STUB AND NOT THE REAL SERVICE OVER EMPTY MOCKS. These tests exercise CIP-68
     * minting, transfer selection and seizure refusal. They are not about how registration is
     * DISCOVERED. Previously they built the real service over mocked repositories and a mocked
     * AccountService, all answering their defaults, so it reported every credential unregistered —
     * harmless until the FES registration path gained a pre-registration guard, at which point all
     * six tests failed before reaching anything they were written to assert, with
     * "The issuer_admin reward account this registration must withdraw-0 from is not registered on
     * chain".
     *
     * <p>⚠ AND NOBODY SAW IT FOR DAYS, because this class was not in the offline test set anyone
     * ran and CI ran no backend tests at all. It surfaced only when the suites were wired into CI.
     * The premise these tests need is a chain where the credential IS registered; stating it
     * directly is both truthful and immune to the next guard added upstream of them.
     *
     * <p>Registration DISCOVERY itself is covered by {@code StakeRegistrationIdempotenceTest},
     * which builds the real service deliberately — leave that one alone.
     */
    private static org.cardanofoundation.cip113.service.ScriptRegistrationService registrationsAllRegistered() {
        var svc = Mockito.mock(org.cardanofoundation.cip113.service.ScriptRegistrationService.class);
        Mockito.when(svc.isStakeAddressRegistered(Mockito.anyString())).thenReturn(true);
        return svc;
    }
}
