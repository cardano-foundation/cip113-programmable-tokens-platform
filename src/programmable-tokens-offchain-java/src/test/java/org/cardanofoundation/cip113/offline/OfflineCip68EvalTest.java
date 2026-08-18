package org.cardanofoundation.cip113.offline;

import com.bloxbean.cardano.client.address.Address;
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
import org.cardanofoundation.cip113.service.substandard.DummySubstandardHandler;
import org.cardanofoundation.cip113.util.Cip68;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Offline build + real phase-2 evaluation of the CIP-68 registration paths, driving the
 * <em>production</em> substandard handlers against a virtually-submitted protocol bootstrap.
 *
 * <p>Nothing is re-implemented here: the handler under test is the same
 * {@code DummySubstandardHandler} the REST layer uses, constructed by hand with offline
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

        // Every handler resolves the protocol by INDEX — findProtocolAndIssuanceUtxos and the
        // dummy handler both hardcode bootstrapTxHash:0 and :2 — so the offline bootstrap's
        // output ordering has to match the real deployment's, not merely contain the right UTxOs.
        Assertions.assertEquals(0, boot.coordinationUtxo().getOutputIndex(),
                "handlers read the coordination UTxO at bootstrapTxHash:0");
        Assertions.assertEquals(2, boot.issuanceCborHexUtxo().getOutputIndex(),
                "handlers read the issuance template at bootstrapTxHash:2");

        var adminUtxos = chain.utxosAt(BootstrapFixture.ADMIN.baseAddress());
        Assertions.assertFalse(adminUtxos.isEmpty(), "admin must still hold change + re-fragmented outputs");
        for (var u : adminUtxos) {
            Assertions.assertEquals(boot.params().txHash(), u.getTxHash(),
                    "every admin utxo must now come from the bootstrap tx (seeds were spent)");
        }
    }

    // ------------------------------------------------------------------ dummy substandard

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
     * tomorrow. A {@code (222)} here would be a promise the substandard cannot keep, and no
     * off-chain guard can keep it either, since whoever holds the minting authority can build the
     * transaction without this backend. So {@code dummy} and {@code freeze-and-seize} are always
     * {@code (333)}; {@code security-token} is the only one that may claim {@code (222)}, because
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
                "a (222) here would be a non-fungibility claim the substandard cannot enforce");
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
                        .substandardId("dummy")
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
                        .substandardId("dummy")
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

        // The crux for this substandard: issuer_admin is parameterised by the asset NAME, so the
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

    // ------------------------------------------------------------------ security-token

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
    public void securityTokenBurnFitsUnderMaxTxSize() throws Exception {
        // Mint to the ADMIN, not Alice: the burner must be a power user holding both
        // can_burn (minting_logic) and can_force_transfer (third_party_transfer_logic),
        // and the bootstrap power user is the admin.
        var st = securityTokenChain(METADATA, 1_000_000L, "1000",
                BootstrapFixture.ADMIN.baseAddress());
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var built = st.built();
        var reg = st.registrations().get(built.programmableTokenPolicyId());
        var label = "security-token";

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
     * security-token is the one substandard whose CIP-68 pair cannot be completed at
     * registration: {@code verify_token_registration} rejects more than one asset name minted
     * under the issuance policy, so the {@code (100)} token has to ride along with the first
     * {@code MintBurn}. That makes this a multi-transaction case — genesis, AddPowerUser and
     * registration must all be built and virtually submitted before the mint can be built.
     */
    @Test
    public void securityTokenChainAndCip68Mint() throws Exception {
        var st = securityTokenChain(METADATA, 1_000_000L);
        var chain = st.chain();
        var boot = st.boot();
        var handler = st.handler();
        var registrations = st.registrations();
        var built = st.built();
        var label = "security-token";

        // ── the mint: this is where security-token's CIP-68 pair is completed ──
        var mintRequest = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                built.programmableTokenPolicyId(),
                registrations.get(built.programmableTokenPolicyId()).getSecurityAssetNameHex(),
                "1000",
                BootstrapFixture.ALICE.baseAddress(),
                null,
                METADATA);

        var mintResult = handler.buildMintTransaction(mintRequest, boot.params());
        Assertions.assertTrue(mintResult.isSuccessful(),
                "security-token CIP-68 mint build failed: " + mintResult.error());

        var mintTx = Transaction.deserialize(HexUtil.decodeHexString(mintResult.unsignedCborTx()));
        Cip68Evidence.dumpOutputs(label + "/mint", mintTx);
        int evaluated = chain.reportAndCheckRedeemers(label + "/mint", mintTx);
        Assertions.assertTrue(evaluated > 0, "no redeemer was evaluated on the mint");

        var policyId = built.programmableTokenPolicyId();
        var tokens = Cip68Evidence.tokensOfPolicy(mintTx, policyId);
        log.info("[{}/mint] issuance policy {} carries {} token(s):", label, policyId, tokens.size());
        for (var t : tokens) {
            log.info("[{}/mint]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }

        Assertions.assertEquals(2, tokens.size(),
                "the first MintBurn must complete the CIP-68 pair: user token + (100) reference");

        var userToken = tokens.stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no user token found"));
        var refToken = tokens.stream()
                .filter(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no (100) reference token found"));

        // The label was settled at GENESIS from initialMintableAmount (1_000_000 → fungible),
        // not from this mint's quantity, and is baked into the policy id.
        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "the genesis cap of 1000000 is fungible, so the user token must carry (333)");
        Assertions.assertEquals(new BigInteger("1000"), userToken.quantity());
        Assertions.assertEquals(BigInteger.ONE, refToken.quantity(),
                "CIP-68 allows exactly one reference token; quantity must be 1");
        Assertions.assertEquals(Cip68.referenceNameFor(userToken.assetNameHex()), refToken.assetNameHex(),
                "the (100) name must be exactly what referenceNameFor derives from the user token");

        // Output layout is load-bearing here: gs_output_index = 1 is hardcoded in the GS spend
        // redeemer, so the reference token must be APPENDED at 2, never inserted ahead of it.
        Assertions.assertEquals(0, userToken.outputIndex(), "user token must be output 0");
        Assertions.assertEquals(2, refToken.outputIndex(),
                "the reference token must be APPENDED at output 2 — inserting it earlier would"
                + " shift the GlobalState output away from the hardcoded gs_output_index = 1");

        var plb = boot.params().programmableLogicBaseParams().scriptHash();
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/mint", mintTx, userToken.outputIndex(),
                plb, stakeCredHex(BootstrapFixture.ALICE.baseAddress()));
        Cip68Evidence.assertProgrammableLogicBaseAddress(label + "/mint", mintTx, refToken.outputIndex(),
                plb, stakeCredHex(BootstrapFixture.ADMIN.baseAddress()));

        Cip68Evidence.assertDatumRoundTrip(label + "/mint", mintTx, refToken.outputIndex(), METADATA);

        int size = mintTx.serialize().length;
        log.info("[{}/mint] transaction.serialize().length = {} bytes", label, size);
        Assertions.assertTrue(size < 16384, "mint tx exceeds 16384 bytes: " + size);

        // ── H3: the reference-token lifecycle, exercised on the real chain ────────────────
        //
        // Submit the mint, so the (100) is genuinely on chain, and then drive the two follow-up
        // cases that were previously broken. Both are about the SAME distinction: "this token has
        // metadata stored" is not "this token still needs a reference token minted".
        chain.submit(mintTx);
        Assertions.assertTrue(chain.findUtxoByUnit(policyId + refToken.assetNameHex()).isPresent(),
                "precondition: the (100) must be on chain after submitting the first mint");

        // (1) A SECOND ORDINARY MINT. The caller sends no cip68Metadata — they are just minting
        //     more of the user token. The handler reloads the metadata from the registration row
        //     (it is stored forever), sees the (100) already exists, and must now simply mint the
        //     user token. Previously it rejected, advising the caller to "omit cip68Metadata" —
        //     a field they had never supplied and could not omit, so the token was unmintable.
        var secondMint = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                built.programmableTokenPolicyId(),
                registrations.get(built.programmableTokenPolicyId()).getSecurityAssetNameHex(),
                "500",
                BootstrapFixture.ALICE.baseAddress(),
                null);
        var secondResult = handler.buildMintTransaction(secondMint, boot.params());
        Assertions.assertTrue(secondResult.isSuccessful(),
                "a second ORDINARY mint on a CIP-68 token must succeed: " + secondResult.error());

        var secondTx = Transaction.deserialize(HexUtil.decodeHexString(secondResult.unsignedCborTx()));
        int secondEvaluated = chain.reportAndCheckRedeemers(label + "/mint2", secondTx);
        Assertions.assertTrue(secondEvaluated > 0, "no redeemer was evaluated on the second mint");

        var secondTokens = Cip68Evidence.tokensOfPolicy(secondTx, policyId);
        for (var t : secondTokens) {
            log.info("[{}/mint2]   out[{}] name={} label={} qty={}",
                    label, t.outputIndex(), t.assetNameHex(), t.label(), t.quantity());
        }
        Assertions.assertEquals(1, secondTokens.size(),
                "the second mint must mint ONLY the user token — a second (100) would break the pair");
        Assertions.assertFalse(
                secondTokens.stream().anyMatch(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label())),
                "duplicate (100) prevention: no reference token may be minted a second time");
        Assertions.assertEquals(new BigInteger("500"), secondTokens.getFirst().quantity());
        Assertions.assertTrue(secondTx.serialize().length < 16384,
                "second mint exceeds 16384 bytes: " + secondTx.serialize().length);

        // (2) AN EXPLICIT cip68Metadata REQUEST once the reference exists. This one IS a metadata
        //     update, which this endpoint cannot serve, so it must be refused — and the advice to
        //     drop the field is actionable here, because the caller did supply it.
        var updateAttempt = new org.cardanofoundation.cip113.model.MintTokenRequest(
                BootstrapFixture.ADMIN.baseAddress(),
                built.programmableTokenPolicyId(),
                registrations.get(built.programmableTokenPolicyId()).getSecurityAssetNameHex(),
                "500",
                BootstrapFixture.ALICE.baseAddress(),
                null,
                new Cip68Metadata("Renamed", "an attempted metadata rewrite", "NEW", 6, null, null));
        var updateResult = handler.buildMintTransaction(updateAttempt, boot.params());
        Assertions.assertFalse(updateResult.isSuccessful(),
                "minting a SECOND (100) must be refused");
        Assertions.assertNull(updateResult.unsignedCborTx(), "a refusal must not return a transaction");
        String updateError = String.valueOf(updateResult.error());
        log.info("[{}/mint-update] refused with: {}", label, updateError);
        // H3 reworded this: the persisted cip68ReferenceMinted flag is now authoritative, so the
        // refusal says "has already been issued" rather than asserting a live UTxO was observed.
        Assertions.assertTrue(updateError.contains("has already been issued"),
                "the error must say the reference token was already issued, got: " + updateError);
        // The advice must be possible to follow — the caller supplied the field, so "drop" is real.
        Assertions.assertTrue(updateError.contains("Drop cip68Metadata"),
                "the error must give actionable advice, got: " + updateError);

        // (3) The confirmed-issuance flag is one-time state: seeing it on chain sets it, and
        //     nothing clears it automatically.
        Assertions.assertTrue(registrations.get(built.programmableTokenPolicyId()).isCip68ReferenceMinted(),
                "observing the reference token on chain must mark cip68ReferenceMinted permanently");
    }

    /**
     * H3: a lookup that cannot be completed is not an absence, and the persisted flag is
     * authoritative once set.
     *
     * <p>The previous round replaced a single-address scan with a chain-wide one, which fixed the
     * misses — but {@code findUtxoByAsset} still collapsed every failure into
     * {@link java.util.Optional#empty()}, so a throttled or unreachable Blockfrost read as "the
     * reference token does not exist yet". Combined with the escape hatch that let an explicit
     * {@code cip68Metadata} override a locally-set flag, a stale index plus a resend minted a
     * second {@code (100)}.
     *
     * <p>The existing test above cannot see any of this: its lookup is an in-memory map over a
     * chain that is immediately consistent, so it never returns anything but a definite yes or a
     * definite no. This one drives the two states that map could not produce.
     */
    @Test
    public void securityTokenRefusesToGuessWhenTheReferenceLookupFails() throws Exception {
        var st = securityTokenChain(METADATA, 1_000_000L);
        var policyId = st.built().programmableTokenPolicyId();
        var assetName = st.registrations().get(policyId).getSecurityAssetNameHex();

        // ── (1) The lookup FAILS. Nothing is on chain and the flag is clear, so under the old
        //        "empty means absent" reading this would have happily minted the pair. UNKNOWN is
        //        not evidence of absence, and a duplicate (100) cannot be undone, so it refuses.
        Mockito.when(st.utxoProvider().assetPresence(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.UNKNOWN);

        var duringOutage = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "1000", METADATA), st.boot().params());
        Assertions.assertFalse(duringOutage.isSuccessful(),
                "an unresolvable reference-token lookup must NOT be read as 'not minted yet'");
        Assertions.assertNull(duringOutage.unsignedCborTx(), "a refusal must not return a transaction");
        String outageError = String.valueOf(duringOutage.error());
        log.info("[security-token/stale-lookup] refused with: {}", outageError);
        Assertions.assertTrue(outageError.contains("cannot determine"),
                "the error must say the question could not be answered, got: " + outageError);
        Assertions.assertFalse(st.registrations().get(policyId).isCip68ReferenceMinted(),
                "a refused build must not claim the reference mint");

        // ── (2) The CONTROL. Same fixture, same request, but the backend now answers ABSENT — a
        //        positive statement rather than a failure. The pair is minted. Without this, (1)
        //        would only prove "it refuses", not "it distinguishes".
        Mockito.when(st.utxoProvider().assetPresence(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.ABSENT);

        var confirmedAbsent = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "1000", METADATA), st.boot().params());
        Assertions.assertTrue(confirmedAbsent.isSuccessful(),
                "a CONFIRMED absence must still authorise the pair: " + confirmedAbsent.error());
        var pairTx = Transaction.deserialize(HexUtil.decodeHexString(confirmedAbsent.unsignedCborTx()));
        Assertions.assertEquals(2, Cip68Evidence.tokensOfPolicy(pairTx, policyId).size(),
                "the confirmed-absent build must mint the user token AND the (100)");
        Assertions.assertTrue(st.registrations().get(policyId).isCip68ReferenceMinted(),
                "handing out a reference-token mint must claim the flag");

        // ── (3) THE FLAG IS AUTHORITATIVE. The transaction from (2) was never submitted, so the
        //        chain still shows nothing — exactly the stale-index shape. An explicit
        //        cip68Metadata used to be treated here as "a deliberate retry" and completed the
        //        pair a second time. It must not: the flag says a signable reference mint is
        //        already out there, and no repeated API call may override that.
        Mockito.when(st.utxoProvider().assetPresence(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.ABSENT);

        var explicitRetry = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "1000", METADATA), st.boot().params());
        Assertions.assertFalse(explicitRetry.isSuccessful(),
                "an explicit metadata resend must NOT re-authorise a second (100) once the flag is set");
        Assertions.assertNull(explicitRetry.unsignedCborTx(), "a refusal must not return a transaction");
        String retryError = String.valueOf(explicitRetry.error());
        log.info("[security-token/flag-authoritative] refused with: {}", retryError);
        Assertions.assertTrue(retryError.contains("already been issued"),
                "the error must state the reference token is already issued, got: " + retryError);
        // The way out is an operator clearing the flag, not another request — say so.
        Assertions.assertTrue(retryError.contains("cip68ReferenceMinted"),
                "the error must name the flag an operator has to clear, got: " + retryError);

        // ── (4) An ORDINARY mint still works while the flag is set. The pair is someone else's
        //        problem; minting more of the user token is not blocked by any of the above.
        var ordinary = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "500", null), st.boot().params());
        Assertions.assertTrue(ordinary.isSuccessful(),
                "an ordinary mint must not be blocked by the reference-token flag: " + ordinary.error());
        var ordinaryTx = Transaction.deserialize(HexUtil.decodeHexString(ordinary.unsignedCborTx()));
        var ordinaryTokens = Cip68Evidence.tokensOfPolicy(ordinaryTx, policyId);
        Assertions.assertEquals(1, ordinaryTokens.size(),
                "an ordinary mint must emit ONLY the user token");
        Assertions.assertFalse(
                ordinaryTokens.stream().anyMatch(t -> Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label())),
                "no second (100) may be minted");
    }

    /**
     * H3, the concurrency half: the reference-token claim is a compare-and-set.
     *
     * <p>Two builds that both start from a snapshot showing {@code cip68ReferenceMinted == false}
     * both assemble a transaction minting the {@code (100)}. Only one may be handed back. The
     * conditional UPDATE is what decides; this drives it directly, because the offline harness is
     * single-threaded and a genuine race cannot be reproduced here.
     */
    @Test
    public void securityTokenReferenceMintIsClaimedByCompareAndSet() throws Exception {
        var st = securityTokenChain(METADATA, 1_000_000L);
        var policyId = st.built().programmableTokenPolicyId();
        var assetName = st.registrations().get(policyId).getSecurityAssetNameHex();

        Mockito.when(st.utxoProvider().assetPresence(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(org.cardanofoundation.cip113.service.UtxoProvider.AssetPresence.ABSENT);

        // A competitor claimed the mint while this build was being assembled: the CAS finds the
        // row already set and updates 0 rows. The registration snapshot this build read still
        // says false, so nothing earlier in the method can catch it — the CAS is the only guard.
        Mockito.when(st.registrationRepository().claimCip68ReferenceMint(Mockito.anyString()))
                .thenReturn(0);

        var loser = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "1000", METADATA), st.boot().params());
        Assertions.assertFalse(loser.isSuccessful(),
                "losing the compare-and-set must not yield a signable transaction");
        Assertions.assertNull(loser.unsignedCborTx(),
                "the loser must receive NO cbor — it is the only thing stopping a duplicate (100)");
        String loserError = String.valueOf(loser.error());
        log.info("[security-token/cas-loser] refused with: {}", loserError);
        Assertions.assertTrue(loserError.contains("already claimed"),
                "the error must name the race, got: " + loserError);

        // The control: win the CAS and the same build succeeds. Proves the refusal above is the
        // claim failing, not the build failing for some unrelated reason.
        Mockito.when(st.registrationRepository().claimCip68ReferenceMint(Mockito.anyString()))
                .thenReturn(1);
        var winner = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, assetName, "1000", METADATA), st.boot().params());
        Assertions.assertTrue(winner.isSuccessful(),
                "winning the compare-and-set must return the transaction: " + winner.error());
        Assertions.assertEquals(2,
                Cip68Evidence.tokensOfPolicy(
                        Transaction.deserialize(HexUtil.decodeHexString(winner.unsignedCborTx())),
                        policyId).size(),
                "the winner mints the pair");
    }

    /**
     * H4: a security-token registered with a cap of ONE still takes the {@code (333)} label.
     *
     * <p>The cap was read as a lifetime bound, which made {@code (222)} look honest. It is not.
     * {@code global_state.ak:229-245} computes {@code remaining = mintable_amount - minted_amount}
     * with a <em>signed</em> {@code minted_amount}, so a burn passes a negative and restores the
     * allowance — this handler mirrors exactly that arithmetic in {@code buildBurnTransaction}.
     * {@code mint 1 → burn 1 → mint 1} is therefore accepted and lifetime issuance under a (222)
     * label exceeds one, which is the one thing (222) promises cannot happen.
     *
     * <p>This is not a cosmetic relabel: the security asset name is a parameter of
     * {@code minting_logic_script} and {@code transfer_logic_script}, and {@code issuance_mint} is
     * parameterised by the former — so the label participates in the token policy id. The
     * transaction is evaluated, not merely built, so the assertion covers the whole derivation.
     */
    @Test
    public void securityTokenAtACapOfOneIsStillFungible() throws Exception {
        var st = securityTokenChain(METADATA, 1L);
        var policyId = st.built().programmableTokenPolicyId();
        var registeredName = st.registrations().get(policyId).getSecurityAssetNameHex();

        log.info("[security-token/cap-1] registered asset name = {} (label {})",
                registeredName, Cip68.readLabel(registeredName));

        Assertions.assertEquals(Integer.valueOf(Cip68.LABEL_FT), Cip68.readLabel(registeredName),
                "a cap of 1 bounds the amount OUTSTANDING, not lifetime issuance — a burn restores "
                + "the allowance, so the token is fungible and must carry (333)");
        Assertions.assertEquals(Cip68.labeledAssetName(Cip68.LABEL_FT, BASE_ASSET_NAME_HEX),
                registeredName,
                "the on-chain name must be exactly the (333)-labelled base name");

        // And the label really did reach the chain, not just the database row: mint one unit and
        // read the asset name back off the evaluated transaction.
        var mint = st.handler().buildMintTransaction(
                mintTokenRequest(policyId, registeredName, "1", METADATA), st.boot().params());
        Assertions.assertTrue(mint.isSuccessful(), "cap-1 mint failed: " + mint.error());

        var mintTx = Transaction.deserialize(HexUtil.decodeHexString(mint.unsignedCborTx()));
        Assertions.assertTrue(st.chain().reportAndCheckRedeemers("security-token/cap-1", mintTx) > 0,
                "no redeemer was evaluated on the cap-1 mint");

        var userToken = Cip68Evidence.tokensOfPolicy(mintTx, policyId).stream()
                .filter(t -> !Integer.valueOf(Cip68.LABEL_REFERENCE).equals(t.label()))
                .findFirst().orElseThrow(() -> new AssertionError("no user token minted"));
        Assertions.assertEquals(Cip68.LABEL_FT, userToken.label().intValue(),
                "the minted user token must carry (333) even though exactly one unit was minted "
                + "against a cap of one");
        Assertions.assertEquals(BigInteger.ONE, userToken.quantity());
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
                org.cardanofoundation.cip113.service.substandard.context.FreezeAndSeizeContext.builder()
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
                org.cardanofoundation.cip113.service.substandard.context.FreezeAndSeizeContext.builder()
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
                org.cardanofoundation.cip113.service.substandard.context.FreezeAndSeizeContext.builder()
                        .blacklistNodePolicyId(BLACKLIST_NODE_POLICY_ID)
                        .issuerAdminPkh(adminPkh)
                        .build());

        var result = fes.handler().buildSeizeTransaction(
                new org.cardanofoundation.cip113.service.substandard.capabilities.Seizeable.SeizeRequest(
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
     * Drive the real {@link org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler}
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
                attempt.boot().params().programmableLogicBaseParams().scriptHash(),
                attempt.handler(), attempt.boot().params());
    }

    private record FesAttempt(
            OfflineChain chain,
            BootstrapFixture.Bootstrapped boot,
            org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler handler,
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
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));

        var substandardService = HandlerFixtures.substandardService();

        var handler = new org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                Mockito.mock(org.cardanofoundation.cip113.model.onchain.siezeandfreeze.blacklist.BlacklistNodeParser.class),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                new AccountService(utxoProvider),
                substandardService,
                HandlerFixtures.protocolScriptBuilderService(),
                new org.cardanofoundation.cip113.service.FreezeAndSeizeScriptBuilderService(substandardService),
                new org.cardanofoundation.cip113.service.LinkedListService(utxoProvider),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(
                        Mockito.mock(com.bloxbean.cardano.client.backend.api.UtxoService.class)),
                Mockito.mock(org.cardanofoundation.cip113.repository.FreezeAndSeizeTokenRegistrationRepository.class),
                blacklistInitRepository(initCip68Enabled),
                Mockito.mock(ProgrammableTokenRegistryRepository.class),
                Mockito.mock(CustomStakeRegistrationRepository.class),
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                        // Deliberately null, not a mock: isStakeAddressRegistered never touches the
                        // builder, and mocking QuickTxBuilder instruments it for every test in the
                        // JVM under the inline mock maker — which silently broke the security-token
                        // burn's real evaluator whenever this class ran as a whole.
                        null,
                        Mockito.mock(AccountService.class),
                        Mockito.mock(CustomStakeRegistrationRepository.class),
                        // Nothing pre-recorded: these tests exercise the ledger and
                        // indexed-certificate sources, not the learned one.
                        Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class)),
                utxoProvider,
                Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class));

        var adminPkh = new Address(BootstrapFixture.ADMIN.baseAddress())
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElseThrow();

        var request = org.cardanofoundation.cip113.model.FreezeAndSeizeRegisterRequest.builder()
                .substandardId("freeze-and-seize")
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
                             org.cardanofoundation.cip113.service.substandard.FreezeAndSeizeHandler handler,
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

    // ------------------------------------------------------------ security-token fixtures

    /**
     * A security-token protocol whose genesis → AddPowerUser → registration chain has been built,
     * evaluated and virtually submitted, ready for a mint.
     *
     * <p>{@code utxoProvider} and {@code registrationRepository} are exposed because the H3 cases
     * re-stub them mid-test: the whole finding is about what happens when the chain lookup gives a
     * different answer from the one an immediately-consistent in-memory chain can produce.
     */
    private record SecurityTokenChain(
            OfflineChain chain,
            BootstrapFixture.Bootstrapped boot,
            org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler handler,
            java.util.Map<String, org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity>
                    registrations,
            org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler.ChainBuildResult
                    built,
            org.cardanofoundation.cip113.service.UtxoProvider utxoProvider,
            org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository
                    registrationRepository) {
    }

    /**
     * A {@link org.cardanofoundation.cip113.service.UtxoProvider} backed by the offline chain.
     *
     * <p>{@code assetPresence} is answered from the same chain as {@code findUtxoByAsset}, so the
     * default behaviour matches production on a healthy backend: a hit is {@code PRESENT}, a miss
     * is {@code ABSENT}. It never returns {@code UNKNOWN} on its own — an in-memory chain has no
     * failure mode — which is precisely why the H3 test re-stubs it.
     */
    private static org.cardanofoundation.cip113.service.UtxoProvider securityTokenUtxoProvider(
            OfflineChain chain) {
        var utxoProvider = Mockito.mock(org.cardanofoundation.cip113.service.UtxoProvider.class);
        Mockito.when(utxoProvider.findUtxo(Mockito.anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> chain.utxoSupplier().getTxOutput(inv.getArgument(0), inv.getArgument(1)));
        Mockito.when(utxoProvider.findUtxos(Mockito.anyString()))
                .thenAnswer(inv -> chain.utxosAt(inv.getArgument(0)));
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
     * An in-memory {@code SecurityTokenRegistrationRepository}.
     *
     * <p>{@code claimCip68ReferenceMint} implements the real compare-and-set semantics against the
     * map — set the flag and return 1 only if it was clear, otherwise return 0 and change nothing.
     * A mock that returned Mockito's default {@code 0} would make every reference mint look like a
     * lost race; one that always returned 1 would never exercise the guard.
     */
    private static org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository
            securityTokenRegistrationRepository(
                    java.util.Map<String,
                            org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity> registrations) {
        var repo = Mockito.mock(
                org.cardanofoundation.cip113.repository.SecurityTokenRegistrationRepository.class);
        Mockito.when(repo.save(Mockito.any())).thenAnswer(inv -> {
            var entity = (org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity)
                    inv.getArgument(0);
            registrations.put(entity.getProgrammableTokenPolicyId(), entity);
            return entity;
        });
        Mockito.when(repo.findByProgrammableTokenPolicyId(Mockito.anyString()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(registrations.get((String) inv.getArgument(0))));
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
     * Build, evaluate and virtually submit the whole security-token registration chain.
     *
     * @param metadata             CIP-68 metadata, or null for a non-CIP-68 registration
     * @param initialMintableAmount the GlobalState cap — note this does NOT choose the label
     */
    private SecurityTokenChain securityTokenChain(Cip68Metadata metadata, long initialMintableAmount)
            throws Exception {
        return securityTokenChain(metadata, initialMintableAmount, "0",
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
    private SecurityTokenChain securityTokenChain(Cip68Metadata metadata, long initialMintableAmount,
                                                  String initialMintQuantity, String recipientAddress)
            throws Exception {
        var chain = new OfflineChain();
        var boot = BootstrapFixture.bootstrap(chain);
        var label = "security-token";

        var registrations = new java.util.HashMap<String,
                org.cardanofoundation.cip113.entity.SecurityTokenRegistrationEntity>();

        var utxoProvider = securityTokenUtxoProvider(chain);
        var registrationRepository = securityTokenRegistrationRepository(registrations);

        // The chain orchestrator builds genesis → AddPowerUser → registration back-to-back with
        // nothing submitted in between, handing each transaction's outputs to this supplier so
        // the next one can spend them. Production wires the QuickTxBuilder over the very same
        // object, so the test must too.
        var hybridUtxoSupplier =
                new org.cardanofoundation.cip113.service.HybridUtxoSupplier(chain.utxoService());

        var handler = new org.cardanofoundation.cip113.service.substandard.SecurityTokenSubstandardHandler(
                new org.cardanofoundation.cip113.service.SecurityTokenScriptBuilderService(
                        HandlerFixtures.substandardService()),
                HandlerFixtures.protocolScriptBuilderService(),
                Mockito.mock(org.cardanofoundation.cip113.service.SecurityTokenAllowlistService.class),
                registrationRepository,
                Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenDenylistEntryRepository.class),
                Mockito.mock(org.cardanofoundation.cip113.repository.SecurityTokenPowerUserRepository.class),
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
                Mockito.mock(CustomStakeRegistrationRepository.class),
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

        var registerRequest = org.cardanofoundation.cip113.model.SecurityTokenRegisterRequest.builder()
                .substandardId("security-token")
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
                .requiresReceiverKyc(false)
                .requiresSenderKyc(false)
                .build();

        var chainResult = handler.buildFullRegistrationChain(registerRequest, boot.params());
        Assertions.assertTrue(chainResult.isSuccessful(),
                "security-token registration chain build failed: " + chainResult.error());

        var built = chainResult.metadata();
        log.info("[{}] chain built: globalStatePolicy={} progTokenPolicy={} denylistPolicy={}",
                label, built.globalStatePolicyId(), built.programmableTokenPolicyId(),
                built.denylistPolicyId());

        // Virtually submit the chain in order, so each tx sees the previous one's outputs.
        var stages = new java.util.LinkedHashMap<String, String>();
        stages.put("genesis", built.genesisCborHex());
        stages.put("addPowerUser", built.addPowerUserCborHex());
        // Present only on the mint path, and it must be submitted BEFORE the registration —
        // the registration reads its outputs as reference inputs.
        if (built.publishScriptsCborHex() != null) {
            stages.put("publishScripts", built.publishScriptsCborHex());
        }
        stages.put("registration", built.registrationCborHex());
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
            int evaluated = chain.reportAndCheckRedeemers(label + "/" + stage.getKey(), stageTx);
            log.info("[{}/{}] {} redeemer(s) genuinely evaluated, size={} bytes",
                    label, stage.getKey(), evaluated, stageTx.serialize().length);

            // registerTransferLogic's ONLY redeemer is the Cert one injected in postBalanceTx
            // with hand-picked ex-units, so it legitimately has nothing the evaluator could
            // measure. Every other stage must have really run its scripts.
            if (!"registerTransferLogic".equals(stage.getKey())
                    && !"registerThirdPartyTransferLogic".equals(stage.getKey())
                    && !"publishScripts".equals(stage.getKey())) {
                Assertions.assertTrue(evaluated > 0,
                        stage.getKey() + " produced no genuinely evaluated redeemer");
            }
            Assertions.assertTrue(stageTx.serialize().length < 16384,
                    stage.getKey() + " exceeds 16384 bytes");
            chain.submit(stageTx);
        }

        return new SecurityTokenChain(chain, boot, handler, registrations, built,
                utxoProvider, registrationRepository);
    }

    /** A security-token mint request against the offline fixture's admin/recipient pair. */
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
     * Bootstrap a fresh offline protocol, then drive the real {@link DummySubstandardHandler}
     * through one registration on top of it.
     *
     * <p>A fresh chain per call is deliberate: the dummy substandard's issuance policy does NOT
     * depend on the asset name, so two registrations on one chain would collide on the handler's
     * "already registered" guard.
     */
    /** The real {@link DummySubstandardHandler}, spliced onto an offline chain. */
    private static DummySubstandardHandler dummyHandler(OfflineChain chain,
                                                        BootstrapFixture.Bootstrapped boot,
                                                        ProgrammableTokenRegistryRepository registry)
            throws Exception {
        var registrySpendHash = HexUtil.encodeHexString(boot.registrySpend().getScriptHash());
        var registryAddress = boot.registryOriginUtxo().getAddress();
        return new DummySubstandardHandler(
                HandlerFixtures.OBJECT_MAPPER,
                HandlerFixtures.NETWORK,
                HandlerFixtures.utxoRepository(chain, registryAddress, registrySpendHash),
                new RegistryNodeParser(HandlerFixtures.OBJECT_MAPPER),
                Mockito.mock(AccountService.class),
                HandlerFixtures.substandardService(),
                HandlerFixtures.protocolScriptBuilderService(),
                chain.quickTxBuilder(),
                chain.protocolParamsSupplier(),
                registry,
                Mockito.mock(CustomStakeRegistrationRepository.class),
                new org.cardanofoundation.cip113.service.ScriptRegistrationService(
                        Mockito.mock(com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService.class),
                        null,
                        Mockito.mock(AccountService.class),
                        Mockito.mock(CustomStakeRegistrationRepository.class),
                        // Nothing pre-recorded: these tests exercise the ledger and
                        // indexed-certificate sources, not the learned one.
                        Mockito.mock(org.cardanofoundation.cip113.repository.KnownScriptRegistrationRepository.class)));
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
        private final DummySubstandardHandler handler;
        private final String policyId;
        private final java.util.Map<String, ProgrammableTokenRegistryEntity> rows;
        private String registeredAssetName;

        DummyMintFixture(OfflineChain chain, BootstrapFixture.Bootstrapped boot,
                         DummySubstandardHandler handler, String policyId, String registeredAssetName,
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
                    .substandardId("dummy")
                    .assetName(assetName)
                    .build());
        }

        DummySubstandardHandler handler() {
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
                        .substandardId("dummy")
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
                        .substandardId("dummy")
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
                .substandardId("dummy")
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
                boot.params().programmableLogicBaseParams().scriptHash());
    }
}
